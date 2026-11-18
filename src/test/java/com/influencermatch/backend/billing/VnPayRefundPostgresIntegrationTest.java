package com.influencermatch.backend.billing;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import com.influencermatch.backend.billing.dto.AdminPaymentDTOs.*;
import com.influencermatch.backend.billing.enums.*;
import com.influencermatch.backend.billing.model.Payment;
import com.influencermatch.backend.billing.repository.*;
import com.influencermatch.backend.billing.service.*;
import com.influencermatch.backend.config.VnPayConfig;
import com.influencermatch.backend.support.*;
import com.influencermatch.backend.user.enums.Role;
import com.influencermatch.backend.user.model.User;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class VnPayRefundPostgresIntegrationTest extends PostgresApiTestSupport {
  @MockBean VnPayRefundClient client;
  @MockBean VnPayQueryClient query;
  @Autowired PaymentRepository payments;
  @Autowired SubscriptionRepository subscriptions;
  @Autowired RefundService refunds;
  @Autowired VnPayRefundService gateway;
  @Autowired VnPayConfig config;
  @Autowired PlatformTransactionManager manager;
  record Fixture(User owner, User admin, UUID payment, UUID sub, String ref) {}
  @BeforeEach void enable() { config.setRefundEnabled(true); }
  @AfterEach void disable() { config.setRefundEnabled(false); org.springframework.security.core.context.SecurityContextHolder.clearContext(); }
  Fixture fixture() throws Exception {
    var owner = account(Role.BRAND); var admin = account(Role.ADMIN); var brand = createBrand(owner);
    UUID sub = grantCampaignSubscription(brand, 10);
    String ref = "GW" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    UUID id = new TransactionTemplate(manager).execute(tx -> {
      var subscription = subscriptions.findById(sub).orElseThrow();
      return payments.saveAndFlush(Payment.builder().brandProfile(subscription.getBrandProfile()).subscription(subscription)
          .amount(new BigDecimal("100000")).currency("VND").paymentGateway("VNPAY").transactionCode(ref)
          .status(PaymentStatus.PAID).createdAt(LocalDateTime.now().minusHours(1)).paidAt(LocalDateTime.now().minusHours(1))
          .gatewayCreateDate("20261118100000").build()).getId();
    });
    call(owner, post("/billing/payments/" + id + "/refund"), Map.of("reason", "Bought by mistake"), 200);
    return new Fixture(owner, admin, id, sub, ref);
  }
  String route(Fixture f) { return "/admin/payments/" + f.payment() + "/refund"; }
  Map<String, String> response(Fixture f, String state) {
    return Map.of("vnp_ResponseCode", "00", "vnp_TransactionStatus", state, "vnp_TransactionType", "02",
        "vnp_TransactionNo", Long.toString(Math.floorMod(f.payment().getLeastSignificantBits(), 99999999999999L) + 1),
        "vnp_Amount", "10000000");
  }
  void approve(Fixture f) throws Exception { call(f.admin(), post(route(f) + "/approve"), Map.of("reason", "Verified unused"), 200); }
  void assertPending(Fixture f) {
    assertThat(payments.findById(f.payment()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.PAID);
    assertThat(payments.findById(f.payment()).orElseThrow().getRefundStatus()).isEqualTo(RefundStatus.PROCESSING);
    assertThat(jdbc.queryForObject("select refund_hold from subscription where id=?", Boolean.class, f.sub())).isTrue();
  }
  void assertCompleted(Fixture f) {
    var p = payments.findById(f.payment()).orElseThrow();
    assertThat(p.getStatus()).isEqualTo(PaymentStatus.REFUNDED); assertThat(p.getRefundStatus()).isEqualTo(RefundStatus.COMPLETED);
    assertThat(p.getGatewayRefundRequestId()).hasSize(32);
    assertThat(jdbc.queryForObject("select status from subscription where id=?", String.class, f.sub())).isEqualTo("CANCELLED");
    assertThat(jdbc.queryForObject("select refund_hold from subscription where id=?", Boolean.class, f.sub())).isFalse();
  }
  @Test void approvalSubmitsOnceAndCompletesBothModels() throws Exception {
    var f = fixture();
    when(client.refund(any())).thenAnswer(invocation -> {
      assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
      var persisted = payments.findById(f.payment()).orElseThrow();
      assertThat(persisted.getRefundStatus()).isEqualTo(RefundStatus.PROCESSING);
      assertThat(persisted.getGatewayRefundRequestId()).isNotBlank();
      return response(f, "00");
    });
    approve(f); assertCompleted(f);
    call(f.admin(), post(route(f) + "/vnpay"), null, 400);
    verify(client, times(1)).refund(argThat(s -> s.txnRef().equals(f.ref()) && s.createdBy().equals(f.admin().getId().toString())));
  }
  @ParameterizedTest @ValueSource(strings = {"05", "06"})
  void acceptedOrForwardedIsNotCompleted(String state) throws Exception {
    var f = fixture(); when(client.refund(any())).thenReturn(response(f, state)); approve(f); assertPending(f);
    call(f.admin(), post(route(f) + "/confirm"), Map.of("amount", 100000, "currency", "VND", "transferReference", "bank"), 400);
    call(f.admin(), post(route(f) + "/vnpay"), null, 400); verify(client, times(1)).refund(any());
  }
  @Test void timeoutIsRecoveredByQueryAndOriginalPaymentSuccessNeverCompletesRefund() throws Exception {
    var f = fixture(); when(client.refund(any())).thenThrow(new IllegalStateException("timeout")); approve(f); assertPending(f);
    var original = new HashMap<>(response(f, "00")); original.put("vnp_TransactionType", "01");
    when(query.queryRefund(f.ref(), "20261118100000")).thenReturn(original, response(f, "00"));
    call(f.admin(), post(route(f) + "/vnpay/query"), null, 200); assertPending(f);
    call(f.admin(), post(route(f) + "/vnpay/query"), null, 200); assertCompleted(f);
    verify(client, times(1)).refund(any());
  }
  @Test void confirmedRejectionAllowsManualFallback() throws Exception {
    var f = fixture(); when(client.refund(any())).thenReturn(response(f, "09")); approve(f);
    assertThat(payments.findById(f.payment()).orElseThrow().getRefundStatus()).isEqualTo(RefundStatus.GATEWAY_FAILED);
    call(f.admin(), post(route(f) + "/confirm"), Map.of("amount", 100000, "currency", "VND", "transferReference", "bank-" + f.payment()), 200);
    assertCompleted(f);
  }
  @Test void amountMismatchCannotCompleteRefund() throws Exception {
    var f = fixture(); var bad = new HashMap<>(response(f, "00")); bad.put("vnp_Amount", "1");
    when(client.refund(any())).thenReturn(bad); approve(f); assertPending(f);
  }
  @Test void ownerCannotSubmitOrQueryAndDisabledModeKeepsManualApproval() throws Exception {
    var f = fixture();
    call(f.owner(), post(route(f) + "/vnpay"), null, 403);
    call(f.owner(), post(route(f) + "/vnpay/query"), null, 403);
    config.setRefundEnabled(false); approve(f);
    assertThat(payments.findById(f.payment()).orElseThrow().getRefundStatus()).isEqualTo(RefundStatus.APPROVED);
    call(f.admin(), post(route(f) + "/vnpay"), null, 400); verifyNoInteractions(client);
  }
  @Test void backgroundQueryRecoversWithoutAuthenticatedUserAndDoesNotResubmit() throws Exception {
    var f = fixture(); when(client.refund(any())).thenThrow(new IllegalStateException("timeout")); approve(f);
    new TransactionTemplate(manager).executeWithoutResult(tx -> {
      var p = payments.findById(f.payment()).orElseThrow(); p.setGatewayRefundLastCheckedAt(LocalDateTime.now().minusMinutes(5)); payments.save(p);
    });
    when(query.queryRefund(f.ref(), "20261118100000")).thenReturn(response(f, "00"));
    org.springframework.security.core.context.SecurityContextHolder.clearContext(); gateway.reconcile(); assertCompleted(f);
    verify(client, times(1)).refund(any());
  }
  @Test void concurrentSubmissionOnlySendsOneHttpRequest() throws Exception {
    var f = fixture(); TestActors.authenticate(f.admin()); refunds.approve(f.payment(), new RefundApprovalRequest("Verified", null));
    org.springframework.security.core.context.SecurityContextHolder.clearContext();
    when(client.refund(any())).thenReturn(response(f, "05"));
    var start = new java.util.concurrent.CountDownLatch(1);
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      java.util.concurrent.Callable<Boolean> task = () -> {
        TestActors.authenticate(f.admin());
        try { start.await(); gateway.submit(f.payment()); return true; }
        catch (com.influencermatch.backend.exception.ValidationException expected) { return false; }
        finally { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }
      };
      var a = pool.submit(task); var b = pool.submit(task); start.countDown();
      assertThat(List.of(a.get(20, java.util.concurrent.TimeUnit.SECONDS), b.get(20, java.util.concurrent.TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(true, false);
    }
    assertPending(f); verify(client, times(1)).refund(any());
  }
  @Test void usageAfterRequestStillBlocksAutomaticRefundBeforeHttp() throws Exception {
    var f = fixture();
    jdbc.update("update subscription set used_campaign_quota=1 where id=?", f.sub());
    call(f.admin(), post(route(f) + "/approve"), Map.of("reason", "Check usage"), 400);
    assertThat(payments.findById(f.payment()).orElseThrow().getRefundStatus()).isEqualTo(RefundStatus.REQUESTED);
    verifyNoInteractions(client);
  }
  @Test void legacyMissingGatewayDateCanBeExplicitlyApprovedForManualOnly() throws Exception {
    var f = fixture();
    jdbc.update("update payment set gateway_create_date=null where id=?", f.payment());
    call(f.admin(), post(route(f) + "/approve"), Map.of("reason", "Legacy payment"), 400);
    assertThat(payments.findById(f.payment()).orElseThrow().getRefundStatus()).isEqualTo(RefundStatus.REQUESTED);
    call(f.admin(), post(route(f) + "/approve"), Map.of("reason", "Legacy manual refund", "manual", true), 200);
    assertThat(payments.findById(f.payment()).orElseThrow().getRefundStatus()).isEqualTo(RefundStatus.APPROVED);
    call(f.admin(), post(route(f) + "/vnpay"), null, 400);
    verifyNoInteractions(client);
  }
}
