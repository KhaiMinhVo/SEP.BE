package com.influencermatch.backend.cooperation;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.fasterxml.jackson.databind.*;
import com.influencermatch.backend.audit.service.AuditService;
import com.influencermatch.backend.creator.model.*;
import com.influencermatch.backend.creator.repository.*;
import com.influencermatch.backend.support.PostgresApiTestSupport;
import com.influencermatch.backend.user.enums.*;
import com.influencermatch.backend.user.model.User;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;

abstract class ExecutionScenarioSupport extends PostgresApiTestSupport {
  @Autowired CreatorRepository creators;
  @Autowired CreatorMasterRepository masters;
  @SpyBean AuditService audit;
  User brand, owner;
  UUID campaign, collaboration, channel, deal;
  String root;
  long dRevision = 1;

  @BeforeEach
  void fixture() throws Exception {
    brand = account(Role.BRAND);
    owner = account(Role.CREATOR);
    campaign = createCampaign(brand, createBrand(brand));
    jdbc.update(
        "update campaign set"
            + " status='ACTIVE',total_budget=1000000,currency='VND',creator_mode='SINGLE',target_creator_count=1"
            + " where id=?",
        campaign);
    var master =
        masters.saveAndFlush(CreatorMaster.builder().user(owner).canonicalName("Creator").build());
    channel =
        creators
            .saveAndFlush(
                Creator.builder()
                    .platform("TIKTOK")
                    .externalId(UUID.randomUUID().toString())
                    .userName("test" + UUID.randomUUID().toString().replace("-", ""))
                    .master(master)
                    .sourceType(
                        com.influencermatch.backend.creator.enums.CreatorSourceType.AUTHORIZED)
                    .authorizedAt(LocalDateTime.now())
                    .build())
            .getId();
    verifyChannel(channel, owner.getId(), "TIKTOK_OAUTH_SANDBOX");
    var rel =
        call(
                brand,
                post("/campaigns/" + campaign + "/relationships"),
                Map.of("creatorId", channel),
                200)
            .path("data")
            .path("id")
            .asText();
    deal =
        UUID.fromString(
            call(
                    brand,
                    post("/campaigns/" + campaign + "/deals")
                        .header("Idempotency-Key", UUID.randomUUID().toString()),
                    Map.of("relationshipIds", List.of(rel)),
                    200)
                .path("data")
                .path("id")
                .asText());
    request(
        brand,
        "/campaigns/" + campaign + "/deals/" + deal + "/proposals",
        Map.of(
            "expectedRevision",
            1,
            "fee",
            10000,
            "currency",
            "VND",
            "terms",
            "Agreed execution terms",
            "deliverables",
            List.of(
                Map.of(
                    "creatorId",
                    channel,
                    "contentType",
                    "Video",
                    "quantity",
                    1,
                    "dueAt",
                    "2027-01-01T00:00:00Z",
                    "acceptanceChecklist",
                    List.of("Brand safe"),
                    "maxRevisionRounds",
                    1))),
        200);
    var result =
        request(
            brand,
            "/campaigns/" + campaign + "/deals/" + deal + "/confirm",
            Map.of(
                "expectedRevision",
                2,
                "agreementSource",
                "EXTERNAL",
                "agreedAt",
                Instant.now().minusSeconds(60).toString(),
                "evidenceSource",
                "EMAIL",
                "evidenceUrl",
                "https://evidence.test/consent",
                "evidenceDescription",
                "Recipient agreed"),
            200);
    collaboration = UUID.fromString(result.path("collaborationId").asText());
    root = "/collaborations/" + collaboration;
    request(
        brand,
        "/campaigns/" + campaign + "/deals/" + deal + "/start",
        Map.of("expectedRevision", 3),
        200);
  }

  JsonNode request(User user, String path, Object body, int expected) throws Exception {
    return request(user, path, body, expected, UUID.randomUUID().toString());
  }

  JsonNode request(User user, String path, Object body, int expected, String key) throws Exception {
    return call(user, post(path).header("Idempotency-Key", key), body, expected).path("data");
  }

  JsonNode state() throws Exception {
    return call(brand, get(root + "/deliverables"), null, 200).path("data");
  }

  UUID unit() throws Exception {
    return UUID.fromString(state().path("deliverables").get(0).path("id").asText());
  }

  long revision() throws Exception {
    return state().path("executionRevision").asLong();
  }

  void submit() throws Exception {
    var d = unit();
    request(
        owner,
        root + "/deliverables/" + d + "/submissions",
        Map.of(
            "expectedRevision",
            dRevision,
            "url",
            "https://content.test/draft",
            "source",
            "CREATOR_PORTAL"),
        200);
    dRevision++;
  }

  void approve() throws Exception {
    request(
        brand,
        root + "/deliverables/" + unit() + "/review",
        Map.of(
            "expectedRevision",
            dRevision,
            "submissionRevision",
            1,
            "decision",
            "APPROVE",
            "completedChecklist",
            List.of("Brand safe")),
        200);
    dRevision++;
  }

  void publish(boolean ack) throws Exception {
    request(
        ack ? brand : owner,
        root + "/deliverables/" + unit() + "/publication",
        Map.of(
            "expectedRevision",
            dRevision,
            "submissionRevision",
            1,
            "url",
            "https://www.tiktok.com/@test/video/123",
            "postId",
            "123",
            "acknowledgeException",
            ack,
            "exceptionReason",
            ack ? "Approved exception" : ""),
        200);
    dRevision++;
  }

  Map<String, Object> measurement() throws Exception {
    return Map.of(
        "deliverableId",
        unit(),
        "submissionRevision",
        1,
        "periodStart",
        "2026-11-01T00:00:00Z",
        "periodEnd",
        "2026-11-02T00:00:00Z",
        "views",
        1000,
        "unavailableMetrics",
        List.of("clicks"),
        "likes",
        80,
        "comments",
        10,
        "shares",
        10);
  }

  void verifyChannel(UUID id, UUID user, String method) {
    jdbc.update(
        "insert into creator_account_verification(id,creator_id,verified_by_user_id,verification_method,status,verified_at,created_at,updated_at)"
            + " values(gen_random_uuid(),?,?,?,'ACTIVE',now(),now(),now())",
        id, user, method);
  }

  long latestOutcome() throws Exception {
    var revisions = state().path("outcomeRevisions");
    return revisions.get(revisions.size() - 1).path("revision").asLong();
  }

  JsonNode outcome() throws Exception {
    request(owner, root + "/outcome",
        Map.of("expectedRevision", revision(), "reason", "Measured outcome", "posts", List.of(measurement())),
        200);
    return request(
        brand,
        root + "/outcome/finance",
        Map.of(
            "expectedRevision",
            revision(),
            "reason",
            "Measured outcome",
            "costs",
            List.of(Map.of("description", "Production", "amount", 1000)),
            "currency",
            "VND",
            "financialConfirmed",
            true,
            "gmv",
            5000,
            "revenue",
            3000,
            "periodStart",
            "2026-11-01T00:00:00Z",
            "periodEnd",
            "2026-11-02T00:00:00Z"),
        200);
  }

  void ready() throws Exception {
    submit();
    approve();
    publish(false);
    outcome();
  }

  void accept() throws Exception {
    request(
        brand,
        root + "/outcome/accept",
        Map.of("expectedRevision", revision(), "outcomeRevision", latestOutcome()),
        200);
  }

  Map<String, Object> review() throws Exception {
    return Map.of(
        "expectedRevision",
        revision(),
        "outcomeRevision",
        latestOutcome(),
        "overallRating",
        2,
        "brandFitRating",
        1,
        "wouldCollaborateAgain",
        false,
        "reviewTags",
        List.of("late"));
  }
}
