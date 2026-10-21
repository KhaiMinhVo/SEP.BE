package com.influencermatch.backend.security;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.*;
import com.influencermatch.backend.auth.service.GoogleAuthService;
import com.influencermatch.backend.brand.model.BrandProfile;
import com.influencermatch.backend.brand.repository.BrandProfileRepository;
import com.influencermatch.backend.campaign.enums.*;
import com.influencermatch.backend.campaign.model.Campaign;
import com.influencermatch.backend.campaign.repository.CampaignRepository;
import com.influencermatch.backend.creator.model.Creator;
import com.influencermatch.backend.creator.repository.CreatorRepository;
import com.influencermatch.backend.relationship.enums.ShortlistPriority;
import com.influencermatch.backend.relationship.model.ShortlistItem;
import com.influencermatch.backend.relationship.repository.ShortlistItemRepository;
import com.influencermatch.backend.user.enums.*;
import com.influencermatch.backend.user.model.User;
import com.influencermatch.backend.user.repository.UserRepository;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=validate", "spring.flyway.enabled=true",
    "application.bootstrap-admin.enabled=false", "application.oauth2.google.enabled=false",
    "application.auth.creator-mock.enabled=true", "spring.jpa.show-sql=false"})
@ActiveProfiles("dev") @AutoConfigureMockMvc @Testcontainers
class RolePermissionPostgresIntegrationTest {
  @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
      DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
  @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", postgres::getJdbcUrl);
    r.add("spring.datasource.username", postgres::getUsername);
    r.add("spring.datasource.password", postgres::getPassword);
  }
  @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired JwtTokenProvider jwt;
  @Autowired UserRepository users; @Autowired PasswordEncoder encoder; @Autowired JdbcTemplate jdbc;
  @Autowired BrandProfileRepository brands; @Autowired CampaignRepository campaigns;
  @Autowired CreatorRepository creators; @Autowired ShortlistItemRepository shortlist;
  @Autowired GoogleAuthService google;
  User admin;

  @BeforeEach void setup() {
    jdbc.update("delete from role_permission_override");
    jdbc.update("update role_permission_config set version=0");
    admin = user(Role.ADMIN);
  }
  User user(Role role) { return users.saveAndFlush(User.builder().email(UUID.randomUUID()+"@permissions.test")
      .fullName("Permission Test").passwordHash(encoder.encode("Password123!"))
      .role(role).status(UserStatus.ACTIVE).build()); }
  String token(User user) { return jwt.generateToken(user); }
  JsonNode call(String access, MockHttpServletRequestBuilder req, Object body, int status) throws Exception {
    if (access != null) req.header("Authorization", "Bearer " + access);
    if (body != null) req.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body));
    var response = mvc.perform(req).andExpect(status().is(status)).andReturn().getResponse();
    return response.getContentAsString().isEmpty() ? json.nullNode() : json.readTree(response.getContentAsString());
  }
  Object update(long version, String permission, boolean enabled) {
    return Map.of("version",version,"changes",List.of(Map.of("permission",permission,"enabled",enabled)));
  }
  JsonNode setPermission(Role role, long version, Permission p, boolean enabled) throws Exception {
    return call(token(admin), patch("/admin/role-permissions/"+role),update(version,p.name(),enabled),200).path("data");
  }
  long auditCount() { return jdbc.queryForObject("select count(*) from audit_log where action='UPDATE_ROLE_PERMISSIONS'",Long.class); }

  @Test void catalogShowsDefaultsEditableCapabilitiesAndUnimplementedEntries() throws Exception {
    var data = call(token(admin),get("/admin/role-permissions"),null,200).path("data");
    assertThat(data.size()).isEqualTo(Role.values().length);
    for (var role : data) for (var p : role.path("permissions")) {
      var defaults = RolePermissions.forRole(Role.valueOf(role.path("role").asText()));
      assertThat(p.path("defaultEnabled").asBoolean()).isEqualTo(defaults.contains(Permission.valueOf(p.path("permission").asText())));
      assertThat(p.path("enabled")).isEqualTo(p.path("defaultEnabled"));
      if (role.path("role").asText().equals("ADMIN") || !p.path("implemented").asBoolean())
        assertThat(p.path("editable").asBoolean()).isFalse();
    }
    assertThat(jdbc.queryForObject("select count(*) from role_permission_config",Long.class)).isEqualTo(3);
    assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where version='11' and success",Long.class)).isEqualTo(1);
  }

  @ParameterizedTest @EnumSource(value=Role.class,names={"BRAND","CREATOR","DATA_MANAGER"})
  void onlyAdminCanReadOrModify(Role role) throws Exception {
    String access=token(user(role));
    call(access,get("/admin/role-permissions"),null,403);
    call(access,patch("/admin/role-permissions/BRAND"),update(0,"CREATE_CAMPAIGN",false),403);
  }
  @Test void anonymousIsRejected() throws Exception {
    call(null,get("/admin/role-permissions"),null,401);
    call(null,patch("/admin/role-permissions/BRAND"),update(0,"CREATE_CAMPAIGN",false),401);
  }

  @ParameterizedTest @ValueSource(strings={
    "{\"version\":0,\"changes\":[]}",
    "{\"version\":-1,\"changes\":[{\"permission\":\"CREATE_CAMPAIGN\",\"enabled\":false}]}",
    "{\"changes\":[{\"permission\":\"CREATE_CAMPAIGN\",\"enabled\":false}]}",
    "{\"version\":0,\"changes\":[null]}",
    "{\"version\":0,\"changes\":[{\"permission\":null,\"enabled\":false}]}",
    "{\"version\":0,\"changes\":[{\"permission\":\"CREATE_CAMPAIGN\"}]}",
    "{\"version\":0,\"changes\":[{\"permission\":\"UNKNOWN\",\"enabled\":false}]}",
    "{\"version\":0,\"changes\":[{\"permission\":\"ASSIGN_USER_ROLE\",\"enabled\":true}]}",
    "{\"version\":0,\"changes\":[{\"permission\":\"VIEW_OWN_NOTIFICATION\",\"enabled\":false}]}",
    "{\"version\":0,\"changes\":[{\"permission\":\"CREATE_CAMPAIGN\",\"enabled\":false},{\"permission\":\"CREATE_CAMPAIGN\",\"enabled\":true}]}"
  }) void invalidChangesDoNotWriteOrAudit(String body) throws Exception {
    long count=auditCount();
    mvc.perform(patch("/admin/role-permissions/BRAND").header("Authorization","Bearer "+token(admin))
        .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
    assertThat(auditCount()).isEqualTo(count);
    assertThat(jdbc.queryForObject("select count(*) from role_permission_override",Long.class)).isZero();
    assertThat(jdbc.queryForObject("select sum(version) from role_permission_config",Long.class)).isZero();
  }

  @Test void adminAndUnknownRolesCannotBeEdited() throws Exception {
    long count=auditCount();
    for (String role : List.of("ADMIN","UNKNOWN"))
      call(token(admin),patch("/admin/role-permissions/"+role),update(0,"VIEW_USER",false),400);
    assertThat(auditCount()).isEqualTo(count);
    call(token(admin),get("/admin/users"),null,200);
  }

  @Test void sameTokenSeesNewAuthoritiesAndServiceChecksThenCanBeReenabled() throws Exception {
    var brand=user(Role.BRAND); String access=token(brand);
    call(access,get("/auth/me"),null,200);
    setPermission(Role.BRAND,0,Permission.VIEW_OWN_BRAND_DATA,false);
    call(access,get("/brands/me"),null,403);
    var me=call(access,get("/auth/me"),null,200).path("data").path("permissions");
    assertThat(me.toString()).doesNotContain("VIEW_OWN_BRAND_DATA");
    setPermission(Role.BRAND,1,Permission.VIEW_OWN_BRAND_DATA,true);
    // The request reaches the service again, which reports the missing profile instead of a permission error.
    call(access,get("/brands/me"),null,404);
    setPermission(Role.BRAND,2,Permission.VIEW_OWN_PROFILE,false);
    call(access,get("/auth/me"),null,403);
    setPermission(Role.BRAND,3,Permission.VIEW_OWN_PROFILE,true);
    call(access,get("/auth/me"),null,200);
    assertThat(jwt.isTokenValid(access,users.findById(brand.getId()).orElseThrow())).isTrue();
  }

  @Test void shortlistAndRelationshipTogglesProtectOldEndpointsAndCompare() throws Exception {
    var brand=user(Role.BRAND); String access=token(brand);
    var profile=brands.saveAndFlush(BrandProfile.builder().user(brand).businessName("Test").build());
    var campaign=campaigns.saveAndFlush(Campaign.builder().brandProfile(profile).name("Test")
        .productService("Product").objective(CampaignObjective.AWARENESS).status(CampaignStatus.DRAFT).build());
    var ids=new ArrayList<UUID>();
    for(int i=0;i<2;i++) {
      var creator=creators.saveAndFlush(Creator.builder().platform("TIKTOK").externalId(UUID.randomUUID().toString()).userName("koc").build());
      ids.add(creator.getId());
      shortlist.saveAndFlush(ShortlistItem.builder().campaign(campaign).creator(creator).user(brand).priority(ShortlistPriority.MEDIUM).addedAt(java.time.LocalDateTime.now()).build());
    }
    String path="/campaigns/"+campaign.getId();
    call(access,get(path+"/shortlist"),null,200);
    call(access,post(path+"/shortlist/compare"),Map.of("creatorIds",ids),200);
    setPermission(Role.BRAND,0,Permission.MANAGE_OWN_SHORTLIST,false);
    call(access,get(path+"/shortlist"),null,403);
    call(access,post(path+"/shortlist"),Map.of("creatorId",ids.get(0),"priority","MEDIUM"),403);
    call(access,delete(path+"/shortlist/"+UUID.randomUUID()),null,403);
    call(access,post(path+"/shortlist/compare"),Map.of("creatorIds",ids),403);
    call(access,get(path),null,200);
    setPermission(Role.BRAND,1,Permission.MANAGE_OWN_SHORTLIST,true);
    call(access,post(path+"/shortlist/compare"),Map.of("creatorIds",ids),200);
    setPermission(Role.BRAND,2,Permission.MANAGE_OWN_RELATIONSHIP,false);
    call(access,get(path+"/relationships"),null,403);
    call(access,get(path+"/shortlist"),null,200);
  }

  @Test void creatorAndDataManagerUseUpdatedPermissionsWithoutNewToken() throws Exception {
    String creator=token(user(Role.CREATOR)), manager=token(user(Role.DATA_MANAGER));
    call(creator,get("/creator-claims/me"),null,200);
    call(manager,get("/admin/data-quality/pending"),null,200);
    setPermission(Role.CREATOR,0,Permission.VIEW_OWN_CREATOR_CLAIM,false);
    setPermission(Role.DATA_MANAGER,0,Permission.REVIEW_CREATOR_CLASSIFICATION,false);
    call(creator,get("/creator-claims/me"),null,403);
    call(manager,get("/admin/data-quality/pending"),null,403);
    call(token(admin),get("/admin/data-quality/pending"),null,200);
    call(manager,get("/admin/users"),null,403);
    setPermission(Role.CREATOR,1,Permission.VIEW_OWN_CREATOR_CLAIM,true);
    setPermission(Role.DATA_MANAGER,1,Permission.REVIEW_CREATOR_CLASSIFICATION,true);
    call(creator,get("/creator-claims/me"),null,200);
    call(manager,get("/admin/data-quality/pending"),null,200);
  }

  @Test void loginRefreshGoogleAndMockResponsesContainEffectivePermissions() throws Exception {
    var brand=user(Role.BRAND);
    setPermission(Role.BRAND,0,Permission.MANAGE_OWN_SHORTLIST,false);
    var login=call(null,post("/auth/login"),Map.of("email",brand.getEmail(),"password","Password123!"),200).path("data");
    assertThat(login.path("user").path("permissions").toString()).doesNotContain("MANAGE_OWN_SHORTLIST");
    var refresh=call(null,post("/auth/refresh"),Map.of("refreshToken",login.path("refreshToken").asText()),200).path("data");
    assertThat(refresh.path("user").path("permissions")).isEqualTo(login.path("user").path("permissions"));
    String code=google.completeGoogleLogin(UUID.randomUUID().toString(),UUID.randomUUID()+"@google.test","Google Brand",true);
    var exchange=call(null,post("/auth/google/exchange"),Map.of("code",code),200).path("data");
    assertThat(exchange.path("user").path("permissions").toString()).doesNotContain("MANAGE_OWN_SHORTLIST");
    setPermission(Role.CREATOR,0,Permission.CLAIM_CREATOR_PROFILE,false);
    var mock=call(null,post("/auth/dev/creator-login"),null,200).path("data");
    assertThat(mock.path("user").path("permissions").toString()).doesNotContain("CLAIM_CREATOR_PROFILE");
    call(refresh.path("accessToken").asText(),post("/auth/logout"),Map.of("refreshToken",refresh.path("refreshToken").asText()),204);
  }

  @Test void versionAuditAndNoopAreConsistent() throws Exception {
    long count=auditCount();
    var result=setPermission(Role.BRAND,0,Permission.CREATE_CAMPAIGN,false);
    assertThat(result.path("version").asLong()).isEqualTo(1);
    assertThat(auditCount()).isEqualTo(count+1);
    var row=jdbc.queryForMap("select user_id,old_value,new_value,created_at from audit_log where action='UPDATE_ROLE_PERMISSIONS' order by created_at desc limit 1");
    assertThat(row.get("user_id")).isEqualTo(admin.getId());
    assertThat(row.get("old_value").toString()).contains("CREATE_CAMPAIGN");
    assertThat(row.get("new_value").toString()).contains("BRAND");
    assertThat(row.get("created_at")).isNotNull();
    assertThat(setPermission(Role.BRAND,1,Permission.CREATE_CAMPAIGN,false).path("version").asLong()).isEqualTo(1);
    assertThat(auditCount()).isEqualTo(count+1);
    call(token(admin),patch("/admin/role-permissions/BRAND"),update(0,"CREATE_CAMPAIGN",true),409);
    assertThat(auditCount()).isEqualTo(count+1);
  }

  @Test void auditFailureRollsBackOverridesAndVersion() throws Exception {
    long count=auditCount();
    jdbc.execute("CREATE FUNCTION role_permission_test_audit_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.action='UPDATE_ROLE_PERMISSIONS' THEN RAISE EXCEPTION 'role permission audit test failure'; END IF; RETURN NEW; END $$");
    jdbc.execute("CREATE TRIGGER role_permission_test_audit_failure BEFORE INSERT ON audit_log FOR EACH ROW EXECUTE FUNCTION role_permission_test_audit_failure()");
    try {
      call(token(admin),patch("/admin/role-permissions/BRAND"),update(0,"CREATE_CAMPAIGN",false),500);
    } finally {
      jdbc.execute("DROP TRIGGER role_permission_test_audit_failure ON audit_log");
      jdbc.execute("DROP FUNCTION role_permission_test_audit_failure()");
    }
    assertThat(jdbc.queryForObject("select count(*) from role_permission_override",Long.class)).isZero();
    assertThat(jdbc.queryForObject("select version from role_permission_config where role='BRAND'",Long.class)).isZero();
    assertThat(auditCount()).isEqualTo(count);
  }

  @Test void concurrentAdminsCannotOverwriteEachOther() throws Exception {
    String first=token(admin),second=token(user(Role.ADMIN));
    var start=new CountDownLatch(1); var pool=Executors.newFixedThreadPool(2);
    long count=auditCount();
    try {
      List<Future<Integer>> futures=new ArrayList<>();
      for (String access : List.of(first,second)) futures.add(pool.submit(() -> {
        start.await();
        return mvc.perform(patch("/admin/role-permissions/BRAND").header("Authorization","Bearer "+access)
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(update(0,"CREATE_CAMPAIGN",false))))
            .andReturn().getResponse().getStatus();
      }));
      start.countDown();
      assertThat(List.of(futures.get(0).get(30,TimeUnit.SECONDS),futures.get(1).get(30,TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(200,409);
      assertThat(auditCount()).isEqualTo(count+1);
    } finally { pool.shutdownNow(); }
  }
  @Test void multiPermissionPatchIsAtomicAndIndependentAcrossRoles() throws Exception {
    var brand=user(Role.BRAND);String access=token(brand),creator=token(user(Role.CREATOR));long count=auditCount();
    var changes=Map.of("version",0,"changes",List.of(
        Map.of("permission","CREATE_CAMPAIGN","enabled",false),Map.of("permission","VIEW_CREATOR_PROFILE","enabled",false)));
    var saved=call(token(admin),patch("/admin/role-permissions/BRAND"),changes,200).path("data");
    assertThat(saved.path("version").asLong()).isEqualTo(1);assertThat(auditCount()).isEqualTo(count+1);
    var permissions=call(access,get("/auth/me"),null,200).path("data").path("permissions").toString();
    assertThat(permissions).doesNotContain("CREATE_CAMPAIGN","VIEW_CREATOR_PROFILE").contains("VIEW_PLAN");
    assertThat(call(creator,get("/auth/me"),null,200).path("data").path("permissions").toString()).contains("VIEW_CREATOR_PROFILE");
    var invalid=Map.of("version",1,"changes",List.of(
        Map.of("permission","CREATE_CAMPAIGN","enabled",true),Map.of("permission","ASSIGN_USER_ROLE","enabled",true)));
    call(token(admin),patch("/admin/role-permissions/BRAND"),invalid,400);
    assertThat(jdbc.queryForObject("select version from role_permission_config where role='BRAND'",Long.class)).isEqualTo(1);
    assertThat(jdbc.queryForObject("select enabled from role_permission_override where role='BRAND' and permission='CREATE_CAMPAIGN'",Boolean.class)).isFalse();
    assertThat(auditCount()).isEqualTo(count+1);
  }

  static java.util.stream.Stream<Arguments> protectedOperations() {
    return java.util.stream.Stream.of(
      Arguments.of(Role.BRAND,Permission.MANAGE_OWN_COLLABORATION,"POST","/campaigns/{id}/collaborations",
          Map.of("relationshipId",UUID.randomUUID(),"agreedFee",1,"currency","VND")),
      Arguments.of(Role.BRAND,Permission.RECORD_OWN_OUTCOME,"POST","/collaborations/{id}/outcome",
          Map.of("views",0,"publishedContentUrl","https://example.test/post")),
      Arguments.of(Role.BRAND,Permission.RECORD_OWN_CREATOR_REVIEW,"POST","/collaborations/{id}/review",
          Map.of("overallRating",5,"brandFitRating",5,"wouldCollaborateAgain",true)),
      Arguments.of(Role.CREATOR,Permission.CLAIM_CREATOR_PROFILE,"POST","/creators/{id}/claim",
          Map.of("evidenceUrls",List.of("https://example.test/proof"),"note","Test")),
      Arguments.of(Role.CREATOR,Permission.MANAGE_OWN_RATE_CARD,"PUT","/creators/{id}/rate-card",Map.of("items",List.of())),
      Arguments.of(Role.DATA_MANAGER,Permission.REVIEW_CREATOR_CLAIM,"PATCH","/data/creator-claims/{id}/review",
          Map.of("decision","APPROVED","reason","Verified")),
      Arguments.of(Role.BRAND,Permission.MANAGE_OWN_SUBSCRIPTION,"POST","/billing/subscribe",Map.of("planId",UUID.randomUUID())));
  }
  @ParameterizedTest @MethodSource("protectedOperations")
  void toggledBusinessOperationsAreForbiddenBeforeAnyResourceMutation(Role role,Permission permission,String method,String path,Object body) throws Exception {
    String access=token(user(role));setPermission(role,0,permission,false);long count=auditCount();
    long allAudits=jdbc.queryForObject("select count(*) from audit_log",Long.class);
    call(access,request(org.springframework.http.HttpMethod.valueOf(method),path.replace("{id}",UUID.randomUUID().toString())),body,403);
    assertThat(auditCount()).isEqualTo(count);assertThat(jdbc.queryForObject("select count(*) from audit_log",Long.class)).isEqualTo(allAudits);
  }

}
