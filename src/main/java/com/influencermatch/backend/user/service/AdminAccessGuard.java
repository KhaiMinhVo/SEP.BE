package com.influencermatch.backend.user.service;

import com.influencermatch.backend.auth.service.AuthSessionStore;
import com.influencermatch.backend.exception.*;
import com.influencermatch.backend.security.permission.*;
import com.influencermatch.backend.user.enums.*;
import com.influencermatch.backend.user.model.User;
import com.influencermatch.backend.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Used inside the caller's transaction. Lock order agrees with auth and manual Claim writes. */
@Component
@RequiredArgsConstructor
public class AdminAccessGuard {
  final UserRepository users;
  final EntityManager em;
  final AuthSessionStore sessions;

  public void read(Permission permission) {
    Permissions.require(permission);
    var actor = Permissions.actor();
    long version = actor.getAuthVersion();
    var current =
        users
            .findById(actor.getId())
            .orElseThrow(() -> new ForbiddenException("Administrator unavailable"));
    em.refresh(current);
    check(current, permission, version);
  }

  void check(User current, Permission permission, long version) {
    if (current.getRole() != Role.ADMIN
        || current.getStatus() != UserStatus.ACTIVE
        || current.getAuthVersion() != version
        || !RolePermissions.forRole(Role.ADMIN).contains(permission))
      throw new ForbiddenException("Administrator authorization changed");
    sessions.require(sessions.currentId(), current, false);
  }

  public User lock(UUID target, Permission permission) {
    Permissions.require(permission);
    var actor = Permissions.actor();
    long version = actor.getAuthVersion();
    users.lockRoleChanges();
    var locked = new HashMap<UUID, User>();
    for (UUID id : new TreeSet<>(List.of(actor.getId(), target)))
      users
          .findLockedById(id)
          .ifPresent(
              u -> {
                em.refresh(u);
                locked.put(id, u);
              });
    var current = locked.get(actor.getId());
    if (current == null) throw new ForbiddenException("Administrator unavailable");
    check(current, permission, version);
    var result = locked.get(target);
    if (result == null) throw new NotFoundException("User not found");
    return result;
  }

  public void lockActor(Permission permission) {
    lock(Permissions.actor().getId(), permission);
  }
}
