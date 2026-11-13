package com.influencermatch.backend.auth.controller;

import com.influencermatch.backend.auth.dto.*;
import com.influencermatch.backend.auth.dto.LoginRequest;
import com.influencermatch.backend.auth.dto.LoginResponse;
import com.influencermatch.backend.auth.dto.RefreshTokenRequest;
import com.influencermatch.backend.auth.dto.RegisterRequest;
import com.influencermatch.backend.auth.dto.UserProfileResponse;
import com.influencermatch.backend.auth.model.*;
import com.influencermatch.backend.auth.repository.*;
import com.influencermatch.backend.auth.service.*;
import com.influencermatch.backend.auth.service.AuthService;
import com.influencermatch.backend.common.dto.ApiResponse;
import com.influencermatch.backend.exception.BusinessException;
import com.influencermatch.backend.user.model.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
@Tag(name = "Authentication")
public class AuthController {

  private final AuthService authService;
  private final AuthCookies cookies;
  private final RefreshTokenService refreshTokens;
  private final RegistrationSupport registration;

  @GetMapping("/policies")
  @Operation(
      summary = "Current Terms and Privacy versions",
      security = {})
  public ApiResponse<java.util.Map<String, String>> policies() {
    return ApiResponse.ok(registration.policies());
  }

  @GetMapping("/csrf")
  @Operation(
      summary = "Get browser CSRF token before authentication requests",
      security = {})
  public ApiResponse<java.util.Map<String, String>> csrf(
      jakarta.servlet.http.HttpServletRequest request,
      jakarta.servlet.http.HttpServletResponse response) {
    var token =
        (org.springframework.security.web.csrf.CsrfToken)
            request.getAttribute(org.springframework.security.web.csrf.CsrfToken.class.getName());
    response.setHeader("Cache-Control", "no-store");
    return ApiResponse.ok(
        java.util.Map.of("token", token.getToken(), "headerName", token.getHeaderName()));
  }

  private final EmailVerificationService verification;

  @PostMapping("/verify-email")
  @Operation(
      summary = "Verify email with a six-digit OTP",
      security = {})
  public ApiResponse<Void> verifyEmail(@Valid @RequestBody VerifyEmailRequest request) {
    verification.verify(request.email(), request.otp());
    return ApiResponse.empty();
  }

  @PostMapping("/resend-verification")
  @Operation(
      summary = "Resend email verification OTP",
      security = {})
  public ApiResponse<Void> resend(
      @Valid @RequestBody CreatorRegistrationDtos.PasswordSetupRequest request) {
    verification.resend(request.email());
    return ApiResponse.ok("If eligible, a verification email has been sent.", null);
  }

  @Operation(
      summary = "Register a new brand account",
      security = {})
  @PostMapping("/register")
  public ResponseEntity<ApiResponse<Void>> register(@Valid @RequestBody RegisterRequest request) {
    authService.register(request);
    return ResponseEntity.created(URI.create("/api/v1/auth/me"))
        .body(ApiResponse.ok("Account created; verification email sent.", null));
  }

  @Operation(
      summary = "Log in and receive a JWT access token",
      security = {})
  @PostMapping("/login")
  public ResponseEntity<ApiResponse<LoginResponse>> login(
      @Valid @RequestBody LoginRequest request, jakarta.servlet.http.HttpServletResponse response) {
    var result = authService.login(request);
    cookies.write(response, result);
    return ResponseEntity.ok(ApiResponse.ok("Login successful.", result));
  }

  @Operation(
      summary = "Rotate IM_REFRESH cookie within the original seven-day session; CSRF required",
      security = {})
  @PostMapping("/refresh")
  public ResponseEntity<ApiResponse<LoginResponse>> refresh(
      jakarta.servlet.http.HttpServletRequest request,
      jakarta.servlet.http.HttpServletResponse response) {
    try {
      var result = authService.refresh(new RefreshTokenRequest(cookies.read(request)));
      cookies.write(response, result);
      return ResponseEntity.ok(ApiResponse.ok(result));
    } catch (BusinessException ex) {
      cookies.clear(response);
      throw ex;
    }
  }

  @Operation(
      summary = "Logout current session using refresh cookie and CSRF; access JWT may be expired",
      security = {})
  @PostMapping("/logout")
  public ResponseEntity<Void> logout(
      jakarta.servlet.http.HttpServletRequest request,
      jakarta.servlet.http.HttpServletResponse response) {
    refreshTokens.revoke(cookies.read(request));
    cookies.clear(response);
    return ResponseEntity.noContent().build();
  }

  private final ChangePasswordService changePasswords;

  @PostMapping("/change-password")
  @Operation(summary = "Change own password and invalidate all sessions; current password and CSRF required",
      security = @SecurityRequirement(name = "BearerAuth"))
  public ResponseEntity<Void> changePassword(
      @AuthenticationPrincipal User user,
      @Valid @RequestBody ChangePasswordRequest request,
      jakarta.servlet.http.HttpServletResponse response) {
    changePasswords.change(user, request);
    cookies.clear(response);
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/logout-all")
  @Operation(summary = "Invalidate every session, including this one")
  public ResponseEntity<Void> logoutAll(
      @AuthenticationPrincipal User user, jakarta.servlet.http.HttpServletResponse response) {
    refreshTokens.logoutAll(user);
    cookies.clear(response);
    return ResponseEntity.noContent().build();
  }

  @Operation(
      summary = "Get the current authenticated user's profile",
      security = @SecurityRequirement(name = "BearerAuth"))
  @GetMapping("/me")
  @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('VIEW_OWN_PROFILE')")
  public ResponseEntity<ApiResponse<UserProfileResponse>> me(
      @AuthenticationPrincipal User currentUser) {
    return ResponseEntity.ok(
        ApiResponse.ok("Profile retrieved successfully.", authService.getProfile(currentUser)));
  }
}
