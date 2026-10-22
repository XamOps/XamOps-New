/**
 * MediaOps demo fixtures (/api/media-ops/*).
 *
 * Before these existed the demo router had no `/api/media-ops` matcher at all — not even a
 * catch-all — so `routeXamops` returned null, `main.tsx` fell through to the real `fetch`, and
 * every request hit media-ops-service with no session. The page rendered its amber
 * "Could not connect to media-ops-service: Overview fetch failed: 401" banner with all four
 * lists empty.
 *
 * ── Why there is ONE dataset and not one per cloud ──────────────────────────────────────────
 * MediaOps is scoped per workspace, not per cloud account. `MediaOpsController` resolves a
 * `clientId` from the HTTP session and no endpoint accepts an accountId or provider; the
 * frontend sends none either (useMediaOpsData issues four bare GETs), and switching the sidebar
 * account does not even refetch. The product is a self-hosted FFmpeg pipeline that *replaces*
 * AWS Elemental rather than wrapping any cloud's media service — its own copy says "Runs on any
 * EC2, GCE, or on-prem server". So the same tenant dataset is the honest answer for the AWS, GCP
 * and Azure demo accounts, and varying it by provider would imply per-cloud scoping the backend
 * does not have.
 *
 * ── Shape rules that decide whether these render ────────────────────────────────────────────
 * The adapter in useMediaOpsData lowercases every status, so these carry the backend's
 * upper-case values: jobs QUEUED|PROCESSING|PAUSED|COMPLETED|FAILED|CANCELLED, streams
 * LIVE|IDLE|ERROR, files VIDEO|HLS|AUDIO|IMAGE. Anything outside those keys falls back to the
 * "idle" badge. `sourceKey` is set so the name column shows "DRSAKSHI/1.mp4" rather than a
 * presigned query string, and `createdAt`/`completedAt` are ISO strings because `timeAgo` parses
 * them with `new Date()`.
 */

const MINUTE = 60_000;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;

const iso = (msAgo: number) => new Date(Date.now() - msAgo).toISOString();

// ─── /api/media-ops/jobs ─────────────────────────────────────────────────────

/**
 * The one in-flight job's progress advances with wall-clock time.
 *
 * useMediaOpsData re-polls /jobs and /overview every 10s for as long as any job is PROCESSING
 * or QUEUED. A frozen "62%" under a live-looking spinner reads as a stuck worker, so this moves
 * about a point a minute and stops short of 100 — the job stays in flight for the whole demo
 * rather than appearing to finish and then resetting on the next poll.
 */
const LOADED_AT = Date.now();
const liveProgress = () => Math.min(94, 38 + Math.floor((Date.now() - LOADED_AT) / 60_000));

interface JobSeed {
  id: number;
  sourceKey: string;
  outputFormat: string;
  resolution: string;
  bitrate: number;
  fps: number;
  enableAdaptive: boolean;
  status: string;
  progress: number | 'live';
  durationSeconds: number | null;
  createdAgo: number;
  completedAgo: number | null;
  awsEquivalentCost: number | null;
  errorMessage?: string | null;
}

const JOB_SEEDS: JobSeed[] = [
  { id: 4821, sourceKey: 'campaigns/launch-keynote-4k.mov',   outputFormat: 'HLS',  resolution: '1080p', bitrate: 5000, fps: 30, enableAdaptive: true,  status: 'PROCESSING', progress: 'live', durationSeconds: 4284, createdAgo: 18 * MINUTE,  completedAgo: null,          awsEquivalentCost: 64.26 },
  { id: 4820, sourceKey: 'campaigns/product-tour-master.mp4', outputFormat: 'HLS',  resolution: '1080p', bitrate: 5000, fps: 30, enableAdaptive: true,  status: 'QUEUED',     progress: 0,      durationSeconds: 1842, createdAgo: 22 * MINUTE,  completedAgo: null,          awsEquivalentCost: 27.63 },
  { id: 4819, sourceKey: 'webinars/finops-deep-dive.mp4',     outputFormat: 'HLS',  resolution: '720p',  bitrate: 2800, fps: 30, enableAdaptive: true,  status: 'COMPLETED',  progress: 100,    durationSeconds: 3612, createdAgo: 2 * HOUR,     completedAgo: 94 * MINUTE,   awsEquivalentCost: 54.18 },
  { id: 4818, sourceKey: 'webinars/finops-deep-dive.mp4',     outputFormat: 'MP4',  resolution: '1080p', bitrate: 5000, fps: 30, enableAdaptive: false, status: 'COMPLETED',  progress: 100,    durationSeconds: 3612, createdAgo: 2 * HOUR,     completedAgo: 88 * MINUTE,   awsEquivalentCost: 54.18 },
  { id: 4817, sourceKey: 'training/onboarding-module-03.mkv', outputFormat: 'HLS',  resolution: '720p',  bitrate: 2800, fps: 25, enableAdaptive: true,  status: 'COMPLETED',  progress: 100,    durationSeconds: 1284, createdAgo: 5 * HOUR,     completedAgo: 4.6 * HOUR,    awsEquivalentCost: 19.26 },
  { id: 4816, sourceKey: 'training/onboarding-module-02.mkv', outputFormat: 'HLS',  resolution: '720p',  bitrate: 2800, fps: 25, enableAdaptive: true,  status: 'COMPLETED',  progress: 100,    durationSeconds: 1142, createdAgo: 6 * HOUR,     completedAgo: 5.7 * HOUR,    awsEquivalentCost: 17.13 },
  { id: 4815, sourceKey: 'events/townhall-q3-raw.mov',        outputFormat: 'MP4',  resolution: '1080p', bitrate: 6000, fps: 30, enableAdaptive: false, status: 'FAILED',     progress: 41,     durationSeconds: null, createdAgo: 8 * HOUR,     completedAgo: 7.8 * HOUR,    awsEquivalentCost: null,
    errorMessage: 'ffmpeg exited with code 1: moov atom not found — the source file is truncated or still uploading. Re-upload the master and retry.' },
  { id: 4814, sourceKey: 'podcast/ep-114-master.wav',         outputFormat: 'AAC',  resolution: 'audio', bitrate: 192,  fps: 0,  enableAdaptive: false, status: 'COMPLETED',  progress: 100,    durationSeconds: 2946, createdAgo: 11 * HOUR,    completedAgo: 10.9 * HOUR,   awsEquivalentCost: 7.36 },
  { id: 4813, sourceKey: 'campaigns/social-cutdown-15s.mp4',  outputFormat: 'MP4',  resolution: '720p',  bitrate: 2400, fps: 30, enableAdaptive: false, status: 'COMPLETED',  progress: 100,    durationSeconds: 15,   createdAgo: 14 * HOUR,    completedAgo: 14 * HOUR,     awsEquivalentCost: 0.23 },
  { id: 4812, sourceKey: 'events/townhall-q2-archive.mov',    outputFormat: 'HLS',  resolution: '1080p', bitrate: 5000, fps: 30, enableAdaptive: true,  status: 'PAUSED',     progress: 73,     durationSeconds: 5820, createdAgo: 20 * HOUR,    completedAgo: null,          awsEquivalentCost: 87.30 },
  { id: 4811, sourceKey: 'training/onboarding-module-01.mkv', outputFormat: 'HLS',  resolution: '720p',  bitrate: 2800, fps: 25, enableAdaptive: true,  status: 'COMPLETED',  progress: 100,    durationSeconds: 968,  createdAgo: DAY,          completedAgo: DAY - 6 * MINUTE, awsEquivalentCost: 14.52 },
  { id: 4810, sourceKey: 'campaigns/brand-film-master.mov',   outputFormat: 'DASH', resolution: '1080p', bitrate: 5000, fps: 24, enableAdaptive: true,  status: 'COMPLETED',  progress: 100,    durationSeconds: 2184, createdAgo: 2 * DAY,      completedAgo: 2 * DAY - 9 * MINUTE, awsEquivalentCost: 32.76 },
  { id: 4809, sourceKey: 'events/customer-panel-raw.mp4',     outputFormat: 'MP4',  resolution: '480p',  bitrate: 1200, fps: 30, enableAdaptive: false, status: 'CANCELLED',  progress: 12,     durationSeconds: null, createdAgo: 3 * DAY,      completedAgo: 3 * DAY,       awsEquivalentCost: null },
  { id: 4808, sourceKey: 'podcast/ep-113-master.wav',         outputFormat: 'AAC',  resolution: 'audio', bitrate: 192,  fps: 0,  enableAdaptive: false, status: 'COMPLETED',  progress: 100,    durationSeconds: 3120, createdAgo: 4 * DAY,      completedAgo: 4 * DAY,       awsEquivalentCost: 7.80 },
];

const outputBase = (seed: JobSeed) => {
  const stem = seed.sourceKey.replace(/\.[^.]+$/, '');
  return `/media/xamops-media/${stem}`;
};

export const demoMediaOpsJobs = () => JOB_SEEDS.map(seed => {
  const done = seed.status === 'COMPLETED';
  const base = outputBase(seed);
  return {
    id: seed.id,
    // A presigned-looking source, which is what the real payload carries; the name column
    // reads sourceKey instead precisely so the query string never reaches the UI.
    sourceUrl: `https://xammer-media-src.s3.amazonaws.com/${seed.sourceKey}?X-Amz-Expires=3600&X-Amz-Signature=demo`,
    sourceKey: seed.sourceKey,
    outputFormat: seed.outputFormat,
    resolution: seed.resolution,
    bitrate: seed.bitrate,
    fps: seed.fps,
    enableAdaptive: seed.enableAdaptive,
    status: seed.status,
    progress: seed.progress === 'live' ? liveProgress() : seed.progress,
    durationSeconds: seed.durationSeconds,
    outputPath: done ? base : null,
    hlsUrl:  done && seed.outputFormat === 'HLS'  ? `${base}/master.m3u8` : null,
    dashUrl: done && seed.outputFormat === 'DASH' ? `${base}/manifest.mpd` : null,
    videoUrl: done && (seed.outputFormat === 'MP4' || seed.outputFormat === 'AAC')
      ? `${base}.${seed.outputFormat.toLowerCase() === 'aac' ? 'm4a' : 'mp4'}` : null,
    ffmpegCommand: seed.enableAdaptive
      ? `ffmpeg -i "${seed.sourceKey}" -filter_complex "[0:v]split=3[v1][v2][v3];[v1]scale=w=1920:h=1080[v1out];[v2]scale=w=1280:h=720[v2out];[v3]scale=w=854:h=480[v3out]" -map "[v1out]" -b:v:0 ${seed.bitrate}k -map "[v2out]" -b:v:1 2800k -map "[v3out]" -b:v:2 1200k -map a:0 -map a:0 -map a:0 -c:a aac -b:a 128k -f hls -hls_time 6 -hls_playlist_type vod -master_pl_name master.m3u8 -preset veryfast "${base}/stream_%v.m3u8"`
      : `ffmpeg -i "${seed.sourceKey}" -c:v libx264 -preset veryfast -b:v ${seed.bitrate}k -c:a aac -b:a 128k "${base}.mp4"`,
    errorMessage: seed.errorMessage ?? null,
    awsEquivalentCost: seed.awsEquivalentCost,
    createdAt: iso(seed.createdAgo),
    completedAt: seed.completedAgo == null ? null : iso(seed.completedAgo),
    // Sparse on purpose: thumbnails are opt-in per job, and the expander only appears
    // when the array is non-empty.
    thumbnailPaths: done && seed.outputFormat === 'HLS'
      ? [1, 2, 3, 4, 5, 6].map(n => `${base}/thumbs/thumb_${String(n).padStart(3, '0')}.jpg`)
      : null,
  };
});

// ─── /api/media-ops/streams ──────────────────────────────────────────────────

export const demoMediaOpsStreams = [
  {
    id: 312, name: 'Product Launch — Main Stage', streamKey: 'sk_live_a41f9c2e7b8d',
    status: 'LIVE', resolution: '1080p', bitrate: 4500,
    rtmpUrl: 'rtmp://ingest.xamops.media/live/sk_live_a41f9c2e7b8d',
    hlsUrl: '/media/live/sk_live_a41f9c2e7b8d/index.m3u8',
    viewers: 842, bandwidthBytes: 184_320_000_000, uptimeSeconds: 7_842,
  },
  {
    id: 311, name: 'Engineering All-Hands', streamKey: 'sk_live_6d2b8e1f4a97',
    status: 'LIVE', resolution: '720p', bitrate: 2800,
    rtmpUrl: 'rtmp://ingest.xamops.media/live/sk_live_6d2b8e1f4a97',
    hlsUrl: '/media/live/sk_live_6d2b8e1f4a97/index.m3u8',
    viewers: 442, bandwidthBytes: 48_640_000_000, uptimeSeconds: 2_914,
  },
  {
    id: 310, name: 'Support Office Hours', streamKey: 'sk_live_c87a3f0d5e12',
    status: 'IDLE', resolution: '720p', bitrate: 2800,
    rtmpUrl: 'rtmp://ingest.xamops.media/live/sk_live_c87a3f0d5e12',
    hlsUrl: '',
    viewers: 0, bandwidthBytes: 12_288_000_000, uptimeSeconds: 0,
  },
];

// ─── /api/media-ops/storage/files ────────────────────────────────────────────

export const demoMediaOpsFiles = [
  { id: 9041, name: 'launch-keynote-4k.mov',        fileType: 'VIDEO', sizeBytes: 18_253_611_008, durationSeconds: 4284, format: 'MOV',  resolution: '3840x2160', filePath: '/media/xamops-media/campaigns/launch-keynote-4k.mov',           createdAt: iso(18 * MINUTE) },
  { id: 9040, name: 'product-tour-master.mp4',      fileType: 'VIDEO', sizeBytes: 4_187_593_113,  durationSeconds: 1842, format: 'MP4',  resolution: '1920x1080', filePath: '/media/xamops-media/campaigns/product-tour-master.mp4',         createdAt: iso(22 * MINUTE) },
  { id: 9039, name: 'finops-deep-dive (HLS)',       fileType: 'HLS',   sizeBytes: 2_361_393_152,  durationSeconds: 3612, format: 'M3U8', resolution: 'Adaptive',  filePath: '/media/xamops-media/webinars/finops-deep-dive/master.m3u8',     createdAt: iso(94 * MINUTE) },
  { id: 9038, name: 'finops-deep-dive.mp4',         fileType: 'VIDEO', sizeBytes: 2_254_857_830,  durationSeconds: 3612, format: 'MP4',  resolution: '1920x1080', filePath: '/media/xamops-media/webinars/finops-deep-dive.mp4',             createdAt: iso(88 * MINUTE) },
  { id: 9037, name: 'onboarding-module-03 (HLS)',   fileType: 'HLS',   sizeBytes: 449_839_104,    durationSeconds: 1284, format: 'M3U8', resolution: 'Adaptive',  filePath: '/media/xamops-media/training/onboarding-module-03/master.m3u8', createdAt: iso(4.6 * HOUR) },
  { id: 9036, name: 'onboarding-module-02 (HLS)',   fileType: 'HLS',   sizeBytes: 399_507_456,    durationSeconds: 1142, format: 'M3U8', resolution: 'Adaptive',  filePath: '/media/xamops-media/training/onboarding-module-02/master.m3u8', createdAt: iso(5.7 * HOUR) },
  { id: 9035, name: 'ep-114-master.m4a',            fileType: 'AUDIO', sizeBytes: 70_778_880,     durationSeconds: 2946, format: 'M4A',  resolution: null,        filePath: '/media/xamops-media/podcast/ep-114-master.m4a',                 createdAt: iso(10.9 * HOUR) },
  { id: 9034, name: 'social-cutdown-15s.mp4',       fileType: 'VIDEO', sizeBytes: 4_587_520,      durationSeconds: 15,   format: 'MP4',  resolution: '1280x720',  filePath: '/media/xamops-media/campaigns/social-cutdown-15s.mp4',          createdAt: iso(14 * HOUR) },
  { id: 9033, name: 'launch-keynote-poster.jpg',    fileType: 'IMAGE', sizeBytes: 842_240,        durationSeconds: null, format: 'JPG',  resolution: '1920x1080', filePath: '/media/xamops-media/campaigns/launch-keynote-poster.jpg',       createdAt: iso(16 * HOUR) },
  { id: 9032, name: 'onboarding-module-01 (HLS)',   fileType: 'HLS',   sizeBytes: 338_690_048,    durationSeconds: 968,  format: 'M3U8', resolution: 'Adaptive',  filePath: '/media/xamops-media/training/onboarding-module-01/master.m3u8', createdAt: iso(DAY) },
  { id: 9031, name: 'brand-film (DASH)',            fileType: 'HLS',   sizeBytes: 1_395_864_371,  durationSeconds: 2184, format: 'MPD',  resolution: 'Adaptive',  filePath: '/media/xamops-media/campaigns/brand-film-master/manifest.mpd',  createdAt: iso(2 * DAY) },
  { id: 9030, name: 'ep-113-master.m4a',            fileType: 'AUDIO', sizeBytes: 74_973_184,     durationSeconds: 3120, format: 'M4A',  resolution: null,        filePath: '/media/xamops-media/podcast/ep-113-master.m4a',                 createdAt: iso(4 * DAY) },
  { id: 9029, name: 'townhall-q2-thumb.png',        fileType: 'IMAGE', sizeBytes: 1_258_291,      durationSeconds: null, format: 'PNG',  resolution: '1280x720',  filePath: '/media/xamops-media/events/townhall-q2-thumb.png',              createdAt: iso(5 * DAY) },
];

// ─── /api/media-ops/overview ─────────────────────────────────────────────────
// Derived from the three lists above so the stat cards agree with the tables they sit over,
// and so the Storage tab's usage bar matches the file grid beneath it.

export const demoMediaOpsOverview = () => {
  const jobs = demoMediaOpsJobs();
  const count = (s: string) => jobs.filter(j => j.status === s).length;
  return {
    liveStreams: demoMediaOpsStreams.filter(s => s.status === 'LIVE').length,
    processingJobs: count('PROCESSING'),
    queuedJobs: count('QUEUED'),
    completedJobs: count('COMPLETED'),
    failedJobs: count('FAILED'),
    totalViewers: demoMediaOpsStreams.reduce((a, s) => a + s.viewers, 0),
    storageUsedBytes: demoMediaOpsFiles.reduce((a, f) => a + f.sizeBytes, 0),
    // What AWS Elemental would have charged for the jobs that actually completed — the
    // figure the Cost Savings tab labels "Actual Tracked Savings".
    totalSavedVsAws: +jobs
      .filter(j => j.status === 'COMPLETED')
      .reduce((a, j) => a + (j.awsEquivalentCost ?? 0), 0)
      .toFixed(2),
  };
};

// ─── /api/media-ops/notifications ────────────────────────────────────────────

export const demoMediaOpsNotifications = [
  { id: 7104, jobId: 4819, type: 'JOB_COMPLETED' as const, message: 'finops-deep-dive.mp4 → HLS 1080p finished in 26 min',      read: false, createdAt: iso(94 * MINUTE) },
  { id: 7103, jobId: 4818, type: 'JOB_COMPLETED' as const, message: 'finops-deep-dive.mp4 → MP4 1080p finished in 32 min',      read: false, createdAt: iso(88 * MINUTE) },
  { id: 7102, jobId: 4817, type: 'JOB_COMPLETED' as const, message: 'onboarding-module-03.mkv → HLS 720p finished in 24 min',   read: true,  createdAt: iso(4.6 * HOUR) },
  { id: 7101, jobId: 4815, type: 'JOB_FAILED'    as const, message: 'townhall-q3-raw.mov failed: moov atom not found',          read: true,  createdAt: iso(7.8 * HOUR) },
  { id: 7100, jobId: 4814, type: 'JOB_COMPLETED' as const, message: 'ep-114-master.wav → AAC 192k finished in 4 min',           read: true,  createdAt: iso(10.9 * HOUR) },
];

export const demoMediaOpsUnreadCount = () => ({
  count: demoMediaOpsNotifications.filter(n => !n.read).length,
});

// ─── /api/media-ops/user-s3/configs ──────────────────────────────────────────
// The transcode form's "connected bucket" picker. Empty would be a legitimate state, but one
// connection shows the output-destination dropdown doing something.

export const demoMediaOpsUserS3Configs = [
  { id: 21, nickname: 'Marketing masters', bucketName: 'xammer-media-src', region: 'us-east-1', accessKeyHint: 'AKIA…7Q4F', prefixFilter: 'campaigns/', createdAt: iso(26 * DAY) },
];

export const demoMediaOpsUserS3Files = [
  { key: 'campaigns/launch-keynote-4k.mov',   name: 'launch-keynote-4k.mov',   sizeBytes: 18_253_611_008, formattedSize: '17.0 GB', lastModified: iso(2 * DAY),  extension: 'mov' },
  { key: 'campaigns/product-tour-master.mp4', name: 'product-tour-master.mp4', sizeBytes: 4_187_593_113,  formattedSize: '3.9 GB',  lastModified: iso(3 * DAY),  extension: 'mp4' },
  { key: 'campaigns/brand-film-master.mov',   name: 'brand-film-master.mov',   sizeBytes: 9_126_805_504,  formattedSize: '8.5 GB',  lastModified: iso(9 * DAY),  extension: 'mov' },
  { key: 'campaigns/social-cutdown-15s.mp4',  name: 'social-cutdown-15s.mp4',  sizeBytes: 31_457_280,     formattedSize: '30.0 MB', lastModified: iso(14 * DAY), extension: 'mp4' },
];
