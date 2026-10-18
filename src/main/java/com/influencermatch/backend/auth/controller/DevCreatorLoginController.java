package com.influencermatch.backend.auth.controller;

import com.influencermatch.backend.auth.dto.LoginResponse;
import com.influencermatch.backend.auth.service.DevCreatorLoginService;
import com.influencermatch.backend.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor
@Profile("dev & !prod")
@ConditionalOnProperty(name="application.auth.creator-mock.enabled",havingValue="true")
public class DevCreatorLoginController {
  private final DevCreatorLoginService service;
  @PostMapping("/auth/dev/creator-login")
  @Operation(summary="Create and log in a new mock Creator (dev only)",
      description="No Google credentials needed. Creates a new DEV_MOCK identity every call; does not log in existing users.", security={})
  public ApiResponse<LoginResponse> login() { return ApiResponse.ok(service.login()); }
}
