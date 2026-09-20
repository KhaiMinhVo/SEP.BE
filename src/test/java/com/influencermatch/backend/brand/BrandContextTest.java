package com.influencermatch.backend.brand;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.influencermatch.backend.brand.model.BrandContext;
import org.junit.jupiter.api.Test;

class BrandContextTest {
  @Test
  void updatingProfileCreatesNextContextVersion() {
    var context =
        BrandContext.builder()
            .contextVersion(1)
            .profileContext(new ObjectMapper().createObjectNode())
            .build();
    context.updateProfileContext(
        new ObjectMapper().createObjectNode().put("businessName", "New Name"));
    assertThat(context.getContextVersion()).isEqualTo(2);
    assertThat(context.getProfileContext().get("businessName").asText()).isEqualTo("New Name");
  }
}
