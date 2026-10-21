package com.influencermatch.backend.billing.controller;

import com.influencermatch.backend.billing.dto.MockPaymentDtos.*;
import com.influencermatch.backend.billing.service.MockPaymentService;
import com.influencermatch.backend.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor
@Profile("dev & !prod")
@ConditionalOnProperty(name="application.payment.mock.enabled",havingValue="true")
public class MockPaymentController {
  private final MockPaymentService service;
  @PostMapping("/billing/mock/webhook")
  @PreAuthorize("hasAuthority('MANAGE_OWN_SUBSCRIPTION')")
  @Operation(summary="Simulate payment outcome (development only)",security=@SecurityRequirement(name="BearerAuth"))
  public ApiResponse<Response> webhook(@Valid @RequestBody Request request) {
    return ApiResponse.ok(service.process(request));
  }
}
