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
    assertThat(creator.getAuthorizedAt()).isEqualTo(authorizedAt);
    verify(em).refresh(creator);
  }
}
