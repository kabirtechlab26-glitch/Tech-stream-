import crypto from 'crypto';
import dotenv from 'dotenv';

dotenv.config();

const devFallbackKey = crypto.randomBytes(32).toString('hex');

export const env = {
  port: Number(process.env.PORT || 4000),
  nodeEnv: process.env.NODE_ENV || 'development',
  allowedOrigins: (process.env.ALLOWED_ORIGINS || '').split(',').map((s) => s.trim()).filter(Boolean),
  databaseUrl: process.env.DATABASE_URL || '',
  redisUrl: process.env.REDIS_URL || 'redis://localhost:6379',
  streamKeyEncryptionKey: process.env.STREAM_KEY_ENCRYPTION_KEY || devFallbackKey,
  firebaseProjectId: process.env.FIREBASE_PROJECT_ID || 'gen-lang-client-0835454657',
  s3Bucket: process.env.S3_BUCKET || 'techlive-videos',
  s3Endpoint: process.env.S3_ENDPOINT || '',
  s3Region: process.env.S3_REGION || 'ap-southeast-1',
  s3AccessKeyId: process.env.S3_ACCESS_KEY_ID || '',
  s3SecretAccessKey: process.env.S3_SECRET_ACCESS_KEY || '',
  youtubeClientId: process.env.YOUTUBE_CLIENT_ID || '',
  youtubeClientSecret: process.env.YOUTUBE_CLIENT_SECRET || '',
  youtubeRedirectUri: process.env.YOUTUBE_REDIRECT_URI || '',
  metaAppId: process.env.META_APP_ID || '',
  metaAppSecret: process.env.META_APP_SECRET || '',
  metaRedirectUri: process.env.META_REDIRECT_URI || '',
  tiktokClientKey: process.env.TIKTOK_CLIENT_KEY || '',
  tiktokClientSecret: process.env.TIKTOK_CLIENT_SECRET || '',
  bkashAppKey: process.env.BKASH_APP_KEY || '',
  bkashAppSecret: process.env.BKASH_APP_SECRET || '',
  nagadMerchantId: process.env.NAGAD_MERCHANT_ID || '',
  stripeSecretKey: process.env.STRIPE_SECRET_KEY || '',
};
