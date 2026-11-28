package com.influencermatch.backend.storage;

import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ImageAssetRepository extends JpaRepository<ImageAsset, UUID> {
  Optional<ImageAsset> findFirstByOwnerIdAndPurposeAndUrl(UUID ownerId, String purpose, String url);
}
