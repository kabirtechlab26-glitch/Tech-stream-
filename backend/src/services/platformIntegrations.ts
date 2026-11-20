import { env } from '../config/env';

export type PlatformType =
  | 'YOUTUBE'
  | 'FACEBOOK'
  | 'TIKTOK'
  | 'TELEGRAM'
  | 'CUSTOM_RTMP'
  | 'CUSTOM_RTMPS';

export interface DestinationCapability {
  platform: PlatformType;
  maxResolution: '720p' | '1080p' | '1440p' | '2160p';
  allowedFps: number[];
  supports60Fps: boolean;
  requiresOAuth: boolean;
  oauthConfigured: boolean;
  directApiAvailable: boolean;
  notes: string;
}

export const PLATFORM_CAPABILITIES: Record<PlatformType, DestinationCapability> = {
  YOUTUBE: {
    platform: 'YOUTUBE',
    maxResolution: '2160p',
    allowedFps: [24, 30, 60],
    supports60Fps: true,
    requiresOAuth: true,
    oauthConfigured: Boolean(env.youtubeClientId && env.youtubeClientSecret),
    directApiAvailable: Boolean(env.youtubeClientId && env.youtubeClientSecret),
    notes: 'Supports up to 4K 60fps via YouTube Data API v3 Live Broadcast & Stream binding.',
  },
  FACEBOOK: {
    platform: 'FACEBOOK',
    maxResolution: '1080p',
    allowedFps: [24, 30, 60],
    supports60Fps: true,
    requiresOAuth: true,
    oauthConfigured: Boolean(env.metaAppId && env.metaAppSecret),
    directApiAvailable: Boolean(env.metaAppId && env.metaAppSecret),
    notes: 'Supports up to 1080p 60fps for eligible Pages/Accounts via Meta Graph Live Video API.',
  },
  TIKTOK: {
    platform: 'TIKTOK',
    maxResolution: '1080p',
    allowedFps: [24, 30, 60],
    supports60Fps: true,
    requiresOAuth: true,
    oauthConfigured: Boolean(env.tiktokClientKey && env.tiktokClientSecret),
    directApiAvailable: Boolean(env.tiktokClientKey && env.tiktokClientSecret),
    notes:
      'Direct TikTok Live API requires approved developer access and account eligibility. Use Custom RTMP/RTMPS when direct API is unavailable.',
  },
  TELEGRAM: {
    platform: 'TELEGRAM',
    maxResolution: '1080p',
    allowedFps: [24, 30],
    supports60Fps: false,
    requiresOAuth: false,
    oauthConfigured: true,
    directApiAvailable: true,
    notes: 'Streams via official Telegram Channel/Group RTMP(S) ingestion URL and stream key.',
  },
  CUSTOM_RTMP: {
    platform: 'CUSTOM_RTMP',
    maxResolution: '2160p',
    allowedFps: [24, 30, 60],
    supports60Fps: true,
    requiresOAuth: false,
    oauthConfigured: true,
    directApiAvailable: true,
    notes: 'Standard unencrypted RTMP ingest; capability depends on target server.',
  },
  CUSTOM_RTMPS: {
    platform: 'CUSTOM_RTMPS',
    maxResolution: '2160p',
    allowedFps: [24, 30, 60],
    supports60Fps: true,
    requiresOAuth: false,
    oauthConfigured: true,
    directApiAvailable: true,
    notes: 'TLS-encrypted RTMPS ingest; capability depends on target server.',
  },
};

export function validateStreamSettingsForDestinations(
  platforms: PlatformType[],
  resolution: string,
  fps: number
): { valid: boolean; reason?: string } {
  const resOrder = ['360p', '480p', '720p', '1080p', '1440p', '2160p'];
  const requestedIdx = resOrder.indexOf(resolution);
  if (requestedIdx === -1) {
    return { valid: false, reason: `Unsupported resolution: ${resolution}` };
  }
  for (const p of platforms) {
    const cap = PLATFORM_CAPABILITIES[p];
    const maxIdx = resOrder.indexOf(cap.maxResolution);
    if (requestedIdx > maxIdx) {
      return {
        valid: false,
        reason: `${p} supports a maximum resolution of ${cap.maxResolution}.`,
      };
    }
    if (!cap.allowedFps.includes(fps)) {
      return {
        valid: false,
        reason: `${p} does not support ${fps} FPS. Allowed: ${cap.allowedFps.join(', ')} FPS.`,
      };
    }
  }
  return { valid: true };
}

export function buildOAuthAuthorizeUrl(platform: PlatformType, state: string): string {
  if (platform === 'YOUTUBE') {
    if (!env.youtubeClientId || !env.youtubeRedirectUri) {
      throw new Error('YouTube OAuth credentials are not configured on the backend.');
    }
    const params = new URLSearchParams({
      client_id: env.youtubeClientId,
      redirect_uri: env.youtubeRedirectUri,
      response_type: 'code',
      access_type: 'offline',
      prompt: 'consent',
      scope: 'https://www.googleapis.com/auth/youtube',
      state,
    });
    return `https://accounts.google.com/o/oauth2/v2/auth?${params.toString()}`;
  }

  if (platform === 'FACEBOOK') {
    if (!env.metaAppId || !env.metaRedirectUri) {
      throw new Error('Meta/Facebook OAuth credentials are not configured on the backend.');
    }
    const params = new URLSearchParams({
      client_id: env.metaAppId,
      redirect_uri: env.metaRedirectUri,
      response_type: 'code',
      scope: 'publish_video,pages_show_list,pages_read_engagement',
      state,
    });
    return `https://www.facebook.com/v20.0/dialog/oauth?${params.toString()}`;
  }

  if (platform === 'TIKTOK') {
    if (!env.tiktokClientKey) {
      throw new Error('Direct TikTok Live API credentials are not approved/configured for this app.');
    }
    const params = new URLSearchParams({
      client_key: env.tiktokClientKey,
      response_type: 'code',
      scope: 'user.info.basic,video.publish',
      state,
    });
    return `https://www.tiktok.com/v2/auth/authorize/?${params.toString()}`;
  }

  throw new Error(`OAuth is not required for ${platform}.`);
}
