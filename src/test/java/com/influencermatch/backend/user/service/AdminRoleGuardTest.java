package com.influencermatch.backend.user.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.influencermatch.backend.audit.service.AuditService;
import com.influencermatch.backend.auth.service.RefreshTokenService;
import com.influencermatch.backend.brand.repository.BrandProfileRepository;
import com.influencermatch.backend.creator.enums.CreatorClaimStatus;
import com.influencermatch.backend.creator.repository.*;
import com.influencermatch.backend.exception.*;
import com.influencermatch.backend.support.TestActors;
import com.influencermatch.backend.user.dto.UpdateUserRoleRequest;
import com.influencermatch.backend.user.enums.Role;
import com.influencermatch.backend.user.repository.UserRepository;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.core.context.SecurityContextHolder;

class AdminRoleGuardTest {
  final UserRepository users=mock(UserRepository.class);
  final RefreshTokenService refresh=mock(RefreshTokenService.class);
  final AuditService audit=mock(AuditService.class);
  final BrandProfileRepository profiles=mock(BrandProfileRepository.class);
  final CreatorRepository creators=mock(CreatorRepository.class);
  final CreatorClaimRepository claims=mock(CreatorClaimRepository.class);
  final AdminUserService service=new AdminUserService(users,refresh,audit,profiles,creators,claims);
  @BeforeEach void login(){TestActors.authenticate(TestActors.user(Role.ADMIN));}
  @AfterEach void clear(){SecurityContextHolder.clearContext();}
  @Test void sameRoleDoesNotRevokeOrAudit(){
    var target=TestActors.user(Role.CREATOR);target.setAuthVersion(5);
    when(users.findById(target.getId())).thenReturn(Optional.of(target));
    service.changeRole(target.getId(),new UpdateUserRoleRequest(Role.CREATOR,"No change"));
    assertThat(target.getAuthVersion()).isEqualTo(5);verifyNoInteractions(refresh,audit,profiles,creators,claims);
  }
  @ParameterizedTest @ValueSource(booleans={true,false})
  void creatorOwnershipOrPendingClaimPreventsRoleChange(boolean owned){
    var target=TestActors.user(Role.CREATOR);when(users.findById(target.getId())).thenReturn(Optional.of(target));
    when(creators.existsByMasterUserId(target.getId())).thenReturn(owned);
    if(!owned)when(claims.existsByClaimantIdAndStatus(target.getId(),CreatorClaimStatus.PENDING)).thenReturn(true);
    assertThatThrownBy(()->service.changeRole(target.getId(),new UpdateUserRoleRequest(Role.DATA_MANAGER,"Change")))
        .isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.code()).isEqualTo(ErrorCode.CREATOR_ROLE_CHANGE_CONFLICT));
    assertThat(target.getRole()).isEqualTo(Role.CREATOR);assertThat(target.getAuthVersion()).isZero();verifyNoInteractions(refresh,audit);
  }
}
