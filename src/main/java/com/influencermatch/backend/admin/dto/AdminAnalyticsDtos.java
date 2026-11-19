package com.influencermatch.backend.admin.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public final class AdminAnalyticsDtos {
  private AdminAnalyticsDtos() {}

  public record SubscriptionSummary(
      long total, long pending, long active, long expired, long cancelled) {}

  public record CampaignSummary(
      long total,
      long draft,
      long readyForDiscovery,
      long active,
      long completed,
      long archived) {}

  public record MonthlyUserGrowth(String month, long newUsers) {}

  public record UserGrowth(int months, List<MonthlyUserGrowth> data) {}

  public record PlanRevenue(
      UUID planId, String planName, String currency, long paymentCount, BigDecimal revenue) {}
}
