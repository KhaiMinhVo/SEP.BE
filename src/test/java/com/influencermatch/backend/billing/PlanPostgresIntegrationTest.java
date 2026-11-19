package com.influencermatch.backend.billing;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import com.influencermatch.backend.support.PostgresApiTestSupport;
import com.influencermatch.backend.user.enums.Role;
import com.influencermatch.backend.user.model.User;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;

class PlanPostgresIntegrationTest extends PostgresApiTestSupport {
  @Autowired com.influencermatch.backend.billing.service.PlanService plans;
  @Autowired com.influencermatch.backend.billing.service.SubscriptionService subscriptions;
  Map<String, Object> body(String name) {
    return new HashMap<>(Map.of("planName", name, "price", 100000, "currency", "VND",
        "durationDays", 30, "campaignQuota", 3, "recommendationQuota", 5, "refreshQuota", 2));
  }
  UUID plan(User admin) throws Exception {
    return UUID.fromString(call(admin, post("/plans"), body("plan-" + UUID.randomUUID()), 201).path("data").path("id").asText());
  }
  @Test void onlyManagePlanCanMutateAndBrandOnlySeesActiveCatalog() throws Exception {
    var admin = account(Role.ADMIN); var brand = account(Role.BRAND); var dataManager = account(Role.DATA_MANAGER);
    var id = plan(admin);
    for (var user : List.of(brand, dataManager)) {
      call(user, post("/plans"), body("forbidden"), 403);
      call(user, put("/plans/" + id), Map.of("price", 500), 403);
      call(user, delete("/plans/" + id), null, 403);
    }
    assertThat(call(brand, get("/plans/" + id), null, 200).path("data").has("activeSubscriptionCount")).isFalse();
    call(admin, delete("/plans/" + id), null, 200);
    call(brand, get("/plans/" + id), null, 404);
    assertThat(call(admin, get("/plans/" + id), null, 200).path("data").path("status").asText()).isEqualTo("INACTIVE");
    var visible = call(brand, get("/plans").param("size", "100"), null, 200).path("data").path("content");
    for (var item : visible) assertThat(item.path("id").asText()).isNotEqualTo(id.toString());
  }
  @ParameterizedTest @CsvSource({
      "price,-1", "price,1.5", "price,92233720368547759", "currency,USD",
      "durationDays,0", "durationDays,3651", "campaignQuota,-1", "recommendationQuota,-1", "refreshQuota,-1"})
  void rejectsInvalidCreateAndUpdate(String field, String value) throws Exception {
    var admin = account(Role.ADMIN); var id = plan(admin); var body = body("invalid-" + UUID.randomUUID());
    Object invalid = field.equals("currency") ? value : new java.math.BigDecimal(value);
    body.put(field, invalid); call(admin, post("/plans"), body, 400);
    call(admin, put("/plans/" + id), Map.of(field, invalid), 400);
  }
  @Test void nameValidationDuplicateNamesAndInvalidSort() throws Exception {
    var admin = account(Role.ADMIN); String name = "unique-" + UUID.randomUUID();
    var request = body("  " + name + "  ");
    var result = call(admin, post("/plans"), request, 201).path("data");
    UUID id = UUID.fromString(result.path("id").asText());
    assertThat(result.path("planName").asText()).isEqualTo(name);
    call(admin, post("/plans"), body(name.toUpperCase(java.util.Locale.ROOT)), 409);
    UUID other = plan(admin);
    call(admin, put("/plans/" + other), Map.of("planName", name), 409);
    for (String invalid : List.of("", "   ", "\u2003", "x".repeat(101))) {
      call(admin, post("/plans"), body(invalid), 400);
      call(admin, put("/plans/" + id), Map.of("planName", invalid), 400);
    }
    call(admin, get("/plans").param("sortBy", "doesNotExist"), null, 400);
    request.put("planName", "long-description-" + UUID.randomUUID()); request.put("description", "x".repeat(2001));
    call(admin, post("/plans"), request, 400);
  }
  @Test void editAndDeactivatePreservePurchasedTermsAndQuotaAndAuditOnlyChanges() throws Exception {
    var admin = account(Role.ADMIN); var owner = account(Role.BRAND); UUID brand = createBrand(owner), id = plan(admin);
    UUID subscription = UUID.fromString(call(admin, post("/admin/subscriptions"), Map.of("brandId", brand, "planId", id), 200)
        .path("data").path("id").asText());
    assertThat(call(admin, get("/plans/" + id), null, 200).path("data").path("activeSubscriptionCount").asInt()).isEqualTo(1);
    call(admin, put("/plans/" + id), Map.of("price", 200000, "durationDays", 1, "campaignQuota", 0,
        "recommendationQuota", 0, "refreshQuota", 0, "planName", "edited-" + UUID.randomUUID()), 200);
    call(admin, put("/plans/" + id), Map.of(), 200);
    call(admin, delete("/plans/" + id), null, 200); call(admin, delete("/plans/" + id), null, 200);
    var contract = call(owner, get("/subscriptions/brand/" + brand), null, 200).path("data").get(0).path("plan");
    assertThat(contract.path("price").decimalValue()).isEqualByComparingTo("100000");
    assertThat(contract.path("durationDays").asInt()).isEqualTo(30);
    assertThat(contract.path("campaignQuota").asInt()).isEqualTo(3);
    assertThat(contract.path("recommendationQuota").asInt()).isEqualTo(5);
    for (int i=0; i<3; i++) subscriptions.checkAndDeductCampaignQuota(brand);
    assertThatThrownBy(() -> subscriptions.checkAndDeductCampaignQuota(brand)).isInstanceOf(com.influencermatch.backend.exception.ConflictException.class);
    subscriptions.reserveRecommendationQuota(brand);
    assertThat(jdbc.queryForObject("select status from subscription where id=?", String.class, subscription)).isEqualTo("ACTIVE");
    call(owner, post("/billing/subscribe"), Map.of("planId", id), 400);
    for (String action : List.of("CREATE_PLAN", "UPDATE_PLAN", "DEACTIVATE_PLAN"))
      assertThat(jdbc.queryForObject("select count(*) from audit_log where entity_id=? and action=?", Long.class, id, action)).isEqualTo(1);
  }
  @Test void activeCountsExcludeExpiredCancelledAndPendingSubscriptions() throws Exception {
    var admin = account(Role.ADMIN); var owner = account(Role.BRAND); var brand = createBrand(owner); UUID id = plan(admin);
    UUID subscription = UUID.fromString(call(admin, post("/admin/subscriptions"), Map.of("brandId", brand, "planId", id), 200)
        .path("data").path("id").asText());
    jdbc.update("update subscription set expiration_date=current_date-1 where id=?", subscription);
    assertThat(call(admin, get("/plans/" + id), null, 200).path("data").path("activeSubscriptionCount").asInt()).isZero();
    for (String state : List.of("CANCELLED", "PENDING")) {
      jdbc.update("update subscription set status=?,expiration_date=current_date+30 where id=?", state, subscription);
      assertThat(call(admin, get("/plans/" + id), null, 200).path("data").path("activeSubscriptionCount").asInt()).isZero();
    }
  }
  @Test void concurrentCaseVariantsCannotCreateDuplicateNames() throws Exception {
    var admin = account(Role.ADMIN); String name = "race-" + UUID.randomUUID();
    var start = new java.util.concurrent.CountDownLatch(1);
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      java.util.function.Function<String, java.util.concurrent.Callable<Boolean>> task = planName -> () -> {
        com.influencermatch.backend.support.TestActors.authenticate(admin);
        try {
          start.await();
          var request = com.influencermatch.backend.billing.dto.PlanDTOs.PlanCreateRequest.builder()
              .planName(planName).price(java.math.BigDecimal.ONE).currency("VND").durationDays(30).build();
          plans.createPlan(request); return true;
        } catch (com.influencermatch.backend.exception.ConflictException expected) { return false; }
        finally { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }
      };
      var a = pool.submit(task.apply(name)); var b = pool.submit(task.apply(name.toUpperCase(java.util.Locale.ROOT))); start.countDown();
      assertThat(List.of(a.get(20, java.util.concurrent.TimeUnit.SECONDS), b.get(20, java.util.concurrent.TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(true, false);
    }
    assertThat(jdbc.queryForObject("select count(*) from plan where lower(plan_name)=lower(?)", Long.class, name)).isEqualTo(1);
  }
}
