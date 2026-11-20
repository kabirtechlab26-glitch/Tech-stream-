-- Tech Live SaaS Multi-Platform Live Streaming PostgreSQL Schema
-- Covers all 19 core operational tables + tutorial Q&A with strict foreign keys, constraints, and indexes.

CREATE EXTENSION IF NOT EXISTS "pgcrypto";

CREATE TABLE roles (
  id SERIAL PRIMARY KEY,
  name VARCHAR(32) UNIQUE NOT NULL CHECK (name IN ('USER', 'ADMIN', 'SUPER_ADMIN')),
  description TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

INSERT INTO roles (name, description) VALUES
  ('USER', 'Standard creator/streamer role'),
  ('ADMIN', 'Platform administrator with payment, user, and stream moderation access'),
  ('SUPER_ADMIN', 'Full infrastructure, withdrawal, and system settings authority')
ON CONFLICT (name) DO NOTHING;

CREATE TABLE users (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  firebase_uid VARCHAR(128) UNIQUE NOT NULL,
  name VARCHAR(120) NOT NULL,
  email VARCHAR(200) UNIQUE NOT NULL,
  email_verified BOOLEAN NOT NULL DEFAULT FALSE,
  photo_url TEXT,
  role_id INTEGER NOT NULL REFERENCES roles(id),
  account_status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE' CHECK (account_status IN ('ACTIVE', 'SUSPENDED', 'BANNED')),
  terms_accepted BOOLEAN NOT NULL DEFAULT FALSE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_users_firebase_uid ON users(firebase_uid);
CREATE INDEX idx_users_status ON users(account_status);

CREATE TABLE subscription_plans (
  id VARCHAR(32) PRIMARY KEY CHECK (id IN ('FREE', 'MONTHLY', 'LIFETIME')),
  display_name VARCHAR(64) NOT NULL,
  price_bdt INTEGER NOT NULL CHECK (price_bdt >= 0),
  currency VARCHAR(8) NOT NULL DEFAULT 'BDT',
  daily_live_limit_seconds INTEGER, -- NULL = unlimited subject to fair use
  storage_limit_mb INTEGER NOT NULL,
  max_upload_size_mb INTEGER NOT NULL,
  max_cloud_duration_hours INTEGER NOT NULL,
  max_concurrent_streams INTEGER NOT NULL,
  allows_prerecorded BOOLEAN NOT NULL DEFAULT FALSE,
  allows_cloud_stream BOOLEAN NOT NULL DEFAULT FALSE,
  allows_looping BOOLEAN NOT NULL DEFAULT FALSE,
  allows_scheduling BOOLEAN NOT NULL DEFAULT FALSE,
  allows_multi_destination BOOLEAN NOT NULL DEFAULT FALSE,
  allows_custom_rtmp BOOLEAN NOT NULL DEFAULT FALSE,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

INSERT INTO subscription_plans (
  id, display_name, price_bdt, daily_live_limit_seconds, storage_limit_mb, max_upload_size_mb,
  max_cloud_duration_hours, max_concurrent_streams, allows_prerecorded, allows_cloud_stream,
  allows_looping, allows_scheduling, allows_multi_destination, allows_custom_rtmp
) VALUES
  ('FREE', 'Free Starter', 0, 10800, 500, 0, 0, 1, FALSE, FALSE, FALSE, FALSE, FALSE, FALSE),
  ('MONTHLY', 'Monthly Pro', 100, NULL, 10240, 1024, 168, 3, TRUE, TRUE, TRUE, TRUE, TRUE, TRUE),
  ('LIFETIME', 'Lifetime Studio', 1500, NULL, 51200, 1024, 168, 5, TRUE, TRUE, TRUE, TRUE, TRUE, TRUE)
ON CONFLICT (id) DO NOTHING;

CREATE TABLE subscriptions (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  plan_id VARCHAR(32) NOT NULL REFERENCES subscription_plans(id),
  status VARCHAR(24) NOT NULL CHECK (status IN ('ACTIVE', 'EXPIRED', 'PENDING', 'CANCELLED')),
  start_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  expiry_at TIMESTAMPTZ,
  daily_live_used_seconds INTEGER NOT NULL DEFAULT 0,
  daily_usage_date DATE NOT NULL DEFAULT CURRENT_DATE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_subscriptions_user ON subscriptions(user_id);

CREATE TABLE payment_methods (
  id VARCHAR(32) PRIMARY KEY CHECK (id IN ('BKASH', 'NAGAD', 'CARD')),
  display_name VARCHAR(64) NOT NULL,
  gateway_mode VARCHAR(24) NOT NULL CHECK (gateway_mode IN ('AUTOMATIC', 'MANUAL', 'HYBRID')),
  merchant_account_label VARCHAR(128),
  is_enabled BOOLEAN NOT NULL DEFAULT TRUE
);

INSERT INTO payment_methods (id, display_name, gateway_mode, merchant_account_label) VALUES
  ('BKASH', 'bKash', 'HYBRID', 'Merchant Wallet via Backend Env'),
  ('NAGAD', 'Nagad', 'HYBRID', 'Merchant Wallet via Backend Env'),
  ('CARD', 'Debit / Credit Card', 'AUTOMATIC', 'Gateway Webhook Processor')
ON CONFLICT (id) DO NOTHING;

CREATE TABLE payments (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  plan_id VARCHAR(32) NOT NULL REFERENCES subscription_plans(id),
  payment_method_id VARCHAR(32) NOT NULL REFERENCES payment_methods(id),
  amount_bdt INTEGER NOT NULL CHECK (amount_bdt > 0),
  currency VARCHAR(8) NOT NULL DEFAULT 'BDT',
  transaction_reference VARCHAR(128) UNIQUE NOT NULL,
  gateway_mode VARCHAR(24) NOT NULL CHECK (gateway_mode IN ('AUTOMATIC', 'MANUAL')),
  status VARCHAR(24) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'PAID', 'FAILED', 'REJECTED', 'REFUNDED')),
  admin_note TEXT,
  verified_by UUID REFERENCES users(id),
  verified_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_payments_user_status ON payments(user_id, status);

CREATE TABLE withdrawals (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  admin_id UUID NOT NULL REFERENCES users(id),
  amount_bdt INTEGER NOT NULL CHECK (amount_bdt >= 100),
  method VARCHAR(64) NOT NULL,
  destination VARCHAR(200) NOT NULL,
  status VARCHAR(24) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'REJECTED')),
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE storage_usage (
  user_id UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
  used_bytes BIGINT NOT NULL DEFAULT 0 CHECK (used_bytes >= 0),
  limit_bytes BIGINT NOT NULL DEFAULT 524288000,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE videos (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  title VARCHAR(140) NOT NULL,
  file_name VARCHAR(200) NOT NULL,
  mime_type VARCHAR(80) NOT NULL,
  file_size_bytes BIGINT NOT NULL CHECK (file_size_bytes > 0 AND file_size_bytes <= 1073741824),
  duration_seconds INTEGER NOT NULL DEFAULT 0,
  resolution VARCHAR(24),
  fps INTEGER,
  video_codec VARCHAR(40),
  audio_codec VARCHAR(40),
  s3_object_key TEXT NOT NULL,
  s3_upload_id TEXT,
  thumbnail_url TEXT,
  status VARCHAR(32) NOT NULL DEFAULT 'UPLOADING' CHECK (
    status IN ('UPLOADING', 'PROCESSING', 'VALIDATING', 'GENERATING_THUMBNAIL', 'PREPARING_STREAM', 'READY', 'FAILED')
  ),
  error_message TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_videos_user_status ON videos(user_id, status);

CREATE TABLE video_processing_jobs (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  video_id UUID NOT NULL REFERENCES videos(id) ON DELETE CASCADE,
  worker_node VARCHAR(120),
  stage VARCHAR(40) NOT NULL,
  progress_pct INTEGER NOT NULL DEFAULT 0 CHECK (progress_pct BETWEEN 0 AND 100),
  ffprobe_metadata JSONB,
  error_log TEXT,
  started_at TIMESTAMPTZ,
  completed_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE stream_destinations (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  name VARCHAR(80) NOT NULL,
  platform VARCHAR(32) NOT NULL CHECK (platform IN ('YOUTUBE', 'FACEBOOK', 'TIKTOK', 'TELEGRAM', 'CUSTOM_RTMP', 'CUSTOM_RTMPS')),
  protocol VARCHAR(16) NOT NULL CHECK (protocol IN ('RTMP', 'RTMPS', 'OAUTH')),
  rtmp_url VARCHAR(400) NOT NULL,
  encrypted_stream_key TEXT NOT NULL, -- AES-256-GCM ciphertext
  encrypted_oauth_token TEXT,
  channel_name VARCHAR(120),
  connection_status VARCHAR(24) NOT NULL DEFAULT 'CONNECTED',
  max_resolution VARCHAR(16) NOT NULL DEFAULT '1080p',
  max_fps INTEGER NOT NULL DEFAULT 60,
  supports_60_fps BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_destinations_user ON stream_destinations(user_id);

CREATE TABLE stream_presets (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  name VARCHAR(80) NOT NULL,
  resolution VARCHAR(16) NOT NULL,
  fps INTEGER NOT NULL CHECK (fps IN (24, 30, 60)),
  bitrate_kbps INTEGER NOT NULL,
  audio_bitrate_kbps INTEGER NOT NULL,
  orientation VARCHAR(24) NOT NULL CHECK (orientation IN ('LANDSCAPE_16_9', 'PORTRAIT_9_16')),
  aspect_fit_mode VARCHAR(24) NOT NULL CHECK (aspect_fit_mode IN ('FIT', 'CROP', 'STRETCH', 'BLUR_BACKGROUND')),
  destination_ids UUID[] NOT NULL DEFAULT '{}',
  microphone_enabled BOOLEAN NOT NULL DEFAULT TRUE,
  camera_enabled BOOLEAN NOT NULL DEFAULT TRUE,
  encoder_profile VARCHAR(64) NOT NULL DEFAULT 'H264_HIGH',
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE streams (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  title VARCHAR(140) NOT NULL,
  description TEXT,
  source_type VARCHAR(24) NOT NULL CHECK (source_type IN ('CAMERA', 'SCREEN', 'PRERECORDED_CLOUD')),
  video_id UUID REFERENCES videos(id) ON DELETE SET NULL,
  destination_ids UUID[] NOT NULL,
  orientation VARCHAR(24) NOT NULL CHECK (orientation IN ('LANDSCAPE_16_9', 'PORTRAIT_9_16')),
  aspect_fit_mode VARCHAR(24) NOT NULL CHECK (aspect_fit_mode IN ('FIT', 'CROP', 'STRETCH', 'BLUR_BACKGROUND')),
  resolution VARCHAR(16) NOT NULL,
  fps INTEGER NOT NULL CHECK (fps IN (24, 30, 60)),
  bitrate_kbps INTEGER NOT NULL,
  audio_bitrate_kbps INTEGER NOT NULL,
  status VARCHAR(24) NOT NULL CHECK (status IN ('PREPARING', 'RUNNING', 'RECONNECTING', 'STOPPED', 'COMPLETED', 'FAILED', 'SCHEDULED')),
  is_cloud_stream BOOLEAN NOT NULL DEFAULT FALSE,
  duration_limit_seconds INTEGER NOT NULL CHECK (duration_limit_seconds BETWEEN 60 AND 604800),
  loop_mode VARCHAR(24) NOT NULL DEFAULT 'ONCE' CHECK (loop_mode IN ('ONCE', 'LOOP_2', 'LOOP_5', 'LOOP_10', 'UNLIMITED')),
  loop_count INTEGER NOT NULL DEFAULT 0,
  started_at TIMESTAMPTZ,
  ended_at TIMESTAMPTZ,
  elapsed_seconds INTEGER NOT NULL DEFAULT 0,
  bytes_transferred BIGINT NOT NULL DEFAULT 0,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_streams_user_status ON streams(user_id, status);

CREATE TABLE scheduled_streams (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  stream_id UUID NOT NULL REFERENCES streams(id) ON DELETE CASCADE,
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  scheduled_start_at TIMESTAMPTZ NOT NULL,
  recurring_rule VARCHAR(80), -- e.g. 'DAILY', 'WEEKLY', or NULL for one-time
  status VARCHAR(24) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'DISPATCHED', 'CANCELLED', 'FAILED')),
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_scheduled_start ON scheduled_streams(scheduled_start_at, status);

CREATE TABLE stream_workers (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  stream_id UUID NOT NULL REFERENCES streams(id) ON DELETE CASCADE,
  server_node VARCHAR(120) NOT NULL,
  pid INTEGER,
  cpu_pct INTEGER NOT NULL DEFAULT 0,
  ram_pct INTEGER NOT NULL DEFAULT 0,
  network_kbps INTEGER NOT NULL DEFAULT 0,
  dropped_frames_pct NUMERIC(5,2) NOT NULL DEFAULT 0.00,
  reconnect_attempts INTEGER NOT NULL DEFAULT 0,
  status VARCHAR(24) NOT NULL CHECK (status IN ('STARTING', 'RUNNING', 'RECONNECTING', 'CRASHED', 'STOPPED')),
  last_heartbeat_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE stream_logs (
  id BIGSERIAL PRIMARY KEY,
  stream_id UUID NOT NULL REFERENCES streams(id) ON DELETE CASCADE,
  worker_id UUID REFERENCES stream_workers(id) ON DELETE SET NULL,
  level VARCHAR(16) NOT NULL CHECK (level IN ('INFO', 'WARN', 'ERROR')),
  event_type VARCHAR(64) NOT NULL,
  message TEXT NOT NULL,
  technical_detail TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_stream_logs_stream ON stream_logs(stream_id, created_at DESC);

CREATE TABLE notifications (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  title VARCHAR(120) NOT NULL,
  message VARCHAR(400) NOT NULL,
  category VARCHAR(40) NOT NULL,
  is_read BOOLEAN NOT NULL DEFAULT FALSE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_notifications_user ON notifications(user_id, created_at DESC);

CREATE TABLE audit_logs (
  id BIGSERIAL PRIMARY KEY,
  actor_id UUID REFERENCES users(id) ON DELETE SET NULL,
  action VARCHAR(100) NOT NULL,
  target_id VARCHAR(128),
  severity VARCHAR(16) NOT NULL CHECK (severity IN ('INFO', 'WARNING', 'CRITICAL')),
  details TEXT NOT NULL,
  ip_address INET,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE system_settings (
  key VARCHAR(64) PRIMARY KEY,
  value JSONB NOT NULL,
  updated_by UUID REFERENCES users(id),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE tutorial_qa (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  stream_id UUID REFERENCES streams(id) ON DELETE SET NULL,
  topic_tag VARCHAR(40) NOT NULL,
  question_text VARCHAR(500) NOT NULL,
  upvotes INTEGER NOT NULL DEFAULT 0,
  answered BOOLEAN NOT NULL DEFAULT FALSE,
  answer_text VARCHAR(800),
  answered_by VARCHAR(120),
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
