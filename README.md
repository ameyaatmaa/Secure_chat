# SecureChat

A location-gated, end-to-end encrypted messaging application. Messages are hidden inside procedurally generated noise images using LSB steganography, locked to geographic coordinates, and self-destruct after 30 minutes through cryptographic key deletion.

---

## How it works

Standard E2E encryption keeps messages private in transit. SecureChat adds two more constraints on top of that — the receiver must be physically present at a location, and the decryption key expires on a timer. After 30 minutes, the server permanently deletes its key shard. At that point, decryption becomes computationally impossible regardless of what an attacker has cached.

**Encryption**: Messages are encrypted with AES-256-GCM. The AES key is split into two 16-byte shards. Shard one is RSA-OAEP encrypted with the receiver's public key. Shard two is held by the server and deleted at expiry.

**Steganography**: The encrypted payload is embedded into a 512x512 procedurally generated noise image using LSB (Least Significant Bit) encoding. To any observer, the transmission looks like a random image file.

**Geo-fencing**: The sender pins a location on a map. The server runs a Haversine calculation against the receiver's GPS coordinates. Shard two is only returned if the receiver is within the configured radius (default 100km).

**Temporal decay**: A scheduler runs every 2 minutes. Any message past its 30-minute TTL has its key shard nulled in the database and its files deleted from disk. The stego image and encrypted document are gone. Without shard two, the AES key cannot be reconstructed.

---

## Features

| Feature | Description |
|---|---|
| End-to-end encryption | AES-256-GCM + RSA-2048 OAEP hybrid encryption |
| Key sharding | AES key split into two halves — neither half alone can decrypt |
| Temporal decay | Server deletes its key shard after 30 minutes |
| LSB steganography | Encrypted payload hidden in RGB channel LSBs of a noise image |
| Geo-fencing | Receiver must be within configured radius to obtain key shard |
| Encrypted documents | Any file type up to 10MB, stored as `.enc` blobs |
| Burn after reading | Server deletes key shard and files after first successful decrypt |
| Deterministic keypairs | Private key is never stored — re-derived from password on every login via PBKDF2 + forge PRNG |
| Online status | Last-seen timestamp updated on every authenticated request |
| Copy protection | `user-select: none`, disabled right-click, tab-switch re-hides message, watermark overlay |

---

## Stack

| Layer | Technology |
|---|---|
| Backend | Spring Boot 3.2.3, Java 21 |
| Database | PostgreSQL 15 |
| Migrations | Flyway |
| Auth | JJWT 0.12.5, BCrypt (cost 12) |
| Server crypto | BouncyCastle 1.77 |
| Client crypto | Web Crypto API (AES-256-GCM, PBKDF2), node-forge (RSA) |
| Frontend | Alpine.js 3.x |
| Maps | Leaflet 1.9.4 |

---

## Running locally

```bash
# Start PostgreSQL (port 5433 to avoid conflicts with system postgres)
docker run -d --name securechat-postgres \
  -e POSTGRES_DB=securechat \
  -e POSTGRES_USER=chatuser \
  -e POSTGRES_PASSWORD=strongpassword \
  -p 5433:5432 postgres:15

# Start the backend
cd backend && mvn spring-boot:run

# Open in browser
# http://localhost:8080
# Use incognito if you run into HSTS redirect issues
```

---

## Architecture

```
Browser (Alpine.js + Web Crypto + forge + Leaflet)
        |
        | HTTP :8080
        v
Spring Boot
  ├── JwtFilter              — extracts JWT from cookie, updates last_seen
  ├── AuthController         — register, login, logout
  ├── MessageController      — send, inbox, decrypt, burn
  ├── LocationController     — Haversine check, returns shard2 on pass
  ├── UserController         — public key + last_seen lookup
  ├── MessageExpiryScheduler — runs every 2min, purges expired messages
  └── SecurityConfig         — stateless JWT, CSRF off
        |
        v
PostgreSQL 15
  ├── users   — id, username, password_hash, public_key, last_seen
  └── messages — encryption keys, file refs, geo coords, expiry flags
```

---

## Encryption flow

### Sending

```
1. Generate 32-byte AES key
   aesKey = crypto.getRandomValues(new Uint8Array(32))

2. Split into shards
   shard1 = aesKey[0..15]
   shard2 = aesKey[16..31]

3. Encrypt message
   iv = random 12 bytes
   ciphertext = AES-256-GCM(plaintext, aesKey, iv)
   payload = iv || ciphertext

4. RSA encrypt shard1
   encryptedShard1 = RSA-OAEP-SHA256(shard1, receiver.publicKey)

5. Generate carrier image + embed payload
   canvas = 512x512 random RGB noise
   stegoImage = LSB_embed(canvas, payload)

6. Upload to server
   stegoImage, encryptedShard1, shard2, receiverUsername, geoLocked, lat, lon, radius
```

### Receiving

```
1. GET /api/messages/{id}
   <- encryptedKey (shard1), keyShard (shard2) if not geo-locked

2. If geo-locked:
   POST /api/location/verify {lat, lon, messageId}
   <- keyShard on pass

3. Reconstruct AES key
   shard1 = RSA-OAEP.decrypt(encryptedKey, privateKey)
   aesKey = shard1 || base64Decode(keyShard)

4. GET /api/messages/{id}/image
   -> draw on canvas, extract LSB payload

5. Decrypt
   iv = payload[0..11]
   plaintext = AES-256-GCM.decrypt(payload[12..], aesKey, iv)

6. If burn-after-read:
   POST /api/messages/{id}/burn
```

---

## Steganography

The payload is embedded in the LSBs of the R, G, B channels of a 512x512 noise image.

```
For each bit in (4-byte length header + payload):
  pixel  = floor(bitIndex / 3)
  channel = bitIndex % 3        // 0=R, 1=G, 2=B
  pixel[channel] = (pixel[channel] & 0xFE) | bit
```

Capacity: 512 x 512 x 3 channels = 786,432 bits = ~96KB max payload.

Embedding happens entirely client-side. Java's `ImageIO.read()` applies sRGB ICC color profile conversion when reading PNG files, shifting pixel values by ±1. That destroys LSB data. By doing steganography in the browser's Canvas API and uploading the pre-embedded PNG, the server stores the bytes without touching them.

---

## Key sharding and expiry

```
AES-256 key (32 bytes):

[ byte0 ... byte15 | byte16 ... byte31 ]
       shard1      |       shard2
  RSA-encrypted    |   stored on server
  for receiver     |   deleted at T+30min
```

The scheduler (`@Scheduled(fixedRate = 120_000)`) finds messages where `expires_at < NOW() AND expired = false`, sets `key_shard = NULL`, deletes the `.png` and `.enc` files from disk, and sets `expired = true`. After this runs, the message cannot be decrypted regardless of what the receiver has cached — the missing 128 bits are gone.

---

## Geo-fencing

The server computes the Haversine distance between the sender's stored coordinates and the receiver's submitted coordinates:

```
a = sin(dlat/2)^2 + cos(lat1) * cos(lat2) * sin(dlon/2)^2
distance = 6371000 * 2 * atan2(sqrt(a), sqrt(1-a))
```

If `distance <= radiusMeters`, the server returns shard2. If not, it returns the actual distance so the receiver knows how far off they are. The client has no authority here — it cannot bypass this check.

If the browser's Geolocation API is unavailable (HTTP, permission denied, timeout), the UI prompts for manual coordinate entry.

---

## Key derivation

The private key is never stored anywhere. On every login:

1. PBKDF2: `password + salt (username:securechat:v1)`, 310,000 iterations, SHA-256 → 256 bits
2. Those bits seed node-forge's PRNG
3. RSA-2048 keypair is generated deterministically (`workers: 0`, synchronous)

`workers: 0` is required. Web workers introduce a race condition that makes the output non-deterministic across runs. The same password must always produce the same keypair or the receiver cannot decrypt old messages.

This takes roughly 15 seconds. The tradeoff is acceptable — the alternative would be storing the private key somewhere, which defeats the purpose.

---

## Database schema

```sql
-- users
id            UUID PRIMARY KEY
username      VARCHAR(50) UNIQUE NOT NULL
password_hash VARCHAR(60) NOT NULL       -- BCrypt cost 12
public_key    TEXT NOT NULL              -- RSA-2048 PEM
created_at    TIMESTAMPTZ DEFAULT NOW()
last_seen     TIMESTAMPTZ

-- messages
id               UUID PRIMARY KEY
sender_id        UUID REFERENCES users(id)
receiver_id      UUID REFERENCES users(id)
image_filename   VARCHAR(255)            -- nullable for documents
encrypted_key    TEXT NOT NULL           -- RSA-encrypted shard1
key_shard        TEXT                    -- shard2, nulled on expiry
sender_lat       DOUBLE PRECISION        -- nullable for non-geo-locked
sender_lon       DOUBLE PRECISION
radius_meters    INTEGER DEFAULT 100000
expires_at       TIMESTAMPTZ
expired          BOOLEAN DEFAULT false
geo_locked       BOOLEAN DEFAULT true
is_document      BOOLEAN DEFAULT false
file_name        VARCHAR(255)
file_type        VARCHAR(100)
file_size        BIGINT
burn_after_read  BOOLEAN DEFAULT false
is_read          BOOLEAN DEFAULT false
created_at       TIMESTAMPTZ DEFAULT NOW()
```

---

## API

| Method | Path | Auth | Notes |
|---|---|---|---|
| POST | `/api/auth/register` | No | `{username, password, publicKey}` |
| POST | `/api/auth/login` | No | Sets HTTP-only JWT cookie |
| POST | `/api/auth/logout` | No | Clears JWT cookie |
| GET | `/api/users/{username}` | Yes | Returns `{publicKey, lastSeen}` |
| POST | `/api/messages/send` | Yes | Multipart — stego image or encrypted document |
| GET | `/api/messages/inbox` | Yes | `{unreadCount, messages[]}` |
| GET | `/api/messages/{id}` | Yes | Metadata + shard2 for non-geo-locked messages |
| GET | `/api/messages/{id}/image` | Yes | PNG stream |
| GET | `/api/messages/{id}/file` | Yes | Encrypted document stream |
| POST | `/api/messages/{id}/burn` | Yes | Deletes shard2 + files permanently |
| POST | `/api/location/verify` | Yes | `{lat, lon, messageId}` → `{valid, distance, keyShard?}` |

---

## Configuration

`backend/src/main/resources/application.yml`

| Property | Default | Notes |
|---|---|---|
| `spring.datasource.url` | `jdbc:postgresql://localhost:5433/securechat` | |
| `server.port` | `8080` | |
| `app.jwt.secret` | `change-this-in-production` | Min 32 chars |
| `app.jwt.expiration-ms` | `86400000` | 24 hours |
| `app.image.storage-path` | `./images` | Stego PNGs and `.enc` files |
| `app.geo.default-radius-meters` | `100000` | 100km |
| `app.message.expiry-minutes` | `30` | TTL before key shard deletion |

---

## Known limitations

- **RSA key generation is slow** — ~15 seconds on first login due to `workers: 0`. The keypair could be cached in sessionStorage (encrypted with a derived key) to avoid re-derivation on the same session.
- **No public key verification** — the client trusts the server to return the correct public key for a recipient. There is no key pinning or out-of-band verification mechanism.
- **Geolocation blocked on HTTP** — falls back to manual coordinate entry.
- **No inbox pagination** — all messages are loaded at once.
- **Local dev only** — HSTS is disabled, cookies are not set to `secure`. Production deployment requires HTTPS and corresponding config changes.
