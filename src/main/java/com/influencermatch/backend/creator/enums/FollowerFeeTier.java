package com.influencermatch.backend.creator.enums;

import java.math.BigDecimal;
import java.util.Arrays;
import lombok.Getter;

@Getter
public enum FollowerFeeTier {
  NANO(10_000L, BigDecimal.valueOf(200_000), BigDecimal.valueOf(500_000)),
  MICRO_LOW(50_000L, BigDecimal.valueOf(1_000_000), BigDecimal.valueOf(2_500_000)),
  MICRO_HIGH(100_000L, BigDecimal.valueOf(2_500_000), BigDecimal.valueOf(5_000_000)),
  MACRO(500_000L, BigDecimal.valueOf(5_000_000), BigDecimal.valueOf(15_000_000)),
  TOP_MACRO(1_000_000L, BigDecimal.valueOf(15_000_000), BigDecimal.valueOf(30_000_000)),
  MEGA(Long.MAX_VALUE, BigDecimal.valueOf(30_000_000), BigDecimal.valueOf(60_000_000));

  private final long maxFollowers;
  private final BigDecimal minFee;
  private final BigDecimal maxFee;

  FollowerFeeTier(long maxFollowers, BigDecimal minFee, BigDecimal maxFee) {
    this.maxFollowers = maxFollowers;
    this.minFee = minFee;
    this.maxFee = maxFee;
  }

  public static FollowerFeeTier fromFollowers(Long followers) {
    long f = (followers != null) ? Math.max(0, followers) : 0L;
    return Arrays.stream(values())
        .filter(tier -> f < tier.maxFollowers)
        .findFirst()
        .orElse(MEGA);
  }
}

