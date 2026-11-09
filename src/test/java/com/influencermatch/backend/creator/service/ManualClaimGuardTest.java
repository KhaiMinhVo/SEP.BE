package com.influencermatch.backend.creator.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.influencermatch.backend.creator.model.*;
import com.influencermatch.backend.creator.repository.*;
import com.influencermatch.backend.exception.*;
import com.influencermatch.backend.user.model.User;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ManualClaimGuardTest {
  @Test
  void expiresAtNowIsExpiredAndNoInvitationIsConsumed() {
    var now = Instant.parse("2026-11-09T12:00:00Z");
    var repo = mock(CreatorInvitationRepository.class);
    var guard =
        new ManualClaimGuard(
            null,
            null,
            repo,
            null,
            null,
            null,
            mock(jakarta.persistence.EntityManager.class),
            Clock.fixed(now, ZoneOffset.UTC),
            null);
    var c = Creator.builder().id(UUID.randomUUID()).build();
    var u = User.builder().id(UUID.randomUUID()).email("creator@test.local").build();
    var i =
        CreatorInvitation.builder()
            .id(UUID.randomUUID())
            .targetCreator(c)
            .invitedEmail(u.getEmail())
            .expiresAt(LocalDateTime.ofInstant(now, ZoneOffset.UTC))
            .build();
    when(repo.findLockedById(i.getId())).thenReturn(Optional.of(i));
    var claim = CreatorClaim.builder().invitation(i).build();
    assertThatThrownBy(() -> guard.invitations(c, u, claim))
        .isInstanceOfSatisfying(
            ConflictException.class,
            e -> assertThat(e.code()).isEqualTo(ErrorCode.INVITATION_EXPIRED));
    assertThat(i.getStatus()).isEqualTo("PENDING");
  }
}
