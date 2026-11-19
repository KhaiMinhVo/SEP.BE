package com.influencermatch.backend.admin.service;

import com.influencermatch.backend.admin.dto.AdminAnalyticsDtos.CampaignSummary;
import com.influencermatch.backend.admin.dto.AdminAnalyticsDtos.MonthlyUserGrowth;
import com.influencermatch.backend.admin.dto.AdminAnalyticsDtos.PlanRevenue;
import com.influencermatch.backend.admin.dto.AdminAnalyticsDtos.SubscriptionSummary;
import com.influencermatch.backend.admin.dto.AdminAnalyticsDtos.UserGrowth;
import com.influencermatch.backend.billing.enums.PaymentStatus;
import com.influencermatch.backend.billing.enums.SubscriptionStatus;
import com.influencermatch.backend.billing.repository.PaymentRepository;
import com.influencermatch.backend.billing.repository.SubscriptionRepository;
import com.influencermatch.backend.campaign.enums.CampaignStatus;
import com.influencermatch.backend.campaign.repository.CampaignRepository;
import com.influencermatch.backend.security.permission.Permission;
import com.influencermatch.backend.security.permission.Permissions;
import com.influencermatch.backend.user.repository.UserRepository;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminAnalyticsService {
  private final SubscriptionRepository subscriptionRepository;
  private final CampaignRepository campaignRepository;
  private final UserRepository userRepository;
  private final PaymentRepository paymentRepository;

  public SubscriptionSummary subscriptionSummary() {
    Permissions.require(Permission.MANAGE_SUBSCRIPTION);
    return new SubscriptionSummary(
        subscriptionRepository.count(),
        subscriptionRepository.countByStatus(SubscriptionStatus.PENDING),
        subscriptionRepository.countByStatus(SubscriptionStatus.ACTIVE),
        subscriptionRepository.countByStatus(SubscriptionStatus.EXPIRED),
        subscriptionRepository.countByStatus(SubscriptionStatus.CANCELLED));
  }

  public CampaignSummary campaignSummary() {
    Permissions.require(Permission.VIEW_DATA_MONITORING);
    return new CampaignSummary(
        campaignRepository.count(),
        campaignRepository.countByStatus(CampaignStatus.DRAFT),
        campaignRepository.countByStatus(CampaignStatus.READY_FOR_DISCOVERY),
        campaignRepository.countByStatus(CampaignStatus.ACTIVE),
        campaignRepository.countByStatus(CampaignStatus.COMPLETED),
        campaignRepository.countByStatus(CampaignStatus.ARCHIVED));
  }

  public UserGrowth userGrowth(int months) {
    Permissions.require(Permission.VIEW_DATA_MONITORING);
    YearMonth currentMonth = YearMonth.now();
    YearMonth firstMonth = currentMonth.minusMonths(months - 1L);
    List<MonthlyUserGrowth> data = new ArrayList<>(months);

    for (int i = 0; i < months; i++) {
      YearMonth month = firstMonth.plusMonths(i);
      LocalDateTime from = month.atDay(1).atStartOfDay();
      LocalDateTime to = month.plusMonths(1).atDay(1).atStartOfDay();
      data.add(
          new MonthlyUserGrowth(
              month.toString(),
              userRepository.countByCreatedAtGreaterThanEqualAndCreatedAtLessThan(from, to)));
    }

    return new UserGrowth(months, List.copyOf(data));
  }

  public List<PlanRevenue> revenueByPlan() {
    Permissions.require(Permission.MANAGE_SUBSCRIPTION);
    return paymentRepository.findRevenueByPlanAndCurrency(PaymentStatus.PAID).stream()
        .map(
            row ->
                new PlanRevenue(
                    row.getPlanId(),
                    row.getPlanName(),
                    row.getCurrency(),
                    row.getPaymentCount(),
                    row.getRevenue()))
        .toList();
  }
}
