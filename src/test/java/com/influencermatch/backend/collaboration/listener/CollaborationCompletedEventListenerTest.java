package com.influencermatch.backend.collaboration.listener;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.influencermatch.backend.brand.repository.*;
import com.influencermatch.backend.campaign.model.HistoricalCampaign;
import com.influencermatch.backend.campaign.repository.HistoricalCampaignRepository;
import com.influencermatch.backend.collaboration.event.CollaborationReviewedEvent;
import com.influencermatch.backend.collaboration.model.*;
import com.influencermatch.backend.collaboration.repository.*;
import com.influencermatch.backend.creator.model.Creator;
import java.util.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;

class CollaborationCompletedEventListenerTest {
  HistoricalCampaignRepository history = mock(HistoricalCampaignRepository.class);
  BrandPatternRepository patterns = mock(BrandPatternRepository.class);
  CollaborationRepository collaborations = mock(CollaborationRepository.class);
  CampaignOutcomeRepository outcomes = mock(CampaignOutcomeRepository.class);
  CreatorReviewRepository reviews = mock(CreatorReviewRepository.class);
  CollaborationCompletedEventListener listener =
      new CollaborationCompletedEventListener(
          history,
          patterns,
          collaborations,
          outcomes,
          mock(BrandProfileRepository.class),
          new ObjectMapper());
  UUID id = UUID.randomUUID(), reviewId = UUID.randomUUID(), outcomeId = UUID.randomUUID();

  CollaborationReviewedEvent event() {
    return new CollaborationReviewedEvent(
        id,
        reviewId,
        outcomeId,
        null,
        null,
        null,
        null,
        "TIKTOK",
        (short) 5,
        (short) 5,
        true,
        "forged event values");
  }

  @BeforeEach
  void fixture() {
    org.springframework.test.util.ReflectionTestUtils.setField(listener, "reviews", reviews);
    var c =
        Collaboration.builder()
            .id(id)
            .creator(Creator.builder().id(UUID.randomUUID()).build())
            .build();
    var o =
        CampaignOutcome.builder()
            .id(outcomeId)
            .collaboration(c)
            .views(100L)
            .evidenceQuality("SELF_REPORTED")
            .build();
    var r =
        CreatorReview.builder()
            .id(reviewId)
            .collaboration(c)
            .outcome(o)
            .overallRating((short) 2)
            .brandFitRating((short) 1)
            .note("Persisted low rating")
            .build();
    when(collaborations.findLockedById(id)).thenReturn(Optional.of(c));
    when(outcomes.findByCollaborationId(id)).thenReturn(Optional.of(o));
    when(reviews.findById(reviewId)).thenReturn(Optional.of(r));
  }

  @Test
  void persistedLowRatingIsPreservedAndNeverBecomesCrawlerVerified() {
    listener.onCollaborationReviewed(event());
    var capture = ArgumentCaptor.forClass(HistoricalCampaign.class);
    verify(history).save(capture.capture());
    var row = capture.getValue();
    assertThat(row.getQuality()).isEqualTo("SELF_REPORTED");
    assertThat(row.getProvenance()).isEqualTo("LEGACY_UNVERIFIED");
    assertThat(row.getEvidenceValue().path("brandFitRating").asInt()).isEqualTo(1);
    assertThat(row.getEvidenceValue().path("brandAccepted").asBoolean()).isFalse();
    assertThat(row.getSourceReviewId()).isEqualTo(reviewId);
    verifyNoInteractions(patterns);
  }

  @Test
  void replayDoesNotAddEvidenceOrIncrementLegacyPattern() {
    when(history.findBySourceReviewIdAndSourceRevision(reviewId, 1L))
        .thenReturn(Optional.of(HistoricalCampaign.builder().build()));
    listener.onCollaborationReviewed(event());
    verify(history, never()).save(any());
    verifyNoInteractions(patterns);
  }

  @Test
  void synchronizationFailurePropagatesForRollback() {
    doThrow(new IllegalStateException("database unavailable")).when(history).save(any());
    assertThatThrownBy(() -> listener.onCollaborationReviewed(event()))
        .isInstanceOf(IllegalStateException.class);
    verifyNoInteractions(patterns);
  }
}
