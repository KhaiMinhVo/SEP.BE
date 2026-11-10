package com.influencermatch.backend.recommendation.engine.topsis.model;

/**
 * Result of the Fixed-Ideal TOPSIS calculation for a single candidate.
 */
public record TopsisResult(
    double distancePositive, // D+
    double distanceNegative, // D-
    double closeness,        // T in [0, 1]
    double matchScore        // MS = 100 * T
) {}
