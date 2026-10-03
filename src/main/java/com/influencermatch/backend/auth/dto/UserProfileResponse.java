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
public class UserProfileResponse {

  private UUID id;
  private String email;
  private String fullName;
  private String role;
  private String status;
  private java.util.List<String> permissions;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;
  private LocalDateTime lastLoginAt;
}
