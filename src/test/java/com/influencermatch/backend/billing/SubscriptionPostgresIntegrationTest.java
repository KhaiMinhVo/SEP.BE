package com.influencermatch.backend.billing;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import com.influencermatch.backend.support.PostgresApiTestSupport;
import com.influencermatch.backend.user.enums.Role;
import java.util.*;
import org.junit.jupiter.api.Test;

class SubscriptionPostgresIntegrationTest extends PostgresApiTestSupport {
  UUID plan(com.influencermatch.backend.user.model.User admin,int price) throws Exception {
    return UUID.fromString(call(admin,post("/plans"),Map.of("planName","Plan-"+UUID.randomUUID(),"price",price,"currency","VND",
        "durationDays",30,"campaignQuota",3,"recommendationQuota",5,"refreshQuota",2),201).path("data").path("id").asText());
  }
  @Test void freeSubscriptionOwnershipDuplicateAndCancellation() throws Exception {
    var admin=account(Role.ADMIN);var owner=account(Role.BRAND);var brand=createBrand(owner);var plan=plan(admin,0);
    var body=Map.of("brandId",brand,"planId",plan);var sub=call(owner,post("/subscriptions"),body,201).path("data");
    UUID id=UUID.fromString(sub.path("id").asText());assertThat(sub.path("status").asText()).isEqualTo("ACTIVE");
    call(owner,post("/subscriptions"),body,409);var other=account(Role.BRAND);
    call(other,get("/subscriptions/brand/"+brand),null,403);call(other,delete("/subscriptions/"+id),null,403);
    call(owner,delete("/subscriptions/"+id),null,200);call(owner,delete("/subscriptions/"+id),null,200);
    assertThat(jdbc.queryForObject("select status from subscription where id=?",String.class,id)).isEqualTo("CANCELLED");
    assertThat(jdbc.queryForObject("select count(*) from subscription where brand_profile_id=?",Long.class,brand)).isEqualTo(1);
  }
  @Test void paidPlanRequiresCheckoutButAdminCanGrantAndCancelWithAudit() throws Exception {
    var admin=account(Role.ADMIN);var owner=account(Role.BRAND);var brand=createBrand(owner);var plan=plan(admin,100);
    var body=Map.of("brandId",brand,"planId",plan);
    assertThat(call(owner,post("/subscriptions"),body,409).path("code").asText()).isEqualTo("PAID_PLAN_REQUIRES_PAYMENT");
    var id=UUID.fromString(call(admin,post("/admin/subscriptions"),body,200).path("data").path("id").asText());
    call(admin,delete("/admin/subscriptions/"+id),null,200);call(admin,delete("/admin/subscriptions/"+id),null,200);
    assertThat(jdbc.queryForObject("select count(*) from audit_log where entity_id=? and action='CREATE_SUBSCRIPTION'",Long.class,id)).isEqualTo(1);
    assertThat(jdbc.queryForObject("select count(*) from audit_log where entity_id=? and action='CANCEL_SUBSCRIPTION'",Long.class,id)).isEqualTo(1);
  }
  @Test void inactivePlanHiddenFromBrandAndCannotBeSubscribed() throws Exception {
    var admin=account(Role.ADMIN);var owner=account(Role.BRAND);var brand=createBrand(owner);var plan=plan(admin,0);
    call(admin,delete("/plans/"+plan),null,200);
    call(owner,get("/plans/"+plan),null,404);call(admin,get("/plans/"+plan),null,200);
    call(owner,post("/subscriptions"),Map.of("brandId",brand,"planId",plan),409);
    assertThat(jdbc.queryForObject("select count(*) from subscription where brand_profile_id=?",Long.class,brand)).isZero();
  }
}
