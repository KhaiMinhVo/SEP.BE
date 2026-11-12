package com.influencermatch.backend.security.evaluator;

import com.influencermatch.backend.creator.model.Creator;
import com.influencermatch.backend.creator.model.CreatorMaster;
import com.influencermatch.backend.creator.repository.CreatorRepository;
import com.influencermatch.backend.security.permission.Permissions;
import com.influencermatch.backend.user.model.User;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Method security expression evaluator for creator authorization checks.
 *
 * <p>Used in SpEL annotations like {@code @PreAuthorize("@creatorSecurity.isOwner(#creatorId)")}.
 */
@Slf4j
@Component("creatorSecurity")
@RequiredArgsConstructor
public class CreatorSecurityEvaluator {

  private final CreatorRepository creatorRepository;

  /**
   * Verifies if the authenticated actor owns the channel through its canonical {@link CreatorMaster}.
   *
   * @param creatorId UUID of the target Creator social account
   * @return true if the actor's user ID matches {@code creator.getMaster().getUser().getId()}, false otherwise
   */
  public boolean isOwner(UUID creatorId) {
    if (creatorId == null) {
      return false;
    }
    try {
      User actor = Permissions.actor();
      if (actor == null || actor.getId() == null) {
        return false;
      }
      return creatorRepository
          .findById(creatorId)
          .map(Creator::getMaster)
          .filter(Objects::nonNull)
          .map(CreatorMaster::getUser)
          .filter(Objects::nonNull)
          .map(User::getId)
          .filter(actor.getId()::equals)
          .isPresent();
    } catch (Exception ex) {
      log.debug("Creator ownership evaluation rejected for creatorId {}: {}", creatorId, ex.getMessage());
      return false;
    }
  }
}
