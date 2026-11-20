package com.influencermatch.backend.campaign;
import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.influencermatch.backend.campaign.dto.CampaignRequest;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
class CampaignImageContractTest {
  @Test void imageIsOptionalAndRejectsUnsafeOrOversizedUrls() throws Exception {
    var mapper = new ObjectMapper();
    try (var factory = Validation.buildDefaultValidatorFactory()) {
      var validator = factory.getValidator();
      for (String image : new String[] {"https://cdn.example.com/campaign.png", null}) {
        var node = mapper.createObjectNode(); if (image != null) node.put("imageUrl", image);
        var request = mapper.treeToValue(node, CampaignRequest.class);
        assertTrue(validator.validateProperty(request, "imageUrl").isEmpty());
        assertEquals(image, mapper.readValue(mapper.writeValueAsString(request), CampaignRequest.class).imageUrl());
      }
      for (String image : new String[] {"http://example.com/a.png", "https:///", "javascript:alert(1)", "data:image/png;base64,AAAA", "https://example.com/has space", "https://example.com/" + "a".repeat(2048)}) {
        var node = mapper.createObjectNode().put("imageUrl", image);
        var request = mapper.treeToValue(node, CampaignRequest.class);
        assertFalse(validator.validateProperty(request, "imageUrl").isEmpty(), image);
      }
    }
  }
}
