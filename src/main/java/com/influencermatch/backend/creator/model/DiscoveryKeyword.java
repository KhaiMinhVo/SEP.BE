package com.influencermatch.backend.creator.model;

import com.influencermatch.backend.creator.enums.KeywordType;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(name = "discovery_keyword")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DiscoveryKeyword {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column
  private UUID id;

  @Column(nullable = false, length = 255)
  private String keyword;

  @Column(length = 100)
  private String category;

  @Enumerated(EnumType.STRING)
  @Column(name = "keyword_type", nullable = false, length = 50)
  @Builder.Default
  private KeywordType keywordType = KeywordType.TARGET;

  @Column(length = 50)
  @Builder.Default
  private String platform = "TIKTOK";

  @Column(name = "is_active", nullable = false)
  @Builder.Default
  private Boolean isActive = true;

  @Column(name = "creator_count", nullable = false)
  @Builder.Default
  private Long creatorCount = 0L;

  @Column(name = "last_crawled_at")
  private LocalDateTime lastCrawledAt;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private LocalDateTime createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  private LocalDateTime updatedAt;
}

