package com.influencermatch.backend.creator;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import com.influencermatch.backend.support.PostgresApiTestSupport;
import com.influencermatch.backend.creator.service.CreatorIngestionService;
import com.influencermatch.backend.creator.dto.CreatorIngestionRequest;
import com.influencermatch.backend.creator.repository.*;
import com.influencermatch.backend.user.enums.Role;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class RetiredSchemaPostgresIntegrationTest extends PostgresApiTestSupport {
  @Autowired CreatorIngestionService ingestion;
  @Autowired CreatorRepository creators;
  @Autowired PublicCreatorMetricRepository metrics;
  @Autowired org.springframework.transaction.PlatformTransactionManager transactions;

  String schema() {return "cleanup_"+UUID.randomUUID().toString().replace("-","");}
  Flyway flyway(String schema,String target) {
    var config=Flyway.configure().dataSource(jdbc.getDataSource()).schemas(schema).defaultSchema(schema);
    if(target!=null)config.target(target);
    return config.load();
  }
  UUID channel(String schema,String url,String location) {
    UUID id=UUID.randomUUID();
    jdbc.update("insert into "+schema+".creator(id,platform,external_id,user_name,profile_url,location,created_at,updated_at) values(?,'TIKTOK',?,'Channel',?,?,now(),now())",id,id.toString(),url,location);
    return id;
  }
  void metric(String schema,UUID id,UUID creator,String url,String location,String time) {
    jdbc.update("insert into "+schema+".public_creator_metric(id,creator_id,url_profile,location,collected_at,freshness_status,niche,content_summary,category,creator_type,data_confidence) values(?,?,?,?,cast(? as timestamp),'FRESH','Beauty','Summary','Lifestyle','KOC',0.6)",id,creator,url,location,time);
  }
  void absent(String schema) {
    for(String table:List.of("auth_exchange_code","creator_rate_card"))
      assertThat(jdbc.queryForObject("select to_regclass(?)::text",String.class,schema+"."+table)).isNull();
    assertThat(jdbc.queryForObject("select count(*) from information_schema.columns where table_schema=? and table_name='public_creator_metric' and column_name in ('url_profile','location')",Long.class,schema)).isZero();
  }
  List<String> rows(String schema,String table) {
    return jdbc.queryForList("select to_jsonb(t)::text from "+schema+"."+table+" t order by to_jsonb(t)::text",String.class);
  }
  @ParameterizedTest @ValueSource(strings={"null","whitespace","existing","independent","uuid-tie","no-snapshots","blank-history","url-only","location-only"})
  void backfillOnlyMissingFieldsFromLatestNonblankSnapshot(String mode) {
    String s=schema();flyway(s,"14").migrate();
    try {
      String initialUrl=(mode.equals("existing")||mode.equals("url-only"))?"https://existing.test/channel":mode.equals("whitespace")?" \t\n":null;
      String initialLocation=(mode.equals("existing")||mode.equals("location-only"))?"Existing":mode.equals("whitespace")?" \t\n":null;
      UUID c=channel(s,initialUrl,initialLocation);
      if(!mode.equals("no-snapshots")) {
        boolean blank=mode.equals("blank-history");
        metric(s,UUID.fromString("00000000-0000-0000-0000-000000000001"),c,blank?" \t":"https://old.test","Old","2026-01-01");
        if(blank)jdbc.update("update "+s+".public_creator_metric set location=E' \t\n'");
        else {
          metric(s,UUID.fromString("00000000-0000-0000-0000-000000000002"),c,"https://new.test","New",mode.equals("uuid-tie")?"2026-01-01":"2026-02-01");
          if(mode.equals("independent")) {
            jdbc.update("update "+s+".public_creator_metric set url_profile=' ' where id='00000000-0000-0000-0000-000000000002'");
            metric(s,UUID.fromString("00000000-0000-0000-0000-000000000003"),c,"https://newest.test"," \t\n","2026-03-01");
          } else metric(s,UUID.randomUUID(),c," \t\n",null,"2026-04-01");
        }
      }
      var before=jdbc.queryForList("select (to_jsonb(m)-'url_profile'-'location')::text from "+s+".public_creator_metric m order by id",String.class);
      assertThat(flyway(s,"15").migrate().migrationsExecuted).isEqualTo(1);absent(s);
      String expectedUrl=(mode.equals("existing")||mode.equals("url-only"))?initialUrl:mode.equals("no-snapshots")||mode.equals("blank-history")?initialUrl:mode.equals("independent")?"https://newest.test":"https://new.test";
      String expectedLocation=(mode.equals("existing")||mode.equals("location-only"))?initialLocation:mode.equals("no-snapshots")||mode.equals("blank-history")?initialLocation:"New";
      var result=jdbc.queryForMap("select profile_url,location from "+s+".creator where id=?",c);
      assertThat(result.get("profile_url")).isEqualTo(expectedUrl);assertThat(result.get("location")).isEqualTo(expectedLocation);
      assertThat(jdbc.queryForList("select to_jsonb(m)::text from "+s+".public_creator_metric m order by id",String.class)).isEqualTo(before);
      assertThat(flyway(s,"15").validateWithResult().validationSuccessful).isTrue();
    } finally {jdbc.execute("drop schema "+s+" cascade");}
  }
  @Test void upgradeRemovesOnlyRetiredDataAndPreservesAuthOwnershipInvitationsAuditAndFees() {
    String s=schema();flyway(s,"14").migrate();
    try {
      UUID owner=UUID.randomUUID(),manager=UUID.randomUUID(),brandOwner=UUID.randomUUID(),master=UUID.randomUUID();
      for(var entry:Map.of(owner,"CREATOR",manager,"DATA_MANAGER",brandOwner,"BRAND").entrySet())
        jdbc.update("insert into "+s+".\"user\"(id,email,full_name,role,status,created_at,updated_at,email_verified,auth_version) values(?,?,'Keep account',?,'ACTIVE',now(),now(),true,7)",entry.getKey(),entry.getKey()+"@example.test",entry.getValue());
      jdbc.update("insert into "+s+".creator_master(id,user_id,canonical_name,status,created_at,updated_at) values(?,?,'Keep Master','ACTIVE',now(),now())",master,owner);
      UUID c=channel(s,"https://creator.test/current","Current");
      jdbc.update("update "+s+".creator set master_id=?,source_type='AUTHORIZED',authorized_at=now() where id=?",master,c);
      UUID metric=UUID.randomUUID();metric(s,metric,c,"https://old.test","Old","2026-01-01");
      jdbc.update("update "+s+".public_creator_metric set quality_reviewed_by=?,quality_reviewed_at=now() where id=?",manager,metric);
      jdbc.update("insert into "+s+".external_identity(id,user_id,provider,provider_subject,provider_email,email_verified,created_at,updated_at) values(?,?,'GOOGLE','legacy','legacy@example.test',true,now(),now())",UUID.randomUUID(),owner);
      for(String table:List.of("email_verification_otp","password_setup_otp"))
        jdbc.update("insert into "+s+"."+table+"(user_id,code_hash,expires_at,last_sent_at,attempts) values(?,repeat('a',64),now()+interval '10 minutes',now(),2)",owner);
      jdbc.update("insert into "+s+".email_verification_delivery(id,user_id,sent_at) values(?,?,now())",UUID.randomUUID(),owner);
      jdbc.update("insert into "+s+".creator_registration_invitation(id,email,token_hash,expires_at,last_sent_at,invited_by) values(?,'next@example.test',repeat('b',64),now()+interval '48 hours',now(),?)",UUID.randomUUID(),manager);
      jdbc.update("insert into "+s+".creator_registration_invitation_delivery(id,email,sent_at) values(?,'next@example.test',now())",UUID.randomUUID());
      jdbc.update("insert into "+s+".creator_invitation(id,target_creator_id,invited_by,invited_email,token_hash,expires_at) values(?,?,?,'owner@example.test',repeat('c',64),now()+interval '1 day')",UUID.randomUUID(),c,manager);
      UUID claim=UUID.randomUUID();
      jdbc.update("insert into "+s+".creator_claim(id,creator_id,claimant_id,evidence_urls,note,status,reviewer_id,reviewed_at,created_at,updated_at) values(?,?,?,'[\"https://proof.test\"]'::jsonb,'Keep evidence','APPROVED',?,now(),now(),now())",claim,c,owner,manager);
      jdbc.update("insert into "+s+".creator_rate_card(id,claim_id,content_type,price,currency,updated_at) values(?,?,'VIDEO',500,'VND',now())",UUID.randomUUID(),claim);
      jdbc.update("insert into "+s+".auth_exchange_code(id,user_id,code_hash,expires_at,created_at) values(?,?,repeat('d',64),now()+interval '1 minute',now())",UUID.randomUUID(),owner);
      jdbc.update("insert into "+s+".creator_identity_audit(id,event_type,master_id,account_id,performed_by,details) values(?,'CLAIM_MASTER',?,?,?,'{}'::jsonb)",UUID.randomUUID(),master,c,manager);
      jdbc.update("insert into "+s+".audit_log(id,user_id,action,entity_type,entity_id,new_value,created_at) values(?,?,'UPDATE_CREATOR_RATE_CARD','Creator',?,'{\"price\":500}'::jsonb,now())",UUID.randomUUID(),owner,c);
      UUID brand=UUID.randomUUID(),campaign=UUID.randomUUID(),relationship=UUID.randomUUID();
      jdbc.update("insert into "+s+".brand_profile(id,user_id,business_name,created_at,updated_at) values(?,?,'Brand',now(),now())",brand,brandOwner);
      jdbc.update("insert into "+s+".campaign(id,brand_profile_id,name,product_service,objective,status,created_at,updated_at) values(?,?,'Campaign','Product','AWARENESS','DRAFT',now(),now())",campaign,brand);
      jdbc.update("insert into "+s+".relationship(id,user_id,creator_id,campaign_id,stage,quoted_fee,agreed_fee,currency,created_at,updated_at) values(?,?,?,?,'CONTACTED',1000,900,'VND',now(),now())",relationship,brandOwner,c,campaign);
      jdbc.update("insert into "+s+".collaboration(id,relationship_id,campaign_id,creator_id,agreed_fee,currency,status,updated_at) values(?,?,?,?,900,'VND','AGREED',now())",UUID.randomUUID(),relationship,campaign,c);
      var protectedTables=List.of("\"user\"","creator_master","creator","external_identity","email_verification_otp","email_verification_delivery","password_setup_otp","creator_registration_invitation","creator_registration_invitation_delivery","creator_invitation","creator_claim","creator_identity_audit","audit_log","brand_profile","campaign","relationship","collaboration");
      Map<String,List<String>> before=new LinkedHashMap<>();for(String table:protectedTables)before.put(table,rows(s,table));
      var metricsBefore=jdbc.queryForList("select (to_jsonb(m)-'url_profile'-'location')::text from "+s+".public_creator_metric m order by id",String.class);
      assertThat(flyway(s,"15").migrate().migrationsExecuted).isEqualTo(1);absent(s);
      for(String table:protectedTables)assertThat(rows(s,table)).as(table).isEqualTo(before.get(table));
      assertThat(jdbc.queryForList("select to_jsonb(m)::text from "+s+".public_creator_metric m order by id",String.class)).isEqualTo(metricsBefore);
    } finally {jdbc.execute("drop schema "+s+" cascade");}
  }
  @Test void freshSchemaHasOnlyTheCurrentTablesAndColumns() {
    String s=schema();
    try {
      assertThat(flyway(s,null).migrate().migrationsExecuted).isEqualTo(20);absent(s);
      for(String table:List.of("external_identity","creator_invitation","creator_registration_invitation","email_verification_otp","email_verification_delivery","password_setup_otp","creator_master","creator_claim","claim_email_outbox","claim_email_retry","meta_oauth_session"))
        assertThat(jdbc.queryForObject("select to_regclass(?)::text",String.class,s+"."+table)).isNotNull();
      assertThat(flyway(s,null).info().current().getVersion().toString()).isEqualTo("21");
    } finally {jdbc.execute("drop schema "+s+" cascade");}
  }
  @Test void ingestionAndProfileComparisonUseCreatorFieldsAndLocationFilterJoinsCreator() throws Exception {
    var body=CreatorIngestionRequest.builder().platform("TIKTOK").externalId(UUID.randomUUID().toString())
        .userName("cleanup-channel").creatorType("KOC").followers(10000L).niche("CleanupOnly")
        .profileUrl("https://tiktok.com/@cleanup-channel").location("Da Nang").build();
    var actor=account(Role.BRAND);
    SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(actor,null,List.of(new SimpleGrantedAuthority("SERVICE_INGEST_CREATOR"))));
    UUID id;
    try {id=ingestion.ingestCreatorData(body).getCreatorId();} finally {SecurityContextHolder.clearContext();}
    var c=creators.findById(id).orElseThrow();assertThat(c.getLocation()).isEqualTo("Da Nang");assertThat(c.getProfileUrl()).isEqualTo(body.getProfileUrl());
    body.setLocation("Ha Noi");body.setProfileUrl("https://tiktok.com/@renamed");
    SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(actor,null,List.of(new SimpleGrantedAuthority("SERVICE_INGEST_CREATOR"))));
    try {ingestion.ingestCreatorData(body);} finally {SecurityContextHolder.clearContext();}
    assertThat(metrics.findAll(PublicCreatorMetricSpecification.hasLocationIn(List.of("Ha Noi")).and((r,q,cb)->cb.equal(r.get("creator").get("id"),id)))).hasSize(1);
    assertThat(metrics.findAll(PublicCreatorMetricSpecification.hasLocationIn(List.of("Da Nang")).and((r,q,cb)->cb.equal(r.get("creator").get("id"),id)))).isEmpty();
    var profile=call(actor,get("/creators/"+id),null,200).path("data");
    assertThat(profile.path("location").asText()).isEqualTo("Ha Noi");assertThat(profile.path("profileUrl").asText()).isEqualTo(body.getProfileUrl());
    var campaign=createCampaign(actor,createBrand(actor));
    jdbc.update("update campaign set niches=array['CleanupOnly'] where id=?",campaign);
    var discovered=call(actor,get("/campaigns/"+campaign+"/discover"),null,200).path("data").path("items");
    var selected=java.util.stream.StreamSupport.stream(discovered.spliterator(),false)
        .filter(item->item.path("creatorId").asText().equals(id.toString())).findFirst().orElseThrow();
    assertThat(selected.path("location").asText()).isEqualTo("Ha Noi");
    assertThat(selected.path("profileUrl").asText()).isEqualTo(body.getProfileUrl());
    var other=creators.saveAndFlush(com.influencermatch.backend.creator.model.Creator.builder().platform("TIKTOK").externalId(UUID.randomUUID().toString()).userName("Other").build());
    for(var creator:List.of(c,other))call(actor,post("/campaigns/"+campaign+"/shortlist"),Map.of("creatorId",creator.getId(),"priority","HIGH"),200);
    var comparison=call(actor,post("/campaigns/"+campaign+"/shortlist/compare"),Map.of("creatorIds",List.of(id,other.getId())),200).path("data").path("creators").get(0);
    assertThat(comparison.path("location").asText()).isEqualTo("Ha Noi");assertThat(comparison.path("profileUrl").asText()).isEqualTo(body.getProfileUrl());
    absent("public");
  }
}
