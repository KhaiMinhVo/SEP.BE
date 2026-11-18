package com.influencermatch.backend.billing.service;

import com.influencermatch.backend.audit.service.AuditService;
import com.influencermatch.backend.billing.dto.AdminPaymentDTOs.*;
import com.influencermatch.backend.billing.enums.*;
import com.influencermatch.backend.billing.model.Payment;
import com.influencermatch.backend.billing.repository.PaymentRepository;
import com.influencermatch.backend.config.VnPayConfig;
import com.influencermatch.backend.exception.*;
import com.influencermatch.backend.security.permission.*;
import java.math.BigDecimal;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** HTTP is always outside DB transactions. Only one refund submission may be claimed per payment. */
@Service @Slf4j
@org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
public class VnPayRefundService {
  private final RefundService refunds;
  private final PaymentRepository payments;
  private final VnPayRefundClient client;
  private final VnPayQueryClient query;
  private final VnPayConfig config;
  private final AuditService audit;
  private final TransactionTemplate tx;
  private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

  public VnPayRefundService(RefundService refunds, PaymentRepository payments, VnPayRefundClient client,
      VnPayQueryClient query, VnPayConfig config, AuditService audit, PlatformTransactionManager manager) {
    this.refunds = refunds; this.payments = payments; this.client = client;
    this.query = query; this.config = config; this.audit = audit; this.tx = new TransactionTemplate(manager);
  }

  public RefundDetails approve(UUID id, RefundApprovalRequest request) {
    Permissions.require(Permission.MANAGE_SUBSCRIPTION);
    var payment = payments.findById(id).orElseThrow(() -> new ResourceNotFoundException("Payment not found"));
    boolean automatic = config.isRefundEnabled() && "VNPAY".equals(payment.getPaymentGateway())
        && !(request != null && request.manual());
    if (automatic) { configured(); validateMetadata(payment); }
    RefundDetails approved = refunds.approve(id, request);
    return automatic ? submit(id) : approved;
  }

  public RefundDetails submit(UUID id) {
    Permissions.require(Permission.MANAGE_SUBSCRIPTION);
    configured();
    var submission = tx.execute(status -> {
      Payment p = refunds.lock(id, false);
      if (p.getRefundStatus() != RefundStatus.APPROVED || p.getGatewayRefundRequestId() != null
          || "MANUAL_SELECTED".equals(p.getGatewayRefundCode())
          || p.getStatus() != PaymentStatus.PAID || !p.getSubscription().isRefundHold())
        throw new ValidationException("An approved, unsubmitted refund is required");
      validateMetadata(p);
      p.setGatewayRefundRequestId(UUID.randomUUID().toString().replace("-", ""));
      p.setGatewayRefundCreateDate(LocalDateTime.now(ZoneId.of("Asia/Ho_Chi_Minh")).format(DATE));
      p.setGatewayRefundLastCheckedAt(refunds.now());
      p.setGatewayRefundCode("SUBMITTING"); p.setRefundStatus(RefundStatus.PROCESSING);
      payments.save(p);
      audit.record("SUBMIT_VNPAY_REFUND", "Payment", p.getId(), "APPROVED", "PROCESSING", p.getGatewayRefundRequestId());
      return snapshot(p);
    });
    try {
      return apply(id, client.refund(submission));
    } catch (Exception error) {
      return uncertain(id, error);
    }
  }

  public RefundDetails query(UUID id) {
    Permissions.require(Permission.MANAGE_SUBSCRIPTION);
    configured();
    return reconcileOne(id, null);
  }

  private RefundDetails reconcileOne(UUID id, LocalDateTime before) {
    var submission = tx.execute(status -> {
      Payment p = refunds.lock(id, false);
      if (p.getRefundStatus() != RefundStatus.PROCESSING || p.getGatewayRefundRequestId() == null)
        throw new ValidationException("No pending VNPAY refund to query");
      if (before != null && p.getGatewayRefundLastCheckedAt() != null
          && !p.getGatewayRefundLastCheckedAt().isBefore(before)) return null;
      p.setGatewayRefundLastCheckedAt(refunds.now()); payments.save(p);
      return snapshot(p);
    });
    if (submission == null) return null;
    try {
      return apply(id, query.queryRefund(submission.txnRef(), submission.gatewayDate()));
    } catch (Exception error) {
      return uncertain(id, error);
    }
  }

  private RefundDetails apply(UUID id, Map<String, String> response) {
    return tx.execute(status -> {
      Payment p = refunds.lock(id, false);
      if (p.getRefundStatus() != RefundStatus.PROCESSING) return RefundDetails.from(p);
      if (p.getStatus() != PaymentStatus.PAID || !p.getSubscription().isRefundHold())
        throw new IllegalStateException("Refund and subscription state conflict");
      if (new BigDecimal(com.influencermatch.backend.billing.utils.VnPayUtil.gatewayAmount(p.getAmount()))
          .compareTo(new BigDecimal(response.get("vnp_Amount"))) != 0)
        throw new IllegalStateException("VNPAY refund amount mismatch");
      String code = response.get("vnp_ResponseCode"), outcome = response.get("vnp_TransactionStatus");
      p.setGatewayRefundCode(code + ":" + outcome);
      // A successful original payment (type 01) is never evidence of a completed refund.
      if ("02".equals(response.get("vnp_TransactionType"))) {
        p.setGatewayRefundTransactionNo(response.get("vnp_TransactionNo"));
        if ("00".equals(code) && "00".equals(outcome)) {
          String reference = response.get("vnp_TransactionNo");
          if (reference == null || !reference.matches("[0-9]{1,15}"))
            throw new IllegalStateException("Missing VNPAY refund transaction number");
          refunds.complete(p, "VNPAY:" + reference, p.getRefundApprovedBy());
        } else if (("00".equals(code) && Set.of("02", "09").contains(Objects.toString(outcome, "")))
            || "95".equals(code)) {
          p.setRefundStatus(RefundStatus.GATEWAY_FAILED);
        }
      }
      payments.save(p);
      audit.recordAs(p.getRefundApprovedBy(), "VNPAY_REFUND_RESULT", "Payment", id,
          "PROCESSING", p.getRefundStatus().name(), p.getGatewayRefundCode());
      return RefundDetails.from(p);
    });
  }

  private RefundDetails uncertain(UUID id, Exception error) {
    log.warn("VNPAY refund outcome requires query: paymentId={}, error={}", id, error.getClass().getSimpleName());
    return tx.execute(status -> {
      Payment p = refunds.lock(id, false);
      if (p.getRefundStatus() == RefundStatus.PROCESSING) {
        p.setGatewayRefundCode("UNVERIFIED_OR_UNAVAILABLE"); payments.save(p);
      }
      return RefundDetails.from(p);
    });
  }

  private VnPayRefundClient.Submission snapshot(Payment p) {
    return new VnPayRefundClient.Submission(p.getId(), p.getGatewayRefundRequestId(), p.getTransactionCode(),
        p.getAmount(), p.getGatewayCreateDate(), p.getRefundApprovedBy().toString(), p.getGatewayRefundCreateDate());
  }
  private void validateMetadata(Payment p) {
    if (!"VNPAY".equals(p.getPaymentGateway()) || !"VND".equals(p.getCurrency())
        || p.getGatewayCreateDate() == null || !p.getGatewayCreateDate().matches("[0-9]{14}")
        || p.getTransactionCode() == null || p.getTransactionCode().isBlank())
      throw new ValidationException("VNPAY refund requires the original VND transaction reference and gateway date");
    com.influencermatch.backend.billing.utils.VnPayUtil.gatewayAmount(p.getAmount());
  }

  private void configured() {
    if (!config.isRefundEnabled() || config.getHashSecret() == null || config.getHashSecret().isBlank()
        || config.getTmnCode() == null || config.getTmnCode().isBlank())
      throw new ValidationException("VNPAY refund is not enabled or configured");
  }

  @Scheduled(fixedDelayString = "${application.vnpay.refund-reconciliation-delay-ms:60000}")
  public void reconcile() {
    if (!config.isRefundEnabled() || config.getHashSecret() == null || config.getHashSecret().isBlank()) return;
    var before = refunds.now().minusMinutes(1);
    for (var p : payments.findRefundReconciliationCandidates(before, PageRequest.of(0, 20))) {
      try { reconcileOne(p.getId(), before); }
      catch (Exception error) { log.warn("VNPAY refund reconciliation unresolved: paymentId={}, error={}", p.getId(), error.getClass().getSimpleName()); }
    }
  }
}
