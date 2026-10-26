package com.influencermatch.backend.creator.meta;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(
    properties = {
      "application.meta.enabled=true",
      "application.meta.app-id=123",
      "application.meta.app-secret=fb-secret",
      "application.meta.login-config-id=456",
      "application.meta.graph-api-version=v26.0",
      "application.meta.redirect-uri=http://localhost:8080/api/v1/integrations/meta/callback",
      "application.meta.encryption-key=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="
    })
class OAuthFlowCompatibilityPostgresIntegrationTest extends DirectInstagramTestSupport {
  MetaDtos.Proof facebookProof() {
    return new MetaDtos.Proof(
        "facebook-user",
        "private-fb-token",
        now.plusSeconds(3600),
        Set.of("pages_show_list", "pages_read_engagement", "instagram_basic"),
        List.of(new MetaDtos.Asset(channel.getExternalId(), "100", "only_ig", "only_ig")));
  }

  Started facebookStart() throws Exception {
    when(facebook.exchange(anyString(), eq("INSTAGRAM"))).thenReturn(facebookProof());
    when(facebook.verify(anyString(), eq("INSTAGRAM"))).thenReturn(facebookProof());
    return startAt(
        creator,
        "/creators/portal/accounts/connect/meta/start",
        Map.of("platform", "INSTAGRAM"),
        "www.facebook.com");
  }

  void facebookCallback(Started s, int expected) throws Exception {
    call(
        null,
        get("/integrations/meta/callback")
            .cookie(s.cookie())
            .param("state", s.state())
            .param("code", "fb-code"),
        null,
        expected);
  }

  @Test
  void callbacksCannotConsumeSessionsFromTheOtherFlow() throws Exception {
    var ig = startDirect(creator, Map.of());
    var fb = facebookStart();
    facebookCallback(ig, 400);
    callback(fb, 400);
    assertThat(sessions.findById(ig.id()).orElseThrow().getStatus()).isEqualTo("NEW");
    assertThat(sessions.findById(fb.id()).orElseThrow().getStatus()).isEqualTo("NEW");
    verifyNoInteractions(instagram);
    verify(facebook, never()).exchange(anyString(), anyString());
    callback(ig, 200);
    facebookCallback(fb, 200);
    assertThat(call(creator, get(path(fb)), null, 200).path("data").path("oauthFlow").asText())
        .isEqualTo("FACEBOOK_LOGIN");
  }

  @Test
  void directLoginReusesTheInstagramIdentityAlreadyVerifiedThroughFacebook() throws Exception {
    var fb = facebookStart();
    facebookCallback(fb, 200);
    String master =
        confirm(fb, channel.getExternalId(), 200).path("data").path("masterId").asText();
    UUID identity =
        jdbc.queryForObject(
            "select id from creator_provider_identity where creator_id=?",
            UUID.class,
            channel.getId());
    UUID verification =
        verifications.findByCreatorIdAndStatus(channel.getId(), "ACTIVE").getFirst().getId();
    long creatorCount = creators.count();
    var ig = readyDirect();
    assertThat(confirm(ig, channel.getExternalId(), 200).path("data").path("masterId").asText())
        .isEqualTo(master);
    assertThat(creators.count()).isEqualTo(creatorCount);
    assertThat(
            jdbc.queryForObject(
                "select id from creator_provider_identity where creator_id=?",
                UUID.class,
                channel.getId()))
        .isEqualTo(identity);
    var evidence = verifications.findByCreatorIdAndStatus(channel.getId(), "ACTIVE");
    assertThat(evidence).hasSize(1);
    assertThat(evidence.getFirst().getId()).isEqualTo(verification);
    assertThat(evidence.getFirst().getVerificationMethod()).isEqualTo("INSTAGRAM_OAUTH");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from creator_master where user_id=?", Long.class, creator.getId()))
        .isEqualTo(1L);
    assertThat(sessions.findById(fb.id()).orElseThrow().getStatus()).isEqualTo("COMPLETED");
  }

  @Test
  void differentCanonicalNamespaceNeverRewritesTheExistingChannelOrMatchesByHandle()
      throws Exception {
    var fb = facebookStart();
    facebookCallback(fb, 200);
    confirm(fb, channel.getExternalId(), 200);
    String before =
        jdbc.queryForObject(
            "select to_jsonb(c)::text from creator c where id=?", String.class, channel.getId());
    var mismatched =
        new com.influencermatch.backend.creator.meta.MetaDtos.Proof(
            "999",
            "private-ig-token",
            now.plusSeconds(600),
            Set.of("instagram_business_basic"),
            List.of(new MetaDtos.Asset("17849999999999", null, "only_ig", "only_ig")));
    when(instagram.exchange(anyString())).thenReturn(mismatched);
    when(instagram.verify(any())).thenReturn(mismatched);
    var ig = readyDirect();
    long count = creators.count();
    confirm(ig, "17849999999999", 409);
    assertThat(creators.count()).isEqualTo(count);
    assertThat(
            jdbc.queryForObject(
                "select to_jsonb(c)::text from creator c where id=?",
                String.class,
                channel.getId()))
        .isEqualTo(before);
    assertThat(verifications.findByCreatorIdAndStatus(channel.getId(), "ACTIVE")).hasSize(1);
  }

  @Test
  void v18PreservesV17ReadyPayloadOwnershipAndRejectsImpossibleFlowPlatformPairs() {
    String schema = "ig_upgrade_" + UUID.randomUUID().toString().replace("-", "");
    try {
      org.flywaydb.core.Flyway.configure()
          .dataSource(jdbc.getDataSource())
          .schemas(schema)
          .defaultSchema(schema)
          .target("17")
          .load()
          .migrate();
      UUID u = UUID.randomUUID(),
          m = UUID.randomUUID(),
          c = UUID.randomUUID(),
          s = UUID.randomUUID();
      jdbc.update(
          "insert into "
              + schema
              + ".\"user\"(id,email,full_name,role,status,email_verified,created_at,updated_at)"
              + " values(?,'old@example.test','Old','CREATOR','ACTIVE',true,now(),now())",
          u);
      jdbc.update(
          "insert into "
              + schema
              + ".creator_master(id,user_id,canonical_name,status,created_at,updated_at)"
              + " values(?,?,'Old','ACTIVE',now(),now())",
          m,
          u);
      jdbc.update(
          "insert into "
              + schema
              + ".creator(id,platform,external_id,user_name,master_id,source_type,authorized_at,created_at,updated_at)"
              + " values(?,'INSTAGRAM','1784000001','only_ig',?,'AUTHORIZED',now(),now(),now())",
          c,
          m);
      String payload = cipher.encrypt(facebookProof());
      jdbc.update(
          "insert into "
              + schema
              + ".meta_oauth_session(id,user_id,auth_version,platform,state_hash,browser_binding_hash,status,expires_at,target_creator_id,payload_ciphertext,created_at,updated_at)"
              + " values(?,?,0,'INSTAGRAM',?,?,'READY',?,?,?,now(),now())",
          s,
          u,
          "a".repeat(64),
          "b".repeat(64),
          java.sql.Timestamp.from(now.plusSeconds(600)),
          c,
          payload);
      String before =
          jdbc.queryForObject(
              "select to_jsonb(c)::text from " + schema + ".creator c where id=?", String.class, c);
      assertThat(
              org.flywaydb.core.Flyway.configure()
                  .dataSource(jdbc.getDataSource())
                  .schemas(schema)
                  .defaultSchema(schema)
                  .target("18")
                  .load()
                  .migrate()
                  .migrationsExecuted)
          .isEqualTo(1);
      assertThat(
              jdbc.queryForObject(
                  "select oauth_flow from " + schema + ".meta_oauth_session where id=?",
                  String.class,
                  s))
          .isEqualTo("FACEBOOK_LOGIN");
      assertThat(
              jdbc.queryForObject(
                  "select payload_ciphertext from " + schema + ".meta_oauth_session where id=?",
                  String.class,
                  s))
          .isEqualTo(payload);
      assertThat(cipher.decrypt(OAuthFlow.FACEBOOK_LOGIN, payload)).isEqualTo(facebookProof());
      assertThat(
              jdbc.queryForObject(
                  "select to_jsonb(c)::text from " + schema + ".creator c where id=?",
                  String.class,
                  c))
          .isEqualTo(before);
      assertThatThrownBy(
              () ->
                  jdbc.update(
                      "update "
                          + schema
                          + ".meta_oauth_session set"
                          + " oauth_flow='INSTAGRAM_LOGIN',platform='FACEBOOK' where id=?",
                      s))
          .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
      assertThat(
              jdbc.queryForObject(
                  "select oauth_flow from " + schema + ".meta_oauth_session where id=?",
                  String.class,
                  s))
          .isEqualTo("FACEBOOK_LOGIN");
      // Incoming be_vy migrations follow the already-applied local OAuth history.
      assertThat(
              org.flywaydb.core.Flyway.configure()
                  .dataSource(jdbc.getDataSource())
                  .schemas(schema)
                  .defaultSchema(schema)
                  .target("21")
                  .load()
                  .migrate()
                  .migrationsExecuted)
          .isEqualTo(3);
      assertThat(
              jdbc.queryForObject(
                  "select to_jsonb(c)::text from " + schema + ".creator c where id=?",
                  String.class,
                  c))
          .isEqualTo(before);
      assertThat(
              jdbc.queryForObject(
                  "select payload_ciphertext from " + schema + ".meta_oauth_session where id=?",
                  String.class,
                  s))
          .isEqualTo(payload);
      assertThat(
              jdbc.queryForObject(
                  "select oauth_flow from " + schema + ".meta_oauth_session where id=?",
                  String.class,
                  s))
          .isEqualTo("FACEBOOK_LOGIN");
      assertThat(
              jdbc.queryForObject(
                  "select to_regclass(?)::text", String.class, schema + ".recommendation_run"))
          .isNotNull();
      assertThat(
              jdbc.queryForList(
                  "select column_name from information_schema.columns where table_schema=? and"
                      + " table_name='campaign_outcome'",
                  String.class,
                  schema))
          .contains("likes", "comments", "shares");
      assertThat(
              jdbc.queryForObject(
                  "select is_nullable from information_schema.columns where table_schema=? and"
                      + " table_name='evidence_asset' and column_name='outcome_id'",
                  String.class,
                  schema))
          .isEqualTo("YES");
    } finally {
      jdbc.execute("drop schema if exists " + schema + " cascade");
    }
  }
}
