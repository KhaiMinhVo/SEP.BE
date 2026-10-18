package com.influencermatch.backend.billing.service;

import com.influencermatch.backend.billing.enums.*;
import com.influencermatch.backend.billing.model.Payment;
import com.influencermatch.backend.billing.repository.*;
import com.influencermatch.backend.brand.repository.BrandProfileRepository;
import jakarta.persistence.EntityManager;
import java.time.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Shared transaction boundary rules for verified gateway callbacks and dev mock payments. */
@Service @RequiredArgsConstructor
@Transactional(propagation=Propagation.MANDATORY)
public class PaymentSettlementService {
  private final BrandProfileRepository brands;
  private final PaymentRepository payments;
  private final SubscriptionRepository subscriptions;
  private final EntityManager em;

  public Payment lock(Payment existing) {
    brands.lockForBilling(existing.getBrandProfile().getId()).orElseThrow();
    Payment payment=payments.findLockedByTransactionCode(existing.getTransactionCode()).orElseThrow();
    em.refresh(payment);
    return payment;
  }

  public boolean apply(Payment payment, boolean success, String failureReason) {
    if(payment.getStatus()==PaymentStatus.PAID) return success;
    if(payment.getStatus()==PaymentStatus.FAILED) return !success;
    if(payment.getStatus()!=PaymentStatus.PENDING) return false;
    var sub=payment.getSubscription();
    em.refresh(sub);
    if(sub.getStatus()!=SubscriptionStatus.PENDING) return false;
    if(success) {
      if(subscriptions.existsByBrandProfileIdAndStatusAndExpirationDateGreaterThanEqual(
          payment.getBrandProfile().getId(),SubscriptionStatus.ACTIVE,LocalDate.now())) return false;
      payment.setStatus(PaymentStatus.PAID);
      payment.setPaidAt(LocalDateTime.now());
      sub.setStatus(SubscriptionStatus.ACTIVE);
      sub.setStartDate(LocalDate.now());
      sub.setExpirationDate(LocalDate.now().plusDays(sub.getPlan().getDurationDays()));
    } else {
      payment.setStatus(PaymentStatus.FAILED);
      payment.setFailureReason(failureReason);
      sub.setStatus(SubscriptionStatus.CANCELLED);
    }
    payments.save(payment);
    subscriptions.save(sub);
    return true;
  }
}
