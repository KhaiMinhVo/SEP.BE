package com.influencermatch.backend.collaboration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.influencermatch.backend.collaboration.controller.CollaborationController;
import com.influencermatch.backend.collaboration.dto.*;
import com.influencermatch.backend.collaboration.enums.CollaborationStatus;
import com.influencermatch.backend.collaboration.service.CollaborationService;
import com.influencermatch.backend.common.dto.PageResponse;
import com.influencermatch.backend.creator.controller.CreatorPortalCollaborationController;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest({CollaborationController.class, CreatorPortalCollaborationController.class})
@AutoConfigureMockMvc(addFilters = false)
class CollaborationReadControllerTest {

  @MockBean
  private com.influencermatch.backend.security.permission.service.EffectivePermissionService effectivePermissions;

  @MockBean
  private com.influencermatch.backend.security.jwt.JwtTokenProvider jwtTokenProvider;

  @MockBean
  private org.springframework.security.core.userdetails.UserDetailsService userDetailsService;

  @Autowired
  private MockMvc mockMvc;

  @MockBean
  private CollaborationService collaborationService;

  @Test
  @DisplayName("GET /campaigns/{id}/collaborations returns paginated collaboration summaries")
  void getCampaignCollaborationsReturnsPage() throws Exception {
    UUID campaignId = UUID.randomUUID();
    UUID collabId = UUID.randomUUID();
    CollaborationSummaryDto dto =
        new CollaborationSummaryDto(
            collabId,
            campaignId,
            UUID.randomUUID(),
            UUID.randomUUID(),
            "KOC Channel",
            "TIKTOK",
            "https://avatar.url",
            new BigDecimal("2000000.00"),
            "VND",
            CollaborationStatus.IN_PROGRESS,
            "https://tiktok.com/@koc/123",
            "Test brief",
            LocalDateTime.now(),
            null,
            LocalDateTime.now(),
            false,
            false);

    PageResponse<CollaborationSummaryDto> page =
        new PageResponse<>(List.of(dto), 0, 20, 1, 1);

    when(collaborationService.getCampaignCollaborations(eq(campaignId), any(), any()))
        .thenReturn(page);

    mockMvc
        .perform(get("/campaigns/" + campaignId + "/collaborations"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.items[0].id").value(collabId.toString()))
        .andExpect(jsonPath("$.data.items[0].creatorName").value("KOC Channel"))
        .andExpect(jsonPath("$.data.items[0].platform").value("TIKTOK"))
        .andExpect(jsonPath("$.data.items[0].status").value("IN_PROGRESS"))
        .andExpect(jsonPath("$.data.totalElements").value(1));
  }

  @Test
  @DisplayName("GET /collaborations/{id} returns collaboration detail with outcome and review")
  void getCollaborationDetailReturnsDetail() throws Exception {
    UUID collabId = UUID.randomUUID();
    OutcomeResponse outcome =
        new OutcomeResponse(
            UUID.randomUUID(),
            collabId,
            15000L,
            250L,
            "https://tiktok.com/@koc/123",
            "Nice video",
            "SELF_REPORTED",
            LocalDateTime.now(),
            LocalDateTime.now());

    CreatorReviewResponse review =
        new CreatorReviewResponse(
            UUID.randomUUID(),
            collabId,
            outcome.id(),
            UUID.randomUUID(),
            (short) 5,
            (short) 5,
            true,
            "Highly recommended",
            LocalDateTime.now());

    CollaborationDetailResponse detail =
        new CollaborationDetailResponse(
            collabId,
            UUID.randomUUID(),
            "Summer Campaign",
            "Brand X",
            UUID.randomUUID(),
            UUID.randomUUID(),
            "KOC Channel",
            "TIKTOK",
            "https://avatar.url",
            "https://profile.url",
            new BigDecimal("3000000.00"),
            "VND",
            CollaborationStatus.COMPLETED,
            "https://tiktok.com/@koc/123",
            "Approved",
            LocalDateTime.now(),
            LocalDateTime.now(),
            LocalDateTime.now(),
            outcome,
            review,
            List.of());

    when(collaborationService.getCollaborationDetail(collabId)).thenReturn(detail);

    mockMvc
        .perform(get("/collaborations/" + collabId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.id").value(collabId.toString()))
        .andExpect(jsonPath("$.data.campaignName").value("Summer Campaign"))
        .andExpect(jsonPath("$.data.brandName").value("Brand X"))
        .andExpect(jsonPath("$.data.status").value("COMPLETED"))
        .andExpect(jsonPath("$.data.outcome.views").value(15000))
        .andExpect(jsonPath("$.data.review.overallRating").value(5))
        .andExpect(jsonPath("$.data.review.wouldCollaborateAgain").value(true));
  }

  @Test
  @DisplayName("GET /creators/portal/collaborations returns assigned collaborations list for creator")
  void getCreatorPortalCollaborationsReturnsList() throws Exception {
    UUID collabId = UUID.randomUUID();
    CreatorCollaborationDto dto =
        new CreatorCollaborationDto(
            collabId,
            UUID.randomUUID(),
            "Fall Campaign",
            "Brand Y",
            UUID.randomUUID(),
            "YOUTUBE",
            "Channel Y",
            new BigDecimal("5000000.00"),
            "VND",
            CollaborationStatus.IN_PROGRESS,
            null,
            "Deliver by Friday",
            LocalDateTime.now(),
            null,
            LocalDateTime.now(),
            false,
            false);

    when(collaborationService.getCreatorPortalCollaborations(any()))
        .thenReturn(List.of(dto));

    mockMvc
        .perform(get("/creators/portal/collaborations"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].id").value(collabId.toString()))
        .andExpect(jsonPath("$.data[0].campaignName").value("Fall Campaign"))
        .andExpect(jsonPath("$.data[0].brandName").value("Brand Y"))
        .andExpect(jsonPath("$.data[0].platform").value("YOUTUBE"))
        .andExpect(jsonPath("$.data[0].status").value("IN_PROGRESS"));
  }

  @Test
  @DisplayName("POST /collaborations/{id}/evidence submits evidence asset successfully")
  void submitEvidenceReturnsCreated() throws Exception {
    UUID collabId = UUID.randomUUID();
    UUID assetId = UUID.randomUUID();
    EvidenceAssetResponse response =
        new EvidenceAssetResponse(
            assetId,
            collabId,
            null,
            "POST_LINK",
            "https://tiktok.com/@koc/123",
            "CREATOR_SUBMITTED",
            LocalDateTime.now());

    when(collaborationService.submitEvidence(eq(collabId), any())).thenReturn(response);

    String body =
        "{\"assetType\":\"POST_LINK\",\"storageUrl\":\"https://tiktok.com/@koc/123\",\"publishedContentUrl\":\"https://tiktok.com/@koc/123\"}";

    mockMvc
        .perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                    "/collaborations/" + collabId + "/evidence")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.id").value(assetId.toString()))
        .andExpect(jsonPath("$.data.assetType").value("POST_LINK"))
        .andExpect(jsonPath("$.data.storageUrl").value("https://tiktok.com/@koc/123"))
        .andExpect(jsonPath("$.data.source").value("CREATOR_SUBMITTED"));
  }

  @Test
  @DisplayName("GET /collaborations/{id}/evidence returns list of evidence assets")
  void getEvidenceAssetsReturnsList() throws Exception {
    UUID collabId = UUID.randomUUID();
    EvidenceAssetResponse response =
        new EvidenceAssetResponse(
            UUID.randomUUID(),
            collabId,
            null,
            "INSIGHTS_SCREENSHOT",
            "https://s3.aws.com/proof.png",
            "BRAND_UPLOAD",
            LocalDateTime.now());

    when(collaborationService.getEvidenceAssets(collabId)).thenReturn(List.of(response));

    mockMvc
        .perform(get("/collaborations/" + collabId + "/evidence"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].assetType").value("INSIGHTS_SCREENSHOT"))
        .andExpect(jsonPath("$.data[0].storageUrl").value("https://s3.aws.com/proof.png"))
        .andExpect(jsonPath("$.data[0].source").value("BRAND_UPLOAD"));
  }
}
