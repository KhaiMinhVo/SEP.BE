package com.influencermatch.backend.recommendation.research.scorer;

import static org.assertj.core.api.Assertions.assertThat;

import com.influencermatch.backend.recommendation.research.contracts.CampaignCreatorSnapshot;
import com.influencermatch.backend.recommendation.research.contracts.CandidateUniverse;
import com.influencermatch.backend.recommendation.research.contracts.CandidateUniverse.CandidateEntry;
import com.influencermatch.backend.recommendation.research.contracts.ExtendedCampaignSnapshot;
import com.influencermatch.backend.recommendation.research.contracts.ExtendedCampaignSnapshot.BrandPatternSpec;
import com.influencermatch.backend.recommendation.research.contracts.HistoricalCollaborationEvidence;
import com.influencermatch.backend.recommendation.research.contracts.MetricObservation;
import com.influencermatch.backend.recommendation.research.contracts.ObservedCreatorContextExtension;
import com.influencermatch.backend.recommendation.research.contracts.ObservedEvidenceRecord;
import com.influencermatch.backend.recommendation.research.contracts.ObservedEvidenceRecord.PostObservation;
import com.influencermatch.backend.recommendation.research.contracts.ResearchEnums.DataOrigin;
import com.influencermatch.backend.recommendation.research.contracts.ResearchEnums.HardEligibilityStatus;
import com.influencermatch.backend.recommendation.research.contracts.ResearchEnums.MetricUnit;
import com.influencermatch.backend.recommendation.research.dgp.DgpConfig;
import com.influencermatch.backend.recommendation.research.dgp.DgpConfig.DatasetSplit;
import com.influencermatch.backend.recommendation.research.dgp.DgpConfig.DgpRegime;
import com.influencermatch.backend.recommendation.research.dgp.DgpConfig.MissingnessIntensity;
import com.influencermatch.backend.recommendation.research.dgp.DgpConfig.MissingnessMechanism;
import com.influencermatch.backend.recommendation.research.dgp.SyntheticDgpGenerator;
import com.influencermatch.backend.recommendation.research.scorer.OfflineLegacyFourMemoryScorer.PolicyVariant;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FullMemoryParityG41Test {

  private static final Instant T0 = Instant.parse("2026-11-10T10:00:00Z");
  private static final Instant T1 = Instant.parse("2026-11-10T12:00:00Z");
  private static final Instant T_AS_OF = Instant.parse("2026-11-25T00:00:00Z");

  private final OfflineLegacyFourMemoryScorer scorer = new OfflineLegacyFourMemoryScorer();

  @Test
  void fullM1M3HighAndDirectM4BeatPartialM1M3LowAndExplicitFallback() {
    UUID campaignId = UUID.randomUUID();
    UUID brandId = UUID.randomUUID();
    UUID otherBrand = UUID.randomUUID();

    UUID creatorStrong = UUID.fromString("00000000-0000-0000-0000-000000000301");
    UUID creatorWeak = UUID.fromString("00000000-0000-0000-0000-000000000302");
    UUID creatorCohort = UUID.fromString("00000000-0000-0000-0000-000000000303");

    // creatorStrong: 3 collabs across 2 brands with full R/T/K + 3 direct brand reviews + high M3 (+0.80 -> sm3=0.90)
    List<HistoricalCollaborationEvidence> history = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      UUID b = (i < 2) ? brandId : otherBrand;
      history.add(collab(creatorStrong, UUID.randomUUID(), b, campaignId, 1.0, 1.0, 1.0, 1.0));
    }
    // creatorWeak: partial R/T/K (missing T) -> M1 fails gate -> PRIOR/NEUTRAL_FALLBACK (0.50), low M3 (-0.40 -> sm3=0.30)
    history.add(collab(creatorWeak, null, brandId, campaignId, 0.8, null, 0.8, null));

    // creatorCohort: 5 cohort reviews from 3 distinct masters on (TIKTOK, BEAUTY)
    List<ObservedEvidenceRecord> allObs = new ArrayList<>(List.of(
        obsRecord(creatorStrong, null, "TIKTOK"),
        obsRecord(creatorWeak, null, "INSTAGRAM"),
        obsRecord(creatorCohort, null, "TIKTOK")));
    var extensions = new java.util.LinkedHashMap<UUID, ObservedCreatorContextExtension>();
    extensions.put(creatorStrong, ext(creatorStrong, "BEAUTY", 0.80));
    extensions.put(creatorWeak, ext(creatorWeak, "TECH", -0.40));
    extensions.put(creatorCohort, ext(creatorCohort, "BEAUTY", 0.20));

    for (int i = 0; i < 5; i++) {
      UUID cohortCreator = UUID.fromString("00000000-0000-0000-0000-00000000050" + i);
      UUID cohortMaster = UUID.fromString("00000000-0000-0000-0000-00000000040" + (i % 3));
      allObs.add(obsRecord(cohortCreator, cohortMaster, "TIKTOK"));
      extensions.put(cohortCreator, ext(cohortCreator, "BEAUTY", 0.10));
      history.add(collab(cohortCreator, cohortMaster, otherBrand, campaignId, null, null, null, 0.75));
    }

    var base = new CampaignCreatorSnapshot(
        CampaignCreatorSnapshot.SCHEMA_VERSION, "g41-1", campaignId, "AWARENESS",
        List.of("TIKTOK", "INSTAGRAM"), List.of("BEAUTY"), null, null, null, T_AS_OF, DataOrigin.REAL,
        allObs, history);

    var extended = new ExtendedCampaignSnapshot(
        ExtendedCampaignSnapshot.SCHEMA_VERSION, base, brandId,
        List.of(new BrandPatternSpec("BEAUTY", "BEAUTY", "TIKTOK", 0.90)), extensions);

    var universe = new CandidateUniverse(
        CandidateUniverse.SCHEMA_VERSION, campaignId, "g41-1", DataOrigin.REAL, "TEST",
        List.of(
            new CandidateEntry(creatorStrong, null, "TIKTOK", HardEligibilityStatus.ELIGIBLE, List.of()),
            new CandidateEntry(creatorWeak, null, "INSTAGRAM", HardEligibilityStatus.ELIGIBLE, List.of()),
            new CandidateEntry(creatorCohort, null, "TIKTOK", HardEligibilityStatus.ELIGIBLE, List.of())));

    var result = scorer.score(extended, universe, PolicyVariant.CORRECTED_REFERENCE_V1, false);
    assertThat(result.mainRanked()).hasSize(3);

    var byId = result.mainRanked().stream()
        .collect(java.util.stream.Collectors.toMap(r -> r.creatorId(), r -> r));

    var strong = byId.get(creatorStrong);
    assertThat(strong.m1Availability()).isEqualTo("AVAILABLE");
    assertThat(strong.sm1()).isGreaterThan(0.75);
    assertThat(strong.hasSemanticEmbedding()).isTrue();
    assertThat(strong.sm3()).isCloseTo(0.90, org.assertj.core.data.Offset.offset(1e-9));
    assertThat(strong.m4Source()).isEqualTo("DIRECT_BRAND_CREATOR");

    var weak = byId.get(creatorWeak);
    assertThat(weak.m1Availability()).isEqualTo("PRIOR/NEUTRAL_FALLBACK");
    assertThat(weak.sm1()).isEqualTo(0.50);
    assertThat(weak.sm3()).isCloseTo(0.30, org.assertj.core.data.Offset.offset(1e-9));
    assertThat(weak.m4Source()).isEqualTo("EXPLICIT_FALLBACK");
    assertThat(weak.sm4()).isEqualTo(0.0); // INSTAGRAM + TECH both mismatch BEAUTY + TIKTOK

    var cohort = byId.get(creatorCohort);
    assertThat(cohort.m4Source()).isEqualTo("COHORT_PLATFORM_NICHE");
    assertThat(cohort.sm4()).isGreaterThan(0.50);

    // Strong creator must outrank weak creator
    assertThat(result.mainRanked().getFirst().creatorId()).isEqualTo(creatorStrong);
    assertThat(result.mainRanked().getLast().creatorId()).isEqualTo(creatorWeak);
  }

  @Test
  void tinyDgpExtendedDiagnosticsProduceValidPerMemorySignals() {
    var extBundle = new SyntheticDgpGenerator().generateExtended(new DgpConfig(
        DgpConfig.DGP_VERSION, DgpRegime.DGP_A, MissingnessMechanism.MCAR,
        MissingnessIntensity.LOW, DatasetSplit.DEV, 77L, 20, T_AS_OF));

    var res = scorer.score(extBundle.extendedSnapshot(), extBundle.baseBundle().candidateUniverse(),
        PolicyVariant.CORRECTED_REFERENCE_V1, false);

    assertThat(res.mainRanked().size() + res.provisionalRanked().size() + res.excluded().size())
        .isEqualTo(20);
    assertThat(res.mainRanked()).allMatch(r -> r.hasSemanticEmbedding()
        && Double.isFinite(r.sm1()) && Double.isFinite(r.sm2())
        && Double.isFinite(r.sm3()) && Double.isFinite(r.sm4()));
  }

  private ObservedEvidenceRecord obsRecord(UUID creatorId, UUID masterId, String platform) {
    return new ObservedEvidenceRecord(
        ObservedEvidenceRecord.SCHEMA_VERSION, creatorId, masterId, platform, DataOrigin.REAL, "g41", T0, T1,
        MetricObservation.observed(10_000.0, MetricUnit.COUNT, 1, 1, "T", T0, T1),
        MetricObservation.observed(2_500.0, MetricUnit.COUNT, 2, 2, "T", T0, T1),
        MetricObservation.observed(125.0, MetricUnit.COUNT, 2, 2, "T", T0, T1),
        null, null,
        MetricObservation.observed(0.05, MetricUnit.DECIMAL_RATIO, 2, 2, "T", T0, T1),
        List.of(
            new PostObservation("p1", T0.minusSeconds(120),
                MetricObservation.observed(2_500.0, MetricUnit.COUNT, 1, 1, "T", T0, T1),
                MetricObservation.observed(125.0, MetricUnit.COUNT, 1, 1, "T", T0, T1), null, null),
            new PostObservation("p2", T0.minusSeconds(60),
                MetricObservation.observed(2_500.0, MetricUnit.COUNT, 1, 1, "T", T0, T1),
                MetricObservation.observed(125.0, MetricUnit.COUNT, 1, 1, "T", T0, T1), null, null)));
  }

  private ObservedCreatorContextExtension ext(UUID creatorId, String niche, double cosine) {
    return new ObservedCreatorContextExtension(
        ObservedCreatorContextExtension.SCHEMA_VERSION, creatorId, DataOrigin.REAL, niche, niche,
        MetricObservation.observed(cosine, MetricUnit.COSINE_SIMILARITY, 1, 1, "EMB", T0, T1), T0, T1);
  }

  private HistoricalCollaborationEvidence collab(
      UUID creatorId, UUID masterId, UUID brandId, UUID campaignId,
      Double r, Double t, Double k, Double rev) {
    return new HistoricalCollaborationEvidence(
        HistoricalCollaborationEvidence.SCHEMA_VERSION, UUID.randomUUID(), creatorId, masterId, brandId, campaignId,
        DataOrigin.REAL, T0, T1, 1, "COMPLETED",
        r != null ? MetricObservation.observed(r, MetricUnit.DECIMAL_RATIO, 1, 1, "H", T0, T1) : null,
        t != null ? MetricObservation.observed(t, MetricUnit.DECIMAL_RATIO, 1, 1, "H", T0, T1) : null,
        k != null ? MetricObservation.observed(k, MetricUnit.DECIMAL_RATIO, 1, 1, "H", T0, T1) : null,
        rev != null ? MetricObservation.observed(rev, MetricUnit.DECIMAL_RATIO, 1, 1, "H", T0, T1) : null);
  }
}
