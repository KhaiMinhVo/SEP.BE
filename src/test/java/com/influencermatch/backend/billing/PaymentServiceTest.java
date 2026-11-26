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
import com.influencermatch.backend.billing.service.PayOsPaymentClient;
import com.influencermatch.backend.brand.model.BrandProfile;
import com.influencermatch.backend.brand.repository.BrandProfileRepository;
import com.influencermatch.backend.config.PayOsConfig;
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
  private PayOsConfig payOsConfig;
  private PayOsPaymentClient payOsPaymentClient;
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
    payOsConfig = mock(PayOsConfig.class);
    payOsPaymentClient = mock(PayOsPaymentClient.class);
    brandProfileRepository = mock(BrandProfileRepository.class);
    settlement = mock(PaymentSettlementService.class);

    when(payOsConfig.isConfigured()).thenReturn(true);
    when(vnPayConfig.getHashSecret()).thenReturn("test-secret-key-12345678901234567890");
    when(vnPayConfig.getTmnCode()).thenReturn("TMNCODE1");
    when(vnPayConfig.getPayUrl()).thenReturn("https://sandbox.vnpayment.vn/paymentv2/vpcpay.html");
    when(vnPayConfig.getReturnUrl()).thenReturn("https://example.test/vnpay/return");

    paymentService =
        new PaymentService(
            planRepository,
            subscriptionRepository,
            paymentRepository,
            vnPayConfig,
            payOsConfig,
            payOsPaymentClient,
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
  @DisplayName("Default checkout preserves the VNPay sandbox flow")
  void createCheckout_createsNewWhenNonePending() throws Exception {
    when(paymentRepository.findRecentPendingCheckouts(
            eq(brand.getId()), eq(planId), eq("VNPAY"), any(), any()))
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
    assertThat(response.getPaymentUrl())
        .contains("https://sandbox.vnpayment.vn/paymentv2/vpcpay.html")
        .contains("vnp_TxnRef=" + response.getTransactionCode());

    verify(subscriptionRepository, times(1)).save(any(Subscription.class));
    verify(paymentRepository, times(1)).save(any(Payment.class));
    verifyNoInteractions(payOsPaymentClient);
  }

  @Test
  @DisplayName("Checkout reuses a recent pending VNPay payment and avoids duplicates")
  void createCheckout_reusesVnPayPendingWithinWindow() throws Exception {
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
    when(paymentRepository.findRecentPendingCheckouts(eq(brand.getId()), eq(planId), eq("VNPAY"), any(), any()))
        .thenReturn(List.of(existingPayment));

    PaymentResponse response = paymentService.createCheckout(planId, brand, "127.0.0.1");

    assertThat(response.getTransactionCode()).isEqualTo(existingTxnRef);
    assertThat(response.getPaymentUrl()).contains("vnp_TxnRef=" + existingTxnRef);
    assertThat(response.getPaymentUrl()).contains("vnp_CreateDate=" + gatewayDate);
    verify(subscriptionRepository, never()).save(any(Subscription.class));
    verify(paymentRepository, never()).save(any(Payment.class));
  }

  @Test
  @DisplayName("PayOS checkout creates a hosted QR payment link")
  void createPayOsCheckout_createsQrCheckout() {
    when(paymentRepository.findRecentPendingCheckouts(eq(brand.getId()), eq(planId), eq("PAYOS"), any(), any()))
        .thenReturn(List.of());
    when(subscriptionRepository.save(any(Subscription.class)))
        .thenAnswer(inv -> inv.getArgument(0));
    when(paymentRepository.save(any(Payment.class)))
        .thenAnswer(inv -> inv.getArgument(0));
    when(payOsPaymentClient.createPaymentLink(anyLong(), eq(500000L), anyString()))
        .thenReturn("https://pay.payos.vn/web/test-link");

    PaymentResponse response = paymentService.createPayOsCheckout(planId, brand);

    assertThat(response.getTransactionCode()).matches("\\d+");
    assertThat(response.getPaymentUrl()).isEqualTo("https://pay.payos.vn/web/test-link");
    verify(payOsPaymentClient).createPaymentLink(
        Long.parseLong(response.getTransactionCode()),
        500000L,
        "IM" + response.getTransactionCode().substring(response.getTransactionCode().length() - 7));
  }

  @Test
  @DisplayName("PayOS checkout reuses a recent pending QR link and avoids duplicates")
  void createPayOsCheckout_reusesPendingWithinWindow() {
    String existingTxnRef = "1760000000123456";
    String paymentUrl = "https://pay.payos.vn/web/already-created";

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
            .paymentGateway("PAYOS")
            .status(PaymentStatus.PENDING)
            .gatewayPaymentUrl(paymentUrl)
            .createdAt(LocalDateTime.now().minusMinutes(5))
            .build();

    when(paymentRepository.findRecentPendingCheckouts(eq(brand.getId()), eq(planId), eq("PAYOS"), any(), any()))
        .thenReturn(List.of(existingPayment));

    PaymentResponse response = paymentService.createPayOsCheckout(planId, brand);

    assertThat(response).isNotNull();
    assertThat(response.getTransactionCode()).isEqualTo(existingTxnRef);
    assertThat(response.getPaymentUrl()).isEqualTo(paymentUrl);

    // Verify NO new payment or subscription is saved in DB!
    verify(subscriptionRepository, never()).save(any(Subscription.class));
    verify(paymentRepository, never()).save(any(Payment.class));
    verifyNoInteractions(payOsPaymentClient);
  }

  @Test
  @DisplayName("createCheckout UTCID05 [A]: Inactive plan is rejected without creating payment")
  void createCheckout_validatesPlan() {
    Plan inactivePlan = Plan.builder().id(planId).status(PlanStatus.INACTIVE).price(BigDecimal.TEN).currency("VND").build();
    when(planRepository.findById(planId)).thenReturn(Optional.of(inactivePlan));

    assertThatThrownBy(() -> paymentService.createCheckout(planId, brand, "127.0.0.1"))
        .isInstanceOf(ValidationException.class);
    verify(paymentRepository, never()).save(any());
    verify(subscriptionRepository, never()).save(any());
  }

  @Test
  @DisplayName("Checkout rejects fractional VND amounts not supported by PayOS")
  void createCheckout_rejectsFractionalVndAmount() {
    plan.setPrice(new BigDecimal("500000.50"));
    when(paymentRepository.findRecentPendingCheckouts(eq(brand.getId()), eq(planId), eq("PAYOS"), any(), any()))
        .thenReturn(List.of());

    assertThatThrownBy(() -> paymentService.createPayOsCheckout(planId, brand))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  @DisplayName("createCheckout UTCID03 [B]: Zero-price plan throws ValidationException with no payment created")
  void createCheckout_zeroPricePlanThrowsValidation() {
    plan.setPrice(BigDecimal.ZERO);

    assertThatThrownBy(() -> paymentService.createCheckout(planId, brand, "127.0.0.1"))
        .isInstanceOf(ValidationException.class);
    verify(paymentRepository, never()).save(any());
    verify(subscriptionRepository, never()).save(any());
  }

  @Test
  @DisplayName("createCheckout UTCID04 [A]: Blank VNPay hash secret throws BusinessException INTERNAL_ERROR")
  void createCheckout_blankHashSecretThrowsInternalError() {
    when(vnPayConfig.getHashSecret()).thenReturn("   ");

    assertThatThrownBy(() -> paymentService.createCheckout(planId, brand, "127.0.0.1"))
        .isInstanceOfSatisfying(
            com.influencermatch.backend.exception.BusinessException.class,
            ex -> assertThat(ex.code())
                .isEqualTo(com.influencermatch.backend.exception.ErrorCode.INTERNAL_ERROR));
    verify(paymentRepository, never()).save(any());
  }
}
