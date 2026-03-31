package com.securechat.util;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class HaversineCalculatorTest {

    @Test
    void samePoint_returnsZero() {
        double dist = HaversineCalculator.distanceMeters(12.9716, 77.5946, 12.9716, 77.5946);
        assertThat(dist).isEqualTo(0.0);
    }

    @Test
    void knownDistance_bengaluruToMysuru_isApprox128km() {
        // Great-circle (Haversine) distance ~128km; road distance is ~140km
        double dist = HaversineCalculator.distanceMeters(12.9716, 77.5946, 12.2958, 76.6394);
        assertThat(dist).isBetween(126_000.0, 130_000.0);
    }

    @Test
    void within50Meters_returnsTrue() {
        // offset ~30m north
        double dist = HaversineCalculator.distanceMeters(12.9716, 77.5946, 12.97187, 77.5946);
        assertThat(dist).isLessThan(50.0);
    }

    @Test
    void beyond50Meters_returnsFalse() {
        // offset ~100m north
        double dist = HaversineCalculator.distanceMeters(12.9716, 77.5946, 12.97250, 77.5946);
        assertThat(dist).isGreaterThan(50.0);
    }
}
