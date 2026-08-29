package com.sq.caa.domain;

import java.math.BigDecimal;

/**
 * Risk banding derived from the total score of an analysis run:
 * {@code LOW < 25 <= MEDIUM < 50 <= HIGH < 75 <= CRITICAL}.
 */
public enum RiskLevel {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL;

    private static final BigDecimal MEDIUM_FLOOR = new BigDecimal("25");
    private static final BigDecimal HIGH_FLOOR = new BigDecimal("50");
    private static final BigDecimal CRITICAL_FLOOR = new BigDecimal("75");

    /** Bands a total score. A {@code null} score is treated as zero. */
    public static RiskLevel forScore(BigDecimal totalScore) {
        BigDecimal score = totalScore == null ? BigDecimal.ZERO : totalScore;
        if (score.compareTo(CRITICAL_FLOOR) >= 0) {
            return CRITICAL;
        }
        if (score.compareTo(HIGH_FLOOR) >= 0) {
            return HIGH;
        }
        if (score.compareTo(MEDIUM_FLOOR) >= 0) {
            return MEDIUM;
        }
        return LOW;
    }
}
