package com.influencermatch.backend.creator.service;

import com.influencermatch.backend.creator.client.CrawlerServiceClient;
import com.influencermatch.backend.exception.*;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Network dispatch runs outside the reservation transaction and holds no billing locks. */
@Service @RequiredArgsConstructor
public class CreatorRefreshService {
  private final CreatorRefreshStore store;
  private final CrawlerServiceClient crawler;

  public record Response(UUID requestId, UUID creatorId, String status, boolean quotaCharged, String message) {}

  public Response request(UUID campaignId, UUID creatorId, UUID key) {
    var request = store.reserve(campaignId, creatorId, key);
    if (!request.created()) {
      if (request.status().equals("DISPATCH_FAILED")) throw unavailable();
      return response(request, request.status());
    }
    try {
      crawler.requestShortlistCrawl(request.platform(), request.username(), request.creatorId(), request.campaignId());
    } catch (Exception failure) {
      store.dispatched(request, false);
      throw unavailable();
    }
    store.dispatched(request, true);
    return response(request, "QUEUED");
  }

  private Response response(CreatorRefreshStore.Request request, String status) {
    return new Response(request.id(), request.creatorId(), status, true,
        "Đã ghi nhận yêu cầu cập nhật (1 lượt). Hồ sơ tiếp tục dùng dữ liệu đã lưu cho đến khi cào xong.");
  }
  private BusinessException unavailable() {
    return new BusinessException(ErrorCode.CRAWLER_UNAVAILABLE,
        "Chưa gửi được yêu cầu cập nhật; lượt đã được hoàn lại. Dữ liệu đã lưu vẫn dùng được.");
  }
}
