package com.influencermatch.backend.recommendation.research.adapters;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.influencermatch.backend.brand.model.BrandPattern;
import com.influencermatch.backend.campaign.model.HistoricalCampaign;
import com.influencermatch.backend.creator.dto.RecommendationCandidateProjection;
import com.influencermatch.backend.creator.model.Post;
import com.influencermatch.backend.recommendation.engine.memory.LearnedM4Evaluator.BrandReviewRecord;
import com.influencermatch.backend.recommendation.research.contracts.ExtendedCampaignSnapshot.BrandPatternSpec;
import com.influencermatch.backend.recommendation.research.contracts.HistoricalCollaborationEvidence;
import com.influencermatch.backend.recommendation.research.contracts.MetricObservation;
import com.influencermatch.backend.recommendation.research.contracts.ObservedCreatorContextExtension;
import com.influencermatch.backend.recommendation.research.contracts.ObservedEvidenceRecord;
import com.influencermatch.backend.recommendation.research.contracts.ObservedEvidenceRecord.PostObservation;
import com.influencermatch.backend.recommendation.research.contracts.ResearchEnums.MetricAvailability;
import com.influencermatch.backend.recommendation.research.validation.ResearchContractValidator;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class LegacyResearchAdapter {

  private static final ObjectMapper JSON = new ObjectMapper();

  public record LossyMappingWarning(String field, MetricAvailability sourceAvailability, String reason) {}

  public record LegacyMappingResult(
      RecommendationCandidateProjection candidate,
      List<Post> posts,
      List<LossyMappingWarning> lossyMappings
  ) {}

  private LegacyResearchAdapter() {}

  public static LegacyMappingResult toLegacyInputs(ObservedEvidenceRecord record) {
    return toLegacyInputs(record, null);
  }

  public static LegacyMappingResult toLegacyInputs(
      ObservedEvidenceRecord record,
      ObservedCreatorContextExtension contextExt) {
    List<LossyMappingWarning> warnings = new ArrayList<>();
    warnings.add(new LossyMappingWarning(
        "record.metadata",
        MetricAvailability.OBSERVED,
        "Legacy RecommendationCandidateProjection drops dataOrigin, snapshotId, knownAt, provenance, and observedCount/totalCount"));

    String niche = contextExt != null ? contextExt.niche() : null;
    String category = contextExt != null ? contextExt.category() : null;
    Double semanticScore = (contextExt != null && contextExt.semanticSimilarity() != null)
        ? contextExt.semanticSimilarity().value()
        : null;
    if (contextExt == null) {
      warnings.add(new LossyMappingWarning(
          "record.semanticAndNiche",
          MetricAvailability.MISSING,
          "ObservedEvidenceRecord without ObservedCreatorContextExtension; legacy M3 uses PRIOR/NEUTRAL_FALLBACK 0.50 and M4 explicit lacks niche/category"));
    } else if (semanticScore == null) {
      warnings.add(new LossyMappingWarning(
          "contextExt.semanticSimilarity",
          MetricAvailability.MISSING,
          "Missing semanticSimilarity; legacy M3 uses PRIOR/NEUTRAL_FALLBACK 0.50"));
    }

    Long followers = extractLong(record.followers(), "followers", warnings);
    BigDecimal avgViews = extractBigDecimal(record.avgViews(), "avgViews", warnings);
    BigDecimal avgLikes = extractBigDecimal(record.avgLikes(), "avgLikes", warnings);
    BigDecimal avgComments = extractBigDecimal(record.avgComments(), "avgComments", warnings);
    BigDecimal avgShares = extractBigDecimal(record.avgShares(), "avgShares", warnings);
    BigDecimal er = extractBigDecimal(record.engagementRate(), "engagementRate", warnings);

    RecommendationCandidateProjection projection = (RecommendationCandidateProjection) Proxy.newProxyInstance(
        RecommendationCandidateProjection.class.getClassLoader(),
        new Class<?>[] { RecommendationCandidateProjection.class },
        (proxy, method, args) -> switch (method.getName()) {
          case "getCreatorId" -> record.creatorId();
          case "getMasterId" -> record.masterId();
          case "getPlatform" -> record.platform();
          case "getAccountStatus" -> "ACTIVE";
          case "getFollowers" -> followers;
          case "getAvgViews" -> avgViews;
          case "getAvgLikes" -> avgLikes;
          case "getAvgComments" -> avgComments;
          case "getAvgShares" -> avgShares;
          case "getEngagementRate" -> er;
          case "getNiche" -> niche;
          case "getCategory" -> category;
          case "getSemanticScore" -> semanticScore;
          default -> null;
        }
    );

    List<Post> posts = new ArrayList<>();
    for (PostObservation p : record.postWindowMetrics()) {
      posts.add(Post.builder()
          .platformPostId(p.platformPostId())
          .postedAt(p.postedAt() != null ? p.postedAt().atOffset(ZoneOffset.UTC).toString() : null)
          .views(extractLong(p.views(), "post.views", warnings))
          .likes(extractLong(p.likes(), "post.likes", warnings))
          .comments(extractLong(p.comments(), "post.comments", warnings))
          .shares(extractLong(p.shares(), "post.shares", warnings))
          .build());
    }

    return new LegacyMappingResult(projection, List.copyOf(posts), List.copyOf(warnings));
  }

  public static List<HistoricalCampaign> toLegacyHistoricalCampaigns(
      List<HistoricalCollaborationEvidence> records,
      UUID creatorId,
      Instant scoringAsOf) {
    if (records == null || records.isEmpty()) {
      return List.of();
    }
    List<HistoricalCollaborationEvidence> replayed = ResearchContractValidator.replayHistoricalAt(
        records.stream().filter(r -> creatorId.equals(r.creatorId())).toList(),
        scoringAsOf);

    List<HistoricalCampaign> mapped = new ArrayList<>();
    for (HistoricalCollaborationEvidence h : replayed) {
      if (!"COMPLETED".equalsIgnoreCase(h.status())) {
        continue;
      }
      ObjectNode ev = JSON.createObjectNode();
      ev.put("evidenceSchemaVersion", "collaboration-completion-evidence-v1");
      ev.put("brandAccepted", true);
      ev.put("outcomeRevision", h.revisionNumber());
      if (h.brandId() != null) {
        ev.put("brandProfileId", h.brandId().toString());
      }
      if (h.rObservation() != null && h.rObservation().value() != null) {
        double r = Math.max(0.0, Math.min(1.0, h.rObservation().value()));
        ev.put("overallRating", 1.0 + 4.0 * r);
        ev.put("wouldCollaborateAgain", r >= 0.75);
      }
      if (h.tObservation() != null && h.tObservation().value() != null) {
        ArrayNode deliverables = ev.putArray("deliverables");
        ObjectNode del = deliverables.addObject();
        del.put("submissionSource", "CREATOR_PORTAL");
        LocalDateTime due = LocalDateTime.ofInstant(h.eventTime(), ZoneOffset.UTC);
        LocalDateTime submitted = h.tObservation().value() >= 0.5 ? due.minusHours(1) : due.plusHours(1);
        del.put("dueAt", due.toString());
        del.put("submittedAt", submitted.toString());
      }
      if (h.kObservation() != null && h.kObservation().value() != null) {
        ev.put("kpiStatus", "AVAILABLE");
        ev.put("kpiActual", Math.max(0.0, Math.min(1.0, h.kObservation().value())));
        ev.put("kpiTarget", 1.0);
        ev.put("kpiEvidenceQuality", "VERIFIED");
      } else if (h.kObservation() != null && h.kObservation().availability() == MetricAvailability.NOT_APPLICABLE) {
        ev.put("kpiStatus", "NOT_APPLICABLE");
      }

      mapped.add(HistoricalCampaign.builder()
          .id(h.collaborationId())
          .evidenceType("COLLABORATION_COMPLETION")
          .evidenceRootId(h.collaborationId())
          .evidenceValue(ev)
          .status("ACTIVE")
          .recordedAt(LocalDateTime.ofInstant(h.knownAt(), ZoneOffset.UTC))
          .businessOccurredAt(LocalDateTime.ofInstant(h.eventTime(), ZoneOffset.UTC))
          .build());
    }
    return List.copyOf(mapped);
  }

  public static List<BrandReviewRecord> toLegacyBrandReviews(
      List<HistoricalCollaborationEvidence> records,
      UUID brandId,
      String platform,
      Instant scoringAsOf) {
    return toLegacyBrandReviews(records, brandId, Map.of(), Map.of(), platform, scoringAsOf);
  }

  public static List<BrandReviewRecord> toLegacyBrandReviews(
      List<HistoricalCollaborationEvidence> records,
      UUID brandId,
      Map<UUID, String> platformByCreator,
      Map<UUID, ObservedCreatorContextExtension> contextByCreator,
      String defaultPlatform,
      Instant scoringAsOf) {
    if (records == null || records.isEmpty()) {
      return List.of();
    }
    List<HistoricalCollaborationEvidence> replayed = ResearchContractValidator.replayHistoricalAt(records, scoringAsOf);
    List<BrandReviewRecord> reviews = new ArrayList<>();
    for (HistoricalCollaborationEvidence h : replayed) {
      if (h.m4ReviewScore() == null || h.m4ReviewScore().value() == null) {
        continue;
      }
      int rating = (int) Math.round(1.0 + 4.0 * Math.max(0.0, Math.min(1.0, h.m4ReviewScore().value())));
      String plat = platformByCreator.getOrDefault(h.creatorId(), defaultPlatform);
      ObservedCreatorContextExtension ext = contextByCreator.get(h.creatorId());
      String niche = ext != null ? ext.niche() : null;
      reviews.add(new BrandReviewRecord(
          h.collaborationId(),
          h.brandId() != null ? h.brandId() : brandId,
          h.creatorId(),
          h.masterId(),
          plat,
          niche,
          Math.max(1, Math.min(5, rating))));
    }
    return List.copyOf(reviews);
  }

  public static List<BrandPattern> toLegacyBrandPatterns(List<BrandPatternSpec> specs) {
    if (specs == null || specs.isEmpty()) {
      return List.of();
    }
    return specs.stream()
        .map(s -> BrandPattern.builder()
            .niche(s.niche())
            .platform(s.platform())
            .averageSuccessfulScore(BigDecimal.valueOf(s.affinityScore()))
            .build())
        .toList();
  }

  private static Long extractLong(MetricObservation obs, String field, List<LossyMappingWarning> warnings) {
    BigDecimal val = extractBigDecimal(obs, field, warnings);
    return val != null ? val.longValue() : null;
  }

  private static BigDecimal extractBigDecimal(MetricObservation obs, String field, List<LossyMappingWarning> warnings) {
    if (obs == null) {
      return null;
    }
    switch (obs.availability()) {
      case PARTIAL_OBSERVATION -> warnings.add(new LossyMappingWarning(
          field, obs.availability(), "PARTIAL_OBSERVATION mapped as numeric value without coverage counts"));
      case INVALID -> warnings.add(new LossyMappingWarning(
          field, obs.availability(), "INVALID collapsed to null; rawInvalidValue='" + obs.rawInvalidValue() + "' dropped"));
      case NOT_APPLICABLE -> warnings.add(new LossyMappingWarning(
          field, obs.availability(), "NOT_APPLICABLE collapsed to null; indistinguishable from MISSING in legacy schema"));
      default -> {}
    }
    return obs.value() != null ? BigDecimal.valueOf(obs.value()) : null;
  }
}
