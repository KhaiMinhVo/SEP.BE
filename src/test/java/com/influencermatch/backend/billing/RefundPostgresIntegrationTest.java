package com.influencermatch.backend.billing;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import com.influencermatch.backend.support.PostgresApiTestSupport;
import com.influencermatch.backend.user.enums.Role;
import com.influencermatch.backend.user.model.User;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class RefundPostgresIntegrationTest extends PostgresApiTestSupport {
  @Autowired com.influencermatch.backend.billing.service.SubscriptionService subscriptions;
  @Autowired com.influencermatch.backend.billing.service.SubscriptionExpirationJob expiration;
  record Fixture(User owner, User admin, UUID brand, UUID sub, UUID payment) {}
  Fixture fixture() throws Exception {
    var owner = account(Role.BRAND); var admin = account(Role.ADMIN); var brand = createBrand(owner);
    UUID sub = grantCampaignSubscription(brand, 10);
    UUID[] paymentId = new UUID[1];
    jdbc.update("update subscription set purchased_price=100000 where id=?", sub);
    new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(tx -> {
      var subscription = subscriptionRepository.findById(sub).orElseThrow();
      paymentId[0] = payments.saveAndFlush(com.influencermatch.backend.billing.model.Payment.builder()
          .brandProfile(subscription.getBrandProfile()).subscription(subscription)
          .amount(new java.math.BigDecimal("100000")).currency("VND").paymentGateway("MANUAL")
          .status(com.influencermatch.backend.billing.enums.PaymentStatus.PAID)
          .createdAt(LocalDateTime.now().minusHours(1)).paidAt(LocalDateTime.now().minusHours(1))
          .transactionCode("refund-" + UUID.randomUUID()).build()).getId();
    });
    return new Fixture(owner, admin, brand, sub, paymentId[0]);
  }
  String route(Fixture f) { return "/admin/payments/" + f.payment() + "/refund"; }
  void request(Fixture f) throws Exception {
    call(f.owner(), post("/billing/payments/" + f.payment() + "/refund"), Map.of("reason", "Bought by mistake"), 200);
  }
  void approve(Fixture f) throws Exception {
    call(f.admin(), post(route(f) + "/approve"), Map.of("reason", "Unused subscription verified"), 200);
  }
  String paymentStatus(Fixture f) { return jdbc.queryForObject("select status from payment where id=?", String.class, f.payment()); }
  @Test void ownerAdminTransferAndQuotaConsistency() throws Exception {
    var f = fixture(); var stranger = account(Role.BRAND);
    call(stranger, post("/billing/payments/" + f.payment() + "/refund"), Map.of("reason", "foreign"), 403);
    call(f.owner(), post(route(f) + "/approve"), Map.of("reason", "self approval"), 403);
    request(f); assertThat(paymentStatus(f)).isEqualTo("PAID");
    assertThat(jdbc.queryForObject("select refund_hold from subscription where id=?", Boolean.class, f.sub())).isFalse();
    approve(f); assertThat(paymentStatus(f)).isEqualTo("PAID");
    assertThatThrownBy(() -> subscriptions.checkAndDeductCampaignQuota(f.brand()))
        .isInstanceOf(com.influencermatch.backend.exception.ForbiddenException.class);
    assertThatThrownBy(() -> subscriptions.reserveRecommendationQuota(f.brand()))
        .isInstanceOf(com.influencermatch.backend.exception.ForbiddenException.class);
    call(f.admin(), post(route(f) + "/confirm"), Map.of("amount", 99999, "currency", "VND", "transferReference", "bank-" + f.payment()), 400);
    call(f.admin(), post(route(f) + "/confirm"), Map.of("amount", 100000, "currency", "USD", "transferReference", "bank-" + f.payment()), 400);
    assertThat(paymentStatus(f)).isEqualTo("PAID");
    var transfer = Map.of("amount", 100000, "currency", "VND", "transferReference", "bank-" + f.payment());
    call(f.owner(), post(route(f) + "/confirm"), transfer, 403);
    call(f.admin(), post(route(f) + "/confirm"), transfer, 200);
    assertThat(paymentStatus(f)).isEqualTo("REFUNDED");
    assertThat(jdbc.queryForObject("select status from subscription where id=?", String.class, f.sub())).isEqualTo("CANCELLED");
    assertThat(jdbc.queryForObject("select refunded_at is not null and refund_completed_by=? from payment where id=?", Boolean.class, f.admin().getId(), f.payment())).isTrue();
    call(f.admin(), post(route(f) + "/confirm"), transfer, 400);
    assertThat(jdbc.queryForObject("select count(*) from audit_log where entity_id=? and action='CONFIRM_REFUND'", Long.class, f.payment())).isEqualTo(1);
  }
  @Test void usageAfterRequestBlocksApprovalAndRejectionKeepsAccess() throws Exception {
    var f = fixture(); request(f); subscriptions.checkAndDeductCampaignQuota(f.brand());
    call(f.admin(), post(route(f) + "/approve"), Map.of("reason", "Check usage again"), 400);
    call(f.admin(), post(route(f) + "/reject"), Map.of("reason", "Campaign benefit already used"), 200);
    assertThat(paymentStatus(f)).isEqualTo("PAID");
    assertThat(jdbc.queryForObject("select refund_hold from subscription where id=?", Boolean.class, f.sub())).isFalse();
    subscriptions.checkAndDeductCampaignQuota(f.brand());
  }
  @Test void oldPaymentNeedsExplicitExceptionAndDateFilterUsesOriginalPaymentTime() throws Exception {
    var f = fixture(); var old = LocalDateTime.now().minusDays(3);
    new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(tx -> {
      var payment = payments.findById(f.payment()).orElseThrow(); payment.setPaidAt(old); payments.saveAndFlush(payment);
    });
    String reference = payments.findById(f.payment()).orElseThrow().getTransactionCode();
    call(f.owner(), post("/billing/payments/" + f.payment() + "/refund"), Map.of("reason", "late"), 400);
    var filtered = call(f.admin(), get("/admin/payments").param("search", reference)
        .param("from", old.minusMinutes(1).toString()).param("to", old.plusMinutes(1).toString()), null, 200);
    assertThat(filtered.path("data").path("items").size()).isEqualTo(1);
    var excluded = call(f.admin(), get("/admin/payments").param("search", reference)
        .param("from", old.plusMinutes(1).toString()), null, 200);
    assertThat(excluded.path("data").path("items").size()).isZero();
    call(f.admin(), get("/admin/payments").param("from", old.plusDays(1).toString()).param("to", old.toString()), null, 400);
    call(f.admin(), post(route(f) + "/approve"), Map.of("reason", "Verified duplicate charge", "exceptionType", "DUPLICATE_PAYMENT"), 200);
    assertThat(paymentStatus(f)).isEqualTo("PAID");
  }
  @Test void cancellationKeepsAccessUntilEndThenExpiresWithoutRefund() throws Exception {
    var f = fixture(); call(f.owner(), delete("/subscriptions/" + f.sub()), null, 200);
    assertThat(jdbc.queryForObject("select cancel_at_period_end from subscription where id=?", Boolean.class, f.sub())).isTrue();
    subscriptions.checkAndDeductCampaignQuota(f.brand());
    expiration.expire();
    assertThat(jdbc.queryForObject("select status from subscription where id=?", String.class, f.sub())).isEqualTo("ACTIVE");
    jdbc.update("update subscription set expiration_date=? where id=?", LocalDate.now().minusDays(1), f.sub());
    expiration.expire();
    assertThat(jdbc.queryForObject("select status from subscription where id=?", String.class, f.sub())).isEqualTo("CANCELLED");
    assertThat(paymentStatus(f)).isEqualTo("PAID");
  }
  @Test void transferReferenceConflictRollsBackPaymentSubscriptionAndAudit() throws Exception {
    var first = fixture(); var second = fixture();
    request(first); approve(first); request(second); approve(second);
    var transfer = Map.of("amount", 100000, "currency", "VND", "transferReference", "unique-" + first.payment());
    call(first.admin(), post(route(first) + "/confirm"), transfer, 200);
    call(second.admin(), post(route(second) + "/confirm"), transfer, 400);
    assertThat(paymentStatus(second)).isEqualTo("PAID");
    assertThat(jdbc.queryForObject("select refund_status from payment where id=?", String.class, second.payment())).isEqualTo("APPROVED");
    assertThat(jdbc.queryForObject("select refund_hold from subscription where id=?", Boolean.class, second.sub())).isTrue();
    assertThat(jdbc.queryForObject("select count(*) from audit_log where entity_id=? and action='CONFIRM_REFUND'", Long.class, second.payment())).isZero();
  }

  @Autowired com.influencermatch.backend.billing.service.RefundService refunds;
  @Autowired com.influencermatch.backend.billing.repository.PaymentRepository payments;
  @Autowired com.influencermatch.backend.billing.repository.SubscriptionRepository subscriptionRepository;
  @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
  @Test void simultaneousConfirmationsRecordOneRefund() throws Exception {
    var f = fixture(); request(f); approve(f);
    var start = new java.util.concurrent.CountDownLatch(1);
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      java.util.concurrent.Callable<Boolean> action = () -> {
        com.influencermatch.backend.support.TestActors.authenticate(f.admin());
        try {
          start.await();
          refunds.confirm(f.payment(), new com.influencermatch.backend.billing.dto.AdminPaymentDTOs.RefundConfirmationRequest(
              new java.math.BigDecimal("100000"), "VND", "race-" + f.payment()));
          return true;
        } catch (com.influencermatch.backend.exception.ValidationException expected) {
          return false;
        } finally { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }
      };
      var a = pool.submit(action); var b = pool.submit(action); start.countDown();
      assertThat(List.of(a.get(20, java.util.concurrent.TimeUnit.SECONDS), b.get(20, java.util.concurrent.TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(true, false);
    }
    assertThat(paymentStatus(f)).isEqualTo("REFUNDED");
    assertThat(jdbc.queryForObject("select count(*) from audit_log where entity_id=? and action='CONFIRM_REFUND'", Long.class, f.payment())).isEqualTo(1);
  }
  @Test void statisticsFilterByTimeAndKeepCurrenciesSeparate() throws Exception {
    var vnd = fixture(); var usd = fixture();
    var date = LocalDateTime.of(2024, 1, 15, 12, 0);
    jdbc.update("update payment set paid_at=? where id=?", date, vnd.payment());
    jdbc.update("update payment set paid_at=?, currency='USD' where id=?", date, usd.payment());
    var report = call(vnd.admin(), get("/admin/payments/stats").param("from", date.toString())
        .param("to", date.toString()), null, 200).path("data");
    assertThat(report.path("totalRevenue").decimalValue()).isEqualByComparingTo("100000");
    assertThat(report.path("paidCount").asInt()).isEqualTo(1);
    var empty = call(vnd.admin(), get("/admin/payments/stats").param("from", date.plusSeconds(1).toString())
        .param("to", date.plusMinutes(1).toString()), null, 200).path("data");
    assertThat(empty.path("totalRevenue").decimalValue()).isZero();
  }
}
