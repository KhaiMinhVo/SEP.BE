package com.influencermatch.backend.creator;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.influencermatch.backend.support.PostgresApiTestSupport;
import com.influencermatch.backend.user.enums.Role;
import com.influencermatch.backend.user.model.User;
import java.util.*;
import org.junit.jupiter.api.*;

class CreatorRefreshQuotaPostgresTest extends PostgresApiTestSupport {
  User owner;
  UUID campaign, brand, creator, subscription;

  @BeforeEach void setup() throws Exception {
    owner = account(Role.BRAND);
    brand = createBrand(owner);
    campaign = createCampaign(owner, brand);
    creator = channel();
    subscription = jdbc.queryForObject("select id from subscription where brand_profile_id=?", UUID.class, brand);
    jdbc.update("update subscription set purchased_refresh_quota=1,used_refresh_quota=0 where id=?", subscription);
  }

  UUID channel() {
    var id = UUID.randomUUID();
    jdbc.update("insert into creator(id,platform,external_id,user_name,display_name,created_at,updated_at) "
        + "values(?,'TIKTOK',?,?,'Refresh fixture',now(),now())", id, id.toString(), "refresh" + id);
    return id;
  }
  String url(UUID id) { return "/campaigns/" + campaign + "/creators/" + id + "/refresh"; }
  int used() { return jdbc.queryForObject("select used_refresh_quota from subscription where id=?", Integer.class, subscription); }

  @Test void explicitRefreshReservesOnePurchasedCreditAndRetriesDoNotChargeTwice() throws Exception {
    var key = UUID.randomUUID().toString();
    var first = call(owner, post(url(creator)).header("Idempotency-Key", key), null, 202).path("data");
    assertThat(first.path("status").asText()).isEqualTo("QUEUED");
    assertThat(first.path("quotaCharged").asBoolean()).isTrue();
    call(owner, post(url(creator)).header("Idempotency-Key", key), null, 202);
    // Even a fresh key while the same channel is pending reuses its request.
    call(owner, post(url(creator)).header("Idempotency-Key", UUID.randomUUID()), null, 202);
    assertThat(used()).isEqualTo(1);
    verify(crawler, times(1)).requestShortlistCrawl(anyString(), anyString(), eq(creator), eq(campaign));
    var other = channel();
    var rejected = call(owner, post(url(other)).header("Idempotency-Key", UUID.randomUUID()), null, 409);
    assertThat(rejected.path("code").asText()).isEqualTo("QUOTA_EXCEEDED");
    verify(crawler, never()).requestShortlistCrawl(anyString(), anyString(), eq(other), any());
  }

  @Test void failedDispatchReturnsCreditAndDoesNotPretendDataWasUpdated() throws Exception {
    doThrow(new IllegalStateException("private provider failure")).when(crawler)
        .requestShortlistCrawl(anyString(), anyString(), eq(creator), any());
    var key = UUID.randomUUID();
    var response = call(owner, post(url(creator)).header("Idempotency-Key", key), null, 503);
    assertThat(response.toString()).doesNotContain("private provider failure");
    assertThat(used()).isZero();
    assertThat(jdbc.queryForObject("select status from creator_refresh_request where id=?", String.class, key))
        .isEqualTo("DISPATCH_FAILED");
    call(owner, post(url(creator)).header("Idempotency-Key", key), null, 503);
    assertThat(used()).isZero();
  }

  @Test void exhaustedCreditDoesNotPreventSavingStoredProfiles() throws Exception {
    jdbc.update("update subscription set purchased_refresh_quota=0 where id=?", subscription);
    var saved = call(owner, post("/campaigns/" + campaign + "/shortlist"), Map.of("creatorId", creator), 200).path("data");
    assertThat(saved.path("refreshRequestStatus").asText()).isEqualTo("NOT_REQUESTED");
    assertThat(used()).isZero();
    verify(crawler, after(200).never()).requestShortlistCrawl(anyString(), anyString(), eq(creator), any());
    call(owner, post(url(creator)).header("Idempotency-Key", UUID.randomUUID()), null, 409);
  }

  @Test void anotherBrandAndUnsupportedPlatformCannotSpendCredit() throws Exception {
    var stranger = account(Role.BRAND);
    call(stranger, post(url(creator)).header("Idempotency-Key", UUID.randomUUID()), null, 404);
    jdbc.update("update creator set platform='YOUTUBE' where id=?", creator);
    call(owner, post(url(creator)).header("Idempotency-Key", UUID.randomUUID()), null, 400);
    assertThat(used()).isZero();
    verifyNoInteractions(crawler);
  }
}
