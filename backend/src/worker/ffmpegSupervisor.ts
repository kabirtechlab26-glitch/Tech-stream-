import { spawn, ChildProcess } from 'child_process';
import os from 'os';

export type AspectFitMode = 'FIT' | 'CROP' | 'STRETCH' | 'BLUR_BACKGROUND';
export type OrientationMode = 'LANDSCAPE_16_9' | 'PORTRAIT_9_16';
export type LoopMode = 'ONCE' | 'LOOP_2' | 'LOOP_5' | 'LOOP_10' | 'UNLIMITED';

export interface CloudStreamJobConfig {
  streamId: string;
  userId: string;
  inputUrl: string; // Signed S3/R2 object URL or local staged media path
  outputUrls: string[]; // Decrypted RTMP / RTMPS ingest URLs
  orientation: OrientationMode;
  aspectFitMode: AspectFitMode;
  resolution: string;
  fps: number;
  bitrateKbps: number;
  audioBitrateKbps: number;
  loopMode: LoopMode;
  durationLimitSeconds: number; // Up to 604800 (7 days)
}

export interface WorkerTelemetry {
  streamId: string;
  status: 'STARTING' | 'RUNNING' | 'RECONNECTING' | 'STOPPED' | 'COMPLETED' | 'FAILED';
  elapsedSeconds: number;
  reconnectAttempts: number;
  cpuPct: number;
  ramPct: number;
  lastError?: string;
}

const MAX_RECONNECT_ATTEMPTS = 12;
const BASE_BACKOFF_MS = 2000;
const MAX_BACKOFF_MS = 60000;

function resolveTargetDimensions(resolution: string, orientation: OrientationMode): [number, number] {
  const heights: Record<string, number> = {
    '360p': 360,
    '480p': 480,
    '720p': 720,
    '1080p': 1080,
    '1440p': 1440,
    '2160p': 2160,
  };
  const h = heights[resolution] || 1080;
  const w = Math.round((h * 16) / 9);
  const evenW = w % 2 === 0 ? w : w + 1;
  const evenH = h % 2 === 0 ? h : h + 1;
  return orientation === 'LANDSCAPE_16_9' ? [evenW, evenH] : [evenH, evenW];
}

export function buildAspectFilterGraph(
  width: number,
  height: number,
  mode: AspectFitMode
): string {
  switch (mode) {
    case 'FIT':
      return `scale=${width}:${height}:force_original_aspect_ratio=decrease,pad=${width}:${height}:(ow-iw)/2:(oh-ih)/2:black`;
    case 'CROP':
      return `scale=${width}:${height}:force_original_aspect_ratio=increase,crop=${width}:${height}`;
    case 'STRETCH':
      return `scale=${width}:${height}`;
    case 'BLUR_BACKGROUND':
      return `split[fg][bg];[bg]scale=${width}:${height}:force_original_aspect_ratio=increase,crop=${width}:${height},boxblur=20:5[blurred];[fg]scale=${width}:${height}:force_original_aspect_ratio=decrease[scaled];[blurred][scaled]overlay=(W-w)/2:(H-h)/2`;
  }
}

export function buildFfmpegArgs(config: CloudStreamJobConfig, remainingSeconds: number): string[] {
  const [width, height] = resolveTargetDimensions(config.resolution, config.orientation);
  const loopMap: Record<LoopMode, string> = {
    ONCE: '0',
    LOOP_2: '1',
    LOOP_5: '4',
    LOOP_10: '9',
    UNLIMITED: '-1',
  };

  const args: string[] = [
    '-re',
    '-stream_loop',
    loopMap[config.loopMode],
    '-i',
    config.inputUrl,
    '-t',
    String(Math.min(remainingSeconds, 604800)),
    '-vf',
    buildAspectFilterGraph(width, height, config.aspectFitMode),
    '-c:v',
    'libx264',
    '-preset',
    'veryfast',
    '-tune',
    'zerolatency',
    '-r',
    String(config.fps),
    '-g',
    String(config.fps * 2),
    '-b:v',
    `${config.bitrateKbps}k`,
    '-maxrate',
    `${Math.round(config.bitrateKbps * 1.15)}k`,
    '-bufsize',
    `${config.bitrateKbps * 2}k`,
    '-pix_fmt',
    'yuv420p',
    '-c:a',
    'aac',
    '-b:a',
    `${config.audioBitrateKbps}k`,
    '-ar',
    '44100',
  ];

  if (config.outputUrls.length === 1) {
    args.push('-f', 'flv', config.outputUrls[0]);
  } else {
    // Parallel multi-destination RTMP/RTMPS tee muxer
    const teeTargets = config.outputUrls
      .map((url) => `[f=flv:onfail=ignore]${url}`)
      .join('|');
    args.push('-f', 'tee', '-map', '0:v', '-map', '0:a?', teeTargets);
  }
  return args;
}

export class CloudStreamSupervisor {
  private activeJobs = new Map<
    string,
    {
      config: CloudStreamJobConfig;
      process: ChildProcess | null;
      startedAtMs: number;
      reconnectAttempts: number;
      stoppedByUser: boolean;
      telemetry: WorkerTelemetry;
    }
  >();

  public startStream(config: CloudStreamJobConfig): WorkerTelemetry {
    if (this.activeJobs.has(config.streamId)) {
      return this.activeJobs.get(config.streamId)!.telemetry;
    }

    const jobState = {
      config,
      process: null as ChildProcess | null,
      startedAtMs: Date.now(),
      reconnectAttempts: 0,
      stoppedByUser: false,
      telemetry: {
        streamId: config.streamId,
        status: 'STARTING' as const,
        elapsedSeconds: 0,
        reconnectAttempts: 0,
        cpuPct: 0,
        ramPct: 0,
      },
    };
    this.activeJobs.set(config.streamId, jobState);
    this.spawnWorkerProcess(config.streamId);
    return jobState.telemetry;
  }

  public stopStream(streamId: string): boolean {
    const job = this.activeJobs.get(streamId);
    if (!job) return false;
    job.stoppedByUser = true;
    job.telemetry.status = 'STOPPED';
    job.process?.kill('SIGTERM');
    this.activeJobs.delete(streamId);
    return true;
  }

  public getTelemetry(streamId: string): WorkerTelemetry | null {
    const job = this.activeJobs.get(streamId);
    if (!job) return null;
    const totalMem = os.totalmem();
    const usedMemPct = Math.round(((totalMem - os.freemem()) / totalMem) * 100);
    const load = os.loadavg()[0] || 0;
    const cpuPct = Math.min(100, Math.round((load / Math.max(1, os.cpus().length)) * 100));
    job.telemetry.elapsedSeconds = Math.floor((Date.now() - job.startedAtMs) / 1000);
    job.telemetry.cpuPct = cpuPct;
    job.telemetry.ramPct = usedMemPct;
    return { ...job.telemetry };
  }

  public listActiveTelemetry(): WorkerTelemetry[] {
    return Array.from(this.activeJobs.keys())
      .map((id) => this.getTelemetry(id))
      .filter((t): t is WorkerTelemetry => t !== null);
  }

  private spawnWorkerProcess(streamId: string): void {
    const job = this.activeJobs.get(streamId);
    if (!job || job.stoppedByUser) return;

    const elapsedSeconds = Math.floor((Date.now() - job.startedAtMs) / 1000);
    const remainingSeconds = Math.max(0, job.config.durationLimitSeconds - elapsedSeconds);
    if (remainingSeconds <= 0) {
      job.telemetry.status = 'COMPLETED';
      this.activeJobs.delete(streamId);
      return;
    }

    const args = buildFfmpegArgs(job.config, remainingSeconds);
    const child = spawn('ffmpeg', args, { stdio: ['ignore', 'pipe', 'pipe'] });
    job.process = child;
    job.telemetry.status = 'RUNNING';

    child.stderr.on('data', (chunk: Buffer) => {
      const line = chunk.toString('utf8').trim();
      if (line.toLowerCase().includes('error') || line.toLowerCase().includes('failed')) {
        job.telemetry.lastError = line.slice(0, 240);
      }
    });

    child.on('exit', (code) => {
      if (job.stoppedByUser) return;
      const nowElapsed = Math.floor((Date.now() - job.startedAtMs) / 1000);
      if (nowElapsed >= job.config.durationLimitSeconds || code === 0) {
        job.telemetry.status = 'COMPLETED';
        this.activeJobs.delete(streamId);
        return;
      }

      if (job.reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
        job.telemetry.status = 'FAILED';
        job.telemetry.lastError = 'Exceeded maximum automatic reconnect attempts.';
        return;
      }

      job.reconnectAttempts += 1;
      job.telemetry.reconnectAttempts = job.reconnectAttempts;
      job.telemetry.status = 'RECONNECTING';

      const delayMs = Math.min(
        MAX_BACKOFF_MS,
        BASE_BACKOFF_MS * Math.pow(2, job.reconnectAttempts - 1)
      );
      setTimeout(() => this.spawnWorkerProcess(streamId), delayMs);
    });
  }
}

export const streamSupervisor = new CloudStreamSupervisor();
