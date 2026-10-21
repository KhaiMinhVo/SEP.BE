package com.influencermatch.backend.security;

import com.influencermatch.backend.security.permission.Permission;
import com.influencermatch.backend.user.model.User;

/**
 * Backward compatibility adapter forwarding to {@link com.influencermatch.backend.security.permission.Permissions}.
 */
public final class Permissions {
  private Permissions() {}

  public static User actor() {
    return com.influencermatch.backend.security.permission.Permissions.actor();
  }

  public static boolean has(Permission permission) {
    return com.influencermatch.backend.security.permission.Permissions.has(permission);
  }

  public static void require(Permission... allowed) {
    com.influencermatch.backend.security.permission.Permissions.require(allowed);
  }
}
