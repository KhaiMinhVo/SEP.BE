package com.influencermatch.backend.discovery;

import static org.assertj.core.api.Assertions.*;
import com.influencermatch.backend.discovery.service.DiscoveryRelevanceQuery;
import org.junit.jupiter.api.Test;

class DiscoveryRelevanceQueryTest {
    @Test void retainsVietnameseSubtopicsWithoutCampaignBoilerplateOrRepeatedKeywords() {
        assertThat(DiscoveryRelevanceQuery.build("Cần tìm KOC review", "du lịch trekking leo núi", "trekking"))
            .isEqualTo("du lịch trekking leo núi");
    }
    @Test void emptyContextDoesNotInventRelevance() {
        assertThat(DiscoveryRelevanceQuery.build(null, "", "Tìm creator review sản phẩm")).isEmpty();
    }
}
