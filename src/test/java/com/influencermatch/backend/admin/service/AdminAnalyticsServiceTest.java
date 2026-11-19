package com.influencermatch.backend.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influencermatch.backend.billing.enums.PaymentStatus;
import com.influencermatch.backend.billing.repository.PaymentRepository;
import com.influencermatch.backend.billing.repository.PaymentRepository.PlanRevenueProjection;
import com.influencermatch.backend.billing.repository.SubscriptionRepository;
import com.influencermatch.backend.campaign.repository.CampaignRepository;
import com.influencermatch.backend.user.enums.Role;
import com.influencermatch.backend.user.repository.UserRepository;
import com.influencermatch.backend.support.TestActors;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(MockitoExtension.class)
class AdminAnalyticsServiceTest {
  @Mock private SubscriptionRepository subscriptionRepository;
  @Mock private CampaignRepository campaignRepository;
  @Mock private UserRepository userRepository;
  @Mock private PaymentRepository paymentRepository;

  @InjectMocks private AdminAnalyticsService service;

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void userGrowthReturnsAllRequestedMonthsIncludingEmptyMonths() {
    TestActors.authenticate(TestActors.user(Role.DATA_MANAGER));
    when(userRepository.countByCreatedAtGreaterThanEqualAndCreatedAtLessThan(
            any(LocalDateTime.class), any(LocalDateTime.class)))
        .thenReturn(2L, 0L, 1L, 0L, 0L, 3L);

    YearMonth currentMonth = YearMonth.now();
    var result = service.userGrowth(6);

    assertThat(result.months()).isEqualTo(6);
    assertThat(result.data())
        .extracting("month")
        .containsExactly(
            currentMonth.minusMonths(5).toString(),
            currentMonth.minusMonths(4).toString(),
            currentMonth.minusMonths(3).toString(),
            currentMonth.minusMonths(2).toString(),
            currentMonth.minusMonths(1).toString(),
            currentMonth.toString());
    assertThat(result.data()).extracting("newUsers").containsExactly(2L, 0L, 1L, 0L, 0L, 3L);
    verify(userRepository, org.mockito.Mockito.times(6))
        .countByCreatedAtGreaterThanEqualAndCreatedAtLessThan(
            any(LocalDateTime.class), any(LocalDateTime.class));
  }

  @Test
  void revenueByPlanUsesSettledPaidTransactionsOnly() {
    TestActors.authenticate(TestActors.user(Role.ADMIN));
    PlanRevenueProjection row = mock(PlanRevenueProjection.class);
    when(row.getPlanId()).thenReturn(UUID.randomUUID());
    when(row.getPlanName()).thenReturn("Starter");
    when(row.getCurrency()).thenReturn("VND");
    when(row.getPaymentCount()).thenReturn(2L);
    when(row.getRevenue()).thenReturn(new BigDecimal("200000.00"));
    when(paymentRepository.findRevenueByPlanAndCurrency(PaymentStatus.PAID))
        .thenReturn(List.of(row));

    var result = service.revenueByPlan();

    assertThat(result).hasSize(1);
    assertThat(result.getFirst().currency()).isEqualTo("VND");
    assertThat(result.getFirst().paymentCount()).isEqualTo(2L);
    assertThat(result.getFirst().revenue()).isEqualByComparingTo("200000.00");
    verify(paymentRepository).findRevenueByPlanAndCurrency(PaymentStatus.PAID);
  }
}
