# SecureChat — Local Overhaul Design Spec

## Overview

Migrate SecureChat from a VM/Docker deployment to local development, overhaul the UI with an Arch/Hyprland aesthetic, and add temporal decay encryption, encrypted document sending, and geo-fencing reliability fixes.

---

## 1. Infrastructure: Local Dev Setup

### PostgreSQL
- Fresh Docker container on **port 5433** (avoids conflict with local postgres on 5432)
- User: `chatuser`, password: `strongpassword`, database: `securechat`
- `docker run` command, no docker-compose needed for just postgres

### Backend
- Run directly via `mvn spring-boot:run`
- Update `application.yml` defaults: datasource URL → `localhost:5433`
- No Docker build for the app

### Frontend
- Served by Spring Boot static resource handler from `frontend/` folder
- Access at `http://localhost:8080`
- No Nginx, no SSL for local dev

---

## 2. Temporal Decay Encryption (30 min TTL)

### Concept
Messages become cryptographically impossible to decrypt after 30 minutes. The AES key is split into two shards — one held by the receiver (RSA-encrypted), one held by the server. Server permanently deletes its shard after expiry.

### Flow

**Sending:**
1. Generate AES-256 key (32 bytes)
2. Split: `shard1` (first 16 bytes), `shard2` (last 16 bytes)
3. Encrypt message/file with the FULL 32-byte AES key
4. RSA-encrypt `shard1` with receiver's public key → `encryptedShard1`
5. Generate noise image on client (Canvas, 512x512 random RGB pixels)
6. LSB-embed encrypted payload into noise image (if payload fits)
7. Upload: stego image + `encryptedShard1` + `shard2` (plaintext, server-held)
8. Server stores `shard2` in DB, stego image on disk

**Receiving:**
1. Open message from inbox
2. If geo-locked: browser gets GPS → `POST /api/location/verify` with coords + messageId
3. Server checks Haversine → if within radius, returns `shard2`
4. If not geo-locked: server returns `shard2` immediately via `GET /api/messages/{id}`
5. Client RSA-decrypts `shard1` with private key
6. Reconstruct AES key = `shard1 || shard2`
7. Download stego image → LSB extract payload → AES-256-GCM decrypt → plaintext

**Expiry (Server-side scheduled task):**
- `@Scheduled` Spring task runs every 2 minutes
- Query: `SELECT * FROM messages WHERE expires_at < NOW() AND expired = false`
- For each: set `key_shard = NULL`, delete image/file from disk, set `expired = true`
- Expired messages show "Message expired" in inbox — unrecoverable

### Database Changes
- `key_shard` (TEXT) — server-held shard, nulled on expiry
- `expires_at` (TIMESTAMPTZ) — `created_at + 30 minutes`
- `expired` (BOOLEAN, default false)

---

## 3. Geo-Fencing Fix

### Problem
Current implementation has redundant dual validation (server coarse + client fine), is unreliable, and gives poor error messages.

### Solution
- **Single server-side Haversine check** — remove client-side check entirely
- Server is the sole authority on location validation
- Default radius: **100km** (configurable per message, 1–10000km range)
- Sender can **toggle geo-lock on/off** per message via UI switch
- When geo-locked, `shard2` is only returned after location verification passes
- Better error response: `{ valid: false, distance: 15234, radius: 100000, message: "You are 15km away, need to be within 100km" }`

### Database Changes
- `geo_locked` (BOOLEAN, default true)

### API Changes
- `POST /api/location/verify` now returns `{ valid, keyShard, distance, radius }` on success
- `GET /api/messages/{id}` returns `shard2` only if message is NOT geo-locked. If geo-locked, client must call `/api/location/verify` first.

---

## 4. Procedural Noise Carrier Images

### Concept
Instead of user-selected images, the client auto-generates a random noise PNG (512x512 random RGB pixels) via Canvas API before sending. Noise images are ideal for LSB steganography because bit modifications are invisible in random data.

### Implementation
- Client-side: `canvas.getContext('2d')` → fill with `Math.random()` RGB values per pixel → `canvas.toBlob('image/png')`
- No image picker in UI
- User types message → hits send → noise image generated → payload embedded → uploaded

---

## 5. Encrypted Document Sending

### Concept
Any file type, max 10MB. Same encryption pipeline as messages.

### Flow
- Small files (payload fits in stego image): LSB-embed encrypted bytes in noise image
- Large files (exceeds stego capacity): store encrypted blob directly on server filesystem at `{IMAGE_STORAGE_PATH}/{uuid}.enc`, skip stego
- Same key-shard temporal decay scheme applies
- Receiver downloads encrypted file → reconstructs AES key → decrypts locally → browser triggers file download

### Database Changes
- `file_name` (VARCHAR 255, nullable) — original filename
- `file_type` (VARCHAR 100, nullable) — MIME type
- `file_size` (BIGINT, nullable) — size in bytes
- `is_document` (BOOLEAN, default false)

### API Changes
- `POST /api/messages/send` — accepts optional `file` multipart field
- `GET /api/messages/{id}/file` — (NEW) download encrypted document blob

---

## 6. Quick Win Features

### 6.1 Burn After Reading
- `burn_after_read` (BOOLEAN, default false) in messages table
- Sender toggles in UI when composing
- After first successful decryption: server deletes `key_shard`, stego image/file, marks expired
- Message shows "This message has been read and destroyed" afterwards

### 6.2 Unread Message Count
- `read` (BOOLEAN, default false) in messages table
- Marked true when receiver opens the message detail view
- Inbox tab shows badge with unread count
- API: `GET /api/messages/inbox` returns `unreadCount` in response

### 6.3 Online Status
- `last_seen` (TIMESTAMPTZ) in users table
- Updated on every authenticated API request (via JWT filter)
- Users seen within last 5 minutes shown as "online" (green dot)
- `GET /api/users/{username}` returns `lastSeen` timestamp

### 6.4 Copy-Paste Protection
- CSS `user-select: none` on decrypted message content
- `oncontextmenu` disabled on message view
- Page Visibility API: if user switches tab while viewing decrypted content, re-hide the message (require re-decryption)
- Watermark overlay with receiver's username on decrypted content (subtle, semi-transparent)

---

## 7. UI Overhaul — Arch/Hyprland Theme

### Color Palette (Tokyo Night / Catppuccin Mocha inspired)
- Background: `#1a1b26` (deep blue-black)
- Surface: `#24283b` (slightly lighter panels)
- Border: `#414868` (subtle dividers)
- Text primary: `#c0caf5` (soft lavender white)
- Text secondary: `#565f89` (muted)
- Accent cyan: `#7dcfff` (links, active states)
- Accent magenta: `#bb9af7` (highlights, badges)
- Accent green: `#9ece6a` (success, online)
- Accent red: `#f7768e` (errors, warnings, expiry countdown)
- Accent orange: `#ff9e64` (warnings)

### Typography
- Primary font: JetBrains Mono (loaded from CDN)
- Fallback: `'Fira Code', 'Cascadia Code', monospace`
- Base size: 14px
- All lowercase UI labels for the hacker aesthetic

### Layout
- Tiled/window-manager feel — sharp corners, NO border-radius
- Thin 1px borders with `#414868`
- Panels look like terminal windows with title bars (e.g., `[ inbox ]`, `[ send ]`)
- Status bar at bottom showing: username, connection status, current view
- Minimal padding, dense information display

### Components
- Input fields: dark background, monospace, thin border, cyan focus glow
- Buttons: outlined style, no fill, cyan/magenta border, uppercase monospace text
- Cards/message items: flat, single-border bottom, hover highlights with subtle bg change
- Map: CartoDB dark_matter tile layer for Leaflet
- Scrollbars: thin, custom styled to match theme
- Toast notifications: slide in from top-right, terminal-style `[INFO]`, `[ERROR]` prefixes

### Animations
- Minimal and snappy — no bouncy/elastic
- Fade in for views (150ms)
- Subtle scanline overlay (CSS pseudo-element, very low opacity) for CRT feel
- Blinking cursor on active input

### Responsive
- Mobile-friendly: stack panels vertically on small screens
- Map takes full width on mobile

---

## 8. Updated Database Schema (Full)

### Flyway `V2__add_expiry_documents_features.sql`

```sql
-- Temporal decay
ALTER TABLE messages ADD COLUMN key_shard TEXT;
ALTER TABLE messages ADD COLUMN expires_at TIMESTAMPTZ;
ALTER TABLE messages ADD COLUMN expired BOOLEAN DEFAULT false;

-- Geo-lock toggle
ALTER TABLE messages ADD COLUMN geo_locked BOOLEAN DEFAULT true;

-- Document support
ALTER TABLE messages ADD COLUMN file_name VARCHAR(255);
ALTER TABLE messages ADD COLUMN file_type VARCHAR(100);
ALTER TABLE messages ADD COLUMN file_size BIGINT;
ALTER TABLE messages ADD COLUMN is_document BOOLEAN DEFAULT false;

-- Burn after read
ALTER TABLE messages ADD COLUMN burn_after_read BOOLEAN DEFAULT false;

-- Read status
ALTER TABLE messages ADD COLUMN read BOOLEAN DEFAULT false;

-- User online status
ALTER TABLE users ADD COLUMN last_seen TIMESTAMPTZ;

-- Index for expiry scheduler
CREATE INDEX idx_messages_expires_at ON messages(expires_at) WHERE expired = false;
```

---

## 9. Updated API Summary

| Method | Endpoint | Auth | Changes |
|--------|----------|------|---------|
| POST | /api/auth/register | No | Unchanged |
| POST | /api/auth/login | No | Unchanged |
| POST | /api/auth/logout | No | Unchanged |
| GET | /api/users/{username} | Yes | Now returns `lastSeen` |
| POST | /api/messages/send | Yes | Accepts `file`, `keyShard`, `geoLocked`, `burnAfterRead`. Auto noise image. |
| GET | /api/messages/inbox | Yes | Returns `unreadCount`, messages include `expired`, `read`, `isDocument` |
| GET | /api/messages/{id} | Yes | Returns `expiresAt`, `geoLocked`, `burnAfterRead`, `isDocument`, `fileName`. Returns `keyShard` only if NOT geo-locked. |
| POST | /api/location/verify | Yes | Returns `keyShard` on success + distance info |
| GET | /api/messages/{id}/image | Yes | Unchanged |
| GET | /api/messages/{id}/file | Yes | NEW — download encrypted document |

---

## 10. File Changes Summary

### New Files
```
backend/src/main/java/com/securechat/scheduler/MessageExpiryScheduler.java
backend/src/main/java/com/securechat/service/KeyShardService.java
backend/src/main/resources/db/migration/V2__add_expiry_documents_features.sql
```

### Modified Files
```
backend/src/main/resources/application.yml          — port 5433, radius 100km
backend/src/main/java/com/securechat/model/Message.java — new columns
backend/src/main/java/com/securechat/model/User.java    — lastSeen
backend/src/main/java/com/securechat/service/MessageService.java — key sharding, docs, burn
backend/src/main/java/com/securechat/service/GeoLocationService.java — single check, distance response
backend/src/main/java/com/securechat/service/SteganographyService.java — minor tweaks
backend/src/main/java/com/securechat/controller/MessageController.java — new fields, file endpoint
backend/src/main/java/com/securechat/controller/LocationController.java — return shard
backend/src/main/java/com/securechat/controller/dto/RegisterRequest.java — unchanged
backend/src/main/java/com/securechat/security/JwtFilter.java — update lastSeen
backend/src/main/java/com/securechat/config/SecurityConfig.java — permit new endpoints
frontend/index.html   — complete rewrite (Hyprland theme)
frontend/style.css    — complete rewrite (dark theme)
frontend/app.js       — major rewrite (noise gen, key sharding, doc upload, new UI logic)
docker-compose.yml    — not used for local, kept for reference
```

### New Local-Only Files (gitignored)
```
LOCAL_PROJECT_DOCS.md  — comprehensive project documentation
```

---

## 11. Git Commit Strategy

Human-readable commits, no conventional commit prefixes, no AI attribution:

1. `set up postgres docker container on port 5433 for local dev`
2. `add message expiry with key sharding and 30min TTL`
3. `fix geo-fencing to use single server-side haversine check`
4. `add encrypted document upload and download support`
5. `add burn-after-read, unread badges, online status, copy protection`
6. `overhaul frontend with hyprland-inspired dark theme`
7. `generate noise carrier images automatically for steganography`
8. `add local project documentation`

---

## 12. Non-Goals (Explicitly Out of Scope)

- WebSocket / real-time notifications (future work)
- Group messaging
- User search / contacts list
- Profile pictures
- SSL/TLS setup (local dev only)
- Production deployment configuration
