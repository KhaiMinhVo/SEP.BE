package com.influencermatch.backend.creator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.influencermatch.backend.creator.model.Creator;
import com.influencermatch.backend.creator.tiktok.TikTokOwnershipService;
import com.influencermatch.backend.storage.CloudinaryStorageService;
import com.influencermatch.backend.storage.StoredMedia;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

class CrawledAvatarPersistenceTest {
  @ParameterizedTest
  @ValueSource(classes = {CreatorIngestionService.class, CreatorClaimResolutionService.class, TikTokOwnershipService.class})
  void onlySuccessfulCloudUploadReplacesAvatar(Class<?> type) {
    Object service = mock(type, CALLS_REAL_METHODS);
    var storage = mock(CloudinaryStorageService.class);
    ReflectionTestUtils.setField(service, "cloudinaryStorageService", storage);
    var creator = Creator.builder().userName("alice").avatarUrl("https://cloud/old")
        .avatarStorageKey("old-key").build();
    apply(service, creator);
    assertThat(creator.getAvatarUrl()).isEqualTo("https://cloud/old");
    when(storage.isConfigured()).thenReturn(true);
    when(storage.uploadFromUrl(anyString(), anyString(), anyString())).thenReturn(Optional.empty());
    apply(service, creator);
    assertThat(creator.getAvatarUrl()).isEqualTo("https://cloud/old");
    assertThat(creator.getAvatarStorageKey()).isEqualTo("old-key");
    var fresh = Creator.builder().userName("alice").build();
    apply(service, fresh);
    assertThat(fresh.getAvatarUrl()).isEqualTo("https://tiktok/temporary");
    assertThat(fresh.getAvatarStorageKey()).isNull();
    var media = new StoredMedia("https://cloud/new", "new-key", "image/png", 100L, 10, 10, LocalDateTime.now());
    when(storage.uploadFromUrl(anyString(), anyString(), anyString())).thenReturn(Optional.of(media));
    apply(service, creator);
    assertThat(creator.getAvatarUrl()).isEqualTo(media.url());
    assertThat(creator.getAvatarStorageKey()).isEqualTo(media.storageKey());
    assertThat(creator.getAvatarUpdatedAt()).isEqualTo(media.updatedAt());
  }

  private void apply(Object service, Creator creator) {
    if (service instanceof TikTokOwnershipService) {
      ReflectionTestUtils.invokeMethod(service, "applyCreatorAvatar", creator, "https://tiktok/temporary", "alice");
    } else {
      ReflectionTestUtils.invokeMethod(service, "applyCreatorAvatar", creator, "https://tiktok/temporary");
    }
  }
}
