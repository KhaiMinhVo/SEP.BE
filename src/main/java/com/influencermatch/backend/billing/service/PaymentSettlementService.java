package com.influencermatch.backend.billing.service;

import com.influencermatch.backend.billing.enums.*;
import com.influencermatch.backend.billing.model.Payment;
import com.influencermatch.backend.billing.model.Subscription;
import com.influencermatch.backend.billing.repository.*;
import com.influencermatch.backend.brand.repository.BrandProfileRepository;
import jakarta.persistence.EntityManager;
import java.time.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Shared transaction boundary rules for verified VNPay payment gateway callbacks. */
@Service @RequiredArgsConstructor
@Transactional(propagation=Propagation.MANDATORY)
public class PaymentSettlementService {
  private final BrandProfileRepository brands;
  private final PaymentRepository payments;
  private final SubscriptionRepository subscriptions;
  private final EntityManager em;
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  private com.influencermatch.backend.notification.service.NotificationService notificationService;

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
      java.util.List<Subscription> activeSubs =
          subscriptions.findByBrandProfileIdAndStatus(
              payment.getBrandProfile().getId(), SubscriptionStatus.ACTIVE);
      for (Subscription oldSub : activeSubs) {
        oldSub.setStatus(SubscriptionStatus.CANCELLED);
        subscriptions.save(oldSub);
      }
      payment.setStatus(PaymentStatus.PAID);
      payment.setPaidAt(LocalDateTime.now());
      sub.setStatus(SubscriptionStatus.ACTIVE);
      sub.setStartDate(LocalDate.now());
      sub.setExpirationDate(LocalDate.now().plusDays(sub.getPlan().getDurationDays()));
      if (notificationService != null
          && payment.getBrandProfile() != null
          && payment.getBrandProfile().getUser() != null) {
        String planName = sub.getPlan() != null ? sub.getPlan().getPlanName() : "dịch vụ";
        notificationService.createAndDispatch(
            payment.getBrandProfile().getUser().getId(),
            com.influencermatch.backend.notification.enums.NotificationType.PAYMENT_SUCCESS,
            com.influencermatch.backend.notification.enums.NotificationType.PAYMENT_SUCCESS.getDefaultTitle(),
            "Giao dịch thanh toán gói "
                + planName
                + " ("
                + payment.getAmount()
                + " "
                + payment.getCurrency()
                + ") đã hoàn tất thành công.",
            "PAYMENT",
            payment.getId());
      }
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
