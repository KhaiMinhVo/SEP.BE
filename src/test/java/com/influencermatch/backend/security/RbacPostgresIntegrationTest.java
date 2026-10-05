package com.influencermatch.backend.security;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.*;
import com.influencermatch.backend.audit.repository.AuditLogRepository;
import com.influencermatch.backend.billing.enums.*;
import com.influencermatch.backend.billing.repository.*;
import com.influencermatch.backend.billing.utils.VnPayUtil;
import com.influencermatch.backend.user.enums.*;
import com.influencermatch.backend.user.model.User;
import com.influencermatch.backend.user.repository.UserRepository;
import java.sql.DriverManager;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.junit.jupiter.api.Disabled;
import org.testcontainers.junit.jupiter.*;

@Disabled("Tạm thời vô hiệu hóa vì máy Tester không chạy Docker")
@SpringBootTest(
    properties = {
      "spring.jpa.hibernate.ddl-auto=validate", "spring.flyway.enabled=true",
      "application.bootstrap-admin.enabled=false", "application.oauth2.google.enabled=false",
      "CREATOR_INGESTION_API_KEY=integration-test-key", "application.vnpay.tmn-code=TEST",
      "application.vnpay.hash-secret=integration-test-signing-secret", "spring.jpa.show-sql=false"
    })
@AutoConfigureMockMvc
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RbacPostgresIntegrationTest {
  @Container
  static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) throws Exception {
    if (!postgres.isRunning()) postgres.start();
    Flyway.configure()
        .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
        .target("3")
        .load()
        .migrate();
    try (var conn =
            DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        var stmt = conn.createStatement()) {
      stmt.execute(
          "INSERT INTO \"user\"(id,email,password_hash,full_name,role,status,created_at,updated_at)"
              + " VALUES"
              + " ('00000000-0000-0000-0000-000000000004','legacy@rbac.test','preserved','Legacy','BRAND','ACTIVE',now(),now())");
    }
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired UserRepository users;
  @Autowired PasswordEncoder encoder;
  @Autowired JdbcTemplate jdbc;
  @Autowired AuditLogRepository audits;
  @Autowired PaymentRepository payments;
  @Autowired SubscriptionRepository subscriptions;
  @Test
  @Order(1)
  void migrationUpgradeAndEmptyDatabasePreserveDataAndValidate() {
    User legacy = users.findByEmail("legacy@rbac.test").orElseThrow();
    assertThat(legacy.getPasswordHash()).isEqualTo("preserved");
    assertThat(legacy.getAuthVersion()).isZero();
    var flyway =
        Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .schemas("empty_rbac")
            .defaultSchema("empty_rbac")
            .load();
    assertThat(flyway.migrate().migrationsExecuted).isEqualTo(4);
    assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
  }

  @Test
  @Order(2)
  void roleChangeRevokesAccessAndRefreshAndManagerCannotAccessPrivateApis() throws Exception {
    var admin = account(Role.ADMIN);
    var target = account(Role.BRAND);
    String original = login(target).get("accessToken").asText();
    String refresh = login(target).get("refreshToken").asText();
    String adminToken = login(admin).get("accessToken").asText();
    call(
        patch("/admin/users/" + target.getId() + "/role"),
        adminToken,
        "{\"role\":\"DATA_MANAGER\",\"reason\":\"Assign data operations\"}",
        200);
    call(get("/auth/me"), original, null, 401);
    call(post("/auth/refresh"), null, "{\"refreshToken\":\"" + refresh + "\"}", 401);
    var next = login(target);
    assertThat(next.at("/user/permissions").toString())
        .contains("REFRESH_CREATOR_DATA")
        .doesNotContain("MANAGE_PLAN");
    String token = next.get("accessToken").asText();
    for (String route :
        List.of(
            "/admin/users",
            "/admin/audit-logs",
            "/brands",
            "/campaigns/00000000-0000-0000-0000-000000000004",
            "/plans")) call(get(route), token, null, 403);
    call(get("/auth/me"), token, null, 200);
    assertThat(users.findById(target.getId()).orElseThrow().getAuthVersion()).isEqualTo(1);
    JsonNode auditPage =
        call(
            get("/admin/audit-logs")
                .param("actorId", admin.getId().toString())
                .param("action", "ASSIGN_USER_ROLE")
                .param("entityId", target.getId().toString()),
            adminToken,
            null,
            200);
    assertThat(auditPage.get("totalElements").asInt()).isEqualTo(1);
    assertThat(auditPage.toString()).doesNotContain("RbacTest123!", "passwordHash", "refreshToken");
    assertThat(audits.findAll())
        .anyMatch(
            a ->
                a.getEntityId().equals(target.getId())
                    && a.getAction().equals("ASSIGN_USER_ROLE")
                    && a.getUserId().equals(admin.getId()));
  }

  @Test
  @Order(3)
  void brandOwnershipAndAdminReadOnlySupportAreEnforced() throws Exception {
    var brand = account(Role.BRAND);
    var other = account(Role.BRAND);
    var admin = account(Role.ADMIN);
    String owner = login(brand).get("accessToken").asText(),
        stranger = login(other).get("accessToken").asText();
    String support = login(admin).get("accessToken").asText();
    String id =
        call(post("/brands"), owner, "{\"businessName\":\"RBAC Brand\"}", 201).get("id").asText();
    call(get("/brands/" + id), stranger, null, 403);
    call(get("/brands/" + id), support, null, 200);
    call(put("/brands/" + id), support, "{\"businessName\":\"Wrong write\"}", 403);
    call(post("/brands"), support, "{\"businessName\":\"Wrong create\"}", 403);
    call(
        patch("/admin/users/" + brand.getId() + "/role"),
        support,
        "{\"role\":\"DATA_MANAGER\",\"reason\":\"Not permitted with profile\"}",
        409);
    var campaign =
        call(
            post("/brands/" + id + "/campaigns"),
            owner,
            "{\"name\":\"Campaign\",\"productService\":\"Product\","
                + "\"objective\":\"AWARENESS\",\"platforms\":[\"TIKTOK\"]}",
            201);
    String cid = campaign.get("id").asText();
    call(get("/campaigns/" + cid), stranger, null, 403);
    call(get("/campaigns/" + cid), support, null, 200);
    call(patch("/campaigns/" + cid + "/archive"), support, null, 403);
    call(get("/brands/" + id + "/campaigns"), stranger, null, 403);
    call(get("/admin/users"), owner, null, 403);
    call(get("/subscriptions/brand/" + id), stranger, null, 403);
  }

  @Test
  @Order(4)
  void lockUnlockNeverRestoresOldTokenAndNoOpDoesNotInvalidate() throws Exception {
    var admin = account(Role.ADMIN);
    var target = account(Role.BRAND);
    String adminToken = login(admin).get("accessToken").asText(),
        old = login(target).get("accessToken").asText();
    String url = "/admin/users/" + target.getId() + "/status";
    call(patch(url), adminToken, "{\"status\":\"ACTIVE\"}", 200);
    call(get("/auth/me"), old, null, 200);
    call(patch(url), adminToken, "{\"status\":\"LOCKED\",\"reason\":\"Test lock\"}", 200);
    call(get("/auth/me"), old, null, 401);
    call(post("/auth/login"), null, credentials(target), 401);
    call(patch(url), adminToken, "{\"status\":\"ACTIVE\",\"reason\":\"Test unlock\"}", 200);
    call(get("/auth/me"), old, null, 401);
    assertThat(login(target).get("accessToken").asText()).isNotBlank();
  }

  @Test
  @Order(5)
  void ingestionIsServiceOnlyAndCannotAuthorizeOtherApis() throws Exception {
    String token = login(account(Role.ADMIN)).get("accessToken").asText();
    call(post("/creators/ingest"), token, "{}", 401);
    call(post("/creators/ingest").header("X-Service-Key", "wrong"), null, "{}", 401);
    call(get("/auth/me").header("X-Service-Key", "integration-test-key"), null, null, 401);
    call(
        post("/creators/ingest").header("X-Service-Key", "integration-test-key"),
        null,
        "{\"platform\":\"TIKTOK\",\"externalId\":\"rbac-creator\",\"userName\":\"creator\",\"displayName\":\"Creator\"}",
        200);
  }

  @Test
  @Order(6)
  void paidSubscriptionRequiresCheckoutAndGatewayVerification() throws Exception {
    var admin = account(Role.ADMIN);
    var brand = account(Role.BRAND);
    String adminToken = login(admin).get("accessToken").asText(),
        brandToken = login(brand).get("accessToken").asText();
    String brandId =
        call(post("/brands"), brandToken, "{\"businessName\":\"Billing Brand\"}", 201)
            .get("id")
            .asText();
    String planId =
        call(
                post("/plans"),
                adminToken,
                "{\"planName\":\"RBAC paid"
                    + " plan\",\"price\":100000,\"currency\":\"VND\",\"durationDays\":30}",
                201)
            .get("id")
            .asText();
    call(
        post("/subscriptions"),
        brandToken,
        "{\"brandId\":\"" + brandId + "\",\"planId\":\"" + planId + "\"}",
        409);
    call(post("/billing/subscribe"), adminToken, "{\"planId\":\"" + planId + "\"}", 403);
    call(post("/billing/subscribe"), brandToken, "{\"planId\":\"" + planId + "\"}", 200);
    var payment =
        payments.findAll().stream()
            .filter(p -> p.getBrandProfile().getId().toString().equals(brandId))
            .findFirst()
            .orElseThrow();
    Map<String, String> fields = new HashMap<>();
    fields.put("vnp_TmnCode", "TEST");
    fields.put("vnp_TxnRef", payment.getTransactionCode());
    fields.put("vnp_Amount", "10000000");
    fields.put("vnp_ResponseCode", "00");
    fields.put("vnp_TransactionStatus", "00");
    fields.put("vnp_SecureHash", "a".repeat(128));
    var invalid = get("/billing/vnpay/return");
    fields.forEach(invalid::param);
    mvc.perform(invalid).andExpect(status().isBadRequest());
    assertThat(payments.findById(payment.getId()).orElseThrow().getStatus())
        .isEqualTo(PaymentStatus.PENDING);
    fields.remove("vnp_SecureHash");
    fields.put("vnp_Amount", "1");
    fields.put(
        "vnp_SecureHash", VnPayUtil.hashAllFields(fields, "integration-test-signing-secret"));
    var wrongAmount = get("/billing/vnpay/return");
    fields.forEach(wrongAmount::param);
    mvc.perform(wrongAmount).andExpect(status().isBadRequest());
    fields.remove("vnp_SecureHash");
    fields.put("vnp_Amount", "10000000");
    fields.put("vnp_TmnCode", "OTHER");
    fields.put(
        "vnp_SecureHash", VnPayUtil.hashAllFields(fields, "integration-test-signing-secret"));
    var wrongMerchant = get("/billing/vnpay/return");
    fields.forEach(wrongMerchant::param);
    mvc.perform(wrongMerchant).andExpect(status().isBadRequest());
    assertThat(payments.findById(payment.getId()).orElseThrow().getStatus())
        .isEqualTo(PaymentStatus.PENDING);
    fields.remove("vnp_SecureHash");
    fields.put("vnp_TmnCode", "TEST");
    fields.put(
        "vnp_SecureHash", VnPayUtil.hashAllFields(fields, "integration-test-signing-secret"));
    var valid = get("/billing/vnpay/ipn");
    fields.forEach(valid::param);
    mvc.perform(valid).andExpect(status().isOk()).andExpect(jsonPath("$.RspCode").value("00"));
    mvc.perform(valid).andExpect(status().isOk()).andExpect(jsonPath("$.RspCode").value("00"));
    assertThat(payments.findById(payment.getId()).orElseThrow().getStatus())
        .isEqualTo(PaymentStatus.PAID);
    assertThat(subscriptions.findById(payment.getSubscription().getId()).orElseThrow().getStatus())
        .isEqualTo(SubscriptionStatus.ACTIVE);

    String otherToken = login(account(Role.BRAND)).get("accessToken").asText();
    String otherBrandId =
        call(post("/brands"), otherToken, "{\"businessName\":\"Failed Payment Brand\"}", 201)
            .get("id")
            .asText();
    call(post("/billing/subscribe"), otherToken, "{\"planId\":\"" + planId + "\"}", 200);
    var failedPayment =
        payments.findAll().stream()
            .filter(p -> p.getBrandProfile().getId().toString().equals(otherBrandId))
            .findFirst()
            .orElseThrow();
    fields.remove("vnp_SecureHash");
    fields.put("vnp_TxnRef", failedPayment.getTransactionCode());
    fields.put("vnp_ResponseCode", "24");
    fields.put("vnp_TransactionStatus", "02");
    fields.put(
        "vnp_SecureHash", VnPayUtil.hashAllFields(fields, "integration-test-signing-secret"));
    var declined = get("/billing/vnpay/return");
    fields.forEach(declined::param);
    mvc.perform(declined)
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("not completed")));
    assertThat(payments.findById(failedPayment.getId()).orElseThrow().getStatus())
        .isEqualTo(PaymentStatus.FAILED);
    assertThat(
            subscriptions
                .findById(failedPayment.getSubscription().getId())
                .orElseThrow()
                .getStatus())
        .isEqualTo(SubscriptionStatus.CANCELLED);
  }

  @Test
  @Order(99)
  void concurrentChangesCannotRemoveLastActiveAdministrator() throws Exception {
    jdbc.update("UPDATE \"user\" SET status='DISABLED' WHERE role='ADMIN'");
    var first = account(Role.ADMIN);
    var second = account(Role.ADMIN);
    String token = login(first).get("accessToken").asText();
    ExecutorService threads = Executors.newFixedThreadPool(2);
    CountDownLatch start = new CountDownLatch(1);
    try {
      var tasks =
          List.of(first, second).stream()
              .map(
                  target ->
                      threads.submit(
                          () -> {
                            start.await();
                            return mvc.perform(
                                    patch("/admin/users/" + target.getId() + "/status")
                                        .header("Authorization", "Bearer " + token)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                            "{\"status\":\"DISABLED\",\"reason\":\"Concurrent"
                                                + " protection test\"}"))
                                .andReturn()
                                .getResponse()
                                .getStatus();
                          }))
              .toList();
      start.countDown();
      var results =
          List.of(tasks.get(0).get(20, TimeUnit.SECONDS), tasks.get(1).get(20, TimeUnit.SECONDS));
      assertThat(results).contains(200).allMatch(s -> s == 200 || s == 409 || s == 401);
      assertThat(users.countByRoleAndStatus(Role.ADMIN, UserStatus.ACTIVE)).isEqualTo(1);
      var remaining =
          users.findAll().stream()
              .filter(u -> u.getRole() == Role.ADMIN && u.getStatus() == UserStatus.ACTIVE)
              .findFirst()
              .orElseThrow();
      call(
          patch("/admin/users/" + remaining.getId() + "/role"),
          login(remaining).get("accessToken").asText(),
          "{\"role\":\"DATA_MANAGER\",\"reason\":\"Cannot demote last admin\"}",
          409);
    } finally {
      threads.shutdownNow();
    }
  }

  @Test
  @Order(7)
  void swaggerDistinguishesPublicJwtAndServiceKeyOperations() throws Exception {
    var result = mvc.perform(get("/api-docs")).andExpect(status().isOk()).andReturn();
    var docs = json.readTree(result.getResponse().getContentAsString());
    assertThat(docs.at("/paths/~1auth~1login/post/security").isEmpty()).isTrue();
    assertThat(docs.at("/paths/~1creators~1ingest/post/security/0/CreatorServiceKey").isArray())
        .isTrue();
    assertThat(docs.at("/components/securitySchemes/CreatorServiceKey/name").asText())
        .isEqualTo("X-Service-Key");
    assertThat(docs.at("/paths/~1campaigns~1{id}/put/description").asText())
        .contains("UPDATE_CAMPAIGN");
  }

  private User account(Role role) {
    // Credentials below are disposable test fixtures, never real user secrets.
    return users.save(
        User.builder()
            .email(UUID.randomUUID() + "@rbac.test")
            .fullName("RBAC User")
            .passwordHash(encoder.encode("RbacTest123!"))
            .role(role)
            .status(UserStatus.ACTIVE)
            .build());
  }

  private String credentials(User user) {
    return "{\"email\":\"" + user.getEmail() + "\",\"password\":\"RbacTest123!\"}";
  }

  private JsonNode login(User user) throws Exception {
    return call(post("/auth/login"), null, credentials(user), 200);
  }

  private JsonNode call(MockHttpServletRequestBuilder req, String token, String body, int expected)
      throws Exception {
    if (token != null) req.header("Authorization", "Bearer " + token);
    if (body != null) req.contentType(MediaType.APPLICATION_JSON).content(body);
    var result = mvc.perform(req).andExpect(status().is(expected)).andReturn();
    String content = result.getResponse().getContentAsString();
    return content.isEmpty() ? json.nullNode() : json.readTree(content).path("data");
  }
}
