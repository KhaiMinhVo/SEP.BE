package com.influencermatch.backend.user;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.*;
import com.influencermatch.backend.audit.service.AuditService;
import com.influencermatch.backend.auth.service.*;
import com.influencermatch.backend.security.jwt.JwtTokenProvider;
import com.influencermatch.backend.security.permission.*;
import com.influencermatch.backend.user.enums.*;
import com.influencermatch.backend.user.model.User;
import com.influencermatch.backend.user.repository.UserRepository;
import com.influencermatch.backend.user.service.AdminAccessGuard;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;

/** Dedicated database: last-admin counts never observe or alter another module's fixtures. */
@SpringBootTest(
    properties = {
      "spring.jpa.hibernate.ddl-auto=validate",
      "spring.flyway.enabled=true",
      "application.bootstrap-admin.enabled=false",
      "application.oauth2.google.enabled=false",
      "application.auth.creator-mock.enabled=false",
      "application.claim-email.dispatch-enabled=false",
      "application.meta.cleanup-enabled=false",
      "application.tiktok.cleanup-enabled=false",
      "spring.jpa.show-sql=false"
    })
@ActiveProfiles("dev")
@AutoConfigureMockMvc
@Testcontainers
class AdminConsolePostgresIntegrationTest {
  @Container
  static final PostgreSQLContainer<?> DB =
      new PostgreSQLContainer<>(
          DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", DB::getJdbcUrl);
    r.add("spring.datasource.username", DB::getUsername);
    r.add("spring.datasource.password", DB::getPassword);
  }

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired UserRepository users;
  @Autowired JwtTokenProvider jwt;
  @Autowired AuthSessionStore sessions;
  @Autowired PasswordEncoder encoder;
  @Autowired JdbcTemplate db;
  @Autowired com.influencermatch.backend.creator.repository.CreatorRepository creators;
  @Autowired com.influencermatch.backend.creator.repository.CreatorMasterRepository masters;
  @MockBean Clock clock;
  @MockBean AuthSessionCleanup cleanup;
  @MockBean com.influencermatch.backend.creator.client.CrawlerServiceClient crawler;
  @SpyBean AdminAccessGuard guard;
  @SpyBean AuditService audit;
  @SpyBean RefreshTokenService refresh;
  final AtomicReference<Instant> now = new AtomicReference<>();
  User admin, other;

  @BeforeEach
  void fixture() {
    now.set(Instant.parse("2026-11-09T12:00:00Z"));
    when(clock.instant()).thenAnswer(i -> now.get());
    when(clock.getZone()).thenReturn(ZoneOffset.UTC);
    db.update("delete from role_permission_override");
    db.update("update role_permission_config set version=0");
    db.update("update \"user\" set status='LOCKED',auth_version=auth_version+1 where role='ADMIN'");
    admin = user(Role.ADMIN);
    other = user(Role.ADMIN);
  }

  User user(Role role) {
    return users.saveAndFlush(
        User.builder()
            .email(UUID.randomUUID() + "@admin.test")
            .fullName("Test " + UUID.randomUUID())
            .passwordHash(encoder.encode("Password123!"))
            .role(role)
            .status(UserStatus.ACTIVE)
            .emailVerified(true)
            .build());
  }

  JsonNode call(User actor, MockHttpServletRequestBuilder req, Object body, int expected)
      throws Exception {
    return callToken(actor == null ? null : jwt.generateToken(actor), req, body, expected);
  }

  JsonNode callToken(String token, MockHttpServletRequestBuilder req, Object body, int expected)
      throws Exception {
    if (token != null) req.header("Authorization", "Bearer " + token);
    if (body != null) req.contentType("application/json").content(json.writeValueAsBytes(body));
    var response =
        mvc.perform(req.with(com.influencermatch.backend.support.TestAuthHttp.request(body)))
            .andExpect(status().is(expected))
            .andReturn()
            .getResponse();
    return response.getContentAsString().isEmpty()
        ? json.nullNode()
        : json.readTree(response.getContentAsString());
  }

  JsonNode state(User actor, UUID target, String value, int expected) throws Exception {
    return call(
        actor,
        patch("/admin/users/" + target + "/status"),
        Map.of("status", value, "reason", "Admin reviewed status"),
        expected);
  }

  JsonNode role(User actor, UUID target, String value, int expected) throws Exception {
    return call(
        actor,
        patch("/admin/users/" + target + "/role"),
        Map.of("role", value, "reason", "Admin reviewed role"),
        expected);
  }

  long version(UUID id) {
    return users.findById(id).orElseThrow().getAuthVersion();
  }

  long auditCount(UUID id) {
    return db.queryForObject(
        "select count(*) from audit_log where entity_type='User' and entity_id=?", Long.class, id);
  }

  String token(RefreshTokenService.Issued issued, User u) {
    var s = sessions.read(issued.entity().getSessionId(), false);
    return jwt.generateToken(u, s.id(), s.expiresAt());
  }

  @Test
  void listFiltersLiteralSearchSortAndPaginationWithoutPrivateData() throws Exception {
    var a = user(Role.BRAND);
    a.setFullName("Exact_%! Name");
    a.setEmail("literal_%!" + UUID.randomUUID() + "@admin.test");
    users.saveAndFlush(a);
    var b = user(Role.BRAND);
    b.setFullName("ExactAB! Name");
    users.saveAndFlush(b);
    var data =
        call(
                admin,
                get("/admin/users")
                    .param("role", "BRAND")
                    .param("status", "ACTIVE")
                    .param("q", "EXACT_%!"),
                null,
                200)
            .path("data");
    assertThat(data.path("totalElements").asInt()).isEqualTo(1);
    assertThat(data.path("items").get(0).path("id").asText()).isEqualTo(a.getId().toString());
    assertThat(
            call(
                    admin,
                    get("/admin/users").param("q", a.getId().toString().toUpperCase(Locale.ROOT)),
                    null,
                    200)
                .at("/data/totalElements")
                .asLong())
        .isEqualTo(1);
    assertThat(
            call(
                    admin,
                    get("/admin/users").param("q", a.getEmail().toUpperCase(Locale.ROOT)),
                    null,
                    200)
                .at("/data/totalElements")
                .asLong())
        .isEqualTo(1);
    String response = call(admin, get("/admin/users/" + a.getId()), null, 200).toString();
    assertThat(response).doesNotContain("password", "token", "contacts", "authVersion", "bank");
    call(admin, get("/admin/users").param("size", "101"), null, 400);
    call(admin, get("/admin/users").param("page", "-1"), null, 400);
    call(admin, get("/admin/users").param("role", "UNKNOWN"), null, 400);
    call(admin, get("/admin/users").param("q", "x".repeat(201)), null, 400);
    db.update(
        "update \"user\" set created_at='2026-01-01 00:00:00' where id in (?,?)",
        a.getId(),
        b.getId());
    var ordered =
        call(admin, get("/admin/users").param("role", "BRAND").param("q", "Exact"), null, 200)
            .at("/data/items");
    assertThat(ordered.get(0).path("id").asText())
        .isGreaterThan(ordered.get(1).path("id").asText());
    var raw =
        mvc.perform(
                get("/admin/users").header("Authorization", "Bearer " + jwt.generateToken(admin)))
            .andReturn()
            .getResponse();
    assertThat(raw.getHeader("Cache-Control")).isEqualTo("private, no-store");
  }

  @ParameterizedTest
  @ValueSource(strings = {"/status", "/role"})
  void reasonsAreRequiredEvenForNoOp(String suffix) throws Exception {
    var target = user(Role.DATA_MANAGER);
    String field = suffix.equals("/status") ? "status" : "role",
        value = suffix.equals("/status") ? "ACTIVE" : "DATA_MANAGER";
    for (var reason : List.of("", "   ", "x".repeat(501))) {
      call(
          admin,
          patch("/admin/users/" + target.getId() + suffix),
          Map.of(field, value, "reason", reason),
          400);
    }
    call(admin, patch("/admin/users/" + target.getId() + suffix), Map.of(field, value), 400);
    assertThat(version(target.getId())).isZero();
    assertThat(auditCount(target.getId())).isZero();
  }

  @Test
  void noOpDoesNotInvalidateSessionsAndReasonIsTrimmed() throws Exception {
    var target = user(Role.DATA_MANAGER);
    var issued = refresh.issue(target);
    var original = token(issued, target);
    long audits = auditCount(target.getId());
    state(admin, target.getId(), "ACTIVE", 200);
    role(admin, target.getId(), "DATA_MANAGER", 200);
    assertThat(version(target.getId())).isZero();
    assertThat(auditCount(target.getId())).isEqualTo(audits);
    assertThat(sessions.read(issued.entity().getSessionId(), false).revoked()).isFalse();
    callToken(original, get("/auth/me"), null, 200);
    call(
        admin,
        patch("/admin/users/" + target.getId() + "/status"),
        Map.of("status", "LOCKED", "reason", "  Trim this  "),
        200);
    assertThat(
            db.queryForObject(
                "select description from audit_log where entity_id=? and"
                    + " action='CHANGE_USER_STATUS'",
                String.class,
                target.getId()))
        .isEqualTo("Trim this");
  }

  @ParameterizedTest
  @ValueSource(strings = {"LOCKED", "DISABLED"})
  void statusRevokesEverySessionAndUnlockCannotRestoreTokens(String status) throws Exception {
    var target = user(Role.BRAND);
    var one = refresh.issue(target);
    var two = refresh.issue(target);
    String old = token(one, target), oldTwo = token(two, target);
    state(admin, target.getId(), status, 200);
    assertThat(version(target.getId())).isEqualTo(1);
    assertThat(sessions.read(one.entity().getSessionId(), false).revoked()).isTrue();
    assertThat(sessions.read(two.entity().getSessionId(), false).revoked()).isTrue();
    callToken(old, get("/auth/me"), null, 401);
    callToken(oldTwo, get("/auth/me"), null, 401);
    callToken(
        null,
        post("/auth/refresh").cookie(new jakarta.servlet.http.Cookie("IM_REFRESH", one.raw())),
        null,
        401);
    state(admin, target.getId(), "ACTIVE", 200);
    assertThat(version(target.getId())).isEqualTo(2);
    callToken(old, get("/auth/me"), null, 401);
    callToken(
        null,
        post("/auth/refresh").cookie(new jakarta.servlet.http.Cookie("IM_REFRESH", two.raw())),
        null,
        401);
    call(
        null,
        post("/auth/login"),
        Map.of("email", target.getEmail(), "password", "Password123!"),
        200);
  }

  @Test
  void roleChangeRevokesSessionsWithoutDeletingBusinessIdentity() throws Exception {
    var target = user(Role.DATA_MANAGER);
    var issued = refresh.issue(target);
    String old = token(issued, target);
    role(admin, target.getId(), "ADMIN", 200);
    assertThat(version(target.getId())).isEqualTo(1);
    assertThat(sessions.read(issued.entity().getSessionId(), false).revoked()).isTrue();
    callToken(old, get("/auth/me"), null, 401);
    assertThat(users.findById(target.getId())).isPresent();
  }

  @ParameterizedTest
  @ValueSource(strings = {"BRAND_PROFILE", "OWNERSHIP", "PENDING_CLAIM"})
  void roleChangeKeepsBrandProfilesChannelOwnershipAndPendingClaims(String kind) throws Exception {
    var target = user(kind.equals("BRAND_PROFILE") ? Role.BRAND : Role.CREATOR);
    UUID channelId = null, masterId = null;
    if (kind.equals("BRAND_PROFILE"))
      call(target, post("/brands"), Map.of("businessName", "Keep profile"), 201);
    else {
      var c =
          com.influencermatch.backend.creator.model.Creator.builder()
              .platform("TIKTOK")
              .externalId(UUID.randomUUID().toString())
              .userName("role-test")
              .build();
      if (kind.equals("OWNERSHIP")) {
        var m =
            masters.saveAndFlush(
                com.influencermatch.backend.creator.model.CreatorMaster.builder()
                    .user(target)
                    .canonicalName("Owner")
                    .build());
        masterId = m.getId();
        c.setMaster(m);
        c.setSourceType(com.influencermatch.backend.creator.enums.CreatorSourceType.AUTHORIZED);
        c.setAuthorizedAt(LocalDateTime.now(clock));
      }
      channelId = creators.saveAndFlush(c).getId();
      if (kind.equals("PENDING_CLAIM"))
        call(
            target,
            post("/creators/" + channelId + "/claim"),
            Map.of("evidenceUrls", List.of("https://proof.test/role"), "note", "Pending claim"),
            201);
    }
    role(admin, target.getId(), "DATA_MANAGER", 409);
    assertThat(users.findById(target.getId()).orElseThrow().getRole()).isEqualTo(target.getRole());
    assertThat(version(target.getId())).isZero();
    assertThat(auditCount(target.getId())).isZero();
    if (kind.equals("OWNERSHIP"))
      assertThat(
              db.queryForObject("select master_id from creator where id=?", UUID.class, channelId))
          .isEqualTo(masterId);
    if (kind.equals("PENDING_CLAIM"))
      assertThat(
              db.queryForObject(
                  "select count(*) from creator_claim where claimant_id=? and status='PENDING'",
                  Long.class,
                  target.getId()))
          .isEqualTo(1);
    if (kind.equals("BRAND_PROFILE"))
      assertThat(
              db.queryForObject(
                  "select count(*) from brand_profile where user_id=?", Long.class, target.getId()))
          .isEqualTo(1);
  }

  @ParameterizedTest
  @ValueSource(strings = {"LOCKED", "DISABLED", "DEMOTE"})
  void selfActionSucceedsOnlyWhileAnotherAdminExists(String action) throws Exception {
    String access = jwt.generateToken(admin);
    var req =
        patch("/admin/users/" + admin.getId() + (action.equals("DEMOTE") ? "/role" : "/status"));
    callToken(
        access,
        req,
        Map.of(
            action.equals("DEMOTE") ? "role" : "status",
            action.equals("DEMOTE") ? "DATA_MANAGER" : action,
            "reason",
            "Self action confirmed"),
        200);
    callToken(access, get("/admin/users"), null, 401);
    call(other, get("/admin/users"), null, 200);
    assertThat(
            db.queryForObject(
                "select count(*) from \"user\" where role='ADMIN' and status='ACTIVE'", Long.class))
        .isEqualTo(1);
  }

  @ParameterizedTest
  @ValueSource(strings = {"LOCKED", "DISABLED", "DEMOTE"})
  void lastAdminCannotBeLockedDisabledOrDemoted(String action) throws Exception {
    state(admin, other.getId(), "LOCKED", 200);
    long before = version(admin.getId());
    if (action.equals("DEMOTE")) role(admin, admin.getId(), "DATA_MANAGER", 409);
    else state(admin, admin.getId(), action, 409);
    assertThat(version(admin.getId())).isEqualTo(before);
    assertThat(users.findById(admin.getId()).orElseThrow().getStatus())
        .isEqualTo(UserStatus.ACTIVE);
    assertThat(auditCount(admin.getId())).isZero();
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void simultaneousAdminsCannotRemoveEveryAdministrator(boolean self) throws Exception {
    var waiting = new CountDownLatch(2);
    var begin = new CountDownLatch(1);
    doAnswer(
            i -> {
              waiting.countDown();
              assertThat(begin.await(10, TimeUnit.SECONDS)).isTrue();
              return i.callRealMethod();
            })
        .when(guard)
        .lock(any(), any());
    var pool = Executors.newFixedThreadPool(2);
    try {
      String a = jwt.generateToken(admin), b = jwt.generateToken(other);
      var first = pool.submit(() -> rawStatus(a, self ? admin.getId() : other.getId()));
      var second = pool.submit(() -> rawStatus(b, self ? other.getId() : admin.getId()));
      assertThat(waiting.await(10, TimeUnit.SECONDS)).isTrue();
      begin.countDown();
      assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(200, self ? 409 : 403);
    } finally {
      begin.countDown();
      pool.shutdownNow();
    }
    assertThat(
            db.queryForObject(
                "select count(*) from \"user\" where role='ADMIN' and status='ACTIVE'", Long.class))
        .isEqualTo(1);
  }

  int rawStatus(String access, UUID target) throws Exception {
    return mvc.perform(
            patch("/admin/users/" + target + "/status")
                .header("Authorization", "Bearer " + access)
                .contentType("application/json")
                .content("{\"status\":\"LOCKED\",\"reason\":\"Concurrency test\"}"))
        .andReturn()
        .getResponse()
        .getStatus();
  }

  @ParameterizedTest
  @CsvSource({
    "LOCKED,false",
    "DEMOTED,false",
    "LOGOUT,false",
    "EXPIRED,false",
    "LOCKED,true",
    "DEMOTED,true",
    "LOGOUT,true",
    "EXPIRED,true"
  })
  void actorAuthorizationIsRecheckedAfterWaiting(String action, boolean matrix) throws Exception {
    var target = user(Role.BRAND);
    var issued = refresh.issue(admin);
    String access = token(issued, admin);
    var entered = new CountDownLatch(1);
    var proceed = new CountDownLatch(1);
    var pool = Executors.newSingleThreadExecutor();
    doAnswer(
            i -> {
              if (Permissions.actor().getId().equals(admin.getId())) {
                entered.countDown();
                assertThat(proceed.await(10, TimeUnit.SECONDS)).isTrue();
              }
              return i.callRealMethod();
            })
        .when(guard)
        .lock(any(), any());
    try {
      var work =
          pool.submit(
              () ->
                  matrix
                      ? mvc.perform(
                              patch("/admin/role-permissions/BRAND")
                                  .header("Authorization", "Bearer " + access)
                                  .contentType("application/json")
                                  .content(
                                      "{\"version\":0,\"changes\":[{\"permission\":\"CREATE_CAMPAIGN\",\"enabled\":false}]}"))
                          .andReturn()
                          .getResponse()
                          .getStatus()
                      : rawStatus(access, target.getId()));
      assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
      switch (action) {
        case "LOCKED" -> state(other, admin.getId(), "LOCKED", 200);
        case "DEMOTED" -> role(other, admin.getId(), "DATA_MANAGER", 200);
        case "LOGOUT" ->
            callToken(
                null,
                post("/auth/logout")
                    .cookie(new jakarta.servlet.http.Cookie("IM_REFRESH", issued.raw())),
                null,
                204);
        case "EXPIRED" -> now.set(sessions.read(issued.entity().getSessionId(), false).expiresAt());
        default -> throw new AssertionError();
      }
      proceed.countDown();
      assertThat(work.get(20, TimeUnit.SECONDS))
          .isEqualTo(Set.of("LOCKED", "DEMOTED").contains(action) ? 403 : 401);
    } finally {
      proceed.countDown();
      pool.shutdownNow();
    }
    assertThat(users.findById(target.getId()).orElseThrow().getStatus())
        .isEqualTo(UserStatus.ACTIVE);
    assertThat(auditCount(target.getId())).isZero();
    assertThat(
            db.queryForObject(
                "select version from role_permission_config where role='BRAND'", Long.class))
        .isZero();
    assertThat(db.queryForObject("select count(*) from role_permission_override", Long.class))
        .isZero();
  }

  @ParameterizedTest
  @ValueSource(strings = {"audit", "revocation"})
  void failureRollsBackStatusVersionAndRevokedSessions(String failure) throws Exception {
    var target = user(Role.DATA_MANAGER);
    var issued = refresh.issue(target);
    String old = token(issued, target);
    if (failure.equals("audit")) {
      var spy =
          (AuditService) org.springframework.test.util.AopTestUtils.getUltimateTargetObject(audit);
      doThrow(new IllegalStateException("test audit failure"))
          .when(spy)
          .recordAs(
              any(),
              eq("CHANGE_USER_STATUS"),
              anyString(),
              eq(target.getId()),
              any(),
              any(),
              anyString());
    } else {
      var spy =
          (RefreshTokenService)
              org.springframework.test.util.AopTestUtils.getUltimateTargetObject(refresh);
      doAnswer(
              i -> {
                i.callRealMethod();
                throw new IllegalStateException("test revocation failure");
              })
          .when(spy)
          .revokeAll(target.getId());
    }
    state(admin, target.getId(), "LOCKED", 500);
    assertThat(version(target.getId())).isZero();
    assertThat(users.findById(target.getId()).orElseThrow().getStatus())
        .isEqualTo(UserStatus.ACTIVE);
    assertThat(sessions.read(issued.entity().getSessionId(), false).revoked()).isFalse();
    assertThat(auditCount(target.getId())).isZero();
    callToken(old, get("/auth/me"), null, 200);
  }

  @ParameterizedTest
  @EnumSource(
      value = Role.class,
      names = {"BRAND", "CREATOR", "DATA_MANAGER"})
  void nonAdminsCannotReadOrWriteAccountsOrMatrix(Role role) throws Exception {
    var u = user(role);
    call(u, get("/admin/users"), null, 403);
    call(u, get("/admin/users/" + admin.getId()), null, 403);
    state(u, admin.getId(), "LOCKED", 403);
    call(u, get("/admin/role-permissions"), null, 403);
  }

  @ParameterizedTest
  @CsvSource({
    "BRAND,VIEW_OWN_HISTORY_AND_INSIGHTS,GET,/campaign-history,200",
    "BRAND,RUN_RECOMMENDATION,POST,/campaigns/{id}/ai-recommendations,404",
    "BRAND,VIEW_OWN_RECOMMENDATION,GET,/campaigns/{id}/ai-recommendations/runs,404",
    "BRAND,VIEW_CREATOR_CONTACT,GET,/creators/{id}/contacts,404",
    "CREATOR,MANAGE_OWN_CREATOR_PROFILE,PATCH,/creators/portal/profile,200",
    "CREATOR,RESPOND_OWN_COOPERATION,GET,/creators/portal/cooperation-deals/{id},404",
    "CREATOR,VIEW_OWN_NOTIFICATION,GET,/creators/portal/notifications,200",
    "CREATOR,MANAGE_OWN_NOTIFICATION,POST,/creators/portal/notifications/{id}/read,404",
    "DATA_MANAGER,VIEW_CREATOR_MASTER_CATALOG,GET,/data/creator-masters,200",
    "DATA_MANAGER,VIEW_CREATOR_IDENTITY_HISTORY,GET,/data/creator-identity-events,200"
  })
  void implementedCapabilitiesCanBeToggledThroughRealApiWithSameToken(
      Role role, Permission p, String method, String route, int enabled) throws Exception {
    var actor = user(role);
    String token = jwt.generateToken(actor);
    String path = route.replace("{id}", UUID.randomUUID().toString());
    var matrix = call(admin, get("/admin/role-permissions"), null, 200).path("data");
    var cap =
        java.util.stream.StreamSupport.stream(matrix.spliterator(), false)
            .filter(v -> v.path("role").asText().equals(role.name()))
            .findFirst()
            .orElseThrow()
            .path("permissions");
    var entry =
        java.util.stream.StreamSupport.stream(cap.spliterator(), false)
            .filter(v -> v.path("permission").asText().equals(p.name()))
            .findFirst()
            .orElseThrow();
    assertThat(entry.path("implemented").asBoolean()).isTrue();
    assertThat(entry.path("editable").asBoolean()).isTrue();
    access(token, actor, method, path, enabled);
    toggle(role, 0, p, false);
    access(token, actor, method, path, 403);
    toggle(role, 1, p, true);
    access(token, actor, method, path, enabled);
    assertThat(version(actor.getId())).isZero();
  }

  void access(String token, User actor, String method, String path, int expected) throws Exception {
    var req = request(org.springframework.http.HttpMethod.valueOf(method), path);
    if (path.equals("/creators/portal/profile"))
      req.header(
          "If-Match",
          com.influencermatch.backend.creator.portal.CreatorPortalProfiles.tag(actor.getId(), 0));
    callToken(token, req, method.equals("GET") ? null : Map.of(), expected);
  }

  JsonNode toggle(Role role, long version, Permission p, boolean enabled) throws Exception {
    return call(
        admin,
        patch("/admin/role-permissions/" + role),
        Map.of(
            "version",
            version,
            "changes",
            List.of(Map.of("permission", p.name(), "enabled", enabled))),
        200);
  }

  @Test
  void unavailableCapabilitiesAndAdminMatrixCannotBeEdited() throws Exception {
    for (var p :
        List.of(
            Permission.MANAGE_PROVIDER_CONFIG,
            Permission.MANAGE_RECOMMENDATION_CONFIG,
            Permission.VIEW_COLLECTION_JOB,
            Permission.RESOLVE_CREATOR_DUPLICATE)) {
      assertThat(PermissionCatalog.implemented(p)).isFalse();
    }
    call(
        admin,
        patch("/admin/role-permissions/DATA_MANAGER"),
        Map.of(
            "version",
            0,
            "changes",
            List.of(Map.of("permission", "MANAGE_RECOMMENDATION_CONFIG", "enabled", true))),
        400);
    call(
        admin,
        patch("/admin/role-permissions/ADMIN"),
        Map.of(
            "version", 0, "changes", List.of(Map.of("permission", "VIEW_USER", "enabled", false))),
        400);
  }
}
