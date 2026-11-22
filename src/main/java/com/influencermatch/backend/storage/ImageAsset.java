package com.influencermatch.backend.storage;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.*;

/** Metadata only. Image bytes are stored exclusively in cloud storage. */
@Entity
@Table(name = "image_asset")
@Getter @Setter @NoArgsConstructor
public class ImageAsset {
  @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
  @Column(nullable = false) private UUID ownerId;
  @Column(nullable = false, length = 50) private String purpose;
  @Column(nullable = false, length = 2048) private String url;
  @Column(nullable = false, length = 500) private String storageKey;
  @Column(nullable = false, length = 100) private String mimeType;
  @Column(nullable = false) private long sizeBytes;
  private Integer width;
  private Integer height;
  @Column(nullable = false) private LocalDateTime updatedAt;

  public StoredMedia media() {
    return new StoredMedia(url, storageKey, mimeType, sizeBytes, width, height, updatedAt);
  }
}
