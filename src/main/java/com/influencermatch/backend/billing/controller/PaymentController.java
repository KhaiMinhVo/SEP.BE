package com.influencermatch.backend.billing.controller;

import com.influencermatch.backend.billing.dto.PaymentRequest;
import com.influencermatch.backend.billing.dto.PaymentResponse;
import com.influencermatch.backend.billing.service.PaymentService;
import com.influencermatch.backend.brand.model.BrandProfile;
import com.influencermatch.backend.brand.repository.BrandProfileRepository;
import com.influencermatch.backend.common.dto.ApiResponse;
import com.influencermatch.backend.security.permission.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
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
@Tag(name = "Billing & Payments", description = "VNPay payment gateway integration and subscription checkouts")
public class PaymentController {

  private final PaymentService paymentService;
  private final BrandProfileRepository brandProfileRepository;

  @Operation(
      summary = "Create checkout payment URL for subscription plan",
      security = @SecurityRequirement(name = "BearerAuth"))
  @org.springframework.security.access.prepost.PreAuthorize(
      "hasAuthority('MANAGE_OWN_SUBSCRIPTION')")
  @PostMapping("/subscribe")
  public ResponseEntity<ApiResponse<PaymentResponse>> subscribeToPlan(
      @Valid @RequestBody PaymentRequest request,
      Authentication authentication,
      HttpServletRequest httpServletRequest)
      throws Exception {

    Permissions.require(
        Permission.MANAGE_OWN_SUBSCRIPTION);
    BrandProfile brandProfile =
        brandProfileRepository
            .findByUserId(Permissions.actor().getId())
            .orElseThrow(
                () ->
                    new com.influencermatch.backend.exception.NotFoundException(
                        "Brand Profile not found"));

    String ipAddress = httpServletRequest.getRemoteAddr();
    if (ipAddress == null) {
      ipAddress = httpServletRequest.getRemoteAddr();
    }

    PaymentResponse checkout =
        paymentService.createCheckout(request.getPlanId(), brandProfile, ipAddress);

    return ResponseEntity.ok(ApiResponse.ok(checkout));
  }

  @io.swagger.v3.oas.annotations.Operation(
      summary = "VNPay return",
      description = "Public gateway callback; verified signature and transaction required",
      security = {})
  @GetMapping("/vnpay/return")
  public ResponseEntity<String> vnpayReturn(HttpServletRequest request) {
    Map<String, String> fields = new HashMap<>();
    for (Enumeration<String> params = request.getParameterNames(); params.hasMoreElements(); ) {
      String fieldName = params.nextElement();
      String fieldValue = request.getParameter(fieldName);
      if ((fieldValue != null) && (fieldValue.length() > 0)) {
        fields.put(fieldName, fieldValue);
      }
    }

    boolean success = paymentService.processVnPayReturn(fields);

    if (success) {
      if (!"00".equals(fields.get("vnp_ResponseCode"))
          || !"00".equals(fields.get("vnp_TransactionStatus")))
        return ResponseEntity.ok("Payment was not completed. You can return to the app.");
      return ResponseEntity.ok("Payment Success! You can close this tab and return to the app.");
    } else {
      return ResponseEntity.badRequest().body("Payment Failed or Invalid Signature!");
    }
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

    boolean success = paymentService.processVnPayReturn(fields);
    Map<String, String> response = new HashMap<>();
    if (success) {
      response.put("RspCode", "00");
      response.put("Message", "Confirm Success");
    } else {
      response.put("RspCode", "97");
      response.put("Message", "Invalid Signature or Failed");
    }
    return ResponseEntity.ok(response);
  }
}
