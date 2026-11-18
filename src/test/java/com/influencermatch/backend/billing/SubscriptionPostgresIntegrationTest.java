package com.influencermatch.backend.billing;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import com.influencermatch.backend.support.PostgresApiTestSupport;
import com.influencermatch.backend.user.enums.Role;
import java.util.*;
import org.junit.jupiter.api.Test;

class SubscriptionPostgresIntegrationTest extends PostgresApiTestSupport {
  @org.springframework.beans.factory.annotation.Autowired
  com.influencermatch.backend.billing.service.SubscriptionService subscriptions;

  @org.springframework.beans.factory.annotation.Autowired
  com.influencermatch.backend.config.VnPayConfig vnPay;

  @org.springframework.boot.test.mock.mockito.MockBean
  com.influencermatch.backend.billing.service.VnPayQueryClient queryClient;
  @org.springframework.beans.factory.annotation.Autowired
  com.influencermatch.backend.billing.repository.PaymentRepository paymentRepository;
  @org.springframework.beans.factory.annotation.Autowired
  com.influencermatch.backend.billing.service.PaymentSettlementService settlement;
  @org.springframework.beans.factory.annotation.Autowired
  org.springframework.transaction.PlatformTransactionManager transactions;

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
    assertThat(jdbc.queryForObject("select status from subscription where id=?",String.class,id)).isEqualTo("ACTIVE");
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
  @Test void catalogEditsDoNotChangeExistingContractOrQuota() throws Exception {
    var admin=account(Role.ADMIN); var owner=account(Role.BRAND); var brand=createBrand(owner); var plan=plan(admin,0);
    var sub=call(owner,post("/subscriptions"),Map.of("brandId",brand,"planId",plan),201).path("data");
    var id=UUID.fromString(sub.path("id").asText());
    call(admin,put("/plans/"+plan),Map.of("price",10000,"durationDays",1,"campaignQuota",0,
        "recommendationQuota",0,"refreshQuota",0,"planName","Edited-"+UUID.randomUUID()),200);
    var contract=call(owner,get("/subscriptions/brand/"+brand),null,200).path("data").get(0).path("plan");
    assertThat(contract.path("price").decimalValue()).isEqualByComparingTo("0");
    assertThat(contract.path("durationDays").asInt()).isEqualTo(30);
    assertThat(contract.path("campaignQuota").asInt()).isEqualTo(3);
    assertThat(contract.path("recommendationQuota").asInt()).isEqualTo(5);
    for(int i=0;i<3;i++) subscriptions.checkAndDeductCampaignQuota(brand);
    assertThatThrownBy(() -> subscriptions.checkAndDeductCampaignQuota(brand))
        .isInstanceOf(com.influencermatch.backend.exception.ConflictException.class);
    for(int i=0;i<5;i++) subscriptions.reserveRecommendationQuota(brand);
    assertThatThrownBy(() -> subscriptions.reserveRecommendationQuota(brand))
        .isInstanceOf(com.influencermatch.backend.exception.ConflictException.class);
  }
  @Test void olderCheckoutStillSettlesAfterAnotherCheckoutAndCatalogEdit() throws Exception {
    var admin=account(Role.ADMIN); var owner=account(Role.BRAND); createBrand(owner); var plan=plan(admin,10000);
    var first=call(owner,post("/billing/subscribe"),Map.of("planId",plan),200).path("data");
    String ref=first.path("transactionCode").asText();
    call(owner,post("/billing/subscribe"),Map.of("planId",plan),200);
    call(admin,put("/plans/"+plan),Map.of("price",20000,"durationDays",1,"campaignQuota",0),200);
    Map<String,String> fields=new HashMap<>();
    fields.put("vnp_TmnCode",vnPay.getTmnCode()); fields.put("vnp_TxnRef",ref);
    fields.put("vnp_Amount","1000000"); fields.put("vnp_ResponseCode","00"); fields.put("vnp_TransactionStatus","00");
    fields.put("vnp_SecureHash",com.influencermatch.backend.billing.utils.VnPayUtil.hashAllFields(fields,vnPay.getHashSecret()));
    var callback=get("/billing/vnpay/ipn"); fields.forEach(callback::param);
    mvc.perform(callback).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.RspCode").value("00"));
    var id=jdbc.queryForObject("select subscription_id from payment where transaction_code=?",UUID.class,ref);
    assertThat(jdbc.queryForObject("select status from subscription where id=?",String.class,id)).isEqualTo("ACTIVE");
    assertThat(jdbc.queryForObject("select expiration_date-start_date from subscription where id=?",Integer.class,id)).isEqualTo(30);
    assertThat(jdbc.queryForObject("select purchased_campaign_quota from subscription where id=?",Integer.class,id)).isEqualTo(3);
    mvc.perform(callback).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.RspCode").value("00"));
  }
  @Test void queryRecoversPaymentWhenNoCallbackArrives() throws Exception {
    var admin=account(Role.ADMIN); var owner=account(Role.BRAND); createBrand(owner); var plan=plan(admin,10000);
    String ref=call(owner,post("/billing/subscribe"),Map.of("planId",plan),200).path("data").path("transactionCode").asText();
    new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(status -> {
      var payment=paymentRepository.findByTransactionCode(ref).orElseThrow();
      payment.setCreatedAt(java.time.LocalDateTime.now().minusMinutes(20));
      paymentRepository.save(payment);
    });
    org.mockito.Mockito.when(queryClient.query(org.mockito.ArgumentMatchers.eq(ref),org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(Map.of("vnp_Amount","1000000","vnp_TransactionStatus","00"));
    var config=new com.influencermatch.backend.config.VnPayConfig();
    config.setHashSecret("test-secret");
    new com.influencermatch.backend.billing.service.PaymentReconciliationJob(
        paymentRepository,settlement,queryClient,config,transactions).reconcile();
    assertThat(jdbc.queryForObject("select status from payment where transaction_code=?",String.class,ref)).isEqualTo("PAID");
    assertThat(jdbc.queryForObject("select s.status from subscription s join payment p on p.subscription_id=s.id where p.transaction_code=?",String.class,ref)).isEqualTo("ACTIVE");
  }
}
