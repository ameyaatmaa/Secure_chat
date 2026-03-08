# Secure Chat — Project Context & Compact Reference

**Status:** Implementation plan created, ready to build
**GitHub:** ameyaatmaa/Secure_chat
**Working dir:** `~/Desktop/Secure_chat/`

---

## What We're Building

A secure chat app where:
- Messages are **AES-256-GCM encrypted** client-side
- Encrypted payloads are **hidden inside PNG images** using LSB steganography
- Images can only be **decrypted if receiver is within 50 meters** of sender-defined GPS location
- Private keys are **never stored** — derived from password via PBKDF2 on every login

---

## Key Decisions Made

| Decision | Choice | Reason |
|----------|--------|--------|
| Architecture | Monolith Spring Boot | Single VM, no need for microservices |
| Frontend | HTML + Alpine.js + forge.js + Leaflet | Lightweight, reactive, no build step |
| Private key storage | PBKDF2-derived, never stored | Maximum security |
| Geo-lock | Hybrid: coarse server + fine client | Server never learns receiver location |
| Image storage | Local filesystem on VM | Simple, bind-mounted Docker volume |
| JWT storage | httpOnly cookie | XSS protection vs localStorage |
| Password hashing | BCrypt strength 12 | Industry standard |

---

## Tech Stack

```
Backend:    Java 21, Spring Boot 3.2, Spring Security, Spring Data JPA
Crypto:     BouncyCastle (AES-256-GCM, RSA-4096-OAEP), PBKDF2-SHA256
Database:   PostgreSQL 15 (Docker), Flyway migrations
Frontend:   HTML5, Alpine.js, forge.js (RSA in browser), Web Crypto API, Leaflet.js
Infra:      Docker Compose, NGINX (TLS termination, rate limiting), Azure VM
Auth:       JWT (jjwt 0.12.5), httpOnly cookie, 24h expiry
```

---

## Project Structure

```
Secure_chat/
├── backend/
│   ├── pom.xml
│   ├── Dockerfile
│   └── src/main/java/com/securechat/
│       ├── SecureChatApplication.java
│       ├── config/       SecurityConfig.java, GlobalExceptionHandler.java
│       ├── controller/   AuthController, MessageController, LocationController, UserController
│       ├── service/      EncryptionService, SteganographyService, GeoLocationService, MessageService
│       ├── repository/   UserRepository, MessageRepository
│       ├── model/        User.java, Message.java
│       ├── security/     JwtProvider.java, JwtFilter.java
│       └── util/         HaversineCalculator.java
├── frontend/
│   ├── index.html        (Alpine.js SPA)
│   ├── app.js            (crypto, LSB, geo, API calls)
│   └── style.css
├── nginx/nginx.conf
├── docker-compose.yml
├── .env.example
└── docs/plans/           (design + implementation plan)
```

---

## Database Schema

```sql
-- users
id UUID PK, username VARCHAR(50) UNIQUE, password_hash VARCHAR(60),
public_key TEXT, created_at TIMESTAMPTZ

-- messages
id UUID PK, sender_id UUID FK, receiver_id UUID FK,
image_filename VARCHAR(255), encrypted_key TEXT,
sender_lat DOUBLE, sender_lon DOUBLE, radius_meters INT DEFAULT 50,
created_at TIMESTAMPTZ
```

---

## REST API

```
POST /api/auth/register       {username, password, publicKey}
POST /api/auth/login          {username, password} → JWT cookie
POST /api/auth/logout         → clears cookie

POST /api/messages/send       multipart: image + encryptedPayload + encryptedKey
                              + receiverUsername + lat + lon + radius
GET  /api/messages/inbox      → [{id, senderUsername, createdAt}]
GET  /api/messages/{id}       → {encryptedKey, senderLat, senderLon, radiusMeters}
GET  /api/messages/{id}/image → PNG stream (authenticated)

POST /api/location/verify     {lat, lon, messageId} → {valid: bool}
GET  /api/users/{username}    → {publicKey}
```

---

## Crypto Flows

**Send:**
```
plaintext → AES-256-GCM(random key) → ciphertext+IV
random AES key → RSA-OAEP encrypt(receiver pubkey) → encryptedKey
base64(IV + ciphertext) → LSB embed in PNG → upload PNG + encryptedKey
```

**Receive:**
```
download PNG → extract LSB payload → base64 decode → IV + ciphertext
encryptedKey → RSA-OAEP decrypt(PBKDF2-derived privkey) → AES key
AES-GCM decrypt(ciphertext, IV, AES key) → plaintext
(only after Haversine check ≤ 50m passes client-side)
```

**Key derivation:**
```
PBKDF2-SHA256(password, username+":securechat:v1", 310000 iters) → 256-bit seed
→ deterministic RSA-2048 keypair via forge.js seeded PRNG
```

---

## Implementation Tasks (17 total)

| # | Task | Key Files |
|---|------|-----------|
| 1 | Spring Boot scaffold | `backend/pom.xml`, `application.yml`, `SecureChatApplication.java` |
| 2 | Flyway DB migration | `db/migration/V1__init.sql` |
| 3 | JPA models + repos | `User.java`, `Message.java`, `UserRepository`, `MessageRepository` |
| 4 | Haversine util (TDD) | `HaversineCalculator.java`, `HaversineCalculatorTest.java` |
| 5 | JWT provider + filter (TDD) | `JwtProvider.java`, `JwtFilter.java`, `JwtProviderTest.java` |
| 6 | Security config | `SecurityConfig.java` (BCrypt, stateless, JWT filter chain) |
| 7 | Auth controller (TDD) | `AuthController.java`, register/login/logout DTOs + tests |
| 8 | EncryptionService (TDD) | AES-256-GCM + RSA-4096-OAEP via BouncyCastle |
| 9 | SteganographyService (TDD) | LSB embed/extract with 4-byte length header |
| 10 | GeoLocationService (TDD) | Haversine wrapper + coarse coord validation |
| 11 | MessageService + controllers | Orchestration, file I/O, magic-byte image validation |
| 12 | Global exception handler | `GlobalExceptionHandler.java` |
| 13 | Dockerfile | Multi-stage eclipse-temurin:21 |
| 14 | Docker Compose + NGINX | Rate limiting, security headers, TLS redirect |
| 15 | Frontend | Alpine.js SPA, forge.js RSA, Web Crypto AES, Leaflet map, LSB JS decoder |
| 16 | GitHub repo + push | `gh repo create`, `git push -u origin master` |
| 17 | Build verification | `mvn test`, Docker build, smoke test, Azure deploy checklist |

---

## Maven Dependencies (key)

```xml
spring-boot-starter-{web,security,data-jpa,validation}
postgresql (runtime)
flyway-core + flyway-database-postgresql
jjwt-{api,impl,jackson} 0.12.5
bcprov-jdk18on 1.77
lombok
bucket4j-core 8.7.0
```

---

## Docker Compose Services

```yaml
postgres:   postgres:15, internal network only, healthcheck
app:        build ./backend, depends_on postgres healthy, bind-mounts /var/app/images
nginx:      nginx:alpine, ports 80/443, proxies to app:8080
```

---

## NGINX Security Headers

```
Strict-Transport-Security: max-age=31536000; includeSubDomains
X-Frame-Options: DENY
X-Content-Type-Options: nosniff
Referrer-Policy: strict-origin-when-cross-origin
Content-Security-Policy: default-src 'self'; script-src 'self' cdn.jsdelivr.net unpkg.com ...
Rate limits: auth 5r/m burst 10, api 30r/m burst 50
```

---

## Azure Deployment Steps

```
1. Azure VM Ubuntu 22.04, Standard B2s, ports 22/80/443
2. apt install docker.io docker-compose-v2
3. git clone + cp .env.example .env (set DB_PASSWORD, JWT_SECRET)
4. docker compose up -d
5. certbot certonly --standalone -d yourdomain.com
6. Update nginx.conf with domain, docker compose restart nginx
7. UFW: allow 22/80/443, deny rest
```

---

## Full Plan & Design Docs

- [Design doc](docs/plans/2026-03-08-geo-locked-stego-chat-design.md)
- [Implementation plan](docs/plans/2026-03-08-implementation-plan.md) — full code for all 17 tasks
