package com.influencermatch.backend.creator.tiktok;

import java.util.Locale;

public final class TikTokUsernames {
  private TikTokUsernames() {}

  // Ingestion and OAuth share this gate to prevent username lookup phantoms/renames.
  // It is held only during database work, never while contacting TikTok.
  public static final String MATCH_LOCK = "TIKTOK:USERNAME_MATCH";

  public static String normalize(String value) {
    if (value == null) return "";
    String trimmed = value.trim();
    return (trimmed.startsWith("@") ? trimmed.substring(1) : trimmed).toLowerCase(Locale.ROOT);
  }
}
