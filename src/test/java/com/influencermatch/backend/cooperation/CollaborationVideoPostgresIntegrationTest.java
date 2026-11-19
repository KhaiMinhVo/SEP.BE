package com.influencermatch.backend.cooperation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.influencermatch.backend.exception.ValidationException;
import com.influencermatch.backend.user.enums.Role;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

class CollaborationVideoPostgresIntegrationTest extends ExecutionScenarioSupport {
  @MockBean CollaborationVideoStorage storage;
  @Autowired CollaborationVideo videos;

  @BeforeEach
  void videoStore() {
    when(storage.required()).thenReturn(true);
    when(storage.available()).thenReturn(true);
    when(storage.upload(any(UUID.class), anyLong())).thenAnswer(call ->
        new CollaborationVideoStorage.UploadGrant(call.getArgument(0),
            "https://private.r2.test/put", Map.of("if-none-match", "*"), Instant.now().plusSeconds(1800)));
    when(storage.playback(any(UUID.class))).thenReturn(
        new CollaborationVideoStorage.Playback("https://private.r2.test/play", Instant.now().plusSeconds(600)));
  }

  @Test
  void submitsOwnedVideosOnceAndKeepsReviewVersionsAndAccess() throws Exception {
    var unitId = unit();
    var deliverable = root + "/deliverables/" + unitId;
    var prepare = deliverable + "/submission-uploads";
    var submissions = deliverable + "/submissions";
    var otherBrand = account(Role.BRAND);
    var otherCreator = account(Role.CREATOR);
    var file = Map.of("expectedRevision", 1, "fileName", "test.mp4", "sizeBytes", 4096);
    request(brand, prepare, file, 403);
    request(otherCreator, prepare, file, 403);
    request(owner, prepare, Map.of("expectedRevision", 1, "fileName", "video.html", "sizeBytes", 4096), 400);
    request(owner, prepare, Map.of("expectedRevision", 1, "fileName", "video.mp4",
        "sizeBytes", 100_000_001), 400);
    request(owner, submissions, Map.of("expectedRevision", 1, "url", "https://example.test/draft",
        "source", "CREATOR_PORTAL"), 409);
    UUID upload = UUID.fromString(request(owner, prepare, file, 200).path("uploadId").asText());
    var body = Map.of("expectedRevision", 1, "uploadId", upload);
    doThrow(new ValidationException("Video not uploaded")).doNothing().when(storage).verify(upload, 4096);
    request(owner, submissions, body, 400);
    assertThat(state().path("deliverables").get(0).path("status").asText()).isEqualTo("PENDING");

    var key = UUID.randomUUID().toString();
    var first = request(owner, submissions, body, 200, key).path("deliverables").get(0).path("submissions").get(0);
    request(owner, submissions, body, 200, key);
    request(owner, submissions, body, 409);
    assertThat(first.path("videoFileName").asText()).isEqualTo("test.mp4");
    assertThat(first.path("videoSizeBytes").asLong()).isEqualTo(4096);
    assertThat(first.has("videoUploadId")).isFalse();
    assertThat(state().path("deliverables").get(0).path("submissions")).hasSize(1);

    var playback = submissions + "/" + first.path("id").asText() + "/video";
    call(otherBrand, get(playback), null, 403);
    call(otherCreator, get(playback), null, 403);
    call(brand, get(playback), null, 200);
    call(owner, get(playback), null, 200);
    var history = "/campaigns/" + campaign + "/history/collaborations/" + collaboration
        + "/deliverables/" + unitId + "/submissions/" + first.path("id").asText() + "/video";
    call(brand, get(history), null, 200);
    call(owner, get(history), null, 403);

    request(brand, deliverable + "/review", Map.of("expectedRevision", 2, "submissionRevision", 1,
        "decision", "REQUEST_CHANGES", "completedChecklist", List.of(), "reason", "Edit caption"), 200);
    request(owner, prepare, file, 409);
    var secondUpload = request(owner, prepare, Map.of("expectedRevision", 3, "fileName", "revised.mp4",
        "sizeBytes", 4096), 200).path("uploadId").asText();
    request(owner, submissions, Map.of("expectedRevision", 3, "uploadId", secondUpload), 200);
    request(brand, deliverable + "/review", Map.of("expectedRevision", 4, "submissionRevision", 2,
        "decision", "APPROVE", "completedChecklist", List.of("Brand safe")), 200);
    assertThat(state().path("deliverables").get(0).path("submissions")).hasSize(2);
    verify(storage).verify(UUID.fromString(secondUpload), 4096);

    UUID abandoned = UUID.fromString(request(owner, prepare, Map.of("expectedRevision", 5,
        "fileName", "abandoned.mp4", "sizeBytes", 4096), 200).path("uploadId").asText());
    jdbc.update("update collaboration_video_upload set expires_at=now()-interval '1 minute' where id in (?,?)",
        abandoned, upload);
    videos.cleanExpired();
    verify(storage).delete(abandoned);
    verify(storage, never()).delete(upload);
    assertThat(jdbc.queryForObject("select count(*) from collaboration_video_upload where id=?",
        Integer.class, abandoned)).isZero();
    call(brand, get(playback), null, 200);

    var post = new java.util.HashMap<>(measurement());
    post.remove("deliverableId");
    post.put("expectedRevision", revision());
    post.put("submissionRevision", 2);
    post.put("url", "https://www.tiktok.com/@test/video/123");
    request(owner, deliverable + "/outcome", post, 200);
    request(brand, root + "/outcome/finance", Map.of(
        "expectedRevision", revision(), "currency", "VND", "financialConfirmed", true,
        "financialEvidence", "https://example.test/finance",
        "costs", List.of(Map.of("description", "Production", "amount", 1000))), 200);
    accept();
    assertThat(state().path("status").asText()).isEqualTo("AWAITING_REVIEW");
  }
}
