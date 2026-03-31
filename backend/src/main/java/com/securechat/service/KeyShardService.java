package com.securechat.service;

import org.springframework.stereotype.Service;
import java.util.Base64;

@Service
public class KeyShardService {

    public String[] split(String aesKeyBase64) {
        byte[] keyBytes = Base64.getDecoder().decode(aesKeyBase64);
        if (keyBytes.length != 32) {
            throw new IllegalArgumentException("AES key must be 32 bytes");
        }
        byte[] shard1 = new byte[16];
        byte[] shard2 = new byte[16];
        System.arraycopy(keyBytes, 0, shard1, 0, 16);
        System.arraycopy(keyBytes, 16, shard2, 0, 16);
        return new String[]{
            Base64.getEncoder().encodeToString(shard1),
            Base64.getEncoder().encodeToString(shard2)
        };
    }

    public String reconstruct(String shard1Base64, String shard2Base64) {
        byte[] s1 = Base64.getDecoder().decode(shard1Base64);
        byte[] s2 = Base64.getDecoder().decode(shard2Base64);
        byte[] full = new byte[32];
        System.arraycopy(s1, 0, full, 0, 16);
        System.arraycopy(s2, 0, full, 16, 16);
        return Base64.getEncoder().encodeToString(full);
    }
}
