package com.influencermatch.backend.auth.dto;

import com.influencermatch.backend.auth.controller.*;
import com.influencermatch.backend.auth.model.*;
import com.influencermatch.backend.auth.repository.*;
import com.influencermatch.backend.auth.service.*;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LoginResponse {

  private String accessToken;

  @com.fasterxml.jackson.annotation.JsonIgnore private String refreshToken;
  @com.fasterxml.jackson.annotation.JsonIgnore private java.time.Instant refreshExpiresAt;

  @Builder.Default private String tokenType = "Bearer";

  /** Seconds until the access token expires. */
  private long expiresIn;

  private UserProfile user;

  @Getter
  @Setter
  @NoArgsConstructor
  @AllArgsConstructor
  @Builder
  public static class UserProfile {
    private UUID id;
    private UUID brandProfileId;
    private String onboardingStatus;
    private String email;
    private String fullName;
    private String role;
    private String status;
    private java.util.List<String> permissions;
    private LocalDateTime lastLoginAt;
  }
}
