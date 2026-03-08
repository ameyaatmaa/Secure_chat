# Design: Geo-Locked Steganographic Secure Chat System

**Date:** 2026-03-08
**Status:** Approved
**Approach:** Monolith Spring Boot + Docker + NGINX + Azure VM

---

## 1. Goals

Build a messaging system that provides:
- End-to-end encrypted communication (AES-256-GCM + RSA-4096)
- Steganographic message hiding in images (LSB encoding)
- Geo-locked message decryption (receiver must be within 50m of sender-defined location)
- Strong authentication (JWT, httpOnly cookies)
- Password-derived key management (no private key ever stored)
- Self-hosted deployment on a single Azure VM

---

## 2. Architecture

```
Azure VM (Ubuntu)
│
├── NGINX (port 443/80) — TLS termination via Let's Encrypt, reverse proxy
│   └── → Spring Boot app (port 8080, internal Docker network only)
│
├── Docker Compose
│   ├── spring-app  (Spring Boot JAR)
│   ├── postgres-db (port 5432, internal only)
│   └── nginx       (port 80/443, exposed to internet)
│
└── /var/app/images/  — stego image storage (bind-mounted into spring-app)
```

**Request flow:**
1. Browser → HTTPS → NGINX → Spring Boot (HTTP internal)
2. Spring Boot writes/reads stego images from bind-mounted volume
3. Spring Boot talks to Postgres on internal Docker network
4. NGINX never sees plaintext message data (encrypted at client before send)

---

## 3. Cryptography & Key Management

### Key Derivation
```
password + salt → PBKDF2-SHA256 (310,000 iterations) → 256-bit master key
master key → RSA-4096 private key (deterministic via seeded SecureRandom)
```
Private key is **never stored anywhere** — reconstructed fresh on each login from password.

### Message Encryption (Sender)
```
plaintext → AES-256-GCM → ciphertext + auth tag
random AES key → RSA-4096 encrypt (receiver public key) → encrypted_key
{ciphertext + encrypted_key + IV} → base64 payload → LSB-embed into PNG
```

### Message Decryption (Receiver)
```
PNG → extract LSB payload → base64 decode
encrypted_key → RSA-4096 decrypt (PBKDF2-derived private key) → AES key
AES key + ciphertext → AES-256-GCM decrypt → plaintext
```

### Geo-Lock (Hybrid)
- **Server (coarse):** validates coordinates are plausible (not null, within valid range)
- **Client (fine):** runs `Haversine(senderLat, senderLon, receiverLat, receiverLon) ≤ 50m`
- RSA decrypt only proceeds client-side after local geo check passes
- Server never stores receiver's location

---

## 4. Database Schema

```sql
CREATE TABLE users (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    username     VARCHAR(50) UNIQUE NOT NULL,
    password_hash VARCHAR(60) NOT NULL,  -- BCrypt
    public_key   TEXT NOT NULL,          -- RSA-4096 public key PEM
    created_at   TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE messages (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    sender_id        UUID REFERENCES users(id),
    receiver_id      UUID REFERENCES users(id),
    image_filename   VARCHAR(255) NOT NULL,
    encrypted_key    TEXT NOT NULL,       -- AES key encrypted with receiver RSA public key
    sender_lat       DOUBLE PRECISION NOT NULL,
    sender_lon       DOUBLE PRECISION NOT NULL,
    radius_meters    INTEGER DEFAULT 50,
    created_at       TIMESTAMPTZ DEFAULT NOW()
);
```

---

## 5. REST API

### Authentication
```
POST /api/auth/register   {username, password, publicKey}  → 201
POST /api/auth/login      {username, password}             → 200 + JWT cookie
POST /api/auth/logout                                      → 200 (clears cookie)
```

### Messaging
```
POST /api/messages/send         multipart: {image, encryptedPayload, encryptedKey,
                                            receiverUsername, lat, lon, radius}
GET  /api/messages/inbox        → [{id, senderUsername, createdAt}]
GET  /api/messages/{id}         → {encryptedKey, senderLat, senderLon, radius}
GET  /api/messages/{id}/image   → PNG file (authenticated, streamed from disk)
```

### Geo & Users
```
POST /api/location/verify   {lat, lon, messageId}  → {valid: true/false}  (coarse check)
GET  /api/users/{username}  → {publicKey}
```

### Security
- JWT stored in `httpOnly` cookie (not localStorage — XSS protection)
- JWT expiry: 24 hours, HS256
- Rate limiting on auth endpoints
- Image upload: PNG/BMP only, max 5MB
- Secure headers: HSTS, CSP, X-Frame-Options, X-Content-Type-Options

---

## 6. Backend Module Structure

```
backend/src/main/java/com/securechat/
├── config/
│   ├── SecurityConfig.java
│   └── JwtConfig.java
├── controller/
│   ├── AuthController.java
│   ├── MessageController.java
│   ├── LocationController.java
│   └── UserController.java
├── service/
│   ├── EncryptionService.java       -- RSA operations via BouncyCastle
│   ├── SteganographyService.java    -- LSB embed/extract
│   ├── GeoLocationService.java      -- Haversine + coarse validation
│   └── MessageService.java          -- orchestration
├── repository/
│   ├── UserRepository.java
│   └── MessageRepository.java
├── model/
│   ├── User.java
│   └── Message.java
├── security/
│   ├── JwtProvider.java
│   └── JwtFilter.java
└── util/
    └── HaversineCalculator.java
```

---

## 7. Frontend

**Stack:** HTML + CSS + Alpine.js + vanilla JS (Web Crypto API + forge.js + Leaflet.js)

**Views (SPA-style, single HTML routing):**
- `/login` — login form, PBKDF2 key derivation, holds private key in Alpine state
- `/register` — registration, key derivation, sends public key to server
- `/inbox` — received message list
- `/send` — compose: pick receiver, write message, pick location (Leaflet map), attach image
- `/view/{id}` — extract payload from PNG, geo check, decrypt, display

**Browser crypto:**
- `window.crypto.subtle` — AES-256-GCM, PBKDF2
- `forge.js` — RSA-4096 in browser
- `navigator.geolocation` — receiver location for 50m check

---

## 8. Docker Compose

```yaml
version: '3.8'
services:
  postgres:
    image: postgres:15
    environment:
      POSTGRES_DB: securechat
      POSTGRES_USER: chatuser
      POSTGRES_PASSWORD: ${DB_PASSWORD}
    volumes:
      - postgres_data:/var/lib/postgresql/data
    networks:
      - internal

  app:
    build: ./backend
    environment:
      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/securechat
      SPRING_DATASOURCE_USERNAME: chatuser
      SPRING_DATASOURCE_PASSWORD: ${DB_PASSWORD}
      JWT_SECRET: ${JWT_SECRET}
      IMAGE_STORAGE_PATH: /var/app/images
    volumes:
      - image_data:/var/app/images
    depends_on:
      - postgres
    networks:
      - internal

  nginx:
    image: nginx:alpine
    ports:
      - "80:80"
      - "443:443"
    volumes:
      - ./nginx/nginx.conf:/etc/nginx/nginx.conf:ro
      - ./nginx/certs:/etc/letsencrypt:ro
      - image_data:/var/app/images:ro
    depends_on:
      - app
    networks:
      - internal
      - external

networks:
  internal:
  external:

volumes:
  postgres_data:
  image_data:
```

---

## 9. Azure Deployment Steps

```
1. Create Azure VM (Ubuntu 22.04 LTS, Standard B2s minimum)
2. Open ports: 22 (SSH), 80 (HTTP), 443 (HTTPS)
3. Install Docker + Docker Compose
4. Clone repository
5. Set environment variables in .env file
6. docker compose up -d
7. Install Certbot, obtain Let's Encrypt cert
8. Configure NGINX with TLS
9. Set up UFW firewall rules
```

---

## 10. Security Hardening

- Passwords: BCrypt (strength 12)
- API: JWT auth on all endpoints except register/login
- Rate limiting: Spring Boot bucket4j on auth endpoints
- Input validation: Spring Validation on all DTOs
- Image: whitelist PNG/BMP by magic bytes (not just extension), max 5MB
- NGINX headers: HSTS, CSP, X-Frame-Options, X-Content-Type-Options, Referrer-Policy
- Postgres: not exposed on host network
- SSH: key-based auth only on VM
