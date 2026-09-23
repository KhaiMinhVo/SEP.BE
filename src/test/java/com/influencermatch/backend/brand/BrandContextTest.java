package com.influencermatch.backend.brand;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.influencermatch.backend.brand.model.BrandContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BrandContextTest {

  @Test
  @DisplayName("Update Profile Context - Should increment context version and update profile data")
  void updatingProfileCreatesNextContextVersion() {
    // GIVEN
    BrandContext context = BrandContext.builder()
        .contextVersion(1)
        .profileContext(new ObjectMapper().createObjectNode())
        .build();

    // WHEN
    context.updateProfileContext(
        new ObjectMapper().createObjectNode().put("businessName", "New Name")
    );

    // THEN
    assertThat(context.getContextVersion()).isEqualTo(2);
    assertThat(context.getProfileContext().get("businessName").asText()).isEqualTo("New Name");
  }
}
