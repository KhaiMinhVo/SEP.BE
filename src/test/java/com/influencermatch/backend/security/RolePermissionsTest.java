package com.influencermatch.backend.security;

import static org.assertj.core.api.Assertions.*;

import com.influencermatch.backend.user.enums.Role;
import java.util.*;
import org.junit.jupiter.api.Test;

class RolePermissionsTest {
  @Test
  void everyRoleMatchesTheDocumentedPermissionNames() {
    assertThat(RolePermissions.names(Role.BRAND))
        .containsExactlyInAnyOrder(
            "VIEW_OWN_PROFILE",
            "VIEW_OWN_NOTIFICATION",
            "MANAGE_OWN_NOTIFICATION",
            "VIEW_CREATOR_PROFILE",
            "CREATE_BRAND_PROFILE",
            "UPDATE_BRAND_PROFILE",
            "VIEW_OWN_BRAND_DATA",
            "CREATE_CAMPAIGN",
            "VIEW_OWN_CAMPAIGN",
            "UPDATE_CAMPAIGN",
            "CHANGE_CAMPAIGN_STATUS",
            "SEARCH_CREATOR",
            "REQUEST_CREATOR_REFRESH",
            "RUN_RECOMMENDATION",
            "VIEW_OWN_RECOMMENDATION",
            "MANAGE_OWN_SHORTLIST",
            "MANAGE_OWN_RELATIONSHIP",
            "MANAGE_OWN_COLLABORATION",
            "RECORD_OWN_OUTCOME",
            "RECORD_OWN_CREATOR_REVIEW",
            "VIEW_OWN_HISTORY_AND_INSIGHTS",
            "VIEW_PLAN",
            "MANAGE_OWN_SUBSCRIPTION",
            "VIEW_OWN_USAGE",
            "VIEW_OWN_BILLING");
    assertThat(RolePermissions.names(Role.DATA_MANAGER))
        .containsExactlyInAnyOrder(
            "VIEW_OWN_PROFILE",
            "VIEW_OWN_NOTIFICATION",
            "MANAGE_OWN_NOTIFICATION",
            "VIEW_CREATOR_PROFILE",
            "VIEW_DATA_MONITORING",
            "VIEW_COLLECTION_JOB",
            "RETRY_COLLECTION_JOB",
            "REFRESH_CREATOR_DATA",
            "RESOLVE_CREATOR_DUPLICATE",
            "RESOLVE_CREATOR_DATA_ISSUE",
            "REVIEW_CREATOR_CLASSIFICATION",
            "VIEW_DATA_OPERATION_LOG");
    assertThat(RolePermissions.names(Role.ADMIN))
        .containsExactlyInAnyOrder(
            "VIEW_OWN_PROFILE",
            "VIEW_OWN_NOTIFICATION",
            "MANAGE_OWN_NOTIFICATION",
            "VIEW_CREATOR_PROFILE",
            "VIEW_DATA_MONITORING",
            "VIEW_COLLECTION_JOB",
            "RETRY_COLLECTION_JOB",
            "REFRESH_CREATOR_DATA",
            "RESOLVE_CREATOR_DUPLICATE",
            "RESOLVE_CREATOR_DATA_ISSUE",
            "REVIEW_CREATOR_CLASSIFICATION",
            "VIEW_DATA_OPERATION_LOG",
            "VIEW_USER",
            "CHANGE_USER_STATUS",
            "ASSIGN_USER_ROLE",
            "MANAGE_PROVIDER_CONFIG",
            "MANAGE_RECOMMENDATION_CONFIG",
            "MANAGE_PLAN",
            "MANAGE_SUBSCRIPTION",
            "VIEW_SYSTEM_STATUS",
            "VIEW_AUDIT_LOG",
            "VIEW_BRAND_SUPPORT_DATA");
  }

  @Test
  void fixedMatrixHasExactly43PermissionsAndNoWildcard() {
    assertThat(Permission.values()).hasSize(43);
    assertThat(RolePermissions.forRole(Role.BRAND)).hasSize(25);
    assertThat(RolePermissions.forRole(Role.DATA_MANAGER)).hasSize(12);
    assertThat(RolePermissions.forRole(Role.ADMIN)).hasSize(22);
    Set<Permission> all = EnumSet.noneOf(Permission.class);
    for (Role role : Role.values()) {
      all.addAll(RolePermissions.forRole(role));
      assertThat(RolePermissions.names(role)).doesNotContain("*", "REVIEW_CREATOR_CLAIM");
      assertThat(RolePermissions.authorities(role))
          .anyMatch(a -> a.getAuthority().equals("ROLE_" + role));
      assertThatThrownBy(() -> RolePermissions.forRole(role).clear())
          .isInstanceOf(UnsupportedOperationException.class);
    }
    assertThat(all).containsExactlyInAnyOrder(Permission.values());
  }

  @Test
  void onlyFourCommonPermissionsAreSharedByAllThreeRoles() {
    Set<Permission> common = EnumSet.copyOf(RolePermissions.forRole(Role.BRAND));
    common.retainAll(RolePermissions.forRole(Role.ADMIN));
    common.retainAll(RolePermissions.forRole(Role.DATA_MANAGER));
    assertThat(common)
        .containsExactlyInAnyOrder(
            Permission.VIEW_OWN_PROFILE,
            Permission.VIEW_OWN_NOTIFICATION,
            Permission.MANAGE_OWN_NOTIFICATION,
            Permission.VIEW_CREATOR_PROFILE);
    Set<Permission> operations = EnumSet.copyOf(RolePermissions.forRole(Role.DATA_MANAGER));
    operations.removeAll(common);
    assertThat(operations).hasSize(8).isSubsetOf(RolePermissions.forRole(Role.ADMIN));
    assertThat(RolePermissions.forRole(Role.ADMIN))
        .doesNotContain(
            Permission.CREATE_CAMPAIGN,
            Permission.UPDATE_CAMPAIGN,
            Permission.CREATE_BRAND_PROFILE,
            Permission.UPDATE_BRAND_PROFILE);
    assertThat(RolePermissions.forRole(Role.DATA_MANAGER))
        .doesNotContain(
            Permission.VIEW_USER,
            Permission.MANAGE_PLAN,
            Permission.MANAGE_SUBSCRIPTION,
            Permission.VIEW_BRAND_SUPPORT_DATA);
  }
}
