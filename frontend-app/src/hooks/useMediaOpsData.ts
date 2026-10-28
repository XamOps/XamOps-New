import { useState, useEffect, useCallback, useRef } from 'react';

// ── Backend DTO types ─────────────────────────────────────────────────────────

export interface MediaOpsOverview {
  liveStreams: number;
  processingJobs: number;
  queuedJobs: number;
  completedJobs: number;
  failedJobs: number;
  totalViewers: number;
  storageUsedBytes: number;
  totalSavedVsAws: number;
}

export interface ApiTranscodeJob {
  id: number;
  sourceUrl: string;
  /** S3 key the source was picked from, e.g. "DRSAKSHI/1.mp4". Absent for uploads/URLs. */
  sourceKey?: string | null;
  outputFormat: string;
  resolution: string;
  bitrate: number;
  fps: number;
  enableAdaptive: boolean;
  status: string;
  progress: number;
  inputSizeBytes: number | null;
  outputSizeBytes: number | null;
  durationSeconds: number | null;
  outputPath: string | null;
  hlsUrl: string | null;
  dashUrl: string | null;
  videoUrl: string | null;
  ffmpegCommand: string | null;
  errorMessage: string | null;
  awsEquivalentCost: number | null;
  createdAt: string;
  completedAt: string | null;
  thumbnailPaths?: string[] | null;
}

export interface ApiMediaStream {
  id: number;
  name: string;
  streamKey: string;
  status: string;
  resolution: string;
  bitrate: number;
  rtmpUrl: string;
  hlsUrl: string;
  viewers: number;
  bandwidthBytes: number;
  uptimeSeconds: number;
}

export interface ApiStorageFile {
  id: number;
  name: string;
  fileType: string;
  sizeBytes: number;
  durationSeconds: number | null;
  format: string;
  resolution: string | null;
  filePath: string;
  createdAt: string;
}

export interface CreateJobRequest {
  sourceUrl: string;
  // Key the source was picked from in S3, e.g. "DRSAKSHI/1.mp4". Sent only for
  // browser-picked sources; the backend mirrors it into the output path.
  sourceKey?: string;
  // Where the source object lives. sourceUrl is a presigned link that expires, so the
  // backend re-signs it from these at the moment the job runs — without them a job that
  // waits in the queue, runs on a schedule, or is retried later fails with a 403.
  sourceBucket?: string;
  sourceRegion?: string;
  sourceAccountId?: string;
  outputFormat: string;
  resolution: string;
  bitrate?: string;
  enableAdaptive: boolean;
  // x264 preset: fast | veryfast (default) | superfast | ultrafast.
  // Faster = lower quality at the same bitrate.
  encodingSpeed?: string;
  // Advanced fields
  presetId?: number;
  codec?: string | null;
  priority?: 'HIGH' | 'NORMAL' | 'LOW';
  startTime?: string;
  endTime?: string;
  subtitleMode?: 'BURN_IN' | 'SIDECAR' | null;
  subtitlePath?: string;
  watermarkText?: string;
  watermarkImagePath?: string;
  watermarkPosition?: string;   // kept for backward compat
  watermarkX?: number;           // center %, overrides position enum when set
  watermarkY?: number;
  watermarkOpacity?: number;
  watermarkScale?: number;
  thumbnailEnabled?: boolean;
  thumbnailIntervalSeconds?: number | null;
  thumbnailTimestamp?: string | null;
  audioBitrate?: string;
  audioSampleRate?: number | null;
  audioChannels?: number | null;
  customFfmpegParams?: string;
  scheduledAt?: string | null;
  encryptionEnabled?: boolean;
  // Optional: id of a connected customer S3 bucket (user_s3_configs) to store the
  // transcoded output in. When omitted, output goes to XamOps storage.
  outputS3ConfigId?: number;
  // Optional: store output in a bucket of the customer's connected AWS account
  // (the sidebar-selected account), written via that account's IAM role.
  outputAccountId?: string;
  outputBucket?: string;
}

export interface CreateStreamRequest {
  name: string;
  resolution: string;
  bitrate?: string;
}

// ── User S3 types ─────────────────────────────────────────────────────────────

export interface UserS3ConfigDto {
  id: number;
  nickname: string;
  bucketName: string;
  region: string;
  accessKeyHint: string | null;
  prefixFilter: string | null;
  createdAt: string;
}

export interface UserS3FileItem {
  key: string;
  name: string;
  sizeBytes: number;
  formattedSize: string;
  lastModified: string;
  extension: string;
}

// ── User S3 API functions ─────────────────────────────────────────────────────

export async function fetchUserS3Configs(): Promise<UserS3ConfigDto[]> {
  const res = await fetch('/api/media-ops/user-s3/configs', { credentials: 'include' });
  if (!res.ok) throw new Error('Failed to load S3 connections');
  return res.json();
}

export async function connectUserS3(req: {
  nickname?: string;
  bucketName: string;
  region: string;
  accessKey: string;
  secretKey: string;
  prefixFilter?: string;
  endpointUrl?: string;
}): Promise<UserS3ConfigDto> {
  const res = await fetch('/api/media-ops/user-s3/configs', {
    method: 'POST',
    credentials: 'include',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(req),
  });
  if (!res.ok) {
    const err = await res.json().catch(() => ({}));
    throw new Error((err as any).error || 'Failed to connect S3 bucket');
  }
  return res.json();
}

export async function deleteUserS3Config(id: number): Promise<void> {
  const res = await fetch(`/api/media-ops/user-s3/configs/${id}`, {
    method: 'DELETE',
    credentials: 'include',
  });
  if (!res.ok && res.status !== 404) throw new Error('Failed to disconnect S3 bucket');
}

export async function fetchUserS3Files(configId: number): Promise<UserS3FileItem[]> {
  const res = await fetch(`/api/media-ops/user-s3/configs/${configId}/files`, {
    credentials: 'include',
  });
  if (!res.ok) {
    const err = await res.json().catch(() => ({}));
    throw new Error((err as any).message || 'Failed to list S3 files');
  }
  return res.json();
}

export async function presignUserS3Url(
  configId: number,
  s3Key: string,
  expiryMinutes = 60
): Promise<string> {
  const res = await fetch(`/api/media-ops/user-s3/configs/${configId}/presign`, {
    method: 'POST',
    credentials: 'include',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ s3Key, expiryMinutes }),
  });
  if (!res.ok) throw new Error('Failed to generate preview URL');
  const data = await res.json();
  return data.url;
}

// ── Format helpers ────────────────────────────────────────────────────────────

export function formatBytes(bytes: number): string {
  if (!bytes || bytes === 0) return '0 B';
  const k = 1024;
  const sizes = ['B', 'KB', 'MB', 'GB', 'TB'];
  const i = Math.floor(Math.log(bytes) / Math.log(k));
  return `${parseFloat((bytes / Math.pow(k, i)).toFixed(1))} ${sizes[i]}`;
}

export function formatSeconds(secs: number | null): string {
  if (!secs) return '—';
  const h = Math.floor(secs / 3600);
  const m = Math.floor((secs % 3600) / 60);
  const s = secs % 60;
  if (h > 0) return `${h}:${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`;
  return `${m}:${String(s).padStart(2, '0')}`;
}

export function formatUptime(secs: number): string {
  if (!secs || secs === 0) return '—';
  const h = Math.floor(secs / 3600);
  const m = Math.floor((secs % 3600) / 60);
  if (h > 0) return `${h}h ${m}m`;
  return `${m}m`;
}

export function timeAgo(iso: string): string {
  if (!iso) return '—';
  const diff = (Date.now() - new Date(iso).getTime()) / 1000;
  if (diff < 60) return 'just now';
  if (diff < 3600) return `${Math.floor(diff / 60)} min ago`;
  if (diff < 86400) return `${Math.floor(diff / 3600)} hr ago`;
  return `${Math.floor(diff / 86400)} day ago`;
}

/**
 * Path portion of a job's source, without the filename — "DRSAKSHI" for
 * "DRSAKSHI/1.mp4", empty for a source that was uploaded or pasted as a URL.
 */
function sourceFolderOf(j: ApiTranscodeJob): string {
  const key = j.sourceKey;
  if (!key || !key.includes('/')) return '';
  return key.slice(0, key.lastIndexOf('/'));
}

/** Filename of a job's source, with any presigned query string stripped. */
function sourceFileName(j: ApiTranscodeJob): string {
  if (j.sourceKey) return j.sourceKey.split('/').pop() || j.sourceKey;
  if (!j.sourceUrl) return 'Unknown';
  const withoutQuery = j.sourceUrl.split('?')[0];
  return decodeURIComponent(withoutQuery.split('/').pop() || withoutQuery);
}

/** Uppercased source extension for the FORMAT column, e.g. "MP4". */
function sourceExtension(j: ApiTranscodeJob): string {
  const name = sourceFileName(j);
  const dot = name.lastIndexOf('.');
  if (dot < 0) return 'MP4';
  return name.slice(dot + 1).toUpperCase().slice(0, 4);
}

// ── Hook ──────────────────────────────────────────────────────────────────────

export function useMediaOpsData() {
  const [overview, setOverview] = useState<MediaOpsOverview | null>(null);
  const [rawJobs, setRawJobs] = useState<ApiTranscodeJob[]>([]);
  const [rawStreams, setRawStreams] = useState<ApiMediaStream[]>([]);
  const [rawFiles, setRawFiles] = useState<ApiStorageFile[]>([]);

  const [loadingOverview, setLoadingOverview] = useState(true);
  const [loadingJobs, setLoadingJobs] = useState(true);
  const [loadingStreams, setLoadingStreams] = useState(true);
  const [loadingFiles, setLoadingFiles] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const stompClientRef = useRef<any>(null);
  const subscriptionsRef = useRef<Map<number, any>>(new Map());
  const isMountedRef = useRef(true);
  const reconnectTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  // ── Fetch functions ────────────────────────────────────────────────────────

  const fetchOverview = useCallback(async () => {
    try {
      const res = await fetch('/api/media-ops/overview', { credentials: 'include' });
      if (!res.ok) throw new Error(`Overview fetch failed: ${res.status}`);
      const data: MediaOpsOverview = await res.json();
      if (isMountedRef.current) setOverview(data);
    } catch (e: any) {
      if (isMountedRef.current) setError(e.message);
    } finally {
      if (isMountedRef.current) setLoadingOverview(false);
    }
  }, []);

  const fetchJobs = useCallback(async () => {
    try {
      const res = await fetch('/api/media-ops/jobs', { credentials: 'include' });
      if (!res.ok) throw new Error(`Jobs fetch failed: ${res.status}`);
      const data: ApiTranscodeJob[] = await res.json();
      if (isMountedRef.current) setRawJobs(data);
    } catch (e: any) {
      if (isMountedRef.current) setError(e.message);
    } finally {
      if (isMountedRef.current) setLoadingJobs(false);
    }
  }, []);

  const fetchStreams = useCallback(async () => {
    try {
      const res = await fetch('/api/media-ops/streams', { credentials: 'include' });
      if (!res.ok) throw new Error(`Streams fetch failed: ${res.status}`);
      const data: ApiMediaStream[] = await res.json();
      if (isMountedRef.current) setRawStreams(data);
    } catch (e: any) {
      if (isMountedRef.current) setError(e.message);
    } finally {
      if (isMountedRef.current) setLoadingStreams(false);
    }
  }, []);

  const fetchFiles = useCallback(async () => {
    try {
      const res = await fetch('/api/media-ops/storage/files', { credentials: 'include' });
      if (!res.ok) throw new Error(`Files fetch failed: ${res.status}`);
      const data: ApiStorageFile[] = await res.json();
      if (isMountedRef.current) setRawFiles(data);
    } catch (e: any) {
      if (isMountedRef.current) setError(e.message);
    } finally {
      if (isMountedRef.current) setLoadingFiles(false);
    }
  }, []);

  // ── STOMP WebSocket ────────────────────────────────────────────────────────

  const subscribeToJob = useCallback((jobId: number) => {
    const client = stompClientRef.current;
    if (!client || subscriptionsRef.current.has(jobId)) return;

    const sub = client.subscribe(`/topic/media-ops/jobs/${jobId}`, (msg: any) => {
      try {
        const update: { id: number; status: string; progress: number } = JSON.parse(msg.body);
        if (!isMountedRef.current) return;
        setRawJobs(prev => prev.map(j =>
          j.id === update.id ? { ...j, status: update.status, progress: update.progress } : j
        ));
        if (update.status === 'COMPLETED' || update.status === 'FAILED' || update.status === 'CANCELLED') {
          subscriptionsRef.current.get(jobId)?.unsubscribe();
          subscriptionsRef.current.delete(jobId);
          setTimeout(() => {
            if (isMountedRef.current) { fetchJobs(); fetchOverview(); }
          }, 600);
        }
      } catch (err) {
        console.error('MediaOps: failed to parse job update', err);
      }
    });
    subscriptionsRef.current.set(jobId, sub);
  }, [fetchJobs, fetchOverview]);

  const connectWebSocket = useCallback(() => {
    if (!window.SockJS || !window.Stomp) return;
    try {
      const socket = new window.SockJS('/media-ops-ws');
      const client = window.Stomp.over(socket);
      if (process.env.NODE_ENV === 'production') {
        client.debug = () => {};
      }
      client.connect(
        {},
        () => {
          if (!isMountedRef.current) { client.disconnect(() => {}); return; }
          stompClientRef.current = client;
          // Subscribe to any already-processing jobs
          setRawJobs(prev => {
            prev.filter(j => j.status === 'PROCESSING').forEach(j => subscribeToJob(j.id));
            return prev;
          });
        },
        (_err: any) => {
          stompClientRef.current = null;
          // The old connection's subscriptions died with it. Clear the registry,
          // otherwise subscribeToJob() sees the stale entries and skips
          // resubscribing on the new connection — progress then freezes until
          // a manual page reload.
          subscriptionsRef.current.clear();
          if (!isMountedRef.current) return;
          reconnectTimerRef.current = setTimeout(() => {
            if (isMountedRef.current) connectWebSocket();
          }, 5000);
        }
      );
    } catch (err) {
      console.error('MediaOps: WebSocket connect error', err);
    }
  }, [subscribeToJob]);

  // When jobs list updates, subscribe to any new PROCESSING jobs
  useEffect(() => {
    if (!stompClientRef.current) return;
    rawJobs.filter(j => j.status === 'PROCESSING').forEach(j => subscribeToJob(j.id));
  }, [rawJobs, subscribeToJob]);

  // Reconciliation poll: while any job is active, refresh the list on an interval.
  //
  // STOMP topics are fire-and-forget — if a terminal (COMPLETED/FAILED/CANCELLED)
  // message is broadcast during a brief WebSocket drop/reconnect, or before the
  // client has subscribed, the update is lost and the row would sit at "Processing"
  // until a manual reload. Polling regardless of WebSocket state guarantees the UI
  // converges on the real server-side status within one interval. When the socket
  // is connected it still drives the fast, real-time progress updates; this poll is
  // just the safety net that catches missed terminal transitions.
  useEffect(() => {
    const anyActive = rawJobs.some(j => j.status === 'PROCESSING' || j.status === 'QUEUED');
    if (!anyActive) return;
    const timer = setInterval(() => {
      fetchJobs();
      fetchOverview();
    }, 10000);
    return () => clearInterval(timer);
  }, [rawJobs, fetchJobs, fetchOverview]);

  // ── Lifecycle ──────────────────────────────────────────────────────────────

  useEffect(() => {
    isMountedRef.current = true;
    fetchOverview();
    fetchJobs();
    fetchStreams();
    fetchFiles();
    connectWebSocket();

    return () => {
      isMountedRef.current = false;
      if (reconnectTimerRef.current) clearTimeout(reconnectTimerRef.current);
      subscriptionsRef.current.forEach(sub => { try { sub.unsubscribe(); } catch {} });
      subscriptionsRef.current.clear();
      try { stompClientRef.current?.disconnect(() => {}); } catch {}
      stompClientRef.current = null;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // ── Actions ────────────────────────────────────────────────────────────────

  const createJob = useCallback(async (req: CreateJobRequest): Promise<ApiTranscodeJob> => {
    const res = await fetch('/api/media-ops/jobs', {
      method: 'POST',
      credentials: 'include',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(req),
    });
    if (!res.ok) {
      const msg = await res.text();
      throw new Error(msg || 'Failed to create job');
    }
    const job: ApiTranscodeJob = await res.json();
    setRawJobs(prev => [job, ...prev]);
    setTimeout(() => { if (stompClientRef.current) subscribeToJob(job.id); }, 800);
    return job;
  }, [subscribeToJob]);

  const createStream = useCallback(async (req: CreateStreamRequest): Promise<ApiMediaStream> => {
    const res = await fetch('/api/media-ops/streams', {
      method: 'POST',
      credentials: 'include',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(req),
    });
    if (!res.ok) {
      const msg = await res.text();
      throw new Error(msg || 'Failed to create stream');
    }
    const stream: ApiMediaStream = await res.json();
    setRawStreams(prev => [stream, ...prev]);
    fetchOverview();
    return stream;
  }, [fetchOverview]);

  const startStream = useCallback(async (streamId: number) => {
    const res = await fetch(`/api/media-ops/streams/${streamId}/start`, {
      method: 'POST',
      credentials: 'include',
    });
    if (!res.ok) throw new Error('Failed to start stream');
    await fetchStreams();
    await fetchOverview();
  }, [fetchStreams, fetchOverview]);

  const stopStream = useCallback(async (streamId: number) => {
    const res = await fetch(`/api/media-ops/streams/${streamId}/stop`, {
      method: 'POST',
      credentials: 'include',
    });
    if (!res.ok) throw new Error('Failed to stop stream');
    await fetchStreams();
    await fetchOverview();
  }, [fetchStreams, fetchOverview]);

  const deleteFile = useCallback(async (fileId: number) => {
    const res = await fetch(`/api/media-ops/storage/files/${fileId}`, {
      method: 'DELETE',
      credentials: 'include',
    });
    if (!res.ok) throw new Error('Failed to delete file');
    setRawFiles(prev => prev.filter(f => f.id !== fileId));
    fetchOverview();
  }, [fetchOverview]);

  const uploadFile = useCallback(async (file: File): Promise<ApiStorageFile> => {
    const form = new FormData();
    form.append('file', file);
    const res = await fetch('/api/media-ops/storage/upload', {
      method: 'POST',
      credentials: 'include',
      body: form,
    });
    if (!res.ok) {
      const msg = await res.text();
      throw new Error(msg || `Upload failed: ${res.status}`);
    }
    const saved: ApiStorageFile = await res.json();
    setRawFiles(prev => [saved, ...prev]);
    fetchOverview();
    return saved;
  }, [fetchOverview]);

  const retryJob = useCallback(async (jobId: number): Promise<ApiTranscodeJob> => {
    const res = await fetch(`/api/media-ops/jobs/${jobId}/retry`, {
      method: 'POST',
      credentials: 'include',
    });
    if (!res.ok) throw new Error('Failed to retry job');
    const job: ApiTranscodeJob = await res.json();
    setRawJobs(prev => prev.map(j => j.id === jobId ? job : j));
    setTimeout(() => { if (stompClientRef.current) subscribeToJob(jobId); }, 800);
    return job;
  }, [subscribeToJob]);

  // Stops a queued/processing/paused job — kills the FFmpeg process server-side.
  // The job ends up CANCELLED and can be re-run via retry.
  const cancelJob = useCallback(async (jobId: number): Promise<void> => {
    const res = await fetch(`/api/media-ops/jobs/${jobId}`, {
      method: 'DELETE',
      credentials: 'include',
    });
    if (!res.ok) throw new Error('Failed to stop job');
    fetchJobs();
  }, [fetchJobs]);

  // Suspends the job's FFmpeg process (keeps encode state, frees CPU).
  const pauseJob = useCallback(async (jobId: number): Promise<void> => {
    const res = await fetch(`/api/media-ops/jobs/${jobId}/pause`, {
      method: 'POST',
      credentials: 'include',
    });
    if (!res.ok) throw new Error(res.status === 409 ? 'Job is not running' : 'Failed to pause job');
    const job: ApiTranscodeJob = await res.json();
    setRawJobs(prev => prev.map(j => j.id === jobId ? job : j));
  }, []);

  // Resumes a paused job's FFmpeg process where it left off.
  const resumeJob = useCallback(async (jobId: number): Promise<void> => {
    const res = await fetch(`/api/media-ops/jobs/${jobId}/resume`, {
      method: 'POST',
      credentials: 'include',
    });
    if (!res.ok) throw new Error(res.status === 409 ? 'Job is not paused' : 'Failed to resume job');
    const job: ApiTranscodeJob = await res.json();
    setRawJobs(prev => prev.map(j => j.id === jobId ? job : j));
    setTimeout(() => { if (stompClientRef.current) subscribeToJob(jobId); }, 800);
  }, [subscribeToJob]);

  // Download the transcoded video plus related files (segments, manifests,
  // thumbnails, subtitles) as a single ZIP archive saved to the user's machine.
  const downloadJob = useCallback(async (jobId: number, fileName?: string): Promise<void> => {
    const res = await fetch(`/api/media-ops/jobs/${jobId}/download`, {
      credentials: 'include',
    });
    if (!res.ok) {
      if (res.status === 409) throw new Error('Job output is not ready yet');
      if (res.status === 404) throw new Error('No output files available for this job');
      throw new Error('Failed to download job output');
    }
    const blob = await res.blob();
    // Prefer the server-provided filename from Content-Disposition when available.
    const disposition = res.headers.get('Content-Disposition') || '';
    const match = disposition.match(/filename="?([^"]+)"?/i);
    const name = match?.[1] || fileName || `transcode-${jobId}.zip`;

    const url = window.URL.createObjectURL(blob);
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = name;
    document.body.appendChild(anchor);
    anchor.click();
    anchor.remove();
    window.URL.revokeObjectURL(url);
  }, []);

  const refresh = useCallback(() => {
    setLoadingOverview(true);
    setLoadingJobs(true);
    setLoadingStreams(true);
    setLoadingFiles(true);
    setError(null);
    fetchOverview();
    fetchJobs();
    fetchStreams();
    fetchFiles();
  }, [fetchOverview, fetchJobs, fetchStreams, fetchFiles]);

  // ── Derived / adapted view-layer data ─────────────────────────────────────

  // Source naming. sourceKey is authoritative when present; falling back to the URL means
  // parsing a presigned link, whose last path segment drags the whole
  // "?X-Amz-Security-Token=…" query string along with it — which is what made the table
  // read "3.mp4?X-Amz-Security-…" instead of "DRSAKSHI/3.mp4".

  const jobs = rawJobs.map(j => ({
    id: String(j.id),
    name: sourceFileName(j),
    /** Folder the source came from, e.g. "DRSAKSHI". Empty for uploads and pasted URLs. */
    sourceFolder: sourceFolderOf(j),
    /** Full path shown when the name is expanded, e.g. "DRSAKSHI/1.mp4". */
    sourcePath: j.sourceKey || sourceFileName(j),
    inputFormat: sourceExtension(j),
    outputFormat: j.outputFormat ?? '—',
    resolution: j.enableAdaptive ? 'Adaptive (1080p·720p·480p)' : (j.resolution ?? '—'),
    status: (j.status ?? 'queued').toLowerCase() as 'queued' | 'processing' | 'paused' | 'completed' | 'failed' | 'cancelled',
    progress: j.progress ?? 0,
    duration: formatSeconds(j.durationSeconds),
    fileSize: j.inputSizeBytes ? formatBytes(j.inputSizeBytes) : '—',
    outputSize: j.outputSizeBytes ? formatBytes(j.outputSizeBytes) : '—',
    savings: (() => {
      if (!j.awsEquivalentCost) return '—';
      const v = Number(j.awsEquivalentCost);
      if (v < 0.01) return `$${v.toFixed(4)}`;
      return `$${v.toFixed(2)}`;
    })(),
    createdAt: timeAgo(j.createdAt),
    completedAt: j.completedAt ? timeAgo(j.completedAt) : undefined,
    fps: j.fps || 30,
    bitrate: j.bitrate ? `${j.bitrate} kbps` : '—',
    ffmpegCommand: j.ffmpegCommand ?? undefined,
    hlsUrl: j.hlsUrl ?? undefined,
    dashUrl: j.dashUrl ?? undefined,
    videoUrl: j.videoUrl ?? undefined,
    errorMessage: j.errorMessage ?? undefined,
    thumbnailPaths: j.thumbnailPaths ?? [],
    rawId: j.id,
  }));

  const streams = rawStreams.map(s => ({
    id: String(s.id),
    name: s.name,
    status: (s.status ?? 'idle').toLowerCase() as 'live' | 'idle' | 'error',
    viewers: s.viewers ?? 0,
    rtmpUrl: s.rtmpUrl,
    hlsUrl: s.hlsUrl || '',
    streamKey: s.streamKey,
    resolution: s.resolution,
    bitrate: s.bitrate ? `${s.bitrate} kbps` : '—',
    uptime: formatUptime(s.uptimeSeconds),
    bandwidth: formatBytes(s.bandwidthBytes),
    rawId: s.id,
  }));

  const files = rawFiles.map(f => ({
    id: String(f.id),
    name: f.name,
    type: (f.fileType ?? 'video').toLowerCase() as 'video' | 'audio' | 'image' | 'hls',
    size: formatBytes(f.sizeBytes),
    duration: f.durationSeconds ? formatSeconds(f.durationSeconds) : undefined,
    format: f.format,
    resolution: f.resolution ?? undefined,
    filePath: f.filePath,
    createdAt: timeAgo(f.createdAt),
    thumbnail: '',
    rawId: f.id,
  }));

  return {
    overview,
    jobs,
    streams,
    files,
    loadingOverview,
    loadingJobs,
    loadingStreams,
    loadingFiles,
    error,
    createJob,
    retryJob,
    cancelJob,
    pauseJob,
    resumeJob,
    downloadJob,
    uploadFile,
    createStream,
    startStream,
    stopStream,
    deleteFile,
    refresh,
  };
}
