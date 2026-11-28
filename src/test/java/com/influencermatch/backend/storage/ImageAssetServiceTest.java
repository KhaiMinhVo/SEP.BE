package com.influencermatch.backend.storage;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;

class ImageAssetServiceTest {
  @Test void reusesOwnedUploadsButImportsForeignAndTemporaryUrls() {
    var storage = mock(CloudinaryStorageService.class);
    var repo = mock(ImageAssetRepository.class);
    var service = new ImageAssetService(storage, repo);
    var owner = UUID.randomUUID();
    var asset = new ImageAsset();
    asset.setUrl("https://cloud/owned"); asset.setStorageKey("owned"); asset.setMimeType("image/png");
    asset.setSizeBytes(1); asset.setUpdatedAt(LocalDateTime.now());
    when(repo.findFirstByOwnerIdAndPurposeAndUrl(owner, "CAMPAIGN_COVER", asset.getUrl())).thenReturn(Optional.of(asset));
    assertThat(service.importImage(owner, "CAMPAIGN_COVER", "covers", asset.getUrl()).storageKey()).isEqualTo("owned");
    verifyNoInteractions(storage);
    when(storage.uploadFromUrl(anyString(), anyString(), anyString())).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.importImage(owner, "CAMPAIGN_COVER", "covers", "https://tiktok/expired"))
        .isInstanceOf(com.influencermatch.backend.exception.ValidationException.class);
    verify(repo, never()).save(any());
    var media = new StoredMedia("https://cloud/new", "new", "image/png", 100, 8, 8, LocalDateTime.now());
    when(storage.uploadFromUrl(anyString(), anyString(), anyString())).thenReturn(Optional.of(media));
    assertThat(service.importImage(owner, "CAMPAIGN_COVER", "covers", "https://other/temporary")).isEqualTo(media);
    verify(repo).save(argThat(image -> owner.equals(image.getOwnerId()) && "new".equals(image.getStorageKey())));
  }
}
