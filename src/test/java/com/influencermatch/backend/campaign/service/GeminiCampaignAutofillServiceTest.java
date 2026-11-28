package com.influencermatch.backend.campaign.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.influencermatch.backend.brand.enums.Platform;
import com.influencermatch.backend.campaign.dto.CampaignAutofillRequest;
import com.influencermatch.backend.campaign.dto.CampaignAutofillResponse;
import com.influencermatch.backend.campaign.enums.CampaignObjective;
import com.influencermatch.backend.config.GeminiProperties;
import com.influencermatch.backend.exception.BusinessException;
import com.influencermatch.backend.exception.ErrorCode;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GeminiCampaignAutofillServiceTest {

  private GeminiCampaignAutofillService service;
  private GeminiProperties properties;

  @BeforeEach
  void setUp() {
    properties = new GeminiProperties();
    service = new GeminiCampaignAutofillService(properties, new ObjectMapper());
  }

  @Test
  @DisplayName("autofill - Throws BusinessException when API key is unconfigured")
  void autofill_apiKeyMissing_throwsBusinessException() {
    properties.setApiKey("");
    CampaignAutofillRequest request = new CampaignAutofillRequest("Ra mắt son dưỡng môi thuần chay hè 2026");

    assertThatThrownBy(() -> service.autofill(request))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("code", ErrorCode.AI_SERVICE_UNAVAILABLE);
  }

  @Test
  @DisplayName("parseResponse - Successfully parses extracted vs inferred fields")
  void parseResponse_withExtractedAndInferredFields() {
    String json = """
        {
          "name": "Chiến Dịch Ra Mắt Son Dưỡng Hè 2026",
          "productService": "Son dưỡng thuần chay chiết xuất bơ hạt mỡ",
          "objective": "AWARENESS",
          "targetAudiences": ["Gen Z 18-24", "Người quan tâm mỹ phẩm thuần chay"],
          "contentType": "Short video review",
          "startDate": "2026-12-01",
          "endDate": "2026-12-31",
          "platforms": ["TIKTOK", "INSTAGRAM"],
          "niches": ["beauty", "skincare"],
          "locations": ["Việt Nam"],
          "followerMin": 10000,
          "followerMax": 100000,
          "budgetMin": 20000000,
          "budgetMax": 30000000,
          "extractedFields": ["name", "productService", "platforms", "budgetMin", "budgetMax"],
          "inferredFields": ["followerMin", "followerMax", "targetAudiences", "niches", "locations", "contentType", "startDate", "endDate", "objective"],
          "aiExplanation": "Đã trích xuất chính xác ngân sách 20-30tr và nền tảng TikTok/Instagram từ prompt của Brand. Do chưa có yêu cầu về quy mô KOC, AI đề xuất tier Micro 10k-100k followers."
        }
        """;

    CampaignAutofillResponse res = service.parseResponse(json);

    assertThat(res.name()).isEqualTo("Chiến Dịch Ra Mắt Son Dưỡng Hè 2026");
    assertThat(res.productService()).isEqualTo("Son dưỡng thuần chay chiết xuất bơ hạt mỡ");
    assertThat(res.objective()).isEqualTo(CampaignObjective.AWARENESS);
    assertThat(res.platforms()).containsExactly(Platform.TIKTOK, Platform.INSTAGRAM);
    assertThat(res.budgetMin()).isEqualByComparingTo(new BigDecimal("20000000"));
    assertThat(res.extractedFields()).containsExactly("name", "productService", "platforms", "budgetMin", "budgetMax");
    assertThat(res.inferredFields()).contains("followerMin", "followerMax", "targetAudiences");
    assertThat(res.aiExplanation()).contains("Đã trích xuất chính xác");
  }

  @Test
  @DisplayName("parseResponse - Strips markdown code fences if returned by model")
  void parseResponse_stripsMarkdownCodeFences() {
    String markdownWrapped = """
        ```json
        {
          "name": "Kem Chống Nắng Phổ Rộng",
          "productService": "Kem chống nắng SPF50+",
          "objective": "CONVERSION",
          "extractedFields": ["productService"],
          "inferredFields": ["name", "objective"],
          "aiExplanation": "AI suy luận mục tiêu Conversion dựa trên dòng sản phẩm kem chống nắng mùa hè."
        }
        ```
        """;

    CampaignAutofillResponse res = service.parseResponse(markdownWrapped);

    assertThat(res.name()).isEqualTo("Kem Chống Nắng Phổ Rộng");
    assertThat(res.productService()).isEqualTo("Kem chống nắng SPF50+");
    assertThat(res.objective()).isEqualTo(CampaignObjective.CONVERSION);
    assertThat(res.extractedFields()).containsExactly("productService");
    assertThat(res.inferredFields()).containsExactly("name", "objective");
  }

  @Test
  @DisplayName("autofillCampaignWithGemini UTCID04 [A]: Malformed JSON throws AI_PARSE_ERROR")
  void parseResponse_malformedJson_throwsBusinessException() {
    String malformed = "{ this is not json }";

    assertThatThrownBy(() -> service.parseResponse(malformed))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("code", ErrorCode.AI_PARSE_ERROR);
  }

  @Test
  @DisplayName("autofillCampaignWithGemini UTCID01 [N]: Maps objective TRAFFIC, platform TIKTOK, currency VND")
  void parseResponse_mapsTrafficTiktokAndVndCurrency() {
    String json = """
        {
          "name": "Traffic Push",
          "productService": "Serum Vitamin C",
          "objective": "TRAFFIC",
          "platforms": ["TIKTOK"],
          "extractedFields": ["objective", "platforms"],
          "inferredFields": ["name"]
        }
        """;

    CampaignAutofillResponse res = service.parseResponse(json);

    assertThat(res.objective()).isEqualTo(CampaignObjective.TRAFFIC);
    assertThat(res.platforms()).containsExactly(Platform.TIKTOK);
    assertThat(res.currency()).isEqualTo("VND");
  }

  @Test
  @DisplayName("autofillCampaignWithGemini UTCID02 [B]: Retains only deliverables with quantity > 0")
  void parseResponse_filtersZeroQuantityDeliverables() {
    String json = """
        {
          "name": "Deliverable Filter",
          "productService": "Sữa rửa mặt",
          "deliverables": [
            {"platform": "TIKTOK", "contentType": "Short video", "quantity": 0},
            {"platform": "TIKTOK", "contentType": "Unboxing", "quantity": 1}
          ],
          "extractedFields": ["deliverables"],
          "inferredFields": []
        }
        """;

    CampaignAutofillResponse res = service.parseResponse(json);

    assertThat(res.deliverables()).hasSize(1);
    assertThat(res.deliverables().getFirst().contentType()).isEqualTo("Unboxing");
    assertThat(res.deliverables().getFirst().quantity()).isEqualTo(1);
  }

  @Test
  @DisplayName("autofillCampaignWithGemini UTCID03 [N]: Both provenance arrays empty returns default extracted and inferred lists")
  void parseResponse_emptyProvenanceArraysFallBackToDefaults() {
    String json = """
        {
          "name": "Fallback Provenance",
          "productService": "Nước hoa hồng",
          "extractedFields": [],
          "inferredFields": []
        }
        """;

    CampaignAutofillResponse res = service.parseResponse(json);

    assertThat(res.extractedFields()).containsExactly("productService");
    assertThat(res.inferredFields()).contains("targetAudiences", "niches", "budgetMin", "budgetMax");
  }

  @Test
  @DisplayName("autofillCampaignWithGemini UTCID05 [A]: Unknown objective defaults to AWARENESS and invalid date maps to null")
  void parseResponse_unknownObjectiveAndInvalidDate() {
    String json = """
        {
          "name": "Bad Values",
          "productService": "Kem dưỡng",
          "objective": "UNKNOWN_OBJ",
          "startDate": "not-a-date",
          "endDate": "2026-13-45"
        }
        """;

    CampaignAutofillResponse res = service.parseResponse(json);

    assertThat(res.objective()).isEqualTo(CampaignObjective.AWARENESS);
    assertThat(res.startDate()).isNull();
    assertThat(res.endDate()).isNull();
  }
}
