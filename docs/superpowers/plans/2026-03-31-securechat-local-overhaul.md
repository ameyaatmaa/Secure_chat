# SecureChat Local Overhaul — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Migrate SecureChat to local dev, add temporal decay encryption (30min), encrypted documents, geo-fence fixes, quick-win features, and overhaul UI to Arch/Hyprland theme.

**Architecture:** Spring Boot 3.2.3 backend with PostgreSQL (Docker on port 5433), Alpine.js frontend served by Spring Boot. Key sharding for temporal decay, single server-side geo-verification, procedural noise images for steganography.

**Tech Stack:** Java 21, Spring Boot, PostgreSQL 15, Flyway, Alpine.js 3, Leaflet, node-forge, Web Crypto API

---

## File Structure

### New Files
| File | Responsibility |
|------|---------------|
| `backend/src/main/java/com/securechat/scheduler/MessageExpiryScheduler.java` | Scheduled task to purge expired messages every 2 min |
| `backend/src/main/java/com/securechat/service/KeyShardService.java` | AES key splitting and reconstruction logic |
| `backend/src/main/resources/db/migration/V2__add_expiry_documents_features.sql` | Schema additions for all new features |
| `LOCAL_PROJECT_DOCS.md` | Comprehensive local-only project documentation (gitignored) |

### Modified Files
| File | Changes |
|------|---------|
| `backend/src/main/resources/application.yml` | Port 5433, radius 100km, scheduling enabled |
| `backend/src/main/java/com/securechat/model/Message.java` | New columns: keyShard, expiresAt, expired, geoLocked, file*, burnAfterRead, read |
| `backend/src/main/java/com/securechat/model/User.java` | Add lastSeen field |
| `backend/src/main/java/com/securechat/repository/MessageRepository.java` | New queries for expiry, unread count |
| `backend/src/main/java/com/securechat/service/MessageService.java` | Key sharding, docs, burn-after-read, mark-read |
| `backend/src/main/java/com/securechat/service/GeoLocationService.java` | Return distance in response |
| `backend/src/main/java/com/securechat/controller/MessageController.java` | New fields, file endpoint, updated responses |
| `backend/src/main/java/com/securechat/controller/LocationController.java` | Return keyShard on success + distance info |
| `backend/src/main/java/com/securechat/security/JwtFilter.java` | Update user lastSeen on each request |
| `backend/src/main/java/com/securechat/config/SecurityConfig.java` | Permit new endpoints, relax cookie secure flag for local dev |
| `backend/src/main/java/com/securechat/controller/AuthController.java` | Set cookie secure=false for local dev |
| `frontend/index.html` | Complete rewrite — Hyprland theme HTML |
| `frontend/style.css` | Complete rewrite — dark theme CSS |
| `frontend/app.js` | Major rewrite — noise gen, key sharding, doc upload, new UI flows |
| `.gitignore` | Add LOCAL_PROJECT_DOCS.md |

---

### Task 1: Set Up PostgreSQL Docker Container on Port 5433

**Files:**
- Modify: `backend/src/main/resources/application.yml`
- Modify: `backend/src/main/java/com/securechat/controller/AuthController.java`
- Modify: `backend/src/main/java/com/securechat/config/SecurityConfig.java`
- Modify: `.gitignore`

- [ ] **Step 1: Start a fresh PostgreSQL Docker container**

```bash
docker run -d \
  --name securechat-postgres \
  -e POSTGRES_DB=securechat \
  -e POSTGRES_USER=chatuser \
  -e POSTGRES_PASSWORD=strongpassword \
  -p 5433:5432 \
  postgres:15
```

- [ ] **Step 2: Verify postgres is running**

```bash
PGPASSWORD=strongpassword psql -U chatuser -d securechat -h localhost -p 5433 -c "SELECT 1"
```

Expected: Returns `1` with no errors.

- [ ] **Step 3: Update application.yml for local dev**

In `backend/src/main/resources/application.yml`, change the default datasource URL port from 5432 to 5433 and update default geo radius:

```yaml
spring:
  datasource:
    url: ${SPRING_DATASOURCE_URL:jdbc:postgresql://localhost:5433/securechat}
    username: ${SPRING_DATASOURCE_USERNAME:chatuser}
    password: ${SPRING_DATASOURCE_PASSWORD:strongpassword}
    driver-class-name: org.postgresql.Driver
  jpa:
    hibernate:
      ddl-auto: validate
    show-sql: false
    properties:
      hibernate:
        dialect: org.hibernate.dialect.PostgreSQLDialect
  flyway:
    enabled: true
    locations: classpath:db/migration
  servlet:
    multipart:
      max-file-size: 10MB
      max-request-size: 15MB

server:
  port: 8080

app:
  jwt:
    secret: ${JWT_SECRET:change-this-secret-in-production-min-32-chars}
    expiration-ms: 86400000
  image:
    storage-path: ${IMAGE_STORAGE_PATH:./images}
  geo:
    default-radius-meters: 100000
  message:
    expiry-minutes: 30
```

- [ ] **Step 4: Fix cookie secure flag for local dev (no HTTPS)**

In `backend/src/main/java/com/securechat/controller/AuthController.java`, change the login and logout cookie to `secure(false)` so it works on `http://localhost:8080`:

In the `login` method, change:
```java
        ResponseCookie cookie = ResponseCookie.from("jwt", token)
                .httpOnly(true)
                .secure(false)
                .path("/")
                .maxAge(86400)
                .sameSite("Lax")
                .build();
```

In the `logout` method, change:
```java
        ResponseCookie cookie = ResponseCookie.from("jwt", "")
                .httpOnly(true)
                .secure(false)
                .path("/")
                .maxAge(0)
                .sameSite("Lax")
                .build();
```

- [ ] **Step 5: Add LOCAL_PROJECT_DOCS.md to .gitignore**

Append to `.gitignore` (create if it doesn't exist):
```
LOCAL_PROJECT_DOCS.md
```

- [ ] **Step 6: Build and verify the backend starts**

```bash
cd backend && mvn clean compile -q
```

Expected: BUILD SUCCESS, no errors.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/resources/application.yml backend/src/main/java/com/securechat/controller/AuthController.java .gitignore
git commit -m "switch to local postgres on port 5433 and fix cookie for local dev"
```

---

### Task 2: Database Schema — Flyway V2 Migration

**Files:**
- Create: `backend/src/main/resources/db/migration/V2__add_expiry_documents_features.sql`
- Modify: `backend/src/main/java/com/securechat/model/Message.java`
- Modify: `backend/src/main/java/com/securechat/model/User.java`
- Modify: `backend/src/main/java/com/securechat/repository/MessageRepository.java`

- [ ] **Step 1: Create V2 migration SQL**

Create `backend/src/main/resources/db/migration/V2__add_expiry_documents_features.sql`:

```sql
-- Temporal decay encryption
ALTER TABLE messages ADD COLUMN key_shard TEXT;
ALTER TABLE messages ADD COLUMN expires_at TIMESTAMPTZ;
ALTER TABLE messages ADD COLUMN expired BOOLEAN NOT NULL DEFAULT false;

-- Geo-lock toggle
ALTER TABLE messages ADD COLUMN geo_locked BOOLEAN NOT NULL DEFAULT true;

-- Document support
ALTER TABLE messages ADD COLUMN file_name VARCHAR(255);
ALTER TABLE messages ADD COLUMN file_type VARCHAR(100);
ALTER TABLE messages ADD COLUMN file_size BIGINT;
ALTER TABLE messages ADD COLUMN is_document BOOLEAN NOT NULL DEFAULT false;

-- Burn after reading
ALTER TABLE messages ADD COLUMN burn_after_read BOOLEAN NOT NULL DEFAULT false;

-- Read status
ALTER TABLE messages ADD COLUMN is_read BOOLEAN NOT NULL DEFAULT false;

-- Make image_filename nullable (documents may not have stego image)
ALTER TABLE messages ALTER COLUMN image_filename DROP NOT NULL;

-- Make sender_lat, sender_lon nullable (non-geo-locked messages)
ALTER TABLE messages ALTER COLUMN sender_lat DROP NOT NULL;
ALTER TABLE messages ALTER COLUMN sender_lon DROP NOT NULL;

-- User online status
ALTER TABLE users ADD COLUMN last_seen TIMESTAMPTZ;

-- Index for expiry scheduler
CREATE INDEX idx_messages_expires_at ON messages(expires_at) WHERE expired = false;

-- Index for unread count
CREATE INDEX idx_messages_unread ON messages(receiver_id) WHERE is_read = false;
```

- [ ] **Step 2: Update Message.java entity**

Replace the entire contents of `backend/src/main/java/com/securechat/model/Message.java`:

```java
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
```

- [ ] **Step 3: Update User.java entity**

In `backend/src/main/java/com/securechat/model/User.java`, add the `lastSeen` field after the `createdAt` field:

```java
    @Column(name = "last_seen")
    private Instant lastSeen;
```

- [ ] **Step 4: Update MessageRepository with new queries**

Replace `backend/src/main/java/com/securechat/repository/MessageRepository.java`:

```java
package com.securechat.repository;

import com.securechat.model.Message;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface MessageRepository extends JpaRepository<Message, UUID> {
    @Query("SELECT m FROM Message m JOIN FETCH m.sender WHERE m.receiver.id = :receiverId ORDER BY m.createdAt DESC")
    List<Message> findByReceiverIdOrderByCreatedAtDesc(UUID receiverId);

    @Query("SELECT COUNT(m) FROM Message m WHERE m.receiver.id = :receiverId AND m.isRead = false AND m.expired = false")
    long countUnreadByReceiverId(UUID receiverId);

    @Query("SELECT m FROM Message m WHERE m.expiresAt < :now AND m.expired = false")
    List<Message> findExpiredMessages(Instant now);

    @Modifying
    @Query("UPDATE Message m SET m.keyShard = null, m.expired = true WHERE m.id = :id")
    void expireMessage(UUID id);
}
```

- [ ] **Step 5: Verify compilation**

```bash
cd backend && mvn clean compile -q
```

Expected: BUILD SUCCESS.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/resources/db/migration/V2__add_expiry_documents_features.sql backend/src/main/java/com/securechat/model/Message.java backend/src/main/java/com/securechat/model/User.java backend/src/main/java/com/securechat/repository/MessageRepository.java
git commit -m "add database schema for message expiry, documents, burn-after-read and online status"
```

---

### Task 3: Temporal Decay — KeyShardService + MessageExpiryScheduler

**Files:**
- Create: `backend/src/main/java/com/securechat/service/KeyShardService.java`
- Create: `backend/src/main/java/com/securechat/scheduler/MessageExpiryScheduler.java`
- Modify: `backend/src/main/java/com/securechat/SecureChatApplication.java`

- [ ] **Step 1: Create KeyShardService**

Create `backend/src/main/java/com/securechat/service/KeyShardService.java`:

```java
package com.securechat.service;

import org.springframework.stereotype.Service;
import java.util.Base64;

@Service
public class KeyShardService {

    /**
     * Split a Base64-encoded AES key into two shards.
     * shard1 = first half (returned to client, RSA-encrypted by client)
     * shard2 = second half (stored on server)
     */
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

    /**
     * Reconstruct AES key from two Base64-encoded shards.
     */
    public String reconstruct(String shard1Base64, String shard2Base64) {
        byte[] s1 = Base64.getDecoder().decode(shard1Base64);
        byte[] s2 = Base64.getDecoder().decode(shard2Base64);
        byte[] full = new byte[32];
        System.arraycopy(s1, 0, full, 0, 16);
        System.arraycopy(s2, 0, full, 16, 16);
        return Base64.getEncoder().encodeToString(full);
    }
}
```

- [ ] **Step 2: Create MessageExpiryScheduler**

Create `backend/src/main/java/com/securechat/scheduler/MessageExpiryScheduler.java`:

```java
package com.securechat.scheduler;

import com.securechat.model.Message;
import com.securechat.repository.MessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;

@Component
public class MessageExpiryScheduler {

    private static final Logger log = LoggerFactory.getLogger(MessageExpiryScheduler.class);

    private final MessageRepository messageRepository;
    private final Path imageStoragePath;

    public MessageExpiryScheduler(MessageRepository messageRepository,
                                   @Value("${app.image.storage-path}") String storagePath) {
        this.messageRepository = messageRepository;
        this.imageStoragePath = Paths.get(storagePath);
    }

    @Scheduled(fixedRate = 120_000) // every 2 minutes
    @Transactional
    public void purgeExpiredMessages() {
        List<Message> expired = messageRepository.findExpiredMessages(Instant.now());
        if (expired.isEmpty()) return;

        log.info("Purging {} expired messages", expired.size());
        for (Message msg : expired) {
            // Delete stego image from disk
            if (msg.getImageFilename() != null) {
                try {
                    Files.deleteIfExists(imageStoragePath.resolve(msg.getImageFilename()));
                } catch (Exception e) {
                    log.warn("Failed to delete image {}: {}", msg.getImageFilename(), e.getMessage());
                }
            }
            // Delete encrypted document from disk
            if (msg.getIsDocument() && msg.getFileName() != null) {
                try {
                    String encFileName = msg.getId() + ".enc";
                    Files.deleteIfExists(imageStoragePath.resolve(encFileName));
                } catch (Exception e) {
                    log.warn("Failed to delete document for {}: {}", msg.getId(), e.getMessage());
                }
            }
            // Null out key shard and mark expired
            messageRepository.expireMessage(msg.getId());
        }
    }
}
```

- [ ] **Step 3: Enable scheduling in SecureChatApplication**

In `backend/src/main/java/com/securechat/SecureChatApplication.java`, add `@EnableScheduling`:

```java
package com.securechat;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class SecureChatApplication {
    public static void main(String[] args) {
        SpringApplication.run(SecureChatApplication.class, args);
    }
}
```

- [ ] **Step 4: Verify compilation**

```bash
cd backend && mvn clean compile -q
```

Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/securechat/service/KeyShardService.java backend/src/main/java/com/securechat/scheduler/MessageExpiryScheduler.java backend/src/main/java/com/securechat/SecureChatApplication.java
git commit -m "add key shard splitting and automatic message expiry after 30 minutes"
```

---

### Task 4: Update MessageService — Key Sharding, Documents, Burn-After-Read

**Files:**
- Modify: `backend/src/main/java/com/securechat/service/MessageService.java`

- [ ] **Step 1: Rewrite MessageService**

Replace `backend/src/main/java/com/securechat/service/MessageService.java`:

```java
package com.securechat.service;

import com.securechat.config.NotFoundException;
import com.securechat.model.Message;
import com.securechat.model.User;
import com.securechat.repository.MessageRepository;
import com.securechat.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

@Service
public class MessageService {

    private final MessageRepository messageRepository;
    private final UserRepository userRepository;
    private final SteganographyService steganographyService;
    private final GeoLocationService geoLocationService;
    private final Path imageStoragePath;
    private final int expiryMinutes;

    public MessageService(MessageRepository messageRepository,
                          UserRepository userRepository,
                          SteganographyService steganographyService,
                          GeoLocationService geoLocationService,
                          @Value("${app.image.storage-path}") String storagePath,
                          @Value("${app.message.expiry-minutes:30}") int expiryMinutes) throws IOException {
        this.messageRepository = messageRepository;
        this.userRepository = userRepository;
        this.steganographyService = steganographyService;
        this.geoLocationService = geoLocationService;
        this.imageStoragePath = Paths.get(storagePath);
        this.expiryMinutes = expiryMinutes;
        Files.createDirectories(this.imageStoragePath);
    }

    public Message sendMessage(String senderUsername, String receiverUsername,
                                MultipartFile imageFile, String encryptedPayloadBase64,
                                String encryptedKey, String keyShard,
                                Double lat, Double lon, int radius,
                                boolean geoLocked, boolean burnAfterRead) throws Exception {
        if (senderUsername.equals(receiverUsername)) {
            throw new IllegalArgumentException("Cannot send a message to yourself");
        }

        User sender = userRepository.findByUsername(senderUsername)
                .orElseThrow(() -> new IllegalArgumentException("Sender not found"));
        User receiver = userRepository.findByUsername(receiverUsername)
                .orElseThrow(() -> new NotFoundException("Receiver not found"));

        if (geoLocked) {
            if (lat == null || lon == null) {
                throw new IllegalArgumentException("Geo-locked messages require coordinates");
            }
            if (!geoLocationService.isCoarseValid(lat, lon)) {
                throw new IllegalArgumentException("Invalid sender coordinates");
            }
        }

        String filename = null;
        if (imageFile != null && !imageFile.isEmpty()) {
            validateImageType(imageFile);
            byte[] payload = Base64.getDecoder().decode(encryptedPayloadBase64);
            if (payload.length == 0) {
                throw new IllegalArgumentException("encryptedPayload must not be empty");
            }
            BufferedImage image = readImage(imageFile.getInputStream());
            BufferedImage encodedImage = steganographyService.embed(image, payload);
            filename = UUID.randomUUID() + ".png";
            ImageIO.write(encodedImage, "PNG", imageStoragePath.resolve(filename).toFile());
        }

        Instant now = Instant.now();
        Message message = Message.builder()
                .sender(sender)
                .receiver(receiver)
                .imageFilename(filename)
                .encryptedKey(encryptedKey)
                .keyShard(keyShard)
                .senderLat(lat)
                .senderLon(lon)
                .radiusMeters(radius)
                .geoLocked(geoLocked)
                .burnAfterRead(burnAfterRead)
                .expiresAt(now.plusSeconds((long) expiryMinutes * 60))
                .build();
        return messageRepository.save(message);
    }

    public Message sendDocument(String senderUsername, String receiverUsername,
                                 MultipartFile file, String encryptedPayloadBase64,
                                 String encryptedKey, String keyShard,
                                 Double lat, Double lon, int radius,
                                 boolean geoLocked, boolean burnAfterRead) throws Exception {
        if (senderUsername.equals(receiverUsername)) {
            throw new IllegalArgumentException("Cannot send a message to yourself");
        }

        User sender = userRepository.findByUsername(senderUsername)
                .orElseThrow(() -> new IllegalArgumentException("Sender not found"));
        User receiver = userRepository.findByUsername(receiverUsername)
                .orElseThrow(() -> new NotFoundException("Receiver not found"));

        if (geoLocked && lat != null && lon != null) {
            if (!geoLocationService.isCoarseValid(lat, lon)) {
                throw new IllegalArgumentException("Invalid sender coordinates");
            }
        }

        // Store encrypted document blob
        byte[] encryptedData = Base64.getDecoder().decode(encryptedPayloadBase64);
        UUID msgId = UUID.randomUUID();
        String encFileName = msgId + ".enc";
        Files.write(imageStoragePath.resolve(encFileName), encryptedData);

        Instant now = Instant.now();
        Message message = Message.builder()
                .sender(sender)
                .receiver(receiver)
                .encryptedKey(encryptedKey)
                .keyShard(keyShard)
                .senderLat(lat)
                .senderLon(lon)
                .radiusMeters(radius)
                .geoLocked(geoLocked)
                .burnAfterRead(burnAfterRead)
                .isDocument(true)
                .fileName(file.getOriginalFilename())
                .fileType(file.getContentType())
                .fileSize(encryptedData.length > 0 ? (long) encryptedData.length : file.getSize())
                .expiresAt(now.plusSeconds((long) expiryMinutes * 60))
                .build();
        return messageRepository.save(message);
    }

    public List<Message> getInbox(String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        return messageRepository.findByReceiverIdOrderByCreatedAtDesc(user.getId());
    }

    public long getUnreadCount(String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        return messageRepository.countUnreadByReceiverId(user.getId());
    }

    public Message getMessage(UUID id, String username) {
        Message message = messageRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Message not found"));
        if (!message.getReceiver().getUsername().equals(username)) {
            throw new SecurityException("Access denied");
        }
        return message;
    }

    @Transactional
    public void markAsRead(UUID id, String username) {
        Message message = getMessage(id, username);
        if (!message.getIsRead()) {
            message.setIsRead(true);
            messageRepository.save(message);
        }
    }

    @Transactional
    public void burnMessage(UUID id) {
        Message message = messageRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Message not found"));
        // Delete files
        if (message.getImageFilename() != null) {
            try {
                Files.deleteIfExists(imageStoragePath.resolve(message.getImageFilename()));
            } catch (Exception ignored) {}
        }
        if (message.getIsDocument()) {
            try {
                Files.deleteIfExists(imageStoragePath.resolve(message.getId() + ".enc"));
            } catch (Exception ignored) {}
        }
        messageRepository.expireMessage(id);
    }

    public Path getImagePath(UUID id, String username) {
        Message message = getMessage(id, username);
        if (message.getImageFilename() == null) {
            throw new NotFoundException("No image for this message");
        }
        Path imagePath = imageStoragePath.resolve(message.getImageFilename());
        if (!Files.exists(imagePath)) {
            throw new NotFoundException("Image file not found");
        }
        return imagePath;
    }

    public Path getDocumentPath(UUID id, String username) {
        Message message = getMessage(id, username);
        if (!message.getIsDocument()) {
            throw new IllegalArgumentException("Message is not a document");
        }
        Path docPath = imageStoragePath.resolve(message.getId() + ".enc");
        if (!Files.exists(docPath)) {
            throw new NotFoundException("Document file not found");
        }
        return docPath;
    }

    private void validateImageType(MultipartFile file) throws IOException {
        byte[] header = new byte[8];
        try (InputStream is = file.getInputStream()) {
            is.read(header);
        }
        boolean isPng = header[0] == (byte)0x89 && header[1] == 0x50 && header[2] == 0x4E && header[3] == 0x47;
        boolean isBmp = header[0] == 0x42 && header[1] == 0x4D;
        if (!isPng && !isBmp) {
            throw new IllegalArgumentException("Only PNG and BMP images are accepted");
        }
    }

    private BufferedImage readImage(InputStream is) throws IOException {
        BufferedImage img = ImageIO.read(is);
        if (img == null) throw new IllegalArgumentException("Cannot read image");
        return img;
    }
}
```

- [ ] **Step 2: Verify compilation**

```bash
cd backend && mvn clean compile -q
```

Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/com/securechat/service/MessageService.java
git commit -m "update message service with key sharding, document support and burn-after-read"
```

---

### Task 5: Update Controllers — Messages, Location, Security

**Files:**
- Modify: `backend/src/main/java/com/securechat/controller/MessageController.java`
- Modify: `backend/src/main/java/com/securechat/controller/LocationController.java`
- Modify: `backend/src/main/java/com/securechat/security/JwtFilter.java`
- Modify: `backend/src/main/java/com/securechat/config/SecurityConfig.java`
- Modify: `backend/src/main/java/com/securechat/service/GeoLocationService.java`

- [ ] **Step 1: Rewrite MessageController**

Replace `backend/src/main/java/com/securechat/controller/MessageController.java`:

```java
package com.securechat.controller;

import com.securechat.model.Message;
import com.securechat.service.MessageService;
import org.springframework.core.io.PathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/messages")
public class MessageController {

    private final MessageService messageService;

    public MessageController(MessageService messageService) {
        this.messageService = messageService;
    }

    @PostMapping("/send")
    public ResponseEntity<?> send(
            @AuthenticationPrincipal UserDetails user,
            @RequestParam(value = "image", required = false) MultipartFile image,
            @RequestParam("encryptedPayload") String encryptedPayload,
            @RequestParam("encryptedKey") String encryptedKey,
            @RequestParam("keyShard") String keyShard,
            @RequestParam("receiverUsername") String receiverUsername,
            @RequestParam(value = "lat", required = false) Double lat,
            @RequestParam(value = "lon", required = false) Double lon,
            @RequestParam(value = "radius", defaultValue = "100000") int radius,
            @RequestParam(value = "geoLocked", defaultValue = "true") boolean geoLocked,
            @RequestParam(value = "burnAfterRead", defaultValue = "false") boolean burnAfterRead,
            @RequestParam(value = "file", required = false) MultipartFile file) throws Exception {

        if (radius <= 0 || radius > 10_000_000) {
            throw new IllegalArgumentException("radius must be between 1 and 10000000 meters");
        }
        if (encryptedKey.length() > 4096) {
            throw new IllegalArgumentException("encryptedKey exceeds maximum allowed length");
        }

        Message msg;
        if (file != null && !file.isEmpty()) {
            msg = messageService.sendDocument(user.getUsername(), receiverUsername,
                    file, encryptedPayload, encryptedKey, keyShard,
                    lat, lon, radius, geoLocked, burnAfterRead);
        } else {
            msg = messageService.sendMessage(user.getUsername(), receiverUsername,
                    image, encryptedPayload, encryptedKey, keyShard,
                    lat, lon, radius, geoLocked, burnAfterRead);
        }
        return ResponseEntity.ok(Map.of("messageId", msg.getId()));
    }

    @GetMapping("/inbox")
    public ResponseEntity<?> inbox(@AuthenticationPrincipal UserDetails user) {
        long unreadCount = messageService.getUnreadCount(user.getUsername());
        var messages = messageService.getInbox(user.getUsername())
                .stream()
                .map(m -> {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("id", m.getId());
                    map.put("senderUsername", m.getSender().getUsername());
                    map.put("createdAt", m.getCreatedAt());
                    map.put("expired", m.getExpired());
                    map.put("isRead", m.getIsRead());
                    map.put("isDocument", m.getIsDocument());
                    map.put("fileName", m.getFileName());
                    map.put("burnAfterRead", m.getBurnAfterRead());
                    map.put("expiresAt", m.getExpiresAt());
                    map.put("geoLocked", m.getGeoLocked());
                    return map;
                })
                .toList();
        return ResponseEntity.ok(Map.of("unreadCount", unreadCount, "messages", messages));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getMessage(
            @AuthenticationPrincipal UserDetails user,
            @PathVariable UUID id) {
        Message msg = messageService.getMessage(id, user.getUsername());
        messageService.markAsRead(id, user.getUsername());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("encryptedKey", msg.getEncryptedKey());
        response.put("senderLat", msg.getSenderLat());
        response.put("senderLon", msg.getSenderLon());
        response.put("radiusMeters", msg.getRadiusMeters());
        response.put("geoLocked", msg.getGeoLocked());
        response.put("expired", msg.getExpired());
        response.put("expiresAt", msg.getExpiresAt());
        response.put("burnAfterRead", msg.getBurnAfterRead());
        response.put("isDocument", msg.getIsDocument());
        response.put("fileName", msg.getFileName());

        // Only return keyShard if NOT geo-locked (geo-locked messages get shard via /location/verify)
        if (!msg.getGeoLocked() && msg.getKeyShard() != null) {
            response.put("keyShard", msg.getKeyShard());
            // Burn after read: destroy after returning shard
            if (msg.getBurnAfterRead()) {
                messageService.burnMessage(id);
            }
        }
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}/image")
    public ResponseEntity<Resource> getImage(
            @AuthenticationPrincipal UserDetails user,
            @PathVariable UUID id) throws Exception {
        Path imagePath = messageService.getImagePath(id, user.getUsername());
        Resource resource = new PathResource(imagePath);
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .body(resource);
    }

    @GetMapping("/{id}/file")
    public ResponseEntity<Resource> getFile(
            @AuthenticationPrincipal UserDetails user,
            @PathVariable UUID id) throws Exception {
        Message msg = messageService.getMessage(id, user.getUsername());
        Path docPath = messageService.getDocumentPath(id, user.getUsername());
        Resource resource = new PathResource(docPath);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + msg.getFileName() + ".enc\"")
                .body(resource);
    }
}
```

- [ ] **Step 2: Rewrite LocationController to return keyShard and distance**

Replace `backend/src/main/java/com/securechat/controller/LocationController.java`:

```java
package com.securechat.controller;

import com.securechat.model.Message;
import com.securechat.service.GeoLocationService;
import com.securechat.service.MessageService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/location")
public class LocationController {

    private static final Logger log = LoggerFactory.getLogger(LocationController.class);

    private final GeoLocationService geoLocationService;
    private final MessageService messageService;

    public LocationController(GeoLocationService geoLocationService, MessageService messageService) {
        this.geoLocationService = geoLocationService;
        this.messageService = messageService;
    }

    record VerifyRequest(
        @NotNull(message = "lat is required") Double lat,
        @NotNull(message = "lon is required") Double lon,
        @NotBlank(message = "messageId is required") String messageId
    ) {}

    @PostMapping("/verify")
    public ResponseEntity<?> verify(
            @AuthenticationPrincipal UserDetails user,
            @Valid @RequestBody VerifyRequest body) {

        if (!geoLocationService.isCoarseValid(body.lat(), body.lon())) {
            return ResponseEntity.ok(Map.of("valid", false, "reason", "Invalid coordinates"));
        }

        UUID messageId;
        try {
            messageId = UUID.fromString(body.messageId());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid messageId format"));
        }

        Message message = messageService.getMessage(messageId, user.getUsername());

        if (message.getExpired()) {
            return ResponseEntity.ok(Map.of("valid", false, "reason", "Message has expired"));
        }

        double distance = geoLocationService.getDistance(
                message.getSenderLat(), message.getSenderLon(),
                body.lat(), body.lon());
        boolean valid = distance <= message.getRadiusMeters();

        log.info("LOCATION VERIFY | user={} | locked=({}, {}) | receiver=({}, {}) | radius={}m | distance={}m | valid={}",
                user.getUsername(),
                message.getSenderLat(), message.getSenderLon(),
                body.lat(), body.lon(),
                message.getRadiusMeters(), Math.round(distance), valid);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("valid", valid);
        response.put("distance", Math.round(distance));
        response.put("radius", message.getRadiusMeters());

        if (valid && message.getKeyShard() != null) {
            response.put("keyShard", message.getKeyShard());
            // Burn after read
            if (message.getBurnAfterRead()) {
                messageService.burnMessage(message.getId());
            }
        } else if (!valid) {
            response.put("message", String.format("You are %d m away, need to be within %d m",
                    Math.round(distance), message.getRadiusMeters()));
        }

        return ResponseEntity.ok(response);
    }
}
```

- [ ] **Step 3: Update GeoLocationService to return distance**

Replace `backend/src/main/java/com/securechat/service/GeoLocationService.java`:

```java
package com.securechat.service;

import com.securechat.util.HaversineCalculator;
import org.springframework.stereotype.Service;

@Service
public class GeoLocationService {

    public boolean isCoarseValid(double lat, double lon) {
        if (lat == 0.0 && lon == 0.0) return false;
        return lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180;
    }

    public double getDistance(double lat1, double lon1, double lat2, double lon2) {
        return HaversineCalculator.distanceMeters(lat1, lon1, lat2, lon2);
    }

    public boolean isWithinRadius(double senderLat, double senderLon,
                                   double receiverLat, double receiverLon,
                                   int radiusMeters) {
        if (!isCoarseValid(receiverLat, receiverLon)) return false;
        return getDistance(senderLat, senderLon, receiverLat, receiverLon) <= radiusMeters;
    }
}
```

- [ ] **Step 4: Update JwtFilter to track lastSeen**

Replace `backend/src/main/java/com/securechat/security/JwtFilter.java`:

```java
package com.securechat.security;

import com.securechat.model.User;
import com.securechat.repository.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.Arrays;

@Component
public class JwtFilter extends OncePerRequestFilter {

    private final JwtProvider jwtProvider;
    private final UserDetailsService userDetailsService;
    private final UserRepository userRepository;

    public JwtFilter(JwtProvider jwtProvider, UserDetailsService userDetailsService,
                     UserRepository userRepository) {
        this.jwtProvider = jwtProvider;
        this.userDetailsService = userDetailsService;
        this.userRepository = userRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        String token = extractTokenFromCookie(request);
        if (token != null && jwtProvider.validateToken(token)) {
            String username = jwtProvider.getUsernameFromToken(token);
            var userDetails = userDetailsService.loadUserByUsername(username);
            var auth = new UsernamePasswordAuthenticationToken(
                    userDetails, null, userDetails.getAuthorities());
            SecurityContextHolder.getContext().setAuthentication(auth);

            // Update last seen (async-safe: fire and forget)
            userRepository.findByUsername(username).ifPresent(u -> {
                u.setLastSeen(Instant.now());
                userRepository.save(u);
            });
        }
        filterChain.doFilter(request, response);
    }

    private String extractTokenFromCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        return Arrays.stream(cookies)
                .filter(c -> "jwt".equals(c.getName()))
                .map(Cookie::getValue)
                .findFirst()
                .orElse(null);
    }
}
```

- [ ] **Step 5: Update SecurityConfig to permit new endpoints**

In `backend/src/main/java/com/securechat/config/SecurityConfig.java`, update the `requestMatchers` to also permit fonts and CDN resources needed by frontend:

Replace the `authorizeHttpRequests` section:

```java
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login").permitAll()
                .requestMatchers("/", "/error", "/index.html", "/app.js", "/style.css", "/favicon.ico").permitAll()
                .anyRequest().authenticated()
            )
```

- [ ] **Step 6: Verify compilation**

```bash
cd backend && mvn clean compile -q
```

Expected: BUILD SUCCESS.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/securechat/controller/MessageController.java backend/src/main/java/com/securechat/controller/LocationController.java backend/src/main/java/com/securechat/service/GeoLocationService.java backend/src/main/java/com/securechat/security/JwtFilter.java backend/src/main/java/com/securechat/config/SecurityConfig.java
git commit -m "update controllers for geo-fence fix, key shard return and online status tracking"
```

---

### Task 6: Add User Online Status to Auth Endpoints

**Files:**
- Modify: `backend/src/main/java/com/securechat/controller/AuthController.java` (already partially modified in Task 1)

- [ ] **Step 1: Update UserController to return lastSeen**

In `backend/src/main/java/com/securechat/controller/UserController.java`, replace the `getPublicKey` method:

```java
package com.securechat.controller;

import com.securechat.repository.UserRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserRepository userRepository;

    public UserController(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @GetMapping("/{username}")
    public ResponseEntity<?> getPublicKey(@PathVariable String username) {
        return userRepository.findByUsername(username)
                .map(u -> {
                    Map<String, Object> response = new LinkedHashMap<>();
                    response.put("publicKey", u.getPublicKey());
                    response.put("lastSeen", u.getLastSeen());
                    return ResponseEntity.ok((Object) response);
                })
                .orElse(ResponseEntity.notFound().build());
    }
}
```

- [ ] **Step 2: Verify compilation**

```bash
cd backend && mvn clean compile -q
```

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/com/securechat/controller/
git commit -m "add online status to user lookup endpoint"
```

---

### Task 7: Frontend — Complete UI Rewrite (Hyprland Theme)

**Files:**
- Rewrite: `frontend/index.html`
- Rewrite: `frontend/style.css`
- Rewrite: `frontend/app.js`

This is the largest task. All three frontend files are completely rewritten.

- [ ] **Step 1: Write the new index.html**

Replace `frontend/index.html`:

```html
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>securechat</title>
    <link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css"/>
    <link href="https://fonts.googleapis.com/css2?family=JetBrains+Mono:wght@300;400;500;600;700&display=swap" rel="stylesheet">
    <link rel="stylesheet" href="style.css">
</head>
<body>
    <div class="scanline"></div>

    <div id="app" x-data="secureChat()" x-init="init()">

        <!-- status bar (top) -->
        <header class="statusbar" x-show="auth.loggedIn">
            <div class="statusbar-left">
                <span class="tag tag-cyan">securechat</span>
                <span class="tag tag-magenta" x-text="auth.username"></span>
            </div>
            <nav class="statusbar-center">
                <a href="#" @click.prevent="navigate('inbox')" :class="{ active: view === 'inbox' }">
                    [ inbox<span x-show="unreadCount > 0" class="badge" x-text="unreadCount"></span> ]
                </a>
                <a href="#" @click.prevent="navigate('send')" :class="{ active: view === 'send' }">[ send ]</a>
                <a href="#" @click.prevent="logout()" class="link-red">[ logout ]</a>
            </nav>
            <div class="statusbar-right">
                <span class="tag tag-dim" x-text="currentTime"></span>
            </div>
        </header>

        <!-- login -->
        <div x-show="view === 'login'" class="window" x-transition>
            <div class="window-title">[ login ]</div>
            <div class="window-body">
                <div class="field">
                    <label>username</label>
                    <input x-model="form.username" type="text" placeholder="enter username" @keydown.enter="login()">
                </div>
                <div class="field">
                    <label>password</label>
                    <input x-model="form.password" type="password" placeholder="enter password" @keydown.enter="login()">
                </div>
                <button @click="login()" class="btn btn-cyan" :disabled="loading">
                    <span x-text="loading ? 'deriving keys...' : '> login'"></span>
                </button>
                <p class="link-text">no account? <a href="#" @click.prevent="navigate('register')">register</a></p>
                <p class="error" x-show="error" x-text="error"></p>
            </div>
        </div>

        <!-- register -->
        <div x-show="view === 'register'" class="window" x-transition>
            <div class="window-title">[ register ]</div>
            <div class="window-body">
                <div class="field">
                    <label>username</label>
                    <input x-model="form.username" type="text" placeholder="3-50 chars, alphanumeric">
                </div>
                <div class="field">
                    <label>password</label>
                    <input x-model="form.password" type="password" placeholder="min 8 characters">
                </div>
                <button @click="register()" class="btn btn-magenta" :disabled="loading">
                    <span x-text="loading ? 'generating rsa keypair...' : '> register'"></span>
                </button>
                <p class="link-text">have an account? <a href="#" @click.prevent="navigate('login')">login</a></p>
                <p class="error" x-show="error" x-text="error"></p>
                <p class="success" x-show="info" x-text="info"></p>
            </div>
        </div>

        <!-- inbox -->
        <div x-show="view === 'inbox'" class="window window-wide" x-transition>
            <div class="window-title">[ inbox ] <span class="dim" x-show="messages.length > 0" x-text="messages.length + ' messages'"></span></div>
            <div class="window-body">
                <div x-show="messages.length === 0" class="empty">no messages yet. waiting for incoming transmissions...</div>
                <template x-for="msg in messages" :key="msg.id">
                    <div class="msg-item" @click="openMessage(msg)" :class="{ 'msg-expired': msg.expired, 'msg-unread': !msg.isRead && !msg.expired }">
                        <div class="msg-item-left">
                            <span class="msg-sender" x-text="msg.senderUsername"></span>
                            <span class="msg-meta">
                                <span x-show="msg.isDocument" class="tag tag-small tag-orange">doc</span>
                                <span x-show="msg.burnAfterRead" class="tag tag-small tag-red">burn</span>
                                <span x-show="msg.geoLocked" class="tag tag-small tag-cyan">geo</span>
                                <span x-show="msg.expired" class="tag tag-small tag-dim">expired</span>
                            </span>
                        </div>
                        <div class="msg-item-right">
                            <span class="msg-time" x-text="formatTime(msg.createdAt)"></span>
                            <span class="msg-countdown" x-show="!msg.expired" x-text="getCountdown(msg.expiresAt)"></span>
                        </div>
                    </div>
                </template>
            </div>
        </div>

        <!-- send message -->
        <div x-show="view === 'send'" class="window window-wide" x-transition>
            <div class="window-title">[ send encrypted message ]</div>
            <div class="window-body">
                <div class="send-grid">
                    <div class="send-form">
                        <div class="field">
                            <label>receiver</label>
                            <input x-model="sendForm.receiver" type="text" placeholder="username">
                        </div>

                        <!-- toggle: message or document -->
                        <div class="toggle-row">
                            <button @click="sendMode = 'message'" :class="sendMode === 'message' ? 'btn btn-cyan btn-sm' : 'btn btn-dim btn-sm'">message</button>
                            <button @click="sendMode = 'document'" :class="sendMode === 'document' ? 'btn btn-orange btn-sm' : 'btn btn-dim btn-sm'">document</button>
                        </div>

                        <div x-show="sendMode === 'message'" class="field">
                            <label>message</label>
                            <textarea x-model="sendForm.message" placeholder="your secret message..."></textarea>
                        </div>

                        <div x-show="sendMode === 'document'" class="field">
                            <label>file (any type, max 10mb)</label>
                            <input type="file" @change="handleFileUpload($event)" class="file-input">
                            <span class="dim" x-show="sendForm.fileName" x-text="sendForm.fileName"></span>
                        </div>

                        <!-- options -->
                        <div class="options-row">
                            <label class="toggle-label">
                                <input type="checkbox" x-model="sendForm.geoLocked">
                                <span>geo-lock</span>
                            </label>
                            <label class="toggle-label">
                                <input type="checkbox" x-model="sendForm.burnAfterRead">
                                <span>burn after read</span>
                            </label>
                        </div>

                        <div x-show="sendForm.geoLocked" class="geo-info">
                            <p class="dim" x-show="!sendForm.lat">click map to set geo-lock location</p>
                            <p x-show="sendForm.lat" class="success">
                                locked: <span x-text="sendForm.lat?.toFixed(4) + ', ' + sendForm.lon?.toFixed(4)"></span>
                            </p>
                        </div>

                        <button @click="sendMessage()" class="btn btn-green" :disabled="sending">
                            <span x-text="sending ? 'encrypting...' : '> transmit'"></span>
                        </button>
                        <p class="error" x-show="error" x-text="error"></p>
                        <p class="success" x-show="info" x-text="info"></p>
                    </div>

                    <div class="send-map" x-show="sendForm.geoLocked">
                        <div id="map"></div>
                    </div>
                </div>
            </div>
        </div>

        <!-- view message -->
        <div x-show="view === 'view'" class="window window-wide" x-transition>
            <div class="window-title">[ decrypt message ]
                <span class="dim" x-show="currentMessage">from: <span x-text="currentMessage?.senderUsername"></span></span>
            </div>
            <div class="window-body">
                <div x-show="currentMessage?.expired" class="expired-notice">
                    [EXPIRED] this message has self-destructed. key shard deleted.
                </div>

                <div x-show="!currentMessage?.expired && !decrypted">
                    <div x-show="currentMessage?.geoLocked" class="geo-notice">
                        this message is geo-locked. your location will be verified.
                    </div>
                    <button @click="decryptMessage()" class="btn btn-cyan" :disabled="decrypting">
                        <span x-text="decrypting ? 'verifying location + decrypting...' : '> unlock + decrypt'"></span>
                    </button>
                </div>

                <!-- decrypted text message -->
                <div x-show="decrypted && !currentMessage?.isDocument" class="decrypted-box">
                    <div class="decrypted-header">
                        <span class="tag tag-green">decrypted</span>
                        <span class="dim" x-show="currentMessage?.burnAfterRead">message will be destroyed after closing</span>
                    </div>
                    <div class="decrypted-content no-select" x-text="decrypted"></div>
                    <div class="watermark" x-text="auth.username"></div>
                </div>

                <!-- decrypted document -->
                <div x-show="decrypted && currentMessage?.isDocument" class="decrypted-box">
                    <div class="decrypted-header">
                        <span class="tag tag-orange">document decrypted</span>
                    </div>
                    <button @click="downloadDecryptedFile()" class="btn btn-orange">
                        > download <span x-text="currentMessage?.fileName"></span>
                    </button>
                </div>

                <p class="error" x-show="error" x-text="error"></p>
                <a href="#" @click.prevent="navigate('inbox')" class="back-link">&lt;-- back to inbox</a>
            </div>
        </div>

        <!-- bottom status bar -->
        <footer class="statusbar-bottom" x-show="auth.loggedIn">
            <span class="dim">view: <span x-text="view"></span></span>
            <span class="dim">encryption: aes-256-gcm + rsa-2048</span>
            <span class="dim">ttl: 30min</span>
        </footer>

    </div>

    <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
    <script src="https://cdn.jsdelivr.net/npm/node-forge@1.3.1/dist/forge.min.js"></script>
    <script defer src="https://cdn.jsdelivr.net/npm/alpinejs@3.x.x/dist/cdn.min.js"></script>
    <script src="app.js"></script>
</body>
</html>
```

- [ ] **Step 2: Write the new style.css (Hyprland theme)**

Replace `frontend/style.css` with the complete dark theme CSS. This is a large file — see the full content below:

```css
/* === RESET === */
*, *::before, *::after { box-sizing: border-box; margin: 0; padding: 0; }

/* === VARIABLES === */
:root {
    --bg: #1a1b26;
    --bg-surface: #24283b;
    --bg-hover: #292e42;
    --border: #414868;
    --text: #c0caf5;
    --text-dim: #565f89;
    --cyan: #7dcfff;
    --magenta: #bb9af7;
    --green: #9ece6a;
    --red: #f7768e;
    --orange: #ff9e64;
    --yellow: #e0af68;
    --font: 'JetBrains Mono', 'Fira Code', 'Cascadia Code', monospace;
}

/* === BASE === */
body {
    font-family: var(--font);
    background: var(--bg);
    color: var(--text);
    font-size: 14px;
    min-height: 100vh;
    line-height: 1.6;
    overflow-x: hidden;
}

/* === SCANLINE OVERLAY === */
.scanline {
    position: fixed;
    top: 0; left: 0; right: 0; bottom: 0;
    pointer-events: none;
    z-index: 9999;
    background: repeating-linear-gradient(
        0deg,
        transparent,
        transparent 2px,
        rgba(0, 0, 0, 0.03) 2px,
        rgba(0, 0, 0, 0.03) 4px
    );
}

/* === STATUS BAR (TOP) === */
.statusbar {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 8px 16px;
    background: var(--bg-surface);
    border-bottom: 1px solid var(--border);
    position: sticky;
    top: 0;
    z-index: 100;
}

.statusbar-left, .statusbar-right { display: flex; gap: 8px; align-items: center; }

.statusbar-center {
    display: flex;
    gap: 4px;
    align-items: center;
}

.statusbar-center a {
    color: var(--text-dim);
    text-decoration: none;
    padding: 4px 8px;
    transition: color 0.15s;
    font-size: 13px;
}

.statusbar-center a:hover,
.statusbar-center a.active { color: var(--cyan); }

.statusbar-center .link-red:hover { color: var(--red); }

.statusbar-bottom {
    position: fixed;
    bottom: 0;
    left: 0; right: 0;
    display: flex;
    justify-content: space-between;
    padding: 4px 16px;
    background: var(--bg-surface);
    border-top: 1px solid var(--border);
    font-size: 12px;
    z-index: 100;
}

/* === TAGS === */
.tag {
    font-size: 12px;
    padding: 2px 8px;
    font-weight: 500;
}

.tag-cyan { color: var(--bg); background: var(--cyan); }
.tag-magenta { color: var(--bg); background: var(--magenta); }
.tag-green { color: var(--bg); background: var(--green); }
.tag-orange { color: var(--bg); background: var(--orange); }
.tag-red { color: var(--bg); background: var(--red); }
.tag-dim { color: var(--text-dim); border: 1px solid var(--border); }
.tag-small { font-size: 10px; padding: 1px 4px; }

.badge {
    background: var(--red);
    color: var(--bg);
    font-size: 10px;
    padding: 0 5px;
    margin-left: 4px;
    font-weight: 700;
}

/* === WINDOW (main container) === */
.window {
    max-width: 480px;
    margin: 40px auto;
    border: 1px solid var(--border);
    background: var(--bg-surface);
}

.window-wide { max-width: 800px; }

.window-title {
    padding: 8px 16px;
    border-bottom: 1px solid var(--border);
    font-size: 13px;
    color: var(--cyan);
    font-weight: 500;
    text-transform: lowercase;
}

.window-body {
    padding: 20px;
    display: flex;
    flex-direction: column;
    gap: 16px;
}

/* === FORM FIELDS === */
.field {
    display: flex;
    flex-direction: column;
    gap: 4px;
}

.field label {
    font-size: 12px;
    color: var(--text-dim);
    text-transform: lowercase;
}

input[type="text"],
input[type="password"],
textarea {
    background: var(--bg);
    border: 1px solid var(--border);
    padding: 10px 12px;
    color: var(--text);
    font-family: var(--font);
    font-size: 14px;
    width: 100%;
    outline: none;
    transition: border-color 0.15s;
}

input:focus, textarea:focus {
    border-color: var(--cyan);
    box-shadow: 0 0 0 1px var(--cyan);
}

textarea {
    min-height: 100px;
    resize: vertical;
}

.file-input {
    font-family: var(--font);
    font-size: 12px;
    color: var(--text-dim);
    padding: 8px 0;
}

.file-input::file-selector-button {
    background: var(--bg);
    color: var(--orange);
    border: 1px solid var(--orange);
    padding: 4px 12px;
    font-family: var(--font);
    font-size: 12px;
    cursor: pointer;
    margin-right: 12px;
}

/* === BUTTONS === */
.btn {
    background: transparent;
    border: 1px solid var(--border);
    color: var(--text);
    padding: 10px 20px;
    font-family: var(--font);
    font-size: 14px;
    cursor: pointer;
    text-transform: lowercase;
    font-weight: 500;
    transition: all 0.15s;
}

.btn:hover:not(:disabled) { background: var(--bg-hover); }
.btn:disabled { opacity: 0.4; cursor: not-allowed; }

.btn-cyan { border-color: var(--cyan); color: var(--cyan); }
.btn-cyan:hover:not(:disabled) { background: rgba(125, 207, 255, 0.1); }

.btn-magenta { border-color: var(--magenta); color: var(--magenta); }
.btn-magenta:hover:not(:disabled) { background: rgba(187, 154, 247, 0.1); }

.btn-green { border-color: var(--green); color: var(--green); }
.btn-green:hover:not(:disabled) { background: rgba(158, 206, 106, 0.1); }

.btn-orange { border-color: var(--orange); color: var(--orange); }
.btn-orange:hover:not(:disabled) { background: rgba(255, 158, 100, 0.1); }

.btn-red { border-color: var(--red); color: var(--red); }

.btn-dim { border-color: var(--border); color: var(--text-dim); }

.btn-sm { padding: 4px 12px; font-size: 12px; }

/* === TOGGLE ROW === */
.toggle-row {
    display: flex;
    gap: 8px;
}

.options-row {
    display: flex;
    gap: 16px;
    flex-wrap: wrap;
}

.toggle-label {
    display: flex;
    align-items: center;
    gap: 6px;
    font-size: 12px;
    color: var(--text-dim);
    cursor: pointer;
}

.toggle-label input[type="checkbox"] {
    appearance: none;
    width: 14px;
    height: 14px;
    border: 1px solid var(--border);
    background: var(--bg);
    cursor: pointer;
    position: relative;
}

.toggle-label input[type="checkbox"]:checked {
    border-color: var(--cyan);
    background: var(--cyan);
}

.toggle-label input[type="checkbox"]:checked::after {
    content: '';
    position: absolute;
    top: 1px; left: 4px;
    width: 4px; height: 8px;
    border: solid var(--bg);
    border-width: 0 2px 2px 0;
    transform: rotate(45deg);
}

/* === MESSAGE LIST === */
.msg-item {
    display: flex;
    justify-content: space-between;
    align-items: center;
    padding: 12px 16px;
    border: 1px solid var(--border);
    cursor: pointer;
    transition: all 0.15s;
    gap: 12px;
}

.msg-item:hover { border-color: var(--cyan); background: var(--bg-hover); }

.msg-unread { border-left: 3px solid var(--cyan); }
.msg-expired { opacity: 0.4; }
.msg-expired:hover { border-color: var(--border); cursor: default; }

.msg-item-left {
    display: flex;
    flex-direction: column;
    gap: 4px;
}

.msg-sender { font-weight: 500; }

.msg-meta {
    display: flex;
    gap: 4px;
}

.msg-item-right {
    display: flex;
    flex-direction: column;
    align-items: flex-end;
    gap: 2px;
}

.msg-time { font-size: 12px; color: var(--text-dim); }
.msg-countdown { font-size: 11px; color: var(--red); }

/* === SEND GRID === */
.send-grid {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 20px;
}

.send-form {
    display: flex;
    flex-direction: column;
    gap: 12px;
}

.send-map { min-height: 300px; }

#map {
    height: 100%;
    min-height: 300px;
    border: 1px solid var(--border);
}

.geo-info { font-size: 12px; }

/* === DECRYPTED MESSAGE === */
.decrypted-box {
    border: 1px solid var(--green);
    padding: 16px;
    position: relative;
    overflow: hidden;
}

.decrypted-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-bottom: 12px;
    font-size: 12px;
}

.decrypted-content {
    line-height: 1.8;
    white-space: pre-wrap;
    word-break: break-word;
}

.no-select {
    user-select: none;
    -webkit-user-select: none;
}

.watermark {
    position: absolute;
    bottom: 10px;
    right: 16px;
    font-size: 48px;
    font-weight: 700;
    color: rgba(192, 202, 245, 0.03);
    pointer-events: none;
    text-transform: uppercase;
}

.expired-notice {
    color: var(--red);
    border: 1px solid var(--red);
    padding: 12px;
    text-align: center;
    font-size: 13px;
}

.geo-notice {
    color: var(--cyan);
    border: 1px solid var(--cyan);
    padding: 8px 12px;
    font-size: 12px;
}

/* === MISC === */
.error { color: var(--red); font-size: 13px; }
.success { color: var(--green); font-size: 13px; }
.dim { color: var(--text-dim); }
.empty { color: var(--text-dim); text-align: center; padding: 40px 20px; font-size: 13px; }

.link-text { font-size: 13px; color: var(--text-dim); }
.link-text a { color: var(--cyan); text-decoration: none; }
.link-text a:hover { text-decoration: underline; }

.back-link { color: var(--cyan); text-decoration: none; font-size: 13px; }
.back-link:hover { text-decoration: underline; }

a { color: var(--cyan); text-decoration: none; }

/* === SCROLLBAR === */
::-webkit-scrollbar { width: 6px; }
::-webkit-scrollbar-track { background: var(--bg); }
::-webkit-scrollbar-thumb { background: var(--border); }
::-webkit-scrollbar-thumb:hover { background: var(--text-dim); }

/* === LEAFLET DARK === */
.leaflet-container { background: var(--bg) !important; }
.leaflet-control-zoom a {
    background: var(--bg-surface) !important;
    color: var(--text) !important;
    border-color: var(--border) !important;
}

/* === RESPONSIVE === */
@media (max-width: 700px) {
    .window-wide { margin: 10px; }
    .send-grid { grid-template-columns: 1fr; }
    .statusbar { flex-wrap: wrap; gap: 8px; }
    .statusbar-right { display: none; }
}

/* === TRANSITIONS === */
[x-transition] {
    transition: opacity 0.15s ease;
}
```

- [ ] **Step 3: Write the new app.js**

Replace `frontend/app.js` with the complete rewritten JavaScript. This handles: noise image generation, key sharding, document upload, geo-lock toggle, burn-after-read, countdown timers, copy protection, and the new UI flows.

```javascript
function secureChat() {
    return {
        view: 'login',
        auth: { loggedIn: false, username: null, privateKey: null },
        form: { username: '', password: '' },
        sendForm: {
            receiver: '', message: '', lat: null, lon: null,
            geoLocked: true, burnAfterRead: false,
            file: null, fileName: ''
        },
        sendMode: 'message',
        messages: [],
        unreadCount: 0,
        currentMessage: null,
        currentMessageId: null,
        decrypted: null,
        decryptedFileBlob: null,
        error: '',
        info: '',
        loading: false,
        sending: false,
        decrypting: false,
        map: null,
        currentTime: '',
        countdownInterval: null,

        async init() {
            this.updateClock();
            setInterval(() => this.updateClock(), 1000);
            // Refresh countdowns every second
            setInterval(() => this.$forceUpdate?.(), 1000);
            // Copy protection: re-hide message on tab switch
            document.addEventListener('visibilitychange', () => {
                if (document.hidden && this.decrypted && this.view === 'view') {
                    this.decrypted = null;
                    this.decryptedFileBlob = null;
                }
            });
            // Disable right-click on decrypted content
            document.addEventListener('contextmenu', (e) => {
                if (e.target.closest('.no-select')) e.preventDefault();
            });
        },

        updateClock() {
            const now = new Date();
            this.currentTime = now.toLocaleTimeString('en-GB', { hour12: false });
        },

        navigate(v) {
            this.error = '';
            this.info = '';
            this.decrypted = null;
            this.decryptedFileBlob = null;
            this.view = v;
            if (v === 'inbox') this.loadInbox();
            if (v === 'send') this.$nextTick(() => this.initMap());
        },

        // --- KEY DERIVATION ---
        async derivePrivateKey(username, password) {
            const salt = new TextEncoder().encode(username + ':securechat:v1');
            const keyMaterial = await crypto.subtle.importKey(
                'raw', new TextEncoder().encode(password), 'PBKDF2', false, ['deriveBits']
            );
            const bits = await crypto.subtle.deriveBits(
                { name: 'PBKDF2', salt, iterations: 310000, hash: 'SHA-256' },
                keyMaterial, 256
            );
            const seed = Array.from(new Uint8Array(bits)).map(b => String.fromCharCode(b)).join('');
            const prng = forge.random.createInstance();
            prng.seedFileSync = () => seed;
            return new Promise((resolve, reject) => {
                forge.pki.rsa.generateKeyPair({ bits: 2048, workers: -1, prng }, (err, keypair) => {
                    if (err) reject(err);
                    else resolve(keypair);
                });
            });
        },

        // --- REGISTER ---
        async register() {
            this.error = '';
            this.loading = true;
            this.info = 'generating rsa-2048 keypair...';
            try {
                const keypair = await this.derivePrivateKey(this.form.username, this.form.password);
                const publicKeyPem = forge.pki.publicKeyToPem(keypair.publicKey);
                const res = await fetch('/api/auth/register', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({
                        username: this.form.username,
                        password: this.form.password,
                        publicKey: publicKeyPem
                    })
                });
                const data = await res.json();
                if (!res.ok) { this.error = data.error; this.info = ''; this.loading = false; return; }
                this.info = 'registered. redirecting to login...';
                setTimeout(() => { this.navigate('login'); this.loading = false; }, 1500);
            } catch (e) {
                this.error = e.message;
                this.info = '';
                this.loading = false;
            }
        },

        // --- LOGIN ---
        async login() {
            this.error = '';
            this.loading = true;
            try {
                const keypair = await this.derivePrivateKey(this.form.username, this.form.password);
                const res = await fetch('/api/auth/login', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ username: this.form.username, password: this.form.password })
                });
                const data = await res.json();
                if (!res.ok) { this.error = data.error || 'invalid credentials'; this.loading = false; return; }
                this.auth = { loggedIn: true, username: data.username, privateKey: keypair.privateKey };
                this.loading = false;
                this.navigate('inbox');
            } catch (e) {
                this.error = e.message;
                this.loading = false;
            }
        },

        // --- LOGOUT ---
        async logout() {
            await fetch('/api/auth/logout', { method: 'POST' });
            this.auth = { loggedIn: false, username: null, privateKey: null };
            this.view = 'login';
            this.messages = [];
            this.unreadCount = 0;
        },

        // --- INBOX ---
        async loadInbox() {
            try {
                const res = await fetch('/api/messages/inbox');
                if (res.ok) {
                    const data = await res.json();
                    this.messages = data.messages || [];
                    this.unreadCount = data.unreadCount || 0;
                }
            } catch (e) { this.error = 'failed to load inbox'; }
        },

        // --- MAP ---
        initMap() {
            if (this.map) { this.map.remove(); this.map = null; }
            const mapEl = document.getElementById('map');
            if (!mapEl) return;
            this.map = L.map('map').setView([20.5937, 78.9629], 5);
            // Dark tile layer
            L.tileLayer('https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}{r}.png', {
                attribution: '&copy; CartoDB',
                maxZoom: 19
            }).addTo(this.map);
            let marker = null;
            this.map.on('click', (e) => {
                if (marker) marker.remove();
                marker = L.marker(e.latlng).addTo(this.map);
                this.sendForm.lat = e.latlng.lat;
                this.sendForm.lon = e.latlng.lng;
            });
        },

        handleFileUpload(event) {
            const file = event.target.files[0];
            if (file) {
                if (file.size > 10 * 1024 * 1024) {
                    this.error = 'file exceeds 10mb limit';
                    return;
                }
                this.sendForm.file = file;
                this.sendForm.fileName = file.name;
            }
        },

        // --- GENERATE NOISE IMAGE ---
        generateNoiseImage() {
            const canvas = document.createElement('canvas');
            canvas.width = 512;
            canvas.height = 512;
            const ctx = canvas.getContext('2d');
            const imageData = ctx.createImageData(512, 512);
            const data = imageData.data;
            for (let i = 0; i < data.length; i += 4) {
                data[i] = Math.floor(Math.random() * 256);
                data[i + 1] = Math.floor(Math.random() * 256);
                data[i + 2] = Math.floor(Math.random() * 256);
                data[i + 3] = 255;
            }
            ctx.putImageData(imageData, 0, 0);
            return new Promise(resolve => canvas.toBlob(resolve, 'image/png'));
        },

        // --- SEND MESSAGE ---
        async sendMessage() {
            this.error = '';
            this.info = '';
            if (this.sendForm.geoLocked && !this.sendForm.lat) {
                this.error = 'click on the map to set geo-lock location';
                return;
            }

            if (this.sendMode === 'message' && !this.sendForm.message) {
                this.error = 'message cannot be empty';
                return;
            }
            if (this.sendMode === 'document' && !this.sendForm.file) {
                this.error = 'select a file to send';
                return;
            }
            if (!this.sendForm.receiver) {
                this.error = 'enter a receiver username';
                return;
            }

            this.sending = true;
            try {
                // Get receiver public key
                const keyRes = await fetch('/api/users/' + this.sendForm.receiver);
                if (!keyRes.ok) { this.error = 'receiver not found'; this.sending = false; return; }
                const { publicKey: pubKeyPem } = await keyRes.json();
                const receiverPublicKey = forge.pki.publicKeyFromPem(pubKeyPem);

                // Generate AES-256 key
                const aesKey = new Uint8Array(32);
                crypto.getRandomValues(aesKey);

                // Split key into shards
                const shard1 = aesKey.slice(0, 16);
                const shard2 = aesKey.slice(16, 32);
                const shard2Base64 = btoa(String.fromCharCode(...shard2));

                // Encrypt data
                let payloadBytes;
                if (this.sendMode === 'message') {
                    payloadBytes = new TextEncoder().encode(this.sendForm.message);
                } else {
                    payloadBytes = new Uint8Array(await this.sendForm.file.arrayBuffer());
                }

                const iv = new Uint8Array(12);
                crypto.getRandomValues(iv);
                const importedKey = await crypto.subtle.importKey('raw', aesKey, 'AES-GCM', false, ['encrypt']);
                const ciphertext = await crypto.subtle.encrypt({ name: 'AES-GCM', iv }, importedKey, payloadBytes);

                // Bundle: iv(12) + ciphertext
                const payload = new Uint8Array(12 + ciphertext.byteLength);
                payload.set(iv, 0);
                payload.set(new Uint8Array(ciphertext), 12);
                const payloadBase64 = btoa(String.fromCharCode(...payload));

                // RSA encrypt shard1 with receiver's public key
                const shard1Bytes = forge.util.createBuffer(String.fromCharCode(...shard1));
                const encryptedShard1 = receiverPublicKey.encrypt(shard1Bytes.data, 'RSA-OAEP', {
                    md: forge.md.sha256.create()
                });
                const encryptedKeyBase64 = btoa(encryptedShard1);

                // Build form data
                const formData = new FormData();
                formData.append('encryptedPayload', payloadBase64);
                formData.append('encryptedKey', encryptedKeyBase64);
                formData.append('keyShard', shard2Base64);
                formData.append('receiverUsername', this.sendForm.receiver);
                formData.append('geoLocked', this.sendForm.geoLocked);
                formData.append('burnAfterRead', this.sendForm.burnAfterRead);

                if (this.sendForm.geoLocked) {
                    formData.append('lat', this.sendForm.lat);
                    formData.append('lon', this.sendForm.lon);
                }

                if (this.sendMode === 'document') {
                    formData.append('file', this.sendForm.file);
                } else {
                    // Generate noise image and embed payload via server
                    const noiseBlob = await this.generateNoiseImage();
                    formData.append('image', noiseBlob, 'noise.png');
                }

                const res = await fetch('/api/messages/send', { method: 'POST', body: formData });
                const data = await res.json();
                if (!res.ok) { this.error = data.error || 'send failed'; this.sending = false; return; }
                this.info = 'message transmitted successfully';
                this.sendForm = { receiver: '', message: '', lat: null, lon: null, geoLocked: true, burnAfterRead: false, file: null, fileName: '' };
            } catch (e) {
                this.error = 'error: ' + e.message;
            }
            this.sending = false;
        },

        // --- OPEN MESSAGE ---
        async openMessage(msg) {
            if (msg.expired) return;
            this.currentMessage = msg;
            this.currentMessageId = msg.id;
            this.decrypted = null;
            this.decryptedFileBlob = null;
            this.error = '';
            this.view = 'view';
        },

        // --- DECRYPT MESSAGE ---
        async decryptMessage() {
            this.error = '';
            if (!this.auth.privateKey) { this.error = 'session expired. login again.'; return; }
            this.decrypting = true;

            try {
                let shard2;

                // Get message metadata
                const msgRes = await fetch('/api/messages/' + this.currentMessageId);
                if (!msgRes.ok) { this.error = 'failed to load message'; this.decrypting = false; return; }
                const msg = await msgRes.json();

                if (msg.expired) { this.error = 'message has expired'; this.decrypting = false; return; }

                if (msg.geoLocked) {
                    // Get current location
                    const pos = await new Promise((resolve, reject) =>
                        navigator.geolocation.getCurrentPosition(resolve, reject, {
                            enableHighAccuracy: true,
                            timeout: 15000,
                            maximumAge: 0
                        })
                    );
                    const { latitude: lat, longitude: lon } = pos.coords;

                    // Server-side geo verification (returns shard on success)
                    const verifyRes = await fetch('/api/location/verify', {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify({ lat, lon, messageId: this.currentMessageId })
                    });
                    const verifyData = await verifyRes.json();

                    if (!verifyData.valid) {
                        this.error = verifyData.message || 'location verification failed. you are ' + verifyData.distance + 'm away, need to be within ' + verifyData.radius + 'm';
                        this.decrypting = false;
                        return;
                    }
                    shard2 = verifyData.keyShard;
                } else {
                    shard2 = msg.keyShard;
                }

                if (!shard2) {
                    this.error = 'key shard unavailable. message may have expired.';
                    this.decrypting = false;
                    return;
                }

                // RSA decrypt shard1
                const encryptedShard1Bytes = atob(msg.encryptedKey);
                const shard1Bytes = this.auth.privateKey.decrypt(encryptedShard1Bytes, 'RSA-OAEP', {
                    md: forge.md.sha256.create()
                });
                const shard1 = new Uint8Array(shard1Bytes.split('').map(c => c.charCodeAt(0)));

                // Decode shard2
                const shard2Decoded = new Uint8Array(atob(shard2).split('').map(c => c.charCodeAt(0)));

                // Reconstruct AES key
                const aesKey = new Uint8Array(32);
                aesKey.set(shard1, 0);
                aesKey.set(shard2Decoded, 16);

                if (msg.isDocument) {
                    // Download encrypted document
                    const fileRes = await fetch('/api/messages/' + this.currentMessageId + '/file');
                    const encryptedBlob = await fileRes.blob();
                    const encryptedBytes = new Uint8Array(await encryptedBlob.arrayBuffer());

                    // Decrypt: first 12 bytes = IV, rest = ciphertext
                    const iv = encryptedBytes.slice(0, 12);
                    const ciphertext = encryptedBytes.slice(12);
                    const importedKey = await crypto.subtle.importKey('raw', aesKey, 'AES-GCM', false, ['decrypt']);
                    const plaintext = await crypto.subtle.decrypt({ name: 'AES-GCM', iv }, importedKey, ciphertext);
                    this.decryptedFileBlob = new Blob([plaintext]);
                    this.decrypted = 'document ready';
                } else {
                    // Download stego image and extract payload
                    const imgRes = await fetch('/api/messages/' + this.currentMessageId + '/image');
                    const imgBlob = await imgRes.blob();
                    const extractedPayload = await extractLsbPayload(imgBlob);

                    // Decrypt: first 12 bytes = IV, rest = ciphertext
                    const iv = extractedPayload.slice(0, 12);
                    const ciphertext = extractedPayload.slice(12);
                    const importedKey = await crypto.subtle.importKey('raw', aesKey, 'AES-GCM', false, ['decrypt']);
                    const plaintext = await crypto.subtle.decrypt({ name: 'AES-GCM', iv }, importedKey, ciphertext);
                    this.decrypted = new TextDecoder().decode(plaintext);
                }
            } catch (e) {
                this.error = 'decryption failed: ' + e.message;
            }
            this.decrypting = false;
        },

        downloadDecryptedFile() {
            if (!this.decryptedFileBlob || !this.currentMessage) return;
            const url = URL.createObjectURL(this.decryptedFileBlob);
            const a = document.createElement('a');
            a.href = url;
            a.download = this.currentMessage.fileName || 'document';
            a.click();
            URL.revokeObjectURL(url);
        },

        formatTime(iso) {
            if (!iso) return '';
            const d = new Date(iso);
            return d.toLocaleString('en-GB', { day: '2-digit', month: 'short', hour: '2-digit', minute: '2-digit', hour12: false });
        },

        getCountdown(expiresAt) {
            if (!expiresAt) return '';
            const remaining = new Date(expiresAt) - new Date();
            if (remaining <= 0) return 'expired';
            const mins = Math.floor(remaining / 60000);
            const secs = Math.floor((remaining % 60000) / 1000);
            return mins + 'm ' + secs + 's';
        }
    };
}

// --- LSB Extraction ---
async function extractLsbPayload(imageBlob) {
    return new Promise((resolve, reject) => {
        const img = new Image();
        img.onload = () => {
            const canvas = document.createElement('canvas');
            canvas.width = img.width;
            canvas.height = img.height;
            const ctx = canvas.getContext('2d');
            ctx.drawImage(img, 0, 0);
            const data = ctx.getImageData(0, 0, img.width, img.height).data;

            // Read header (first 32 bits = payload length in bytes)
            let headerBits = '';
            for (let i = 0; i < 32; i++) {
                const pixelIdx = Math.floor(i / 3) * 4;
                const channel = i % 3;
                headerBits += (data[pixelIdx + channel] & 1).toString();
            }
            const payloadLength = parseInt(headerBits, 2);
            if (payloadLength <= 0 || payloadLength > 10_000_000) {
                reject(new Error('invalid payload length: ' + payloadLength));
                return;
            }

            const totalBits = (4 + payloadLength) * 8;
            const result = new Uint8Array(payloadLength);
            let bitIdx = 32;
            let resultBit = 0;
            for (let i = 32; i < totalBits && resultBit < payloadLength * 8; i++) {
                const pixelIdx = Math.floor(i / 3) * 4;
                const channel = i % 3;
                const bit = data[pixelIdx + channel] & 1;
                if (bit) result[Math.floor(resultBit / 8)] |= (1 << (7 - (resultBit % 8)));
                resultBit++;
            }
            resolve(result);
        };
        img.onerror = reject;
        img.src = URL.createObjectURL(imageBlob);
    });
}
```

- [ ] **Step 4: Verify backend still compiles**

```bash
cd backend && mvn clean compile -q
```

Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add frontend/index.html frontend/style.css frontend/app.js
git commit -m "rewrite frontend with hyprland dark theme, noise images, key sharding and document support"
```

---

### Task 8: Integration Test — Start Everything and Smoke Test

- [ ] **Step 1: Make sure the PostgreSQL container is running**

```bash
docker ps | grep securechat-postgres || docker start securechat-postgres
```

If not started:
```bash
docker run -d --name securechat-postgres -e POSTGRES_DB=securechat -e POSTGRES_USER=chatuser -e POSTGRES_PASSWORD=strongpassword -p 5433:5432 postgres:15
```

- [ ] **Step 2: Start the Spring Boot backend**

```bash
cd backend && mvn spring-boot:run
```

Expected: Application starts on port 8080, Flyway runs both V1 and V2 migrations.

- [ ] **Step 3: Verify in browser**

Open `http://localhost:8080` — should show the Hyprland-themed login screen.

- [ ] **Step 4: Quick smoke test**

1. Register user "alice" with password "testtest1"
2. Register user "bob" with password "testtest2"
3. Login as alice, send a message to bob (click map to geo-lock)
4. Login as bob, check inbox, decrypt the message
5. Verify countdown timer is shown
6. Verify message expires after 30 minutes

- [ ] **Step 5: Commit any fixes if needed**

---

### Task 9: Write LOCAL_PROJECT_DOCS.md

**Files:**
- Create: `LOCAL_PROJECT_DOCS.md` (gitignored)

- [ ] **Step 1: Write comprehensive local documentation**

Create `LOCAL_PROJECT_DOCS.md` at project root with complete documentation covering:
- Project overview and all features
- Architecture diagram (text-based)
- Every class, module, and its purpose
- API endpoint reference
- Encryption flow diagrams
- Database schema
- How to run locally
- Configuration reference
- Feature list with descriptions

This file will be generated based on the final state of all code after all changes are complete. It should be thorough enough that someone could understand the entire project from this file alone.

- [ ] **Step 2: Verify it's in .gitignore**

```bash
grep "LOCAL_PROJECT_DOCS.md" .gitignore
```

Expected: Shows the entry.

---

### Task 10: Final Git Commits

- [ ] **Step 1: Check git status**

```bash
git status
```

- [ ] **Step 2: Commit any remaining changes**

Stage and commit with appropriate human-style messages.

- [ ] **Step 3: Verify all commits look clean**

```bash
git log --oneline -10
```

Expected commits (approximately):
```
abc1234 add local project documentation
abc1235 rewrite frontend with hyprland dark theme, noise images, key sharding and document support
abc1236 update controllers for geo-fence fix, key shard return and online status tracking
abc1237 update message service with key sharding, document support and burn-after-read
abc1238 add key shard splitting and automatic message expiry after 30 minutes
abc1239 add database schema for message expiry, documents, burn-after-read and online status
abc1240 add online status to user lookup endpoint
abc1241 switch to local postgres on port 5433 and fix cookie for local dev
```
