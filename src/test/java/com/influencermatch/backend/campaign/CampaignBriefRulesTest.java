package com.influencermatch.backend.campaign;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.influencermatch.backend.campaign.dto.*;
import com.influencermatch.backend.campaign.service.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class CampaignBriefRulesTest {
  @Test
  void campaignRevisionRejectsMissingMalformedAndStale() {
    var id = UUID.randomUUID();
    assertThatThrownBy(() -> CampaignRevision.require(null, id, 0))
        .isInstanceOf(com.influencermatch.backend.exception.BusinessException.class);
    assertThatThrownBy(() -> CampaignRevision.require("*", id, 0))
        .isInstanceOf(com.influencermatch.backend.exception.ValidationException.class);
    assertThatThrownBy(() -> CampaignRevision.require(CampaignRevision.tag(id, 1), id, 0))
        .isInstanceOf(com.influencermatch.backend.exception.BusinessException.class);
    assertThatCode(() -> CampaignRevision.require(CampaignRevision.tag(id, 0), id, 0))
        .doesNotThrowAnyException();
  }

  @Test
  void embeddingUsesFrozenWhitelistedContextAndCampaignPrecedence() {
    var mapper = new ObjectMapper();
    var data =
        mapper
            .createObjectNode()
            .put("brief", "Brief")
            .put("productService", "Product")
            .put("phoneNumber", "PRIVATE")
            .put("feedbackHistory", "PRIVATE");
    data.putArray("niches").add("Food");
    var brand =
        mapper
            .createObjectNode()
            .put("industry", "Beauty")
            .put("guidelines", "No medical claims")
            .put("email", "PRIVATE");
    String text = CampaignBriefData.text(data, brand);
    assertThat(text)
        .contains("Brief", "Food", "No medical claims")
        .doesNotContain("Beauty", "PRIVATE");
  }

  @Test
  void autofillParsesNewFieldsWithoutInventingPerCreatorFee() {
    var service =
        new GeminiCampaignAutofillService(
            new com.influencermatch.backend.config.GeminiProperties(), new ObjectMapper());
    var result =
        (CampaignAutofillResponse)
            org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                service,
                "parseResponse",
                "{\"name\":\"Draft\",\"brief\":\"Launch\",\"creatorMode\":\"MULTI\",\"targetCreatorCount\":3,\"totalBudget\":9000000,\"deliverables\":[{\"platform\":\"TIKTOK\",\"contentType\":\"Review\",\"quantity\":2}]}");
    assertThat(result.totalBudget()).isEqualByComparingTo("9000000");
    assertThat(result.creatorFeeMax()).isNull();
    assertThat(result.deliverables().getFirst().quantity()).isEqualTo(2);
    assertThat(result.currency()).isEqualTo("VND");
  }
}
