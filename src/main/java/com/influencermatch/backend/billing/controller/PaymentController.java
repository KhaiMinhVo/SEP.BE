package com.influencermatch.backend.billing.controller;

import com.influencermatch.backend.billing.dto.PaymentRequest;
import com.influencermatch.backend.billing.dto.PaymentResponse;
import com.influencermatch.backend.billing.service.PaymentService;
import com.influencermatch.backend.brand.model.BrandProfile;
import com.influencermatch.backend.brand.repository.BrandProfileRepository;
import com.influencermatch.backend.common.dto.ApiResponse;
import com.influencermatch.backend.security.permission.*;
import jakarta.validation.Valid;
import com.influencermatch.backend.config.VnPayConfig;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/billing")
@RequiredArgsConstructor
@Tag(name = "Billing & Payments", description = "VNPay sandbox and PayOS QR subscription checkouts")
public class PaymentController {

  private final PaymentService paymentService;
  private final BrandProfileRepository brandProfileRepository;
  private final VnPayConfig vnPayConfig;

  @Operation(
      summary = "Create checkout payment URL for subscription plan",
      security = @SecurityRequirement(name = "BearerAuth"))
  @org.springframework.security.access.prepost.PreAuthorize(
      "hasAuthority('MANAGE_OWN_SUBSCRIPTION')")
  @PostMapping("/subscribe")
  public ResponseEntity<ApiResponse<PaymentResponse>> subscribeToPlan(
      @Valid @RequestBody PaymentRequest request,
      HttpServletRequest httpServletRequest) throws Exception {

    Permissions.require(
        Permission.MANAGE_OWN_SUBSCRIPTION);
    BrandProfile brandProfile =
        brandProfileRepository
            .findByUserId(Permissions.actor().getId())
            .orElseThrow(
                () ->
                    new com.influencermatch.backend.exception.NotFoundException(
                        "Brand Profile not found"));

    PaymentResponse checkout =
        paymentService.createCheckout(
            request.getPlanId(), brandProfile, httpServletRequest.getRemoteAddr());

    return ResponseEntity.ok(ApiResponse.ok(checkout));
  }

  @Operation(
      summary = "Create PayOS QR checkout payment URL for subscription plan",
      security = @SecurityRequirement(name = "BearerAuth"))
  @org.springframework.security.access.prepost.PreAuthorize(
      "hasAuthority('MANAGE_OWN_SUBSCRIPTION')")
  @PostMapping("/subscribe/payos")
  public ResponseEntity<ApiResponse<PaymentResponse>> subscribeToPlanWithPayOs(
      @Valid @RequestBody PaymentRequest request) {
    Permissions.require(Permission.MANAGE_OWN_SUBSCRIPTION);
    BrandProfile brandProfile =
        brandProfileRepository
            .findByUserId(Permissions.actor().getId())
            .orElseThrow(
                () ->
                    new com.influencermatch.backend.exception.NotFoundException(
                        "Brand Profile not found"));
    PaymentResponse checkout =
        paymentService.createPayOsCheckout(request.getPlanId(), brandProfile);
    return ResponseEntity.ok(ApiResponse.ok(checkout));
  }

  @io.swagger.v3.oas.annotations.Operation(
      summary = "VNPay return",
      description = "Public gateway callback; verified signature and transaction required",
      security = {})
  @GetMapping("/vnpay/return")
  public ResponseEntity<Void> vnpayReturn(HttpServletRequest request) {
    Map<String, String> fields = new HashMap<>();
    for (Enumeration<String> params = request.getParameterNames(); params.hasMoreElements(); ) {
      String fieldName = params.nextElement();
      String fieldValue = request.getParameter(fieldName);
      if ((fieldValue != null) && (fieldValue.length() > 0)) {
        fields.put(fieldName, fieldValue);
      }
    }

    var result = paymentService.processVnPayCallback(fields);
    boolean success = result == PaymentService.CallbackResult.ACCEPTED;
    boolean isPaid =
        success
            && "00".equals(fields.get("vnp_ResponseCode"))
            && "00".equals(fields.get("vnp_TransactionStatus"));

    String target = vnPayConfig.getFrontendReturnUrl();
    if (target == null || target.isBlank()) {
      target = "https://www.influencermatch.site/subscription";
    }
    String delimiter = target.contains("?") ? "&" : "?";
    String redirectUrl =
        target
            + delimiter
            + "payment="
            + (isPaid ? "success" : success ? "failed" : "pending")
            + "&txnRef="
            + java.net.URLEncoder.encode(fields.getOrDefault("vnp_TxnRef", ""), java.nio.charset.StandardCharsets.UTF_8);

    HttpHeaders headers = new HttpHeaders();
    headers.setLocation(URI.create(redirectUrl));
    return new ResponseEntity<>(headers, HttpStatus.FOUND);
  }

  @io.swagger.v3.oas.annotations.Operation(
      summary = "VNPay IPN",
      description = "Public gateway callback; verified signature and transaction required",
      security = {})
  @GetMapping("/vnpay/ipn")
  public ResponseEntity<Map<String, String>> vnpayIpn(HttpServletRequest request) {
    Map<String, String> fields = new HashMap<>();
    for (Enumeration<String> params = request.getParameterNames(); params.hasMoreElements(); ) {
      String fieldName = params.nextElement();
      String fieldValue = request.getParameter(fieldName);
      if ((fieldValue != null) && (fieldValue.length() > 0)) {
        fields.put(fieldName, fieldValue);
      }
    }

    var result = paymentService.processVnPayCallback(fields);
    boolean success = result == PaymentService.CallbackResult.ACCEPTED;
    Map<String, String> response = new HashMap<>();
    if (success) {
      response.put("RspCode", "00");
      response.put("Message", "Confirm Success");
    } else {
      response.put("RspCode", switch (result) {
        case INVALID_SIGNATURE, INVALID_MERCHANT -> "97";
        case NOT_FOUND -> "01";
        case INVALID_AMOUNT -> "04";
        default -> "99";
      });
      response.put("Message", result.name());
    }
    return ResponseEntity.ok(response);
  }

  @io.swagger.v3.oas.annotations.Operation(
      summary = "PayOS payment webhook",
      description = "Public webhook; raw payload signature and transaction are verified",
      security = {})
  @PostMapping("/payos/webhook")
  public ResponseEntity<Map<String, Boolean>> payOsWebhook(
      @RequestBody String rawBody) {
    var result = paymentService.processPayOsWebhook(rawBody);
    if (result == PaymentService.CallbackResult.ACCEPTED
        || result == PaymentService.CallbackResult.PENDING) {
      return ResponseEntity.ok(Map.of("success", true));
    }
    return ResponseEntity.badRequest().body(Map.of("success", false));
  }
}
