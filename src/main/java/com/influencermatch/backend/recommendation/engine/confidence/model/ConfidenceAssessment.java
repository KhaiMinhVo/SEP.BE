package com.influencermatch.backend.recommendation.engine.confidence.model;

import com.influencermatch.backend.recommendation.engine.confidence.enums.ConfidenceTier;
import java.util.List;

/**
 * Encapsulates evidence reliability assessment across all 4 memories
 * and composite confidence index.
 */
public record ConfidenceAssessment(
    ConfidenceTier tier,
    double compositeConfidence, // [0, 1]
    double reliabilityM1,
    double reliabilityM2,
    double reliabilityM3,
    double reliabilityM4,
    List<String> evidenceWarnings
) {}
