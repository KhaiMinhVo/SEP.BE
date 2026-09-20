package com.influencermatch.backend.user.service;

import com.influencermatch.backend.auth.dto.*;
import com.influencermatch.backend.auth.service.RefreshTokenService;
import com.influencermatch.backend.exception.NotFoundException;
import com.influencermatch.backend.user.controller.*;
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

  @Transactional(readOnly = true)
  public Page<AdminUserResponse> list(Pageable pageable) {
    return users.findAll(pageable).map(AdminUserResponse::from);
  }

  @Transactional(readOnly = true)
  public AdminUserResponse get(UUID id) {
    return AdminUserResponse.from(find(id));
  }

  @Transactional
  public AdminUserResponse changeStatus(UUID id, UpdateUserStatusRequest request) {
    User user = find(id);
    user.setStatus(request.status());
    if (request.status() != UserStatus.ACTIVE) refreshTokens.revokeAll(user.getId());
    return AdminUserResponse.from(user);
  }

  private User find(UUID id) {
    return users.findById(id).orElseThrow(() -> new NotFoundException("User not found"));
  }
}
