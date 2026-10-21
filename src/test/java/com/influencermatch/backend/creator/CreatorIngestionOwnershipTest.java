package com.influencermatch.backend.creator;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.influencermatch.backend.creator.dto.CreatorIngestionRequest;
import com.influencermatch.backend.creator.enums.CreatorSourceType;
import com.influencermatch.backend.creator.model.*;
import com.influencermatch.backend.creator.repository.*;
import com.influencermatch.backend.creator.service.CreatorIngestionService;
import com.influencermatch.backend.user.model.User;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;

class CreatorIngestionOwnershipTest {
  @Test void ingestionPreservesVerifiedOwnerAndAuthorizationTimestamp() {
    var creators = mock(CreatorRepository.class);
    var metrics = mock(PublicCreatorMetricRepository.class);
    var posts = mock(PostRepository.class);
    var em = mock(EntityManager.class);
    var owner = User.builder().id(UUID.randomUUID()).build();
    var authorizedAt = LocalDateTime.now().minusDays(1);
    var creator = Creator.builder().id(UUID.randomUUID()).platform("TIKTOK").externalId("channel")
        .owner(owner).sourceType(CreatorSourceType.AUTHORIZED).authorizedAt(authorizedAt).build();
    when(creators.findLockedByPlatformAndExternalId("TIKTOK", "channel")).thenReturn(Optional.of(creator));
    when(creators.save(any())).thenAnswer(i -> i.getArgument(0));
    when(metrics.save(any())).thenAnswer(i -> i.getArgument(0));
    new CreatorIngestionService(creators, metrics, posts, em).ingestCreatorData(
        CreatorIngestionRequest.builder().platform("TIKTOK").externalId("channel").userName("updated").build());
    assertThat(creator.getUserName()).isEqualTo("updated");
    assertThat(creator.getOwner()).isSameAs(owner);
    assertThat(creator.getSourceType()).isEqualTo(CreatorSourceType.AUTHORIZED);
    verify(creators).acquireIdentityLock("TIKTOK:channel");
    verify(em).refresh(creator);
  }

  @Test
  void ingestionCreatesNewCreatorWithNullMasterAndPublicSourceType() {
    var creators = mock(CreatorRepository.class);
    var metrics = mock(PublicCreatorMetricRepository.class);
    var posts = mock(PostRepository.class);
    var em = mock(EntityManager.class);

    when(creators.findLockedByPlatformAndExternalId("FACEBOOK", "vanity_slug")).thenReturn(Optional.empty());
    when(creators.save(any())).thenAnswer(i -> {
      Creator c = i.getArgument(0);
      c.setId(UUID.randomUUID());
      return c;
    });
    when(metrics.save(any())).thenAnswer(i -> {
      PublicCreatorMetric m = i.getArgument(0);
      m.setId(UUID.randomUUID());
      return m;
    });

    var observedAt = LocalDateTime.now();
    var response = new CreatorIngestionService(creators, metrics, posts, em).ingestCreatorData(
        CreatorIngestionRequest.builder()
            .platform("FACEBOOK")
            .externalId("vanity_slug")
            .userName("fb_koc")
            .isStableExternalId(false)
            .crawlRunId("CR-001")
            .observedAt(observedAt)
            .followers(15000L)
            .build());

    assertThat(response.getStatus()).isEqualTo("SUCCESS");
    verify(creators).acquireIdentityLock("FACEBOOK:vanity_slug");

    var captor = org.mockito.ArgumentCaptor.forClass(Creator.class);
    verify(creators).save(captor.capture());
    Creator saved = captor.getValue();
    assertThat(saved.getMaster()).isNull();
    assertThat(saved.getOwner()).isNull();
    assertThat(saved.getSourceType()).isEqualTo(CreatorSourceType.PUBLIC);
    assertThat(saved.getIsStableId()).isFalse();

    var metricCaptor = org.mockito.ArgumentCaptor.forClass(PublicCreatorMetric.class);
    verify(metrics).save(metricCaptor.capture());
    PublicCreatorMetric savedMetric = metricCaptor.getValue();
    assertThat(savedMetric.getCrawlRunId()).isEqualTo("CR-001");
    assertThat(savedMetric.getObservedAt()).isEqualTo(observedAt);
  }

  @Test
  void ingestionSkipsDuplicateSnapshotWhenCrawlRunIdAlreadyExists() {
    var creators = mock(CreatorRepository.class);
    var metrics = mock(PublicCreatorMetricRepository.class);
    var posts = mock(PostRepository.class);
    var em = mock(EntityManager.class);

    var creatorId = UUID.randomUUID();
    var creator = Creator.builder()
        .id(creatorId)
        .platform("TIKTOK")
        .externalId("ch_123")
        .sourceType(CreatorSourceType.PUBLIC)
        .build();

    var existingMetric = PublicCreatorMetric.builder()
        .id(UUID.randomUUID())
        .creator(creator)
        .crawlRunId("CR-EXISTING-99")
        .build();

    when(creators.findLockedByPlatformAndExternalId("TIKTOK", "ch_123")).thenReturn(Optional.of(creator));
    when(creators.save(any())).thenAnswer(i -> i.getArgument(0));
    when(metrics.existsByCreatorIdAndCrawlRunId(creatorId, "CR-EXISTING-99")).thenReturn(true);
    when(metrics.findByCreatorId(creatorId)).thenReturn(Optional.of(existingMetric));

    var response = new CreatorIngestionService(creators, metrics, posts, em).ingestCreatorData(
        CreatorIngestionRequest.builder()
            .platform("TIKTOK")
            .externalId("ch_123")
            .crawlRunId("CR-EXISTING-99")
            .userName("koc_tiktok")
            .build());

    assertThat(response.getStatus()).isEqualTo("SKIPPED");
    assertThat(response.getMessage()).contains("Snapshot already recorded");
    verify(metrics, never()).save(any());
  }
}
