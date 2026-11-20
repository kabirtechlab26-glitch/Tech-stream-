# Tech Live — Multi-Platform Live Streaming SaaS Architecture

## 1. System Topology

```
+-----------------------------------+
|  Android App (Kotlin + Compose)   |
|  - CameraX / MediaProjection      |
|  - MediaCodec H.264/AAC + RTMP(S) |
|  - Credential Manager + Firebase  |
|  - Resumable Multipart Uploader   |
|  - Community Tutorial Q&A         |
+-----------------+-----------------+
                  |
      HTTPS / REST + Firebase Sync
                  v
+-----------------------------------+        +-----------------------------+
|  Backend API (Node.js/TypeScript) |<------>|  PostgreSQL (19+ Tables)    |
|  - Auth & RBAC (USER/ADMIN/SUPER) |        |  + Cloud Firestore (Realtime) |
|  - Payment Gateway & Verification |        +-----------------------------+
|  - YouTube/Meta/TikTok OAuth APIs |
|  - AES-256-GCM Stream Key Vault   |
+--------+-----------------+--------+
         |                 |
         v                 v
+-----------------+  +-------------------------------------------+
| S3 / R2 Object  |  | Redis + BullMQ Job Queue                  |
| Video Storage   |  | - FFprobe Video Validation & Thumbnails   |
| (1 GB Resumable)|  | - Scheduled & Recurring Stream Dispatcher |
+--------+--------+  +---------------------+---------------------+
         |                                 |
         +----------------+----------------+
                          v
+---------------------------------------------------------+
| Cloud Streaming Workers (FFmpeg + 7-Day Supervisor)     |
| - Server-side Looping (1x, 2x, 5x, 10x, Unlimited)      |
| - 16:9 / 9:16 Aspect Modes (Fit/Crop/Stretch/Blur Bg)   |
| - Multi-Destination Parallel RTMP/RTMPS Output          |
| - Crash Detection + Exponential Backoff Auto-Reconnect  |
+-------------------------+-------------------------------+
                          | RTMP / RTMPS
                          v
     YouTube Live  |  Facebook Live  |  TikTok  |  Telegram  |  Custom RTMP(S)
```

## 2. Responsibility Separation

| Layer | Responsibilities |
| :--- | :--- |
| **Android App (`/app`)** | Google Sign-In (Credential Manager), real-time CameraX + MediaProjection screen capture, camera PIP overlay, mic + Android 10+ device audio capture, hardware MediaCodec H.264/AAC encoding, direct RTMP/RTMPS live publishing, resumable chunked video upload, 3-hour daily free quota guard, community Q&A, analytics, payment submission, and role-gated Admin Console. |
| **Backend API (`/backend`)** | Bearer JWT/Firebase token verification, RBAC (`USER`, `ADMIN`, `SUPER_ADMIN`), dynamic plan & limit settings, AES-256-GCM encryption at rest for stream keys & OAuth tokens, YouTube/Meta/TikTok/Telegram OAuth & capability verification, S3 multipart upload orchestration, bKash/Nagad/Card payment verification, real balance calculation, and audit logs. |
| **Cloud Workers (`/worker`)** | FFprobe codec/resolution/FPS/duration validation, thumbnail extraction, 7-day (604,800s) continuous cloud streaming independent of the Android phone, server-side looping, aspect-ratio conversion, crash recovery with exponential backoff, and CPU/RAM/network telemetry. |
| **Database (`/database/schema.sql` & `firestore.rules`)** | Authoritative relational PostgreSQL schema + Zero-Trust Firestore collections with strict per-user isolation and admin-only payment/withdrawal/settings enforcement. |
| **Web Admin Dashboard (`/admin-web`)** | Standalone web admin console covering Dashboard, Users, Streams, Videos, Payments, Subscriptions, Revenue, Withdrawals, Servers, Reports, and System Settings. |

## 3. Required Environment Configuration (Backend Only)

All secret credentials live strictly in `/backend/.env` (see `/backend/.env.example`), **never** in the Android APK.

- `DATABASE_URL`, `REDIS_URL`, `STREAM_KEY_ENCRYPTION_KEY` (32-byte hex)
- `S3_BUCKET`, `S3_ENDPOINT`, `S3_ACCESS_KEY_ID`, `S3_SECRET_ACCESS_KEY`
- `YOUTUBE_CLIENT_ID`, `YOUTUBE_CLIENT_SECRET`, `YOUTUBE_REDIRECT_URI`
- `META_APP_ID`, `META_APP_SECRET`, `META_REDIRECT_URI`
- `TIKTOK_CLIENT_KEY`, `TIKTOK_CLIENT_SECRET`
- `BKASH_APP_KEY`, `BKASH_APP_SECRET`, `BKASH_USERNAME`, `BKASH_PASSWORD`
- `NAGAD_MERCHANT_ID`, `NAGAD_PRIVATE_KEY`
- `STRIPE_SECRET_KEY`, `STRIPE_WEBHOOK_SECRET`
- `FIREBASE_SERVICE_ACCOUNT_JSON` (for ID token verification and FCM push notifications)
