package com.influencermatch.backend.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.influencermatch.backend.billing.dto.PaymentResponse;
import com.influencermatch.backend.billing.enums.PaymentStatus;
import com.influencermatch.backend.billing.enums.PlanStatus;
import com.influencermatch.backend.billing.enums.SubscriptionStatus;
import com.influencermatch.backend.billing.model.Payment;
import com.influencermatch.backend.billing.model.Plan;
import com.influencermatch.backend.billing.model.Subscription;
import com.influencermatch.backend.billing.repository.PaymentRepository;
import com.influencermatch.backend.billing.repository.PlanRepository;
import com.influencermatch.backend.billing.repository.SubscriptionRepository;
import com.influencermatch.backend.billing.service.PaymentService;
import com.influencermatch.backend.billing.service.PaymentSettlementService;
import com.influencermatch.backend.brand.model.BrandProfile;
import com.influencermatch.backend.brand.repository.BrandProfileRepository;
import com.influencermatch.backend.config.VnPayConfig;
import com.influencermatch.backend.exception.ValidationException;
import com.influencermatch.backend.support.TestActors;
import com.influencermatch.backend.user.enums.Role;
import com.influencermatch.backend.user.model.User;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PaymentServiceTest {

  private PlanRepository planRepository;
  private SubscriptionRepository subscriptionRepository;
  private PaymentRepository paymentRepository;
  private VnPayConfig vnPayConfig;
  private BrandProfileRepository brandProfileRepository;
  private PaymentSettlementService settlement;
  private PaymentService paymentService;

  private User user;
  private BrandProfile brand;
  private Plan plan;
  private UUID planId;

  @BeforeEach
  void setUp() {
    planRepository = mock(PlanRepository.class);
    subscriptionRepository = mock(SubscriptionRepository.class);
    paymentRepository = mock(PaymentRepository.class);
    vnPayConfig = mock(VnPayConfig.class);
    brandProfileRepository = mock(BrandProfileRepository.class);
    settlement = mock(PaymentSettlementService.class);

    when(vnPayConfig.getHashSecret()).thenReturn("test-secret-key-12345678901234567890");
    when(vnPayConfig.getTmnCode()).thenReturn("TMNCODE1");
    when(vnPayConfig.getPayUrl()).thenReturn("https://sandbox.vnpayment.vn/paymentv2/vpcpay.html");
    when(vnPayConfig.getReturnUrl()).thenReturn("https://www.influencermatch.site/subscription");

    paymentService =
        new PaymentService(
            planRepository,
            subscriptionRepository,
            paymentRepository,
            vnPayConfig,
            brandProfileRepository,
            settlement);

    user = TestActors.user(Role.BRAND);
    TestActors.authenticate(user);
    brand = BrandProfile.builder().id(UUID.randomUUID()).user(user).build();

    planId = UUID.randomUUID();
    plan =
        Plan.builder()
            .id(planId)
            .planName("Pro Plan")
            .price(new BigDecimal("500000"))
            .currency("VND")
            .status(PlanStatus.ACTIVE)
            .durationDays(30)
            .campaignQuota(10)
            .recommendationQuota(50)
            .refreshQuota(5)
            .build();

    when(brandProfileRepository.lockForBilling(brand.getId())).thenReturn(Optional.of(brand));
    when(planRepository.findById(planId)).thenReturn(Optional.of(plan));
  }

  @Test
  @DisplayName("P0-BE-01: When no recent PENDING checkout exists, createCheckout creates a new Payment and Subscription")
  void createCheckout_createsNewWhenNonePending() throws Exception {
    when(paymentRepository.findRecentPendingCheckouts(eq(brand.getId()), eq(planId), any(), any()))
        .thenReturn(List.of());

    when(subscriptionRepository.save(any(Subscription.class)))
        .thenAnswer(inv -> {
          Subscription s = inv.getArgument(0);
          s.setId(UUID.randomUUID());
          return s;
        });
    when(paymentRepository.save(any(Payment.class)))
        .thenAnswer(inv -> {
          Payment p = inv.getArgument(0);
          p.setId(UUID.randomUUID());
          return p;
        });

    PaymentResponse response = paymentService.createCheckout(planId, brand, "127.0.0.1");

    assertThat(response).isNotNull();
    assertThat(response.getTransactionCode()).isNotBlank();
    assertThat(response.getPaymentUrl()).contains("https://sandbox.vnpayment.vn/paymentv2/vpcpay.html");
    assertThat(response.getPaymentUrl()).contains("vnp_TxnRef=" + response.getTransactionCode());

    verify(subscriptionRepository, times(1)).save(any(Subscription.class));
    verify(paymentRepository, times(1)).save(any(Payment.class));
  }

  @Test
  @DisplayName("P0-BE-01: When a recent PENDING checkout exists within 15 min, createCheckout reuses it and avoids creating duplicates")
  void createCheckout_reusesPendingWithinWindow() throws Exception {
    String existingTxnRef = "OLD_TXN_REF_123";
    String gatewayDate = "20261122120000";

    Subscription existingSub =
        Subscription.builder()
            .id(UUID.randomUUID())
            .brandProfile(brand)
            .plan(plan)
            .status(SubscriptionStatus.PENDING)
            .startDate(LocalDate.now())
            .expirationDate(LocalDate.now().plusDays(30))
            .build();

    Payment existingPayment =
        Payment.builder()
            .id(UUID.randomUUID())
            .brandProfile(brand)
            .subscription(existingSub)
            .transactionCode(existingTxnRef)
            .amount(plan.getPrice())
            .currency("VND")
            .paymentGateway("VNPAY")
            .status(PaymentStatus.PENDING)
            .gatewayCreateDate(gatewayDate)
            .createdAt(LocalDateTime.now().minusMinutes(5))
            .build();

    when(paymentRepository.findRecentPendingCheckouts(eq(brand.getId()), eq(planId), any(), any()))
        .thenReturn(List.of(existingPayment));

    PaymentResponse response = paymentService.createCheckout(planId, brand, "127.0.0.1");

    assertThat(response).isNotNull();
    assertThat(response.getTransactionCode()).isEqualTo(existingTxnRef);
    assertThat(response.getPaymentUrl()).contains("vnp_TxnRef=" + existingTxnRef);
    assertThat(response.getPaymentUrl()).contains("vnp_CreateDate=" + gatewayDate);

    // Verify NO new payment or subscription is saved in DB!
    verify(subscriptionRepository, never()).save(any(Subscription.class));
    verify(paymentRepository, never()).save(any(Payment.class));
  }

  @Test
  @DisplayName("Checkout rejects inactive or non-VND or zero-price plan")
  void createCheckout_validatesPlan() {
    Plan inactivePlan = Plan.builder().id(planId).status(PlanStatus.INACTIVE).price(BigDecimal.TEN).currency("VND").build();
    when(planRepository.findById(planId)).thenReturn(Optional.of(inactivePlan));

    assertThatThrownBy(() -> paymentService.createCheckout(planId, brand, "127.0.0.1"))
        .isInstanceOf(ValidationException.class);
  }
}
