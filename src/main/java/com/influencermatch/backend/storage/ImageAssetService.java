package com.influencermatch.backend.storage;

import com.influencermatch.backend.exception.ValidationException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/** Reusable upload/import boundary for image attachments, never accepts client metadata as truth. */
@Service @RequiredArgsConstructor
public class ImageAssetService {
  public static final String CAMPAIGN = "CAMPAIGN_COVER";
  private final CloudinaryStorageService storage;
  private final ImageAssetRepository assets;

  @Transactional
  public StoredMedia upload(UUID owner, String purpose, String folder, MultipartFile file) {
    return remember(owner, purpose, storage.uploadFile(file, folder, owner.toString()));
  }

  @Transactional
  public StoredMedia importImage(UUID owner, String purpose, String folder, String url) {
    return assets.findFirstByOwnerIdAndPurposeAndUrl(owner, purpose, url).map(ImageAsset::media)
        .orElseGet(() -> remember(owner, purpose, storage.uploadFromUrl(url, folder, owner.toString())
            .orElseThrow(() -> new ValidationException("Cannot store image. The previous image was kept; try another image."))));
  }

  @Transactional
  public StoredMedia remember(UUID owner, String purpose, StoredMedia media) {
    var asset = new ImageAsset();
    asset.setOwnerId(owner);
    asset.setPurpose(purpose);
    asset.setUrl(media.url());
    asset.setStorageKey(media.storageKey());
    asset.setMimeType(media.mimeType());
    asset.setSizeBytes(media.sizeBytes());
    asset.setWidth(media.width());
    asset.setHeight(media.height());
    asset.setUpdatedAt(media.updatedAt());
    assets.save(asset);
    return media;
  }
}
