package com.influencermatch.backend.creator.service;

import com.influencermatch.backend.creator.repository.CreatorRepository;
import com.influencermatch.backend.exception.ConflictException;
import com.influencermatch.backend.exception.ErrorCode;
import java.util.List;
import java.util.UUID;

public final class ChannelPlatformLimit {
  private ChannelPlatformLimit() {}

  public static boolean occupied(List<UUID> linkedIds, UUID targetId) {
    return !linkedIds.isEmpty() && (targetId == null || !linkedIds.contains(targetId));
  }

  /** The caller holds the Creator user row lock throughout the ownership change. */
  public static void requireSlot(CreatorRepository creators, UUID userId, String platform, UUID targetId) {
    if (occupied(creators.findChannelIdsByOwnerAndPlatform(userId, platform), targetId))
      throw new ConflictException(ErrorCode.CHANNEL_PLATFORM_ALREADY_LINKED,
          "Gỡ kênh đang liên kết trên nền tảng này trước khi liên kết kênh khác.");
  }
}
