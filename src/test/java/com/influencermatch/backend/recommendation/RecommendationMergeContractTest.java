package com.influencermatch.backend.recommendation;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.influencermatch.backend.campaign.enums.CandidateSource;
import com.influencermatch.backend.campaign.repository.RecommendationRunRepository;
import com.influencermatch.backend.exception.ValidationException;
import com.influencermatch.backend.recommendation.controller.AiRecommendationController;
import com.influencermatch.backend.recommendation.dto.RecommendationResponseDto;
import com.influencermatch.backend.recommendation.service.AiRecommendationService;
import com.influencermatch.backend.relationship.service.CampaignOwnershipService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RecommendationMergeContractTest {
  final AiRecommendationService service = mock(AiRecommendationService.class);
  final AiRecommendationController controller =
      new AiRecommendationController(
          service, mock(RecommendationRunRepository.class), mock(CampaignOwnershipService.class));

  @Test
  void bestMatchKeepsExplicitCandidateSourceAndPrivateResponse() {
    UUID campaign = UUID.randomUUID();
    when(service.recommendKocs(campaign, 10, CandidateSource.SHORTLIST))
        .thenReturn(RecommendationResponseDto.builder().campaignId(campaign).build());
    var response = controller.recommendKocs(campaign, 10, CandidateSource.SHORTLIST, "BEST_MATCH");
    assertThat(response.getStatusCode().value()).isEqualTo(200);
    assertThat(response.getHeaders().getCacheControl()).isEqualTo("private, no-store");
    verify(service).recommendKocs(campaign, 10, CandidateSource.SHORTLIST);
  }

  @ParameterizedTest
  @ValueSource(strings = {"BUDGET_READY_FIRST", "DIVERSIFIED_MATCH", "UNKNOWN"})
  void inactivePolicyCannotBeSilentlySelected(String mode) {
    assertThatThrownBy(
            () -> controller.recommendKocs(UUID.randomUUID(), 10, CandidateSource.SHORTLIST, mode))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("BEST_MATCH only");
    verifyNoInteractions(service);
  }
}
