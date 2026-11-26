package com.influencermatch.backend.recommendation.research.dgp;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;

public record DgpConfig(
    String dgpVersion,
    DgpRegime regime,
    MissingnessMechanism missingnessMechanism,
    MissingnessIntensity missingnessIntensity,
    DatasetSplit split,
    long baseSeed,
    int creatorCount,
    Instant scoringAsOf
) {

  public static final String DGP_VERSION = "dgp.v1";

  public enum DgpRegime {
    DGP_A(0.85, 0.05),
    DGP_B(0.15, 0.35);

    private final double historyTransferWeight;
    private final double brandBiasStd;

    DgpRegime(double historyTransferWeight, double brandBiasStd) {
      this.historyTransferWeight = historyTransferWeight;
      this.brandBiasStd = brandBiasStd;
    }

    public double historyTransferWeight() {
      return historyTransferWeight;
    }

    public double brandBiasStd() {
      return brandBiasStd;
    }
  }

  public enum MissingnessMechanism {
    MCAR,
    MNAR
  }

  public enum MissingnessIntensity {
    LOW(0.08, 0.15),
    HIGH(0.30, 0.45);

    private final double mcarRate;
    private final double mnarScale;

    MissingnessIntensity(double mcarRate, double mnarScale) {
      this.mcarRate = mcarRate;
      this.mnarScale = mnarScale;
    }

    public double mcarRate() {
      return mcarRate;
    }

    public double mnarScale() {
      return mnarScale;
    }
  }

  public enum DatasetSplit {
    TRAIN(101L),
    DEV(202L),
    TEST(303L);

    private final long splitSalt;

    DatasetSplit(long splitSalt) {
      this.splitSalt = splitSalt;
    }

    public long splitSalt() {
      return splitSalt;
    }
  }

  public DgpConfig {
    if (!DGP_VERSION.equals(dgpVersion)) {
      throw new IllegalArgumentException("Unsupported dgpVersion: " + dgpVersion);
    }
    Objects.requireNonNull(regime, "regime must not be null");
    Objects.requireNonNull(missingnessMechanism, "missingnessMechanism must not be null");
    Objects.requireNonNull(missingnessIntensity, "missingnessIntensity must not be null");
    Objects.requireNonNull(split, "split must not be null");
    Objects.requireNonNull(scoringAsOf, "scoringAsOf must not be null");
    if (creatorCount < 1) {
      throw new IllegalArgumentException("creatorCount must be >= 1");
    }
  }

  public String configHash() {
    String raw = String.join("|",
        dgpVersion, regime.name(), missingnessMechanism.name(),
        missingnessIntensity.name(), split.name(),
        Long.toString(baseSeed), Integer.toString(creatorCount), scoringAsOf.toString());
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest).substring(0, 16);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }
}
