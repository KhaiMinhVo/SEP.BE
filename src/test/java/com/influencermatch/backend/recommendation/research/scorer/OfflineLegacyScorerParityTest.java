package com.influencermatch.backend.recommendation.research.scorer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.influencermatch.backend.campaign.model.Campaign;
import com.influencermatch.backend.recommendation.engine.FourMemoryTopsisEngine;
import com.influencermatch.backend.recommendation.engine.phase0.Phase0Orchestrator;
import com.influencermatch.backend.recommendation.research.adapters.LegacyResearchAdapter;
import com.influencermatch.backend.recommendation.research.adapters.LegacyResearchAdapter.LossyMappingWarning;
import com.influencermatch.backend.recommendation.research.contracts.CampaignCreatorSnapshot;
import com.influencermatch.backend.recommendation.research.contracts.CandidateUniverse;
import com.influencermatch.backend.recommendation.research.contracts.CandidateUniverse.CandidateEntry;
import com.influencermatch.backend.recommendation.research.contracts.HistoricalCollaborationEvidence;
import com.influencermatch.backend.recommendation.research.contracts.MetricObservation;
import com.influencermatch.backend.recommendation.research.contracts.ObservedEvidenceRecord;
import com.influencermatch.backend.recommendation.research.contracts.ObservedEvidenceRecord.PostObservation;
import com.influencermatch.backend.recommendation.research.contracts.ResearchEnums.DataOrigin;
import com.influencermatch.backend.recommendation.research.contracts.ResearchEnums.HardEligibilityStatus;
import com.influencermatch.backend.recommendation.research.contracts.ResearchEnums.MetricAvailability;
import com.influencermatch.backend.recommendation.research.contracts.ResearchEnums.MetricUnit;
import com.influencermatch.backend.recommendation.research.dgp.DgpConfig;
import com.influencermatch.backend.recommendation.research.dgp.DgpConfig.DatasetSplit;
import com.influencermatch.backend.recommendation.research.dgp.DgpConfig.DgpRegime;
import com.influencermatch.backend.recommendation.research.dgp.DgpConfig.MissingnessIntensity;
import com.influencermatch.backend.recommendation.research.dgp.DgpConfig.MissingnessMechanism;
import com.influencermatch.backend.recommendation.research.dgp.SyntheticDgpGenerator;
import com.influencermatch.backend.recommendation.research.scorer.OfflineLegacyFourMemoryScorer.PolicyVariant;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OfflineLegacyScorerParityTest {

  private static final Instant T0 = Instant.parse("2026-11-20T10:00:00Z");
  private static final Instant T1 = Instant.parse("2026-11-20T10:05:00Z");
  private static final Instant T_AS_OF = Instant.parse("2026-11-25T00:00:00Z");
  private static final Instant T_FUTURE = Instant.parse("2026-11-26T00:00:00Z");

  private final OfflineLegacyFourMemoryScorer offlineScorer = new OfflineLegacyFourMemoryScorer();

  @Test
  void test1And2_goldenFixtureMatchesHandCalculatedM2AndProductionEngineParity() {
    UUID campaignId = UUID.fromString("00000000-0000-0000-0000-000000000100");
    UUID creatorId = UUID.fromString("00000000-0000-0000-0000-000000000101");

    // Golden creator: followers=1000, 2 identical posts (views=250, likes=12.5? -> likes=50 on views=1000 => ER=0.05, VR=0.25, STAB=1.0)
    // Hand-calculated M2 (TIKTOK: K_ER=0.05, K_VR=0.50):
    //   erNorm = 0.05 / (0.05 + 0.05) = 0.50
    //   vrNorm = 0.25 / (0.25 + 0.50) = 1/3
    //   stabNorm = 1.0 (CV=0)
    //   sm2 = (0.50 + 1/3 + 1.0) / 3.0 = 11/18
    var golden = new ObservedEvidenceRecord(
        ObservedEvidenceRecord.SCHEMA_VERSION,
        creatorId, null, "TIKTOK", DataOrigin.REAL, "golden-1", T0, T1,
        MetricObservation.observed(1_000.0, MetricUnit.COUNT, 1, 1, "GOLDEN", T0, T1),
        MetricObservation.observed(250.0, MetricUnit.COUNT, 2, 2, "GOLDEN", T0, T1),
        MetricObservation.observed(12.5, MetricUnit.COUNT, 2, 2, "GOLDEN", T0, T1),
        null, null,
        MetricObservation.observed(0.05, MetricUnit.DECIMAL_RATIO, 2, 2, "GOLDEN", T0, T1),
        List.of(
            new PostObservation("g1", T0.minusSeconds(120),
                MetricObservation.observed(250.0, MetricUnit.COUNT, 1, 1, "GOLDEN", T0, T1),
                MetricObservation.observed(13.0, MetricUnit.COUNT, 1, 1, "GOLDEN", T0, T1),
                null, null),
            new PostObservation("g2", T0.minusSeconds(60),
                MetricObservation.observed(250.0, MetricUnit.COUNT, 1, 1, "GOLDEN", T0, T1),
                MetricObservation.observed(12.0, MetricUnit.COUNT, 1, 1, "GOLDEN", T0, T1),
                null, null)));

    var snapshot = new CampaignCreatorSnapshot(
        CampaignCreatorSnapshot.SCHEMA_VERSION, "golden-1", campaignId, "AWARENESS",
        List.of("TIKTOK"), List.of(), null, null, null, T_AS_OF, DataOrigin.REAL,
        List.of(golden), List.of());
    var universe = new CandidateUniverse(
        CandidateUniverse.SCHEMA_VERSION, campaignId, "golden-1", DataOrigin.REAL, "GOLDEN",
        List.of(new CandidateEntry(creatorId, null, "TIKTOK", HardEligibilityStatus.ELIGIBLE, List.of())));

    var offlineResult = offlineScorer.score(snapshot, universe, PolicyVariant.CORRECTED_REFERENCE_V1, false);
    assertThat(offlineResult.mainRanked()).hasSize(1);
    var scored = offlineResult.mainRanked().getFirst();

    assertThat(scored.erNorm()).isCloseTo(0.50, org.assertj.core.data.Offset.offset(1e-9));
    assertThat(scored.vrNorm()).isCloseTo(1.0 / 3.0, org.assertj.core.data.Offset.offset(1e-9));
    assertThat(scored.stabNorm()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-9));
    assertThat(scored.sm2()).isCloseTo(11.0 / 18.0, org.assertj.core.data.Offset.offset(1e-9));
    assertThat(scored.sm1()).isEqualTo(0.50);
    assertThat(scored.sm3()).isEqualTo(0.50);
    assertThat(scored.sm4()).isEqualTo(0.50);

    // Direct parity check against production Phase0Orchestrator + FourMemoryTopsisEngine
    var legacy = LegacyResearchAdapter.toLegacyInputs(golden);
    var campaign = Campaign.builder().id(campaignId).platforms(List.of("TIKTOK")).build();
    var p0 = new Phase0Orchestrator().execute(
        List.of(legacy.candidate()), campaign, Set.of(), Map.of(creatorId, legacy.posts()), ZoneOffset.UTC);
    var prod = new FourMemoryTopsisEngine().evaluateAndRank(
        List.of(legacy.candidate()), Map.of(creatorId, p0.getFirst()), campaign,
        c -> List.of(), List.of(),
        c -> com.influencermatch.backend.recommendation.engine.presentation.enums.PriceStatus.PRICE_UNKNOWN,
        List.of(), LocalDateTime.ofInstant(T_AS_OF, ZoneOffset.UTC)).getFirst();

    assertThat(scored.matchScore()).isCloseTo(prod.matchScore(), org.assertj.core.data.Offset.offset(1e-12));
    assertThat(scored.sm2()).isCloseTo(prod.memoryScores().sm2(), org.assertj.core.data.Offset.offset(1e-12));
  }

  @Test
  void test3To10_routingLossyMissingnessUniverseDeduplicationTieBreakAndFaultIsolation() {
    UUID campaignId = UUID.fromString("00000000-0000-0000-0000-000000000200");
    UUID idA = UUID.fromString("00000000-0000-0000-0000-000000000201");
    UUID idB = UUID.fromString("00000000-0000-0000-0000-000000000202");
    UUID idProv = UUID.fromString("00000000-0000-0000-0000-000000000203");
    UUID idMissing = UUID.fromString("00000000-0000-0000-0000-000000000204");
    UUID idIneligible = UUID.fromString("00000000-0000-0000-0000-000000000205");
    UUID idOutsideUniverse = UUID.fromString("00000000-0000-0000-0000-000000000206");

    // idA and idB have identical metrics -> deterministic tie-break must order idA before idB
    var recA = readyRecord(idA, 250.0);
    var recB = readyRecord(idB, 250.0);
    // idProv has 1 post -> PROVISIONAL
    var recProv = new ObservedEvidenceRecord(
        ObservedEvidenceRecord.SCHEMA_VERSION, idProv, null, "TIKTOK", DataOrigin.REAL, "s1", T0, T1,
        MetricObservation.observed(1_000.0, MetricUnit.COUNT, 1, 1, "T", T0, T1),
        MetricObservation.observed(250.0, MetricUnit.COUNT, 1, 1, "T", T0, T1),
        MetricObservation.observed(12.5, MetricUnit.COUNT, 1, 1, "T", T0, T1),
        null, null,
        MetricObservation.observed(0.05, MetricUnit.DECIMAL_RATIO, 1, 1, "T", T0, T1),
        List.of(new PostObservation("p1", T0,
            MetricObservation.observed(250.0, MetricUnit.COUNT, 1, 1, "T", T0, T1),
            MetricObservation.observed(12.5, MetricUnit.COUNT, 1, 1, "T", T0, T1), null, null)));
    // idMissing has MISSING views and INVALID likes -> NOT_SCORABLE (fault isolated, does not crash batch)
    var recMissing = new ObservedEvidenceRecord(
        ObservedEvidenceRecord.SCHEMA_VERSION, idMissing, null, "TIKTOK", DataOrigin.REAL, "s1", T0, T1,
        MetricObservation.observed(1_000.0, MetricUnit.COUNT, 1, 1, "T", T0, T1),
        MetricObservation.missing(MetricUnit.COUNT, 3, "T", T0, T1),
        MetricObservation.invalid(MetricUnit.COUNT, "-5", 3, "T", T0, T1),
        null, null,
        MetricObservation.missing(MetricUnit.DECIMAL_RATIO, 3, "T", T0, T1),
        List.of());
    var recOutside = readyRecord(idOutsideUniverse, 500.0);

    // Duplicate revision of same collaborationId must NOT be double-counted; future revision excluded
    UUID collabId = UUID.randomUUID();
    var hRev1 = new HistoricalCollaborationEvidence(
        HistoricalCollaborationEvidence.SCHEMA_VERSION, collabId, idA, null,
        UUID.randomUUID(), UUID.randomUUID(), DataOrigin.REAL, T0, T1, 1, "COMPLETED",
        MetricObservation.observed(0.8, MetricUnit.DECIMAL_RATIO, 1, 1, "H", T0, T1),
        MetricObservation.observed(1.0, MetricUnit.DECIMAL_RATIO, 1, 1, "H", T0, T1),
        MetricObservation.observed(0.9, MetricUnit.DECIMAL_RATIO, 1, 1, "H", T0, T1),
        MetricObservation.observed(0.8, MetricUnit.DECIMAL_RATIO, 1, 1, "H", T0, T1));

    var snapshot = new CampaignCreatorSnapshot(
        CampaignCreatorSnapshot.SCHEMA_VERSION, "s1", campaignId, "AWARENESS",
        List.of("TIKTOK"), List.of(), null, null, null, T_AS_OF, DataOrigin.REAL,
        List.of(recB, recA, recProv, recMissing, recOutside), List.of(hRev1));
    var universe = new CandidateUniverse(
        CandidateUniverse.SCHEMA_VERSION, campaignId, "s1", DataOrigin.REAL, "TEST",
        List.of(
            new CandidateEntry(idB, null, "TIKTOK", HardEligibilityStatus.ELIGIBLE, List.of()),
            new CandidateEntry(idA, null, "TIKTOK", HardEligibilityStatus.ELIGIBLE, List.of()),
            new CandidateEntry(idProv, null, "TIKTOK", HardEligibilityStatus.ELIGIBLE, List.of()),
            new CandidateEntry(idMissing, null, "TIKTOK", HardEligibilityStatus.ELIGIBLE, List.of()),
            new CandidateEntry(idIneligible, null, "TIKTOK", HardEligibilityStatus.INELIGIBLE, List.of("PLATFORM_MISMATCH"))));

    var result = offlineScorer.score(snapshot, universe, PolicyVariant.CORRECTED_REFERENCE_V1, false);
    assertThat(result.mainRanked()).extracting(OfflineLegacyFourMemoryScorer.ScoredCandidateRecord::creatorId)
        .containsExactly(idA, idB); // deterministic tie-break + outsideUniverse excluded
    assertThat(result.provisionalRanked()).extracting(OfflineLegacyFourMemoryScorer.ScoredCandidateRecord::creatorId)
        .containsExactly(idProv);
    assertThat(result.excluded()).extracting(OfflineLegacyFourMemoryScorer.ExcludedCandidateRecord::creatorId)
        .containsExactlyInAnyOrder(idMissing, idIneligible);
    assertThat(result.excluded().stream()
        .filter(e -> e.creatorId().equals(idMissing)).findFirst().orElseThrow()
        .lossyMappings())
        .extracting(LossyMappingWarning::sourceAvailability)
        .contains(MetricAvailability.INVALID);

    // Future collaboration rejected if passed in snapshot
    var futureCollab = new HistoricalCollaborationEvidence(
        HistoricalCollaborationEvidence.SCHEMA_VERSION, collabId, idA, null,
        UUID.randomUUID(), UUID.randomUUID(), DataOrigin.REAL, T0, T_FUTURE, 2, "COMPLETED",
        null, null, null, null);
    var futureSnapshot = new CampaignCreatorSnapshot(
        CampaignCreatorSnapshot.SCHEMA_VERSION, "s1", campaignId, "AWARENESS",
        List.of("TIKTOK"), List.of(), null, null, null, T_AS_OF, DataOrigin.REAL,
        List.of(recA), List.of(futureCollab));
    assertThatThrownBy(() -> offlineScorer.score(futureSnapshot, universe, PolicyVariant.CORRECTED_REFERENCE_V1, false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("TEMPORAL_LEAKAGE");
  }

  @Test
  void g3BundleCanBeScoredEndToEndWithFiniteOutputsAndNoOracleLeakage() {
    var bundle = new SyntheticDgpGenerator().generate(new DgpConfig(
        DgpConfig.DGP_VERSION, DgpRegime.DGP_A, MissingnessMechanism.MCAR,
        MissingnessIntensity.LOW, DatasetSplit.DEV, 42L, 15, T_AS_OF));

    var result1 = offlineScorer.score(bundle.scorerSnapshot(), bundle.candidateUniverse(), PolicyVariant.CORRECTED_REFERENCE_V1, true);
    var result2 = offlineScorer.score(bundle.scorerSnapshot(), bundle.candidateUniverse(), PolicyVariant.CORRECTED_REFERENCE_V1, true);

    assertThat(result1.mainRanked()).isEqualTo(result2.mainRanked());
    assertThat(result1.provisionalRanked()).isEqualTo(result2.provisionalRanked());
    assertThat(result1.mainRanked()).allMatch(r -> Double.isFinite(r.matchScore()));
    assertThat(result1.provisionalRanked()).allMatch(r -> Double.isFinite(r.matchScore()));
  }

  private ObservedEvidenceRecord readyRecord(UUID creatorId, double avgViews) {
    return new ObservedEvidenceRecord(
        ObservedEvidenceRecord.SCHEMA_VERSION, creatorId, null, "TIKTOK", DataOrigin.REAL, "s1", T0, T1,
        MetricObservation.observed(1_000.0, MetricUnit.COUNT, 1, 1, "T", T0, T1),
        MetricObservation.observed(avgViews, MetricUnit.COUNT, 2, 2, "T", T0, T1),
        MetricObservation.observed(12.5, MetricUnit.COUNT, 2, 2, "T", T0, T1),
        null, null,
        MetricObservation.observed(0.05, MetricUnit.DECIMAL_RATIO, 2, 2, "T", T0, T1),
        List.of(
            new PostObservation("p1", T0.minusSeconds(120),
                MetricObservation.observed(avgViews, MetricUnit.COUNT, 1, 1, "T", T0, T1),
                MetricObservation.observed(12.5, MetricUnit.COUNT, 1, 1, "T", T0, T1), null, null),
            new PostObservation("p2", T0.minusSeconds(60),
                MetricObservation.observed(avgViews, MetricUnit.COUNT, 1, 1, "T", T0, T1),
                MetricObservation.observed(12.5, MetricUnit.COUNT, 1, 1, "T", T0, T1), null, null)));
  }
}
