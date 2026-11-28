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
    "application.brand-embedding.enabled=false",
    "application.campaign-embedding.enabled=false",
    "CREATOR_INGESTION_API_KEY=discovery-contract-test-key",
    "logging.level.com.influencermatch.backend=WARN",
    "logging.level.org.springframework=WARN",
    "logging.level.org.hibernate.SQL=OFF"
})
class DiscoveryPostgresIntegrationTest extends PostgresApiTestSupport {
  @Autowired com.influencermatch.backend.campaign.embedding.CampaignEmbeddingStore embeddings;
  @Autowired PostRepository posts;
  @Autowired CreatorRepository creators;
  @Autowired PublicCreatorMetricRepository metrics;
  @Autowired DataQualityRepository qualityMetrics;
  @Autowired CreatorMasterRepository masters;
  @Autowired jakarta.persistence.EntityManager entityManager;
  @MockBean AiServiceClient ai;
  User owner;
  UUID campaign;
  String topic;
  final List<UUID> fixtureCreatorIds = new ArrayList<>();

  @AfterEach void hideDiscoveryFixtures() {
    // Discovery searches a global catalogue; unique owners do not isolate creator fixtures.
    for (var id : fixtureCreatorIds)
      jdbc.update("update creator set discovery_hidden=true where id=?", id);
  }

  @BeforeEach void setup() throws Exception {
    owner = account(Role.BRAND);
    var brand = createBrand(owner);
    campaign = createCampaign(owner, brand);
    topic = "sunblock" + UUID.randomUUID().toString().replace("-", "");
    jdbc.update("update campaign set name=?,product_service=?,niches='{}',locations=array[?]::text[] where id=?",
        "Campaign " + topic, topic, "fixture-" + campaign, campaign);
    when(ai.getEmbedding(anyString())).thenReturn(List.of());
  }

  @Test void broadFoodCategoryDoesNotRequireProductPhraseOrAdmitVectorSimilarPets() throws Exception {
    jdbc.update("update campaign set product_service='Mì trộn ra mắt',name='Chiến dịch ẩm thực',"
        + "niches=array['Ẩm thực & Nhà hàng'] where id=?", campaign);
    var food = creator("Food reviewer", "Ẩm thực & Nhà hàng", "Review quán ăn và món ngon", "KOC");
    var pets = creator("Pet reviewer", "Thú cưng", "Chăm sóc thú cưng", "KOC");
    var vector = new ArrayList<>(Collections.nCopies(768, 0.0f));
    vector.set(0, 1.0f);
    when(ai.getEmbedding(anyString())).thenReturn(vector);
    jdbc.update("update creator set embedding=cast(? as vector) where id=?", vector.toString(), pets.getId());

    var home = creator("Home reviewer", "Đánh giá đồ gia dụng", "Review đồ dùng nhà bếp", "KOC");
    jdbc.update("update creator set embedding=cast(? as vector) where id=?", vector.toString(), home.getId());
    for (var route : List.of("discover", "discovery")) {
      var data = call(owner, get("/campaigns/" + campaign + "/" + route), null, 200).path("data");
      assertThat(ids(data)).containsExactly(food.getId().toString());
      var rows = data.has("content") ? data.path("content") : data.path("items");
      assertThat(rows.get(0).path("isSimilarFallback").asBoolean()).isFalse();
    }
  }

  @Test void crawlerCategoryCodesAndFoodLabelsMatchCampaignAndUiAliases() throws Exception {
    var food = creator("Food reviewer", "Thực phẩm & Nấu ăn", "Review món ngon", "KOC");
    var tech = creator("Tech reviewer", "TECH", "Review điện thoại", "KOC");
    for (var route : List.of("discover", "discovery")) {
      jdbc.update("update campaign set product_service='Mì trộn',niches=array['Ẩm thực & Nhà hàng'] where id=?", campaign);
      var data = call(owner, get("/campaigns/" + campaign + "/" + route)
          .param("niche", "FOOD"), null, 200).path("data");
      assertThat(ids(data)).containsExactly(food.getId().toString());
      jdbc.update("update campaign set product_service='Điện thoại',niches=array['Công nghệ & Điện thoại / PC'] where id=?", campaign);
      data = call(owner, get("/campaigns/" + campaign + "/" + route)
          .param("niche", "TECHNOLOGY"), null, 200).path("data");
      assertThat(ids(data)).containsExactly(tech.getId().toString());
    }
  }

  @Test void inferredProductTopicCannotReplaceExplicitFoodCategoryDuringExpansion() throws Exception {
    jdbc.update("update campaign set product_service='kem chống nắng',niches=array['FOOD'] where id=?", campaign);
    creator("Skincare reviewer", "SKINCARE", "Review kem chống nắng", "KOC");
    creator("Pet reviewer", "PETS", "Review thú cưng", "KOC");
    for (var route : List.of("discover", "discovery"))
      assertThat(ids(call(owner, get("/campaigns/" + campaign + "/" + route), null, 200).path("data"))).isEmpty();
  }

  @Test void realCrawlerPayloadsRemainConsistentThroughIngestionAndBothSearchEndpoints() throws Exception {
    JsonNode fixtures;
    try (var in = getClass().getResourceAsStream("/contracts/crawler-discovery-fixtures.json")) {
      fixtures = json.readTree(in);
    }
    String foodId = null;
    JsonNode foodPayload = null;
    for (var fixture : fixtures) {
      var payload = fixture.path("payload");
      var response = call(null, post("/creators/ingest")
          .header("X-Service-Key", "discovery-contract-test-key"), payload, 200).path("data");
      assertThat(response.path("status").asText()).isEqualTo("SUCCESS");
      var id = UUID.fromString(response.path("creatorId").asText());
      fixtureCreatorIds.add(id);
      var metric = metrics.findByCreatorId(id).orElseThrow();
      assertThat(metric.getCategory()).isEqualTo(fixture.path("expectedCategory").asText());
      assertThat(metric.getEngagementRate()).isEqualByComparingTo("0.005");
      assertThat(metric.getAvgLikes()).isEqualByComparingTo("100");
      assertThat(posts.findByCreatorId(id)).hasSize(3);
      var retry = call(null, post("/creators/ingest")
          .header("X-Service-Key", "discovery-contract-test-key"), payload, 200).path("data");
      assertThat(retry.path("status").asText()).isEqualTo("SKIPPED");
      if (fixture.path("expectedCategory").asText().equals("FOOD")) {
        foodId = id.toString(); foodPayload = payload;
      }
    }
    jdbc.update("update campaign set product_service='Mì trộn',niches=array['Ẩm thực & Nhà hàng'] where id=?", campaign);
    assertThat(qualityMetrics.pending(org.springframework.data.domain.PageRequest.of(0, 100)).getContent())
        .extracting(metric -> metric.getCreator().getId()).doesNotContainAnyElementsOf(fixtureCreatorIds);
    for (var route : List.of("discover", "discovery")) {
      var data = call(owner, get("/campaigns/" + campaign + "/" + route)
          .param("minEr", "0.004").param("maxEr", "0.006"), null, 200).path("data");
      assertThat(ids(data)).containsExactly(foodId);
      for (var keyword : List.of("Ẩm thực", "FOOD", "Thực phẩm & Nấu ăn")) {
        var byCategoryName = call(owner, get("/campaigns/" + campaign + "/" + route)
            .param("keyword", keyword), null, 200).path("data");
        assertThat(ids(byCategoryName)).as(route + ": " + keyword).containsExactly(foodId);
      }
    }
    // An explicit industry search supplies context even when the draft has no category yet.
    jdbc.update("update campaign set niches='{}',product_service='Product',name='New campaign' where id=?", campaign);
    for (var route : List.of("discover", "discovery")) {
      assertThat(ids(call(owner, get("/campaigns/" + campaign + "/" + route)
          .param("keyword", "Ẩm thực"), null, 200).path("data"))).containsExactly(foodId);
      assertThat(ids(call(owner, get("/campaigns/" + campaign + "/" + route)
          .param("niche", "FOOD"), null, 200).path("data"))).containsExactly(foodId);
    }
    jdbc.update("update campaign set product_service='Mì trộn',niches=array['FOOD'] where id=?", campaign);
    // A newer unknown classification must not keep stale FOOD labels from the previous crawl.
    // Legacy accounts may have multiple snapshots; ingestion must update the same latest record discovery reads.
    snapshot(creators.findById(UUID.fromString(foodId)).orElseThrow(), "FOOD", "Old food classification", "KOC",
        LocalDateTime.now().minusDays(1), MetricDataStatus.FRESH);
    var changed = ((com.fasterxml.jackson.databind.node.ObjectNode) foodPayload).deepCopy();
    changed.put("category", "UNKNOWN").put("niche", "UNKNOWN").put("crawlRunId", "contract-reclassified-food");
    call(null, post("/creators/ingest").header("X-Service-Key", "discovery-contract-test-key"), changed, 200);
    for (var route : List.of("discover", "discovery"))
      assertThat(ids(call(owner, get("/campaigns/" + campaign + "/" + route), null, 200).path("data"))).isEmpty();
  }

  @Test
  @org.springframework.transaction.annotation.Transactional
  void legacyCategoryMigrationRepairsContradictionsWithoutChangingManagerDecisions() throws Exception {
    var home = creator("Legacy home", "Gia dụng", "Review máy hút bụi", "KOC");
    var approved = creator("Approved food", "Thực phẩm", "Review món ngon", "KOC");
    entityManager.flush();
    jdbc.update("update public_creator_metric set category='BEAUTY' where creator_id in (?,?)", home.getId(), approved.getId());
    var manager = account(Role.DATA_MANAGER);
    jdbc.update("update public_creator_metric set quality_reviewed_at=now(),quality_reviewed_by=? where creator_id=?",
        manager.getId(), approved.getId());
    try (var in = getClass().getResourceAsStream("/db/migration/V57__align_observed_discovery_categories.sql")) {
      jdbc.execute(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
    }
    entityManager.clear();
    assertThat(metrics.findByCreatorId(home.getId()).orElseThrow().getCategory()).isEqualTo("HOME");
    assertThat(metrics.findByCreatorId(approved.getId()).orElseThrow().getCategory()).isEqualTo("BEAUTY");
  }

  @Test
  @org.springframework.transaction.annotation.Transactional
  void crawlerRateMigrationDoesNotDivideAlreadyRepairedRatiosAgain() throws Exception {
    var legacy = creator("Legacy percent", "FOOD", "Review food", "KOC");
    var repaired = creator("Already repaired", "FOOD", "Review food", "KOC");
    var incomplete = creator("Incomplete counts", "FOOD", "Review food", "KOC");
    var noFollowers = creator("Missing followers", "FOOD", "Review food", "KOC");
    entityManager.flush();
    for (var candidate : List.of(legacy, repaired, incomplete, noFollowers))
      jdbc.update("update public_creator_metric set followers=10000,avg_likes=300,avg_comments=40,avg_shares=10,"
          + "engagement_rate=3.5,crawl_run_id='migration-unit-test' where creator_id=?", candidate.getId());
    jdbc.update("update public_creator_metric set engagement_rate=0.035 where creator_id=?", repaired.getId());
    jdbc.update("update public_creator_metric set avg_comments=null where creator_id=?", incomplete.getId());
    jdbc.update("update public_creator_metric set followers=0 where creator_id=?", noFollowers.getId());
    String migration;
    try (var in = getClass().getResourceAsStream("/db/migration/V56__normalize_crawler_engagement_rate.sql")) {
      migration = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    }
    jdbc.execute(migration);
    jdbc.execute(migration);
    entityManager.clear();
    assertThat(metrics.findByCreatorId(legacy.getId()).orElseThrow().getEngagementRate()).isEqualByComparingTo("0.035");
    assertThat(metrics.findByCreatorId(repaired.getId()).orElseThrow().getEngagementRate()).isEqualByComparingTo("0.035");
    assertThat(metrics.findByCreatorId(incomplete.getId()).orElseThrow().getEngagementRate()).isEqualByComparingTo("3.5");
    assertThat(metrics.findByCreatorId(noFollowers.getId()).orElseThrow().getEngagementRate()).isEqualByComparingTo("3.5");
  }

  Creator creator(String name, String niche, String summary, String type) {
    var c = creators.saveAndFlush(Creator.builder().platform("TIKTOK")
        .location("fixture-" + campaign)
        .externalId(UUID.randomUUID().toString()).userName(name + UUID.randomUUID().toString().replace("-", ""))
        .displayName(name).build());
    fixtureCreatorIds.add(c.getId());
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

  @Test
  @org.springframework.transaction.annotation.Transactional
  void fullBriefChangesPreselectionWithinSameCategoryBeforeTop50AndAiReview() throws Exception {
    entityManager.flush();
    entityManager.clear();
    jdbc.update("update campaign set product_service='Travel',niches=array['TRAVEL'],brief='Trekking mountain hiking' where id=?", campaign);
    // The strongest candidate is inserted after more than one retrieval page of weak matches.
    for (int i = 0; i < 51; i++) creator("Generic travel " + i, "TRAVEL", "Travel lifestyle", "KOC");
    var resort = creator("Resort specialist", "TRAVEL", "Travel resort luxury hotel vacation", "KOC");
    var mountain = creator("Mountain specialist", "TRAVEL", "Travel trekking mountain hiking", "KOC");
    var hashtag = creator("Hashtag only", "TRAVEL", "Travel lifestyle", "KOC");
    for (int i = 0; i < 5; i++) {
      posts.saveAndFlush(Post.builder().creator(mountain).platformPostId(UUID.randomUUID().toString())
          .caption("Travel trekking mountain hiking guide").build());
      posts.saveAndFlush(Post.builder().creator(resort).platformPostId(UUID.randomUUID().toString())
          .caption("Travel resort luxury hotel vacation guide").build());
      posts.saveAndFlush(Post.builder().creator(hashtag).platformPostId(UUID.randomUUID().toString())
          .caption("Daily dance #trekking #mountain #hiking").build());
    }
    for (var route : List.of("discover", "discovery")) {
      var data = call(owner, get("/campaigns/" + campaign + "/" + route).param("size", "1"), null, 200).path("data");
      assertThat(ids(data)).containsExactly(mountain.getId().toString());
    }
    jdbc.update("update campaign set brief='Resort luxury hotel vacation' where id=?", campaign);
    entityManager.clear();
    for (var route : List.of("discover", "discovery")) {
      var data = call(owner, get("/campaigns/" + campaign + "/" + route).param("size", "1"), null, 200).path("data");
      assertThat(ids(data)).containsExactly(resort.getId().toString());
    }
    verify(ai, never()).getExplanation(any());
  }

  @Test void managerHiddenCreatorIsExcludedFromBothDiscoveryRoutesUntilRestored() throws Exception {
    var c = creator("Activity review", "BEAUTY", "Review " + topic, "KOC");
    var manager = account(Role.DATA_MANAGER);
    String visibility = "/data/creator-activity/" + c.getId() + "/visibility";
    call(manager, patch(visibility), Map.of("hidden", true, "reason", "Inactive creator"), 200);
    for (var route : List.of("discover", "discovery"))
      assertThat(ids(call(owner, get("/campaigns/" + campaign + "/" + route), null, 200).path("data")))
          .doesNotContain(c.getId().toString());
    call(manager, patch(visibility), Map.of("hidden", false, "reason", "Activity reviewed"), 200);
    for (var route : List.of("discover", "discovery"))
      assertThat(ids(call(owner, get("/campaigns/" + campaign + "/" + route), null, 200).path("data")))
          .contains(c.getId().toString());
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

  @Test void uiFiltersStayHardWhileSparseCampaignPreferencesCanExpand() throws Exception {
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
      assertThat(ids(data)).as(route).containsExactly(wanted.getId().toString(), wrongFollowers.getId().toString());
      var rows = data.has("content") ? data.path("content") : data.path("items");
      assertThat(rows.get(0).path("isSimilarFallback").asBoolean()).isFalse();
      assertThat(rows.get(1).path("isSimilarFallback").asBoolean()).isTrue();
      assertThat(rows.get(1).path("expansionReasons").toString()).contains("FOLLOWERS");
      data = call(owner, get("/campaigns/" + campaign + "/" + route)
          .param("niche", "PETS"), null, 200).path("data");
      assertThat(ids(data)).as(route).isEmpty();
    }
  }

  @Test void sunscreenProductNarrowsBroadBeautyCategoryToSkincare() throws Exception {
    jdbc.update("update campaign set product_service=?,name=? where id=?",
        "Kem chống nắng nâng tone kiềm dầu HSSV", "Sunscreen launch " + topic, campaign);
    var skincare = creator("Skincare " + topic, "Chăm sóc da", "Đánh giá sản phẩm dưỡng da", "KOC");
    var salon = creator("Salon " + topic, "Chăm sóc và tạo mẫu tóc", "Kỹ thuật cắt tỉa và nhuộm tóc", "KOC");
    var home = creator("Home " + topic, "Đánh giá đồ gia dụng", "Review đồ dùng thông minh", "KOC");
    var personal = creator("Personal " + topic, "Chăm sóc cá nhân", "Trải nghiệm du lịch và trekking", "KOC");
    for (var c : List.of(skincare, salon, home, personal))
      jdbc.update("update public_creator_metric set category='BEAUTY' where creator_id=?", c.getId());
    for (var niche : List.of("BEAUTY", "Làm đẹp & Mỹ phẩm")) {
      jdbc.update("update campaign set niches=array[?]::text[] where id=?", niche, campaign);
      for (var route : List.of("discover", "discovery")) {
        var data = call(owner, get("/campaigns/" + campaign + "/" + route), null, 200).path("data");
        assertThat(ids(data)).as(route + ": " + niche).containsExactly(skincare.getId().toString());
      }
    }
  }

  @Test void postEvidenceRecoversSparseBackupProfilesAndFillsTop50WithoutUnrelatedCreators() throws Exception {
    jdbc.update("update campaign set product_service=?,name=?,niches=array['BEAUTY'] where id=?",
        "Kem chống nắng nâng tone kiềm dầu cho học sinh sinh viên giá dưới 200k", "Sunscreen launch", campaign);
    var expected = new HashSet<String>();
    for (int i = 0; i < 52; i++) {
      var candidate = creator("Post skincare " + i, "UNKNOWN", "Creator", "KOC");
      jdbc.update("update public_creator_metric set category='BEAUTY' where creator_id=?", candidate.getId());
      posts.saveAndFlush(Post.builder().creator(candidate).platformPostId(UUID.randomUUID().toString())
          .caption("Review kem chống nắng dùng hằng ngày cho da dầu").build());
      expected.add(candidate.getId().toString());
    }
    var unrelated = creator("Salon", "Chăm sóc tóc", "Creator", "KOC");
    jdbc.update("update public_creator_metric set category='BEAUTY' where creator_id=?", unrelated.getId());
    posts.saveAndFlush(Post.builder().creator(unrelated).platformPostId(UUID.randomUUID().toString())
        .caption("Hướng dẫn cắt tóc và uốn tóc").build());
    for (var route : List.of("discover", "discovery")) {
      var data = call(owner, get("/campaigns/" + campaign + "/" + route).param("size", "50"), null, 200).path("data");
      assertThat(ids(data)).as(route).hasSize(50).doesNotHaveDuplicates()
          .isSubsetOf(expected).doesNotContain(unrelated.getId().toString());
    }
  }

  @Test void keywordFallbackKeepsContextAndTop50WhenEmbeddingUnavailable() throws Exception {
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
        assertThat(card.path("discoveryPolicyVersion").asText()).isEqualTo("progressive-v4");
        assertThat(card.path("finalScore").isNull() || card.path("finalScore").isMissingNode()).isTrue();
      }
    }
    verify(ai, never()).getExplanation(any());
  }

  @Test void nationwideAndCityAliasesApplyOnBothDiscoveryEndpoints() throws Exception {
    var hcm = creator("HCM reviewer", "BEAUTY", topic, "KOC");
    var hanoi = creator("Hanoi reviewer", "BEAUTY", topic, "KOC");
    jdbc.update("update creator set location='TP.HCM' where id=?", hcm.getId());
    jdbc.update("update creator set location='Hà Nội' where id=?", hanoi.getId());
    for (String location : List.of("Thành phố Hồ Chí Minh", "Toàn quốc", "Vietnam")) {
      jdbc.update("update campaign set locations=array[?]::text[] where id=?", location, campaign);
      for (var route : List.of("discover", "discovery")) {
        var data = call(owner, get("/campaigns/" + campaign + "/" + route), null, 200).path("data");
        if (location.equals("Thành phố Hồ Chí Minh")) {
          assertThat(ids(data)).containsExactly(hcm.getId().toString(), hanoi.getId().toString());
          var rows = data.has("content") ? data.path("content") : data.path("items");
          assertThat(rows.get(1).path("expansionReasons").toString()).contains("LOCATION");
        } else
          assertThat(ids(data)).containsExactlyInAnyOrder(hcm.getId().toString(), hanoi.getId().toString());
      }
    }
  }

  @Test void vectorRecoversBroadBeautyProfilesWithoutExactProductWords() throws Exception {
    jdbc.update("update campaign set product_service='kem chống nắng',niches=array['BEAUTY'] where id=?", campaign);
    var candidate = creator("Beauty reviewer", "UNKNOWN", "Trải nghiệm mỹ phẩm hằng ngày", "KOC");
    var vector = Collections.nCopies(768, 0.1f);
    when(ai.getEmbedding(anyString())).thenReturn(vector);
    jdbc.update("update public_creator_metric set category='BEAUTY' where creator_id=?", candidate.getId());
    jdbc.update("update creator set embedding=cast(? as vector) where id=?", vector.toString(), candidate.getId());
    var wrongPlatform = creator("Wrong platform", "BEAUTY", "Mỹ phẩm", "KOC");
    jdbc.update("update creator set platform='YOUTUBE',embedding=cast(? as vector) where id=?", vector.toString(), wrongPlatform.getId());
    jdbc.update("update campaign set platforms=array['TIKTOK'] where id=?", campaign);
    for (var route : List.of("discover", "discovery")) {
      var data = call(owner, get("/campaigns/" + campaign + "/" + route), null, 200).path("data");
      assertThat(ids(data)).containsExactly(candidate.getId().toString());
    }
  }

  @Test void vectorRanksOnlyContextEligibleProfilesBeforeTakingTop50() throws Exception {
    var query = new ArrayList<>(Collections.nCopies(768, 0.0f));
    query.set(0, 1.0f);
    var unrelatedVector = new ArrayList<>(Collections.nCopies(768, 0.0f));
    unrelatedVector.set(1, 1.0f);
    when(ai.getEmbedding(anyString())).thenReturn(query);
    jdbc.update("update campaign set product_service=?,niches=array['BEAUTY'] where id=?",
        "kem chống nắng", campaign);
    for (int i = 0; i < 51; i++) {
      var candidate = creator("Skincare" + i, "Chăm sóc da", "Review dưỡng da", "KOC");
      jdbc.update("update public_creator_metric set category='BEAUTY',followers=500000 where creator_id=?", candidate.getId());
      jdbc.update("update creator set embedding=cast(? as vector) where id=?",
          (i == 0 ? Collections.nCopies(768, 0.0f) : unrelatedVector).toString(), candidate.getId());
    }
    var best = creator("Best skincare", "Chăm sóc da", "Review dưỡng da", "KOC");
    var salon = creator("Salon", "Chăm sóc và tạo mẫu tóc", "Kỹ thuật nhuộm tóc", "KOC");
    var home = creator("Home", "Đánh giá đồ gia dụng", "Review đồ dùng nhà bếp", "KOC");
    for (var c : List.of(best, salon, home)) {
      jdbc.update("update creator set embedding=cast(? as vector) where id=?", query.toString(), c.getId());
      jdbc.update("update public_creator_metric set category='BEAUTY' where creator_id=?", c.getId());
    }
    for (var route : List.of("discover", "discovery")) {
      var data = call(owner, get("/campaigns/" + campaign + "/" + route).param("size", "50"), null, 200).path("data");
      assertThat(ids(data).getFirst()).as(route).isEqualTo(best.getId().toString());
      assertThat(ids(data)).doesNotContain(salon.getId().toString(), home.getId().toString());
      assertThat(data.path("totalElements").asInt()).isEqualTo(50);
      if (route.equals("discovery")) {
        var card = data.path("content").get(0);
        assertThat(card.path("retrievalMethod").asText()).isEqualTo("VECTOR_HYBRID");
        assertThat(card.path("retrievalSimilarity").asDouble()).isCloseTo(1.0, within(0.00001));
        assertThat(card.path("aiScoreAvailable").asBoolean()).isFalse();
        assertThat(card.hasNonNull("finalScore")).isFalse();
      }
    }
    verify(ai, times(2)).getEmbedding(anyString());
    verify(ai, never()).getExplanation(any());
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
    verify(ai, never()).getExplanation(any());
    jdbc.update("update campaign set niches=array['BEAUTY'] where id=?", campaign);
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
    data = call(owner, post("/campaigns/" + campaign + "/ai-recommendations")
        .header("Idempotency-Key", UUID.randomUUID().toString()), null, 200).path("data");
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
    jdbc.update("update campaign set niches=array['BEAUTY'] where id=?", campaign);
    call(owner, patch("/campaigns/" + campaign + "/status"),
        Map.of("status", "READY_FOR_DISCOVERY"), 200);
    var error = call(owner, post(url).header("Idempotency-Key", UUID.randomUUID().toString()), null, 409);
    assertThat(error.path("code").asText()).isEqualTo("RECOMMENDATION_NO_CANDIDATES");
    call(owner, post(url).param("candidateSource", "DISCOVERY")
        .header("Idempotency-Key", UUID.randomUUID().toString()), null, 400);
    verifyNoInteractions(ai);
    assertThat(jdbc.queryForObject("select count(*) from recommendation_run where campaign_id=?",
        Integer.class, campaign)).isZero();
    assertThat(jdbc.queryForObject("select sum(used_ai_quota) from subscription where brand_profile_id="
        + "(select brand_profile_id from campaign where id=?)", Long.class, campaign)).isZero();
  }

  @Test void publicOpenApiReportsBuildRevisionAndCurrentDiscoveryContract() throws Exception {
    var spec = call(null, get("/api-docs"), null, 200);
    var expectedCommit = System.getenv("APP_GIT_COMMIT");
    assertThat(spec.path("info").path("x-build-commit").asText())
        .isEqualTo(expectedCommit == null ? "local" : expectedCommit);
    var parameters = new ArrayList<String>();
    spec.path("paths").path("/campaigns/{campaignId}/discovery").path("get").path("parameters")
        .forEach(parameter -> parameters.add(parameter.path("name").asText()));
    assertThat(parameters).contains("platform", "sortBy", "minEr", "isClaimed");
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

  @Test void latestStaleSnapshotRemainsDiscoverableAndRejectedSnapshotCannotResurrectOldProfile() throws Exception {
    var accepted = creator("Accepted", "BEAUTY", topic, "KOC");
    var rejected = creator("Rejected", "BEAUTY", topic, "KOC");
    snapshot(rejected, "BEAUTY", topic, "REJECTED", LocalDateTime.now().plusMinutes(1), MetricDataStatus.FRESH);
    var stale = creator("Stale", "BEAUTY", topic, "KOC");
    snapshot(stale, "BEAUTY", topic, "KOC", LocalDateTime.now().plusMinutes(1), MetricDataStatus.STALE);
    for (var route : List.of("discover", "discovery")) {
      var data = call(owner, get("/campaigns/" + campaign + "/" + route), null, 200).path("data");
      assertThat(ids(data)).as(route).containsExactlyInAnyOrder(accepted.getId().toString(), stale.getId().toString());
      var rows = data.has("content") ? data.path("content") : data.path("items");
      for (var row : rows) if (row.path("creatorId").asText().equals(stale.getId().toString())) {
        assertThat(row.path("freshnessStatus").asText()).isEqualTo("STALE");
        assertThat(row.path("collectedAt").isTextual()).isTrue();
      }
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
