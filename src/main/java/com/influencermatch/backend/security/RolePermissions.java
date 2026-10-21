package com.influencermatch.backend.security;

import com.influencermatch.backend.security.permission.Permission;
import com.influencermatch.backend.user.enums.Role;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.springframework.security.core.GrantedAuthority;

/**
 * Backward compatibility adapter forwarding to {@link com.influencermatch.backend.security.permission.RolePermissions}.
 */
public final class RolePermissions {
  private RolePermissions() {}

  public static Set<Permission> forRole(Role role) {
    return com.influencermatch.backend.security.permission.RolePermissions.forRole(role);
  }

  public static List<String> names(Role role) {
    return com.influencermatch.backend.security.permission.RolePermissions.names(role);
  }

  public static Collection<? extends GrantedAuthority> authorities(Role role) {
    return com.influencermatch.backend.security.permission.RolePermissions.authorities(role);
  }
}
