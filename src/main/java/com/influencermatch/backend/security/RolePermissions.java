package com.influencermatch.backend.security;

import com.influencermatch.backend.user.enums.Role;
import java.util.*;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

public final class RolePermissions {
  private RolePermissions() {}

  private static final Set<Permission> COMMON =
      Collections.unmodifiableSet(
          EnumSet.of(
              Permission.VIEW_OWN_PROFILE,
              Permission.VIEW_OWN_NOTIFICATION,
              Permission.MANAGE_OWN_NOTIFICATION,
              Permission.VIEW_CREATOR_PROFILE));

  public static Set<Permission> forRole(Role role) {
    Objects.requireNonNull(role, "role");
    EnumSet<Permission> permissions = EnumSet.copyOf(COMMON);
    if (role == Role.BRAND)
      permissions.addAll(
          EnumSet.of(
              Permission.CREATE_BRAND_PROFILE,
              Permission.UPDATE_BRAND_PROFILE,
              Permission.VIEW_OWN_BRAND_DATA,
              Permission.CREATE_CAMPAIGN,
              Permission.VIEW_OWN_CAMPAIGN,
              Permission.UPDATE_CAMPAIGN,
              Permission.CHANGE_CAMPAIGN_STATUS,
              Permission.SEARCH_CREATOR,
              Permission.REQUEST_CREATOR_REFRESH,
              Permission.RUN_RECOMMENDATION,
              Permission.VIEW_OWN_RECOMMENDATION,
              Permission.MANAGE_OWN_SHORTLIST,
              Permission.MANAGE_OWN_RELATIONSHIP,
              Permission.MANAGE_OWN_COLLABORATION,
              Permission.RECORD_OWN_OUTCOME,
              Permission.RECORD_OWN_CREATOR_REVIEW,
              Permission.VIEW_OWN_HISTORY_AND_INSIGHTS,
              Permission.VIEW_PLAN,
              Permission.MANAGE_OWN_SUBSCRIPTION,
              Permission.VIEW_OWN_USAGE,
              Permission.VIEW_OWN_BILLING));
    if (role == Role.ADMIN || role == Role.DATA_MANAGER)
      permissions.addAll(
          EnumSet.of(
              Permission.VIEW_DATA_MONITORING,
              Permission.VIEW_COLLECTION_JOB,
              Permission.RETRY_COLLECTION_JOB,
              Permission.REFRESH_CREATOR_DATA,
              Permission.RESOLVE_CREATOR_DUPLICATE,
              Permission.RESOLVE_CREATOR_DATA_ISSUE,
              Permission.REVIEW_CREATOR_CLASSIFICATION,
              Permission.VIEW_DATA_OPERATION_LOG));
    if (role == Role.ADMIN)
      permissions.addAll(
          EnumSet.of(
              Permission.VIEW_USER,
              Permission.CHANGE_USER_STATUS,
              Permission.ASSIGN_USER_ROLE,
              Permission.MANAGE_PROVIDER_CONFIG,
              Permission.MANAGE_RECOMMENDATION_CONFIG,
              Permission.MANAGE_PLAN,
              Permission.MANAGE_SUBSCRIPTION,
              Permission.VIEW_SYSTEM_STATUS,
              Permission.VIEW_AUDIT_LOG,
              Permission.VIEW_BRAND_SUPPORT_DATA));
    return Collections.unmodifiableSet(permissions);
  }

  public static List<String> names(Role role) {
    return forRole(role).stream().map(Enum::name).sorted().toList();
  }

  public static Collection<? extends GrantedAuthority> authorities(Role role) {
    List<GrantedAuthority> result = new ArrayList<>();
    result.add(new SimpleGrantedAuthority("ROLE_" + role.name()));
    names(role).forEach(name -> result.add(new SimpleGrantedAuthority(name)));
    return List.copyOf(result);
  }
}
