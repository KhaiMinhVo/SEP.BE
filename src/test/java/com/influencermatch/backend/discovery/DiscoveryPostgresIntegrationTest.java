package com.influencermatch.backend.discovery;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.influencermatch.backend.creator.client.AiServiceClient;
import com.influencermatch.backend.creator.enums.*;
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

@org.springframework.test.context.TestPropertySource(properties = {
    "logging.level.com.influencermatch.backend=WARN",
    "logging.level.org.springframework=WARN",
    "logging.level.org.hibernate.SQL=OFF"
})
class DiscoveryPostgresIntegrationTest extends PostgresApiTestSupport {
  @Autowired com.influencermatch.backend.campaign.embedding.CampaignEmbeddingStore embeddings;
  @Autowired PostRepository posts;
  @Autowired CreatorRepository creators;
  @Autowired PublicCreatorMetricRepository metrics;
  @Autowired CreatorMasterRepository masters;
  @MockBean AiServiceClient ai;
  User owner;
  UUID campaign;
  String topic;

  @BeforeEach void setup() throws Exception {
    owner = account(Role.BRAND);
    var brand = createBrand(owner);
    campaign = createCampaign(owner, brand);
    topic = "sunblock" + UUID.randomUUID().toString().replace("-", "");
    jdbc.update("update campaign set name=?,product_service=?,niches='{}' where id=?",
        "Campaign " + topic, topic, campaign);
    when(ai.getEmbedding(anyString())).thenReturn(List.of());
  }

  Creator creator(String name, String niche, String summary, String type) {
    var c = creators.saveAndFlush(Creator.builder().platform("TIKTOK")
        .externalId(UUID.randomUUID().toString()).userName(name + UUID.randomUUID().toString().replace("-", ""))
        .displayName(name).build());
    snapshot(c, niche, summary, type, LocalDateTime.now(), MetricDataStatus.FRESH);
    return c;
  }

  PublicCreatorMetric snapshot(Creator c, String niche, String summary, String type,
      LocalDateTime at, MetricDataStatus status) {
    return metrics.saveAndFlush(PublicCreatorMetric.builder().creator(c).niche(niche).category(niche)
        .contentSummary(summary).creatorType(type).followers(25000L)
        .engagementRate(new BigDecimal("3.5")).collectedAt(at).observedAt(at)
        .freshnessStatus(status).build());
  }

  List<String> ids(JsonNode data) {
    var result = new ArrayList<String>();
    var items = data.has("content") ? data.path("content") : data.path("items");
    items.forEach(item -> result.add(item.path("creatorId").asText()));
    return result;
  }

  @Test void productWithoutNicheNeverDumpsUnrelatedCatalogOnEitherEndpoint() throws Exception {
    var skincare = creator("Skincare", "BEAUTY", "Honest review " + topic, "KOC");
    creator("Pets", "PETS", "Pet care and household products", "KOC");
    creator("School", "EDUCATION", "School supplies for students", "KOC");
    creator("Brand", "BEAUTY", "Official shop " + topic, "BRAND");
    for (var route : List.of("discover", "discovery")) {
      var data = call(owner, get("/campaigns/" + campaign + "/" + route), null, 200).path("data");
      assertThat(ids(data)).as(route).containsExactly(skincare.getId().toString());
    }
  }

  @Test void sunscreenCampaignExcludesPetsAndSchoolSuppliesWithAndWithoutNiche() throws Exception {
    jdbc.update("update campaign set product_service=?,name=? where id=?",
        "kem chống nắng", "Kem chống nắng nâng tone kiềm dầu học đường " + topic, campaign);
    var skincare = creator("Review " + topic, "Chăm sóc da", "Review kem chống nắng cho da dầu", "KOC");
    creator("Pets " + topic, "Thú cưng", "Chăm sóc thú cưng và đồ gia dụng", "KOC");
    creator("School " + topic, "Đồ dùng học tập", "Review đồ dùng học tập và văn phòng phẩm", "KOC");
    for (var niche : List.of("", "Chăm sóc da & Skincare")) {
      jdbc.update("update campaign set niches=string_to_array(?, ',') where id=?", niche, campaign);
      for (var route : List.of("discover", "discovery")) {
        var data = call(owner, get("/campaigns/" + campaign + "/" + route), null, 200).path("data");
        assertThat(ids(data)).as(route + ": " + niche).containsExactly(skincare.getId().toString());
      }
    }
  }

  @Test void uiFiltersApplyBeforeRankingAndCannotWidenCampaignBounds() throws Exception {
    var wanted = creator("Wanted", "BEAUTY", topic, "KOC");
    var wrongEr = creator("WrongER", "BEAUTY", topic, "KOC");
    jdbc.update("update public_creator_metric set engagement_rate=0.1 where creator_id=?", wrongEr.getId());
    var wrongFollowers = creator("WrongFollowers", "BEAUTY", topic, "KOC");
    jdbc.update("update public_creator_metric set followers=90000 where creator_id=?", wrongFollowers.getId());
    jdbc.update("update campaign set follower_min=20000,follower_max=50000 where id=?", campaign);
    for (var route : List.of("discover", "discovery")) {
      var data = call(owner, get("/campaigns/" + campaign + "/" + route)
          .param("minEr", "1").param("maxEr", "4").param("tier", "MICRO")
          .param("minFollowers", "0").param("maxFollowers", "1000000"), null, 200).path("data");
      assertThat(ids(data)).as(route).containsExactly(wanted.getId().toString());
      data = call(owner, get("/campaigns/" + campaign + "/" + route)
          .param("niche", "PETS"), null, 200).path("data");
      assertThat(ids(data)).as(route).isEmpty();
    }
  }

  @Test void databaseKeywordRankingSelectsTop50WithoutCallingAi() throws Exception {
    for (int i = 0; i < 51; i++) {
      var candidate = creator("Other" + i, "BEAUTY", topic, "KOC");
      jdbc.update("update public_creator_metric set engagement_rate=20 where creator_id=?", candidate.getId());
    }
    var best = creator(topic, "BEAUTY", topic, "KOC");
    for (var route : List.of("discover", "discovery")) {
      var data = call(owner, get("/campaigns/" + campaign + "/" + route)
          .param("size", "1"), null, 200).path("data");
      assertThat(ids(data)).as(route).containsExactly(best.getId().toString());
      assertThat(data.path("totalElements").asInt()).isEqualTo(50);
      if (route.equals("discovery")) {
        var card = data.path("content").get(0);
        assertThat(card.path("aiScoreAvailable").asBoolean()).isFalse();
        assertThat(card.path("finalScore").isNull() || card.path("finalScore").isMissingNode()).isTrue();
      }
    }
    verifyNoInteractions(ai);
  }

  @Test void databaseSelectionThenShortlistThenAiScoresOnlySelectedCreator() throws Exception {
    var selected = creator("Selected", "BEAUTY", topic, "KOC");
    var outsider = creator("Not selected", "BEAUTY", topic, "KOC");
    jdbc.update("update public_creator_metric set engagement_rate=0.04,avg_views=5000,avg_likes=200 where creator_id=?",
        selected.getId());
    for (int i = 1; i <= 3; i++)
      posts.saveAndFlush(Post.builder().creator(selected).platformPostId(UUID.randomUUID().toString())
          .postUrl("https://example.test/post/" + UUID.randomUUID())
          .views(5000L + i * 100L).likes(200L)
          .comments(5L).shares(5L)
          .postedAt(java.time.OffsetDateTime.now().minusDays(i).toString()).build());

    var data = call(owner, get("/campaigns/" + campaign + "/discover"), null, 200).path("data");
    assertThat(ids(data)).contains(selected.getId().toString(), outsider.getId().toString());
    call(owner, post("/campaigns/" + campaign + "/shortlist"),
        Map.of("creatorId", selected.getId(), "priority", "HIGH"), 200);
    verifyNoInteractions(ai);
    call(owner, patch("/campaigns/" + campaign + "/status"),
        Map.of("status", "READY_FOR_DISCOVERY"), 200);
    var job = jdbc.queryForObject("select id from campaign_embedding_job where context_id="
        + "(select embedding_source_context_id from campaign_context where campaign_id=? and active)",
        UUID.class, campaign);
    var lease = embeddings.claim(job);
    var vector = Collections.nCopies(768, 0.1f);
    assertThat(embeddings.finish(lease, vector)).isTrue();
    jdbc.update("update creator set embedding=cast(? as vector) where id=?", vector.toString(), selected.getId());
    when(ai.getExplanation(any())).thenReturn(
        com.influencermatch.backend.recommendation.dto.ExplainResponseDto.builder()
            .summary("Fixture").explanationAvailable(false).build());
    data = call(owner, post("/campaigns/" + campaign + "/ai-recommendations"), null, 200).path("data");
    var scoredIds = new ArrayList<String>();
    for (String field : List.of("recommendations", "provisionalRecommendations"))
      data.path(field).forEach(item -> scoredIds.add(item.path("creatorId").asText()));
    assertThat(scoredIds).containsExactly(selected.getId().toString());
    assertThat(jdbc.queryForObject("select candidate_source from recommendation_run where id=?",
        String.class, UUID.fromString(data.path("recommendationRunId").asText()))).isEqualTo("SHORTLIST");
  }

  @Test void shortlistScoringUsesLatestClassificationAndDoesNotInventMissingSimilarity() throws Exception {
    var selected = creator("Selected", "BEAUTY", topic, "KOC");
    call(owner, post("/campaigns/" + campaign + "/shortlist"),
        Map.of("creatorId", selected.getId(), "priority", "HIGH"), 200);
    String vector = Collections.nCopies(768, 0.1f).toString();
    var candidates = metrics.findCandidatesByShortlist(campaign, vector, false,
        List.of(UUID.randomUUID()));
    assertThat(candidates).hasSize(1);
    assertThat(candidates.getFirst().getSemanticScore()).isNull();
    var rejected = snapshot(selected, "BEAUTY", topic, "REJECTED",
        LocalDateTime.now().plusMinutes(1), MetricDataStatus.FRESH);
    rejected.setObservedAt(LocalDateTime.now().minusDays(2));
    metrics.saveAndFlush(rejected);
    assertThat(metrics.findCandidatesByShortlist(campaign, vector, false,
        List.of(UUID.randomUUID()))).isEmpty();
  }

  @Test void emptyShortlistAndDiscoverySourceCannotStartAiScoring() throws Exception {
    var url = "/campaigns/" + campaign + "/ai-recommendations";
    var error = call(owner, post(url), null, 409);
    assertThat(error.path("code").asText()).isEqualTo("RECOMMENDATION_NO_CANDIDATES");
    call(owner, post(url).param("candidateSource", "DISCOVERY"), null, 400);
    verifyNoInteractions(ai);
    assertThat(jdbc.queryForObject("select count(*) from recommendation_run where campaign_id=?",
        Integer.class, campaign)).isZero();
    assertThat(jdbc.queryForObject("select sum(used_ai_quota) from subscription where brand_profile_id="
        + "(select brand_profile_id from campaign where id=?)", Long.class, campaign)).isZero();
  }

  @Test void claimedFeeAndPlatformFiltersRemainEffectiveOnBothEndpoints() throws Exception {
    var wanted = creator("Authorized", "BEAUTY", topic, "KOC");
    var master = masters.saveAndFlush(CreatorMaster.builder().user(account(Role.CREATOR))
        .canonicalName("Authorized").status("ACTIVE").build());
    wanted.setMaster(master); wanted.setSourceType(CreatorSourceType.AUTHORIZED);
    wanted.setAuthorizedAt(LocalDateTime.now()); creators.saveAndFlush(wanted);
    jdbc.update("update public_creator_metric set actual_fee=1500000 where creator_id=?", wanted.getId());
    creator("Public", "BEAUTY", topic, "KOC");
    for (var route : List.of("discover", "discovery")) {
      var url = "/campaigns/" + campaign + "/" + route;
      var data = call(owner, get(url).param("isClaimed", "true")
          .param("minFee", "1000000").param("maxFee", "2000000")
          .param("sortBy", "LOWEST_COMPARABLE_FEE"), null, 200).path("data");
      assertThat(ids(data)).as(route).containsExactly(wanted.getId().toString());
      data = call(owner, get(url).param("platform", "YOUTUBE"), null, 200).path("data");
      assertThat(ids(data)).as(route).isEmpty();
      call(owner, get(url).param("minFollowers", "50000").param("maxFollowers", "10000"), null, 400);
    }
  }

  @Test void newestRejectedOrStaleSnapshotCannotResurrectOldAcceptedProfile() throws Exception {
    var accepted = creator("Accepted", "BEAUTY", topic, "KOC");
    var rejected = creator("Rejected", "BEAUTY", topic, "KOC");
    snapshot(rejected, "BEAUTY", topic, "REJECTED", LocalDateTime.now().plusMinutes(1), MetricDataStatus.FRESH);
    var stale = creator("Stale", "BEAUTY", topic, "KOC");
    snapshot(stale, "BEAUTY", topic, "KOC", LocalDateTime.now().plusMinutes(1), MetricDataStatus.STALE);
    for (var route : List.of("discover", "discovery")) {
      var data = call(owner, get("/campaigns/" + campaign + "/" + route), null, 200).path("data");
      assertThat(ids(data)).as(route).containsExactly(accepted.getId().toString());
    }
  }

  @Test void multiwordKeywordRequiresAllTermsAndAcceptsPunctuation() throws Exception {
    var wanted = creator("Wanted", "BEAUTY", topic + " sunscreen", "KOC");
    creator("Partial", "BEAUTY", topic + " pets", "KOC");
    for (var route : List.of("discover", "discovery")) {
      var data = call(owner, get("/campaigns/" + campaign + "/" + route)
          .param("keyword", topic + " sunscreen!"), null, 200).path("data");
      assertThat(ids(data)).as(route).containsExactly(wanted.getId().toString());
    }
  }

  @Test void masterGroupingAndCountUseRealDatabaseAndStablePagination() throws Exception {
    var creatorOwner = account(Role.CREATOR);
    var master = masters.saveAndFlush(CreatorMaster.builder().user(creatorOwner)
        .canonicalName("Skincare channels").status("ACTIVE").build());
    var first = creator("First", "BEAUTY", topic, "KOC");
    var second = creator("Second", "BEAUTY", topic, "KOC");
    first.setMaster(master); second.setMaster(master);
    first.setSourceType(CreatorSourceType.AUTHORIZED); second.setSourceType(CreatorSourceType.AUTHORIZED);
    first.setAuthorizedAt(LocalDateTime.now()); second.setAuthorizedAt(LocalDateTime.now());
    creators.saveAndFlush(first); creators.saveAndFlush(second);
    creator("Independent", "BEAUTY", topic, "KOC");
    for (var route : List.of("discover", "discovery")) {
      var seen = new HashSet<String>();
      for (int page = 0; page < 2; page++) {
        var data = call(owner, get("/campaigns/" + campaign + "/" + route)
            .param("page", String.valueOf(page)).param("size", "1"), null, 200).path("data");
        assertThat(data.path("totalElements").asInt()).as(route).isEqualTo(2);
        assertThat(ids(data)).hasSize(1);
        assertThat(seen.add(ids(data).getFirst())).isTrue();
      }
    }
  }
}
