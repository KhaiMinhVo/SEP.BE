package com.influencermatch.backend.recommendation.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.influencermatch.backend.common.dto.ApiResponse;
import com.influencermatch.backend.exception.BusinessException;
import com.influencermatch.backend.exception.ForbiddenException;
import com.influencermatch.backend.recommendation.dto.RecommendationConfigResponseDto;
import com.influencermatch.backend.recommendation.engine.critic.DynamicWeightBlender;
import com.influencermatch.backend.recommendation.engine.phase0.model.ScoringPolicyConfig;
import com.influencermatch.backend.recommendation.engine.presentation.MmrReranker;
import com.influencermatch.backend.support.TestActors;
import com.influencermatch.backend.user.enums.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;

class AdminRecommendationConfigControllerTest {

  private final AdminRecommendationConfigController controller = new AdminRecommendationConfigController();

  @AfterEach
  void cleanContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void adminCanAccessConfigurationWithExactFrozenMetadata() {
    TestActors.authenticate(TestActors.user(Role.ADMIN));

    ResponseEntity<ApiResponse<RecommendationConfigResponseDto>> response = controller.getConfiguration();

    assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
    RecommendationConfigResponseDto dto = response.getBody().data();
    assertThat(dto).isNotNull();
    assertThat(dto.getEngineVersion()).isEqualTo("Four-Memory TOPSIS Engine v1.0");
    assertThat(dto.getCalibrationVersion()).isEqualTo(ScoringPolicyConfig.CALIBRATION_VERSION);
    assertThat(dto.isDynamicallyMutable()).isFalse();

    // Verify STAB floor is 0.00
    assertThat(dto.getStability().getStabMissingPolicyFloor())
        .isEqualTo(ScoringPolicyConfig.STAB_MISSING_POLICY_FLOOR);

    // Verify Dynamic Weighting lambda is 0.30 and CRITIC min pool size is 10
    assertThat(dto.getDynamicWeighting().getDefaultLambda())
        .isEqualTo(DynamicWeightBlender.DEFAULT_LAMBDA);
    assertThat(dto.getDynamicWeighting().getMinPoolSizeForCritic()).isEqualTo(10);
    assertThat(dto.getDynamicWeighting().getCanonicalObjectiveWeights()).containsKey("AWARENESS");

    // Verify MMR diversity lambda is 0.70
    assertThat(dto.getDiversityReranking().getDefaultMmrLambda())
        .isEqualTo(MmrReranker.DEFAULT_LAMBDA);

    // Verify Half-saturation K constants
    assertThat(dto.getHalfSaturation().getErViewsK().get("TIKTOK"))
        .isEqualTo(ScoringPolicyConfig.K_ER_VIEWS_TIKTOK);
  }

  @Test
  void dataManagerCanAccessConfiguration() {
    TestActors.authenticate(TestActors.user(Role.DATA_MANAGER));

    ResponseEntity<ApiResponse<RecommendationConfigResponseDto>> response = controller.getConfiguration();

    assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
    assertThat(response.getBody().data().getEngineVersion()).isEqualTo("Four-Memory TOPSIS Engine v1.0");
  }

  @Test
  void brandCannotAccessConfiguration() {
    TestActors.authenticate(TestActors.user(Role.BRAND));

    assertThatThrownBy(controller::getConfiguration)
        .isInstanceOf(ForbiddenException.class);
  }

  @Test
  void unauthenticatedCannotAccessConfiguration() {
    assertThatThrownBy(controller::getConfiguration)
        .isInstanceOf(BusinessException.class);
  }
}
