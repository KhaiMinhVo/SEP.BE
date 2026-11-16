package com.influencermatch.backend.billing.service;

import com.influencermatch.backend.billing.dto.BrandPaymentResponse;
import com.influencermatch.backend.billing.model.Payment;
import com.influencermatch.backend.billing.repository.PaymentRepository;
import com.influencermatch.backend.common.dto.PageResponse;
import com.influencermatch.backend.exception.ResourceNotFoundException;
import com.influencermatch.backend.security.permission.Permission;
import com.influencermatch.backend.security.permission.Permissions;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BrandPaymentService {
  private final PaymentRepository payments;

  public PageResponse<BrandPaymentResponse> history(Pageable pageable) {
    Permissions.require(Permission.MANAGE_OWN_SUBSCRIPTION);
    UUID actor = Permissions.actor().getId();
    return PageResponse.from(payments.findAll((root, query, cb) ->
        cb.equal(root.get("brandProfile").get("user").get("id"), actor), pageable)
        .map(this::response));
  }

  public BrandPaymentResponse status(String reference) {
    Permissions.require(Permission.MANAGE_OWN_SUBSCRIPTION);
    var payment = payments.findByTransactionCode(reference)
        .filter(p -> p.getBrandProfile().getUser().getId().equals(Permissions.actor().getId()))
        .orElseThrow(() -> new ResourceNotFoundException("Payment not found"));
    return response(payment);
  }

  private BrandPaymentResponse response(Payment payment) {
    var sub = payment.getSubscription();
    return new BrandPaymentResponse(payment.getId(), payment.getTransactionCode(),
        sub.getPlan().getPlanName(), payment.getAmount(), payment.getCurrency(),
        payment.getPaymentGateway(), payment.getStatus(), payment.getCreatedAt(),
        payment.getPaidAt(), sub.getStartDate(), sub.getExpirationDate(), payment.getFailureReason());
  }
}
