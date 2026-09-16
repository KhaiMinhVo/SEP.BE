package com.influencermatch.backend.brand;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class BrandContextM4Test {
    @Test void updatingProfileCreatesNextContextVersion() {
        var context = BrandContextM4.builder().contextVersion(1).profileContext(new ObjectMapper().createObjectNode()).build();
        context.updateProfileContext(new ObjectMapper().createObjectNode().put("businessName", "New Name"));
        assertThat(context.getContextVersion()).isEqualTo(2);
        assertThat(context.getProfileContext().get("businessName").asText()).isEqualTo("New Name");
    }
}
