package com.influencermatch.backend.controller;

import com.influencermatch.backend.dto.ApiResponse;
import com.influencermatch.backend.dto.auth.LoginRequest;
import com.influencermatch.backend.dto.auth.LoginResponse;
import com.influencermatch.backend.dto.auth.RegisterRequest;
import com.influencermatch.backend.dto.auth.UserProfileResponse;
import com.influencermatch.backend.dto.auth.RefreshTokenRequest;
import com.influencermatch.backend.dto.auth.GoogleCodeExchangeRequest;
import com.influencermatch.backend.config.GoogleOAuthProperties;
import com.influencermatch.backend.exception.BusinessException;
import com.influencermatch.backend.exception.ErrorCode;
import com.influencermatch.backend.entity.User;
import com.influencermatch.backend.service.AuthService;
import com.influencermatch.backend.service.GoogleAuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.net.URI;

@Slf4j
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
@Tag(name = "Authentication")
public class AuthController {

    private final AuthService authService;
    private final GoogleAuthService googleAuthService;
    private final GoogleOAuthProperties googleOAuthProperties;

    @Operation(summary = "Register a new brand account", security = {})
    @PostMapping("/register")
    public ResponseEntity<ApiResponse<Void>> register(@Valid @RequestBody RegisterRequest request) {
        authService.register(request);
        return ResponseEntity
                .created(URI.create("/api/v1/auth/me"))
                .body(ApiResponse.empty());
    }

    @Operation(summary = "Log in and receive a JWT access token", security = {})
    @PostMapping("/login")
    public ResponseEntity<ApiResponse<LoginResponse>> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("Login successful.", authService.login(request)));
    }

    @Operation(summary = "Start Google OAuth2 login", security = {})
    @GetMapping("/google")
    public ResponseEntity<Void> googleLogin() {
        if (!googleOAuthProperties.isEnabled())
            throw new BusinessException(ErrorCode.GOOGLE_OAUTH_DISABLED, "Google login is not configured");
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create("/api/v1/oauth2/authorization/google")).build();
    }

    @Operation(summary = "Exchange a one-time Google login code for JWT tokens", security = {})
    @PostMapping("/google/exchange")
    public ResponseEntity<ApiResponse<LoginResponse>> exchangeGoogleCode(@Valid @RequestBody GoogleCodeExchangeRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("Google login successful.", googleAuthService.exchange(request.code())));
    }

    @Operation(summary = "Rotate a refresh token and issue a new token pair", security = {})
    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<LoginResponse>> refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(authService.refresh(request)));
    }

    @Operation(summary = "Revoke a refresh token", security = @SecurityRequirement(name = "BearerAuth"))
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody RefreshTokenRequest request) {
        authService.logout(request);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Get the current authenticated user's profile", security = @SecurityRequirement(name = "BearerAuth"))
    @GetMapping("/me")
    public ResponseEntity<ApiResponse<UserProfileResponse>> me(@AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(ApiResponse.ok("Profile retrieved successfully.", authService.getProfile(currentUser)));
    }
}
