package com.influencermatch.backend.recommendation.research.scorer;

import com.influencermatch.backend.brand.model.BrandPattern;
import com.influencermatch.backend.recommendation.engine.critic.DynamicWeightBlender;
import com.influencermatch.backend.recommendation.engine.critic.model.EngineWeights;
import com.influencermatch.backend.recommendation.engine.memory.BrandAffinityScorer;
import com.influencermatch.backend.recommendation.engine.memory.LearnedM4Evaluator.BrandReviewRecord;
import com.influencermatch.backend.recommendation.engine.memory.SemanticRelevanceScorer;
import com.influencermatch.backend.recommendation.engine.phase0.PolicyNormalizer;
import com.influencermatch.backend.recommendation.engine.phase0.enums.ErMethod;
import com.influencermatch.backend.recommendation.engine.phase0.model.ScoringPolicyConfig;
import com.influencermatch.backend.recommendation.engine.topsis.TopsisRankingEngine;
import com.influencermatch.backend.recommendation.research.adapters.LegacyResearchAdapter;
import com.influencermatch.backend.recommendation.research.contracts.CampaignCreatorSnapshot;
import com.influencermatch.backend.recommendation.research.contracts.CandidateUniverse;
import com.influencermatch.backend.recommendation.research.contracts.CandidateUniverse.CandidateEntry;
import com.influencermatch.backend.recommendation.research.contracts.ExtendedCampaignSnapshot;
import com.influencermatch.backend.recommendation.research.contracts.HistoricalCollaborationEvidence;
import com.influencermatch.backend.recommendation.research.contracts.MetricObservation;
import com.influencermatch.backend.recommendation.research.contracts.ObservedCreatorContextExtension;
import com.influencermatch.backend.recommendation.research.contracts.ObservedEvidenceRecord;
import com.influencermatch.backend.recommendation.research.contracts.ObservedEvidenceRecord.PostObservation;
import com.influencermatch.backend.recommendation.research.contracts.ResearchEnums.HardEligibilityStatus;
import com.influencermatch.backend.recommendation.research.contracts.ResearchEnums.MetricAvailability;
import com.influencermatch.backend.recommendation.research.eval.ExperimentRunner.ResearchScorer;
import com.influencermatch.backend.recommendation.research.scorer.OfflineLegacyFourMemoryScorer.ExcludedCandidateRecord;
import com.influencermatch.backend.recommendation.research.scorer.OfflineLegacyFourMemoryScorer.OfflineScoringResult;
import com.influencermatch.backend.recommendation.research.scorer.OfflineLegacyFourMemoryScorer.ScoredCandidateRecord;
import com.influencermatch.backend.recommendation.research.validation.ResearchContractValidator;
import com.influencermatch.backend.recommendation.research.validation.ScorerInputBoundary;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * G7B.2 Research-Only Evidence-Conditioned Shrinkage Scorer (evidence-shrinkage-v1).
 * - M1: Component-wise R/T/K shrinkage with cluster-design effective support (ICC rho = 0.50).
 * - M4: Hierarchical Empirical-Bayes shrinkage with leave-target-creator-out disjoint cohorts.
 * - Fixed-weight controlled comparison (CRITIC OFF, lambda = 0.0, MMR OFF).
 */
public final class EvidenceConditionedShrinkageScorer {

  public static final String SCORER_VERSION = "evidence-shrinkage-v1";
  public static final double WITHIN_BRAND_ICC_RHO = 0.50;
  public static final double KAPPA_R = 5.0;
  public static final double KAPPA_T = 5.0;
  public static final double KAPPA_K = 5.0;
  public static final double KAPPA_M4_DIRECT = 3.0;
  public static final double KAPPA_M4_NICHE = 5.0;
  public static final double KAPPA_M4_PLAT = 10.0;
  public static final int MIN_EMPIRICAL_PRIOR_SUPPORT = 5;

  public enum PriorMode {
    EMPIRICAL_OBSERVED,
    NEUTRAL_FIXED_050
  }

  public record ShrinkageDiagnostics(
      UUID creatorId,
      double nEffR, double nEffT, double nEffK,
      double lambdaR, double lambdaT, double lambdaK,
      double priorR, double priorT, double priorK,
      String m1PriorSource,
      double lambdaM4Plat, double lambdaM4Niche, double lambdaM4Direct,
      int directReviews, int nicheCohortReviews, int platCohortReviews,
      String m4PriorSource
  ) {}

  public static ResearchScorer asResearchScorer(PriorMode priorMode, boolean enforceM1Gate) {
    var instance = new EvidenceConditionedShrinkageScorer();
    return new ResearchScorer() {
      @Override
      public String scorerVersion() {
        return SCORER_VERSION + "+" + priorMode.name().toLowerCase() + (enforceM1Gate ? "+m1gate" : "") + "+fixedW";
      }

      @Override
      public OfflineScoringResult score(ExtendedCampaignSnapshot snapshot, CandidateUniverse universe) {
        return instance.score(snapshot, universe, priorMode, enforceM1Gate).result();
      }
    };
  }

  public record ShrinkageRunOutput(
      OfflineScoringResult result,
      Map<UUID, ShrinkageDiagnostics> diagnosticsByCreator
  ) {}

  public ShrinkageRunOutput score(
      ExtendedCampaignSnapshot rawExtended,
      CandidateUniverse universe,
      PriorMode priorMode,
      boolean enforceM1Gate) {

    ExtendedCampaignSnapshot extended = ScorerInputBoundary.acceptForScorer(rawExtended);
    CampaignCreatorSnapshot snapshot = extended.baseSnapshot();
    if (!snapshot.campaignId().equals(universe.campaignId())) {
      throw new IllegalArgumentException("CandidateUniverse campaignId does not match snapshot campaignId");
    }
    if (snapshot.dataOrigin() != universe.dataOrigin()) {
      throw new IllegalArgumentException("DATA_ORIGIN_MIXING: Universe origin != snapshot origin");
    }

    Instant asOf = snapshot.scoringAsOf();
    Instant cutoff24m = asOf.minus(730, ChronoUnit.DAYS);
    List<HistoricalCollaborationEvidence> replayedHistory = ResearchContractValidator.replayHistoricalAt(
        snapshot.historicalCollaborations(), asOf);

    Map<UUID, ObservedEvidenceRecord> evidenceById = snapshot.creators().stream()
        .collect(Collectors.toMap(ObservedEvidenceRecord::creatorId, e -> e, (a, b) -> a, LinkedHashMap::new));
    Map<UUID, String> platformByCreator = snapshot.creators().stream()
        .collect(Collectors.toMap(ObservedEvidenceRecord::creatorId, ObservedEvidenceRecord::platform, (a, b) -> a, LinkedHashMap::new));
    Map<UUID, ObservedCreatorContextExtension> contextById = extended.contextExtensions();

    List<BrandPattern> brandPatterns = LegacyResearchAdapter.toLegacyBrandPatterns(extended.brandPatterns());
    List<BrandReviewRecord> allReviews = LegacyResearchAdapter.toLegacyBrandReviews(
        replayedHistory, extended.brandId(), platformByCreator, contextById, null, asOf);

    String objective = snapshot.objective() != null ? snapshot.objective() : "AWARENESS";
    EngineWeights fixedWeights = new DynamicWeightBlender().blend(objective, Optional.empty(), 0.0);

    PolicyNormalizer normalizer = new PolicyNormalizer();
    SemanticRelevanceScorer semanticScorer = new SemanticRelevanceScorer();
    BrandAffinityScorer brandScorer = new BrandAffinityScorer();
    TopsisRankingEngine topsis = new TopsisRankingEngine();

    List<ScoredCandidateRecord> mainRanked = new ArrayList<>();
    List<ScoredCandidateRecord> provRanked = new ArrayList<>();
    List<ExcludedCandidateRecord> excluded = new ArrayList<>();
    Map<UUID, ShrinkageDiagnostics> diagnostics = new LinkedHashMap<>();

    for (CandidateEntry entry : universe.entries()) {
      UUID cid = entry.creatorId();
      if (entry.hardEligibility() != HardEligibilityStatus.ELIGIBLE) {
        excluded.add(new ExcludedCandidateRecord(cid, entry.hardEligibility().name(), entry.eligibilityReasons(), List.of()));
        continue;
      }
      ObservedEvidenceRecord obs = evidenceById.get(cid);
      if (obs == null) {
        excluded.add(new ExcludedCandidateRecord(cid, "MISSING_EVIDENCE", List.of("NO_OBSERVED_RECORD_IN_SNAPSHOT"), List.of()));
        continue;
      }

      ObservedCreatorContextExtension ext = contextById.get(cid);
      var prep = CommonResearchFeaturePreparer.prepare(obs, ext);
      if (prep.isInvalid()) {
        excluded.add(new ExcludedCandidateRecord(cid, "INVALID_DATA", prep.invalidReasons(), List.of()));
        continue;
      }

      String platform = prep.platform();
      Double erNorm = prep.erNorm();
      Double vrNorm = prep.vrNorm();
      Double stabNorm = prep.stabNorm();
      if (erNorm == null || vrNorm == null) {
        excluded.add(new ExcludedCandidateRecord(cid, "NOT_SCORABLE", List.of("MISSING_M2_ER_OR_VR"), List.of()));
        continue;
      }
      double effectiveStab = (stabNorm != null) ? stabNorm : 0.0;
      double sm2 = (erNorm + vrNorm + effectiveStab) / 3.0;

      // M1 Component-wise Shrinkage (R, T, K) with cluster-design effective support
      List<HistoricalCollaborationEvidence> creatorHist = replayedHistory.stream()
          .filter(h -> cid.equals(h.creatorId())
              && "COMPLETED".equalsIgnoreCase(h.status())
              && !h.eventTime().isBefore(cutoff24m))
          .toList();

      double[] priorRtk = resolveM1Prior(replayedHistory, platformByCreator, platform, cutoff24m, priorMode, cid);
      ComponentShrinkage rShr = shrinkComponent(creatorHist, "R", priorRtk[0], KAPPA_R);
      ComponentShrinkage tShr = shrinkComponent(creatorHist, "T", priorRtk[1], KAPPA_T);
      ComponentShrinkage kShr = shrinkComponent(creatorHist, "K", priorRtk[2], KAPPA_K);
      double sm1 = Math.max(0.0, Math.min(1.0, 0.50 * rShr.estimate() + 0.30 * tShr.estimate() + 0.20 * kShr.estimate()));
      String m1Avail = (rShr.nEff() + tShr.nEff() + kShr.nEff() > 0.0) ? "SHRUNK_AVAILABLE" : "PRIOR/NEUTRAL_FALLBACK";

      // M3 Semantic Relevance (from CommonResearchFeaturePreparer)
      double sm3Norm = prep.sm3Norm();
      boolean hasEmb = prep.hasEmbedding();

      // M4 Hierarchical Empirical-Bayes Shrinkage with leave-target-creator-out disjoint cohorts
      String niche = ext != null ? ext.niche() : null;
      String category = ext != null ? ext.category() : null;
      var sm4Exp = brandScorer.score(snapshot.niches(), niche, category, platform, brandPatterns);
      double explicitAnchor = sm4Exp.isConfigured() ? sm4Exp.sm4Norm() : 0.50;
      double anchor = (priorMode == PriorMode.NEUTRAL_FIXED_050 && !sm4Exp.isConfigured()) ? 0.50 : explicitAnchor;

      M4Shrinkage m4Shr = shrinkM4(cid, obs.masterId(), platform, niche, anchor, allReviews);

      diagnostics.put(cid, new ShrinkageDiagnostics(
          cid,
          rShr.nEff(), tShr.nEff(), kShr.nEff(),
          rShr.lambda(), tShr.lambda(), kShr.lambda(),
          priorRtk[0], priorRtk[1], priorRtk[2],
          priorMode == PriorMode.NEUTRAL_FIXED_050 ? "NEUTRAL_FIXED_050" : (priorRtk[3] > 0.5 ? "EMPIRICAL_PLATFORM_COHORT" : "NEUTRAL_FALLBACK_050"),
          m4Shr.lambdaPlat(), m4Shr.lambdaNiche(), m4Shr.lambdaDirect(),
          m4Shr.directCount(), m4Shr.nicheCohortCount(), m4Shr.platCohortCount(),
          m4Shr.source()));

      double matchScore = topsis.compute(sm1, sm2, sm3Norm, m4Shr.estimate(), fixedWeights).matchScore();
      boolean routeProvisional = (stabNorm == null) || (enforceM1Gate && creatorHist.size() < 3);
      String routing = routeProvisional ? "PROVISIONAL" : "MAIN";

      var rec = new ScoredCandidateRecord(
          cid, routing, sm1, sm2, sm3Norm, m4Shr.estimate(),
          erNorm, vrNorm, stabNorm != null ? stabNorm : Double.NaN,
          matchScore,
          (rShr.nEff() >= 2.0 && stabNorm != null) ? "HIGH" : "LOW",
          m1Avail, hasEmb, m4Shr.source(), List.of());

      if (routeProvisional) provRanked.add(rec); else mainRanked.add(rec);
    }

    sortDeterministic(mainRanked);
    sortDeterministic(provRanked);

    var result = new OfflineScoringResult(
        SCORER_VERSION + "+" + priorMode.name().toLowerCase() + (enforceM1Gate ? "+m1gate" : "") + "+fixedW",
        ScoringPolicyConfig.CALIBRATION_VERSION,
        snapshot.snapshotId(),
        snapshot.campaignId(),
        snapshot.scoringAsOf(),
        snapshot.dataOrigin(),
        universe.entries().stream().map(CandidateEntry::creatorId).toList(),
        fixedWeights,
        List.copyOf(mainRanked),
        List.copyOf(provRanked),
        List.copyOf(excluded));

    return new ShrinkageRunOutput(result, Map.copyOf(diagnostics));
  }

  private record ComponentShrinkage(double estimate, double nEff, double lambda) {}

  private static ComponentShrinkage shrinkComponent(
      List<HistoricalCollaborationEvidence> creatorHist, String component, double prior, double kappa) {
    Map<UUID, List<Double>> byBrand = new HashMap<>();
    double sum = 0.0;
    int totalCount = 0;
    for (HistoricalCollaborationEvidence h : creatorHist) {
      MetricObservation obs = switch (component) {
        case "R" -> h.rObservation();
        case "T" -> h.tObservation();
        case "K" -> h.kObservation();
        default -> null;
      };
      if (obs == null || obs.value() == null) continue;
      double val = Math.max(0.0, Math.min(1.0, obs.value()));
      UUID brandKey = h.brandId() != null ? h.brandId() : h.collaborationId();
      byBrand.computeIfAbsent(brandKey, k -> new ArrayList<>()).add(val);
      sum += val;
      totalCount++;
    }
    if (totalCount == 0) {
      return new ComponentShrinkage(prior, 0.0, 0.0);
    }
    double nEff = 0.0;
    for (List<Double> brandVals : byBrand.values()) {
      int nb = brandVals.size();
      nEff += nb / (1.0 + (nb - 1) * WITHIN_BRAND_ICC_RHO);
    }
    double lambda = nEff / (nEff + kappa);
    double sampleMean = sum / totalCount;
    double shrunk = lambda * sampleMean + (1.0 - lambda) * prior;
    return new ComponentShrinkage(shrunk, nEff, lambda);
  }

  private static double[] resolveM1Prior(
      List<HistoricalCollaborationEvidence> allHist,
      Map<UUID, String> platformByCreator,
      String targetPlatform,
      Instant cutoff24m,
      PriorMode mode,
      UUID excludeCreatorId) {
    if (mode == PriorMode.NEUTRAL_FIXED_050) {
      return new double[] { 0.50, 0.50, 0.50, 0.0 };
    }
    double rSum = 0.0, tSum = 0.0, kSum = 0.0;
    int rCnt = 0, tCnt = 0, kCnt = 0;
    for (HistoricalCollaborationEvidence h : allHist) {
      if (excludeCreatorId.equals(h.creatorId())) continue; // leave-target-creator-out prior
      if (!"COMPLETED".equalsIgnoreCase(h.status()) || h.eventTime().isBefore(cutoff24m)) continue;
      String p = platformByCreator.get(h.creatorId());
      if (p == null || !p.equalsIgnoreCase(targetPlatform)) continue;
      if (h.rObservation() != null && h.rObservation().value() != null) { rSum += h.rObservation().value(); rCnt++; }
      if (h.tObservation() != null && h.tObservation().value() != null) { tSum += h.tObservation().value(); tCnt++; }
      if (h.kObservation() != null && h.kObservation().value() != null) { kSum += h.kObservation().value(); kCnt++; }
    }
    double rPrior = (rCnt >= MIN_EMPIRICAL_PRIOR_SUPPORT) ? (rSum / rCnt) : 0.50;
    double tPrior = (tCnt >= MIN_EMPIRICAL_PRIOR_SUPPORT) ? (tSum / tCnt) : 0.50;
    double kPrior = (kCnt >= MIN_EMPIRICAL_PRIOR_SUPPORT) ? (kSum / kCnt) : 0.50;
    double empFlag = (rCnt >= MIN_EMPIRICAL_PRIOR_SUPPORT || tCnt >= MIN_EMPIRICAL_PRIOR_SUPPORT || kCnt >= MIN_EMPIRICAL_PRIOR_SUPPORT) ? 1.0 : 0.0;
    return new double[] { rPrior, tPrior, kPrior, empFlag };
  }

  private record M4Shrinkage(
      double estimate,
      double lambdaPlat, double lambdaNiche, double lambdaDirect,
      int directCount, int nicheCohortCount, int platCohortCount,
      String source
  ) {}

  private static M4Shrinkage shrinkM4(
      UUID creatorId, UUID masterId, String platform, String niche,
      double explicitAnchor, List<BrandReviewRecord> allReviews) {

    // Deduplicate by reviewId and partition into disjoint sets (leave-target-creator-out cohorts)
    Map<UUID, BrandReviewRecord> dedup = new LinkedHashMap<>();
    for (BrandReviewRecord r : allReviews) {
      if (r.reviewId() != null) dedup.putIfAbsent(r.reviewId(), r);
    }

    List<BrandReviewRecord> direct = new ArrayList<>();
    List<BrandReviewRecord> nicheCohort = new ArrayList<>();
    List<BrandReviewRecord> platCohort = new ArrayList<>();

    for (BrandReviewRecord r : dedup.values()) {
      boolean isTarget = creatorId.equals(r.creatorId()) || (masterId != null && masterId.equals(r.masterId()));
      if (isTarget) {
        direct.add(r);
        continue;
      }
      boolean platMatch = platform != null && platform.equalsIgnoreCase(r.platform());
      if (!platMatch) continue;
      boolean nicheMatch = niche != null && r.niche() != null && niche.equalsIgnoreCase(r.niche());
      if (nicheMatch) {
        nicheCohort.add(r);
      } else {
        platCohort.add(r);
      }
    }

    int platMasters = distinctMasters(platCohort);
    double lamPlat = (platMasters >= 2 && !platCohort.isEmpty())
        ? platCohort.size() / (platCohort.size() + KAPPA_M4_PLAT) : 0.0;
    double platMean = platCohort.stream().mapToDouble(r -> (r.brandFitRating() - 1.0) / 4.0).average().orElse(explicitAnchor);
    double muPlat = lamPlat * platMean + (1.0 - lamPlat) * explicitAnchor;

    int nicheMasters = distinctMasters(nicheCohort);
    double lamNiche = (nicheMasters >= 2 && !nicheCohort.isEmpty())
        ? nicheCohort.size() / (nicheCohort.size() + KAPPA_M4_NICHE) : 0.0;
    double nicheMean = nicheCohort.stream().mapToDouble(r -> (r.brandFitRating() - 1.0) / 4.0).average().orElse(muPlat);
    double muNiche = lamNiche * nicheMean + (1.0 - lamNiche) * muPlat;

    double lamDirect = !direct.isEmpty() ? direct.size() / (direct.size() + KAPPA_M4_DIRECT) : 0.0;
    double directMean = direct.stream().mapToDouble(r -> (r.brandFitRating() - 1.0) / 4.0).average().orElse(muNiche);
    double muDirect = lamDirect * directMean + (1.0 - lamDirect) * muNiche;

    String src = lamDirect > 0.0 ? "SHRUNK_DIRECT_HIERARCHY"
        : (lamNiche > 0.0 ? "SHRUNK_COHORT_NICHE" : (lamPlat > 0.0 ? "SHRUNK_COHORT_PLATFORM" : "EXPLICIT_OR_NEUTRAL_ANCHOR"));

    return new M4Shrinkage(
        Math.max(0.0, Math.min(1.0, muDirect)),
        lamPlat, lamNiche, lamDirect,
        direct.size(), nicheCohort.size(), platCohort.size(), src);
  }

  private static int distinctMasters(List<BrandReviewRecord> list) {
    Set<UUID> masters = new HashSet<>();
    for (BrandReviewRecord r : list) {
      masters.add(r.masterId() != null ? r.masterId() : r.creatorId());
    }
    return masters.size();
  }

  private static void sortDeterministic(List<ScoredCandidateRecord> list) {
    list.sort((a, b) -> {
      int cmp = Double.compare(b.matchScore(), a.matchScore());
      if (cmp != 0) return cmp;
      int cmp3 = Double.compare(b.sm3(), a.sm3());
      if (cmp3 != 0) return cmp3;
      int cmp2 = Double.compare(b.sm2(), a.sm2());
      if (cmp2 != 0) return cmp2;
      return a.creatorId().compareTo(b.creatorId());
    });
  }

  private static boolean hasInvalidMetric(ObservedEvidenceRecord obs) {
    return isInvalid(obs.followers()) || isInvalid(obs.avgViews()) || isInvalid(obs.avgLikes()) || isInvalid(obs.engagementRate())
        || (obs.followers() != null && obs.followers().value() != null && obs.followers().value() < 0)
        || (obs.engagementRate() != null && obs.engagementRate().value() != null
            && (obs.engagementRate().value() < 0 || obs.engagementRate().value() > ScoringPolicyConfig.ER_EXTREME_OUTLIER_THRESHOLD));
  }

  private static boolean isInvalid(MetricObservation obs) {
    return obs != null && obs.availability() == MetricAvailability.INVALID;
  }

  private static Double computeNativeErNorm(ObservedEvidenceRecord obs, String platform, PolicyNormalizer normalizer) {
    if (obs.engagementRate() == null || obs.engagementRate().value() == null) return null;
    ErMethod method = "FACEBOOK".equals(platform) ? ErMethod.BY_FOLLOWERS : ErMethod.BY_VIEWS;
    return normalizer.normalize(obs.engagementRate().value(), normalizer.resolveKEr(platform, method));
  }

  private static Double computeNativeVrNorm(ObservedEvidenceRecord obs, String platform, PolicyNormalizer normalizer) {
    if ("FACEBOOK".equals(platform) || obs.followers() == null || obs.followers().value() == null
        || obs.followers().value() <= 0.0 || obs.avgViews() == null || obs.avgViews().value() == null) {
      return null;
    }
    return normalizer.normalize(obs.avgViews().value() / obs.followers().value(), normalizer.resolveKVr(platform));
  }

  private static Double computeNativeStabNorm(ObservedEvidenceRecord obs) {
    List<Double> vals = new ArrayList<>();
    for (PostObservation p : obs.postWindowMetrics()) {
      if (p.views() != null && p.views().value() != null && p.views().value() >= 0.0) vals.add(p.views().value());
    }
    if (vals.size() < 2) return null;
    double mean = vals.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
    if (mean <= 0.0) return null;
    double var = 0.0;
    for (double v : vals) var += Math.pow(v - mean, 2);
    return 1.0 / (1.0 + Math.sqrt(var / (vals.size() - 1)) / mean);
  }
}
