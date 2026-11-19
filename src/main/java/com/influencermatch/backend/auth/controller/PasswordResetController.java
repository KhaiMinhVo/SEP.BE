package com.influencermatch.backend.auth.controller;

import com.influencermatch.backend.auth.service.PasswordResetService;
import com.influencermatch.backend.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth/password-reset")
@RequiredArgsConstructor
@Tag(name = "Authentication")
public class PasswordResetController {
  private final PasswordResetService passwords;

  @PostMapping("/request")
  @Operation(summary = "Request a password reset OTP for an eligible Brand or Creator",
      security = {}, responses = {
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "202", description = "Request acknowledged"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "Email service unavailable")
      })
  public ResponseEntity<ApiResponse<ResetAcknowledgement>> request(@Valid @RequestBody ResetRequest request) {
    passwords.request(request.email());
    return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(
        ApiResponse.ok(new ResetAcknowledgement(
            "Nếu tài khoản đủ điều kiện, mã xác nhận sẽ được gửi đến email của bạn.")));
  }

  @PostMapping("/confirm")
  @Operation(summary = "Reset password with a single-use OTP and revoke all sessions",
      security = {}, responses = {
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "Password reset"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid input or OTP")
      })
  public ResponseEntity<Void> confirm(@Valid @RequestBody ResetConfirm request) {
    passwords.confirm(request.email(), request.otp(), request.newPassword());
    return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
  }

  public record ResetRequest(@NotBlank @Email @Size(max = 255) String email) {}

  public record ResetAcknowledgement(String message) {}

  public record ResetConfirm(
      @NotBlank @Email @Size(max = 255) String email,
      @NotBlank @Pattern(regexp = "[0-9]{6}") String otp,
      @NotBlank @Size(min = 8, max = 72) String newPassword) {}
}
