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
import com.influencermatch.backend.security.JwtTokenProvider;
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
      "application.bootstrap-admin.enabled=false",
      "spring.jpa.show-sql=false", "logging.level.org.hibernate.SQL=WARN",
      "logging.level.com.influencermatch=INFO"
    })
@AutoConfigureMockMvc
@Testcontainers
class CollaborationPostgresIntegrationTest {
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
      assertThat(flyway.migrate().migrationsExecuted).isEqualTo(14);
      assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
    }
  }

  @Test
  void completeSmokeFlowPersistsRelationsAndDoesNotChangeCampaignOrPayment() throws Exception {
    Fixture f = fixture();
    JsonNode collaboration = create(f, 201);
    UUID id = UUID.fromString(collaboration.get("id").asText());
    assertThat(collaboration.get("currency").asText()).isEqualTo("VND");
    assertThat(collaboration.get("status").asText()).isEqualTo("IN_PROGRESS");
    assertThat(relationships.findById(f.relationship().getId()).orElseThrow().getStage())
        .isEqualTo(RelationshipStage.COLLABORATING);
    mvc.perform(
            post("/collaborations/" + id + "/review")
                .header("Authorization", bearer(f.owner()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(reviewBody()))
        .andExpect(status().isConflict());
    var accepted =
        postJson(
            "/collaborations/" + id + "/outcome",
            f.owner(),
            "{\"views\":10000,\"clicks\":120,\"publishedContentUrl\":\" https://example.com/post"
                + " \",\"resultNote\":\" result \"}",
            201);
    assertThat(accepted.get("source").asText()).isEqualTo("SELF_REPORTED");
    assertThat(accepted.get("views").asLong()).isEqualTo(10000);
    assertThat(accepted.get("clicks").asLong()).isEqualTo(120);
    var persisted = outcomes.findById(UUID.fromString(accepted.get("id").asText())).orElseThrow();
    assertThat(persisted.getKpiActual()).isNull();
    assertThat(persisted.getActualFee()).isNull();
    assertThat(persisted.getRevenue()).isNull();
    assertThat(persisted.getRoi()).isNull();
    assertThat(relationships.findById(f.relationship().getId()).orElseThrow().getStage())
        .isEqualTo(RelationshipStage.COMPLETED);
    var stored = collaborations.findById(id).orElseThrow();
    assertThat(stored.getStatus()).isEqualTo(CollaborationStatus.COMPLETED);
    assertThat(stored.getPaymentStatus()).isEqualTo(CollaborationPaymentStatus.PENDING);
    assertThat(stored.getPublishedContentUrl()).isEqualTo("https://example.com/post");
    assertThat(campaigns.findById(f.campaign().getId()).orElseThrow().getStatus())
        .isEqualTo(CampaignStatus.ACTIVE);
    var review = postJson("/collaborations/" + id + "/review", f.owner(), reviewBody(), 201);
    assertThat(review.get("outcomeId").asText()).isEqualTo(accepted.get("id").asText());
    assertThat(review.get("reviewedByUserId").asText()).isEqualTo(f.owner().getId().toString());
    assertThat(review.get("brandFitRating").asInt()).isEqualTo(4);
    postJson("/collaborations/" + id + "/review", f.owner(), reviewBody(), 409);
    postJson("/collaborations/" + id + "/outcome", f.owner(), outcomeBody(), 409);
    create(f, 409);
  }

  @Test
  void zeroAndMissingMetricsAndReviewAfterCampaignCompletedAreSupported() throws Exception {
    Fixture f = fixture();
    var c = create(f, 201);
    String id = c.get("id").asText();
    var response =
        postJson(
            "/collaborations/" + id + "/outcome",
            f.owner(),
            "{\"clicks\":0,\"publishedContentUrl\":\"https://example.com/post\"}",
            201);
    assertThat(response.get("clicks").asLong()).isZero();
    assertThat(response.path("views").isMissingNode() || response.path("views").isNull()).isTrue();
    assertThat(outcomes.findByCollaborationId(UUID.fromString(id)).orElseThrow().getViews())
        .isNull();
    var campaign = campaigns.findById(f.campaign().getId()).orElseThrow();
    campaign.setStatus(CampaignStatus.COMPLETED);
    campaigns.saveAndFlush(campaign);
    postJson("/collaborations/" + id + "/review", f.owner(), reviewBody(), 201);
  }

  @Test
  void securityOwnershipAndRelatedIdsAreEnforced() throws Exception {
    Fixture f = fixture(), other = fixture();
    String path = "/campaigns/" + f.campaign().getId() + "/collaborations";
    mvc.perform(
            post(path)
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody(f.relationship().getId())))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    for (Role role : List.of(Role.ADMIN, Role.DATA_MANAGER))
      postJson(path, account(role), createBody(f.relationship().getId()), 403);
    postJson(path, other.owner(), createBody(f.relationship().getId()), 403);
    postJson(path, f.owner(), createBody(other.relationship().getId()), 403);
    var sameOwnerCampaign = campaign(f.owner(), f.campaign().getBrandProfile());
    var wrong = relationship(f.owner(), sameOwnerCampaign, f.creator());
    postJson(path, f.owner(), createBody(wrong.getId()), 400);
    var c = create(f, 201);
    String id = c.get("id").asText();
    for (User actor : List.of(other.owner(), account(Role.ADMIN), account(Role.DATA_MANAGER))) {
      postJson("/collaborations/" + id + "/outcome", actor, outcomeBody(), 403);
      postJson("/collaborations/" + id + "/review", actor, reviewBody(), 403);
    }
    postJson(
        "/campaigns/" + UUID.randomUUID() + "/collaborations",
        f.owner(),
        createBody(f.relationship().getId()),
        404);
    postJson(path, f.owner(), createBody(UUID.randomUUID()), 404);
    postJson("/collaborations/" + UUID.randomUUID() + "/outcome", f.owner(), outcomeBody(), 404);
    postJson("/collaborations/" + UUID.randomUUID() + "/review", f.owner(), reviewBody(), 404);
  }

  @Test
  void validatesMoneyMetricsUrlStarsAndRequiredBoolean() throws Exception {
    Fixture f = fixture();
    String path = "/campaigns/" + f.campaign().getId() + "/collaborations";
    for (String fee : List.of("-1", "1.001", "100000000000000000")) {
      postJson(
          path,
          f.owner(),
          "{\"relationshipId\":\""
              + f.relationship().getId()
              + "\",\"agreedFee\":"
              + fee
              + ",\"currency\":\"VND\"}",
          400);
    }
    postJson(
        path,
        f.owner(),
        "{\"relationshipId\":\""
            + f.relationship().getId()
            + "\",\"agreedFee\":0,\"currency\":\"ZZZ\"}",
        400);
    postJson(
        path,
        f.owner(),
        "{\"relationshipId\":\"" + f.relationship().getId() + "\",\"currency\":\"VND\"}",
        400);
    postJson(
        path,
        f.owner(),
        "{\"relationshipId\":\""
            + f.relationship().getId()
            + "\",\"agreedFee\":0,\"currency\":\"VND\",\"note\":\""
            + "x".repeat(2001)
            + "\"}",
        400);
    String id = create(f, 201).get("id").asText();
    for (String body :
        List.of(
            "{\"publishedContentUrl\":\"https://example.com/post\"}",
            "{\"views\":-1,\"publishedContentUrl\":\"https://example.com/post\"}",
            "{\"clicks\":-1,\"publishedContentUrl\":\"https://example.com/post\"}",
            "{\"views\":1.5,\"publishedContentUrl\":\"https://example.com/post\"}",
            "{\"views\":9223372036854775808,\"publishedContentUrl\":\"https://example.com/post\"}",
            "{\"views\":1,\"publishedContentUrl\":\"ftp://example.com/post\"}",
            "{\"views\":1,\"publishedContentUrl\":\"javascript:alert(1)\"}",
            "{\"views\":1,\"publishedContentUrl\":\"https://\"}",
            "{\"views\":1}")) postJson("/collaborations/" + id + "/outcome", f.owner(), body, 400);
    mvc.perform(
            post("/collaborations/" + id + "/outcome")
                .header("Authorization", bearer(f.owner()))
                .header("X-Correlation-ID", "flow5-validation")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"views\":-1,\"publishedContentUrl\":\"https://example.com/post\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.traceId").value("flow5-validation"))
        .andExpect(jsonPath("$.fieldErrors.views").isArray());
    assertThat(outcomes.existsByCollaborationId(UUID.fromString(id))).isFalse();
    postJson("/collaborations/" + id + "/outcome", f.owner(), outcomeBody(), 201);
    for (String body :
        List.of(
            "{\"overallRating\":0,\"brandFitRating\":4,\"wouldCollaborateAgain\":true}",
            "{\"overallRating\":5,\"brandFitRating\":6,\"wouldCollaborateAgain\":true}",
            "{\"overallRating\":4.5,\"brandFitRating\":4,\"wouldCollaborateAgain\":true}",
            "{\"overallRating\":5,\"brandFitRating\":4}",
            "{\"overallRating\":5,\"brandFitRating\":4,\"wouldCollaborateAgain\":\"true\"}",
            "{\"overallRating\":5,\"brandFitRating\":4,\"wouldCollaborateAgain\":1}",
            "{\"brandFitRating\":4,\"wouldCollaborateAgain\":true}"))
      postJson("/collaborations/" + id + "/review", f.owner(), body, 400);
  }

  @Test
  void campaignAndCollaborationWorkflowRejectsInvalidStates() throws Exception {
    Fixture f = fixture();
    String path = "/campaigns/" + f.campaign().getId() + "/collaborations";
    for (CampaignStatus state :
        List.of(
            CampaignStatus.DRAFT,
            CampaignStatus.READY_FOR_DISCOVERY,
            CampaignStatus.ARCHIVED,
            CampaignStatus.COMPLETED)) {
      var c = campaigns.findById(f.campaign().getId()).orElseThrow();
      c.setStatus(state);
      campaigns.saveAndFlush(c);
      postJson(path, f.owner(), createBody(f.relationship().getId()), 409);
    }
    var campaign = campaigns.findById(f.campaign().getId()).orElseThrow();
    campaign.setStatus(CampaignStatus.ACTIVE);
    campaigns.saveAndFlush(campaign);
    var r = relationships.findById(f.relationship().getId()).orElseThrow();
    r.setStage(RelationshipStage.NOT_INTERESTED);
    relationships.saveAndFlush(r);
    postJson(path, f.owner(), createBody(r.getId()), 409);
    r.setStage(RelationshipStage.INTERESTED);
    relationships.saveAndFlush(r);
    String id = create(f, 201).get("id").asText();
    var c = collaborations.findById(UUID.fromString(id)).orElseThrow();
    c.setStatus(CollaborationStatus.CANCELLED);
    collaborations.saveAndFlush(c);
    postJson("/collaborations/" + id + "/outcome", f.owner(), outcomeBody(), 409);
    c.setStatus(CollaborationStatus.COMPLETED);
    collaborations.saveAndFlush(c);
    postJson("/collaborations/" + id + "/review", f.owner(), reviewBody(), 409);
  }

  @Test
  void transactionRollbackRestoresRelationshipAndCollaboration() throws Exception {
    Fixture f = fixture();
    authenticate(f.owner());
    assertThatThrownBy(
            () ->
                tx.execute(
                    status -> {
                      service.create(
                          f.campaign().getId(),
                          new CreateCollaborationRequest(
                              f.relationship().getId(), BigDecimal.ZERO, "VND", null));
                      throw new IllegalStateException("force rollback");
                    }))
        .isInstanceOf(IllegalStateException.class);
    assertThat(collaborations.existsByRelationshipId(f.relationship().getId())).isFalse();
    assertThat(relationships.findById(f.relationship().getId()).orElseThrow().getStage())
        .isEqualTo(RelationshipStage.CONTACTED);
    String id = create(f, 201).get("id").asText();
    authenticate(f.owner());
    assertThatThrownBy(
            () ->
                tx.execute(
                    status -> {
                      service.recordOutcome(
                          UUID.fromString(id),
                          new RecordOutcomeRequest(0L, null, "https://example.com/post", null));
                      throw new IllegalStateException("force rollback");
                    }))
        .isInstanceOf(IllegalStateException.class);
    assertThat(outcomes.existsByCollaborationId(UUID.fromString(id))).isFalse();
    assertThat(collaborations.findById(UUID.fromString(id)).orElseThrow().getStatus())
        .isEqualTo(CollaborationStatus.IN_PROGRESS);
    assertThat(relationships.findById(f.relationship().getId()).orElseThrow().getStage())
        .isEqualTo(RelationshipStage.COLLABORATING);
  }

  @Test
  void reviewCreationRollsBackWithItsTransaction() throws Exception {
    Fixture f = fixture();
    UUID id = UUID.fromString(create(f, 201).get("id").asText());
    postJson("/collaborations/" + id + "/outcome", f.owner(), outcomeBody(), 201);
    authenticate(f.owner());
    assertThatThrownBy(
            () ->
                tx.execute(
                    status -> {
                      service.review(id, new CreateCreatorReviewRequest(5, 4, true, null));
                      throw new IllegalStateException("force review rollback");
                    }))
        .isInstanceOf(IllegalStateException.class);
    assertThat(reviews.existsByCollaborationId(id)).isFalse();
    assertThat(outcomes.existsByCollaborationId(id)).isTrue();
    postJson("/collaborations/" + id + "/review", f.owner(), reviewBody(), 201);
  }

  @Test
  void concurrentRequestsCreateExactlyOneOfEachResource() throws Exception {
    Fixture f = fixture();
    race(
        "/campaigns/" + f.campaign().getId() + "/collaborations",
        f.owner(),
        createBody(f.relationship().getId()));
    UUID id =
        jdbc.queryForObject(
            "SELECT id FROM collaboration WHERE relationship_id=?",
            UUID.class,
            f.relationship().getId());
    race("/collaborations/" + id + "/outcome", f.owner(), outcomeBody());
    race("/collaborations/" + id + "/review", f.owner(), reviewBody());
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM campaign_outcome WHERE collaboration_id=?",
                Integer.class,
                id))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM creator_review WHERE collaboration_id=?", Integer.class, id))
        .isEqualTo(1);
    var outcome = outcomes.findByCollaborationId(id).orElseThrow();
    assertThatThrownBy(
            () -> jdbc.update("UPDATE campaign_outcome SET views=-1 WHERE id=?", outcome.getId()))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO campaign_outcome(id,collaboration_id) VALUES (?,?)",
                    UUID.randomUUID(),
                    id))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO"
                        + " collaboration(id,relationship_id,campaign_id,creator_id,status,updated_at)"
                        + " VALUES (?,?,?,?,'IN_PROGRESS',now())",
                    UUID.randomUUID(),
                    f.relationship().getId(),
                    f.campaign().getId(),
                    f.creator().getId()))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void swaggerDocumentsTheThreeProtectedPostOperations() throws Exception {
    var document =
        json.readTree(
            mvc.perform(get("/api-docs"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    for (String path :
        List.of(
            "/campaigns/{id}/collaborations",
            "/collaborations/{id}/outcome",
            "/collaborations/{id}/review")) {
      var op = document.get("paths").get(path).get("post");
      assertThat(op.get("responses").has("201")).isTrue();
      assertThat(op.get("security").toString()).contains("BearerAuth");
    }
  }


  @org.junit.jupiter.params.ParameterizedTest(name="fee={0}, HTTP {1}")
  @org.junit.jupiter.params.provider.CsvSource({"0,201", "-0.01,400", "1.001,400", "100000000000000000,400"})
  void agreedFeeBoundariesPreserveStateOnFailure(String fee, int expected) throws Exception {
    var f=fixture();
    String body="{\"relationshipId\":\""+f.relationship().getId()+"\",\"agreedFee\":"+fee+",\"currency\":\"VND\"}";
    postJson("/campaigns/"+f.campaign().getId()+"/collaborations",f.owner(),body,expected);
    assertThat(collaborations.existsByRelationshipId(f.relationship().getId())).isEqualTo(expected==201);
    var saved=relationships.findById(f.relationship().getId()).orElseThrow();
    assertThat(saved.getStage()).isEqualTo(expected==201?RelationshipStage.COLLABORATING:RelationshipStage.CONTACTED);
    if(expected==201) assertThat(saved.getAgreedFee()).isEqualByComparingTo(fee);
    else assertThat(saved.getAgreedFee()).isNull();
  }

  @org.junit.jupiter.params.ParameterizedTest(name="views={0}, clicks={1}")
  @org.junit.jupiter.params.provider.CsvSource({"null,null,400", "0,null,201", "null,0,201", "0,0,201", "-1,0,400", "0,-1,400"})
  void outcomeMetricBoundariesDoNotCompleteRejectedRequests(String views,String clicks,int expected) throws Exception {
    var f=fixture();UUID id=UUID.fromString(create(f,201).path("id").asText());
    postJson("/collaborations/"+id+"/outcome",f.owner(),
        "{\"views\":"+views+",\"clicks\":"+clicks+",\"publishedContentUrl\":\"https://example.com/post\"}",expected);
    assertThat(outcomes.existsByCollaborationId(id)).isEqualTo(expected==201);
    var stored=collaborations.findById(id).orElseThrow();
    assertThat(stored.getPaymentStatus()).isEqualTo(CollaborationPaymentStatus.PENDING);
    assertThat(stored.getStatus()).isEqualTo(expected==201?CollaborationStatus.COMPLETED:CollaborationStatus.IN_PROGRESS);
    assertThat(relationships.findById(f.relationship().getId()).orElseThrow().getStage())
        .isEqualTo(expected==201?RelationshipStage.COMPLETED:RelationshipStage.COLLABORATING);
    if(expected==201) {
      var result=outcomes.findByCollaborationId(id).orElseThrow();
      assertThat(result.getViews()).isEqualTo("null".equals(views)?null:Long.valueOf(views));
      assertThat(result.getClicks()).isEqualTo("null".equals(clicks)?null:Long.valueOf(clicks));
    } else assertThat(stored.getPublishedContentUrl()).isNull();
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings={"", "ftp://example.com/post", "javascript:alert(1)", "https://"})
  void invalidPostLinksDoNotWriteOutcome(String link) throws Exception {
    var f=fixture();UUID id=UUID.fromString(create(f,201).path("id").asText());
    var body=json.createObjectNode().put("views",1).put("publishedContentUrl",link);
    postJson("/collaborations/"+id+"/outcome",f.owner(),json.writeValueAsString(body),400);
    assertThat(outcomes.existsByCollaborationId(id)).isFalse();
    var stored=collaborations.findById(id).orElseThrow();
    assertThat(stored.getStatus()).isEqualTo(CollaborationStatus.IN_PROGRESS);
    assertThat(stored.getPublishedContentUrl()).isNull();
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({"overallRating,0", "overallRating,6", "brandFitRating,0", "brandFitRating,6",
      "overallRating,4.5", "brandFitRating,true", "overallRating,\"5\"", "brandFitRating,null"})
  void invalidReviewStarsDoNotPersistReview(String field,String value) throws Exception {
    var f=fixture();UUID id=UUID.fromString(create(f,201).path("id").asText());
    postJson("/collaborations/"+id+"/outcome",f.owner(),outcomeBody(),201);
    var body=json.readTree(reviewBody());((com.fasterxml.jackson.databind.node.ObjectNode)body).set(field,json.readTree(value));
    postJson("/collaborations/"+id+"/review",f.owner(),json.writeValueAsString(body),400);
    assertThat(reviews.existsByCollaborationId(id)).isFalse();
    assertThat(outcomes.existsByCollaborationId(id)).isTrue();
    assertThat(collaborations.findById(id).orElseThrow().getStatus()).isEqualTo(CollaborationStatus.COMPLETED);
  }

  void race(String path, User actor, String body) throws Exception {
    var pool = Executors.newFixedThreadPool(2);
    var start = new CountDownLatch(1);
    try {
      List<Future<Integer>> jobs = new ArrayList<>();
      for (int i = 0; i < 2; i++)
        jobs.add(
            pool.submit(
                () -> {
                  start.await();
                  return mvc.perform(
                          post(path)
                              .header("Authorization", bearer(actor))
                              .contentType(MediaType.APPLICATION_JSON)
                              .content(body))
                      .andReturn()
                      .getResponse()
                      .getStatus();
                }));
      start.countDown();
      assertThat(
              List.of(jobs.get(0).get(30, TimeUnit.SECONDS), jobs.get(1).get(30, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(201, 409);
    } finally {
      pool.shutdownNow();
    }
  }

  JsonNode create(Fixture f, int expected) throws Exception {
    return postJson(
        "/campaigns/" + f.campaign().getId() + "/collaborations",
        f.owner(),
        createBody(f.relationship().getId()),
        expected);
  }

  String createBody(UUID relationship) {
    return "{\"relationshipId\":\""
        + relationship
        + "\",\"agreedFee\":1250000.50,\"currency\":\" vnd \",\"note\":\" note \"}";
  }

  String outcomeBody() {
    return "{\"views\":1000,\"clicks\":20,\"publishedContentUrl\":\"https://example.com/post\"}";
  }

  String reviewBody() {
    return "{\"overallRating\":5,\"brandFitRating\":4,\"wouldCollaborateAgain\":false,\"note\":\""
        + " review \"}";
  }

  JsonNode postJson(String path, User actor, String body, int expected) throws Exception {
    var result =
        mvc.perform(
                post(path)
                    .header("Authorization", bearer(actor))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().is(expected))
            .andReturn();
    return json.readTree(result.getResponse().getContentAsString()).path("data");
  }

  String bearer(User user) {
    return "Bearer " + jwt.generateToken(user);
  }

  void authenticate(User user) {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
  }

  User account(Role role) {
    return users.saveAndFlush(
        User.builder()
            .email(UUID.randomUUID() + "@flow5.test")
            .passwordHash("fixture-only")
            .fullName("Flow5")
            .role(role)
            .status(UserStatus.ACTIVE)
            .build());
  }

  Campaign campaign(User owner, BrandProfile brand) {
    return campaigns.saveAndFlush(
        Campaign.builder()
            .brandProfile(brand)
            .name("Flow5")
            .productService("Product")
            .objective(CampaignObjective.AWARENESS)
            .status(CampaignStatus.ACTIVE)
            .build());
  }

  BrandCreatorRelationship relationship(User owner, Campaign campaign, Creator creator) {
    return relationships.saveAndFlush(
        BrandCreatorRelationship.builder()
            .user(owner)
            .campaign(campaign)
            .creator(creator)
            .stage(RelationshipStage.CONTACTED)
            .createdAt(LocalDateTime.now())
            .updatedAt(LocalDateTime.now())
            .build());
  }

  Fixture fixture() {
    User owner = account(Role.BRAND);
    var brand =
        brands.saveAndFlush(BrandProfile.builder().user(owner).businessName("Flow5 Brand").build());
    Campaign campaign = campaign(owner, brand);
    var creator =
        creators.saveAndFlush(
            Creator.builder()
                .platform("TIKTOK")
                .externalId(UUID.randomUUID().toString())
                .userName("creator")
                .build());
    return new Fixture(owner, campaign, creator, relationship(owner, campaign, creator));
  }

  record Fixture(
      User owner, Campaign campaign, Creator creator, BrandCreatorRelationship relationship) {}
}
