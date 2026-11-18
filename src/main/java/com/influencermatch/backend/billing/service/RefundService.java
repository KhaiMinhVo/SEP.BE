package com.influencermatch.backend.billing.service;

import com.influencermatch.backend.audit.service.AuditService;
import com.influencermatch.backend.billing.dto.AdminPaymentDTOs.*;
import com.influencermatch.backend.billing.enums.*;
import com.influencermatch.backend.billing.model.Payment;
import com.influencermatch.backend.billing.repository.*;
import com.influencermatch.backend.brand.repository.BrandProfileRepository;
import com.influencermatch.backend.exception.*;
import com.influencermatch.backend.security.permission.*;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Full refunds with a shared approval policy. Pending gateway refunds cannot be confirmed manually. */
@Service @RequiredArgsConstructor @Transactional
public class RefundService {
  private final PaymentRepository payments;
  private final SubscriptionRepository subscriptions;
  private final BrandProfileRepository brands;
  private final EntityManager em;
  private final AuditService audit;
  private final java.time.Clock clock;

  public RefundDetails request(UUID id, RefundRequest request) {
    Permissions.require(Permission.MANAGE_OWN_SUBSCRIPTION, Permission.MANAGE_SUBSCRIPTION);
    String reason = text(request == null ? null : request.getReason(), 1000, "Refund reason");
    Payment p = lock(id, true);
    requirePaid(p);
    if (p.getRefundStatus() != RefundStatus.NONE)
      throw new ValidationException("A refund request already exists for this payment");
    LocalDateTime now = now();
    eligible(p, now);
    p.setRefundStatus(RefundStatus.REQUESTED);
    p.setRefundRequestedAt(now);
    p.setRefundReason(reason);
    record(p, "REQUEST_REFUND", RefundStatus.NONE, reason);
    return RefundDetails.from(p);
  }

  public RefundDetails approve(UUID id, RefundApprovalRequest request) {
    Permissions.require(Permission.MANAGE_SUBSCRIPTION);
    Payment p = lock(id, false);
    requirePaid(p);
    boolean exception = request != null && request.exceptionType() != null;
    RefundStatus before = p.getRefundStatus();
    if (before != RefundStatus.REQUESTED && !(exception && before == RefundStatus.NONE))
      throw new ValidationException("Only a requested refund can be approved");
    String reason = text(request == null ? null : request.reason(), 1000, "Approval reason");
    if (!exception) {
      eligible(p, p.getRefundRequestedAt());
    }
    if (exception) p.setRefundExceptionType(request.exceptionType().name());
    if (p.getRefundRequestedAt() == null) {
      p.setRefundRequestedAt(now());
      p.setRefundReason(reason);
    }
    if (request != null && request.manual()) p.setGatewayRefundCode("MANUAL_SELECTED");
    p.setRefundStatus(RefundStatus.APPROVED);
    p.setRefundApprovedAt(now());
    p.setRefundApprovedBy(Permissions.actor().getId());
    p.setRefundDecisionReason(reason);
    p.getSubscription().setRefundHold(true);
    record(p, "APPROVE_REFUND", before, reason);
    return RefundDetails.from(p);
  }

  public RefundDetails reject(UUID id, RefundRequest request) {
    Permissions.require(Permission.MANAGE_SUBSCRIPTION);
    Payment p = lock(id, false);
    requirePaid(p);
    if (p.getRefundStatus() != RefundStatus.REQUESTED)
      throw new ValidationException("Only a requested refund can be rejected");
    String reason = text(request == null ? null : request.getReason(), 1000, "Rejection reason");
    p.setRefundStatus(RefundStatus.REJECTED);
    p.setRefundDecisionReason(reason);
    record(p, "REJECT_REFUND", RefundStatus.REQUESTED, reason);
    return RefundDetails.from(p);
  }

  public RefundDetails confirm(UUID id, RefundConfirmationRequest request) {
    Permissions.require(Permission.MANAGE_SUBSCRIPTION);
    Payment p = lock(id, false);
    requirePaid(p);
    if ((p.getRefundStatus() != RefundStatus.APPROVED && p.getRefundStatus() != RefundStatus.GATEWAY_FAILED)
        || !p.getSubscription().isRefundHold())
      throw new ValidationException("Refund must be approved and access held before confirmation");
    if (request == null || request.amount() == null || request.amount().signum() <= 0
        || p.getAmount().compareTo(request.amount()) != 0
        || !p.getCurrency().equals(request.currency()))
      throw new ValidationException("Refund amount and currency must exactly match the original payment");
    String reference = text(request.transferReference(), 255, "Bank transfer reference");
    RefundStatus before = p.getRefundStatus();
    complete(p, reference, Permissions.actor().getId());
    record(p, "CONFIRM_REFUND", before, reference);
    try {
      payments.flush();
    } catch (org.springframework.dao.DataIntegrityViolationException ex) {
      throw new ValidationException("Bank transfer reference is already recorded or refund data conflicts");
    }
    return RefundDetails.from(p);
  }

  void complete(Payment p, String reference, UUID actor) {
    p.setRefundTransferReference(reference);
    p.setRefundStatus(RefundStatus.COMPLETED);
    p.setStatus(PaymentStatus.REFUNDED);
    p.setRefundedAt(now());
    p.setRefundCompletedBy(actor);
    p.getSubscription().setStatus(SubscriptionStatus.CANCELLED);
    p.getSubscription().setCancelAtPeriodEnd(false);
    p.getSubscription().setRefundHold(false);
  }

  Payment lock(UUID id, boolean ownerAllowed) {
    Payment existing = payments.findById(id)
        .orElseThrow(() -> new ResourceNotFoundException("Payment not found"));
    if (ownerAllowed && !Permissions.has(Permission.MANAGE_SUBSCRIPTION)
        && !existing.getBrandProfile().getUser().getId().equals(Permissions.actor().getId()))
      throw new ForbiddenException("Payment belongs to another brand");
    // Match checkout/callback lock ordering; lock quota row before inspecting usage.
    brands.lockForBilling(existing.getBrandProfile().getId()).orElseThrow();
    Payment p = payments.findLockedById(id).orElseThrow();
    em.refresh(p);
    var sub = subscriptions.findLockedById(p.getSubscription().getId()).orElseThrow();
    em.refresh(sub);
    p.setSubscription(sub);
    return p;
  }

  LocalDateTime now() { return LocalDateTime.now(clock.withZone(java.time.ZoneId.systemDefault())); }

  private void requirePaid(Payment p) {
    if (p.getStatus() != PaymentStatus.PAID || p.getAmount() == null || p.getAmount().signum() <= 0
        || p.getCurrency() == null || !p.getCurrency().matches("[A-Z]{3}"))
      throw new ValidationException("A positive paid transaction with a valid currency is required");
  }

  private void eligible(Payment p, LocalDateTime requestedAt) {
    if (p.getPaidAt() == null || requestedAt == null || requestedAt.isBefore(p.getPaidAt())
        || requestedAt.isAfter(p.getPaidAt().plusHours(24)))
      throw new ValidationException("Refund must be requested within 24 hours of payment");
    var s = p.getSubscription();
    if (s.getStatus() != SubscriptionStatus.ACTIVE || s.isRefundHold()
        || s.getExpirationDate().isBefore(now().toLocalDate())
        || s.getUsedCampaignQuota() > 0 || s.getUsedRecommendationQuota() > 0 || s.getUsedRefreshQuota() > 0)
      throw new ValidationException("Only an active subscription with no used benefits is refundable");
  }

  private String text(String value, int max, String field) {
    if (value == null || value.isBlank() || value.trim().length() > max)
      throw new ValidationException(field + " is required and must be at most " + max + " characters");
    return value.trim();
  }

  private void record(Payment p, String action, RefundStatus before, String reason) {
    payments.save(p);
    subscriptions.save(p.getSubscription());
    audit.record(action, "Payment", p.getId(), before.name(), p.getRefundStatus().name(), reason);
  }
}
