package com.influencermatch.backend.recommendation.research.dgp;

import com.influencermatch.backend.recommendation.research.dgp.DgpConfig.DatasetSplit;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.EnumMap;
import java.util.Map;
import java.util.Random;

public final class DeterministicRngStreams {

  public enum StreamName {
    LATENT_WORLD,
    BOOKING_EXPOSURE,
    HISTORICAL_OUTCOMES,
    PUBLIC_MEASUREMENT,
    MISSINGNESS,
    ORACLE_EVALUATION
  }

  private final Map<StreamName, Random> streams = new EnumMap<>(StreamName.class);

  public DeterministicRngStreams(long baseSeed, DatasetSplit split) {
    for (StreamName name : StreamName.values()) {
      streams.put(name, new Random(deriveSeed(baseSeed, split.splitSalt(), name.name())));
    }
  }

  public Random stream(StreamName name) {
    return streams.get(name);
  }

  private static long deriveSeed(long baseSeed, long splitSalt, String streamName) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      md.update(ByteBuffer.allocate(16).putLong(baseSeed).putLong(splitSalt).array());
      md.update(streamName.getBytes(StandardCharsets.UTF_8));
      byte[] hash = md.digest();
      return ByteBuffer.wrap(hash, 0, 8).getLong();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }
}
