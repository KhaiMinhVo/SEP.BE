package com.influencermatch.backend.recommendation.engine.phase0.model;

import com.influencermatch.backend.recommendation.engine.phase0.enums.ErMethod;
import com.influencermatch.backend.recommendation.engine.phase0.enums.Phase0Status;
import java.util.List;
import java.util.UUID;

public record NormalizedCandidateContext(
    UUID creatorId,
    UUID masterId,
    String platform,
    Phase0Status status,
    List<String> exclusionReasons,

    // Điểm chuẩn hóa [0, 1) sẵn sàng bàn giao cho Phase 1
    double erNormalized,
    double vrNormalized,
    double stabNormalized,

    // Raw metrics đã tính
    double rawEr,
    double rawVr,
    double rawStab,

    // Metadata bằng chứng phục vụ Phase 5 tính Confidence
    ErMethod erMethod,
    int postCount,
    boolean isStabImputed,
    double evidenceReliabilityM2, // r_i2
    String calibrationVersion
) {
  public boolean isEligible() {
    return status == Phase0Status.ELIGIBLE;
  }

  public boolean isExcluded() {
    return status == Phase0Status.INELIGIBLE;
  }
}
