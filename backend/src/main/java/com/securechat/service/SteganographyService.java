package com.securechat.service;

import org.springframework.stereotype.Service;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;

@Service
public class SteganographyService {

    // Header: 4 bytes (int) for payload length
    private static final int HEADER_BYTES = 4;

    public BufferedImage embed(BufferedImage image, byte[] payload) {
        int totalBits = (HEADER_BYTES + payload.length) * 8;
        int availableBits = image.getWidth() * image.getHeight() * 3;
        if (totalBits > availableBits) {
            throw new IllegalArgumentException(
                "Payload too large for image. Payload: " + payload.length + " bytes, " +
                "image capacity: " + (availableBits / 8 - HEADER_BYTES) + " bytes"
            );
        }

        BufferedImage output = copyImage(image);
        byte[] data = prependLength(payload);
        int bitIndex = 0;

        outer:
        for (int y = 0; y < output.getHeight(); y++) {
            for (int x = 0; x < output.getWidth(); x++) {
                int rgb = output.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;

                if (bitIndex < data.length * 8) r = setBit(r, getBit(data, bitIndex++));
                if (bitIndex < data.length * 8) g = setBit(g, getBit(data, bitIndex++));
                if (bitIndex < data.length * 8) b = setBit(b, getBit(data, bitIndex++));

                output.setRGB(x, y, (r << 16) | (g << 8) | b);
                if (bitIndex >= data.length * 8) break outer;
            }
        }
        return output;
    }

    public byte[] extract(BufferedImage image) {
        // Read header (first 4*8 = 32 bits) to get payload length
        byte[] header = extractBits(image, 0, HEADER_BYTES * 8);
        int payloadLength = ByteBuffer.wrap(header).getInt();
        if (payloadLength <= 0 || payloadLength > image.getWidth() * image.getHeight() * 3 / 8) {
            throw new IllegalStateException("Invalid payload length in image header: " + payloadLength);
        }
        return extractBits(image, HEADER_BYTES * 8, payloadLength * 8);
    }

    private byte[] extractBits(BufferedImage image, int startBit, int numBits) {
        byte[] result = new byte[(numBits + 7) / 8];
        int currentBit = 0;
        int resultBit = 0;

        outer:
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int rgb = image.getRGB(x, y);
                int[] channels = {(rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF};
                for (int channel : channels) {
                    if (currentBit >= startBit && resultBit < numBits) {
                        if ((channel & 1) == 1) {
                            result[resultBit / 8] |= (byte)(1 << (7 - (resultBit % 8)));
                        }
                        resultBit++;
                    }
                    currentBit++;
                    if (resultBit >= numBits) break outer;
                }
            }
        }
        return result;
    }

    private byte[] prependLength(byte[] payload) {
        byte[] result = new byte[HEADER_BYTES + payload.length];
        byte[] lengthBytes = ByteBuffer.allocate(4).putInt(payload.length).array();
        System.arraycopy(lengthBytes, 0, result, 0, HEADER_BYTES);
        System.arraycopy(payload, 0, result, HEADER_BYTES, payload.length);
        return result;
    }

    private int getBit(byte[] data, int index) {
        return (data[index / 8] >> (7 - (index % 8))) & 1;
    }

    private int setBit(int channel, int bit) {
        return (channel & 0xFE) | bit;
    }

    private BufferedImage copyImage(BufferedImage src) {
        BufferedImage copy = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        copy.getGraphics().drawImage(src, 0, 0, null);
        return copy;
    }
}
