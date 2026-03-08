package com.securechat.service;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class GeoLocationServiceTest {

    private final GeoLocationService service = new GeoLocationService();

    @Test
    void coordinatesInRange_isValid() {
        assertThat(service.isCoarseValid(12.9716, 77.5946)).isTrue();
    }

    @Test
    void nullIsland_isInvalid() {
        assertThat(service.isCoarseValid(0.0, 0.0)).isFalse();
    }

    @Test
    void outOfRange_lat_isInvalid() {
        assertThat(service.isCoarseValid(91.0, 0.0)).isFalse();
        assertThat(service.isCoarseValid(-91.0, 0.0)).isFalse();
    }

    @Test
    void outOfRange_lon_isInvalid() {
        assertThat(service.isCoarseValid(0.0, 181.0)).isFalse();
    }
}
