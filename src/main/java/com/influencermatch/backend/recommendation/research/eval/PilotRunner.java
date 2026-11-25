package com.influencermatch.backend.recommendation.research.eval;

import com.influencermatch.backend.recommendation.research.contracts.CampaignCreatorSnapshot;
import com.influencermatch.backend.recommendation.research.contracts.CandidateUniverse;
import com.influencermatch.backend.recommendation.research.contracts.CandidateUniverse.CandidateEntry;
import com.influencermatch.backend.recommendation.research.contracts.ExperimentRunManifest;
import com.influencermatch.backend.recommendation.research.contracts.ExtendedCampaignSnapshot;
import com.influencermatch.backend.recommendation.research.contracts.ExtendedCampaignSnapshot.BrandPatternSpec;
import com.influencermatch.backend.recommendation.research.contracts.HistoricalCollaborationEvidence;
import com.influencermatch.backend.recommendation.research.contracts.MetricObservation;
import com.influencermatch.backend.recommendation.research.contracts.ObservedCreatorContextExtension;
import com.influencermatch.backend.recommendation.research.contracts.ObservedEvidenceRecord;
import com.influencermatch.backend.recommendation.research.contracts.ObservedEvidenceRecord.PostObservation;
import com.influencermatch.backend.recommendation.research.contracts.OracleGroundTruthRecord;
import com.influencermatch.backend.recommendation.research.contracts.ResearchEnums.DataOrigin;
import com.influencermatch.backend.recommendation.research.contracts.ResearchEnums.HardEligibilityStatus;
import com.influencermatch.backend.recommendation.research.contracts.ResearchEnums.MetricAvailability;
import com.influencermatch.backend.recommendation.research.contracts.ResearchEnums.MetricUnit;
import com.influencermatch.backend.recommendation.research.contracts.ResearchEnums.OracleGradeSource;
import com.influencermatch.backend.recommendation.research.dgp.DeterministicRngStreams;
import com.influencermatch.backend.recommendation.research.dgp.DeterministicRngStreams.StreamName;
import com.influencermatch.backend.recommendation.research.dgp.DgpConfig.DatasetSplit;
import com.influencermatch.backend.recommendation.research.dgp.DgpConfig.DgpRegime;
import com.influencermatch.backend.recommendation.research.dgp.DgpConfig.MissingnessIntensity;
import com.influencermatch.backend.recommendation.research.dgp.DgpConfig.MissingnessMechanism;
import com.influencermatch.backend.recommendation.research.eval.ExperimentRunner.PairedWorldEvaluationReport;
import com.influencermatch.backend.recommendation.research.eval.ExperimentRunner.ResearchScorer;
import com.influencermatch.backend.recommendation.research.eval.ExperimentRunner.RunStatus;
import com.influencermatch.backend.recommendation.research.scorer.OfflineLegacyFourMemoryScorer;
import com.influencermatch.backend.recommendation.research.scorer.OfflineLegacyFourMemoryScorer.OfflineScoringResult;
import com.influencermatch.backend.recommendation.research.scorer.OfflineLegacyFourMemoryScorer.PolicyVariant;
import com.influencermatch.backend.recommendation.research.scorer.OfflineLegacyFourMemoryScorer.ScoredCandidateRecord;
import com.influencermatch.backend.recommendation.research.validation.ResearchJsonMapper;
import com.influencermatch.backend.recommendation.research.validation.ScorerInputBoundary;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * G6 Computational Pilot Runner (dgp.pilot.v1):
 * - Purely experimental sample generation (separated from validation fixtures).
 * - Supports multi-brand, multi-master, multi-period worlds and 8 queries/world.
 */
public final class PilotRunner {

  public static final String PILOT_DGP_VERSION = "dgp.pilot.v1";
  private static final List<String> NICHES = List.of("BEAUTY", "TECH", "FOOD", "FASHION");
  private static final List<String> PLATFORMS = List.of("TIKTOK", "INSTAGRAM", "FACEBOOK");

  public record PilotWorldSpec(
      DgpRegime regime,
      MissingnessMechanism mechanism,
      MissingnessIntensity intensity,
      long seed,
      int brandCount,
      int masterCount,
      int periodCount,
      int queriesPerWorld,
      Instant startAsOf
  ) {}

  public record PilotConditionSummary(
      DgpRegime regime,
      MissingnessMechanism mechanism,
      int worldCount,
      int queryCount,
      double meanSm1,
      double meanSm2,
      double meanSm3,
      double meanSm4,
      double m1FallbackFraction,
      double m3FallbackFraction,
      Map<String, Long> m4TierCounts,
      Map<MetricAvailability, Long> metricAvailabilityCounts,
      long mainCount,
      long provisionalCount,
      long excludedCount,
      long universeCount,
      long scoredCount,
      long returnedCount,
      double meanNdcgAt10,
      double meanRegretAt10,
      double meanListFillRateAt10,
      double meanCandidateCoverage,
      double meanMainCoverage,
      long emptyRuns,
      long failedRuns,
      long lossyWarningsCount,
      long elapsedMs
  ) {}

  public List<PairedWorldEvaluationReport> runPilotWorld(PilotWorldSpec spec) {
    return runPilotWorld(spec, false);
  }

  public List<PairedWorldEvaluationReport> runPilotWorld(PilotWorldSpec spec, boolean enforceM1Gate) {
    var offlineScorer = new OfflineLegacyFourMemoryScorer();
    ResearchScorer legacyScorer = new ResearchScorer() {
      @Override
      public String scorerVersion() { return PolicyVariant.CORRECTED_REFERENCE_V1.id() + (enforceM1Gate ? "+m1gate" : ""); }

      @Override
      public OfflineScoringResult score(ExtendedCampaignSnapshot snapshot, CandidateUniverse universe) {
        return offlineScorer.score(snapshot, universe, PolicyVariant.CORRECTED_REFERENCE_V1, enforceM1Gate);
      }
    };
    return runPilotWorld(spec, List.of(legacyScorer));
  }

  public List<PairedWorldEvaluationReport> runPilotWorld(PilotWorldSpec spec, List<ResearchScorer> scorers) {
    DeterministicRngStreams rng = new DeterministicRngStreams(spec.seed(), DatasetSplit.DEV);
    Random latentRng = rng.stream(StreamName.LATENT_WORLD);
    Random bookingRng = rng.stream(StreamName.BOOKING_EXPOSURE);
    Random histRng = rng.stream(StreamName.HISTORICAL_OUTCOMES);
    Random pubRng = rng.stream(StreamName.PUBLIC_MEASUREMENT);
    Random missRng = rng.stream(StreamName.MISSINGNESS);

    // 1. Generate 3 brands and 40 creator masters (1-2 platform accounts per master)
    List<UUID> brandIds = new ArrayList<>();
    List<Double> brandBiases = new ArrayList<>();
    List<String> brandNiches = new ArrayList<>();
    for (int b = 0; b < spec.brandCount(); b++) {
      brandIds.add(uuid("pilot:" + spec.seed() + ":brand:" + b));
      brandBiases.add(latentRng.nextGaussian() * spec.regime().brandBiasStd());
      brandNiches.add(NICHES.get(b % NICHES.size()));
    }

    record CreatorAccount(UUID creatorId, UUID masterId, String platform, String niche, double q, double r) {}
    List<CreatorAccount> accounts = new ArrayList<>();
    for (int m = 0; m < spec.masterCount(); m++) {
      UUID masterId = uuid("pilot:" + spec.seed() + ":master:" + m);
      double q = clamp(0.55 + latentRng.nextGaussian() * 0.18, 0.05, 0.98);
      double r = clamp(0.70 + latentRng.nextGaussian() * 0.15, 0.10, 0.99);
      String niche = NICHES.get(latentRng.nextInt(NICHES.size()));
      int numAccounts = (m % 2 == 0) ? 2 : 1;
      for (int a = 0; a < numAccounts; a++) {
        // Favor video platforms so >= 10 candidates per query are scorable, while keeping FACEBOOK for NOT_APPLICABLE coverage
        String platform = (a == 0) ? (m % 5 == 0 ? "FACEBOOK" : "TIKTOK") : "INSTAGRAM";
        UUID creatorId = uuid("pilot:" + spec.seed() + ":creator:" + m + ":" + a);
        accounts.add(new CreatorAccount(creatorId, masterId, platform, niche, q, r));
      }
    }

    // 2. Simulate 6 time periods of collaborations & public snapshots
    List<HistoricalCollaborationEvidence> allHistory = new ArrayList<>();
    for (int p = 0; p < spec.periodCount(); p++) {
      Instant periodTime = spec.startAsOf().plus(Duration.ofDays(p * 14L));
      for (int mIdx = 0; mIdx < accounts.size(); mIdx++) {
        CreatorAccount acc = accounts.get(mIdx);
        // ~35% chance of collaboration in a given period
        if (bookingRng.nextDouble() < 0.35) {
          int bIdx = bookingRng.nextInt(spec.brandCount());
          UUID brandId = brandIds.get(bIdx);
          UUID campId = uuid("pilot:" + spec.seed() + ":pastcamp:" + p + ":" + bIdx);
          UUID collabId = uuid("pilot:" + spec.seed() + ":collab:" + p + ":" + acc.creatorId());
          Instant knownAt = periodTime.plus(Duration.ofHours(6));

          double transfer = spec.regime().historyTransferWeight();
          double noise = histRng.nextDouble();
          double outcome = clamp(transfer * acc.q() + (1.0 - transfer) * noise, 0.0, 1.0);
          double review = clamp(outcome + brandBiases.get(bIdx) + histRng.nextGaussian() * 0.05, 0.0, 1.0);

          boolean missingOutcome = shouldDrop(spec, acc.q(), missRng);
          boolean missingReview = shouldDrop(spec, acc.q(), missRng);

          MetricObservation rObs = missingOutcome
              ? MetricObservation.missing(MetricUnit.DECIMAL_RATIO, 1, "PILOT_HIST", periodTime, knownAt)
              : MetricObservation.observed(round4(outcome), MetricUnit.DECIMAL_RATIO, 1, 1, "PILOT_HIST", periodTime, knownAt);
          MetricObservation revObs = missingReview
              ? MetricObservation.missing(MetricUnit.DECIMAL_RATIO, 1, "PILOT_M4", periodTime, knownAt)
              : MetricObservation.observed(round4(review), MetricUnit.DECIMAL_RATIO, 1, 1, "PILOT_M4", periodTime, knownAt);

          allHistory.add(new HistoricalCollaborationEvidence(
              HistoricalCollaborationEvidence.SCHEMA_VERSION,
              collabId, acc.creatorId(), acc.masterId(), brandId, campId,
              DataOrigin.SYN, periodTime, knownAt, 1, "COMPLETED",
              rObs, rObs, rObs, revObs));
        }
      }
    }

    // 3. Evaluate 8 queries across the 3 brands and all time periods
    var runner = new ExperimentRunner();
    List<PairedWorldEvaluationReport> reports = new ArrayList<>();

    for (int qIdx = 0; qIdx < spec.queriesPerWorld(); qIdx++) {
      int bIdx = qIdx % spec.brandCount();
      int periodIdx = qIdx % spec.periodCount();
      Instant queryAsOf = spec.startAsOf().plus(Duration.ofDays(periodIdx * 14L + 7L));
      UUID brandId = brandIds.get(bIdx);
      String targetNiche = brandNiches.get(bIdx);
      UUID queryCampaignId = uuid("pilot:" + spec.seed() + ":" + spec.regime() + ":" + spec.mechanism() + ":q:" + qIdx);
      String snapshotId = "pilot-" + spec.seed() + "-q" + qIdx;

      int dayOfMonth = queryAsOf.atOffset(ZoneOffset.UTC).getDayOfMonth();
      double cycle = 0.05 * Math.sin(2.0 * Math.PI * dayOfMonth / 30.0);

      List<ObservedEvidenceRecord> creatorObs = new ArrayList<>();
      Map<UUID, ObservedCreatorContextExtension> extensions = new LinkedHashMap<>();
      List<CandidateEntry> entries = new ArrayList<>();
      List<OracleGroundTruthRecord> oracles = new ArrayList<>();

      for (CreatorAccount acc : accounts) {
        double fit = clamp((acc.niche().equals(targetNiche) ? 0.90 : 0.40) + latentRng.nextGaussian() * 0.08, 0.0, 1.0);
        double expectedUtility = clamp(0.45 * acc.q() + 0.35 * fit + 0.15 * acc.r() + cycle, 0.0, 1.0);
        oracles.add(new OracleGroundTruthRecord(
            OracleGroundTruthRecord.SCHEMA_VERSION, queryCampaignId, acc.creatorId(), DataOrigin.SYN,
            OracleGradeSource.SYNTHETIC_EXPECTED_UTILITY, queryAsOf, expectedUtility, null, null, true, false,
            "pilot:" + spec.regime().name()));

        Instant obsAt = queryAsOf.minus(Duration.ofHours(4));
        Instant knownAt = queryAsOf.minus(Duration.ofHours(1));
        long followers = Math.round(5_000 + acc.q() * 95_000);
        double erVal = clamp(0.015 + 0.08 * acc.q() + pubRng.nextGaussian() * 0.008, 0.0, 0.40);
        double avgViewsVal = followers * clamp(0.15 + 0.75 * acc.q() + pubRng.nextGaussian() * 0.10, 0.0, 3.0);
        double avgLikesVal = avgViewsVal * erVal;

        boolean isFb = "FACEBOOK".equals(acc.platform());
        boolean dropViews = shouldDrop(spec, acc.q(), missRng);
        boolean dropEr = shouldDrop(spec, acc.q(), missRng);
        boolean partialViews = !isFb && !dropViews && pubRng.nextDouble() < 0.15;

        MetricObservation avgViewsObs = isFb
            ? MetricObservation.notApplicable(MetricUnit.COUNT, "PILOT_FB", obsAt, knownAt)
            : (dropViews
                ? MetricObservation.missing(MetricUnit.COUNT, 3, "PILOT_PUB", obsAt, knownAt)
                : MetricObservation.observed(Math.round(avgViewsVal), MetricUnit.COUNT, partialViews ? 2 : 3, 3, "PILOT_PUB", obsAt, knownAt));
        MetricObservation erObs = dropEr
            ? MetricObservation.missing(MetricUnit.DECIMAL_RATIO, 3, "PILOT_PUB", obsAt, knownAt)
            : MetricObservation.observed(round4(erVal), MetricUnit.DECIMAL_RATIO, 3, 3, "PILOT_PUB", obsAt, knownAt);
        MetricObservation avgLikesObs = MetricObservation.observed(Math.round(avgLikesVal), MetricUnit.COUNT, 3, 3, "PILOT_PUB", obsAt, knownAt);

        int postCount = (avgViewsObs.observedCount() != null && avgViewsObs.observedCount() >= 2) ? avgViewsObs.observedCount() : 1;
        List<PostObservation> posts = new ArrayList<>();
        for (int pIdx = 0; pIdx < postCount; pIdx++) {
          posts.add(new PostObservation("p-" + pIdx, obsAt.minus(Duration.ofDays(pIdx + 1L)), avgViewsObs, avgLikesObs, null, null));
        }

        creatorObs.add(new ObservedEvidenceRecord(
            ObservedEvidenceRecord.SCHEMA_VERSION, acc.creatorId(), acc.masterId(), acc.platform(),
            DataOrigin.SYN, snapshotId, obsAt, knownAt,
            MetricObservation.observed(followers, MetricUnit.COUNT, 1, 1, "PILOT_PUB", obsAt, knownAt),
            avgViewsObs, avgLikesObs, null, null, erObs, posts));

        double cosine = clamp((acc.niche().equals(targetNiche) ? 0.72 : 0.18) + pubRng.nextGaussian() * 0.10, -1.0, 1.0);
        extensions.put(acc.creatorId(), new ObservedCreatorContextExtension(
            ObservedCreatorContextExtension.SCHEMA_VERSION, acc.creatorId(), DataOrigin.SYN,
            acc.niche(), acc.niche(),
            MetricObservation.observed(round4(cosine), MetricUnit.COSINE_SIMILARITY, 1, 1, "PILOT_EMB", obsAt, knownAt),
            obsAt, knownAt));

        entries.add(new CandidateEntry(acc.creatorId(), acc.masterId(), acc.platform(), HardEligibilityStatus.ELIGIBLE, List.of()));
      }

      // Filter history up to queryAsOf for snapshot
      List<HistoricalCollaborationEvidence> asOfHistory = allHistory.stream()
          .filter(h -> !h.knownAt().isAfter(queryAsOf) && !h.eventTime().isAfter(queryAsOf))
          .toList();

      var baseSnap = new CampaignCreatorSnapshot(
          CampaignCreatorSnapshot.SCHEMA_VERSION, snapshotId, queryCampaignId, "AWARENESS",
          List.of("TIKTOK", "INSTAGRAM"), List.of(targetNiche), 1_000L, 500_000L, 1_000.0,
          queryAsOf, DataOrigin.SYN, creatorObs, asOfHistory);
      var extSnap = new ExtendedCampaignSnapshot(
          ExtendedCampaignSnapshot.SCHEMA_VERSION, baseSnap, brandId,
          List.of(new BrandPatternSpec(targetNiche, targetNiche, null, 0.85)), extensions);
      ScorerInputBoundary.acceptForScorer(extSnap);

      var universe = new CandidateUniverse(
          CandidateUniverse.SCHEMA_VERSION, queryCampaignId, snapshotId, DataOrigin.SYN, "PILOT_UNIVERSE", entries);

      String scorerTag = scorers.stream().map(ResearchScorer::scorerVersion).reduce((a, b) -> a + "," + b).orElse("none");
      var manifest = new ExperimentRunManifest(
          ExperimentRunManifest.SCHEMA_VERSION,
          uuid(snapshotId + ":" + spec.regime() + ":" + spec.mechanism()),
          "protocol.v1", PILOT_DGP_VERSION, "features.v1", scorerTag,
          spec.regime().name() + "|" + spec.mechanism().name() + "|" + spec.intensity().name(),
          Integer.toHexString(ResearchJsonMapper.toDeterministicJson(baseSnap).hashCode()),
          "pilotSeed=" + spec.seed(), DataOrigin.SYN, queryAsOf,
          Map.of("brands", String.valueOf(spec.brandCount()), "masters", String.valueOf(spec.masterCount())));

      reports.add(runner.evaluateWorld(manifest, extSnap, universe, oracles, scorers));
    }
    return reports;
  }

  private static boolean shouldDrop(PilotWorldSpec spec, double q, Random missRng) {
    double p = (spec.mechanism() == MissingnessMechanism.MCAR)
        ? spec.intensity().mcarRate()
        : clamp(spec.intensity().mnarScale() * Math.pow(1.0 - q, 1.5) * 2.2, 0.02, 0.90);
    return missRng.nextDouble() < p;
  }

  private static double clamp(double v, double min, double max) {
    return Math.max(min, Math.min(max, v));
  }

  private static double round4(double v) {
    return Math.round(v * 10_000.0) / 10_000.0;
  }

  private static UUID uuid(String text) {
    return UUID.nameUUIDFromBytes(text.getBytes(StandardCharsets.UTF_8));
  }
}
