package com.influencermatch.backend.user.service;

import com.influencermatch.backend.audit.service.AuditService;
import com.influencermatch.backend.auth.dto.*;
import com.influencermatch.backend.auth.service.RefreshTokenService;
import com.influencermatch.backend.brand.repository.BrandProfileRepository;
import com.influencermatch.backend.exception.*;
import com.influencermatch.backend.exception.NotFoundException;
import com.influencermatch.backend.security.*;
import com.influencermatch.backend.user.controller.*;
import com.influencermatch.backend.user.dto.UpdateUserRoleRequest;
import com.influencermatch.backend.user.enums.*;
import com.influencermatch.backend.user.model.*;
import com.influencermatch.backend.user.repository.*;
import com.influencermatch.backend.user.repository.UserRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AdminUserService {
  private final UserRepository users;
  private final RefreshTokenService refreshTokens;
  private final AuditService audit;
  private final BrandProfileRepository profiles;

  @Transactional(readOnly = true)
  public Page<AdminUserResponse> list(Pageable pageable) {
    Permissions.require(Permission.VIEW_USER);
    return users.findAll(pageable).map(AdminUserResponse::from);
  }

  @Transactional(readOnly = true)
  public AdminUserResponse get(UUID id) {
    Permissions.require(Permission.VIEW_USER);
    return AdminUserResponse.from(find(id));
  }

  @Transactional
  public AdminUserResponse changeStatus(UUID id, UpdateUserStatusRequest request) {
    Permissions.require(Permission.CHANGE_USER_STATUS);
    users.lockRoleChanges();
    User user = find(id);
    if (user.getStatus() == request.status()) return AdminUserResponse.from(user);
    protectLastAdmin(user, user.getRole(), request.status());
    String before = user.getStatus().name();
    user.setStatus(request.status());
    invalidate(user);
    audit.record(
        "CHANGE_USER_STATUS",
        "User",
        id,
        java.util.Map.of("status", before),
        java.util.Map.of("status", user.getStatus().name()),
        request.reason());
    return AdminUserResponse.from(user);
  }

  @Transactional
  public AdminUserResponse changeRole(UUID id, UpdateUserRoleRequest request) {
    Permissions.require(Permission.ASSIGN_USER_ROLE);
    users.lockRoleChanges();
    User user = find(id);
    if (user.getRole() == request.role()) return AdminUserResponse.from(user);
    if (request.role() != Role.BRAND && profiles.existsByUserId(id))
      throw new BusinessException(
          ErrorCode.BRAND_ROLE_CHANGE_CONFLICT, "Use a separate internal account");
    protectLastAdmin(user, request.role(), user.getStatus());
    String before = user.getRole().name();
    user.setRole(request.role());
    invalidate(user);
    audit.record(
        "ASSIGN_USER_ROLE",
        "User",
        id,
        java.util.Map.of("role", before),
        java.util.Map.of("role", user.getRole().name()),
        request.reason());
    return AdminUserResponse.from(user);
  }

  private void invalidate(User user) {
    user.setAuthVersion(user.getAuthVersion() + 1);
    refreshTokens.revokeAll(user.getId());
  }

  private void protectLastAdmin(User user, Role nextRole, UserStatus nextStatus) {
    if (user.getRole() == Role.ADMIN
        && user.getStatus() == UserStatus.ACTIVE
        && (nextRole != Role.ADMIN || nextStatus != UserStatus.ACTIVE)
        && users.countByRoleAndStatus(Role.ADMIN, UserStatus.ACTIVE) <= 1)
      throw new BusinessException(
          ErrorCode.LAST_ACTIVE_ADMIN, "At least one active administrator is required");
  }

  private User find(UUID id) {
    return users.findById(id).orElseThrow(() -> new NotFoundException("User not found"));
  }
}
