package com.influencermatch.backend.collaboration.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.influencermatch.backend.brand.model.BrandPattern;
import com.influencermatch.backend.brand.model.BrandProfile;
import com.influencermatch.backend.brand.repository.BrandPatternRepository;
import com.influencermatch.backend.brand.repository.BrandProfileRepository;
import com.influencermatch.backend.campaign.model.Campaign;
import com.influencermatch.backend.campaign.model.HistoricalCampaign;
import com.influencermatch.backend.campaign.repository.HistoricalCampaignRepository;
import com.influencermatch.backend.collaboration.event.CollaborationReviewedEvent;
import com.influencermatch.backend.collaboration.model.CampaignOutcome;
import com.influencermatch.backend.collaboration.model.Collaboration;
import com.influencermatch.backend.collaboration.repository.CampaignOutcomeRepository;
import com.influencermatch.backend.collaboration.repository.CollaborationRepository;
import com.influencermatch.backend.creator.model.Creator;
import com.influencermatch.backend.creator.model.CreatorMaster;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CollaborationCompletedEventListenerTest {

  private HistoricalCampaignRepository historicalCampaignRepository;
  private BrandPatternRepository brandPatternRepository;
  private CollaborationRepository collaborationRepository;
  private CampaignOutcomeRepository campaignOutcomeRepository;
  private BrandProfileRepository brandProfileRepository;
  private ObjectMapper objectMapper;
  private CollaborationCompletedEventListener listener;

  @BeforeEach
  void setUp() {
    historicalCampaignRepository = mock(HistoricalCampaignRepository.class);
    brandPatternRepository = mock(BrandPatternRepository.class);
    collaborationRepository = mock(CollaborationRepository.class);
    campaignOutcomeRepository = mock(CampaignOutcomeRepository.class);
    brandProfileRepository = mock(BrandProfileRepository.class);
    objectMapper = new ObjectMapper();

    listener =
        new CollaborationCompletedEventListener(
            historicalCampaignRepository,
            brandPatternRepository,
            collaborationRepository,
            campaignOutcomeRepository,
            brandProfileRepository,
            objectMapper);
  }

  @Test
  @DisplayName("Syncs M1 HistoricalCampaign with VERIFIED quality and normalized reliability score")
  void syncsHistoricalCampaignOnReview() {
    UUID collabId = UUID.randomUUID();
    UUID reviewId = UUID.randomUUID();
    UUID outcomeId = UUID.randomUUID();
    UUID campaignId = UUID.randomUUID();
    UUID brandProfileId = UUID.randomUUID();
    UUID creatorId = UUID.randomUUID();
    UUID masterId = UUID.randomUUID();

    CreatorMaster master = CreatorMaster.builder().id(masterId).build();
    Creator creator = Creator.builder().id(creatorId).master(master).build();
    Campaign campaign =
        Campaign.builder()
            .id(campaignId)
            .brandProfile(BrandProfile.builder().id(brandProfileId).build())
            .build();
    Collaboration collaboration =
        Collaboration.builder()
            .id(collabId)
            .creator(creator)
            .master(master)
            .campaign(campaign)
            .publishedContentUrl("https://tiktok.com/@koc/phase5video")
            .build();

    CampaignOutcome outcome =
        CampaignOutcome.builder()
            .id(outcomeId)
            .collaboration(collaboration)
            .views(15000L)
            .clicks(350L)
            .likes(1200L)
            .comments(80L)
            .shares(25L)
            .build();

    when(collaborationRepository.findById(collabId)).thenReturn(Optional.of(collaboration));
    when(campaignOutcomeRepository.findByCollaborationId(collabId)).thenReturn(Optional.of(outcome));
    when(brandProfileRepository.findById(brandProfileId))
        .thenReturn(Optional.of(campaign.getBrandProfile()));

    CollaborationReviewedEvent event =
        new CollaborationReviewedEvent(
            collabId,
            reviewId,
            outcomeId,
            campaignId,
            brandProfileId,
            creatorId,
            masterId,
            "TIKTOK",
            (short) 5,
            (short) 4,
            true,
            "Excellent collaboration, on-time delivery");

    listener.onCollaborationReviewed(event);

    ArgumentCaptor<HistoricalCampaign> histCaptor =
        ArgumentCaptor.forClass(HistoricalCampaign.class);
    verify(historicalCampaignRepository).save(histCaptor.capture());

    HistoricalCampaign savedHist = histCaptor.getValue();
    assertThat(savedHist.getQuality()).isEqualTo("VERIFIED");
    assertThat(savedHist.getEvidenceType()).isEqualTo("COLLABORATION_REVIEW");
    assertThat(savedHist.getCreator().getId()).isEqualTo(creatorId);
    assertThat(savedHist.getMaster().getId()).isEqualTo(masterId);
    assertThat(savedHist.getTextValue()).isEqualTo("Excellent collaboration, on-time delivery");

    assertThat(savedHist.getEvidenceValue().get("overallRating").asInt()).isEqualTo(5);
    assertThat(savedHist.getEvidenceValue().get("brandFitRating").asInt()).isEqualTo(4);
    assertThat(savedHist.getEvidenceValue().get("reliabilityScore").asDouble()).isEqualTo(0.9);
    assertThat(savedHist.getEvidenceValue().get("views").asLong()).isEqualTo(15000L);
    assertThat(savedHist.getEvidenceValue().get("likes").asLong()).isEqualTo(1200L);
    assertThat(savedHist.getEvidenceValue().get("publishedContentUrl").asText())
        .isEqualTo("https://tiktok.com/@koc/phase5video");
  }

  @Test
  @DisplayName("Syncs M4 BrandPattern incrementing evidence count and updating confidence")
  void syncsBrandPatternOnReview() {
    UUID collabId = UUID.randomUUID();
    UUID brandProfileId = UUID.randomUUID();
    BrandProfile brandProfile = BrandProfile.builder().id(brandProfileId).build();

    Collaboration collaboration =
        Collaboration.builder()
            .id(collabId)
            .creator(Creator.builder().id(UUID.randomUUID()).build())
            .campaign(Campaign.builder().id(UUID.randomUUID()).brandProfile(brandProfile).build())
            .build();

    when(collaborationRepository.findById(collabId)).thenReturn(Optional.of(collaboration));
    when(campaignOutcomeRepository.findByCollaborationId(collabId)).thenReturn(Optional.empty());
    when(brandProfileRepository.findById(brandProfileId)).thenReturn(Optional.of(brandProfile));
    when(brandPatternRepository.findByBrandProfileIdAndPatternType(
            brandProfileId, "COLLABORATION_SUCCESS"))
        .thenReturn(Optional.empty());

    CollaborationReviewedEvent event =
        new CollaborationReviewedEvent(
            collabId,
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            brandProfileId,
            UUID.randomUUID(),
            null,
            "TIKTOK",
            (short) 5,
            (short) 5,
            true,
            "Flawless execution");

    listener.onCollaborationReviewed(event);

    ArgumentCaptor<BrandPattern> patternCaptor = ArgumentCaptor.forClass(BrandPattern.class);
    verify(brandPatternRepository).save(patternCaptor.capture());

    BrandPattern savedPattern = patternCaptor.getValue();
    assertThat(savedPattern.getPatternType()).isEqualTo("COLLABORATION_SUCCESS");
    assertThat(savedPattern.getEvidenceCount()).isEqualTo(1);
    assertThat(savedPattern.getConfidence()).isEqualByComparingTo(new BigDecimal("0.5500"));
    assertThat(savedPattern.getAverageSuccessfulScore())
        .isEqualByComparingTo(new BigDecimal("5.0000"));
    assertThat(savedPattern.getPatternValue().get("successCount").asInt()).isEqualTo(1);
    assertThat(savedPattern.getPatternValue().get("lastOverallRating").asInt()).isEqualTo(5);
  }

  @Test
  @DisplayName("Handles missing collaboration gracefully without throwing exception")
  void handlesMissingCollaborationGracefully() {
    UUID collabId = UUID.randomUUID();
    when(collaborationRepository.findById(collabId)).thenReturn(Optional.empty());

    CollaborationReviewedEvent event =
        new CollaborationReviewedEvent(
            collabId,
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            null,
            "TIKTOK",
            (short) 5,
            (short) 5,
            true,
            "Note");

    listener.onCollaborationReviewed(event);

    verify(historicalCampaignRepository, never()).save(any());
    verify(brandPatternRepository, never()).save(any());
  }
}
