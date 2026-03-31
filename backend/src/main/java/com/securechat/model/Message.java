package com.securechat.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "messages")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Message {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sender_id", nullable = false)
    private User sender;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "receiver_id", nullable = false)
    private User receiver;

    @Column(name = "image_filename")
    private String imageFilename;

    @Column(name = "encrypted_key", nullable = false, columnDefinition = "TEXT")
    private String encryptedKey;

    @Column(name = "sender_lat")
    private Double senderLat;

    @Column(name = "sender_lon")
    private Double senderLon;

    @Column(name = "radius_meters", nullable = false)
    private Integer radiusMeters;

    @Column(name = "created_at")
    private Instant createdAt;

    // Temporal decay
    @Column(name = "key_shard", columnDefinition = "TEXT")
    private String keyShard;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "expired")
    @Builder.Default
    private Boolean expired = false;

    // Geo-lock toggle
    @Column(name = "geo_locked")
    @Builder.Default
    private Boolean geoLocked = true;

    // Document support
    @Column(name = "file_name")
    private String fileName;

    @Column(name = "file_type", length = 100)
    private String fileType;

    @Column(name = "file_size")
    private Long fileSize;

    @Column(name = "is_document")
    @Builder.Default
    private Boolean isDocument = false;

    // Burn after read
    @Column(name = "burn_after_read")
    @Builder.Default
    private Boolean burnAfterRead = false;

    // Read status
    @Column(name = "is_read")
    @Builder.Default
    private Boolean isRead = false;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
        if (expiresAt == null) {
            expiresAt = createdAt.plusSeconds(1800); // 30 minutes
        }
    }
}
