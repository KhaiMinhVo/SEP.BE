package com.influencermatch.backend.creator;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.*;
import com.influencermatch.backend.billing.enums.*;
import com.influencermatch.backend.billing.model.*;
import com.influencermatch.backend.billing.repository.*;
import com.influencermatch.backend.brand.model.BrandProfile;
import com.influencermatch.backend.brand.repository.BrandProfileRepository;
import com.influencermatch.backend.campaign.enums.*;
import com.influencermatch.backend.campaign.model.Campaign;
import com.influencermatch.backend.campaign.repository.CampaignRepository;
import com.influencermatch.backend.creator.enums.*;
import com.influencermatch.backend.creator.model.*;
import com.influencermatch.backend.creator.repository.*;
import com.influencermatch.backend.relationship.enums.ShortlistPriority;
import com.influencermatch.backend.relationship.model.ShortlistItem;
import com.influencermatch.backend.relationship.repository.ShortlistItemRepository;
import com.influencermatch.backend.security.JwtTokenProvider;
import com.influencermatch.backend.user.enums.*;
import com.influencermatch.backend.user.model.User;
import com.influencermatch.backend.user.repository.UserRepository;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=validate","spring.flyway.enabled=true",
    "application.bootstrap-admin.enabled=false","application.oauth2.google.enabled=false",
    "application.payment.mock.enabled=true","spring.jpa.show-sql=false"})
@ActiveProfiles("dev") @AutoConfigureMockMvc @Testcontainers
class ComparePaymentPostgresIntegrationTest {
  @Container static final PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>(
      DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
  @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url",postgres::getJdbcUrl); r.add("spring.datasource.username",postgres::getUsername);
    r.add("spring.datasource.password",postgres::getPassword);
  }
  @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired JwtTokenProvider jwt;
  @Autowired UserRepository users; @Autowired BrandProfileRepository brands; @Autowired CampaignRepository campaigns;
  @Autowired CreatorRepository creators; @Autowired PublicCreatorMetricRepository metrics;
  @Autowired ShortlistItemRepository shortlist; @Autowired CreatorClaimRepository claims;
  @Autowired CreatorRateCardRepository cards; @Autowired PlanRepository plans;
  @Autowired SubscriptionRepository subscriptions; @Autowired PaymentRepository payments; @Autowired JdbcTemplate jdbc;

  User user(Role role) { return users.saveAndFlush(User.builder().email(UUID.randomUUID()+"@compare.test")
      .fullName("Test").role(role).status(UserStatus.ACTIVE).build()); }
  BrandProfile brand(User u) { return brands.saveAndFlush(BrandProfile.builder().user(u).businessName("Test").build()); }
  Creator creator() { return creators.saveAndFlush(Creator.builder().platform("TIKTOK").externalId(UUID.randomUUID().toString()).userName("koc").build()); }
  Campaign campaign(BrandProfile b) { return campaigns.saveAndFlush(Campaign.builder().brandProfile(b).name("Compare")
      .productService("Product").objective(CampaignObjective.AWARENESS).status(CampaignStatus.DRAFT).build()); }
  void add(Campaign c,Creator creator,User u) { shortlist.saveAndFlush(ShortlistItem.builder().campaign(c).creator(creator)
      .user(u).priority(ShortlistPriority.HIGH).note("My note").addedAt(LocalDateTime.now()).build()); }
  JsonNode call(User u,MockHttpServletRequestBuilder req,Object body,int status) throws Exception {
    if(u!=null)req.header("Authorization","Bearer "+jwt.generateToken(u));
    if(body!=null)req.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
    var result=mvc.perform(req).andExpect(status().is(status)).andReturn().getResponse().getContentAsString();
    return result.isBlank()?json.nullNode():json.readTree(result).path("data");
  }
  String compare(Campaign c) { return "/campaigns/"+c.getId()+"/shortlist/compare"; }
  String mock="/billing/mock/webhook";
  Map<String,Object> outcome(Payment p,String status) { return Map.of("transactionCode",p.getTransactionCode(),"outcome",status); }
  Plan plan() { return plans.saveAndFlush(Plan.builder().planName(UUID.randomUUID().toString()).price(new BigDecimal("100000"))
      .currency("VND").durationDays(30).status(PlanStatus.ACTIVE).createdAt(LocalDateTime.now()).build()); }
  Payment payment(BrandProfile b,Plan plan) {
    var sub=subscriptions.saveAndFlush(Subscription.builder().brandProfile(b).plan(plan).status(SubscriptionStatus.PENDING)
        .startDate(LocalDate.now().minusDays(3)).expirationDate(LocalDate.now().plusDays(27)).build());
    return payments.saveAndFlush(Payment.builder().brandProfile(b).subscription(sub).amount(plan.getPrice()).currency("VND")
        .paymentGateway("VNPAY").transactionCode(UUID.randomUUID().toString()).status(PaymentStatus.PENDING).createdAt(LocalDateTime.now()).build());
  }

  @Test void comparePreservesOrderUsesLatestAndOnlyShowsActiveOwnerRates() throws Exception {
    var u=user(Role.BRAND); var c=campaign(brand(u)); var a=creator(); var b=creator(); add(c,a,u);add(c,b,u);
    metrics.saveAndFlush(PublicCreatorMetric.builder().creator(a).creatorType("KOC").niche("Old").followers(100L)
        .collectedAt(LocalDateTime.now().minusDays(1)).freshnessStatus(MetricDataStatus.STALE).build());
    var latest=metrics.saveAndFlush(PublicCreatorMetric.builder().creator(a).creatorType("KOL").niche("Beauty").followers(200L)
        .collectedAt(LocalDateTime.now()).freshnessStatus(MetricDataStatus.FRESH).build());
    var owner=user(Role.CREATOR); a.setOwner(owner);a.setSourceType(CreatorSourceType.AUTHORIZED);a.setAuthorizedAt(LocalDateTime.now());creators.saveAndFlush(a);
    var claim=claims.saveAndFlush(CreatorClaim.builder().creator(a).claimant(owner).status(CreatorClaimStatus.APPROVED)
        .evidenceUrls(List.of("https://example.com/proof")).note("Proof").build());
    cards.saveAndFlush(CreatorRateCard.builder().claim(claim).contentType(RateCardContentType.VIDEO).price(BigDecimal.TEN)
        .currency("VND").updatedAt(LocalDateTime.now()).build());
    var data=call(u,post(compare(c)),Map.of("creatorIds",List.of(b.getId(),a.getId())),200);
    assertThat(data.path("creators").get(0).path("creatorId").asText()).isEqualTo(b.getId().toString());
    assertThat(data.path("creators").get(0).has("metrics")).isTrue();
    assertThat(data.path("creators").get(0).path("metrics").isNull()).isTrue();
    assertThat(data.path("creators").get(1).path("metrics").path("metricId").asText()).isEqualTo(latest.getId().toString());
    assertThat(data.path("creators").get(1).path("rateCard").path("items").size()).isEqualTo(1);
    claim.setStatus(CreatorClaimStatus.REVOKED);claims.saveAndFlush(claim);
    a.setOwner(null);a.setSourceType(CreatorSourceType.PUBLIC);a.setAuthorizedAt(null);creators.saveAndFlush(a);
    data=call(u,post(compare(c)),Map.of("creatorIds",List.of(a.getId(),b.getId())),200);
    assertThat(data.path("creators").get(0).path("rateCard").path("items").isEmpty()).isTrue();
  }

  @Test void compareValidatesOwnershipMembershipAndIds() throws Exception {
    var u=user(Role.BRAND);var c=campaign(brand(u));var a=creator();var b=creator();add(c,a,u);
    var body=Map.of("creatorIds",List.of(a.getId(),b.getId()));
    call(null,post(compare(c)),body,401);
    call(user(Role.CREATOR),post(compare(c)),body,403);
    call(user(Role.BRAND),post(compare(c)),body,404);
    call(u,post(compare(c)),body,400);
    call(u,post(compare(c)),Map.of("creatorIds",List.of(a.getId(),a.getId())),400);
    call(u,post(compare(c)),Map.of("creatorIds",List.of(a.getId())),400);
    call(u,post(compare(c)),Map.of("creatorIds",Collections.nCopies(6,a.getId())),400);
  }

  @Test void checkoutExposesTransactionCodeAndMockActivatesIdempotently() throws Exception {
    var u=user(Role.BRAND);brand(u);var plan=plan();
    var checkout=call(u,post("/billing/subscribe"),Map.of("planId",plan.getId()),200);
    assertThat(checkout.path("paymentUrl").asText()).contains(checkout.path("transactionCode").asText());
    var p=payments.findByTransactionCode(checkout.path("transactionCode").asText()).orElseThrow();
    var first=call(u,post(mock),outcome(p,"SUCCESS"),200);
    assertThat(first.path("paymentStatus").asText()).isEqualTo("PAID");
    assertThat(first.path("subscriptionStatus").asText()).isEqualTo("ACTIVE");
    assertThat(call(u,post(mock),outcome(p,"SUCCESS"),200)).isEqualTo(first);
    call(u,post(mock),outcome(p,"FAILED"),409);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE entity_id=? AND action='MOCK_PAYMENT_WEBHOOK'",Integer.class,p.getId())).isEqualTo(1);
  }

  @Test void mockFailureOwnershipPermissionsAndValidation() throws Exception {
    var u=user(Role.BRAND);var p=payment(brand(u),plan());
    call(null,post(mock),outcome(p,"FAILED"),401);
    call(user(Role.DATA_MANAGER),post(mock),outcome(p,"FAILED"),403);
    call(user(Role.BRAND),post(mock),outcome(p,"FAILED"),404);
    call(u,post(mock),Map.of("transactionCode","missing","outcome","SUCCESS"),404);
    call(u,post(mock),Map.of("transactionCode",p.getTransactionCode(),"outcome","INVALID"),400);
    var failed=call(u,post(mock),outcome(p,"FAILED"),200);
    assertThat(failed.path("subscriptionStatus").asText()).isEqualTo("CANCELLED");
    assertThat(call(u,post(mock),outcome(p,"FAILED"),200)).isEqualTo(failed);
    call(u,post(mock),outcome(p,"SUCCESS"),409);
  }

  @Test void parallelPaymentsForSameBrandCannotActivateTwoSubscriptions() throws Exception {
    var u=user(Role.BRAND);var brand=brand(u);var plan=plan();var a=payment(brand,plan);var b=payment(brand,plan);
    var start=new CountDownLatch(1);
    try(var pool=Executors.newFixedThreadPool(2)) {
      List<Future<Integer>> results=new ArrayList<>();
      for(var p:List.of(a,b))results.add(pool.submit(()->{
        start.await(); return mvc.perform(post(mock).header("Authorization","Bearer "+jwt.generateToken(u))
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(outcome(p,"SUCCESS"))))
            .andReturn().getResponse().getStatus();
      }));
      start.countDown();
      assertThat(List.of(results.get(0).get(30,TimeUnit.SECONDS),results.get(1).get(30,TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(200,409);
    }
  }

  @Test void concurrentDuplicateCallbacksCreateOneAuditAndPreserveQuota() throws Exception {
    var u=user(Role.BRAND);var p=payment(brand(u),plan());
    jdbc.update("UPDATE subscription SET used_campaign_quota=3 WHERE id=?",p.getSubscription().getId());
    var start=new CountDownLatch(1);
    try(var pool=Executors.newFixedThreadPool(2)) {
      List<Future<JsonNode>> results=new ArrayList<>();
      for(int i=0;i<2;i++)results.add(pool.submit(()->{start.await();return call(u,post(mock),outcome(p,"SUCCESS"),200);}));
      start.countDown();assertThat(results.get(0).get(30,TimeUnit.SECONDS)).isEqualTo(results.get(1).get(30,TimeUnit.SECONDS));
    }
    assertThat(jdbc.queryForObject("SELECT used_campaign_quota FROM subscription WHERE id=?",Integer.class,p.getSubscription().getId())).isEqualTo(3);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE entity_id=? AND action='MOCK_PAYMENT_WEBHOOK'",Integer.class,p.getId())).isEqualTo(1);
  }

  @Test void anotherBrandCannotReadOrMutateShortlistAndRelationships() throws Exception {
    var owner=user(Role.BRAND);var attacker=user(Role.BRAND);var c=campaign(brand(owner));var a=creator();var b=creator();
    var shortItem=call(owner,post("/campaigns/"+c.getId()+"/shortlist"),Map.of("creatorId",a.getId(),"priority","HIGH"),200);
    var relationship=call(owner,post("/campaigns/"+c.getId()+"/relationships"),Map.of("creatorId",a.getId()),200);
    String base="/campaigns/"+c.getId();
    call(attacker,get(base+"/shortlist"),null,404);
    call(attacker,post(base+"/shortlist"),Map.of("creatorId",b.getId(),"priority","HIGH"),404);
    // Existing entries must not leak their existence via duplicate-conflict responses.
    call(attacker,post(base+"/shortlist"),Map.of("creatorId",a.getId(),"priority","HIGH"),404);
    call(attacker,delete(base+"/shortlist/"+shortItem.path("id").asText()),null,404);
    call(attacker,get(base+"/relationships"),null,404);
    call(attacker,post(base+"/relationships"),Map.of("creatorId",b.getId()),404);
    call(attacker,post(base+"/relationships"),Map.of("creatorId",a.getId()),404);
    String rel=base+"/relationships/"+relationship.path("id").asText();
    call(attacker,get(rel),null,404);
    call(attacker,patch(rel+"/stage"),Map.of("stage","INTERESTED"),404);
    call(attacker,patch(rel+"/details"),Map.of("notes","tampered"),404);
    assertThat(call(owner,get(rel),null,200).path("stage").asText()).isEqualTo("CONTACTED");
    assertThat(shortlist.existsById(UUID.fromString(shortItem.path("id").asText()))).isTrue();
    assertThat(shortlist.existsByCampaignIdAndCreatorId(c.getId(),b.getId())).isFalse();
  }

  @Test void owningBothCampaignsDoesNotPermitWrongNestedCampaignPath() throws Exception {
    var owner=user(Role.BRAND);var brand=brand(owner);var c=campaign(brand);var other=campaign(brand);var a=creator();
    var s=call(owner,post("/campaigns/"+c.getId()+"/shortlist"),Map.of("creatorId",a.getId(),"priority","HIGH"),200);
    var r=call(owner,post("/campaigns/"+c.getId()+"/relationships"),Map.of("creatorId",a.getId(),"shortlistItemId",s.path("id").asText()),200);
    String wrong="/campaigns/"+other.getId();
    call(owner,delete(wrong+"/shortlist/"+s.path("id").asText()),null,404);
    String rel=wrong+"/relationships/"+r.path("id").asText();
    call(owner,get(rel),null,404);
    call(owner,patch(rel+"/stage"),Map.of("stage","INTERESTED"),404);
    call(owner,patch(rel+"/details"),Map.of("notes","wrong campaign"),404);
    call(owner,post(wrong+"/relationships"),Map.of("creatorId",a.getId(),"shortlistItemId",s.path("id").asText()),404);
    String correct="/campaigns/"+c.getId()+"/relationships/"+r.path("id").asText();
    call(owner,patch(correct+"/stage"),Map.of("stage","INTERESTED"),200);
    assertThat(call(owner,patch(correct+"/details"),Map.of("notes","owner updated"),200).path("notes").asText()).isEqualTo("owner updated");
    assertThat(call(owner,get("/campaigns/"+c.getId()+"/shortlist"),null,200).path("content").size()).isEqualTo(1);
    assertThat(call(owner,get("/campaigns/"+c.getId()+"/relationships"),null,200).path("content").size()).isEqualTo(1);
    var removable=call(owner,post("/campaigns/"+c.getId()+"/shortlist"),
        Map.of("creatorId",creator().getId(),"priority","HIGH"),200);
    call(owner,delete("/campaigns/"+c.getId()+"/shortlist/"+removable.path("id").asText()),null,200);
  }

  @Test void relationshipCannotAttachForeignOrMismatchedShortlistItem() throws Exception {
    var owner=user(Role.BRAND);var c=campaign(brand(owner));var a=creator();var b=creator();
    var other=user(Role.BRAND);var foreign=campaign(brand(other));
    var s=call(owner,post("/campaigns/"+c.getId()+"/shortlist"),Map.of("creatorId",a.getId(),"priority","HIGH"),200);
    var f=call(other,post("/campaigns/"+foreign.getId()+"/shortlist"),Map.of("creatorId",a.getId(),"priority","HIGH"),200);
    String path="/campaigns/"+c.getId()+"/relationships";
    call(owner,post(path),Map.of("creatorId",b.getId(),"shortlistItemId",s.path("id").asText()),400);
    call(owner,post(path),Map.of("creatorId",a.getId(),"shortlistItemId",f.path("id").asText()),404);
    call(owner,post(path),Map.of("creatorId",a.getId(),"shortlistItemId",UUID.randomUUID()),404);
    assertThat(call(owner,get(path),null,200).path("content").size()).isZero();
  }

  @Test void legacyPollutedRowsDoNotGrantAccessToForeignCampaign() throws Exception {
    var owner=user(Role.BRAND);var attacker=user(Role.BRAND);var c=campaign(brand(owner));var a=creator();
    var s=shortlist.saveAndFlush(ShortlistItem.builder().campaign(c).creator(a).user(attacker)
        .priority(ShortlistPriority.HIGH).addedAt(LocalDateTime.now()).build());
    String base="/campaigns/"+c.getId();
    call(attacker,delete(base+"/shortlist/"+s.getId()),null,404);
    assertThat(call(owner,get(base+"/shortlist"),null,200).path("content").size()).isZero();
    var r=call(owner,post(base+"/relationships"),Map.of("creatorId",a.getId()),200);
    jdbc.update("UPDATE relationship SET user_id=? WHERE id=?",attacker.getId(),UUID.fromString(r.path("id").asText()));
    call(attacker,get(base+"/relationships/"+r.path("id").asText()),null,404);
    assertThat(call(owner,get(base+"/relationships"),null,200).path("content").size()).isZero();
  }

  @Test void unauthenticatedAndNonBrandActorsRemainDenied() throws Exception {
    var owner=user(Role.BRAND);var c=campaign(brand(owner));var a=creator();
    for(String suffix:List.of("/shortlist","/relationships")) {
      String path="/campaigns/"+c.getId()+suffix;
      call(null,get(path),null,401);
      for(Role role:List.of(Role.ADMIN,Role.DATA_MANAGER,Role.CREATOR)) {
        call(user(role),get(path),null,403);
        call(user(role),post(path),Map.of("creatorId",a.getId(),"priority","HIGH"),403);
      }
    }
    call(owner,get("/campaigns/"+UUID.randomUUID()+"/shortlist"),null,404);
    call(owner,get("/campaigns/"+UUID.randomUUID()+"/relationships"),null,404);
  }
}
