package com.influencermatch.backend.user.dto;

import com.influencermatch.backend.user.enums.Role;
import jakarta.validation.constraints.*;

public record UpdateUserRoleRequest(@NotNull Role role, @NotBlank @Size(max = 500) String reason) {
  public UpdateUserRoleRequest {
    reason = reason == null ? null : reason.trim();
  }
}
