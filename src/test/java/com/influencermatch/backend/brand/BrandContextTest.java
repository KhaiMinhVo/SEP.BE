package com.influencermatch.backend.brand;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import com.influencermatch.backend.brand.model.BrandContext;
import static org.assertj.core.api.Assertions.*;

class BrandContextTest {
    @Test void updatingProfileCreatesNextContextVersion() {
        var context = BrandContext.builder().contextVersion(1).profileContext(new ObjectMapper().createObjectNode()).build();
        context.updateProfileContext(new ObjectMapper().createObjectNode().put("businessName", "New Name"));
        assertThat(context.getContextVersion()).isEqualTo(2);
        assertThat(context.getProfileContext().get("businessName").asText()).isEqualTo("New Name");
    }
}


