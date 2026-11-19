package com.influencermatch.backend.discovery;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.influencermatch.backend.creator.client.AiServiceClient;
import com.influencermatch.backend.creator.enums.MetricDataStatus;
import com.influencermatch.backend.creator.model.*;
import com.influencermatch.backend.creator.repository.*;
import com.influencermatch.backend.support.PostgresApiTestSupport;
import com.influencermatch.backend.user.enums.Role;
import com.influencermatch.backend.user.model.User;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class ProgressiveDiscoveryPostgresTest extends PostgresApiTestSupport {
    @Autowired CreatorRepository creators;
    @Autowired PublicCreatorMetricRepository metrics;
    @Autowired CreatorMasterRepository masters;
    @Autowired jakarta.persistence.EntityManager entityManager;
    @MockBean AiServiceClient ai;
    User owner;
    UUID campaign;
    List<Float> vector;

    @BeforeEach void setup() throws Exception {
        owner = account(Role.BRAND);
        campaign = createCampaign(owner, createBrand(owner));
        entityManager.flush();
        jdbc.update("update campaign set product_service='kem chống nắng',niches=array['SKINCARE'],"
            + "locations=array['Thành phố Hồ Chí Minh'],platforms=array['TIKTOK'],follower_min=20000 where id=?", campaign);
        entityManager.clear();
        vector = new ArrayList<>(Collections.nCopies(768, 0.0f));
        vector.set(0, 1.0f);
        when(ai.getEmbedding(anyString())).thenReturn(vector);
    }

    Creator candidate(String niche, String category, String summary, String location, long followers) {
        var creator = creators.saveAndFlush(Creator.builder().platform("TIKTOK").location(location)
            .externalId(UUID.randomUUID().toString()).userName(UUID.randomUUID().toString())
            .displayName("Reviewer").build());
        metrics.saveAndFlush(PublicCreatorMetric.builder().creator(creator).niche(niche).category(category)
            .contentSummary(summary).creatorType("KOC").followers(followers)
            .engagementRate(new BigDecimal("3.5")).collectedAt(LocalDateTime.now())
            .observedAt(LocalDateTime.now()).freshnessStatus(MetricDataStatus.FRESH).build());
        return creator;
    }

    JsonNode rows(JsonNode data) { return data.has("content") ? data.path("content") : data.path("items"); }
    List<String> ids(JsonNode rows) {
        var ids = new ArrayList<String>();
        rows.forEach(row -> ids.add(row.path("creatorId").asText()));
        return ids;
    }

    @Test void sparsePoolPreservesExactThenExpandsLocationFollowersAndRelatedTopics() throws Exception {
        var exact = candidate("SKINCARE", "BEAUTY", "Review kem chống nắng", "TP.HCM", 25000);
        var elsewhere = candidate("SKINCARE", "BEAUTY", "Review kem chống nắng", "Hà Nội", 25000);
        var smaller = candidate("SKINCARE", "BEAUTY", "Review kem chống nắng", "TP.HCM", 18000);
        var related = candidate("UNKNOWN", "BEAUTY", "Trải nghiệm mỹ phẩm", null, 15000);
        var unrelated = candidate("PETS", "PETS", "Đồ chơi thú cưng", "TP.HCM", 25000);
        jdbc.update("update creator set embedding=cast(? as vector) where id=?", vector.toString(), related.getId());
        for (String route : List.of("discover", "discovery")) {
            var result = rows(call(owner, get("/campaigns/" + campaign + "/" + route), null, 200).path("data"));
            assertThat(ids(result)).containsExactly(exact.getId().toString(), elsewhere.getId().toString(),
                smaller.getId().toString(), related.getId().toString()).doesNotContain(unrelated.getId().toString());
            assertThat(result.get(0).path("isSimilarFallback").asBoolean()).isFalse();
            assertThat(result.get(1).path("expansionReasons").toString()).contains("LOCATION");
            assertThat(result.get(2).path("expansionReasons").toString()).contains("FOLLOWERS");
            assertThat(result.get(3).path("expansionReasons").toString()).contains("TOPIC", "FOLLOWERS");
        }
        var page = call(owner, get("/campaigns/" + campaign + "/discovery").param("page", "1")
            .param("size", "1"), null, 200).path("data");
        assertThat(ids(page.path("content"))).containsExactly(elsewhere.getId().toString());
        assertThat(page.path("totalElements").asInt()).isEqualTo(4);
    }

    @Test void explicitUiFiltersRemainHardEvenWhenThereAreNoExactMatches() throws Exception {
        candidate("SKINCARE", "BEAUTY", "Review kem chống nắng", "Hà Nội", 18000);
        for (String route : List.of("discover", "discovery")) {
            var url = "/campaigns/" + campaign + "/" + route;
            assertThat(ids(rows(call(owner, get(url).param("minFollowers", "20000"), null, 200).path("data")))).isEmpty();
            assertThat(ids(rows(call(owner, get(url).param("platform", "YOUTUBE"), null, 200).path("data")))).isEmpty();
            assertThat(ids(rows(call(owner, get(url).param("niche", "PETS"), null, 200).path("data")))).isEmpty();
        }
    }

    @Test void embeddingOutageFallsBackToRelatedCategoryWithoutReturningUnrelatedCatalog() throws Exception {
        when(ai.getEmbedding(anyString())).thenThrow(new IllegalStateException("unavailable"));
        var related = candidate("UNKNOWN", "BEAUTY", "Trải nghiệm mỹ phẩm", null, 15000);
        candidate("PETS", "PETS", "Thú cưng", null, 25000);
        for (String route : List.of("discover", "discovery")) {
            var result = rows(call(owner, get("/campaigns/" + campaign + "/" + route), null, 200).path("data"));
            assertThat(ids(result)).containsExactly(related.getId().toString());
            assertThat(result.get(0).path("isSimilarFallback").asBoolean()).isTrue();
        }
        verify(ai, never()).getExplanation(any());
    }

    @Test void sufficientExactMatchesNeverTriggerUnnecessaryExpansion() throws Exception {
        var expected = new HashSet<String>();
        for (int i = 0; i < 6; i++)
            expected.add(candidate("SKINCARE", "BEAUTY", "Review kem chống nắng", "TP.HCM", 25000).getId().toString());
        var elsewhere = candidate("SKINCARE", "BEAUTY", "Review kem chống nắng", "Hà Nội", 25000);
        var result = rows(call(owner, get("/campaigns/" + campaign + "/discovery"), null, 200).path("data"));
        assertThat(ids(result)).containsExactlyInAnyOrderElementsOf(expected).doesNotContain(elsewhere.getId().toString());
        result.forEach(row -> assertThat(row.path("isSimilarFallback").asBoolean()).isFalse());
    }

    @Test void vectorRetrievesDifferentLabelsButExcludesOppositeEmbeddings() throws Exception {
        var similar = candidate("LIFESTYLE", "LIFESTYLE", "Trải nghiệm sản phẩm hằng ngày", null, 25000);
        var unrelated = candidate("PETS", "PETS", "Chăm sóc thú cưng", null, 25000);
        jdbc.update("update creator set embedding=cast(? as vector) where id=?", vector.toString(), similar.getId());
        var opposite = new ArrayList<>(vector); opposite.set(0, -1.0f);
        jdbc.update("update creator set embedding=cast(? as vector) where id=?", opposite.toString(), unrelated.getId());
        var result = rows(call(owner, get("/campaigns/" + campaign + "/discovery"), null, 200).path("data"));
        assertThat(ids(result)).containsExactly(similar.getId().toString());
        assertThat(result.get(0).path("retrievalSimilarity").asDouble()).isCloseTo(1.0, within(0.00001));
        assertThat(result.get(0).path("isSimilarFallback").asBoolean()).isTrue();
    }
}
