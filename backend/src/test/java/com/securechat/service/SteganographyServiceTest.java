package com.securechat.service;

import org.junit.jupiter.api.Test;
import java.awt.image.BufferedImage;
import static org.assertj.core.api.Assertions.*;

class SteganographyServiceTest {

    private final SteganographyService service = new SteganographyService();

    @Test
    void embed_and_extract_roundTrip() throws Exception {
        BufferedImage image = new BufferedImage(200, 200, BufferedImage.TYPE_INT_RGB);
        byte[] payload = "Hello steganography test payload!".getBytes();
        BufferedImage encoded = service.embed(image, payload);
        byte[] extracted = service.extract(encoded);
        assertThat(extracted).isEqualTo(payload);
    }

    @Test
    void payloadTooLarge_throwsException() {
        // 10x10 image = 100 pixels * 3 channels = 300 bits = 37 bytes usable
        BufferedImage tinyImage = new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB);
        byte[] bigPayload = new byte[500];
        assertThatThrownBy(() -> service.embed(tinyImage, bigPayload))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too large");
    }
}
