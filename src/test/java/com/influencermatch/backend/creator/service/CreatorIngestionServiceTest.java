package com.influencermatch.backend.creator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.influencermatch.backend.creator.dto.CreatorIngestionRequest;
import com.influencermatch.backend.creator.dto.CreatorIngestionResponse;
import com.influencermatch.backend.creator.model.Creator;
import com.influencermatch.backend.creator.model.PublicCreatorMetric;
import com.influencermatch.backend.creator.repository.CreatorRepository;
import com.influencermatch.backend.creator.repository.PostRepository;
import com.influencermatch.backend.creator.repository.PublicCreatorMetricRepository;
import jakarta.persistence.EntityManager;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CreatorIngestionServiceTest {

  @Mock private CreatorRepository creatorRepository;
  @Mock private PublicCreatorMetricRepository metricRepository;
  @Mock private PostRepository postRepository;
  @Mock private EntityManager entityManager;

  private CreatorIngestionService service;

  @BeforeEach
  void setUp() {
    service = new CreatorIngestionService(creatorRepository, metricRepository, postRepository, entityManager);
  }

  @Test
  @DisplayName("testIngestKoc_Success: Gửi payload creatorType = KOC -> trả về SUCCESS")
  void testIngestKoc_Success() {
    when(creatorRepository.findLockedByPlatformAndExternalId(any(), any())).thenReturn(Optional.empty());
    when(creatorRepository.save(any())).thenAnswer(i -> {
      Creator c = i.getArgument(0);
      c.setId(UUID.randomUUID());
      return c;
    });
    when(metricRepository.findByCreatorId(any())).thenReturn(Optional.empty());
    when(metricRepository.save(any())).thenAnswer(i -> {
      PublicCreatorMetric m = i.getArgument(0);
      m.setId(UUID.randomUUID());
      return m;
    });

    CreatorIngestionRequest request = CreatorIngestionRequest.builder()
        .platform("TIKTOK")
        .externalId("koc_review_01")
        .userName("koc_reviewer")
        .creatorType("KOC")
        .followers(12000L)
        .build();

    CreatorIngestionResponse res = service.ingestCreatorData(request);
    assertThat(res.getStatus()).isEqualTo("SUCCESS");
    assertThat(res.getCreatorId()).isNotNull();
  }

  @Test
  @DisplayName("testIngestKol_Success: Gửi payload creatorType = KOL -> trả về SUCCESS")
  void testIngestKol_Success() {
    when(creatorRepository.findLockedByPlatformAndExternalId(any(), any())).thenReturn(Optional.empty());
    when(creatorRepository.save(any())).thenAnswer(i -> {
      Creator c = i.getArgument(0);
      c.setId(UUID.randomUUID());
      return c;
    });
    when(metricRepository.findByCreatorId(any())).thenReturn(Optional.empty());
    when(metricRepository.save(any())).thenAnswer(i -> {
      PublicCreatorMetric m = i.getArgument(0);
      m.setId(UUID.randomUUID());
      return m;
    });

    CreatorIngestionRequest request = CreatorIngestionRequest.builder()
        .platform("TIKTOK")
        .externalId("kol_celeb_01")
        .userName("kol_celeb")
        .creatorType("KOL")
        .followers(150000L)
        .build();

    CreatorIngestionResponse res = service.ingestCreatorData(request);
    assertThat(res.getStatus()).isEqualTo("SUCCESS");
    assertThat(res.getCreatorId()).isNotNull();
  }

  @Test
  @DisplayName("testIngestBrand_Rejected: Gửi payload creatorType = BRAND -> trả về REJECTED")
  void testIngestBrand_Rejected() {
    CreatorIngestionRequest request = CreatorIngestionRequest.builder()
        .platform("TIKTOK")
        .externalId("brand_shop_01")
        .userName("shop_thoi_trang")
        .creatorType("BRAND")
        .followers(20000L)
        .build();

    CreatorIngestionResponse res = service.ingestCreatorData(request);
    assertThat(res.getStatus()).isEqualTo("REJECTED");
    assertThat(res.getMessage()).contains("Only KOL or KOC");
    verify(creatorRepository, never()).save(any());
  }

  @Test
  @DisplayName("testIngestUser_Rejected: Gửi payload creatorType = USER -> trả về REJECTED")
  void testIngestUser_Rejected() {
    CreatorIngestionRequest request = CreatorIngestionRequest.builder()
        .platform("TIKTOK")
        .externalId("user_personal_01")
        .userName("user_regular")
        .creatorType("USER")
        .followers(8000L)
        .build();

    CreatorIngestionResponse res = service.ingestCreatorData(request);
    assertThat(res.getStatus()).isEqualTo("REJECTED");
    assertThat(res.getMessage()).contains("Only KOL or KOC");
    verify(creatorRepository, never()).save(any());
  }

  @Test
  @DisplayName("testIngestLowFollowers_Rejected: Gửi payload followers < 5000 -> trả về REJECTED")
  void testIngestLowFollowers_Rejected() {
    CreatorIngestionRequest request = CreatorIngestionRequest.builder()
        .platform("TIKTOK")
        .externalId("low_koc_01")
        .userName("low_follower_koc")
        .creatorType("KOC")
        .followers(3000L)
        .build();

    CreatorIngestionResponse res = service.ingestCreatorData(request);
    assertThat(res.getStatus()).isEqualTo("REJECTED");
    assertThat(res.getMessage()).contains("Minimum 5,000 followers");
    verify(creatorRepository, never()).save(any());
  }

  @Test
  @DisplayName("Section IV Test 1: Ingestion nhận 3 bài -> Lưu đúng 3 bài và xóa snapshot cũ")
  void testIngestCreator_withThreePosts_savesExactlyThreePosts() {
    UUID creatorId = UUID.randomUUID();
    when(creatorRepository.findLockedByPlatformAndExternalId(any(), any())).thenReturn(Optional.empty());
    when(creatorRepository.save(any())).thenAnswer(i -> {
      Creator c = i.getArgument(0);
      c.setId(creatorId);
      return c;
    });
    when(metricRepository.findByCreatorId(any())).thenReturn(Optional.empty());
    when(metricRepository.save(any())).thenAnswer(i -> i.getArgument(0));

    var post1 = com.influencermatch.backend.creator.dto.PostDto.builder().platformPostId("p1").views(10_000L).likes(500L).build();
    var post2 = com.influencermatch.backend.creator.dto.PostDto.builder().platformPostId("p2").views(12_000L).likes(600L).build();
    var post3 = com.influencermatch.backend.creator.dto.PostDto.builder().platformPostId("p3").views(15_000L).likes(750L).build();

    CreatorIngestionRequest request = CreatorIngestionRequest.builder()
        .platform("TIKTOK")
        .externalId("koc_3posts")
        .userName("koc_3posts")
        .creatorType("KOC")
        .followers(25000L)
        .recentPosts(java.util.List.of(post1, post2, post3))
        .build();

    CreatorIngestionResponse res = service.ingestCreatorData(request);
    assertThat(res.getStatus()).isEqualTo("SUCCESS");

    // Xóa snapshot cũ trước khi nạp mới
    verify(postRepository, times(1)).deleteByCreatorId(creatorId);
    // Lưu đúng 3 bài mới
    verify(postRepository, times(3)).save(any(com.influencermatch.backend.creator.model.Post.class));
  }

  @Test
  @DisplayName("Section IV Test 2: Ingestion nhận 5 bài (từ Facebook Crawler) -> Giới hạn đúng tối đa MAX_RECENT_POSTS = 3")
  void testIngestCreator_withFivePosts_capsAtMaxRecentPostsThree() {
    UUID creatorId = UUID.randomUUID();
    when(creatorRepository.findLockedByPlatformAndExternalId(any(), any())).thenReturn(Optional.empty());
    when(creatorRepository.save(any())).thenAnswer(i -> {
      Creator c = i.getArgument(0);
      c.setId(creatorId);
      return c;
    });
    when(metricRepository.findByCreatorId(any())).thenReturn(Optional.empty());
    when(metricRepository.save(any())).thenAnswer(i -> i.getArgument(0));

    // Giả lập Facebook crawler gửi 5 bài
    var posts = java.util.List.of(
        com.influencermatch.backend.creator.dto.PostDto.builder().platformPostId("fb_1").views(5_000L).likes(200L).build(),
        com.influencermatch.backend.creator.dto.PostDto.builder().platformPostId("fb_2").views(6_000L).likes(250L).build(),
        com.influencermatch.backend.creator.dto.PostDto.builder().platformPostId("fb_3").views(7_000L).likes(300L).build(),
        com.influencermatch.backend.creator.dto.PostDto.builder().platformPostId("fb_4").views(8_000L).likes(350L).build(),
        com.influencermatch.backend.creator.dto.PostDto.builder().platformPostId("fb_5").views(9_000L).likes(400L).build()
    );

    CreatorIngestionRequest request = CreatorIngestionRequest.builder()
        .platform("FACEBOOK")
        .externalId("fb_koc_5posts")
        .userName("fb_koc")
        .creatorType("KOC")
        .followers(30000L)
        .recentPosts(posts)
        .build();

    CreatorIngestionResponse res = service.ingestCreatorData(request);
    assertThat(res.getStatus()).isEqualTo("SUCCESS");

    // Chỉ lưu tối đa 3 bài theo hợp đồng Current Post Snapshot
    verify(postRepository, times(3)).save(any(com.influencermatch.backend.creator.model.Post.class));
  }

  @Test
  @DisplayName("Section IV Test 9: Ingestion với recentPosts rỗng/null -> Không xóa snapshot cũ")
  void testIngestCreator_withEmptyPosts_doesNotDeleteExistingSnapshot() {
    UUID creatorId = UUID.randomUUID();
    when(creatorRepository.findLockedByPlatformAndExternalId(any(), any())).thenReturn(Optional.empty());
    when(creatorRepository.save(any())).thenAnswer(i -> {
      Creator c = i.getArgument(0);
      c.setId(creatorId);
      return c;
    });
    when(metricRepository.findByCreatorId(any())).thenReturn(Optional.empty());
    when(metricRepository.save(any())).thenAnswer(i -> i.getArgument(0));

    CreatorIngestionRequest request = CreatorIngestionRequest.builder()
        .platform("TIKTOK")
        .externalId("koc_empty_posts")
        .userName("koc_empty_posts")
        .creatorType("KOC")
        .followers(10000L)
        .recentPosts(java.util.Collections.emptyList())
        .build();

    CreatorIngestionResponse res = service.ingestCreatorData(request);
    assertThat(res.getStatus()).isEqualTo("SUCCESS");

    // Tuyệt đối không xóa bài cũ khi không có dữ liệu mới
    verify(postRepository, never()).deleteByCreatorId(any());
    verify(postRepository, never()).save(any(com.influencermatch.backend.creator.model.Post.class));
  }

  @Test
  @DisplayName("Section IV Test 10: Ingestion nhận bài trùng platformPostId -> Khử trùng lặp tại cổng Ingestion")
  void testIngestCreator_withDuplicatePlatformPostId_filtersDuplicates() {
    UUID creatorId = UUID.randomUUID();
    when(creatorRepository.findLockedByPlatformAndExternalId(any(), any())).thenReturn(Optional.empty());
    when(creatorRepository.save(any())).thenAnswer(i -> {
      Creator c = i.getArgument(0);
      c.setId(creatorId);
      return c;
    });
    when(metricRepository.findByCreatorId(any())).thenReturn(Optional.empty());
    when(metricRepository.save(any())).thenAnswer(i -> i.getArgument(0));

    // Post 1 và Post 2 trùng platformPostId
    var posts = java.util.List.of(
        com.influencermatch.backend.creator.dto.PostDto.builder().platformPostId("dup_vid_123").views(10_000L).likes(500L).build(),
        com.influencermatch.backend.creator.dto.PostDto.builder().platformPostId("dup_vid_123").views(10_000L).likes(500L).build(),
        com.influencermatch.backend.creator.dto.PostDto.builder().platformPostId("unique_vid_456").views(15_000L).likes(750L).build()
    );

    CreatorIngestionRequest request = CreatorIngestionRequest.builder()
        .platform("TIKTOK")
        .externalId("koc_dup_posts")
        .userName("koc_dup_posts")
        .creatorType("KOC")
        .followers(15000L)
        .recentPosts(posts)
        .build();

    CreatorIngestionResponse res = service.ingestCreatorData(request);
    assertThat(res.getStatus()).isEqualTo("SUCCESS");

    // Chỉ lưu 2 bài độc nhất (bỏ qua bài trùng)
    verify(postRepository, times(2)).save(any(com.influencermatch.backend.creator.model.Post.class));
  }

  @Test
  @DisplayName("Section IV Integration: Pipeline Payload -> Ingestion -> Repository -> EvidenceValidator đạt SCORE_READY với Reliability = 0.65")
  void testEndToEnd_CrawlerPayloadToIngestionToEvidenceValidation() {
    UUID creatorId = UUID.randomUUID();
    when(creatorRepository.findLockedByPlatformAndExternalId(any(), any())).thenReturn(Optional.empty());
    when(creatorRepository.save(any())).thenAnswer(i -> {
      Creator c = i.getArgument(0);
      c.setId(creatorId);
      return c;
    });
    when(metricRepository.findByCreatorId(any())).thenReturn(Optional.empty());
    when(metricRepository.save(any())).thenAnswer(i -> i.getArgument(0));

    java.util.List<com.influencermatch.backend.creator.model.Post> capturedPosts = new java.util.ArrayList<>();
    doAnswer(i -> {
      capturedPosts.add(i.getArgument(0));
      return i.getArgument(0);
    }).when(postRepository).save(any(com.influencermatch.backend.creator.model.Post.class));

    // 1. Crawler gửi payload Ingestion
    var postDtos = java.util.List.of(
        com.influencermatch.backend.creator.dto.PostDto.builder().platformPostId("vid_01").views(20_000L).likes(1_000L).comments(100L).shares(50L).postedAt("2026-11-08T10:00:00Z").build(),
        com.influencermatch.backend.creator.dto.PostDto.builder().platformPostId("vid_02").views(25_000L).likes(1_250L).comments(120L).shares(60L).postedAt("2026-11-08T11:00:00Z").build(),
        com.influencermatch.backend.creator.dto.PostDto.builder().platformPostId("vid_03").views(30_000L).likes(1_500L).comments(150L).shares(75L).postedAt("2026-11-08T12:00:00Z").build()
    );

    CreatorIngestionRequest request = CreatorIngestionRequest.builder()
        .platform("TIKTOK")
        .externalId("pipeline_creator_01")
        .userName("pipeline_creator")
        .creatorType("KOC")
        .followers(50000L)
        .recentPosts(postDtos)
        .build();

    CreatorIngestionResponse res = service.ingestCreatorData(request);
    assertThat(res.getStatus()).isEqualTo("SUCCESS");
    assertThat(capturedPosts).hasSize(3);

    // 2. Chuyển sang EvidenceValidator trong Phase 0
    com.influencermatch.backend.creator.dto.RecommendationCandidateProjection cand =
        mock(com.influencermatch.backend.creator.dto.RecommendationCandidateProjection.class);
    when(cand.getFollowers()).thenReturn(50000L);
    when(cand.getPlatform()).thenReturn("TIKTOK");

    var validator = new com.influencermatch.backend.recommendation.engine.phase0.EvidenceValidator();
    var metrics = validator.evaluate(cand, capturedPosts);

    // 3. Khẳng định toàn bộ tiêu chí hợp đồng
    assertThat(metrics.scoringReadiness()).isEqualTo(com.influencermatch.backend.recommendation.engine.phase0.enums.ScoringReadiness.SCORE_READY);
    assertThat(metrics.canComputeStability()).isTrue();
    assertThat(metrics.evidenceReliabilityM2()).isEqualTo(0.65);
    assertThat(metrics.rawEr()).isGreaterThan(0.0);
    assertThat(metrics.rawVr()).isGreaterThan(0.0);
    assertThat(metrics.rawStab()).isGreaterThan(0.0);
  }
}
