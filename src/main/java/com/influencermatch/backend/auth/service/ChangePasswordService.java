package com.influencermatch.backend.auth.service;

import com.influencermatch.backend.auth.dto.ChangePasswordRequest;
import com.influencermatch.backend.exception.BusinessException;
import com.influencermatch.backend.exception.ErrorCode;
import com.influencermatch.backend.user.model.User;
import com.influencermatch.backend.user.repository.UserRepository;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ChangePasswordService {
  private final UserRepository users;
  private final PasswordEncoder encoder;
  private final AuthSessionStore sessions;

  @Transactional
  public void change(User actor, ChangePasswordRequest request) {
    users.lockRoleChanges();
    var user = users.findLockedById(actor.getId())
        .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED, "Account is unavailable."));
    sessions.require(sessions.currentId(), user, true);
    if (user.getPasswordHash() == null || user.getPasswordHash().isBlank()
        || !encoder.matches(request.currentPassword(), user.getPasswordHash())) {
      throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Current password is incorrect.");
    }
    if (request.newPassword().getBytes(StandardCharsets.UTF_8).length > 72) {
      throw new BusinessException(ErrorCode.VALIDATION_ERROR, "New password must not exceed 72 UTF-8 bytes.");
    }
    if (encoder.matches(request.newPassword(), user.getPasswordHash())) {
      throw new BusinessException(ErrorCode.VALIDATION_ERROR, "New password must differ from the current password.");
    }
    user.setPasswordHash(encoder.encode(request.newPassword()));
    user.setAuthVersion(user.getAuthVersion() + 1);
    users.save(user);
    sessions.revokeAll(user.getId());
  }
}
