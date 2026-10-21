package com.influencermatch.backend.security;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.influencermatch.backend.security.role.*;
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

  @Test void requestSnapshotIsImmutableAndNotCachedBetweenRequests() {
    var row = new RolePermissionOverride(); row.setRole(Role.BRAND);
    row.setPermission(Permission.MANAGE_OWN_SHORTLIST); row.setEnabled(false);
    when(overrides.findByRole(Role.BRAND)).thenReturn(List.of(row));
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
    var snapshot = service.resolve(Role.BRAND);
    assertThat(snapshot).doesNotContain(Permission.MANAGE_OWN_SHORTLIST);
    assertThatThrownBy(() -> snapshot.clear()).isInstanceOf(UnsupportedOperationException.class);
    row.setEnabled(true);
    assertThat(service.resolve(Role.BRAND)).isSameAs(snapshot);
    service.names(Role.BRAND); service.authorities(Role.BRAND);
    verify(overrides, times(1)).findByRole(Role.BRAND);
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
    assertThat(service.resolve(Role.BRAND)).contains(Permission.MANAGE_OWN_SHORTLIST);
    verify(overrides, times(2)).findByRole(Role.BRAND);
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
}
