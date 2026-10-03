package com.influencermatch.backend.auth.dto;

import com.influencermatch.backend.auth.controller.*;
import com.influencermatch.backend.auth.model.*;
import com.influencermatch.backend.auth.repository.*;
import com.influencermatch.backend.auth.service.*;

public record UpdateUserStatusRequest(
    @jakarta.validation.constraints.NotNull
        com.influencermatch.backend.user.enums.UserStatus status,
    @jakarta.validation.constraints.Size(max = 500) String reason) {
  public UpdateUserStatusRequest(com.influencermatch.backend.user.enums.UserStatus status) {
    this(status, null);
  }
}
