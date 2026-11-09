package com.influencermatch.backend.brand;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.influencermatch.backend.brand.model.BrandContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BrandContextTest {

  @Test
  @DisplayName("Update Profile Context - Rejects in-place edits; context versions are immutable")
  void updatingProfileCreatesNextContextVersion() {
    // GIVEN
    BrandContext context =
        BrandContext.builder()
            .contextVersion(1)
            .profileContext(new ObjectMapper().createObjectNode())
            .build();

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                context.updateProfileContext(
                    new ObjectMapper().createObjectNode().put("businessName", "New Name")))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThat(context.getContextVersion()).isEqualTo(1);
    assertThat(context.getProfileContext().isEmpty()).isTrue();
  }
}
