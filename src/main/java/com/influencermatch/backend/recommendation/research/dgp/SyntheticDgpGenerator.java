package com.influencermatch.backend.recommendation.research.dgp;

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
import com.influencermatch.backend.recommendation.research.contracts.ResearchEnums.MeasurementCompleteness;
import com.influencermatch.backend.recommendation.research.contracts.ResearchEnums.MetricAvailability;
import com.influencermatch.backend.recommendation.research.contracts.ResearchEnums.MetricUnit;
import com.influencermatch.backend.recommendation.research.contracts.ResearchEnums.OracleGradeSource;
import com.influencermatch.backend.recommendation.research.dgp.DeterministicRngStreams.StreamName;
import com.influencermatch.backend.recommendation.research.dgp.DgpConfig.MissingnessMechanism;
import com.influencermatch.backend.recommendation.research.validation.ResearchContractValidator;
import com.influencermatch.backend.recommendation.research.validation.ResearchJsonMapper;
import com.influencermatch.backend.recommendation.research.validation.ScorerInputBoundary;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

public final class SyntheticDgpGenerator {

  private static final List<String> NICHES = List.of("BEAUTY", "TECH", "FOOD", "FASHION");
  private static final List<String> PLATFORMS = List.of("TIKTOK", "INSTAGRAM", "FACEBOOK");

  public record GeneratedWorldBundle(
      ExperimentRunManifest manifest,
      CampaignCreatorSnapshot scorerSnapshot,
      CandidateUniverse candidateUniverse,
      List<OracleGroundTruthRecord> oracleGroundTruth,
      List<HistoricalCollaborationEvidence> allHistoricalIncludingFuture
  ) {}

  public record ExtendedWorldBundle(
      GeneratedWorldBundle baseBundle,
      ExtendedCampaignSnapshot extendedSnapshot
  ) {}

  public ExtendedWorldBundle generateExtended(DgpConfig config) {
    GeneratedWorldBundle base = generate(config);
    DeterministicRngStreams rng = new DeterministicRngStreams(config.baseSeed(), config.split());
    Random latentRng = rng.stream(StreamName.LATENT_WORLD);
    Random pubRng = rng.stream(StreamName.PUBLIC_MEASUREMENT);

    String targetNiche = NICHES.get(latentRng.nextInt(NICHES.size()));
    UUID brandId = deterministicUuid(config.configHash() + ":brand");
    Map<UUID, ObservedCreatorContextExtension> extensions = new java.util.LinkedHashMap<>();

    for (ObservedEvidenceRecord rec : base.scorerSnapshot().creators()) {
      String creatorNiche = NICHES.get(latentRng.nextInt(NICHES.size()));
      // Observed noisy semantic similarity derived strictly from niche match + measurement noise (never latentQuality)
      double rawCosine = clamp((creatorNiche.equals(targetNiche) ? 0.72 : 0.18) + pubRng.nextGaussian() * 0.10, -1.0, 1.0);
      MetricObservation simObs = MetricObservation.observed(
          round4(rawCosine), MetricUnit.COSINE_SIMILARITY, 1, 1, "SYN_EMBEDDER", rec.observedAt(), rec.knownAt());
      extensions.put(rec.creatorId(), new ObservedCreatorContextExtension(
          ObservedCreatorContextExtension.SCHEMA_VERSION,
          rec.creatorId(), DataOrigin.SYN, creatorNiche, creatorNiche, simObs, rec.observedAt(), rec.knownAt()));
    }

    var extended = new ExtendedCampaignSnapshot(
        ExtendedCampaignSnapshot.SCHEMA_VERSION,
        base.scorerSnapshot(),
        brandId,
        List.of(new BrandPatternSpec(targetNiche, targetNiche, null, 0.85)),
        extensions);
    ScorerInputBoundary.acceptForScorer(extended);
    return new ExtendedWorldBundle(base, extended);
  }

  public GeneratedWorldBundle generate(DgpConfig config) {
    DeterministicRngStreams rng = new DeterministicRngStreams(config.baseSeed(), config.split());
    Random latentRng = rng.stream(StreamName.LATENT_WORLD);
    Random bookingRng = rng.stream(StreamName.BOOKING_EXPOSURE);
    Random histRng = rng.stream(StreamName.HISTORICAL_OUTCOMES);
    Random pubRng = rng.stream(StreamName.PUBLIC_MEASUREMENT);
    Random missRng = rng.stream(StreamName.MISSINGNESS);
    Random oracleRng = rng.stream(StreamName.ORACLE_EVALUATION);

    Instant asOf = config.scoringAsOf();
    UUID campaignId = deterministicUuid(config.configHash() + ":campaign");
    UUID brandId = deterministicUuid(config.configHash() + ":brand");
    String targetNiche = NICHES.get(latentRng.nextInt(NICHES.size()));
    double brandBias = latentRng.nextGaussian() * config.regime().brandBiasStd();
    int dayOfMonth = asOf.atOffset(ZoneOffset.UTC).getDayOfMonth();
    double stationaryCycle = 0.05 * Math.sin(2.0 * Math.PI * dayOfMonth / 30.0);

    List<ObservedEvidenceRecord> creators = new ArrayList<>();
    List<CandidateEntry> universeEntries = new ArrayList<>();
    List<HistoricalCollaborationEvidence> snapshotHistory = new ArrayList<>();
    List<HistoricalCollaborationEvidence> allHistoryWithFuture = new ArrayList<>();
    List<OracleGroundTruthRecord> oracles = new ArrayList<>();

    for (int i = 0; i < config.creatorCount(); i++) {
      UUID creatorId = deterministicUuid(config.configHash() + ":creator:" + i);
      UUID masterId = deterministicUuid(config.configHash() + ":master:" + i);
      String platform = PLATFORMS.get(i % PLATFORMS.size());
      String niche = NICHES.get(latentRng.nextInt(NICHES.size()));

      double latentQuality = clamp(0.55 + latentRng.nextGaussian() * 0.18, 0.05, 0.98);
      double latentReliability = clamp(0.70 + latentRng.nextGaussian() * 0.15, 0.10, 0.99);
      double baseFit = niche.equals(targetNiche) ? 0.92 : 0.42;
      double brandCreatorFit = clamp(baseFit + latentRng.nextGaussian() * 0.08, 0.0, 1.0);

      // Oracle expected_future_utility: purely from latent variables + cycle, never from any scorer
      double expectedUtility = clamp(
          0.45 * latentQuality + 0.35 * brandCreatorFit + 0.15 * latentReliability + stationaryCycle,
          0.0, 1.0);

      // Exposure / booking probability (independent of missingness mechanism)
      boolean exposed = bookingRng.nextDouble() < clamp(0.25 + 0.55 * brandCreatorFit, 0.05, 0.95);
      boolean selected = exposed && oracleRng.nextDouble() < clamp(0.20 + 0.65 * expectedUtility, 0.05, 0.95);

      oracles.add(new OracleGroundTruthRecord(
          OracleGroundTruthRecord.SCHEMA_VERSION,
          campaignId, creatorId, DataOrigin.SYN,
          OracleGradeSource.SYNTHETIC_EXPECTED_UTILITY, asOf,
          expectedUtility, null, null, exposed, selected,
          config.regime() == DgpConfig.DgpRegime.DGP_B
              ? "regime=DGP_B_WEAK_BIASED_NON_TRANSFERABLE_HISTORY"
              : "regime=DGP_A_PREDICTIVE_HISTORY"));

      // Public performance & missingness
      Instant obsAt = asOf.minus(Duration.ofHours(2 + pubRng.nextInt(72)));
      Instant knownAt = obsAt.plus(Duration.ofHours(1 + pubRng.nextInt(12)));
      if (knownAt.isAfter(asOf)) {
        knownAt = asOf;
      }

      long followerCount = Math.round(5_000 + latentQuality * 95_000 + pubRng.nextDouble() * 5_000);
      MetricObservation followersObs = MetricObservation.observed(
          followerCount, MetricUnit.COUNT, 1, 1, "SYN_DGP", obsAt, knownAt);

      double trueEr = clamp(0.015 + 0.08 * latentQuality + pubRng.nextGaussian() * 0.008, 0.0, 0.40);
      double trueAvgViews = followerCount * clamp(0.10 + 0.80 * latentQuality + pubRng.nextGaussian() * 0.10, 0.0, 3.0);
      double trueAvgLikes = trueAvgViews * trueEr;

      boolean isFacebook = "FACEBOOK".equals(platform);
      boolean isMissingViews = shouldDrop(config, latentQuality, missRng);
      boolean isMissingEr = shouldDrop(config, latentQuality, missRng);

      MetricObservation avgViewsObs;
      if (isFacebook) {
        avgViewsObs = MetricObservation.notApplicable(MetricUnit.COUNT, "SYN_DGP_FB", obsAt, knownAt);
      } else if (i % 15 == 0) {
        // Explicit full observed zero case
        avgViewsObs = MetricObservation.observed(0.0, MetricUnit.COUNT, 3, 3, "SYN_DGP", obsAt, knownAt);
      } else if (i % 15 == 1) {
        // Partial observed zero (2 of 3 posts)
        avgViewsObs = MetricObservation.observed(0.0, MetricUnit.COUNT, 2, 3, "SYN_DGP", obsAt, knownAt);
      } else if (i % 15 == 4) {
        // Invalid metric case on video platform (distinct from missing and NOT_APPLICABLE)
        avgViewsObs = MetricObservation.invalid(MetricUnit.COUNT, "-999", 3, "SYN_DGP", obsAt, knownAt);
      } else if (isMissingViews) {
        avgViewsObs = MetricObservation.missing(MetricUnit.COUNT, 3, "SYN_DGP", obsAt, knownAt);
      } else if (pubRng.nextDouble() < 0.25) {
        avgViewsObs = MetricObservation.observed(Math.round(trueAvgViews), MetricUnit.COUNT, 2, 3, "SYN_DGP", obsAt, knownAt);
      } else {
        avgViewsObs = MetricObservation.observed(Math.round(trueAvgViews), MetricUnit.COUNT, 3, 3, "SYN_DGP", obsAt, knownAt);
      }

      MetricObservation erObs = isMissingEr
          ? MetricObservation.missing(MetricUnit.DECIMAL_RATIO, 3, "SYN_DGP", obsAt, knownAt)
          : MetricObservation.observed(round4(trueEr), MetricUnit.DECIMAL_RATIO, 3, 3, "SYN_DGP", obsAt, knownAt);

      MetricObservation avgLikesObs = MetricObservation.observed(
          Math.round(trueAvgLikes), MetricUnit.COUNT, 3, 3, "SYN_DGP", obsAt, knownAt);

      int postLoops = (avgViewsObs.observedCount() != null && avgViewsObs.observedCount() >= 2) ? avgViewsObs.observedCount() : 1;
      List<PostObservation> posts = new ArrayList<>();
      for (int pIdx = 0; pIdx < postLoops; pIdx++) {
        posts.add(new PostObservation("syn-" + i + "-" + pIdx, obsAt.minus(Duration.ofDays(pIdx + 1L)),
            avgViewsObs, avgLikesObs, null, null));
      }

      creators.add(new ObservedEvidenceRecord(
          ObservedEvidenceRecord.SCHEMA_VERSION,
          creatorId, masterId, platform, DataOrigin.SYN,
          "snap-" + config.configHash(), obsAt, knownAt,
          followersObs, avgViewsObs, avgLikesObs, null, null, erObs, posts));

      universeEntries.add(new CandidateEntry(
          creatorId, masterId, platform, HardEligibilityStatus.ELIGIBLE, List.of()));

      // Historical collaborations: never booked / incomplete / completed-missing-outcome / completed / future
      int bookingScenario = i % 5;
      if (bookingScenario != 0) {
        int collabCount = (bookingScenario == 3) ? (2 + histRng.nextInt(3)) : 1;
        for (int cIdx = 0; cIdx < collabCount; cIdx++) {
          UUID collabId = deterministicUuid(config.configHash() + ":collab:" + i + ":" + cIdx);
          UUID pastBrandId = (cIdx == 0) ? brandId : deterministicUuid(config.configHash() + ":otherbrand:" + cIdx);
          UUID pastCampaignId = deterministicUuid(config.configHash() + ":pastcamp:" + i + ":" + cIdx);
          Instant eventTime = asOf.minus(Duration.ofDays(10L + cIdx * 15L));
          Instant histKnownAt = eventTime.plus(Duration.ofHours(6));

          double transfer = config.regime().historyTransferWeight();
          double noise = histRng.nextDouble();
          double histOutcome = clamp(transfer * latentQuality + (1.0 - transfer) * noise, 0.0, 1.0);
          double reviewScore = clamp(histOutcome + brandBias + histRng.nextGaussian() * 0.05, 0.0, 1.0);

          boolean dropOutcome = (bookingScenario == 2) || shouldDrop(config, latentQuality, missRng);
          boolean dropReview = shouldDrop(config, latentQuality, missRng);
          String status = (bookingScenario == 1) ? "IN_PROGRESS" : "COMPLETED";

          MetricObservation rObs = ("IN_PROGRESS".equals(status) || dropOutcome)
              ? MetricObservation.missing(MetricUnit.DECIMAL_RATIO, 1, "SYN_HIST", eventTime, histKnownAt)
              : MetricObservation.observed(round4(histOutcome), MetricUnit.DECIMAL_RATIO, 1, 1, "SYN_HIST", eventTime, histKnownAt);
          MetricObservation revObs = ("IN_PROGRESS".equals(status) || dropReview)
              ? MetricObservation.missing(MetricUnit.DECIMAL_RATIO, 1, "SYN_M4", eventTime, histKnownAt)
              : MetricObservation.observed(round4(reviewScore), MetricUnit.DECIMAL_RATIO, 1, 1, "SYN_M4", eventTime, histKnownAt);

          var rev1 = new HistoricalCollaborationEvidence(
              HistoricalCollaborationEvidence.SCHEMA_VERSION,
              collabId, creatorId, masterId, pastBrandId, pastCampaignId,
              DataOrigin.SYN, eventTime, histKnownAt, 1, status,
              rObs, rObs, rObs, revObs);
          snapshotHistory.add(rev1);
          allHistoryWithFuture.add(rev1);

          // Add a future revision for replay leakage testing
          if (cIdx == 0) {
            Instant futureKnownAt = asOf.plus(Duration.ofDays(5));
            allHistoryWithFuture.add(new HistoricalCollaborationEvidence(
                HistoricalCollaborationEvidence.SCHEMA_VERSION,
                collabId, creatorId, masterId, pastBrandId, pastCampaignId,
                DataOrigin.SYN, eventTime, futureKnownAt, 2, "DISPUTED",
                MetricObservation.observed(0.0, MetricUnit.DECIMAL_RATIO, 1, 1, "SYN_HIST", eventTime, futureKnownAt),
                null, null, null));
          }
        }
      }
    }

    String snapshotId = "snap-" + config.configHash();
    var snapshot = new CampaignCreatorSnapshot(
        CampaignCreatorSnapshot.SCHEMA_VERSION,
        snapshotId, campaignId, "AWARENESS",
        List.of("TIKTOK", "INSTAGRAM", "FACEBOOK"), List.of(targetNiche),
        1_000L, 500_000L, 1_000.0, asOf, DataOrigin.SYN,
        creators, snapshotHistory);

    // Strictly validate through ScorerInputBoundary (no oracle, no future leakage)
    ScorerInputBoundary.acceptForScorer(snapshot);

    var universe = new CandidateUniverse(
        CandidateUniverse.SCHEMA_VERSION, campaignId, snapshotId, DataOrigin.SYN, "DGP_UNIVERSE", universeEntries);

    String dataHash = sha256Short(ResearchJsonMapper.toDeterministicJson(snapshot));
    var manifest = new ExperimentRunManifest(
        ExperimentRunManifest.SCHEMA_VERSION,
        deterministicUuid(config.configHash() + ":run"),
        "protocol.v1", config.dgpVersion(), "features.v1", "NONE_DGP_ONLY",
        config.configHash(), dataHash,
        "baseSeed=" + config.baseSeed() + ",split=" + config.split().name(),
        DataOrigin.SYN, asOf,
        Map.of("regime", config.regime().name(),
            "missingness", config.missingnessMechanism().name() + ":" + config.missingnessIntensity().name()));

    return new GeneratedWorldBundle(manifest, snapshot, universe, List.copyOf(oracles), List.copyOf(allHistoryWithFuture));
  }

  private static boolean shouldDrop(DgpConfig config, double latentQuality, Random missRng) {
    double p = (config.missingnessMechanism() == MissingnessMechanism.MCAR)
        ? config.missingnessIntensity().mcarRate()
        : clamp(config.missingnessIntensity().mnarScale() * Math.pow(1.0 - latentQuality, 1.5) * 2.2, 0.02, 0.90);
    return missRng.nextDouble() < p;
  }

  private static double clamp(double v, double min, double max) {
    return Math.max(min, Math.min(max, v));
  }

  private static double round4(double v) {
    return Math.round(v * 10_000.0) / 10_000.0;
  }

  private static UUID deterministicUuid(String seedText) {
    return UUID.nameUUIDFromBytes(seedText.getBytes(StandardCharsets.UTF_8));
  }

  private static String sha256Short(String text) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest).substring(0, 16);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }
}
