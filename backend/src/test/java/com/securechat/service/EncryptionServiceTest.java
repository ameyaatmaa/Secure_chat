package com.securechat.service;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Security;

import static org.assertj.core.api.Assertions.*;

class EncryptionServiceTest {

    private final EncryptionService service = new EncryptionService();

    @BeforeAll
    static void registerBC() {
        Security.addProvider(new BouncyCastleProvider());
    }

    @Test
    void aesGcm_encryptDecrypt_roundTrip() throws Exception {
        String plaintext = "Hello, secure world!";
        byte[] key = service.generateAesKey();
        EncryptionService.AesResult result = service.aesEncrypt(plaintext.getBytes(), key);
        byte[] decrypted = service.aesDecrypt(result.ciphertext(), result.iv(), key);
        assertThat(new String(decrypted)).isEqualTo(plaintext);
    }

    @Test
    void aesGcm_tamperedCiphertext_throwsException() throws Exception {
        byte[] key = service.generateAesKey();
        EncryptionService.AesResult result = service.aesEncrypt("data".getBytes(), key);
        result.ciphertext()[0] ^= 0xFF; // tamper
        assertThatThrownBy(() -> service.aesDecrypt(result.ciphertext(), result.iv(), key))
                .isInstanceOf(Exception.class);
    }

    @Test
    void rsaEncryptDecrypt_roundTrip() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA", "BC");
        gen.initialize(2048);
        KeyPair pair = gen.generateKeyPair();
        byte[] aesKey = service.generateAesKey();
        byte[] encrypted = service.rsaEncrypt(aesKey, pair.getPublic());
        byte[] decrypted = service.rsaDecrypt(encrypted, pair.getPrivate());
        assertThat(decrypted).isEqualTo(aesKey);
    }
}
