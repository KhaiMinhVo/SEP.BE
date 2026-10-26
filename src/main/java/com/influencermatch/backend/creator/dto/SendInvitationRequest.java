package com.influencermatch.backend.creator.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SendInvitationRequest {

  private UUID creatorId;

  @NotBlank(message = "Email is required")
  @Email(message = "Invalid email format")
  @jakarta.validation.constraints.Size(max=255)
  private String email;
  public void setEmail(String value) { this.email=value==null?null:value.trim().toLowerCase(java.util.Locale.ROOT); }

  private UUID campaignId;
}
