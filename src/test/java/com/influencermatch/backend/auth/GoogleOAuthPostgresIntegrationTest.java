package com.influencermatch.backend.auth;

import com.influencermatch.backend.auth.repository.ExternalIdentityRepository;
import com.influencermatch.backend.auth.service.GoogleAuthService;
import com.influencermatch.backend.exception.BusinessException;
import com.influencermatch.backend.exception.ErrorCode;
import com.influencermatch.backend.user.repository.UserRepository;
import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import org.junit.jupiter.api.Disabled;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Disabled("Tạm thời vô hiệu hóa vì máy Tester không chạy Docker")
@SpringBootTest(properties = {
    "spring.jpa.hibernate.ddl-auto=validate", "spring.flyway.enabled=true",
    "application.bootstrap-admin.enabled=false", "application.oauth2.google.enabled=false"
})
@AutoConfigureMockMvc
@Testcontainers
class GoogleOAuthPostgresIntegrationTest {
  @Container
  static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) throws Exception {
    Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
        .target("2").load().migrate();
    try (var connection = DriverManager.getConnection(
        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        var statement = connection.createStatement()) {
      statement.execute("""
          INSERT INTO "user" (id, email, password_hash, full_name, role, status, created_at, updated_at)
          VALUES ('00000000-0000-0000-0000-000000000001', 'existing@test.local',
                  'existing-hash', 'Existing Brand', 'BRAND', 'ACTIVE', now(), now())
          """);
    }
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

  @Autowired GoogleAuthService google;
  @Autowired UserRepository users;
  @Autowired ExternalIdentityRepository identities;
  @Autowired MockMvc mvc;

  @Test
  void upgradePreservesExistingPasswordAndLinksExistingBrand() {
    var original = users.findByEmail("existing@test.local").orElseThrow();
    String code = google.completeGoogleLogin("existing-sub", original.getEmail(), "Brand", true);
    var response = google.exchange(code);
    assertThat(response.getUser().getId()).isEqualTo(original.getId());
    assertThat(users.findById(original.getId()).orElseThrow().getPasswordHash()).isEqualTo("existing-hash");
  }

  @Test
  void googleOnlyUserCanExchangeOnceAndUseJwt() throws Exception {
    String code = google.completeGoogleLogin("new-sub", "new@test.local", "New Brand", true);
    var response = google.exchange(code);
    assertThat(users.findByEmail("new@test.local").orElseThrow().getPasswordHash()).isNull();
    assertThat(response.getRefreshToken()).isNotBlank();
    assertThat(response.getUser().getLastLoginAt()).isNotNull();
    assertThatThrownBy(() -> google.exchange(code)).isInstanceOfSatisfying(BusinessException.class,
        e -> assertThat(e.code()).isEqualTo(ErrorCode.AUTH_CODE_INVALID_OR_EXPIRED));
    mvc.perform(get("/auth/me").header("Authorization", "Bearer " + response.getAccessToken()))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.email").value("new@test.local"));
  }

  @Test
  void identityCannotBeDuplicated() {
    google.completeGoogleLogin("unique-sub", "unique@test.local", "Unique Brand", true);
    long count = identities.count();
    google.completeGoogleLogin("unique-sub", "unique@test.local", "Unique Brand", true);
    assertThat(identities.count()).isEqualTo(count);
  }

  @Test
  void exchangeIsPublicButRejectsInvalidCode() throws Exception {
    mvc.perform(post("/auth/google/exchange").contentType(MediaType.APPLICATION_JSON)
        .content("{\"code\":\"invalid-code\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTH_CODE_INVALID_OR_EXPIRED"));
  }
}
