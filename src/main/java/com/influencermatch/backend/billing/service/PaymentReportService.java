package com.influencermatch.backend.billing.service;

import com.influencermatch.backend.billing.dto.AdminPaymentDTOs.PaymentStatsResponse;
import com.influencermatch.backend.exception.ValidationException;
import com.influencermatch.backend.security.permission.Permission;
import com.influencermatch.backend.security.permission.Permissions;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PaymentReportService {
  private final JdbcTemplate jdbc;
  public record RevenuePoint(LocalDate period, BigDecimal revenue, long paidCount) {}
  public record RevenueReport(LocalDate from, LocalDate to, String groupBy, String currency,
      List<RevenuePoint> items) {}

  public static void validateRange(LocalDateTime from, LocalDateTime to) {
    if (from != null && to != null && from.isAfter(to))
      throw new ValidationException("Start date must not be after end date");
  }

  public PaymentStatsResponse stats(LocalDateTime from, LocalDateTime to) {
    Permissions.require(Permission.MANAGE_SUBSCRIPTION);
    validateRange(from, to);
    String sql = "SELECT "
        + "COALESCE(SUM(amount) FILTER (WHERE status='PAID'),0) revenue, "
        + "COALESCE(SUM(amount) FILTER (WHERE status='REFUNDED'),0) refunded, "
        + "COALESCE(SUM(amount) FILTER (WHERE status='PENDING'),0) pending, "
        + "COUNT(*) FILTER (WHERE status='PAID') paid_count, "
        + "COUNT(*) FILTER (WHERE status='REFUNDED') refunded_count, "
        + "COUNT(*) FILTER (WHERE status='PENDING') pending_count, "
        + "COUNT(*) FILTER (WHERE status='FAILED') failed_count, COUNT(*) total_count "
        + "FROM payment WHERE currency='VND'";
    List<Object> args = new ArrayList<>();
    if (from != null) { sql += " AND COALESCE(paid_at,created_at)>=?"; args.add(from); }
    if (to != null) { sql += " AND COALESCE(paid_at,created_at)<=?"; args.add(to); }
    return jdbc.queryForObject(sql, (rs, row) -> PaymentStatsResponse.builder()
        .totalRevenue(rs.getBigDecimal("revenue")).totalRefunded(rs.getBigDecimal("refunded"))
        .totalPending(rs.getBigDecimal("pending")).paidCount(rs.getLong("paid_count"))
        .refundedCount(rs.getLong("refunded_count")).pendingCount(rs.getLong("pending_count"))
        .failedCount(rs.getLong("failed_count")).totalCount(rs.getLong("total_count")).build(), args.toArray());
  }

  public RevenueReport revenue(LocalDate from, LocalDate to, String groupBy) {
    Permissions.require(Permission.MANAGE_SUBSCRIPTION);
    if (!Set.of("day", "month").contains(groupBy))
      throw new ValidationException("groupBy must be day or month");
    LocalDate end = to == null ? LocalDate.now() : to;
    LocalDate start = from == null ? end.minusDays(29) : from;
    if (start.isAfter(end) || ChronoUnit.DAYS.between(start, end) > 366)
      throw new ValidationException("Choose a date range of at most 367 calendar days");
    var points = jdbc.query("SELECT date_trunc(?, paid_at)::date period, SUM(amount) revenue, "
        + "COUNT(*) paid_count FROM payment WHERE status='PAID' AND currency='VND' "
        + "AND paid_at>=? AND paid_at<? GROUP BY 1 ORDER BY 1",
        (rs, row) -> new RevenuePoint(rs.getDate("period").toLocalDate(),
            rs.getBigDecimal("revenue"), rs.getLong("paid_count")),
        groupBy, start.atStartOfDay(), end.plusDays(1).atStartOfDay());
    Map<LocalDate, RevenuePoint> byDate = new HashMap<>();
    points.forEach(point -> byDate.put(point.period(), point));
    List<RevenuePoint> filled = new ArrayList<>();
    for (LocalDate date = groupBy.equals("month") ? start.withDayOfMonth(1) : start;
         !date.isAfter(end); date = groupBy.equals("month") ? date.plusMonths(1) : date.plusDays(1)) {
      filled.add(byDate.getOrDefault(date, new RevenuePoint(date, BigDecimal.ZERO, 0)));
    }
    return new RevenueReport(start, end, groupBy, "VND", filled);
  }
}
