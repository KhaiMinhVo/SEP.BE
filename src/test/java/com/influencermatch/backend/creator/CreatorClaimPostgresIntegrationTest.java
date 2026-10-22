package com.influencermatch.backend.creator;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.influencermatch.backend.creator.model.Creator;
import com.influencermatch.backend.creator.repository.CreatorRepository;
import com.influencermatch.backend.security.jwt.JwtTokenProvider;
import com.influencermatch.backend.user.enums.*;
import com.influencermatch.backend.user.model.User;
import com.influencermatch.backend.user.repository.UserRepository;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(properties = {
    "spring.jpa.hibernate.ddl-auto=validate", "spring.flyway.enabled=true",
    "application.bootstrap-admin.enabled=false",
    "CREATOR_INGESTION_API_KEY=claim-integration-key", "application.auth.creator-mock.enabled=true"
})
@AutoConfigureMockMvc
@Testcontainers
class CreatorClaimPostgresIntegrationTest {
  @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
      DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", postgres::getJdbcUrl);
    r.add("spring.datasource.username", postgres::getUsername);
    r.add("spring.datasource.password", postgres::getPassword);
  }

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired UserRepository users;
  @Autowired CreatorRepository creators;
  @Autowired JwtTokenProvider jwt;

  @Test void devMockCreatesNewCreatorWithoutGoogleAndCompletesClaimFlow() throws Exception {
    var login=call(null,post("/auth/dev/creator-login"),null,200);
    var data=json.readTree(login.getResponse().getContentAsString()).path("data");
    var owner=users.findById(UUID.fromString(data.path("user").path("id").asText())).orElseThrow();
    assertThat(owner.getRole()).isEqualTo(Role.CREATOR);
    assertThat(owner.getPasswordHash()).isNull();
    assertThat(owner.getEmail()).endsWith("@creator-mock.invalid");
    mvc.perform(get("/auth/me").header("Authorization","Bearer "+data.path("accessToken").asText()))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.role").value("CREATOR"));
    var other=json.readTree(call(null,post("/auth/dev/creator-login"),null,200).getResponse().getContentAsString()).path("data");
    assertThat(other.path("user").path("id").asText()).isNotEqualTo(owner.getId().toString());
    var c=channel();String id=claim(owner,c);
    call(user(Role.DATA_MANAGER,false),patch("/data/creator-claims/"+id+"/review"),
        "{\"decision\":\"APPROVED\",\"reason\":\"Development fixture\"}",200);
  }

  private static final String EVIDENCE = "{\"evidenceUrls\":[\"https://example.com/proof\"],\"note\":\"My channel\"}";

  private User user(Role role, boolean verified) {
    String email = UUID.randomUUID() + "@example.test";
    return users.saveAndFlush(User.builder().email(email).fullName("Test").role(role).status(UserStatus.ACTIVE)
        .emailVerified(role!=Role.CREATOR || verified).build());
  }

  private Creator channel() {
    return creators.saveAndFlush(Creator.builder().platform("TIKTOK").externalId(UUID.randomUUID().toString()).userName("channel").build());
  }

  private MvcResult call(User u, MockHttpServletRequestBuilder request, String body, int status) throws Exception {
    if (u != null) request.header("Authorization", "Bearer " + jwt.generateToken(u));
    if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(body);
    return mvc.perform(request).andExpect(status().is(status)).andReturn();
  }

  private String claim(User u, Creator c) throws Exception {
    return json.readTree(call(u, post("/creators/" + c.getId() + "/claim"), EVIDENCE, 201)
        .getResponse().getContentAsString()).path("data").path("id").asText();
  }

  @Test void completeFlowCompetingClaimsIngestionAndRevocation() throws Exception {
    var owner = user(Role.CREATOR, true);
    var other = user(Role.CREATOR, true);
    var reviewer = user(Role.DATA_MANAGER, false);
    var c = channel();
    assertThat(owner.getAuthorities()).extracting(a -> a.getAuthority()).contains("ROLE_CREATOR");
    String id = claim(owner, c);
    String competing = claim(other, c);
    call(owner, patch("/data/creator-claims/" + id + "/review"), "{\"decision\":\"APPROVED\",\"reason\":\"Verified\"}", 403);
    call(reviewer, patch("/data/creator-claims/" + id + "/review"), "{\"decision\":\"APPROVED\",\"reason\":\"Verified\"}", 200);
    var competitor = call(reviewer, get("/data/creator-claims/" + competing), null, 200);
    assertThat(json.readTree(competitor.getResponse().getContentAsString()).path("data").path("status").asText())
        .isEqualTo("REJECTED");
    mvc.perform(get("/creators/" + c.getId()).header("Authorization", "Bearer " + jwt.generateToken(owner)))
        .andExpect(jsonPath("$.data.sourceType").value("AUTHORIZED"))
        .andExpect(jsonPath("$.data.verifiedByInfluencerMatch").value(true));
    mvc.perform(post("/creators/ingest").header("X-Service-Key", "claim-integration-key")
        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
            "platform", c.getPlatform(), "externalId", c.getExternalId(), "userName", "updated", "creatorType", "KOC"))))
        .andExpect(status().isOk());
    assertThat(creators.findById(c.getId()).orElseThrow().getSourceType().name()).isEqualTo("AUTHORIZED");
    assertThat(creators.findById(c.getId()).orElseThrow().getUserName()).isEqualTo("updated");
    call(reviewer, post("/data/creator-claims/" + id + "/revoke"), "{\"reason\":\"Ownership withdrawn\"}", 200);
    assertThat(creators.findById(c.getId()).orElseThrow().getSourceType().name()).isEqualTo("PUBLIC");
    claim(other, c);
  }

  @Test void validationAndClaimAccess() throws Exception {
    var owner = user(Role.CREATOR, true);
    var c = channel();
    String path = "/creators/" + c.getId() + "/claim";
    call(null, post(path), EVIDENCE, 401);
    call(user(Role.BRAND, false), post(path), EVIDENCE, 403);
    call(user(Role.CREATOR, false), post(path), EVIDENCE, 401);
    call(owner, post(path), "{\"evidenceUrls\":[],\"note\":\"\"}", 400);
    call(owner, post("/creators/" + UUID.randomUUID() + "/claim"), EVIDENCE, 404);
    claim(owner, c);
    call(owner, post(path), EVIDENCE, 409);
  }



  @Test void concurrentReviewsHaveOneWinner() throws Exception {
    var c = channel();
    var reviewer = user(Role.ADMIN, false);
    var ids = List.of(claim(user(Role.CREATOR, true), c), claim(user(Role.CREATOR, true), c));
    var start = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      List<Future<Integer>> results = new ArrayList<>();
      for (String id : ids) results.add(pool.submit(() -> {
        start.await();
        return mvc.perform(patch("/data/creator-claims/" + id + "/review")
            .header("Authorization", "Bearer " + jwt.generateToken(reviewer))
            .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"APPROVED\",\"reason\":\"Verified\"}"))
            .andReturn().getResponse().getStatus();
      }));
      start.countDown();
      assertThat(List.of(results.get(0).get(30, TimeUnit.SECONDS), results.get(1).get(30, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(200, 409);
    }
  }
  @Autowired com.influencermatch.backend.auth.repository.ExternalIdentityRepository identities;
  @Autowired com.influencermatch.backend.creator.repository.CreatorClaimRepository claimRepository;
  @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

  @Test void claimEvidenceIsPrivateAcrossCreatorsAndPublicProfile() throws Exception {
    var owner=user(Role.CREATOR,true);var other=user(Role.CREATOR,true);var c=channel();
    String id=claim(owner,c);
    var own=json.readTree(call(owner,get("/creator-claims/me"),null,200).getResponse().getContentAsString()).path("data");
    assertThat(own.path("items").size()).isEqualTo(1);
    assertThat(own.path("items").get(0).path("id").asText()).isEqualTo(id);
    assertThat(own.path("items").get(0).path("evidenceUrls").size()).isEqualTo(1);
    var foreign=json.readTree(call(other,get("/creator-claims/me"),null,200).getResponse().getContentAsString()).path("data");
    assertThat(foreign.path("items").size()).isZero();
    call(other,get("/data/creator-claims/"+id),null,403);
    var profile=call(other,get("/creators/"+c.getId()),null,200).getResponse().getContentAsString();
    assertThat(profile).doesNotContain("evidenceUrls","claimantId","My channel");
  }

  @Test void unverifiedEmailCannotSubmitClaim() throws Exception {
    var owner=user(Role.CREATOR,false);var c=channel();
    identities.saveAndFlush(com.influencermatch.backend.auth.model.ExternalIdentity.builder().user(owner)
        .provider(com.influencermatch.backend.auth.model.ExternalIdentityProvider.GOOGLE)
        .providerSubject(UUID.randomUUID().toString()).providerEmail(owner.getEmail()).emailVerified(false).build());
    call(owner,post("/creators/"+c.getId()+"/claim"),EVIDENCE,401);
    assertThat(claimRepository.existsByCreatorIdAndClaimantIdAndStatus(c.getId(),owner.getId(),
        com.influencermatch.backend.creator.enums.CreatorClaimStatus.PENDING)).isFalse();
    assertThat(creators.findById(c.getId()).orElseThrow().getOwner()).isNull();
  }

  @Test void rejectedClaimCanBeResubmittedAndOnlyReviewerCanDecide() throws Exception {
    var owner=user(Role.CREATOR,true);var c=channel();String id=claim(owner,c);
    String approve="{\"decision\":\"APPROVED\",\"reason\":\"Not allowed\"}";
    for(var actor:List.of(owner,user(Role.BRAND,false))) {
      call(actor,patch("/data/creator-claims/"+id+"/review"),approve,403);
      call(actor,post("/data/creator-claims/"+id+"/revoke"),"{\"reason\":\"Not allowed\"}",403);
    }
    var manager=user(Role.DATA_MANAGER,false);
    call(manager,patch("/data/creator-claims/"+id+"/review"),"{\"decision\":\"REJECTED\",\"reason\":\"More evidence required\"}",200);
    assertThat(creators.findById(c.getId()).orElseThrow().getSourceType().name()).isEqualTo("PUBLIC");
    String resubmitted=claim(owner,c);assertThat(resubmitted).isNotEqualTo(id);
    assertThat(claimRepository.findById(UUID.fromString(id)).orElseThrow().getStatus().name()).isEqualTo("REJECTED");
    assertThat(claimRepository.findById(UUID.fromString(resubmitted)).orElseThrow().getStatus().name()).isEqualTo("PENDING");
  }



  @Test void missingChannelAndClaimReturn404WithoutAudits() throws Exception {
    var owner=user(Role.CREATOR,true);var reviewer=user(Role.DATA_MANAGER,false);var id=UUID.randomUUID();
    long count=jdbc.queryForObject("select count(*) from audit_log",Long.class);
    call(owner,post("/creators/"+id+"/claim"),EVIDENCE,404);
    call(reviewer,get("/data/creator-claims/"+id),null,404);
    call(reviewer,post("/data/creator-claims/"+id+"/revoke"),json.writeValueAsString(Map.of("reason","Missing claim")),404);
    assertThat(jdbc.queryForObject("select count(*) from audit_log",Long.class)).isEqualTo(count);
    assertThat(jdbc.queryForObject("select count(*) from creator_claim where claimant_id=?",Long.class,owner.getId())).isZero();
  }

  @Test void authorizedChannelRejectsNewClaims() throws Exception {
    var owner=user(Role.CREATOR,true);var channel=channel();String id=claim(owner,channel);
    call(user(Role.ADMIN,false),patch("/data/creator-claims/"+id+"/review"),
        json.writeValueAsString(Map.of("decision","APPROVED","reason","Verified")),200);
    call(user(Role.CREATOR,true),post("/creators/"+channel.getId()+"/claim"),EVIDENCE,409);
    assertThat(creators.findById(channel.getId()).orElseThrow().getOwner().getId()).isEqualTo(owner.getId());
  }

}
