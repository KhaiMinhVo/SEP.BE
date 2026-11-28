package com.influencermatch.backend.recommendation.research.scorer;

import com.influencermatch.backend.brand.model.BrandPattern;
import com.influencermatch.backend.campaign.model.Campaign;
import com.influencermatch.backend.campaign.model.HistoricalCampaign;
import com.influencermatch.backend.creator.dto.RecommendationCandidateProjection;
import com.influencermatch.backend.creator.model.Post;
import com.influencermatch.backend.recommendation.engine.confidence.ConfidenceCalculator;
import com.influencermatch.backend.recommendation.engine.critic.CriticWeightCalculator;
import com.influencermatch.backend.recommendation.engine.critic.DynamicWeightBlender;
import com.influencermatch.backend.recommendation.engine.critic.model.EngineWeights;
import com.influencermatch.backend.recommendation.engine.memory.BrandAffinityScorer;
import com.influencermatch.backend.recommendation.engine.memory.LearnedM4Evaluator;
import com.influencermatch.backend.recommendation.engine.memory.LearnedM4Evaluator.BrandReviewRecord;
import com.influencermatch.backend.recommendation.engine.memory.M1HistoricalScorer;
import com.influencermatch.backend.recommendation.engine.memory.SemanticRelevanceScorer;
import com.influencermatch.backend.recommendation.engine.memory.model.CandidateMemoryScores;
import com.influencermatch.backend.recommendation.engine.phase0.EvidenceValidator;
import com.influencermatch.backend.recommendation.engine.phase0.PolicyNormalizer;
import com.influencermatch.backend.recommendation.engine.phase0.enums.ErMethod;
import com.influencermatch.backend.recommendation.engine.phase0.model.ScoringPolicyConfig;
import com.influencermatch.backend.recommendation.engine.topsis.TopsisRankingEngine;
import com.influencermatch.backend.recommendation.engine.topsis.model.TopsisResult;
import com.influencermatch.backend.recommendation.research.adapters.LegacyResearchAdapter;
import com.influencermatch.backend.recommendation.research.contracts.CampaignCreatorSnapshot;
import com.influencermatch.backend.recommendation.research.contracts.CandidateUniverse;
import com.influencermatch.backend.recommendation.research.contracts.CandidateUniverse.CandidateEntry;
import com.influencermatch.backend.recommendation.research.contracts.ExtendedCampaignSnapshot;
import com.influencermatch.backend.recommendation.research.contracts.MetricObservation;
import com.influencermatch.backend.recommendation.research.contracts.ObservedCreatorContextExtension;
import com.influencermatch.backend.recommendation.research.contracts.ObservedEvidenceRecord;
import com.influencermatch.backend.recommendation.research.contracts.ObservedEvidenceRecord.PostObservation;
import com.influencermatch.backend.recommendation.research.contracts.ResearchEnums.HardEligibilityStatus;
import com.influencermatch.backend.recommendation.research.contracts.ResearchEnums.MetricAvailability;
import com.influencermatch.backend.recommendation.research.eval.ExperimentRunner.ResearchScorer;
import com.influencermatch.backend.recommendation.research.scorer.OfflineLegacyFourMemoryScorer.ExcludedCandidateRecord;
import com.influencermatch.backend.recommendation.research.scorer.OfflineLegacyFourMemoryScorer.OfflineScoringResult;
import com.influencermatch.backend.recommendation.research.scorer.OfflineLegacyFourMemoryScorer.PolicyVariant;
import com.influencermatch.backend.recommendation.research.scorer.OfflineLegacyFourMemoryScorer.ScoredCandidateRecord;
import com.influencermatch.backend.recommendation.research.validation.ScorerInputBoundary;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * G7A Missing-Evidence Research Baselines reading research-native contracts
 * via ScorerInputBoundary and preserving availability, counts, and provenance.
 */
public final class ResearchBaselineScorers {

  public enum BaselineMethod {
    LEGACY_REFERENCE_V1("legacy-reference-v1"),
    STRICT_GATING_BASELINE_V1("strict-gating-v1"),
    NEUTRAL_IMPUTATION_BASELINE_V1("neutral-imputation-v1"),
    AVAILABLE_FEATURE_RENORMALISATION_BASELINE_V1("afr-topsis-v1");

    private final String versionId;

    BaselineMethod(String versionId) {
      this.versionId = versionId;
    }

    public String versionId() {
      return versionId;
    }
  }

  private ResearchBaselineScorers() {}

  public static ResearchScorer create(BaselineMethod method, boolean enforceM1Gate) {
    return create(method, enforceM1Gate, true);
  }

  public static ResearchScorer create(BaselineMethod method, boolean enforceM1Gate, boolean useCritic) {
    return new ResearchScorer() {
      @Override
      public String scorerVersion() {
        return method.versionId() + (enforceM1Gate ? "+m1gate" : "") + (!useCritic ? "+fixedW" : "");
      }

      @Override
      public OfflineScoringResult score(ExtendedCampaignSnapshot snapshot, CandidateUniverse universe) {
        if (method == BaselineMethod.LEGACY_REFERENCE_V1) {
          return new OfflineLegacyFourMemoryScorer().score(
              snapshot, universe, PolicyVariant.CORRECTED_REFERENCE_V1, enforceM1Gate, useCritic);
        }
        return scoreNativeBaseline(snapshot, universe, method, enforceM1Gate, useCritic);
      }
    };
  }

  private record NativeEval(
      UUID creatorId,
      double sm1, boolean m1Avail, String m1AvailabilityTag,
      double sm2, boolean m2Avail, double erNorm, double vrNorm, double stabNorm,
      double sm3, boolean m3Avail,
      double sm4, boolean m4Avail, String m4Source,
      boolean routeProvisional,
      boolean excluded,
      String excludedRouting,
      List<String> reasons
  ) {}

  private static OfflineScoringResult scoreNativeBaseline(
      ExtendedCampaignSnapshot rawExtended,
      CandidateUniverse universe,
      BaselineMethod method,
      boolean enforceM1Gate,
      boolean useCritic) {

    ExtendedCampaignSnapshot extended = ScorerInputBoundary.acceptForScorer(rawExtended);
    CampaignCreatorSnapshot snapshot = extended.baseSnapshot();
    if (!snapshot.campaignId().equals(universe.campaignId())) {
      throw new IllegalArgumentException("CandidateUniverse campaignId does not match snapshot campaignId");
    }
    if (snapshot.dataOrigin() != universe.dataOrigin()) {
      throw new IllegalArgumentException("DATA_ORIGIN_MIXING: Universe origin != snapshot origin");
    }

    Map<UUID, ObservedEvidenceRecord> evidenceById = snapshot.creators().stream()
        .collect(Collectors.toMap(ObservedEvidenceRecord::creatorId, e -> e, (a, b) -> a, LinkedHashMap::new));
    Map<UUID, String> platformByCreator = snapshot.creators().stream()
        .collect(Collectors.toMap(ObservedEvidenceRecord::creatorId, ObservedEvidenceRecord::platform, (a, b) -> a, LinkedHashMap::new));
    Map<UUID, ObservedCreatorContextExtension> contextById = extended.contextExtensions();

    List<BrandPattern> brandPatterns = LegacyResearchAdapter.toLegacyBrandPatterns(extended.brandPatterns());
    List<BrandReviewRecord> reviews = LegacyResearchAdapter.toLegacyBrandReviews(
        snapshot.historicalCollaborations(), extended.brandId(), platformByCreator, contextById, null, snapshot.scoringAsOf());

    LocalDateTime scoringAsOfLdt = LocalDateTime.ofInstant(snapshot.scoringAsOf(), ZoneOffset.UTC);
    String objective = snapshot.objective() != null ? snapshot.objective() : "AWARENESS";

    M1HistoricalScorer m1Scorer = new M1HistoricalScorer();
    PolicyNormalizer normalizer = new PolicyNormalizer();
    SemanticRelevanceScorer semanticScorer = new SemanticRelevanceScorer();
    BrandAffinityScorer brandScorer = new BrandAffinityScorer();
    LearnedM4Evaluator m4Evaluator = new LearnedM4Evaluator();

    List<NativeEval> mainEvals = new ArrayList<>();
    List<NativeEval> provEvals = new ArrayList<>();
    List<ExcludedCandidateRecord> excluded = new ArrayList<>();

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

      NativeEval eval = evaluateNativeCandidate(
          obs, contextById.get(cid), snapshot, brandPatterns, reviews, scoringAsOfLdt,
          method, enforceM1Gate, m1Scorer, normalizer, semanticScorer, brandScorer, m4Evaluator);

      if (eval.excluded()) {
        excluded.add(new ExcludedCandidateRecord(cid, eval.excludedRouting(), eval.reasons(), List.of()));
      } else if (eval.routeProvisional()) {
        provEvals.add(eval);
      } else {
        mainEvals.add(eval);
      }
    }

    // Compute weights via CRITIC + DynamicWeightBlender on main pool (or fixed business weights when useCritic=false)
    List<double[]> matrix = mainEvals.stream()
        .map(e -> new double[] { e.sm1(), e.sm2(), e.sm3(), e.sm4() })
        .toList();
    Optional<double[]> criticOpt = (useCritic && matrix.size() >= 2)
        ? new CriticWeightCalculator().calculate(matrix) : Optional.empty();
    EngineWeights weights = new DynamicWeightBlender().blend(objective, criticOpt, useCritic ? DynamicWeightBlender.DEFAULT_LAMBDA : 0.0);

    List<ScoredCandidateRecord> mainRanked = rankEvals(mainEvals, "MAIN", weights, method);
    List<ScoredCandidateRecord> provRanked = rankEvals(provEvals, "PROVISIONAL", weights, method);

    return new OfflineScoringResult(
        method.versionId() + (enforceM1Gate ? "+m1gate" : "") + (!useCritic ? "+fixedW" : ""),
        ScoringPolicyConfig.CALIBRATION_VERSION,
        snapshot.snapshotId(),
        snapshot.campaignId(),
        snapshot.scoringAsOf(),
        snapshot.dataOrigin(),
        universe.entries().stream().map(CandidateEntry::creatorId).toList(),
        weights,
        mainRanked,
        provRanked,
        List.copyOf(excluded));
  }

  private static NativeEval evaluateNativeCandidate(
      ObservedEvidenceRecord obs,
      ObservedCreatorContextExtension ext,
      CampaignCreatorSnapshot snapshot,
      List<BrandPattern> brandPatterns,
      List<BrandReviewRecord> reviews,
      LocalDateTime scoringAsOfLdt,
      BaselineMethod method,
      boolean enforceM1Gate,
      M1HistoricalScorer m1Scorer,
      PolicyNormalizer normalizer,
      SemanticRelevanceScorer semanticScorer,
      BrandAffinityScorer brandScorer,
      LearnedM4Evaluator m4Evaluator) {

    // 1. Common M2/M3 preparation + INVALID contract check
    var prep = CommonResearchFeaturePreparer.prepare(obs, ext);
    if (prep.isInvalid()) {
      return new NativeEval(obs.creatorId(), 0, false, "INVALID", 0, false, Double.NaN, Double.NaN, Double.NaN,
          0, false, 0, false, "INVALID", false, true, "INVALID_DATA", prep.invalidReasons());
    }

    // 2. M1 evaluation
    List<HistoricalCampaign> hist = LegacyResearchAdapter.toLegacyHistoricalCampaigns(
        snapshot.historicalCollaborations(), obs.creatorId(), snapshot.scoringAsOf());
    M1HistoricalScorer.M1Result m1Res = m1Scorer.evaluate(hist, snapshot.objective(), scoringAsOfLdt);
    boolean m1Avail = m1Res.isAvailable();
    double sm1 = m1Avail ? m1Res.sm1Norm() : 0.50;
    String m1Tag = m1Avail ? "AVAILABLE" : "PRIOR/NEUTRAL_FALLBACK";

    // 3. M2 native evaluation (from CommonResearchFeaturePreparer)
    String platform = prep.platform();
    Double erNorm = prep.erNorm();
    Double vrNorm = prep.vrNorm();
    Double stabNorm = prep.stabNorm();

    boolean erOk = erNorm != null && Double.isFinite(erNorm);
    boolean vrOk = vrNorm != null && Double.isFinite(vrNorm);
    boolean stabOk = stabNorm != null && Double.isFinite(stabNorm);

    double sm2;
    boolean m2Avail;
    if (method == BaselineMethod.NEUTRAL_IMPUTATION_BASELINE_V1) {
      double e = erOk ? erNorm : 0.50;
      double v = vrOk ? vrNorm : 0.50;
      double s = stabOk ? stabNorm : 0.50;
      sm2 = (e + v + s) / 3.0;
      m2Avail = true;
    } else if (method == BaselineMethod.AVAILABLE_FEATURE_RENORMALISATION_BASELINE_V1) {
      List<Double> availM2 = new ArrayList<>();
      if (erOk) availM2.add(erNorm);
      if (vrOk) availM2.add(vrNorm);
      if (stabOk) availM2.add(stabNorm);
      if (availM2.isEmpty()) {
        sm2 = Double.NaN;
        m2Avail = false;
      } else {
        sm2 = availM2.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        m2Avail = true;
      }
    } else { // STRICT_GATING_BASELINE_V1
      if (!erOk || !vrOk) {
        return new NativeEval(obs.creatorId(), sm1, m1Avail, m1Tag, Double.NaN, false,
            erOk ? erNorm : Double.NaN, vrOk ? vrNorm : Double.NaN, stabOk ? stabNorm : Double.NaN,
            0.50, false, 0.50, false, "NONE", false, true, "NOT_SCORABLE",
            List.of("STRICT_M2_MISSING_ER_OR_VR"));
      }
      sm2 = (erNorm + vrNorm + (stabOk ? stabNorm : 0.0)) / 3.0;
      m2Avail = true;
    }

    // 4. M3 native evaluation (from CommonResearchFeaturePreparer)
    boolean m3Avail = prep.hasEmbedding();
    double sm3 = prep.sm3Norm();

    // 5. M4 native evaluation
    String niche = ext != null ? ext.niche() : null;
    String category = ext != null ? ext.category() : null;
    var sm4Exp = brandScorer.score(snapshot.niches(), niche, category, platform, brandPatterns);
    var m4Eval = m4Evaluator.evaluate(obs.creatorId(), obs.masterId(), platform, niche, sm4Exp.sm4Norm(), reviews);
    boolean m4HasLearned = m4Eval.getM4LearnedScore() != null;
    boolean m4HasExplicit = sm4Exp.isConfigured() && (niche != null || category != null);
    boolean m4Avail = m4HasLearned || m4HasExplicit;
    double sm4 = m4Avail ? m4Eval.getM4EffectiveScore() : 0.50;
    String m4Src = m4Avail ? m4Eval.getM4Source() : "PRIOR/NEUTRAL_FALLBACK";

    // 6. Routing & all-missing guard
    if (method == BaselineMethod.AVAILABLE_FEATURE_RENORMALISATION_BASELINE_V1
        && !m1Avail && !m2Avail && !m3Avail && !m4Avail) {
      return new NativeEval(obs.creatorId(), sm1, false, m1Tag, Double.NaN, false,
          Double.NaN, Double.NaN, Double.NaN, 0.50, false, 0.50, false, m4Src,
          false, true, "NOT_SCORABLE", List.of("ALL_FEATURES_MISSING"));
    }

    boolean routeProvisional = false;
    if (enforceM1Gate && !m1Avail) {
      routeProvisional = true;
    }
    if (method == BaselineMethod.STRICT_GATING_BASELINE_V1 && (!m1Avail || !stabOk || !m3Avail || !m4Avail)) {
      routeProvisional = true;
    }

    return new NativeEval(
        obs.creatorId(),
        sm1, m1Avail, m1Tag,
        sm2, m2Avail, erOk ? erNorm : Double.NaN, vrOk ? vrNorm : Double.NaN, stabOk ? stabNorm : Double.NaN,
        sm3, m3Avail,
        sm4, m4Avail, m4Src,
        routeProvisional, false, null, List.of());
  }

  private static List<ScoredCandidateRecord> rankEvals(
      List<NativeEval> evals, String routing, EngineWeights weights, BaselineMethod method) {
    TopsisRankingEngine topsis = new TopsisRankingEngine();
    List<ScoredCandidateRecord> records = new ArrayList<>(evals.size());

    for (NativeEval e : evals) {
      double matchScore;
      if (method == BaselineMethod.AVAILABLE_FEATURE_RENORMALISATION_BASELINE_V1) {
        matchScore = computeAfrTopsisScore(e, weights);
      } else {
        TopsisResult tr = topsis.compute(e.sm1(), e.sm2(), e.sm3(), e.sm4(), weights);
        matchScore = tr.matchScore();
      }
      if (!Double.isFinite(matchScore)) {
        throw new IllegalStateException("Non-finite matchScore in " + method);
      }
      records.add(new ScoredCandidateRecord(
          e.creatorId(), routing,
          e.sm1(), e.sm2(), e.sm3(), e.sm4(),
          e.erNorm(), e.vrNorm(), e.stabNorm(),
          matchScore,
          e.m1Avail() && e.m2Avail() ? "HIGH" : "LOW",
          e.m1AvailabilityTag(),
          e.m3Avail(),
          e.m4Source(),
          List.of()));
    }

    records.sort((a, b) -> {
      int cmp = Double.compare(b.matchScore(), a.matchScore());
      if (cmp != 0) return cmp;
      int cmp3 = Double.compare(b.sm3(), a.sm3());
      if (cmp3 != 0) return cmp3;
      int cmp2 = Double.compare(b.sm2(), a.sm2());
      if (cmp2 != 0) return cmp2;
      return a.creatorId().compareTo(b.creatorId());
    });
    return List.copyOf(records);
  }

  /**
   * Fixed-Ideal TOPSIS with Available-Feature Renormalisation (AFR):
   * Uses fixed weights w_j and fixed ideals (1, 1, 1, 1) / (0, 0, 0, 0) restricted to available subset A,
   * scaled by W_A = sum_{j in A} w_j so renormalised weights w_j / W_A sum to 1.0.
   */
  private static double computeAfrTopsisScore(NativeEval e, EngineWeights w) {
    double[] scores = { e.sm1(), e.sm2(), e.sm3(), e.sm4() };
    boolean[] avail = { e.m1Avail(), e.m2Avail(), e.m3Avail(), e.m4Avail() };
    double[] wArr = { w.w1(), w.w2(), w.w3(), w.w4() };

    double wSum = 0.0;
    for (int j = 0; j < 4; j++) {
      if (avail[j]) wSum += wArr[j];
    }
    if (wSum <= TopsisRankingEngine.EPSILON) {
      throw new IllegalStateException("AFR_NO_AVAILABLE_WEIGHT");
    }
    double dPosSq = 0.0;
    double dNegSq = 0.0;
    for (int j = 0; j < 4; j++) {
      if (!avail[j]) continue;
      double s = Math.max(0.0, Math.min(1.0, scores[j]));
      double wNorm = wArr[j] / wSum;
      dPosSq += Math.pow(wNorm * (1.0 - s), 2);
      dNegSq += Math.pow(wNorm * s, 2);
    }
    double dPos = Math.sqrt(dPosSq);
    double dNeg = Math.sqrt(dNegSq);
    double denom = dPos + dNeg;
    if (denom <= TopsisRankingEngine.EPSILON) {
      return 0.0;
    }
    return (dNeg / denom) * 100.0;
  }

  private static void checkInvalid(MetricObservation obs, String field, List<String> reasons) {
    if (obs != null && obs.availability() == MetricAvailability.INVALID) {
      reasons.add("INVALID_" + field.toUpperCase());
    }
  }

  private static Double computeNativeErNorm(ObservedEvidenceRecord obs, String platform, PolicyNormalizer normalizer) {
    if (obs.engagementRate() == null || obs.engagementRate().value() == null) {
      return null;
    }
    ErMethod method = "FACEBOOK".equals(platform) ? ErMethod.BY_FOLLOWERS : ErMethod.BY_VIEWS;
    return normalizer.normalize(obs.engagementRate().value(), normalizer.resolveKEr(platform, method));
  }

  private static Double computeNativeVrNorm(ObservedEvidenceRecord obs, String platform, PolicyNormalizer normalizer) {
    if ("FACEBOOK".equals(platform)) {
      return null;
    }
    if (obs.followers() == null || obs.followers().value() == null || obs.followers().value() <= 0.0) {
      return null;
    }
    if (obs.avgViews() == null || obs.avgViews().value() == null) {
      return null;
    }
    double rawVr = obs.avgViews().value() / obs.followers().value();
    return normalizer.normalize(rawVr, normalizer.resolveKVr(platform));
  }

  private static Double computeNativeStabNorm(ObservedEvidenceRecord obs) {
    List<Double> viewVals = new ArrayList<>();
    for (PostObservation p : obs.postWindowMetrics()) {
      if (p.views() != null && p.views().value() != null && p.views().value() >= 0.0) {
        viewVals.add(p.views().value());
      }
    }
    if (viewVals.size() < 2) {
      return null;
    }
    double mean = viewVals.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
    if (mean <= 0.0) {
      return null;
    }
    double var = 0.0;
    for (double v : viewVals) {
      var += Math.pow(v - mean, 2);
    }
    double sd = Math.sqrt(var / (viewVals.size() - 1));
    double cv = sd / mean;
    return 1.0 / (1.0 + cv);
  }
}
