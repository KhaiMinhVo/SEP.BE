package com.influencermatch.backend.security;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.influencermatch.backend.security.permission.*;
import com.influencermatch.backend.security.permission.model.RolePermissionOverride;
import com.influencermatch.backend.security.permission.repository.RolePermissionOverrideRepository;
import com.influencermatch.backend.security.permission.service.EffectivePermissionService;
import com.influencermatch.backend.user.enums.*;
import com.influencermatch.backend.user.model.User;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.*;

class EffectivePermissionServiceTest {
  final RolePermissionOverrideRepository overrides = mock(RolePermissionOverrideRepository.class);
  final EffectivePermissionService service = new EffectivePermissionService(overrides);
  @AfterEach void cleanup() { RequestContextHolder.resetRequestAttributes(); SecurityContextHolder.clearContext(); }

  @ParameterizedTest @EnumSource(Role.class)
  void absentOverridesPreserveDefaults(Role role) {
    assertThat(service.resolve(role)).containsExactlyInAnyOrderElementsOf(RolePermissions.forRole(role));
    assertThat(service.authorities(role)).extracting(a -> a.getAuthority()).contains("ROLE_" + role.name());
    if (role == Role.ADMIN) verifyNoInteractions(overrides);
  }

  @Test void securityFilterSnapshotIsReusedByDispatcherAndService() {
    var request = new MockHttpServletRequest();
    service.authorities(Role.CREATOR, request);
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    service.names(Role.CREATOR); service.resolve(Role.CREATOR);
    verify(overrides, times(1)).findByRole(Role.CREATOR);
  }

  @Test void overrideCannotGrantPermissionOutsideDefaultRole() {
    var row = new RolePermissionOverride(); row.setRole(Role.BRAND);
    row.setPermission(Permission.ASSIGN_USER_ROLE); row.setEnabled(true);
    when(overrides.findByRole(Role.BRAND)).thenReturn(List.of(row));
    assertThat(service.resolve(Role.BRAND)).doesNotContain(Permission.ASSIGN_USER_ROLE);
  }

  @Test void servicePermissionChecksUseAuthenticationSnapshotRatherThanStaticRole() {
    var user = User.builder().role(Role.BRAND).status(UserStatus.ACTIVE).build();
    SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
        user, null, List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_BRAND"))));
    assertThat(Permissions.has(Permission.CREATE_CAMPAIGN)).isFalse();
    assertThatThrownBy(() -> Permissions.require(Permission.CREATE_CAMPAIGN))
        .isInstanceOf(com.influencermatch.backend.exception.ForbiddenException.class);
  }

  @Test void disablingAllDefaultPermissionsReturnsEmptySetForNonAdmin() {
    var disabledAll = RolePermissions.forRole(Role.BRAND).stream()
        .map(p -> {
          var o = new RolePermissionOverride();
          o.setRole(Role.BRAND);
          o.setPermission(p);
          o.setEnabled(false);
          return o;
        })
        .toList();
    when(overrides.findByRole(Role.BRAND)).thenReturn(disabledAll);

    assertThat(service.resolve(Role.BRAND)).isEmpty();
  }

  @Test void adminRoleRetainsAllDefaultsEvenIfOverridesExistWithoutQueryingOverrides() {
    var result = service.resolve(Role.ADMIN);

    assertThat(result).containsExactlyInAnyOrderElementsOf(RolePermissions.forRole(Role.ADMIN));
    verifyNoInteractions(overrides);
  }
  @Test
  @org.junit.jupiter.api.DisplayName("resolveEffectivePermissions UTCID02 [N]: One disabled default permission is removed while others remain")
  void resolveEffectivePermissions_oneDefaultDisabledRetainsOthers() {
    var row = new RolePermissionOverride();
    row.setRole(Role.BRAND);
    row.setPermission(Permission.CREATE_CAMPAIGN);
    row.setEnabled(false);
    when(overrides.findByRole(Role.BRAND)).thenReturn(List.of(row));

    var resolved = service.resolve(Role.BRAND);

    var expected = EnumSet.copyOf(RolePermissions.forRole(Role.BRAND));
    expected.remove(Permission.CREATE_CAMPAIGN);
    assertThat(resolved).doesNotContain(Permission.CREATE_CAMPAIGN);
    assertThat(resolved).containsExactlyInAnyOrderElementsOf(expected);
  }
}
