package com.influencermatch.backend.recommendation.research;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.influencermatch.backend.recommendation.research.adapters.LegacyResearchAdapter;
import com.influencermatch.backend.recommendation.research.contracts.CampaignCreatorSnapshot;
import com.influencermatch.backend.recommendation.research.contracts.CandidateUniverse;
import com.influencermatch.backend.recommendation.research.contracts.CandidateUniverse.CandidateEntry;
import com.influencermatch.backend.recommendation.research.contracts.DerivedFeature;
import com.influencermatch.backend.recommendation.research.contracts.ExperimentRunManifest;
import com.influencermatch.backend.recommendation.research.contracts.HistoricalCollaborationEvidence;
import com.influencermatch.backend.recommendation.research.contracts.MetricObservation;
import com.influencermatch.backend.recommendation.research.contracts.ObservedEvidenceRecord;
import com.influencermatch.backend.recommendation.research.contracts.ObservedEvidenceRecord.PostObservation;
import com.influencermatch.backend.recommendation.research.contracts.OracleGroundTruthRecord;
import com.influencermatch.backend.recommendation.research.contracts.ResearchEnums.DataOrigin;
import com.influencermatch.backend.recommendation.research.contracts.ResearchEnums.HardEligibilityStatus;
import com.influencermatch.backend.recommendation.research.contracts.ResearchEnums.MeasurementCompleteness;
import com.influencermatch.backend.recommendation.research.contracts.ResearchEnums.MetricAvailability;
import com.influencermatch.backend.recommendation.research.contracts.ResearchEnums.MetricUnit;
import com.influencermatch.backend.recommendation.research.contracts.ResearchEnums.OracleGradeSource;
import com.influencermatch.backend.recommendation.research.validation.ResearchContractValidator;
import com.influencermatch.backend.recommendation.research.validation.ResearchJsonMapper;
import com.influencermatch.backend.recommendation.research.validation.ScorerInputBoundary;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ResearchDataContractsAcceptanceTest {

  private static final Instant T0 = Instant.parse("2026-11-20T10:00:00Z");
  private static final Instant T1 = Instant.parse("2026-11-20T10:05:00Z");
  private static final Instant T_AS_OF = Instant.parse("2026-11-21T00:00:00Z");
  private static final Instant T_FUTURE = Instant.parse("2026-11-22T00:00:00Z");

  @Test
  void test1_observedZeroDiffersFromMissing() {
    var observedZero = MetricObservation.observed(0.0, MetricUnit.COUNT, 3, 3, "TIKTOK_CRAWLER", T0, T1);
    var missing = MetricObservation.missing(MetricUnit.COUNT, 3, "TIKTOK_CRAWLER", T0, T1);

    assertThat(observedZero.availability()).isEqualTo(MetricAvailability.OBSERVED_ZERO);
    assertThat(observedZero.value()).isZero();
    assertThat(missing.availability()).isEqualTo(MetricAvailability.MISSING);
    assertThat(missing.value()).isNull();
  }

  @Test
  void test2_partialCoverageIsStoredSeparately() {
    var partial = MetricObservation.observed(150.0, MetricUnit.COUNT, 2, 3, "TIKTOK_CRAWLER", T0, T1);

    assertThat(partial.availability()).isEqualTo(MetricAvailability.PARTIAL_OBSERVATION);
    assertThat(partial.measurementCompleteness()).isEqualTo(MeasurementCompleteness.PARTIAL);
    assertThat(partial.observedCount()).isEqualTo(2);
    assertThat(partial.totalCount()).isEqualTo(3);
  }

  @Test
  void test3_invalidIsNotSilentlyCoercedToMissingAndImputedRejectedInObservedEvidence() {
    var invalid = MetricObservation.invalid(MetricUnit.COUNT, "-50", 3, "TIKTOK_CRAWLER", T0, T1);
    assertThat(invalid.availability()).isEqualTo(MetricAvailability.INVALID);
    assertThat(invalid.rawInvalidValue()).isEqualTo("-50");
    assertThat(invalid.value()).isNull();

    var imputed = new MetricObservation(0.5, MetricUnit.STABILITY_INDEX, MetricAvailability.IMPUTED,
        MeasurementCompleteness.NONE, 0, 3, "IMPUTER", T0, T1, null);
    var record = sampleObservedRecord(DataOrigin.REAL, T1,
        MetricObservation.observed(0.05, MetricUnit.DECIMAL_RATIO, 3, 3, "TIKTOK_CRAWLER", T0, T1),
        imputed);
    assertThatThrownBy(() -> ResearchContractValidator.validateObservedEvidence(record))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("IMPUTED_IN_OBSERVED_EVIDENCE");

    // DerivedFeature is allowed to represent imputed features separately
    var derived = new DerivedFeature("stab_imputed", 0.0, MetricUnit.STABILITY_INDEX,
        MetricAvailability.IMPUTED, true, "POLICY_FLOOR", List.of("post_window_metrics"));
    assertThat(derived.isImputed()).isTrue();
  }

  @Test
  void test4_unknownUnitIsRejected() {
    String badJson = """
        {
          "availability": "OBSERVED",
          "knownAt": "2026-11-20T10:05:00Z",
          "measurementCompleteness": "FULL",
          "observedAt": "2026-11-20T10:00:00Z",
          "observedCount": 3,
          "provenance": "TIKTOK_CRAWLER",
          "totalCount": 3,
          "unit": "UNKNOWN_UNIT_XYZ",
          "value": 0.05
        }
        """;
    assertThatThrownBy(() -> ResearchJsonMapper.fromJson(badJson, MetricObservation.class))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void test5_erPercentageCannotEnterCanonicalRatioSchemaWithoutConversion() {
    var percentEr = MetricObservation.observed(5.0, MetricUnit.PERCENT, 3, 3, "PYTHON_AI", T0, T1);
    var record = sampleObservedRecord(DataOrigin.REAL, T1, percentEr,
        MetricObservation.observed(1000.0, MetricUnit.COUNT, 3, 3, "TIKTOK_CRAWLER", T0, T1));

    assertThatThrownBy(() -> ResearchContractValidator.validateObservedEvidence(record))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("UNIT_CONTRACT_VIOLATION");
  }

  @Test
  void test6_oracleFieldsCannotEnterScorerInputs() {
    Map<String, Object> rawWithOracle = new LinkedHashMap<>();
    rawWithOracle.put("schemaVersion", CampaignCreatorSnapshot.SCHEMA_VERSION);
    rawWithOracle.put("snapshotId", "snap-1");
    rawWithOracle.put("campaignId", UUID.randomUUID().toString());
    rawWithOracle.put("scoringAsOf", T_AS_OF.toString());
    rawWithOracle.put("dataOrigin", "SYN");
    rawWithOracle.put("syntheticExpectedFutureUtility", 0.92);

    assertThatThrownBy(() -> ScorerInputBoundary.acceptForScorer(rawWithOracle))
        .isInstanceOf(SecurityException.class)
        .hasMessageContaining("ORACLE_BOUNDARY_VIOLATION");

    var oracleRecord = new OracleGroundTruthRecord(
        OracleGroundTruthRecord.SCHEMA_VERSION,
        UUID.randomUUID(), UUID.randomUUID(), DataOrigin.SYN,
        OracleGradeSource.SYNTHETIC_EXPECTED_UTILITY, T0,
        0.85, null, null, true, false, "oracle only");
    assertThat(oracleRecord.labelSource()).isEqualTo(OracleGradeSource.SYNTHETIC_EXPECTED_UTILITY);
  }

  @Test
  void test7_futureRecordsAndFutureRevisionsAreRejectedOrExcludedInReplay() {
    var futureCreator = sampleObservedRecord(DataOrigin.REAL, T_FUTURE,
        MetricObservation.observed(0.05, MetricUnit.DECIMAL_RATIO, 3, 3, "TIKTOK_CRAWLER", T0, T_FUTURE),
        MetricObservation.observed(1000.0, MetricUnit.COUNT, 3, 3, "TIKTOK_CRAWLER", T0, T_FUTURE));

    var badSnapshot = new CampaignCreatorSnapshot(
        CampaignCreatorSnapshot.SCHEMA_VERSION, "snap-future", UUID.randomUUID(),
        "AWARENESS", List.of("TIKTOK"), List.of("Beauty"), 1000L, 100000L, 500.0,
        T_AS_OF, DataOrigin.REAL, List.of(futureCreator), List.of());

    assertThatThrownBy(() -> ScorerInputBoundary.acceptForScorer(badSnapshot))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("TEMPORAL_LEAKAGE");

    // Historical replay excludes revision 2 known after scoringAsOf, keeping revision 1
    UUID collabId = UUID.randomUUID();
    UUID creatorId = UUID.randomUUID();
    var rev1 = new HistoricalCollaborationEvidence(
        HistoricalCollaborationEvidence.SCHEMA_VERSION, collabId, creatorId, null,
        UUID.randomUUID(), UUID.randomUUID(), DataOrigin.REAL, T0, T1, 1, "COMPLETED",
        MetricObservation.observed(1.0, MetricUnit.DECIMAL_RATIO, 1, 1, "COLLAB_DB", T0, T1),
        null, null, null);
    var rev2Future = new HistoricalCollaborationEvidence(
        HistoricalCollaborationEvidence.SCHEMA_VERSION, collabId, creatorId, null,
        UUID.randomUUID(), UUID.randomUUID(), DataOrigin.REAL, T0, T_FUTURE, 2, "DISPUTED",
        MetricObservation.observed(0.0, MetricUnit.DECIMAL_RATIO, 1, 1, "COLLAB_DB", T0, T_FUTURE),
        null, null, null);

    var replayed = ResearchContractValidator.replayHistoricalAt(List.of(rev1, rev2Future), T_AS_OF);
    assertThat(replayed).hasSize(1);
    assertThat(replayed.getFirst().revisionNumber()).isEqualTo(1);
  }

  @Test
  void test8_provenanceIsolationPreventsMixingOriginsAndHumanGradesInCollabHistory() {
    var synCreator = sampleObservedRecord(DataOrigin.SYN, T1,
        MetricObservation.observed(0.05, MetricUnit.DECIMAL_RATIO, 3, 3, "DGP", T0, T1),
        MetricObservation.observed(1000.0, MetricUnit.COUNT, 3, 3, "DGP", T0, T1));

    var mixedSnapshot = new CampaignCreatorSnapshot(
        CampaignCreatorSnapshot.SCHEMA_VERSION, "snap-mix", UUID.randomUUID(),
        "AWARENESS", List.of("TIKTOK"), List.of(), null, null, null,
        T_AS_OF, DataOrigin.REAL, List.of(synCreator), List.of());

    assertThatThrownBy(() -> ResearchContractValidator.validateSnapshot(mixedSnapshot))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("DATA_ORIGIN_MIXING");

    assertThatThrownBy(() -> new HistoricalCollaborationEvidence(
        HistoricalCollaborationEvidence.SCHEMA_VERSION, UUID.randomUUID(), UUID.randomUUID(), null,
        UUID.randomUUID(), UUID.randomUUID(), DataOrigin.HUMAN, T0, T1, 1, "COMPLETED",
        null, null, null, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("HUMAN suitability judgments");
  }

  @Test
  void test9_candidateUniverseRejectsDuplicateCreatorsOrMasterPlatform() {
    UUID masterId = UUID.randomUUID();
    var e1 = new CandidateEntry(UUID.randomUUID(), masterId, "TIKTOK", HardEligibilityStatus.ELIGIBLE, List.of());
    var e2 = new CandidateEntry(UUID.randomUUID(), masterId, "TIKTOK", HardEligibilityStatus.ELIGIBLE, List.of());

    assertThatThrownBy(() -> new CandidateUniverse(
        CandidateUniverse.SCHEMA_VERSION, UUID.randomUUID(), "snap-1", DataOrigin.REAL, "SHORTLIST", List.of(e1, e2)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Duplicate (masterId, platform)");
  }

  @Test
  void test10_legacyAdapterExplicitlyReportsLossyMappings() {
    var partialViews = MetricObservation.observed(150.0, MetricUnit.COUNT, 2, 3, "TIKTOK_CRAWLER", T0, T1);
    var invalidLikes = MetricObservation.invalid(MetricUnit.COUNT, "-10", 3, "TIKTOK_CRAWLER", T0, T1);
    var notAppViews = MetricObservation.notApplicable(MetricUnit.COUNT, "FACEBOOK_PHOTO", T0, T1);

    var record = new ObservedEvidenceRecord(
        ObservedEvidenceRecord.SCHEMA_VERSION, UUID.randomUUID(), null, "TIKTOK", DataOrigin.REAL,
        "snap-1", T0, T1,
        MetricObservation.observed(1000.0, MetricUnit.COUNT, 1, 1, "TIKTOK_CRAWLER", T0, T1),
        partialViews, invalidLikes, null, null,
        MetricObservation.observed(0.05, MetricUnit.DECIMAL_RATIO, 3, 3, "TIKTOK_CRAWLER", T0, T1),
        List.of(new PostObservation("p1", T0, notAppViews, invalidLikes, null, null)));

    var mapped = LegacyResearchAdapter.toLegacyInputs(record);
    assertThat(mapped.candidate().getAvgViews()).isEqualByComparingTo("150.0");
    assertThat(mapped.candidate().getAvgLikes()).isNull();
    assertThat(mapped.posts().getFirst().getViews()).isNull();
    assertThat(mapped.lossyMappings())
        .extracting(LegacyResearchAdapter.LossyMappingWarning::sourceAvailability)
        .contains(MetricAvailability.PARTIAL_OBSERVATION, MetricAvailability.INVALID, MetricAvailability.NOT_APPLICABLE);
  }

  @Test
  void test11_serializationAndDeserializationAreDeterministic() {
    var manifest = new ExperimentRunManifest(
        ExperimentRunManifest.SCHEMA_VERSION,
        UUID.fromString("00000000-0000-0000-0000-000000000042"),
        "protocol.v1", "dgp.v0", "features.v1", "four_memory.v1",
        "cfg-sha256", "data-sha256", "fixed-seed-42", DataOrigin.SYN, T_AS_OF,
        Map.of("java", "21", "env", "research"));

    String json1 = ResearchJsonMapper.toDeterministicJson(manifest);
    String json2 = ResearchJsonMapper.toDeterministicJson(manifest);
    assertThat(json1).isEqualTo(json2);

    var restored = ResearchJsonMapper.fromJson(json1, ExperimentRunManifest.class);
    assertThat(restored).isEqualTo(manifest);
  }

  private ObservedEvidenceRecord sampleObservedRecord(
      DataOrigin origin, Instant knownAt, MetricObservation er, MetricObservation avgViews) {
    return new ObservedEvidenceRecord(
        ObservedEvidenceRecord.SCHEMA_VERSION,
        UUID.randomUUID(), null, "TIKTOK", origin, "snap-1", T0, knownAt,
        MetricObservation.observed(10_000.0, MetricUnit.COUNT, 1, 1, "TEST", T0, knownAt),
        avgViews, null, null, null, er, List.of());
  }
}
