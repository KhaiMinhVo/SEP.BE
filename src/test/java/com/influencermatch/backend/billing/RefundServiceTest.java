package com.influencermatch.backend.billing;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.influencermatch.backend.audit.service.AuditService;
import com.influencermatch.backend.billing.dto.AdminPaymentDTOs.*;
import com.influencermatch.backend.billing.enums.*;
import com.influencermatch.backend.billing.model.*;
import com.influencermatch.backend.billing.repository.*;
import com.influencermatch.backend.billing.service.RefundService;
import com.influencermatch.backend.brand.model.BrandProfile;
import com.influencermatch.backend.brand.repository.BrandProfileRepository;
import com.influencermatch.backend.exception.*;
import com.influencermatch.backend.support.TestActors;
import com.influencermatch.backend.user.enums.Role;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.core.context.SecurityContextHolder;

class RefundServiceTest {
  final PaymentRepository payments = mock(PaymentRepository.class);
  final SubscriptionRepository subscriptions = mock(SubscriptionRepository.class);
  final BrandProfileRepository brands = mock(BrandProfileRepository.class);
  final EntityManager em = mock(EntityManager.class);
  final AuditService audit = mock(AuditService.class);
  final Clock clock = Clock.fixed(Instant.parse("2026-11-18T03:00:00Z"), ZoneId.systemDefault());
  final RefundService service = new RefundService(payments, subscriptions, brands, em, audit, clock);
  final LocalDateTime now = LocalDateTime.now(clock);
  final com.influencermatch.backend.user.model.User owner = TestActors.user(Role.BRAND);
  Payment p;
  @BeforeEach void setup() {
    TestActors.authenticate(owner);
    var brand = BrandProfile.builder().id(UUID.randomUUID()).user(owner).build();
    var sub = Subscription.builder().id(UUID.randomUUID()).brandProfile(brand)
        .status(SubscriptionStatus.ACTIVE).expirationDate(now.toLocalDate().plusDays(30)).build();
    p = Payment.builder().id(UUID.randomUUID()).brandProfile(brand).subscription(sub)
        .amount(new BigDecimal("100000.00")).currency("VND").status(PaymentStatus.PAID)
        .paidAt(now.minusHours(1)).build();
    when(payments.findById(p.getId())).thenReturn(Optional.of(p));
    when(payments.findLockedById(p.getId())).thenReturn(Optional.of(p));
    when(brands.lockForBilling(brand.getId())).thenReturn(Optional.of(brand));
    when(subscriptions.findLockedById(sub.getId())).thenReturn(Optional.of(sub));
  }
  @AfterEach void clear() { SecurityContextHolder.clearContext(); }
  void admin() { TestActors.authenticate(TestActors.user(Role.ADMIN)); }
  void request() { service.request(p.getId(), new RefundRequest("Purchased by mistake")); }
  void approve() { admin(); service.approve(p.getId(), new RefundApprovalRequest("Unused, verified", null)); }
  RefundConfirmationRequest transfer() { return new RefundConfirmationRequest(new BigDecimal("100000"), "VND", "BANK-001"); }

  @Test void fullWorkflowOnlyCancelsAndMarksRefundedAfterTransfer() {
    request(); assertThat(p.getRefundStatus()).isEqualTo(RefundStatus.REQUESTED);
    assertThat(p.getSubscription().isRefundHold()).isFalse();
    approve(); assertThat(p.getStatus()).isEqualTo(PaymentStatus.PAID);
    assertThat(p.getSubscription().getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
    assertThat(p.getSubscription().isRefundHold()).isTrue();
    service.confirm(p.getId(), transfer());
    assertThat(p.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
    assertThat(p.getRefundStatus()).isEqualTo(RefundStatus.COMPLETED);
    assertThat(p.getSubscription().getStatus()).isEqualTo(SubscriptionStatus.CANCELLED);
    assertThat(p.getSubscription().isRefundHold()).isFalse();
    assertThat(p.getRefundedAt()).isEqualTo(now);
    assertThat(p.getRefundTransferReference()).isEqualTo("BANK-001");
    assertThatThrownBy(() -> service.confirm(p.getId(), transfer())).isInstanceOf(ValidationException.class);
  }
  @Test void inclusiveTwentyFourHourBoundaryAndApprovalAfterWindow() {
    p.setPaidAt(now.minusHours(24)); request();
    p.setPaidAt(now.minusHours(48)); p.setRefundRequestedAt(now.minusHours(24));
    approve(); assertThat(p.getRefundStatus()).isEqualTo(RefundStatus.APPROVED);
  }
  @Test void lateFutureAndMissingPaidAtCannotRequest() {
    p.setPaidAt(now.minusHours(24).minusNanos(1));
    assertThatThrownBy(this::request).isInstanceOf(ValidationException.class);
    p.setPaidAt(now.plusSeconds(1)); assertThatThrownBy(this::request).isInstanceOf(ValidationException.class);
    p.setPaidAt(null); assertThatThrownBy(this::request).isInstanceOf(ValidationException.class);
    verifyNoInteractions(audit);
  }
  @ParameterizedTest @ValueSource(strings = {"campaign", "ai", "refresh"})
  void eachUsedBenefitBlocksRequestAndApproval(String benefit) {
    request();
    switch (benefit) {
      case "campaign" -> p.getSubscription().setUsedCampaignQuota(1);
      case "ai" -> p.getSubscription().setUsedRecommendationQuota(1);
      default -> p.getSubscription().setUsedRefreshQuota(1);
    }
    assertThatThrownBy(this::approve).isInstanceOf(ValidationException.class);
    p.setRefundStatus(RefundStatus.NONE);
    assertThatThrownBy(this::request).isInstanceOf(ValidationException.class);
    assertThat(p.getSubscription().isRefundHold()).isFalse();
  }
  @Test void ownerCannotApproveRejectOrConfirmAndForeignOwnerCannotRequest() {
    assertThatThrownBy(() -> service.approve(p.getId(), new RefundApprovalRequest("reason", null))).isInstanceOf(ForbiddenException.class);
    assertThatThrownBy(() -> service.reject(p.getId(), new RefundRequest("reason"))).isInstanceOf(ForbiddenException.class);
    assertThatThrownBy(() -> service.confirm(p.getId(), transfer())).isInstanceOf(ForbiddenException.class);
    TestActors.authenticate(TestActors.user(Role.BRAND));
    assertThatThrownBy(this::request).isInstanceOf(ForbiddenException.class);
    verifyNoInteractions(audit);
  }
  @Test void amountCurrencyAndReferenceAreMandatoryAndExact() {
    request(); approve();
    for (var invalid : List.of(
        new RefundConfirmationRequest(BigDecimal.ONE, "VND", "BANK-001"),
        new RefundConfirmationRequest(new BigDecimal("100000"), "USD", "BANK-001"),
        new RefundConfirmationRequest(new BigDecimal("100000"), "VND", " "),
        new RefundConfirmationRequest(BigDecimal.ZERO, "VND", "BANK-001"))) {
      assertThatThrownBy(() -> service.confirm(p.getId(), invalid)).isInstanceOf(ValidationException.class);
      assertThat(p.getStatus()).isEqualTo(PaymentStatus.PAID);
      assertThat(p.getRefundStatus()).isEqualTo(RefundStatus.APPROVED);
      assertThat(p.getSubscription().isRefundHold()).isTrue();
    }
  }
  @Test void explicitExceptionRequiresAdminReasonAndCanHandleUsedOldPayment() {
    p.setPaidAt(now.minusDays(30)); p.getSubscription().setUsedCampaignQuota(5);
    admin();
    assertThatThrownBy(() -> service.approve(p.getId(), new RefundApprovalRequest(" ", RefundExceptionType.SYSTEM_ERROR)))
        .isInstanceOf(ValidationException.class);
    service.approve(p.getId(), new RefundApprovalRequest("Confirmed duplicate charge", RefundExceptionType.DUPLICATE_PAYMENT));
    assertThat(p.getRefundExceptionType()).isEqualTo("DUPLICATE_PAYMENT");
    assertThat(p.getRefundApprovedBy()).isNotNull();
    service.confirm(p.getId(), transfer()); assertThat(p.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
  }
  @Test void rejectionLeavesPaymentAndAccessUntouchedAndDuplicateRequestBlocked() {
    request(); assertThatThrownBy(this::request).isInstanceOf(ValidationException.class);
    admin(); service.reject(p.getId(), new RefundRequest("Does not qualify"));
    assertThat(p.getStatus()).isEqualTo(PaymentStatus.PAID);
    assertThat(p.getSubscription().getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
    assertThat(p.getSubscription().isRefundHold()).isFalse();
    assertThat(p.getRefundStatus()).isEqualTo(RefundStatus.REJECTED);
    assertThatThrownBy(() -> service.confirm(p.getId(), transfer())).isInstanceOf(ValidationException.class);
  }
  @Test void nonPaidAndInvalidOriginalAmountOrCurrencyCannotRequest() {
    p.setStatus(PaymentStatus.PENDING); assertThatThrownBy(this::request).isInstanceOf(ValidationException.class);
    p.setStatus(PaymentStatus.PAID); p.setAmount(BigDecimal.ZERO);
    assertThatThrownBy(this::request).isInstanceOf(ValidationException.class);
    p.setAmount(BigDecimal.ONE); p.setCurrency(null);
    assertThatThrownBy(this::request).isInstanceOf(ValidationException.class);
  }
}
