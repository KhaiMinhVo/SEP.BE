package com.influencermatch.backend.creator.service;

import com.influencermatch.backend.billing.enums.SubscriptionStatus;
import com.influencermatch.backend.billing.repository.SubscriptionRepository;
import com.influencermatch.backend.brand.repository.BrandProfileRepository;
import com.influencermatch.backend.creator.repository.CreatorRepository;
import com.influencermatch.backend.exception.*;
import com.influencermatch.backend.relationship.service.CampaignOwnershipService;
import com.influencermatch.backend.security.permission.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor
public class CreatorRefreshStore {
  private final CampaignOwnershipService ownership;
  private final BrandProfileRepository brands;
  private final CreatorRepository creators;
  private final SubscriptionRepository subscriptions;
  private final JdbcTemplate jdbc;
  private final Clock clock;

  public record Request(UUID id, UUID campaignId, UUID creatorId, UUID subscriptionId,
      String platform, String username, String status, boolean created) {}

  private List<Request> read(String where, Object... parameters) {
    return jdbc.query("SELECT * FROM creator_refresh_request WHERE " + where,
        (rs, n) -> new Request(rs.getObject("id", UUID.class), rs.getObject("campaign_id", UUID.class),
            rs.getObject("creator_id", UUID.class), rs.getObject("subscription_id", UUID.class),
            rs.getString("platform"), rs.getString("username"), rs.getString("status"), false), parameters);
  }

  @Transactional
  public Request reserve(UUID campaignId, UUID creatorId, UUID key) {
    Permissions.require(Permission.REQUEST_CREATOR_REFRESH);
    var campaign = ownership.requireOwned(campaignId);
    var brandId = campaign.getBrandProfile().getId();
    // Same billing lock as purchases/refunds; serialize concurrent requests across campaigns.
    brands.lockForBilling(brandId).orElseThrow(() -> new NotFoundException("Brand not found"));
    var previous = read("id=?", key);
    if (!previous.isEmpty()) {
      var request = previous.getFirst();
      if (!request.campaignId().equals(campaignId) || !request.creatorId().equals(creatorId))
        throw new ValidationException("Idempotency key belongs to another refresh request");
      return request;
    }
    var creator = creators.findById(creatorId)
        .filter(c -> !c.isDiscoveryHidden()).orElseThrow(() -> new NotFoundException("Creator not found"));
    if (!Set.of("TIKTOK", "INSTAGRAM", "FACEBOOK").contains(creator.getPlatform())
        || creator.getUserName() == null || creator.getUserName().isBlank())
      throw new ValidationException("Kênh này chưa hỗ trợ cập nhật dữ liệu tự động");
    var pending = read("brand_profile_id=? AND creator_id=? AND status IN ('RESERVED','QUEUED') "
        + "AND requested_at>? ORDER BY requested_at DESC LIMIT 1", brandId, creatorId,
        Timestamp.from(clock.instant().minusSeconds(300)));
    if (!pending.isEmpty()) return pending.getFirst();
    var today = LocalDate.now(clock);
    var subscription = subscriptions.findByBrandProfileId(brandId).stream()
        .filter(s -> s.getStatus() == SubscriptionStatus.ACTIVE && !s.isRefundHold()
            && !s.getStartDate().isAfter(today) && !s.getExpirationDate().isBefore(today))
        .findFirst().orElseThrow(() -> new BusinessException(ErrorCode.SUBSCRIPTION_REQUIRED,
            "Cần gói còn hiệu lực để cập nhật. Bạn vẫn có thể xem và chọn kênh bằng dữ liệu đã lưu."));
    if (subscriptions.reserveRefreshQuotaAtomically(subscription.getId(), today) != 1)
      throw new ConflictException(ErrorCode.QUOTA_EXCEEDED,
          "Đã hết lượt cập nhật dữ liệu trong gói. Bạn vẫn có thể xem và chọn kênh bằng dữ liệu đã lưu.");
    jdbc.update("INSERT INTO creator_refresh_request(id,brand_profile_id,campaign_id,creator_id,subscription_id,"
        + "platform,username,status,requested_at) VALUES(?,?,?,?,?,?,?,'RESERVED',?)", key, brandId,
        campaignId, creatorId, subscription.getId(), creator.getPlatform(), creator.getUserName(),
        Timestamp.from(clock.instant()));
    return new Request(key, campaignId, creatorId, subscription.getId(), creator.getPlatform(),
        creator.getUserName(), "RESERVED", true);
  }

  @Transactional
  public void dispatched(Request request, boolean accepted) {
    int changed = jdbc.update("UPDATE creator_refresh_request SET status=?,dispatched_at=? "
        + "WHERE id=? AND status='RESERVED'", accepted ? "QUEUED" : "DISPATCH_FAILED",
        Timestamp.from(clock.instant()), request.id());
    if (!accepted && changed == 1) subscriptions.releaseRefreshQuota(request.subscriptionId());
  }

  @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 60000)
  @Transactional
  public void releaseAbandonedReservations() {
    // Recover quota if the process stopped between reservation and dispatch.
    var abandoned = read("status='RESERVED' AND requested_at<? ORDER BY requested_at LIMIT 100 FOR UPDATE SKIP LOCKED",
        Timestamp.from(clock.instant().minusSeconds(600)));
    for (var request : abandoned) dispatched(request, false);
  }
}
