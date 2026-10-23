package com.influencermatch.backend.auth.dto;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.influencermatch.backend.exception.ValidationException;

public final class CreatorRegistrationDtos {
  private CreatorRegistrationDtos() {}
  public record InvitationRequest(@NotBlank @Email @Size(max=255) String email) {
    public InvitationRequest {email=email==null?null:email.trim().toLowerCase(java.util.Locale.ROOT);}
  }
  public record ResolveRequest(@NotBlank @Size(max=200) String token) {}
  public record InvitationResponse(UUID id,String email,Instant expiresAt) {}
  public record RegisterCreatorRequest(@NotBlank @Size(max=200) String invitationToken,
      @NotBlank @Size(min=8) String password,@NotBlank @Size(max=150) String fullName) {
    @JsonAnySetter public void rejectUnknown(String name,Object value) {
      throw new ValidationException("Creator email and role are determined by the invitation");
    }
  }
  public record PasswordSetupRequest(@NotBlank @Email String email) {}
  public record PasswordSetupConfirm(@NotBlank @Email String email,@NotNull @Pattern(regexp="[0-9]{6}") String otp,
      @NotBlank @Size(min=8) String newPassword) {}
}
