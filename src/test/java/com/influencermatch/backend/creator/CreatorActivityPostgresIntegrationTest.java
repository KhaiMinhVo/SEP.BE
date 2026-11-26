package com.influencermatch.backend.creator;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.influencermatch.backend.creator.enums.MetricDataStatus;
import com.influencermatch.backend.creator.model.*;
import com.influencermatch.backend.creator.repository.*;
import com.influencermatch.backend.support.PostgresApiTestSupport;
import com.influencermatch.backend.user.enums.Role;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class CreatorActivityPostgresIntegrationTest extends PostgresApiTestSupport {
  @Autowired CreatorRepository creators;
  @Autowired PostRepository posts;
  @Autowired PublicCreatorMetricRepository metrics;

  Creator fixture(String date, MetricDataStatus freshness, int ageDays) {
    var c = creators.saveAndFlush(Creator.builder().platform("TIKTOK")
        .userName("activity-" + UUID.randomUUID()).externalId(UUID.randomUUID().toString()).build());
    metrics.saveAndFlush(PublicCreatorMetric.builder().creator(c).creatorType("KOC")
        .niche("Beauty").freshnessStatus(freshness)
        .collectedAt(LocalDateTime.now(ZoneOffset.UTC).minusDays(ageDays)).build());
    if (date != null) posts.saveAndFlush(Post.builder().creator(c)
        .platformPostId(UUID.randomUUID().toString()).postedAt(date).build());
    return c;
  }

  Map<String, String> allActivity(com.influencermatch.backend.user.model.User manager, String query) throws Exception {
    var result = new HashMap<String, String>();
    int page = 0;
    int totalPages = 1;
    while (page < totalPages) {
      var data = call(manager, get("/data/creator-activity?size=100&page=" + page + (query == null ? "" : "&" + query)), null, 200).path("data");
      totalPages = Math.max(1, data.path("totalPages").asInt(1));
      data.path("items").forEach(i -> result.put(i.path("creatorId").asText(), i.path("status").asText()));
      page++;
    }
    return result;
  }

  @Test void assessesInactivityAndTreatsMissingStaleFailedAndMalformedEvidenceAsUnknown() throws Exception {
    var manager = account(Role.DATA_MANAGER);
    var old = Instant.now().minusSeconds(45 * 86400L).toString();
    var inactive = fixture(old, MetricDataStatus.FRESH, 0);
    var active = fixture(Instant.now().minusSeconds(86400).toString(), MetricDataStatus.FRESH, 0);
    var stale = fixture(old, MetricDataStatus.FRESH, 8);
    var missing = fixture(null, MetricDataStatus.FRESH, 0);
    var invalid = fixture("2026-99-99", MetricDataStatus.FRESH, 0);
    var future = fixture(Instant.now().plusSeconds(86400).toString(), MetricDataStatus.FRESH, 0);
    var failed = fixture(old, MetricDataStatus.REFRESH_FAILED, 0);
    var statuses = allActivity(manager, null);
    assertThat(statuses.get(inactive.getId().toString())).isEqualTo("INACTIVE");
    assertThat(statuses.get(active.getId().toString())).isEqualTo("ACTIVE");
    for (var c : List.of(stale, missing, invalid, future, failed))
      assertThat(statuses.get(c.getId().toString())).isEqualTo("UNKNOWN");
    assertThat(allActivity(manager, "status=INACTIVE").keySet()).contains(inactive.getId().toString());
  }

  @Test void latestFailedRefreshWinsAndInvalidNewerPostPreventsFalseWarning() throws Exception {
    var manager = account(Role.DATA_MANAGER);
    var old = Instant.now().minusSeconds(40 * 86400L).toString();
    var c = fixture(old, MetricDataStatus.FRESH, 1);
    metrics.saveAndFlush(PublicCreatorMetric.builder().creator(c).creatorType("KOC")
        .freshnessStatus(MetricDataStatus.REFRESH_FAILED).collectedAt(LocalDateTime.now(ZoneOffset.UTC)).build());
    var invalid = fixture(old, MetricDataStatus.FRESH, 0);
    posts.saveAndFlush(Post.builder().creator(invalid).platformPostId("invalid").postedAt("yesterday").build());
    assertThat(allActivity(manager, "status=UNKNOWN").keySet()).contains(c.getId().toString(), invalid.getId().toString());
  }

  @Test void onlyDataManagerCanListOrChangeVisibility() throws Exception {
    var c = fixture(null, MetricDataStatus.FRESH, 0);
    for (var role : List.of(Role.ADMIN, Role.BRAND, Role.CREATOR)) {
      var user = account(role);
      call(user, get("/data/creator-activity"), null, 403);
      call(user, patch("/data/creator-activity/" + c.getId() + "/visibility"),
          Map.of("hidden", true, "reason", "Inactive"), 403);
    }
    call(null, get("/data/creator-activity"), null, 401);
  }

  @Test void hideAndRestorePersistWithAuditAndExcludeFromBrandListingAndVectorRetrieval() throws Exception {
    var manager = account(Role.DATA_MANAGER);
    var brand = account(Role.BRAND);
    var c = fixture(Instant.now().minusSeconds(40 * 86400L).toString(), MetricDataStatus.FRESH, 0);
    var campaign = createCampaign(brand, createBrand(brand));
    jdbc.update("INSERT INTO shortlist_item(id,campaign_id,creator_id,user_id,priority,added_at) VALUES (?,?,?,?,?,?)",
        UUID.randomUUID(), campaign, c.getId(), brand.getId(), "MEDIUM", LocalDateTime.now());
    var vector = new ArrayList<Float>(Collections.nCopies(768, 0f));
    vector.set(0, 1f);
    jdbc.update("UPDATE creator SET embedding=CAST(? AS vector) WHERE id=?", vector.toString(), c.getId());
    String url = "/data/creator-activity/" + c.getId() + "/visibility";
    call(manager, patch(url), Map.of("hidden", true, "reason", "No recent posts"), 200);
    assertThat(creators.findById(c.getId()).orElseThrow().isDiscoveryHidden()).isTrue();
    assertThat(metrics.findCandidatesByShortlist(campaign, vector.toString(), false, List.of(UUID.randomUUID())))
        .extracting(p -> p.getCreatorId()).doesNotContain(c.getId());
    assertThat(creators.searchSimilarCreators(vector.toString(), 50)).extracting(p -> p.getId()).doesNotContain(c.getId());
    assertThat(call(brand, get("/creators"), null, 200).path("data").findValuesAsText("creatorId"))
        .doesNotContain(c.getId().toString());
    call(manager, patch(url), Map.of("hidden", true, "reason", "Repeated"), 200);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM creator_visibility_audit WHERE creator_id=?", Long.class, c.getId())).isEqualTo(1L);
    call(manager, patch(url), Map.of("hidden", false, "reason", "Reviewed new activity"), 200);
    assertThat(creators.searchSimilarCreators(vector.toString(), 50)).extracting(p -> p.getId()).contains(c.getId());
    assertThat(metrics.findCandidatesByShortlist(campaign, vector.toString(), false, List.of(UUID.randomUUID())))
        .extracting(p -> p.getCreatorId()).contains(c.getId());
    assertThat(posts.findByCreatorId(c.getId())).hasSize(1);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM creator_visibility_audit WHERE creator_id=? AND actor_id=?", Long.class, c.getId(), manager.getId())).isEqualTo(2L);
  }

  @Test void validatesRequestsAndParsesSupportedCrawlerDates() throws Exception {
    var manager = account(Role.DATA_MANAGER);
    call(manager, get("/data/creator-activity?size=101"), null, 400);
    call(manager, get("/data/creator-activity?page=-1"), null, 400);
    call(manager, patch("/data/creator-activity/" + UUID.randomUUID() + "/visibility"),
        Map.of("hidden", true, "reason", "Checked"), 404);
    call(manager, patch("/data/creator-activity/" + UUID.randomUUID() + "/visibility"),
        Map.of("hidden", true, "reason", " "), 400);
    for (var value : List.of("1704067200", "1704067200000", "2024-01-01", "2024-01-01T07:00:00+07:00"))
      assertThat(jdbc.queryForObject("SELECT im_post_timestamp(?)", java.sql.Timestamp.class, value)
          .toLocalDateTime()).isEqualTo(LocalDateTime.of(2024, 1, 1, 0, 0));
    assertThat(jdbc.queryForObject("SELECT im_post_timestamp(?)", java.sql.Timestamp.class, "nonsense")).isNull();
  }
}
