package com.influencermatch.backend.creator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.influencermatch.backend.creator.dto.CreatorDetailResponse;
import com.influencermatch.backend.creator.enums.CreatorSourceType;
import com.influencermatch.backend.creator.model.Creator;
import com.influencermatch.backend.creator.model.Post;
import com.influencermatch.backend.creator.model.PublicCreatorMetric;
import com.influencermatch.backend.creator.repository.CreatorRepository;
import com.influencermatch.backend.creator.repository.PostRepository;
import com.influencermatch.backend.creator.repository.PublicCreatorMetricRepository;
import com.influencermatch.backend.creator.service.CreatorService;
import com.influencermatch.backend.exception.NotFoundException;
import com.influencermatch.backend.user.enums.Role;
import com.influencermatch.backend.user.enums.UserStatus;
import com.influencermatch.backend.user.model.User;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(MockitoExtension.class)
class CreatorServiceTest {

  @Mock CreatorRepository creatorRepository;
  @Mock PublicCreatorMetricRepository metricRepository;
  @Mock PostRepository postRepository;

  CreatorService creatorService;
  User viewer;
  Creator creator;

  @BeforeEach
  void setUp() {
    creatorService = new CreatorService(
        creatorRepository, metricRepository, postRepository);

    viewer = User.builder().id(UUID.randomUUID()).role(Role.BRAND).status(UserStatus.ACTIVE).build();
    creator = Creator.builder()
        .id(UUID.randomUUID())
        .platform("INSTAGRAM")
        .externalId("inst_123")
        .userName("ngoc_test")
        .displayName("Ngoc Creator")
        .profileUrl("https://instagram.com/ngoc_test")
        .avatarUrl("https://avatar.com/ngoc.jpg")
        .location("Da Nang")
        .sourceType(CreatorSourceType.PUBLIC)
        .build();

    SecurityContextHolder.getContext().setAuthentication(
        new UsernamePasswordAuthenticationToken(viewer, null, viewer.getAuthorities()));
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void getCreatorDetailThrowsWhenNotFound() {
    UUID randomId = UUID.randomUUID();
    when(creatorRepository.findById(randomId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> creatorService.getCreatorDetail(randomId))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void getCreatorDetailReturnsEnrichedMetricsAndPosts() {
    when(creatorRepository.findById(creator.getId())).thenReturn(Optional.of(creator));

    PublicCreatorMetric metric = PublicCreatorMetric.builder()
        .id(UUID.randomUUID())
        .creator(creator)
        .followers(85000L)
        .avgViews(new BigDecimal("25000.00"))
        .avgLikes(new BigDecimal("4200.00"))
        .avgComments(new BigDecimal("210.00"))
        .avgShares(new BigDecimal("75.00"))
        .engagementRate(new BigDecimal("0.0519"))
        .niche("Fashion")
        .category("OOTD")
        .contentSummary("Phối đồ phong cách Hàn Quốc")
        .build();

    when(metricRepository.latestForCreators(List.of(creator.getId()))).thenReturn(List.of(metric));

    Post post = Post.builder()
        .id(UUID.randomUUID())
        .creator(creator)
        .platformPostId("p_1")
        .postUrl("https://instagram.com/p/1")
        .caption("Outfit of the day")
        .views(30000L)
        .likes(5000L)
        .comments(300L)
        .shares(100L)
        .postedAt("2026-10-21T10:00:00")
        .build();

    when(postRepository.findByCreatorId(creator.getId())).thenReturn(List.of(post));

    CreatorDetailResponse res = creatorService.getCreatorDetail(creator.getId());

    assertThat(res.getId()).isEqualTo(creator.getId());
    assertThat(res.getUserName()).isEqualTo("ngoc_test");
    assertThat(res.getFollowers()).isEqualTo(85000L);
    assertThat(res.getAvgViews()).isEqualByComparingTo(new BigDecimal("25000.00"));
    assertThat(res.getAvgLikes()).isEqualByComparingTo(new BigDecimal("4200.00"));
    assertThat(res.getAvgComments()).isEqualByComparingTo(new BigDecimal("210.00"));
    assertThat(res.getAvgShares()).isEqualByComparingTo(new BigDecimal("75.00"));
    assertThat(res.getEngagementRate()).isEqualByComparingTo(new BigDecimal("0.0519"));
    assertThat(res.getNiche()).isEqualTo("Fashion");
    assertThat(res.getCategory()).isEqualTo("OOTD");
    assertThat(res.getContentSummary()).isEqualTo("Phối đồ phong cách Hàn Quốc");

    assertThat(new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(res).has("rateCard")).isFalse();

    // Recent posts
    assertThat(res.getRecentPosts()).hasSize(1);
    assertThat(res.getRecentPosts().get(0).getCaption()).isEqualTo("Outfit of the day");
  }

  @Test
  void getCreatorDetailPreservesVerificationWithoutRateCard() {
    User owner = User.builder().id(UUID.randomUUID()).role(Role.CREATOR).build();
    creator.setOwner(owner);
    creator.setSourceType(CreatorSourceType.AUTHORIZED);

    when(creatorRepository.findById(creator.getId())).thenReturn(Optional.of(creator));
    when(metricRepository.latestForCreators(List.of(creator.getId()))).thenReturn(List.of());
    when(postRepository.findByCreatorId(creator.getId())).thenReturn(List.of());

    CreatorDetailResponse res = creatorService.getCreatorDetail(creator.getId());

    assertThat(res.isVerifiedByInfluencerMatch()).isTrue();
    assertThat(res.getSourceType()).isEqualTo(CreatorSourceType.AUTHORIZED);
    assertThat(new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(res).has("rateCard")).isFalse();
  }
}
