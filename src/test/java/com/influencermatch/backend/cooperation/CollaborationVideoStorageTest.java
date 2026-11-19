package com.influencermatch.backend.cooperation;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CollaborationVideoStorageTest {
  @Test
  void signsATimedCreateOnlyUploadAtTheDeclaredLength() {
    var storage = new CollaborationVideoStorage(true, "https://account.r2.cloudflarestorage.com",
        "videos", "test-key", "test-secret");
    try {
      var grant = storage.upload(UUID.randomUUID(), 4096);
      var query = URLDecoder.decode(URI.create(grant.url()).getRawQuery(), StandardCharsets.UTF_8);
      assertThat(query).contains("X-Amz-Expires=1800", "cache-control;content-length;content-type;host;if-none-match");
      assertThat(grant.headers()).containsEntry("if-none-match", "*").containsEntry("content-type", "video/mp4");
      assertThat(grant.headers()).doesNotContainKeys("host", "content-length");
      var playback = URI.create(storage.playback(grant.uploadId()).url());
      assertThat(playback.getRawQuery()).contains("X-Amz-Expires=600", "X-Amz-SignedHeaders=host");
    } finally {
      storage.close();
    }
  }
}
