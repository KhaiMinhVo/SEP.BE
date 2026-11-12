package com.influencermatch.backend.creator;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import com.influencermatch.backend.creator.model.Creator;
import com.influencermatch.backend.creator.repository.CreatorRepository;
import com.influencermatch.backend.support.PostgresApiTestSupport;
import com.influencermatch.backend.user.enums.*;
import java.math.BigDecimal;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;

class RetiredRateCardPostgresIntegrationTest extends PostgresApiTestSupport {
  @Autowired CreatorRepository creators;
  Creator channel() {return creators.saveAndFlush(Creator.builder().platform("TIKTOK")
      .externalId(UUID.randomUUID().toString()).userName("Channel").build());}

  @ParameterizedTest @CsvSource({"BRAND,GET","BRAND,PUT","CREATOR,GET","CREATOR,PUT","ADMIN,GET","ADMIN,PUT","DATA_MANAGER,GET","DATA_MANAGER,PUT"})
  void retiredEndpointsAre404WithoutBusinessWrites(Role role,String method) throws Exception {
    var actor=account(role);var channel=channel();long auditCount=jdbc.queryForObject("select count(*) from audit_log",Long.class);
    var error=call(actor,request(HttpMethod.valueOf(method),"/creators/"+channel.getId()+"/rate-card"),
        method.equals("PUT")?Map.of("items",List.of()):null,404);
    assertThat(error.path("code").asText()).isEqualTo("RESOURCE_NOT_FOUND");
    assertThat(creators.findById(channel.getId()).orElseThrow().getOwner()).isNull();
    assertThat(jdbc.queryForObject("select count(*) from audit_log",Long.class)).isEqualTo(auditCount);
  }

  @Test void claimRevokeAndCompareWorkWithoutRateCardStorage() throws Exception {
    var owner=account(Role.CREATOR);var reviewer=account(Role.DATA_MANAGER);
    var a=channel();var b=channel();
    var claim=UUID.fromString(call(owner,post("/creators/"+a.getId()+"/claim"),
        Map.of("evidenceUrls",List.of("https://example.test/proof"),"note","Proof"),201).path("data").path("id").asText());
    call(reviewer,patch("/data/creator-claims/"+claim+"/review"),Map.of("decision","APPROVED","reason","Verified"),200);
    var brand=account(Role.BRAND);var campaign=createCampaign(brand,createBrand(brand));
    for(var channel:List.of(a,b))call(brand,post("/campaigns/"+campaign+"/shortlist"),Map.of("creatorId",channel.getId(),"priority","HIGH"),200);
    var profile=call(brand,get("/creators/"+a.getId()),null,200).path("data");
    assertThat(profile.path("verifiedByInfluencerMatch").asBoolean()).isTrue();assertThat(profile.has("rateCard")).isFalse();
    var compared=call(brand,post("/campaigns/"+campaign+"/shortlist/compare"),Map.of("creatorIds",List.of(a.getId(),b.getId())),200).path("data").path("creators");
    assertThat(compared.size()).isEqualTo(2);compared.forEach(item->assertThat(item.has("rateCard")).isFalse());
    assertThat(compared.get(0).path("verifiedByInfluencerMatch").asBoolean()).isTrue();
    call(reviewer,post("/data/creator-claims/"+claim+"/revoke"),Map.of("reason","Withdrawn"),200);
    var revoked=call(brand,get("/creators/"+a.getId()),null,200).path("data");
    assertThat(revoked.path("sourceType").asText()).isEqualTo("PUBLIC");assertThat(revoked.path("verifiedByInfluencerMatch").asBoolean()).isFalse();
    assertThat(revoked.has("rateCard")).isFalse();
    assertThat(jdbc.queryForObject("select to_regclass('public.creator_rate_card')::text",String.class)).isNull();
  }

  @Test void retiredPermissionIsAbsentAndCannotBeConfigured() throws Exception {
    var admin=account(Role.ADMIN);
    var catalog=call(admin,get("/admin/role-permissions"),null,200);
    assertThat(catalog.toString()).doesNotContain("MANAGE_OWN_RATE_CARD");
    var version=catalog.path("data").findValues("version");assertThat(version).isNotEmpty();
    call(admin,patch("/admin/role-permissions/CREATOR"),Map.of("version",0,"changes",
        List.of(Map.of("permission","MANAGE_OWN_RATE_CARD","enabled",true))),400);
    for(Role role:Role.values()) {
      var actor=account(role);
      assertThat(call(actor,get("/auth/me"),null,200).path("data").path("permissions").toString()).doesNotContain("MANAGE_OWN_RATE_CARD");
    }
  }

  @ParameterizedTest @ValueSource(booleans={true,false})
  void upgradePreservesLegacyDataAndOnlyBumpsAffectedConfiguration(boolean hasRetiredOverride) throws Exception {
    String schema="retired_rates_"+UUID.randomUUID().toString().replace("-","");
    var old=Flyway.configure().dataSource(jdbc.getDataSource()).schemas(schema).defaultSchema(schema).target("10").load();
    old.migrate();
    String prefix=schema+".";var owner=UUID.randomUUID();var channel=UUID.randomUUID();var claim=UUID.randomUUID();var price=UUID.randomUUID();var audit=UUID.randomUUID();
    try {
      jdbc.update("insert into "+prefix+"\"user\"(id,email,full_name,role,status,created_at,updated_at) values(?,'legacy@creator.test','Legacy','CREATOR','ACTIVE',now(),now())",owner);
      var master=UUID.randomUUID();
      jdbc.update("insert into "+prefix+"creator_master(id,user_id,canonical_name,status,created_at,updated_at) values(?,?,'Legacy','ACTIVE',now(),now())",master,owner);
      jdbc.update("insert into "+prefix+"creator(id,platform,external_id,user_name,master_id,source_type,authorized_at,created_at,updated_at) values(?,'TIKTOK','legacy','legacy',?,'AUTHORIZED',now(),now(),now())",channel,master);
      jdbc.update("insert into "+prefix+"creator_claim(id,creator_id,claimant_id,evidence_urls,note,status,created_at,updated_at) values(?,?,?,'[]'::jsonb,'Legacy','APPROVED',now(),now())",claim,channel,owner);
      jdbc.update("insert into "+prefix+"creator_rate_card(id,claim_id,content_type,price,currency,note,updated_at) values(?,?,'POST',99.25,'USD','Keep me',timestamp '2026-01-01 00:00:00')",price,claim);
      jdbc.update("insert into "+prefix+"audit_log(id,user_id,action,entity_type,entity_id,new_value,created_at) values(?,?,'UPDATE_CREATOR_RATE_CARD','Creator',?,cast(? as jsonb),now())",audit,owner,channel,json.writeValueAsString(Map.of("price","99.25")));
      jdbc.update("update "+prefix+"role_permission_config set version=case role when 'CREATOR' then 7 when 'BRAND' then 3 else 5 end");
      jdbc.update("insert into "+prefix+"role_permission_override(id,role,permission,enabled) values(?,'CREATOR','CLAIM_CREATOR_PROFILE',false)",UUID.randomUUID());
      if(hasRetiredOverride)jdbc.update("insert into "+prefix+"role_permission_override(id,role,permission,enabled) values(?,'CREATOR','MANAGE_OWN_RATE_CARD',false)",UUID.randomUUID());
      var current=Flyway.configure().dataSource(jdbc.getDataSource()).schemas(schema).defaultSchema(schema).target("12").load();
      assertThat(current.migrate().migrationsExecuted).isEqualTo(1);assertThat(current.validateWithResult().validationSuccessful).isTrue();
      assertThat(jdbc.queryForObject("select count(*) from "+prefix+"role_permission_override where permission='MANAGE_OWN_RATE_CARD'",Long.class)).isZero();
      assertThat(jdbc.queryForObject("select version from "+prefix+"role_permission_config where role='CREATOR'",Long.class)).isEqualTo(hasRetiredOverride?8:7);
      assertThat(jdbc.queryForObject("select version from "+prefix+"role_permission_config where role='BRAND'",Long.class)).isEqualTo(3);
      assertThat(jdbc.queryForObject("select version from "+prefix+"role_permission_config where role='DATA_MANAGER'",Long.class)).isEqualTo(5);
      assertThat(jdbc.queryForObject("select enabled from "+prefix+"role_permission_override where permission='CLAIM_CREATOR_PROFILE'",Boolean.class)).isFalse();
      assertThat(jdbc.queryForObject("select price from "+prefix+"creator_rate_card where id=?",BigDecimal.class,price)).isEqualByComparingTo("99.25");
      assertThat(jdbc.queryForObject("select currency from "+prefix+"creator_rate_card where id=?",String.class,price)).isEqualTo("USD");
      assertThat(jdbc.queryForObject("select note from "+prefix+"creator_rate_card where id=?",String.class,price)).isEqualTo("Keep me");
      assertThat(jdbc.queryForObject("select new_value->>'price' from "+prefix+"audit_log where id=?",String.class,audit)).isEqualTo("99.25");
      assertThat(jdbc.queryForObject("select source_type from "+prefix+"creator where id=?",String.class,channel)).isEqualTo("AUTHORIZED");
      assertThat(jdbc.queryForObject("select status from "+prefix+"creator_claim where id=?",String.class,claim)).isEqualTo("APPROVED");
    } finally {jdbc.execute("drop schema "+schema+" cascade");}
  }
}
