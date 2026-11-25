package com.influencermatch.backend.recommendation.research.dgp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.influencermatch.backend.recommendation.research.contracts.HistoricalCollaborationEvidence;
import com.influencermatch.backend.recommendation.research.contracts.OracleGroundTruthRecord;
import com.influencermatch.backend.recommendation.research.contracts.ResearchEnums.MetricAvailability;
import com.influencermatch.backend.recommendation.research.dgp.DgpConfig.DatasetSplit;
import com.influencermatch.backend.recommendation.research.dgp.DgpConfig.DgpRegime;
import com.influencermatch.backend.recommendation.research.dgp.DgpConfig.MissingnessIntensity;
import com.influencermatch.backend.recommendation.research.dgp.DgpConfig.MissingnessMechanism;
import com.influencermatch.backend.recommendation.research.validation.ResearchContractValidator;
import com.influencermatch.backend.recommendation.research.validation.ResearchJsonMapper;
import com.influencermatch.backend.recommendation.research.validation.ScorerInputBoundary;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class SyntheticDgpAcceptanceTest {

  private static final Instant T_AS_OF = Instant.parse("2026-11-25T00:00:00Z");
  private final SyntheticDgpGenerator generator = new SyntheticDgpGenerator();

  private DgpConfig config(DgpRegime regime, MissingnessMechanism mech, MissingnessIntensity intensity,
      DatasetSplit split, long seed, int n) {
    return new DgpConfig(DgpConfig.DGP_VERSION, regime, mech, intensity, split, seed, n, T_AS_OF);
  }

  @Test
  void test1_oracleIsIndependentOfScoringEngine() {
    var b1 = generator.generate(config(DgpRegime.DGP_A, MissingnessMechanism.MCAR, MissingnessIntensity.LOW, DatasetSplit.DEV, 42L, 30));
    var b2 = generator.generate(config(DgpRegime.DGP_A, MissingnessMechanism.MCAR, MissingnessIntensity.HIGH, DatasetSplit.DEV, 42L, 30));
    // Changing missingness intensity does not alter oracle expected_future_utility
    assertThat(b1.oracleGroundTruth()).extracting(OracleGroundTruthRecord::syntheticExpectedFutureUtility)
        .containsExactlyElementsOf(b2.oracleGroundTruth().stream()
            .map(OracleGroundTruthRecord::syntheticExpectedFutureUtility).toList());
  }

  @Test
  void test2And3_dgpAIsPredictiveWhileDgpBIsNegativeControl() {
    var dgpA = generator.generate(config(DgpRegime.DGP_A, MissingnessMechanism.MCAR, MissingnessIntensity.LOW, DatasetSplit.TRAIN, 7L, 120));
    var dgpB = generator.generate(config(DgpRegime.DGP_B, MissingnessMechanism.MCAR, MissingnessIntensity.LOW, DatasetSplit.TRAIN, 7L, 120));

    double corrA = pearsonHistoryVsOracle(dgpA);
    double corrB = pearsonHistoryVsOracle(dgpB);

    assertThat(corrA).isGreaterThan(0.45);
    assertThat(corrB).isLessThan(0.25);
    assertThat(corrA).isGreaterThan(corrB + 0.25);
  }

  @Test
  void test4_observedZeroDiffersFromMissingAndPartialZero() {
    var bundle = generator.generate(config(DgpRegime.DGP_A, MissingnessMechanism.MCAR, MissingnessIntensity.HIGH, DatasetSplit.DEV, 11L, 45));
    var availabilities = bundle.scorerSnapshot().creators().stream()
        .map(c -> c.avgViews().availability())
        .collect(Collectors.toSet());

    assertThat(availabilities).contains(
        MetricAvailability.OBSERVED_ZERO,
        MetricAvailability.PARTIAL_OBSERVATION,
        MetricAvailability.MISSING,
        MetricAvailability.INVALID,
        MetricAvailability.NOT_APPLICABLE);
  }

  @Test
  void test5_mcarVsMnarDependenceOnLatentUtility() {
    var mcar = generator.generate(config(DgpRegime.DGP_A, MissingnessMechanism.MCAR, MissingnessIntensity.HIGH, DatasetSplit.TEST, 99L, 150));
    var mnar = generator.generate(config(DgpRegime.DGP_A, MissingnessMechanism.MNAR, MissingnessIntensity.HIGH, DatasetSplit.TEST, 99L, 150));

    double mcarDiff = meanOracleWhenMissingMinusObserved(mcar);
    double mnarDiff = meanOracleWhenMissingMinusObserved(mnar);

    // Under MNAR, lower-quality creators are dropped more often -> missing creators have lower mean oracle utility
    assertThat(Math.abs(mcarDiff)).isLessThan(0.08);
    assertThat(mnarDiff).isLessThan(-0.04);
  }

  @Test
  void test6_exposureSelectionDiffersFromEvidenceMissingness() {
    var bundle = generator.generate(config(DgpRegime.DGP_A, MissingnessMechanism.MCAR, MissingnessIntensity.HIGH, DatasetSplit.DEV, 55L, 60));
    long exposedMissing = 0;
    long unexposedObserved = 0;
    Map<UUID, OracleGroundTruthRecord> oracleById = bundle.oracleGroundTruth().stream()
        .collect(Collectors.toMap(OracleGroundTruthRecord::creatorId, o -> o));

    for (var c : bundle.scorerSnapshot().creators()) {
      boolean exposed = Boolean.TRUE.equals(oracleById.get(c.creatorId()).exposedToBrand());
      boolean erMissing = c.engagementRate().availability() == MetricAvailability.MISSING;
      if (exposed && erMissing) exposedMissing++;
      if (!exposed && !erMissing) unexposedObserved++;
    }
    assertThat(exposedMissing).isGreaterThan(0);
    assertThat(unexposedObserved).isGreaterThan(0);
  }

  @Test
  void test7_futureRecordsNeverLeakIntoScoringSnapshot() {
    var bundle = generator.generate(config(DgpRegime.DGP_A, MissingnessMechanism.MCAR, MissingnessIntensity.LOW, DatasetSplit.DEV, 12L, 30));

    assertThat(bundle.allHistoricalIncludingFuture().size())
        .isGreaterThan(bundle.scorerSnapshot().historicalCollaborations().size());
    assertThat(bundle.scorerSnapshot().historicalCollaborations())
        .allMatch(h -> !h.knownAt().isAfter(T_AS_OF) && !h.eventTime().isAfter(T_AS_OF));

    var replayed = ResearchContractValidator.replayHistoricalAt(bundle.allHistoricalIncludingFuture(), T_AS_OF);
    assertThat(replayed).hasSameSizeAs(bundle.scorerSnapshot().historicalCollaborations());
  }

  @Test
  void test8_m1AndM4HaveBothAboveAndBelowThresholdCases() {
    var bundle = generator.generate(config(DgpRegime.DGP_A, MissingnessMechanism.MCAR, MissingnessIntensity.LOW, DatasetSplit.DEV, 19L, 50));
    Map<UUID, List<HistoricalCollaborationEvidence>> byCreator = bundle.scorerSnapshot().historicalCollaborations().stream()
        .collect(Collectors.groupingBy(HistoricalCollaborationEvidence::creatorId));

    long passM1 = byCreator.values().stream()
        .filter(list -> list.stream().filter(h -> "COMPLETED".equals(h.status()) && h.rObservation().value() != null).count() >= 2)
        .count();
    long belowM1 = bundle.scorerSnapshot().creators().size() - passM1;
    long withReview = bundle.scorerSnapshot().historicalCollaborations().stream()
        .filter(h -> h.m4ReviewScore().value() != null).count();
    long missingReview = bundle.scorerSnapshot().historicalCollaborations().stream()
        .filter(h -> h.m4ReviewScore().value() == null).count();

    assertThat(passM1).isGreaterThan(0);
    assertThat(belowM1).isGreaterThan(0);
    assertThat(withReview).isGreaterThan(0);
    assertThat(missingReview).isGreaterThan(0);
  }

  @Test
  void test9And10_sameSeedIsDeterministicAndDifferentSeedsOrSplitsProduceIndependentWorlds() {
    var cfg1 = config(DgpRegime.DGP_A, MissingnessMechanism.MCAR, MissingnessIntensity.LOW, DatasetSplit.TRAIN, 123L, 25);
    var cfg2 = config(DgpRegime.DGP_A, MissingnessMechanism.MCAR, MissingnessIntensity.LOW, DatasetSplit.TRAIN, 123L, 25);
    var cfgOtherSeed = config(DgpRegime.DGP_A, MissingnessMechanism.MCAR, MissingnessIntensity.LOW, DatasetSplit.TRAIN, 456L, 25);
    var cfgTestSplit = config(DgpRegime.DGP_A, MissingnessMechanism.MCAR, MissingnessIntensity.LOW, DatasetSplit.TEST, 123L, 25);

    var b1 = generator.generate(cfg1);
    var b2 = generator.generate(cfg2);
    var bOther = generator.generate(cfgOtherSeed);
    var bSplit = generator.generate(cfgTestSplit);

    assertThat(ResearchJsonMapper.toDeterministicJson(b1.scorerSnapshot()))
        .isEqualTo(ResearchJsonMapper.toDeterministicJson(b2.scorerSnapshot()));
    assertThat(b1.manifest().dataHash()).isEqualTo(b2.manifest().dataHash());
    assertThat(b1.manifest().dataHash()).isNotEqualTo(bOther.manifest().dataHash());
    assertThat(b1.manifest().dataHash()).isNotEqualTo(bSplit.manifest().dataHash());
  }

  @Test
  void test11_scorerCannotAccessOracleFields() {
    var bundle = generator.generate(config(DgpRegime.DGP_A, MissingnessMechanism.MCAR, MissingnessIntensity.LOW, DatasetSplit.DEV, 5L, 10));
    String snapshotJson = ResearchJsonMapper.toDeterministicJson(bundle.scorerSnapshot());
    assertThat(snapshotJson).doesNotContain("syntheticExpectedFutureUtility", "oracleGroundTruth", "exposedToBrand", "selectedByBrand");

    @SuppressWarnings("unchecked")
    Map<String, Object> tampered = ResearchJsonMapper.fromJson(snapshotJson, Map.class);
    tampered.put("syntheticExpectedFutureUtility", 0.99);
    assertThatThrownBy(() -> ScorerInputBoundary.acceptForScorer(tampered))
        .isInstanceOf(SecurityException.class)
        .hasMessageContaining("ORACLE_BOUNDARY_VIOLATION");
  }

  private double pearsonHistoryVsOracle(SyntheticDgpGenerator.GeneratedWorldBundle bundle) {
    Map<UUID, Double> oracleMap = bundle.oracleGroundTruth().stream()
        .collect(Collectors.toMap(OracleGroundTruthRecord::creatorId, OracleGroundTruthRecord::syntheticExpectedFutureUtility));
    Map<UUID, Double> meanHistByCreator = bundle.scorerSnapshot().historicalCollaborations().stream()
        .filter(h -> h.rObservation().value() != null)
        .collect(Collectors.groupingBy(
            HistoricalCollaborationEvidence::creatorId,
            Collectors.averagingDouble(h -> h.rObservation().value())));

    List<UUID> ids = meanHistByCreator.keySet().stream().toList();
    double meanX = ids.stream().mapToDouble(meanHistByCreator::get).average().orElse(0.0);
    double meanY = ids.stream().mapToDouble(oracleMap::get).average().orElse(0.0);
    double num = 0.0, denX = 0.0, denY = 0.0;
    for (UUID id : ids) {
      double dx = meanHistByCreator.get(id) - meanX;
      double dy = oracleMap.get(id) - meanY;
      num += dx * dy;
      denX += dx * dx;
      denY += dy * dy;
    }
    return num / Math.sqrt(denX * denY);
  }

  private double meanOracleWhenMissingMinusObserved(SyntheticDgpGenerator.GeneratedWorldBundle bundle) {
    Map<UUID, Double> oracleMap = bundle.oracleGroundTruth().stream()
        .collect(Collectors.toMap(OracleGroundTruthRecord::creatorId, OracleGroundTruthRecord::syntheticExpectedFutureUtility));
    double missingMean = bundle.scorerSnapshot().creators().stream()
        .filter(c -> c.engagementRate().availability() == MetricAvailability.MISSING)
        .mapToDouble(c -> oracleMap.get(c.creatorId())).average().orElse(0.0);
    double observedMean = bundle.scorerSnapshot().creators().stream()
        .filter(c -> c.engagementRate().availability() != MetricAvailability.MISSING)
        .mapToDouble(c -> oracleMap.get(c.creatorId())).average().orElse(0.0);
    return missingMean - observedMean;
  }
}
