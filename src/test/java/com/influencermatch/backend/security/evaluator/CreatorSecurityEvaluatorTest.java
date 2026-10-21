package com.influencermatch.backend.security.evaluator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.influencermatch.backend.creator.model.Creator;
import com.influencermatch.backend.creator.model.CreatorMaster;
import com.influencermatch.backend.creator.repository.CreatorRepository;
import com.influencermatch.backend.user.enums.Role;
import com.influencermatch.backend.user.enums.UserStatus;
import com.influencermatch.backend.user.model.User;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(MockitoExtension.class)
class CreatorSecurityEvaluatorTest {

  @Mock
  private CreatorRepository creatorRepository;

  private CreatorSecurityEvaluator evaluator;

  private User actor;

  @BeforeEach
  void setUp() {
    evaluator = new CreatorSecurityEvaluator(creatorRepository);
    actor = User.builder()
        .id(UUID.randomUUID())
        .email("actor@koc.test")
        .role(Role.CREATOR)
        .status(UserStatus.ACTIVE)
        .build();
    SecurityContextHolder.getContext().setAuthentication(
        new UsernamePasswordAuthenticationToken(actor, null, actor.getAuthorities()));
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  @Test
  @DisplayName("isOwner returns true when creator.master.user.id matches authenticated actor.id")
  void isOwnerReturnsTrueWhenMatching() {
    UUID creatorId = UUID.randomUUID();
    CreatorMaster master = CreatorMaster.builder()
        .user(actor)
        .canonicalName("KOC Actor")
        .build();
    Creator creator = Creator.builder()
        .master(master)
        .build();

    when(creatorRepository.findById(creatorId)).thenReturn(Optional.of(creator));

    boolean isOwner = evaluator.isOwner(creatorId);
    assertThat(isOwner).isTrue();
  }

  @Test
  @DisplayName("isOwner returns false when creator.master.user.id does not match actor.id")
  void isOwnerReturnsFalseWhenDifferentUser() {
    UUID creatorId = UUID.randomUUID();
    User otherUser = User.builder()
        .id(UUID.randomUUID())
        .email("other@koc.test")
        .build();
    CreatorMaster master = CreatorMaster.builder()
        .user(otherUser)
        .canonicalName("Other KOC")
        .build();
    Creator creator = Creator.builder()
        .master(master)
        .build();

    when(creatorRepository.findById(creatorId)).thenReturn(Optional.of(creator));

    boolean isOwner = evaluator.isOwner(creatorId);
    assertThat(isOwner).isFalse();
  }

  @Test
  @DisplayName("isOwner returns false when creator has no master")
  void isOwnerReturnsFalseWhenMasterNull() {
    UUID creatorId = UUID.randomUUID();
    Creator creator = Creator.builder()
        .master(null)
        .build();

    when(creatorRepository.findById(creatorId)).thenReturn(Optional.of(creator));

    boolean isOwner = evaluator.isOwner(creatorId);
    assertThat(isOwner).isFalse();
  }

  @Test
  @DisplayName("isOwner returns false when creator is not found")
  void isOwnerReturnsFalseWhenCreatorNotFound() {
    UUID creatorId = UUID.randomUUID();
    when(creatorRepository.findById(creatorId)).thenReturn(Optional.empty());

    boolean isOwner = evaluator.isOwner(creatorId);
    assertThat(isOwner).isFalse();
  }

  @Test
  @DisplayName("isOwner returns false when creatorId is null")
  void isOwnerReturnsFalseWhenCreatorIdNull() {
    boolean isOwner = evaluator.isOwner(null);
    assertThat(isOwner).isFalse();
    verifyNoInteractions(creatorRepository);
  }

  @Test
  @DisplayName("isOwner returns false when no authentication in SecurityContext")
  void isOwnerReturnsFalseWhenUnauthenticated() {
    SecurityContextHolder.clearContext();
    UUID creatorId = UUID.randomUUID();

    boolean isOwner = evaluator.isOwner(creatorId);
    assertThat(isOwner).isFalse();
  }
}
