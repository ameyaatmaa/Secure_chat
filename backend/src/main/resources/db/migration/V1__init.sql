CREATE EXTENSION IF NOT EXISTS "pgcrypto";

CREATE TABLE users (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    username      VARCHAR(50) UNIQUE NOT NULL,
    password_hash VARCHAR(60) NOT NULL,
    public_key    TEXT NOT NULL,
    created_at    TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE messages (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    sender_id        UUID NOT NULL REFERENCES users(id),
    receiver_id      UUID NOT NULL REFERENCES users(id),
    image_filename   VARCHAR(255) NOT NULL,
    encrypted_key    TEXT NOT NULL,
    sender_lat       DOUBLE PRECISION NOT NULL,
    sender_lon       DOUBLE PRECISION NOT NULL,
    radius_meters    INTEGER NOT NULL DEFAULT 50,
    created_at       TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX idx_messages_receiver ON messages(receiver_id);
CREATE INDEX idx_messages_sender ON messages(sender_id);
