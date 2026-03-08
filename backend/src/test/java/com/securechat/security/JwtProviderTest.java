package com.securechat.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class JwtProviderTest {

    private JwtProvider jwtProvider;

    @BeforeEach
    void setUp() {
        jwtProvider = new JwtProvider("test-secret-key-min-32-characters-long", 3600000L);
    }

    @Test
    void generateAndValidate_roundTrip() {
        String token = jwtProvider.generateToken("alice");
        assertThat(jwtProvider.validateToken(token)).isTrue();
        assertThat(jwtProvider.getUsernameFromToken(token)).isEqualTo("alice");
    }

    @Test
    void tamperedToken_isInvalid() {
        String token = jwtProvider.generateToken("alice");
        String tampered = token.substring(0, token.length() - 5) + "XXXXX";
        assertThat(jwtProvider.validateToken(tampered)).isFalse();
    }

    @Test
    void expiredToken_isInvalid() {
        JwtProvider shortLived = new JwtProvider("test-secret-key-min-32-characters-long", 1L);
        String token = shortLived.generateToken("alice");
        try { Thread.sleep(10); } catch (InterruptedException ignored) {}
        assertThat(shortLived.validateToken(token)).isFalse();
    }
}
