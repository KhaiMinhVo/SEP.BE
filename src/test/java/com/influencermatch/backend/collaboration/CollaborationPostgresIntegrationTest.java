package com.influencermatch.backend.collaboration;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.*;
import com.influencermatch.backend.brand.model.BrandProfile;
import com.influencermatch.backend.brand.repository.BrandProfileRepository;
import com.influencermatch.backend.campaign.enums.*;
import com.influencermatch.backend.campaign.model.Campaign;
import com.influencermatch.backend.campaign.repository.CampaignRepository;
import com.influencermatch.backend.collaboration.dto.*;
import com.influencermatch.backend.collaboration.enums.*;
import com.influencermatch.backend.collaboration.model.*;
import com.influencermatch.backend.collaboration.repository.*;
import com.influencermatch.backend.collaboration.service.CollaborationService;
import com.influencermatch.backend.creator.model.Creator;
import com.influencermatch.backend.creator.repository.CreatorRepository;
import com.influencermatch.backend.relationship.enums.RelationshipStage;
import com.influencermatch.backend.relationship.model.BrandCreatorRelationship;
import com.influencermatch.backend.relationship.repository.BrandCreatorRelationshipRepository;
import com.influencermatch.backend.security.jwt.JwtTokenProvider;
import com.influencermatch.backend.user.enums.*;
import com.influencermatch.backend.user.model.User;
import com.influencermatch.backend.user.repository.UserRepository;
import java.math.BigDecimal;
import java.sql.DriverManager;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(
    properties = {
      "spring.jpa.hibernate.ddl-auto=validate", "spring.flyway.enabled=true",
      "application.bootstrap-admin.enabled=false", "application.oauth2.google.enabled=false",
      "spring.jpa.show-sql=false", "logging.level.org.hibernate.SQL=WARN",
      "logging.level.com.influencermatch=INFO"
    })
@AutoConfigureMockMvc
@Testcontainers
class CollaborationPostgresIntegrationTest {
  @org.springframework.boot.test.mock.mockito.MockBean
  com.influencermatch.backend.creator.client.CrawlerServiceClient crawler;

  @Container static final PostgreSQLContainer<?> postgres = container();

  static PostgreSQLContainer<?> container() {
    return new PostgreSQLContainer<>(
        DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
  }

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) throws Exception {
    Flyway.configure()
        .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
        .target("7")
        .load()
        .migrate();
    try (var connection =
            DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        var sql = connection.createStatement()) {
      sql.execute(
          """
INSERT INTO "user"(id,email,password_hash,full_name,role,status,created_at,updated_at)
VALUES ('10000000-0000-0000-0000-000000000001','flow5-legacy@example.test','preserved-hash','Legacy','BRAND','ACTIVE',now(),now());
INSERT INTO brand_profile(id,user_id,business_name,created_at,updated_at)
VALUES ('10000000-0000-0000-0000-000000000002','10000000-0000-0000-0000-000000000001','Legacy Brand',now(),now());
INSERT INTO campaign(id,brand_profile_id,name,product_service,objective,status,created_at,updated_at)
VALUES ('10000000-0000-0000-0000-000000000003','10000000-0000-0000-0000-000000000002','Legacy','Product','AWARENESS','COMPLETED',now(),now());
INSERT INTO creator(id,platform,external_id,user_name,created_at,updated_at)
VALUES ('10000000-0000-0000-0000-000000000004','TIKTOK','legacy-flow5','legacy',now(),now());
INSERT INTO relationship(id,user_id,creator_id,campaign_id,stage,created_at,updated_at)
VALUES ('10000000-0000-0000-0000-000000000005','10000000-0000-0000-0000-000000000001','10000000-0000-0000-0000-000000000004','10000000-0000-0000-0000-000000000003','COMPLETED',now(),now());
INSERT INTO collaboration(id,relationship_id,campaign_id,creator_id,agreed_fee,currency,status,updated_at)
VALUES ('10000000-0000-0000-0000-000000000006','10000000-0000-0000-0000-000000000005','10000000-0000-0000-0000-000000000003','10000000-0000-0000-0000-000000000004',123000,'VND','COMPLETED',now());
INSERT INTO campaign_outcome(id,collaboration_id,kpi_type,kpi_target,kpi_actual,result_note)
VALUES ('10000000-0000-0000-0000-000000000007','10000000-0000-0000-0000-000000000006','VIEWS',100,125,'legacy result');
""");
    }
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired UserRepository users;
  @Autowired BrandProfileRepository brands;
  @Autowired CampaignRepository campaigns;
  @Autowired CreatorRepository creators;
  @Autowired BrandCreatorRelationshipRepository relationships;
  @Autowired CollaborationRepository collaborations;
  @Autowired CampaignOutcomeRepository outcomes;
  @Autowired CreatorReviewRepository reviews;
  @Autowired CollaborationService service;
  @Autowired JwtTokenProvider jwt;
  @Autowired JdbcTemplate jdbc;
  @Autowired TransactionTemplate tx;
  final java.util.Map<UUID, UUID> preparedDeals = new java.util.HashMap<>();

  @AfterEach
  void clearAuthentication() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void upgradePreservesLegacyKpiAndNullMetricsAndFreshDatabaseMigrates() {
    var legacy =
        outcomes.findById(UUID.fromString("10000000-0000-0000-0000-000000000007")).orElseThrow();
    assertThat(legacy.getKpiActual()).isEqualByComparingTo("125");
    assertThat(legacy.getViews()).isNull();
    assertThat(legacy.getClicks()).isNull();
    assertThat(legacy.getResultNote()).isEqualTo("legacy result");
    assertThat(users.findByEmail("flow5-legacy@example.test").orElseThrow().getPasswordHash())
        .isEqualTo("preserved-hash");
    try (var fresh = container()) {
      fresh.start();
      var flyway =
          Flyway.configure()
              .dataSource(fresh.getJdbcUrl(), fresh.getUsername(), fresh.getPassword())
              .load();
      assertThat(flyway.migrate().migrationsExecuted).isEqualTo(33);
      assertThat(flyway.info().current().getVersion().toString()).isEqualTo("34");
      assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
      assertThat(jdbc.queryForObject("select execution_source from collaboration where id='10000000-0000-0000-0000-000000000006'",String.class)).isEqualTo("LEGACY");
      assertThat(jdbc.queryForObject("select count(*) from collaboration_deliverable where collaboration_id='10000000-0000-0000-0000-000000000006'",Long.class)).isZero();
      assertThat(jdbc.queryForObject("select count(*) from collaboration_outcome_acceptance where collaboration_id='10000000-0000-0000-0000-000000000006'",Long.class)).isZero();
      assertThat(
              jdbc.queryForObject(
                  "select source from cooperation_deal where"
                      + " id='10000000-0000-0000-0000-000000000006'",
                  String.class))
          .isEqualTo("LEGACY");
      assertThat(
              jdbc.queryForObject(
                  "select accepted_by from cooperation_deal where"
                      + " id='10000000-0000-0000-0000-000000000006'",
                  UUID.class))
          .isNull();
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from collaboration_channel where"
                      + " collaboration_id='10000000-0000-0000-0000-000000000006'",
                  Long.class))
          .isOne();
    }
  }

}
