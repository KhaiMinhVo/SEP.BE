package com.influencermatch.backend.recommendation.engine.phase0;

import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * Data Source Contract: Xác định quy ước múi giờ nguồn (Source Timezone) theo hợp đồng của từng nền tảng / crawler.
 *
 * Nguyên tắc kiến trúc:
 * Tuyệt đối không suy luận Source Timezone từ vị trí địa lý của Campaign (Campaign Location != Data Source Timezone).
 * Nguồn dữ liệu từ các social platform APIs (TikTok Research API, Meta Graph API, YouTube Data API)
 * mặc định tuân thủ chuẩn ISO-8601 UTC.
 */
public final class DataSourceContract {

  public static final ZoneId DEFAULT_SOURCE_ZONE = ZoneOffset.UTC;
  public static final ZoneId VN_SOURCE_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

  private DataSourceContract() {}

  /**
   * Xác định múi giờ nguồn chuẩn hóa theo hợp đồng dữ liệu của nền tảng:
   * - Nền tảng nội địa / feed chỉ định giờ Việt Nam (ZALO, VN_LOCAL): sử dụng Asia/Ho_Chi_Minh.
   * - Toàn bộ các nền tảng mạng xã hội quốc tế (TIKTOK, INSTAGRAM, YOUTUBE, FACEBOOK,...): chuẩn hóa UTC.
   */
  public static ZoneId resolveSourceZone(String platform) {
    if (platform == null || platform.isBlank()) {
      return DEFAULT_SOURCE_ZONE;
    }
    String p = platform.trim().toUpperCase();
    if ("ZALO".equals(p) || "VN_LOCAL".equals(p)) {
      return VN_SOURCE_ZONE;
    }
    return DEFAULT_SOURCE_ZONE;
  }
}

