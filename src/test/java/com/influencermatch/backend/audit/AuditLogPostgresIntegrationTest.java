package com.influencermatch.backend.audit;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import com.influencermatch.backend.audit.model.AuditLog;
import com.influencermatch.backend.audit.repository.AuditLogRepository;
import com.influencermatch.backend.support.PostgresApiTestSupport;
import com.influencermatch.backend.support.TestAuthHttp;
import com.influencermatch.backend.support.TestBrandHttp;
import com.influencermatch.backend.support.TestCampaignHttp;
import com.influencermatch.backend.user.enums.Role;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;

class AuditLogPostgresIntegrationTest extends PostgresApiTestSupport {
  @Autowired AuditLogRepository logs;
  AuditLog event(UUID actor,String action,UUID entity,LocalDateTime time){
    return logs.saveAndFlush(AuditLog.builder().userId(actor).action(action).entityType("User").entityId(entity)
        .createdAt(time).oldValue(json.createObjectNode().put("status","ACTIVE"))
        .newValue(json.createObjectNode().put("status","LOCKED")).build());
  }
  @Test void combinesAllFiltersAndIncludesTimeBoundaries() throws Exception {
    var admin=account(Role.ADMIN);var actor=account(Role.BRAND);var other=account(Role.BRAND);
    var entity=UUID.randomUUID();String action="FILTER_"+UUID.randomUUID();var time=LocalDateTime.of(2026,1,2,12,0);
    var expected=event(actor.getId(),action,entity,time);
    event(other.getId(),action,entity,time);event(actor.getId(),action,UUID.randomUUID(),time);
    event(actor.getId(),action,entity,time.minusSeconds(1));event(actor.getId(),action+"_OTHER",entity,time);
    var data=call(admin,get("/admin/audit-logs").param("actorId",actor.getId().toString()).param("action",action)
        .param("entityType","User").param("entityId",entity.toString()).param("from",time.toString()).param("to",time.toString()),null,200).path("data");
    assertThat(data.path("totalElements").asLong()).isEqualTo(1);assertThat(data.path("items").get(0).path("id").asText()).isEqualTo(expected.getId().toString());
    assertThat(data.path("items").get(0).path("newValue").path("status").asText()).isEqualTo("LOCKED");
  }
  @Test void paginationOrdersLatestFirstAndCapsSize() throws Exception {
    var admin=account(Role.ADMIN);String action="PAGE_"+UUID.randomUUID();var time=LocalDateTime.of(2026,1,2,12,0);
    var ids=new ArrayList<String>();for(int i=0;i<3;i++)ids.add(event(admin.getId(),action,UUID.randomUUID(),time.plusSeconds(i)).getId().toString());
    var first=call(admin,get("/admin/audit-logs").param("action",action).param("page","0").param("size","2"),null,200).path("data");
    assertThat(first.path("totalElements").asLong()).isEqualTo(3);assertThat(first.path("totalPages").asInt()).isEqualTo(2);
    assertThat(first.path("items").get(0).path("id").asText()).isEqualTo(ids.get(2));
    var second=call(admin,get("/admin/audit-logs").param("action",action).param("page","1").param("size","2"),null,200).path("data");
    assertThat(second.path("items").get(0).path("id").asText()).isEqualTo(ids.get(0));
    var clamped=call(admin,get("/admin/audit-logs").param("action",action).param("page","-1").param("size","1000"),null,200).path("data");
    assertThat(clamped.path("page").asInt()).isZero();assertThat(clamped.path("size").asInt()).isEqualTo(100);
    assertThat(call(admin,get("/admin/audit-logs").param("action",action).param("size","0"),null,200).path("data").path("size").asInt()).isEqualTo(1);
  }
  @Test void keywordSearchReturnsSafeDtoAndPreservesDeletedActorId() throws Exception {
    var admin=account(Role.ADMIN);var deletedActor=UUID.randomUUID();var entity=UUID.randomUUID();
    String action="KEYWORD_AUDIT_"+UUID.randomUUID();
    logs.saveAndFlush(AuditLog.builder().userId(deletedActor).action(action).entityType("SensitiveEntity")
        .entityId(entity).createdAt(LocalDateTime.now())
        .oldValue(json.createObjectNode().put("status","ACTIVE").put("password","not-for-response").put("email","private@example.test"))
        .newValue(json.createObjectNode().put("role","ADMIN").put("accessToken","private-token"))
        .description("private-description").ipAddress("192.0.2.1").build());

    var response=call(admin,get("/admin/audit-logs").param("keyword","sensitiveentity")
        .param("actorId",deletedActor.toString()).param("entityId",entity.toString()),null,200);
    var item=response.path("data").path("items").get(0);
    assertThat(response.path("data").path("totalElements").asLong()).isEqualTo(1);
    assertThat(item.path("actorId").asText()).isEqualTo(deletedActor.toString());
    assertThat(item.path("userId").asText()).isEqualTo(deletedActor.toString());
    assertThat(item.path("entityType").asText()).isEqualTo("SensitiveEntity");
    assertThat(item.path("timestamp").asText()).isNotBlank();
    assertThat(item.path("oldValue").path("status").asText()).isEqualTo("ACTIVE");
    assertThat(item.path("oldValue").has("password")).isFalse();
    assertThat(item.path("oldValue").has("email")).isFalse();
    assertThat(item.path("newValue").has("accessToken")).isFalse();
    assertThat(item.has("description")).isFalse();
    assertThat(response.toString()).doesNotContain("not-for-response","private@example.test","private-token","private-description");
    var byActorKeyword=call(admin,get("/admin/audit-logs").param("keyword",deletedActor.toString()),null,200);
    assertThat(byActorKeyword.path("data").path("totalElements").asLong()).isGreaterThanOrEqualTo(1);
  }
  @Test void csvExportAppliesFiltersAndOmitsPrivateAuditPayload() throws Exception {
    var admin=account(Role.ADMIN);var actor=UUID.randomUUID();var entity=UUID.randomUUID();
    String action="CSV_AUDIT_"+UUID.randomUUID();
    logs.saveAndFlush(AuditLog.builder().userId(actor).action(action).entityType("User").entityId(entity)
        .createdAt(LocalDateTime.now()).oldValue(json.createObjectNode().put("password","csv-secret"))
        .newValue(json.createObjectNode().put("email","csv-private@example.test"))
        .description("csv-private-description").ipAddress("192.0.2.44").build());
    event(UUID.randomUUID(),action+"_OTHER",entity,LocalDateTime.now());
    var response=mvc.perform(get("/admin/audit-logs/export").header("Authorization","Bearer "+jwt.generateToken(admin))
            .param("keyword",action)
            .param("actorId",actor.toString()).param("entityId",entity.toString()).param("limit","99999")
            .with(TestAuthHttp.request(null)).with(TestBrandHttp.revision(mvc)).with(TestCampaignHttp.revision(mvc)))
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
        .andReturn().getResponse();
    String csv=response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    assertThat(response.getHeader("Content-Disposition")).contains("attachment");
    assertThat(response.getHeader("X-Export-Row-Limit")).isEqualTo("10000");
    assertThat(csv).contains("id,actorId,action,entityType,entityId,timestamp",action,actor.toString(),entity.toString());
    assertThat(csv.lines().count()).isEqualTo(2);
    assertThat(csv).doesNotContain("csv-secret","csv-private@example.test","csv-private-description","192.0.2.44");
  }
  @ParameterizedTest @ValueSource(strings={"reversed","badDate","badActor"})
  void invalidFiltersAre400AndDoNotCreateAudit(String kind) throws Exception {
    var admin=account(Role.ADMIN);var req=get("/admin/audit-logs");
    switch(kind){case "reversed"->req.param("from","2026-01-02T00:00:00").param("to","2026-01-01T00:00:00");
      case "badDate"->req.param("from","yesterday");case "badActor"->req.param("actorId","not-a-uuid");}
    call(admin,req,null,400);
    assertThat(jdbc.queryForObject("select count(*) from audit_log where user_id=?",Long.class,admin.getId())).isZero();
  }
  @ParameterizedTest @EnumSource(value=Role.class,names={"BRAND","CREATOR","DATA_MANAGER"})
  void nonAdminCannotReadAudit(Role role) throws Exception {
    call(account(role),get("/admin/audit-logs"),null,403);
    call(account(role),get("/admin/audit-logs/export"),null,403);
  }
  @Test void anonymousCannotReadAudit() throws Exception {
    call(null,get("/admin/audit-logs"),null,401);
    call(null,get("/admin/audit-logs/export"),null,401);
  }
  @Test void auditFailureRollsBackUserStatusVersionAndRefreshRevocation() throws Exception {
    var admin=account(Role.ADMIN);var target=account(Role.BRAND);var session=login(target);
    jdbc.execute("CREATE FUNCTION admin_audit_test_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.entity_id='"+target.getId()+"'::uuid AND NEW.action='CHANGE_USER_STATUS' THEN RAISE EXCEPTION 'audit test failure'; END IF; RETURN NEW; END $$");
    jdbc.execute("CREATE TRIGGER admin_audit_test_failure BEFORE INSERT ON audit_log FOR EACH ROW EXECUTE FUNCTION admin_audit_test_failure()");
    try{call(admin,patch("/admin/users/"+target.getId()+"/status"),Map.of("status","LOCKED","reason","Test"),500);}
    finally{jdbc.execute("DROP TRIGGER admin_audit_test_failure ON audit_log");jdbc.execute("DROP FUNCTION admin_audit_test_failure()");}
    var stored=users.findById(target.getId()).orElseThrow();assertThat(stored.getStatus().name()).isEqualTo("ACTIVE");assertThat(stored.getAuthVersion()).isZero();
    assertThat(jdbc.queryForObject("select count(*) from refresh_token where user_id=? and revoked_at is null",Long.class,target.getId())).isEqualTo(1);
    assertThat(jdbc.queryForObject("select count(*) from audit_log where entity_id=?",Long.class,target.getId())).isZero();
    callToken(session.path("accessToken").asText(),get("/auth/me"),null,200);
  }
}
