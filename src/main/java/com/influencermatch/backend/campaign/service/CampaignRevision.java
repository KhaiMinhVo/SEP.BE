package com.influencermatch.backend.campaign.service;

import com.influencermatch.backend.exception.*;
import java.util.UUID;

public final class CampaignRevision {
  private CampaignRevision() {}

  public static String tag(UUID id, long revision) {
    return "\"campaign:" + id + ":r" + revision + "\"";
  }

  public static void require(String header, UUID id, long revision) {
    if (header == null || header.isBlank())
      throw new BusinessException(ErrorCode.PRECONDITION_REQUIRED, "If-Match is required");
    if (!header.matches(
        "\"campaign:[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}:r[0-9]+\""))
      throw new ValidationException("Invalid If-Match ETag");
    if (!header.equals(tag(id, revision)))
      throw new BusinessException(
          ErrorCode.CAMPAIGN_REVISION_CONFLICT, "Campaign revision changed");
  }
}
