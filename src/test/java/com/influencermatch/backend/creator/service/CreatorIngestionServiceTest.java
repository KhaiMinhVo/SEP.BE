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
}
