package com.influencermatch.backend.billing.service;

import com.influencermatch.backend.audit.service.AuditService;
import com.influencermatch.backend.billing.dto.AdminPaymentDTOs.AdminPaymentResponse;
import com.influencermatch.backend.billing.dto.AdminPaymentDTOs.PaymentStatsResponse;
import com.influencermatch.backend.billing.dto.AdminPaymentDTOs.RefundRequest;
import com.influencermatch.backend.billing.enums.PaymentStatus;
import com.influencermatch.backend.billing.enums.SubscriptionStatus;
import com.influencermatch.backend.billing.model.Payment;
import com.influencermatch.backend.billing.repository.PaymentRepository;
import com.influencermatch.backend.common.dto.PageResponse;
import com.influencermatch.backend.exception.ResourceNotFoundException;
import com.influencermatch.backend.exception.ValidationException;
import com.influencermatch.backend.security.permission.Permission;
import com.influencermatch.backend.security.permission.Permissions;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AdminPaymentService {

  private final PaymentRepository paymentRepository;
  private final AuditService auditService;

  @Transactional(readOnly = true)
  public PageResponse<AdminPaymentResponse> listPayments(
      String search,
      PaymentStatus status,
      String gateway,
      LocalDateTime from,
      LocalDateTime to,
      Pageable pageable) {

    Permissions.require(Permission.MANAGE_SUBSCRIPTION);

    Specification<Payment> spec =
        (root, query, cb) -> {
          List<Predicate> predicates = new ArrayList<>();

          if (status != null) {
            predicates.add(cb.equal(root.get("status"), status));
          }

          if (gateway != null && !gateway.isBlank() && !"all".equalsIgnoreCase(gateway)) {
            predicates.add(cb.equal(cb.upper(root.get("paymentGateway")), gateway.toUpperCase()));
          }

          if (from != null) {
            predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from));
          }

          if (to != null) {
            predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), to));
          }

          if (search != null && !search.isBlank()) {
            String pattern = "%" + search.trim().toLowerCase() + "%";
            var brandJoin = root.join("brandProfile", JoinType.LEFT);
            var userJoin = brandJoin.join("user", JoinType.LEFT);
            var subJoin = root.join("subscription", JoinType.LEFT);
            var planJoin = subJoin.join("plan", JoinType.LEFT);

            Predicate pTxn = cb.like(cb.lower(root.get("transactionCode")), pattern);
            Predicate pBrand = cb.like(cb.lower(brandJoin.get("businessName")), pattern);
            Predicate pUser = cb.like(cb.lower(userJoin.get("fullName")), pattern);
            Predicate pEmail = cb.like(cb.lower(userJoin.get("email")), pattern);
            Predicate pPlan = cb.like(cb.lower(planJoin.get("name")), pattern);

            predicates.add(cb.or(pTxn, pBrand, pUser, pEmail, pPlan));
          }

          return cb.and(predicates.toArray(Predicate[]::new));
        };

    Page<Payment> page = paymentRepository.findAll(spec, pageable);
    return PageResponse.from(page.map(this::mapToResponse));
  }

  @Transactional(readOnly = true)
  public AdminPaymentResponse getPayment(UUID id) {
    Permissions.require(Permission.MANAGE_SUBSCRIPTION);
    Payment payment =
        paymentRepository
            .findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Payment not found with id: " + id));
    return mapToResponse(payment);
  }

  @Transactional(readOnly = true)
  public PaymentStatsResponse getStats() {
    Permissions.require(Permission.MANAGE_SUBSCRIPTION);

    BigDecimal totalRevenue = paymentRepository.sumAmountByStatus(PaymentStatus.PAID);
    BigDecimal totalRefunded = paymentRepository.sumAmountByStatus(PaymentStatus.REFUNDED);
    BigDecimal totalPending = paymentRepository.sumAmountByStatus(PaymentStatus.PENDING);

    long paidCount = paymentRepository.countByStatus(PaymentStatus.PAID);
    long pendingCount = paymentRepository.countByStatus(PaymentStatus.PENDING);
    long refundedCount = paymentRepository.countByStatus(PaymentStatus.REFUNDED);
    long failedCount = paymentRepository.countByStatus(PaymentStatus.FAILED);
    long totalCount = paymentRepository.count();

    return PaymentStatsResponse.builder()
        .totalRevenue(totalRevenue != null ? totalRevenue : BigDecimal.ZERO)
        .totalRefunded(totalRefunded != null ? totalRefunded : BigDecimal.ZERO)
        .totalPending(totalPending != null ? totalPending : BigDecimal.ZERO)
        .paidCount(paidCount)
        .pendingCount(pendingCount)
        .refundedCount(refundedCount)
        .failedCount(failedCount)
        .totalCount(totalCount)
        .build();
  }

  @Transactional
  public AdminPaymentResponse refundPayment(UUID id, RefundRequest request) {
    Permissions.require(Permission.MANAGE_SUBSCRIPTION);

    Payment payment =
        paymentRepository
            .findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Payment not found with id: " + id));

    if (payment.getStatus() != PaymentStatus.PAID) {
      throw new ValidationException(
          "Chỉ giao dịch đã thanh toán (PAID) mới có thể hoàn tiền. Trạng thái hiện tại: "
              + payment.getStatus());
    }

    PaymentStatus oldStatus = payment.getStatus();
    payment.setStatus(PaymentStatus.REFUNDED);
    String reason =
        (request != null && request.getReason() != null && !request.getReason().isBlank())
            ? request.getReason()
            : "Admin manual refund";
    payment.setFailureReason("Hoàn tiền: " + reason);
    paymentRepository.save(payment);

    if (payment.getSubscription() != null
        && payment.getSubscription().getStatus() == SubscriptionStatus.ACTIVE) {
      payment.getSubscription().setStatus(SubscriptionStatus.CANCELLED);
    }

    auditService.record(
        "REFUND_PAYMENT",
        "Payment",
        payment.getId(),
        oldStatus.name(),
        PaymentStatus.REFUNDED.name(),
        reason);

    return mapToResponse(payment);
  }

  private AdminPaymentResponse mapToResponse(Payment payment) {
    var brand = payment.getBrandProfile();
    var user = brand != null ? brand.getUser() : null;
    var sub = payment.getSubscription();
    var plan = sub != null ? sub.getPlan() : null;

    return AdminPaymentResponse.builder()
        .id(payment.getId())
        .ref(
            payment.getTransactionCode() != null
                ? payment.getTransactionCode()
                : payment.getId().toString())
        .brandId(brand != null ? brand.getId() : null)
        .brandName(brand != null ? brand.getBusinessName() : "N/A")
        .userId(user != null && user.getId() != null ? user.getId().toString() : null)
        .userName(
            user != null
                ? user.getFullName()
                : (brand != null ? brand.getBusinessName() : "Khách hàng"))
        .userEmail(user != null ? user.getEmail() : "")
        .plan(plan != null ? plan.getPlanName() : "Gói dịch vụ")
        .amount(payment.getAmount())
        .currency(payment.getCurrency() != null ? payment.getCurrency() : "VND")
        .gateway(payment.getPaymentGateway() != null ? payment.getPaymentGateway() : "VNPAY")
        .status(payment.getStatus())
        .createdAt(payment.getCreatedAt())
        .paidAt(payment.getPaidAt())
        .failureReason(payment.getFailureReason())
        .build();
  }
}
