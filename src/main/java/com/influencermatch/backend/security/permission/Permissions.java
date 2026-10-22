package com.influencermatch.backend.security.permission;

import com.influencermatch.backend.exception.*;
import com.influencermatch.backend.user.enums.UserStatus;
import com.influencermatch.backend.user.model.User;
import org.springframework.security.core.context.SecurityContextHolder;

public final class Permissions {
  private Permissions() {}

  public static User actor() {
    User user = actorOrNull();
    if (user == null)
      throw new BusinessException(ErrorCode.UNAUTHENTICATED, "Authentication required");
    return user;
  }

  public static User actorOrNull() {
    var auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof User user))
      return null;
    if (user.getStatus() != UserStatus.ACTIVE)
      return null;
    return user;
  }

  public static boolean has(Permission permission) {
    actor();
    return SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
        .anyMatch(a -> a.getAuthority().equals(permission.name()));
  }

  public static void require(Permission... allowed) {
    actor();
    for (Permission permission : allowed)
      if (has(permission)) return;
    throw new ForbiddenException("Permission required");
  }
}
