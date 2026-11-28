package com.influencermatch.backend.recommendation.research.scorer;

import com.influencermatch.backend.brand.model.BrandPattern;
import com.influencermatch.backend.campaign.model.Campaign;
import com.influencermatch.backend.campaign.model.HistoricalCampaign;
import com.influencermatch.backend.creator.dto.RecommendationCandidateProjection;
import com.influencermatch.backend.creator.model.Post;
import com.influencermatch.backend.recommendation.engine.FourMemoryTopsisEngine;
import com.influencermatch.backend.recommendation.engine.FourMemoryTopsisEngine.EvaluatedCandidate;
import com.influencermatch.backend.recommendation.engine.critic.model.EngineWeights;
import com.influencermatch.backend.recommendation.engine.memory.LearnedM4Evaluator.BrandReviewRecord;
import com.influencermatch.backend.recommendation.engine.memory.M1HistoricalScorer;
import com.influencermatch.backend.recommendation.engine.phase0.Phase0Orchestrator;
import com.influencermatch.backend.recommendation.engine.phase0.model.NormalizedCandidateContext;
import com.influencermatch.backend.recommendation.engine.phase0.model.ScoringPolicyConfig;
import com.influencermatch.backend.recommendation.research.adapters.LegacyResearchAdapter;
import com.influencermatch.backend.recommendation.research.adapters.LegacyResearchAdapter.LegacyMappingResult;
import com.influencermatch.backend.recommendation.research.adapters.LegacyResearchAdapter.LossyMappingWarning;
import com.influencermatch.backend.recommendation.research.contracts.CampaignCreatorSnapshot;
import com.influencermatch.backend.recommendation.research.contracts.CandidateUniverse;
import com.influencermatch.backend.recommendation.research.contracts.CandidateUniverse.CandidateEntry;
import com.influencermatch.backend.recommendation.research.contracts.ExtendedCampaignSnapshot;
import com.influencermatch.backend.recommendation.research.contracts.ObservedCreatorContextExtension;
import com.influencermatch.backend.recommendation.research.contracts.ObservedEvidenceRecord;
import com.influencermatch.backend.recommendation.research.contracts.ResearchEnums.DataOrigin;
import com.influencermatch.backend.recommendation.research.contracts.ResearchEnums.HardEligibilityStatus;
import com.influencermatch.backend.recommendation.research.validation.ScorerInputBoundary;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class OfflineLegacyFourMemoryScorer {

  public enum PolicyVariant {
    LEGACY_REFERENCE_V1("legacy-reference-v1"),
    CORRECTED_REFERENCE_V1("corrected-reference-v1");

    private final String id;

    PolicyVariant(String id) {
      this.id = id;
    }

    public String id() {
      return id;
    }
  }

  public record ScoredCandidateRecord(
      UUID creatorId,
      String routing,
      double sm1,
      double sm2,
      double sm3,
      double sm4,
      double erNorm,
      double vrNorm,
      double stabNorm,
      double matchScore,
      String confidenceTier,
      String m1Availability,
      boolean hasSemanticEmbedding,
      String m4Source,
      List<LossyMappingWarning> lossyMappings) {
  }

  public record ExcludedCandidateRecord(
      UUID creatorId,
      String routing,
      List<String> reasons,
      List<LossyMappingWarning> lossyMappings) {
  }

  public record OfflineScoringResult(
      String scorerVersion,
      String calibrationVersion,
      String snapshotId,
      UUID campaignId,
      Instant scoringAsOf,
      DataOrigin dataOrigin,
      List<UUID> universeCreatorIds,
      EngineWeights weights,
      List<ScoredCandidateRecord> mainRanked,
      List<ScoredCandidateRecord> provisionalRanked,
      List<ExcludedCandidateRecord> excluded) {
  }

  private final Phase0Orchestrator phase0 = new Phase0Orchestrator();
  private final M1HistoricalScorer m1Scorer = new M1HistoricalScorer();
  private final FourMemoryTopsisEngine engine = new FourMemoryTopsisEngine();

  public OfflineScoringResult score(
      CampaignCreatorSnapshot rawSnapshot,
      CandidateUniverse universe,
      PolicyVariant policyVariant,
      boolean enforceM1Gate) {
    var extended = new ExtendedCampaignSnapshot(
        ExtendedCampaignSnapshot.SCHEMA_VERSION, rawSnapshot, null, List.of(), Map.of());
    return score(extended, universe, policyVariant, enforceM1Gate);
  }

  public OfflineScoringResult score(
      ExtendedCampaignSnapshot rawExtended,
      CandidateUniverse universe,
      PolicyVariant policyVariant,
      boolean enforceM1Gate) {
    return score(rawExtended, universe, policyVariant, enforceM1Gate, true);
  }

  public OfflineScoringResult score(
      ExtendedCampaignSnapshot rawExtended,
      CandidateUniverse universe,
      PolicyVariant policyVariant,
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

    Map<UUID, CandidateEntry> universeById = universe.entries().stream()
        .collect(Collectors.toMap(CandidateEntry::creatorId, e -> e, (a, b) -> a, LinkedHashMap::new));
    Map<UUID, ObservedEvidenceRecord> evidenceById = snapshot.creators().stream()
        .collect(Collectors.toMap(ObservedEvidenceRecord::creatorId, e -> e, (a, b) -> a, LinkedHashMap::new));
    Map<UUID, ObservedCreatorContextExtension> contextById = extended.contextExtensions();

    Campaign campaign = Campaign.builder()
        .id(snapshot.campaignId())
        .platforms(snapshot.platforms())
        .niches(snapshot.niches())
        .followerMin(snapshot.followerMin())
        .followerMax(snapshot.followerMax())
        .build();

    List<RecommendationCandidateProjection> legacyCandidates = new ArrayList<>();
    Map<UUID, List<Post>> postsByCreator = new LinkedHashMap<>();
    Map<UUID, String> platformByCreator = new LinkedHashMap<>();
    for (ObservedEvidenceRecord rec : snapshot.creators()) {
      platformByCreator.put(rec.creatorId(), rec.platform());
    }
    Map<UUID, List<LossyMappingWarning>> warningsByCreator = new LinkedHashMap<>();
    List<ExcludedCandidateRecord> excluded = new ArrayList<>();

    for (CandidateEntry entry : universeById.values()) {
      UUID creatorId = entry.creatorId();
      if (entry.hardEligibility() != HardEligibilityStatus.ELIGIBLE) {
        excluded.add(new ExcludedCandidateRecord(creatorId, entry.hardEligibility().name(), entry.eligibilityReasons(),
            List.of()));
        continue;
      }
      ObservedEvidenceRecord obs = evidenceById.get(creatorId);
      if (obs == null) {
        excluded.add(new ExcludedCandidateRecord(creatorId, "MISSING_EVIDENCE",
            List.of("NO_OBSERVED_RECORD_IN_SNAPSHOT"), List.of()));
        continue;
      }
      LegacyMappingResult mapped = LegacyResearchAdapter.toLegacyInputs(obs, contextById.get(creatorId));
      legacyCandidates.add(mapped.candidate());
      postsByCreator.put(creatorId, mapped.posts());
      platformByCreator.put(creatorId, obs.platform());
      warningsByCreator.put(creatorId, mapped.lossyMappings());
    }

    List<NormalizedCandidateContext> p0List = phase0.execute(
        legacyCandidates, campaign, Set.of(), postsByCreator, ZoneOffset.UTC);
    Map<UUID, NormalizedCandidateContext> p0Map = p0List.stream()
        .collect(Collectors.toMap(NormalizedCandidateContext::creatorId, p -> p));

    LocalDateTime scoringAsOfLdt = LocalDateTime.ofInstant(snapshot.scoringAsOf(), ZoneOffset.UTC);
    Function<RecommendationCandidateProjection, List<HistoricalCampaign>> historyProvider = cand -> LegacyResearchAdapter
        .toLegacyHistoricalCampaigns(
            snapshot.historicalCollaborations(), cand.getCreatorId(), snapshot.scoringAsOf());

    List<RecommendationCandidateProjection> mainPool = new ArrayList<>();
    List<RecommendationCandidateProjection> provisionalPool = new ArrayList<>();

    for (RecommendationCandidateProjection cand : legacyCandidates) {
      NormalizedCandidateContext p0 = p0Map.get(cand.getCreatorId());
      if (p0 == null)
        continue;
      if (!p0.isCampaignEligible()) {
        excluded.add(new ExcludedCandidateRecord(
            cand.getCreatorId(), "INELIGIBLE", p0.eligibilityReasons(), warningsByCreator.get(cand.getCreatorId())));
        continue;
      }
      switch (p0.scoringReadiness()) {
        case SCORE_READY -> {
          if (enforceM1Gate
              && !m1Scorer.evaluate(historyProvider.apply(cand), snapshot.objective(), scoringAsOfLdt).isAvailable()) {
            provisionalPool.add(cand);
          } else {
            mainPool.add(cand);
          }
        }
        case PROVISIONAL -> provisionalPool.add(cand);
        case NOT_SCORABLE -> excluded.add(new ExcludedCandidateRecord(
            cand.getCreatorId(), "NOT_SCORABLE", p0.dataQualityReasons(), warningsByCreator.get(cand.getCreatorId())));
        case INVALID_DATA -> excluded.add(new ExcludedCandidateRecord(
            cand.getCreatorId(), "INVALID_DATA", p0.dataQualityReasons(), warningsByCreator.get(cand.getCreatorId())));
      }
    }

    List<BrandPattern> brandPatterns = LegacyResearchAdapter.toLegacyBrandPatterns(extended.brandPatterns());
    List<BrandReviewRecord> reviews = LegacyResearchAdapter.toLegacyBrandReviews(
        snapshot.historicalCollaborations(), extended.brandId(), platformByCreator, contextById, null,
        snapshot.scoringAsOf());

    EngineWeights fixedWeights = engine.getFallbackWeights(snapshot.objective() != null ? snapshot.objective() : "AWARENESS");
    List<EvaluatedCandidate> mainEvaluated;
    if (mainPool.isEmpty()) {
      mainEvaluated = List.of();
    } else if (!useCritic) {
      mainEvaluated = new ArrayList<>();
      for (RecommendationCandidateProjection cand : mainPool) {
        mainEvaluated.add(engine.evaluateSingle(
            cand, p0Map.get(cand.getCreatorId()), campaign, historyProvider.apply(cand),
            brandPatterns, fixedWeights,
            com.influencermatch.backend.recommendation.engine.presentation.enums.PriceStatus.PRICE_UNKNOWN,
            reviews, scoringAsOfLdt));
      }
      mainEvaluated.sort((a, b) -> {
        int cmp = Double.compare(b.matchScore(), a.matchScore());
        if (cmp != 0) return cmp;
        int cmp3 = Double.compare(b.memoryScores().sm3(), a.memoryScores().sm3());
        if (cmp3 != 0) return cmp3;
        int cmp2 = Double.compare(b.memoryScores().sm2(), a.memoryScores().sm2());
        if (cmp2 != 0) return cmp2;
        return a.creatorId().compareTo(b.creatorId());
      });
    } else {
      mainEvaluated = engine.evaluateAndRank(mainPool, p0Map, campaign, historyProvider, brandPatterns,
          c -> com.influencermatch.backend.recommendation.engine.presentation.enums.PriceStatus.PRICE_UNKNOWN,
          reviews, scoringAsOfLdt);
    }

    EngineWeights runWeights = !mainEvaluated.isEmpty()
        ? mainEvaluated.getFirst().weights()
        : fixedWeights;

    List<EvaluatedCandidate> provEvaluated = new ArrayList<>();
    for (RecommendationCandidateProjection cand : provisionalPool) {
      provEvaluated.add(engine.evaluateSingle(
          cand, p0Map.get(cand.getCreatorId()), campaign, historyProvider.apply(cand),
          brandPatterns, runWeights,
          com.influencermatch.backend.recommendation.engine.presentation.enums.PriceStatus.PRICE_UNKNOWN,
          reviews, scoringAsOfLdt));
    }
    provEvaluated.sort((a, b) -> {
      int cmp = Double.compare(b.matchScore(), a.matchScore());
      return cmp != 0 ? cmp : a.creatorId().compareTo(b.creatorId());
    });

    return new OfflineScoringResult(
        policyVariant.id(),
        ScoringPolicyConfig.CALIBRATION_VERSION,
        snapshot.snapshotId(),
        snapshot.campaignId(),
        snapshot.scoringAsOf(),
        snapshot.dataOrigin(),
        List.copyOf(universeById.keySet()),
        runWeights,
        toScoredRecords(mainEvaluated, "MAIN", warningsByCreator),
        toScoredRecords(provEvaluated, "PROVISIONAL", warningsByCreator),
        List.copyOf(excluded));
  }

  private static List<ScoredCandidateRecord> toScoredRecords(
      List<EvaluatedCandidate> evaluated,
      String routing,
      Map<UUID, List<LossyMappingWarning>> warningsByCreator) {
    List<ScoredCandidateRecord> out = new ArrayList<>(evaluated.size());
    for (EvaluatedCandidate ec : evaluated) {
      double score = ec.matchScore();
      if (!Double.isFinite(score)) {
        throw new IllegalStateException("Non-finite matchScore produced for " + ec.creatorId());
      }
      var m = ec.memoryScores();
      String m1Avail = "AVAILABLE".equals(m.m1Availability()) ? "AVAILABLE" : "PRIOR/NEUTRAL_FALLBACK";
      String m4Src = m.m4Source() != null ? m.m4Source() : "PRIOR/NEUTRAL_FALLBACK";
      out.add(new ScoredCandidateRecord(
          ec.creatorId(), routing,
          m.sm1(), m.sm2(), m.sm3(), m.sm4(),
          m.erNorm(), m.vrNorm(), m.stabNorm(),
          score,
          ec.confidence().tier().name(),
          m1Avail,
          m.m3EmbeddingAvailable(),
          m4Src,
          warningsByCreator.getOrDefault(ec.creatorId(), List.of())));
    }
    return List.copyOf(out);
  }
}
