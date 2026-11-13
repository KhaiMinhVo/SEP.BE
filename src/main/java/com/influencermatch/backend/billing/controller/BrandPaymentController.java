package com.influencermatch.backend.billing.controller;

import com.influencermatch.backend.billing.dto.BrandPaymentResponse;
import com.influencermatch.backend.billing.service.BrandPaymentService;
import com.influencermatch.backend.common.dto.ApiResponse;
import com.influencermatch.backend.common.dto.PageResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/billing/payments")
@PreAuthorize("hasAuthority('MANAGE_OWN_SUBSCRIPTION')")
@RequiredArgsConstructor
@Tag(name = "Billing & Payments")
public class BrandPaymentController {
  private final BrandPaymentService service;

  @GetMapping
  public ApiResponse<PageResponse<BrandPaymentResponse>> history(
      @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
    return ApiResponse.ok(service.history(PageRequest.of(Math.max(0, page),
        Math.min(100, Math.max(1, size)), Sort.by(Sort.Direction.DESC, "createdAt", "id"))));
  }

  @GetMapping("/{reference}")
  public ApiResponse<BrandPaymentResponse> status(@PathVariable String reference) {
    return ApiResponse.ok(service.status(reference));
  }
}
