package com.influencermatch.backend.billing.controller;

import com.influencermatch.backend.billing.dto.AdminPaymentDTOs.AdminPaymentResponse;
import com.influencermatch.backend.billing.dto.AdminPaymentDTOs.PaymentStatsResponse;
import com.influencermatch.backend.billing.dto.AdminPaymentDTOs.RefundRequest;
import com.influencermatch.backend.billing.enums.PaymentStatus;
import com.influencermatch.backend.billing.service.AdminPaymentService;
import com.influencermatch.backend.common.dto.ApiResponse;
import com.influencermatch.backend.common.dto.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDateTime;
import java.time.LocalDate;
import com.influencermatch.backend.billing.service.PaymentReportService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin/payments")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('MANAGE_SUBSCRIPTION')")
@Tag(
    name = "Admin Payments",
    description = "MANAGE_SUBSCRIPTION; Admin transaction management, monitoring, and refunds")
@SecurityRequirement(name = "BearerAuth")
public class AdminPaymentController {

  private final AdminPaymentService service;
  private final PaymentReportService reports;

  @Operation(
      summary = "List payment transactions with filters and pagination",
      description = "Supports filtering by keyword (ref, brand, user, plan), status, gateway, and date range.")
  @GetMapping
  public ApiResponse<PageResponse<AdminPaymentResponse>> list(
      @RequestParam(required = false) String search,
      @RequestParam(required = false) PaymentStatus status,
      @RequestParam(required = false) String gateway,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          LocalDateTime from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          LocalDateTime to,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(defaultValue = "desc") String sortDir) {

    PaymentReportService.validateRange(from, to);
    Sort.Direction direction =
        "asc".equalsIgnoreCase(sortDir) ? Sort.Direction.ASC : Sort.Direction.DESC;
    PageRequest pageRequest =
        PageRequest.of(
            Math.max(0, page),
            Math.min(Math.max(size, 1), 100),
            Sort.by(direction, "createdAt"));

    return ApiResponse.ok(service.listPayments(search, status, gateway, from, to, pageRequest));
  }

  @Operation(summary = "Get transaction financial statistics")
  @GetMapping("/stats")
  public ApiResponse<PaymentStatsResponse> stats(
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
    return ApiResponse.ok(reports.stats(from, to));
  }

  @GetMapping("/revenue")
  @Operation(summary = "Revenue by payment date, grouped by day or month (VND)")
  public ApiResponse<PaymentReportService.RevenueReport> revenue(
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(defaultValue = "day") String groupBy) {
    return ApiResponse.ok(reports.revenue(from, to, groupBy));
  }

  @Operation(summary = "Get transaction detail by ID")
  @GetMapping("/{id}")
  public ApiResponse<AdminPaymentResponse> get(@PathVariable UUID id) {
    return ApiResponse.ok(service.getPayment(id));
  }

  @Operation(summary = "Refund a paid transaction")
  @PostMapping("/{id}/refund")
  public ApiResponse<AdminPaymentResponse> refund(
      @PathVariable UUID id, @RequestBody(required = false) RefundRequest request) {
    return ApiResponse.ok(service.refundPayment(id, request));
  }
}
