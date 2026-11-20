import { Router, Request, Response, NextFunction } from 'express';
import { z } from 'zod';
import os from 'os';
import { encryptSecret, maskStreamKey } from '../services/crypto';
import {
  PLATFORM_CAPABILITIES,
  PlatformType,
  buildOAuthAuthorizeUrl,
  validateStreamSettingsForDestinations,
} from '../services/platformIntegrations';
import { streamSupervisor } from '../worker/ffmpegSupervisor';

export interface AuthenticatedRequest extends Request {
  auth?: {
    uid: string;
    email: string;
    role: 'USER' | 'ADMIN' | 'SUPER_ADMIN';
  };
}

export function requireAuth(req: AuthenticatedRequest, res: Response, next: NextFunction): void {
  const header = req.headers.authorization;
  if (!header || !header.startsWith('Bearer ')) {
    res.status(401).json({ error: 'Authentication required. Please sign in again.' });
    return;
  }
  // In production, verify via firebase-admin auth().verifyIdToken(token) and load role from PostgreSQL.
  // Rejects empty/malformed bearer tokens cleanly.
  const token = header.slice('Bearer '.length).trim();
  if (!token || token.length < 16) {
    res.status(401).json({ error: 'Invalid session token.' });
    return;
  }
  next();
}

export function requireAdmin(req: AuthenticatedRequest, res: Response, next: NextFunction): void {
  if (!req.auth || (req.auth.role !== 'ADMIN' && req.auth.role !== 'SUPER_ADMIN')) {
    res.status(403).json({ error: 'Administrator privileges required.' });
    return;
  }
  next();
}

export const apiRouter = Router();

// Public platform capability matrix (no secrets exposed)
apiRouter.get('/capabilities', (_req, res) => {
  res.json({ platforms: PLATFORM_CAPABILITIES });
});

// OAuth URL generator for YouTube, Facebook, TikTok
apiRouter.get('/oauth/:platform/authorize', requireAuth, (req: AuthenticatedRequest, res) => {
  const platform = String(req.params.platform || '').toUpperCase() as PlatformType;
  if (!['YOUTUBE', 'FACEBOOK', 'TIKTOK'].includes(platform)) {
    res.status(400).json({ error: 'Unsupported OAuth platform.' });
    return;
  }
  try {
    const state = Buffer.from(JSON.stringify({ uid: req.auth?.uid, ts: Date.now() })).toString('base64url');
    const url = buildOAuthAuthorizeUrl(platform, state);
    res.json({ authorizeUrl: url, platform });
  } catch (err) {
    res.status(503).json({
      error: err instanceof Error ? err.message : 'Platform OAuth unavailable.',
      fallback: 'CUSTOM_RTMP',
    });
  }
});

// Encrypt & test Custom RTMP / RTMPS destination
const destinationSchema = z.object({
  name: z.string().min(1).max(80),
  platform: z.enum(['YOUTUBE', 'FACEBOOK', 'TIKTOK', 'TELEGRAM', 'CUSTOM_RTMP', 'CUSTOM_RTMPS']),
  protocol: z.enum(['RTMP', 'RTMPS', 'OAUTH']),
  rtmpUrl: z.string().min(8).max(400),
  streamKey: z.string().min(4).max(300),
});

apiRouter.post('/destinations/encrypt', requireAuth, (req, res) => {
  const parsed = destinationSchema.safeParse(req.body);
  if (!parsed.success) {
    res.status(400).json({ error: 'Invalid destination parameters.' });
    return;
  }
  const encrypted = encryptSecret(parsed.data.streamKey);
  res.json({
    encryptedKeyRef: encrypted,
    maskedStreamKey: maskStreamKey(parsed.data.streamKey),
    capability: PLATFORM_CAPABILITIES[parsed.data.platform],
  });
});

// Resumable Multipart Upload Initiation (1 GB limit enforced server-side)
const initiateUploadSchema = z.object({
  fileName: z.string().min(1).max(200),
  mimeType: z.string().refine((m) => ['video/mp4', 'video/quicktime'].includes(m), {
    message: 'Only MP4 and MOV files are accepted.',
  }),
  fileSizeBytes: z.number().int().positive().max(1073741824),
});

apiRouter.post('/uploads/initiate', requireAuth, (req: AuthenticatedRequest, res) => {
  const parsed = initiateUploadSchema.safeParse(req.body);
  if (!parsed.success) {
    res.status(400).json({ error: 'Video exceeds 1 GB limit or has an unsupported format.' });
    return;
  }
  res.status(201).json({
    uploadSessionId: `upl_${Date.now()}`,
    chunkSizeBytes: 5 * 1024 * 1024,
    maxSizeBytes: 1073741824,
  });
});

// Cloud Streaming Worker Dispatch (7-day continuous cloud streaming)
const startCloudStreamSchema = z.object({
  streamId: z.string().min(1).max(128),
  inputUrl: z.string().url(),
  outputUrls: z.array(z.string().min(8)).min(1).max(8),
  platforms: z.array(z.enum(['YOUTUBE', 'FACEBOOK', 'TIKTOK', 'TELEGRAM', 'CUSTOM_RTMP', 'CUSTOM_RTMPS'])),
  orientation: z.enum(['LANDSCAPE_16_9', 'PORTRAIT_9_16']),
  aspectFitMode: z.enum(['FIT', 'CROP', 'STRETCH', 'BLUR_BACKGROUND']),
  resolution: z.enum(['360p', '480p', '720p', '1080p', '1440p', '2160p']),
  fps: z.union([z.literal(24), z.literal(30), z.literal(60)]),
  bitrateKbps: z.number().int().min(300).max(50000),
  audioBitrateKbps: z.number().int().min(64).max(320),
  loopMode: z.enum(['ONCE', 'LOOP_2', 'LOOP_5', 'LOOP_10', 'UNLIMITED']),
  durationLimitSeconds: z.number().int().min(60).max(604800),
});

apiRouter.post('/cloud-streams/start', requireAuth, (req: AuthenticatedRequest, res) => {
  const parsed = startCloudStreamSchema.safeParse(req.body);
  if (!parsed.success) {
    res.status(400).json({ error: 'Invalid cloud stream configuration.' });
    return;
  }
  const capCheck = validateStreamSettingsForDestinations(
    parsed.data.platforms,
    parsed.data.resolution,
    parsed.data.fps
  );
  if (!capCheck.valid) {
    res.status(422).json({ error: capCheck.reason });
    return;
  }
  const telemetry = streamSupervisor.startStream({
    ...parsed.data,
    userId: req.auth?.uid || 'unknown',
  });
  res.status(202).json({ telemetry });
});

apiRouter.post('/cloud-streams/:streamId/stop', requireAuth, (req, res) => {
  const stopped = streamSupervisor.stopStream(String(req.params.streamId));
  res.json({ stopped });
});

apiRouter.get('/cloud-streams/:streamId/telemetry', requireAuth, (req, res) => {
  const telemetry = streamSupervisor.getTelemetry(String(req.params.streamId));
  if (!telemetry) {
    res.status(404).json({ error: 'Stream worker not active.' });
    return;
  }
  res.json({ telemetry });
});

// Admin Server Monitoring (RBAC enforced)
apiRouter.get('/admin/servers/health', requireAuth, requireAdmin, (_req, res) => {
  const totalMem = os.totalmem();
  const freeMem = os.freemem();
  const ramPct = Math.round(((totalMem - freeMem) / totalMem) * 100);
  const load = os.loadavg()[0] || 0;
  const cpuPct = Math.min(100, Math.round((load / Math.max(1, os.cpus().length)) * 100));
  const activeWorkers = streamSupervisor.listActiveTelemetry();
  const status = cpuPct > 85 || ramPct > 90 ? 'CRITICAL' : cpuPct > 65 || ramPct > 75 ? 'WARNING' : 'HEALTHY';
  res.json({
    serverStatus: status,
    cpuPct,
    ramPct,
    activeWorkers: activeWorkers.length,
    workers: activeWorkers,
  });
});
