package com.influencermatch.backend.security;

import com.influencermatch.backend.exception.*;
import com.influencermatch.backend.user.enums.UserStatus;
import com.influencermatch.backend.user.model.User;
import org.springframework.security.core.context.SecurityContextHolder;

public final class Permissions {
  private Permissions() {}

  public static User actor() {
    var auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof User user))
      throw new BusinessException(ErrorCode.UNAUTHENTICATED, "Authentication required");
    if (user.getStatus() != UserStatus.ACTIVE)
      throw new BusinessException(ErrorCode.UNAUTHENTICATED, "Account is not active");
    return user;
  }

  public static boolean has(Permission permission) {
    return RolePermissions.forRole(actor().getRole()).contains(permission);
  }

  public static void require(Permission... allowed) {
    User user = actor();
    for (Permission permission : allowed)
      if (RolePermissions.forRole(user.getRole()).contains(permission)) return;
    throw new ForbiddenException("Permission required");
  }
}
