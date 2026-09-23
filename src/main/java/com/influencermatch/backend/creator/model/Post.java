package com.influencermatch.backend.creator.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(name = "post")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Post {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "creator_id", nullable = false)
  private Creator creator;

  @Column(name = "platform_post_id", nullable = false, length = 255)
  private String platformPostId;

  @Column(name = "post_url", length = 1000)
  private String postUrl;

  @Column(columnDefinition = "TEXT")
  private String caption;

  @Column
  private Long views;

  @Column
  private Long likes;

  @Column
  private Long comments;

  @Column
  private Long shares;

  @Column(name = "posted_at", length = 100)
  private String postedAt;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private LocalDateTime createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  private LocalDateTime updatedAt;
}
