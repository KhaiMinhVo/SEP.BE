package com.influencermatch.backend.brand.service;

import com.influencermatch.backend.exception.*;
import java.util.UUID;

public final class BrandRevision {
  private BrandRevision() {}

  public static String tag(UUID id, long revision) {
    return "\"brand:" + id + ":r" + revision + "\"";
  }

  public static void require(String header, UUID id, long revision) {
    if (header == null || header.isBlank())
      throw new BusinessException(ErrorCode.PRECONDITION_REQUIRED, "If-Match is required");
    if (!header.matches("\"brand:[0-9a-fA-F-]{36}:r[0-9]+\""))
      throw new ValidationException("Invalid If-Match ETag");
    if (!header.equals(tag(id, revision)))
      throw new BusinessException(
          ErrorCode.BRAND_REVISION_CONFLICT, "Brand profile revision changed");
  }
}
