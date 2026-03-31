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
