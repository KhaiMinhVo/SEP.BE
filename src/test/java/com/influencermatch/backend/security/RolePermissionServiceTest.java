package com.influencermatch.backend.security;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.influencermatch.backend.audit.service.AuditService;
import com.influencermatch.backend.exception.*;
import com.influencermatch.backend.security.role.*;
import com.influencermatch.backend.user.enums.*;
import com.influencermatch.backend.user.model.User;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import static com.influencermatch.backend.security.role.RolePermissionDtos.*;

class RolePermissionServiceTest {
  final RolePermissionConfigRepository configs=mock(RolePermissionConfigRepository.class);
  final RolePermissionOverrideRepository overrides=mock(RolePermissionOverrideRepository.class);
  final AuditService audit=mock(AuditService.class);
  final ValidatorFactory factory=Validation.buildDefaultValidatorFactory();
  final RolePermissionService service=new RolePermissionService(configs,overrides,audit,factory.getValidator());
  @BeforeEach void admin() {
    var user=User.builder().role(Role.ADMIN).status(UserStatus.ACTIVE).build();
    SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user,null,user.getAuthorities()));
  }
  @AfterEach void cleanup() { factory.close(); SecurityContextHolder.clearContext(); }
  RolePermissionConfig config() {
    var config=new RolePermissionConfig();config.setId(UUID.randomUUID());config.setRole(Role.BRAND);
    when(configs.lockByRole(Role.BRAND)).thenReturn(Optional.of(config));return config;
  }
  Update update(long version, Permission p, boolean enabled) { return new Update(version,List.of(new Change(p,enabled))); }
  @Test void disabledCapabilityIsPersistedAndAudited() {
    var config=config();
    var result=service.update(Role.BRAND,update(0,Permission.CREATE_CAMPAIGN,false));
    assertThat(result.version()).isEqualTo(1);
    assertThat(result.permissions()).filteredOn(p -> p.permission()==Permission.CREATE_CAMPAIGN)
        .allMatch(p -> !p.enabled() && p.defaultEnabled() && p.editable());
    verify(overrides).save(any());
    verify(audit).record(eq("UPDATE_ROLE_PERMISSIONS"),eq("ROLE_PERMISSION"),eq(config.getId()),any(),any(),anyString());
  }
  @Test void enablingDefaultIsNoop() {
    config();service.update(Role.BRAND,update(0,Permission.CREATE_CAMPAIGN,true));
    verifyNoInteractions(audit);verify(overrides,never()).save(any());verify(configs,never()).save(any());
  }
  @Test void staleVersionDoesNotWrite() {
    config();
    assertThatThrownBy(() -> service.update(Role.BRAND,update(1,Permission.CREATE_CAMPAIGN,false)))
        .isInstanceOfSatisfying(BusinessException.class,e -> assertThat(e.code()).isEqualTo(ErrorCode.OPTIMISTIC_CONFLICT));
    verifyNoInteractions(overrides,audit);
  }
  @Test void invalidAndOutOfRoleChangesAreRejectedBeforeLocking() {
    for (var request : List.of(update(0,Permission.ASSIGN_USER_ROLE,true),
        update(0,Permission.VIEW_OWN_NOTIFICATION,false),new Update(0L,List.of()),
        new Update(0L,List.of(new Change(Permission.CREATE_CAMPAIGN,true),new Change(Permission.CREATE_CAMPAIGN,false)))))
      assertThatThrownBy(() -> service.update(Role.BRAND,request)).isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> service.update(Role.ADMIN,update(0,Permission.VIEW_USER,false))).isInstanceOf(BusinessException.class);
    verifyNoInteractions(configs,overrides,audit);
  }
  @Test void nonAdminCannotCallServiceDirectly() {
    var user=User.builder().role(Role.BRAND).status(UserStatus.ACTIVE).build();
    SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user,null,user.getAuthorities()));
    assertThatThrownBy(service::catalog).isInstanceOf(ForbiddenException.class);
    assertThatThrownBy(() -> service.update(Role.BRAND,update(0,Permission.CREATE_CAMPAIGN,false))).isInstanceOf(ForbiddenException.class);
    verifyNoInteractions(configs,overrides,audit);
  }
}
