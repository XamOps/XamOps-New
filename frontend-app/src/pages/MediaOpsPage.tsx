import React, { useState, useEffect, useCallback, useRef } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { AnimatePresence, motion } from 'framer-motion';
import { Icon } from '@iconify/react';
import { useTopbar } from '../contexts/TopbarContext';
import { useTheme } from '../contexts/ThemeContext';
import { useMediaOpsData, formatBytes, type MediaOpsOverview, type CreateJobRequest, type CreateStreamRequest, type ApiStorageFile } from '../hooks/useMediaOpsData';
import { SimpleMode } from '../components/mediaops/SimpleMode';
import { AdvancedMode, type AdvancedModeState } from '../components/mediaops/AdvancedMode';
import { useMediaOpsNotifications } from '../hooks/useMediaOpsNotifications';
import { NotificationBell } from '../components/mediaops/NotificationBell';
import { NotificationList } from '../components/mediaops/NotificationList';

// ─── Types ────────────────────────────────────────────────────────────────────

type Tab = 'overview' | 'transcode' | 'streams' | 'storage' | 'cost-savings';

type JobStatus = 'queued' | 'processing' | 'completed' | 'failed';
type StreamStatus = 'live' | 'idle' | 'error';

interface TranscodeJob {
  id: string;
  rawId: number;
  name: string;
  inputFormat: string;
  outputFormat: string;
  resolution: string;
  status: JobStatus;
  progress: number;
  duration: string;
  fileSize: string;
  outputSize: string;
  savings: string;
  createdAt: string;
  completedAt?: string;
  fps: number;
  bitrate: string;
  ffmpegCommand?: string;
  hlsUrl?: string;
  dashUrl?: string;
  videoUrl?: string;
  errorMessage?: string;
}

interface LiveStream {
  id: string;
  rawId: number;
  name: string;
  status: StreamStatus;
  viewers: number;
  rtmpUrl: string;
  hlsUrl: string;
  streamKey: string;
  resolution: string;
  bitrate: string;
  uptime: string;
  bandwidth: string;
}

interface MediaFile {
  id: string;
  rawId: number;
  name: string;
  type: 'video' | 'audio' | 'image' | 'hls';
  size: string;
  duration?: string;
  format: string;
  resolution?: string;
  filePath: string;
  createdAt: string;
  thumbnail: string;
}

function formatSavings(raw: number | null | undefined): string {
  if (!raw && raw !== 0) return '—';
  const v = Number(raw);
  if (v === 0) return '$0.00';
  if (v < 0.001) return `< $0.001`;
  if (v < 0.01)  return `$${v.toFixed(4)}`;
  if (v < 1)     return `$${v.toFixed(3)}`;
  return `$${v.toFixed(2)}`;
}

// ─── Static comparison data (educational content, not from API) ───────────────

const AWS_COMPARISON = [
  { service: 'AWS MediaConvert', useCase: 'Video Transcoding', awsPrice: '$0.0150/min', ffmpegPrice: '$0.00 (CPU cost ~$0.0002/min)', monthlySavings: '$1,240', yearSavings: '$14,880', savingsPct: 98 },
  { service: 'AWS MediaLive', useCase: 'Live Encoding', awsPrice: '$0.2200/hr/channel', ffmpegPrice: '$0.00 (instance cost ~$0.004/hr)', monthlySavings: '$648', yearSavings: '$7,776', savingsPct: 98 },
  { service: 'AWS MediaPackage', useCase: 'HLS Packaging', awsPrice: '$0.0330/GB', ffmpegPrice: '$0.00 (bundled with encoding)', monthlySavings: '$420', yearSavings: '$5,040', savingsPct: 100 },
  { service: 'Amazon IVS', useCase: 'Interactive Video', awsPrice: '$0.2000/hr + $0.05/GB', ffmpegPrice: '$0.00 (nginx-rtmp + FFmpeg)', monthlySavings: '$312', yearSavings: '$3,744', savingsPct: 97 },
];

// ─── Helpers ──────────────────────────────────────────────────────────────────

function useActiveTab(): Tab {
  const { pathname } = useLocation();
  if (pathname.startsWith('/media-ops/transcode')) return 'transcode';
  if (pathname.startsWith('/media-ops/streams')) return 'streams';
  if (pathname.startsWith('/media-ops/storage')) return 'storage';
  if (pathname.startsWith('/media-ops/cost-savings')) return 'cost-savings';
  return 'overview';
}

const TAB_DEFS: { id: Tab; label: string; icon: string; path: string }[] = [
  { id: 'overview', label: 'Overview', icon: 'mdi:view-dashboard-outline', path: '/media-ops' },
  { id: 'transcode', label: 'Transcode', icon: 'mdi:film-sync', path: '/media-ops/transcode' },
  { id: 'streams', label: 'Live Streams', icon: 'mdi:broadcast', path: '/media-ops/streams' },
  { id: 'storage', label: 'Storage', icon: 'mdi:folder-play-outline', path: '/media-ops/storage' },
  { id: 'cost-savings', label: 'Cost Savings', icon: 'mdi:piggy-bank-outline', path: '/media-ops/cost-savings' },
];

function StatusBadge({ status }: { status: JobStatus | StreamStatus }) {
  const map: Record<string, { label: string; cls: string; dot: string }> = {
    processing: { label: 'Processing', cls: 'bg-blue-50 text-blue-700 border-blue-200', dot: 'bg-blue-500 animate-pulse' },
    queued: { label: 'Queued', cls: 'bg-amber-50 text-amber-700 border-amber-200', dot: 'bg-amber-400' },
    completed: { label: 'Completed', cls: 'bg-emerald-50 text-emerald-700 border-emerald-200', dot: 'bg-emerald-500' },
    failed: { label: 'Failed', cls: 'bg-red-50 text-red-700 border-red-200', dot: 'bg-red-500' },
    live: { label: 'LIVE', cls: 'bg-red-50 text-red-700 border-red-200', dot: 'bg-red-500 animate-pulse' },
    idle: { label: 'Idle', cls: 'bg-[rgba(0,0,0,0.04)] text-[rgba(0,0,0,0.45)] border-[rgba(0,0,0,0.08)]', dot: 'bg-[rgba(0,0,0,0.2)]' },
    error: { label: 'Error', cls: 'bg-red-50 text-red-700 border-red-200', dot: 'bg-red-500' },
  };
  const s = map[status] || map.idle;
  return (
    <span className={`inline-flex items-center gap-1.5 px-2 py-0.5 rounded-full text-[11px] font-semibold border ${s.cls}`}>
      <span className={`w-1.5 h-1.5 rounded-full ${s.dot}`} />
      {s.label}
    </span>
  );
}

function ProgressBar({ value, color = 'bg-pink-500' }: { value: number; color?: string }) {
  return (
    <div className="w-full h-1.5 bg-[rgba(0,0,0,0.06)] rounded-full overflow-hidden">
      <div className={`h-full rounded-full transition-all duration-700 ${color}`} style={{ width: `${value}%` }} />
    </div>
  );
}

function StatCard({ label, value, icon, iconBg, iconColor, borderColor, sub, tooltip }:
  { label: string; value: string | number; icon: string; iconBg: string; iconColor: string; borderColor: string; sub?: string; tooltip?: string }) {
  return (
    <div className={`group relative rounded-2xl border border-l-4 p-4 shadow-sm flex flex-col gap-2 bg-white border-[rgba(0,0,0,0.08)] ${borderColor}`}>
      <div className="flex items-start justify-between">
        <div className={`w-9 h-9 rounded-xl ${iconBg} flex items-center justify-center`}>
          <Icon icon={icon} className={`${iconColor} text-base`} />
        </div>
        {tooltip && <Icon icon="mdi:information-outline" className="text-[11px] mt-1 opacity-0 group-hover:opacity-100 transition-opacity text-[rgba(0,0,0,0.4)]" />}
      </div>
      <p className="text-[10px] font-semibold uppercase tracking-wider text-[rgba(0,0,0,0.5)]">{label}</p>
      <p style={{ fontFamily: "'Bebas Neue','Barlow Condensed',sans-serif", fontSize: '26px', fontWeight: 400, lineHeight: '26px', color: 'rgb(10,10,10)', fontVariantNumeric: 'tabular-nums' }}>
        {String(value)}
      </p>
      {sub && <p className="text-[11px] text-[rgba(0,0,0,0.4)] -mt-1">{sub}</p>}
      {tooltip && (
        <div className="absolute bottom-full left-1/2 -translate-x-1/2 mb-2 w-52 rounded-xl px-3 py-2 text-[11px] leading-snug shadow-lg z-50 opacity-0 group-hover:opacity-100 pointer-events-none transition-opacity bg-gray-900 text-white">
          {tooltip}
          <div className="absolute top-full left-1/2 -translate-x-1/2 border-l-4 border-r-4 border-t-4 border-l-transparent border-r-transparent border-t-gray-900" />
        </div>
      )}
    </div>
  );
}

function SkeletonBlock({ className }: { className?: string }) {
  return <div className={`bg-[rgba(0,0,0,0.06)] rounded-xl animate-pulse ${className ?? ''}`} />;
}

// ─── Overview Tab ─────────────────────────────────────────────────────────────

interface OverviewTabProps {
  overview: MediaOpsOverview | null;
  jobs: TranscodeJob[];
  streams: LiveStream[];
  loadingOverview: boolean;
  loadingJobs: boolean;
  loadingStreams: boolean;
}

function OverviewTab({ overview, jobs, streams, loadingOverview, loadingJobs, loadingStreams }: OverviewTabProps) {
  const saved = overview ? formatSavings(overview.totalSavedVsAws) : '—';
  const storageStr = overview ? formatBytes(overview.storageUsedBytes) : '—';

  const stats = [
    { label: 'Active Streams', value: overview?.liveStreams ?? '—', icon: 'mdi:broadcast', iconBg: 'bg-red-50', iconColor: 'text-red-500', borderColor: 'border-l-red-500', sub: 'Live right now', tooltip: 'Number of currently active RTMP/HLS live streams.' },
    { label: 'Jobs Running', value: overview?.processingJobs ?? '—', icon: 'mdi:film-sync', iconBg: 'bg-blue-50', iconColor: 'text-blue-500', borderColor: 'border-l-blue-500', sub: 'Transcoding now', tooltip: 'FFmpeg transcoding jobs currently in progress.' },
    { label: 'Jobs Completed', value: overview?.completedJobs ?? '—', icon: 'mdi:check-circle-outline', iconBg: 'bg-emerald-50', iconColor: 'text-emerald-500', borderColor: 'border-l-emerald-500', sub: 'Total completed', tooltip: 'Jobs that finished successfully.' },
    { label: 'Total Viewers', value: overview?.totalViewers ?? '—', icon: 'mdi:account-group-outline', iconBg: 'bg-violet-50', iconColor: 'text-violet-500', borderColor: 'border-l-violet-500', sub: 'Across all streams', tooltip: 'Total concurrent viewers across all live streams.' },
    { label: 'Storage Used', value: storageStr, icon: 'mdi:database-outline', iconBg: 'bg-sky-50', iconColor: 'text-sky-500', borderColor: 'border-l-sky-500', sub: 'Media storage', tooltip: 'Total media storage consumed.' },
    { label: 'Saved vs AWS', value: saved, icon: 'mdi:piggy-bank-outline', iconBg: 'bg-pink-50', iconColor: 'text-pink-500', borderColor: 'border-l-pink-500', sub: 'Total tracked', tooltip: 'Money saved by using FFmpeg instead of AWS Media Services.' },
  ];

  return (
    <div className="space-y-6">
      {/* Hero Banner */}
      <div className="relative overflow-hidden rounded-2xl bg-gradient-to-r from-[#1a1a2e] via-[#16213e] to-[#0f3460] p-6 shadow-lg">
        <div className="absolute inset-0 opacity-10">
          <div className="absolute top-4 right-16 w-32 h-32 rounded-full bg-pink-500 blur-3xl" />
          <div className="absolute bottom-0 right-0 w-48 h-32 rounded-full bg-violet-600 blur-3xl" />
          <div className="absolute top-0 left-1/2 w-24 h-24 rounded-full bg-blue-400 blur-2xl" />
        </div>
        <div className="relative flex flex-col md:flex-row md:items-center md:justify-between gap-4">
          <div>
            <div className="flex items-center gap-2 mb-2">
              <div className="w-8 h-8 rounded-xl bg-gradient-to-br from-pink-500 to-rose-600 flex items-center justify-center shadow">
                <Icon icon="mdi:film" className="text-white text-sm" />
              </div>
              <span className="text-[11px] font-bold uppercase tracking-widest text-pink-300">MediaOps</span>
              <span className="px-2 py-0.5 rounded-full bg-pink-500/20 border border-pink-400/30 text-[10px] font-bold text-pink-300 uppercase tracking-wider">Beta</span>
            </div>
            <h1 className="text-2xl font-bold text-white leading-tight mb-1">World-class media processing,<br />zero AWS bill.</h1>
            <p className="text-[13px] text-white/60 max-w-lg">Replace AWS MediaConvert, MediaLive & MediaPackage with FFmpeg. Transcode, stream, and store media — free.</p>
          </div>
          <div className="flex flex-col gap-2">
            <div className="flex items-center gap-2 px-4 py-2.5 rounded-xl bg-white/10 border border-white/10 backdrop-blur-sm">
              <Icon icon="mdi:lightning-bolt" className="text-yellow-400" />
              <span className="text-[12px] text-white font-semibold">FFmpeg — Ready</span>
            </div>
            <div className="flex items-center gap-2 px-4 py-2.5 rounded-xl bg-white/10 border border-white/10 backdrop-blur-sm">
              <span className={`w-2 h-2 rounded-full ${(overview?.processingJobs ?? 0) > 0 ? 'bg-emerald-400 animate-pulse' : 'bg-gray-400'}`} />
              <span className="text-[12px] text-white font-semibold">
                {(overview?.processingJobs ?? 0) > 0 ? `${overview!.processingJobs} job(s) processing` : 'Worker idle'}
              </span>
            </div>
          </div>
        </div>
      </div>

      {/* Stat Cards */}
      {loadingOverview ? (
        <div className="grid grid-cols-2 md:grid-cols-3 xl:grid-cols-6 gap-4">
          {Array.from({ length: 6 }).map((_, i) => <SkeletonBlock key={i} className="h-28" />)}
        </div>
      ) : (
        <div className="grid grid-cols-2 md:grid-cols-3 xl:grid-cols-6 gap-4">
          {stats.map((s, i) => <StatCard key={i} {...s} />)}
        </div>
      )}

      {/* Two columns: recent jobs + live streams */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-5">
        {/* Recent Jobs */}
        <div className="bg-white border border-[rgba(0,0,0,0.08)] rounded-xl shadow-sm overflow-hidden">
          <div className="flex items-center justify-between px-5 py-3.5 border-b border-[rgba(0,0,0,0.06)]">
            <div className="flex items-center gap-2">
              <Icon icon="mdi:film-sync" className="text-blue-500 text-base" />
              <span className="text-[13px] font-bold text-[rgba(0,0,0,0.88)]">Recent Transcoding Jobs</span>
            </div>
            <span className="text-[11px] text-[rgba(0,0,0,0.4)]">{jobs.length} total</span>
          </div>
          {loadingJobs ? (
            <div className="p-4 space-y-2">{Array.from({ length: 4 }).map((_, i) => <SkeletonBlock key={i} className="h-12" />)}</div>
          ) : jobs.length === 0 ? (
            <div className="py-10 text-center">
              <Icon icon="mdi:film-off-outline" className="text-3xl text-[rgba(0,0,0,0.15)] mx-auto mb-2" />
              <p className="text-[12px] text-[rgba(0,0,0,0.35)]">No jobs yet</p>
            </div>
          ) : (
            <div className="divide-y divide-[rgba(0,0,0,0.04)]">
              {jobs.slice(0, 5).map(job => (
                <div key={job.id} className="flex items-center gap-3 px-5 py-3 hover:bg-[rgba(0,0,0,0.01)] transition-colors">
                  <div className={`w-8 h-8 rounded-lg flex items-center justify-center flex-shrink-0 ${
                    job.status === 'completed' ? 'bg-emerald-50' : job.status === 'processing' ? 'bg-blue-50' : job.status === 'failed' ? 'bg-red-50' : 'bg-amber-50'
                  }`}>
                    <Icon icon={job.status === 'completed' ? 'mdi:check' : job.status === 'processing' ? 'mdi:loading' : job.status === 'failed' ? 'mdi:close' : 'mdi:clock-outline'}
                      className={`text-sm ${job.status === 'completed' ? 'text-emerald-500' : job.status === 'processing' ? 'text-blue-500 animate-spin' : job.status === 'failed' ? 'text-red-500' : 'text-amber-500'}`} />
                  </div>
                  <div className="flex-1 min-w-0">
                    <p className="text-[12px] font-semibold text-[rgba(0,0,0,0.88)] truncate">{job.name}</p>
                    <p className="text-[11px] text-[rgba(0,0,0,0.4)]">{job.inputFormat} → {job.outputFormat} · {job.resolution} · {job.createdAt}</p>
                    {job.status === 'processing' && <ProgressBar value={job.progress} color="bg-blue-500" />}
                  </div>
                  <div className="text-right flex-shrink-0">
                    <StatusBadge status={job.status} />
                    {job.status === 'processing' && <p className="text-[10px] text-[rgba(0,0,0,0.35)] mt-0.5">{job.progress}%</p>}
                    {job.status === 'completed' && <p className="text-[10px] text-emerald-600 font-semibold mt-0.5">{job.savings}</p>}
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>

        {/* Live Streams */}
        <div className="bg-white border border-[rgba(0,0,0,0.08)] rounded-xl shadow-sm overflow-hidden">
          <div className="flex items-center justify-between px-5 py-3.5 border-b border-[rgba(0,0,0,0.06)]">
            <div className="flex items-center gap-2">
              <Icon icon="mdi:broadcast" className="text-red-500 text-base" />
              <span className="text-[13px] font-bold text-[rgba(0,0,0,0.88)]">Live Streams</span>
            </div>
            <span className="text-[11px] text-[rgba(0,0,0,0.4)]">{streams.filter(s => s.status === 'live').length} live now</span>
          </div>
          {loadingStreams ? (
            <div className="p-4 space-y-2">{Array.from({ length: 3 }).map((_, i) => <SkeletonBlock key={i} className="h-12" />)}</div>
          ) : streams.length === 0 ? (
            <div className="py-10 text-center">
              <Icon icon="mdi:broadcast-off" className="text-3xl text-[rgba(0,0,0,0.15)] mx-auto mb-2" />
              <p className="text-[12px] text-[rgba(0,0,0,0.35)]">No streams configured</p>
            </div>
          ) : (
            <div className="divide-y divide-[rgba(0,0,0,0.04)]">
              {streams.map(stream => (
                <div key={stream.id} className="flex items-center gap-3 px-5 py-3 hover:bg-[rgba(0,0,0,0.01)] transition-colors">
                  <div className={`w-8 h-8 rounded-lg flex items-center justify-center flex-shrink-0 ${stream.status === 'live' ? 'bg-red-50' : stream.status === 'error' ? 'bg-red-50' : 'bg-[rgba(0,0,0,0.04)]'}`}>
                    <Icon icon={stream.status === 'live' ? 'mdi:broadcast' : stream.status === 'error' ? 'mdi:broadcast-off' : 'mdi:broadcast'}
                      className={`text-sm ${stream.status === 'live' ? 'text-red-500' : stream.status === 'error' ? 'text-red-400' : 'text-[rgba(0,0,0,0.3)]'}`} />
                  </div>
                  <div className="flex-1 min-w-0">
                    <p className="text-[12px] font-semibold text-[rgba(0,0,0,0.88)] truncate">{stream.name}</p>
                    <p className="text-[11px] text-[rgba(0,0,0,0.4)]">{stream.resolution} · {stream.bitrate} · {stream.uptime}</p>
                  </div>
                  <div className="text-right flex-shrink-0">
                    <StatusBadge status={stream.status} />
                    {stream.status === 'live' && (
                      <p className="text-[10px] text-[rgba(0,0,0,0.4)] mt-0.5 flex items-center gap-1 justify-end">
                        <Icon icon="mdi:account-group-outline" className="text-[10px]" />
                        {stream.viewers} viewers
                      </p>
                    )}
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>
      </div>

      {/* Quick Start */}
      <div className="bg-white border border-[rgba(0,0,0,0.08)] rounded-xl shadow-sm p-5">
        <p className="text-[12px] font-bold uppercase tracking-wider text-[rgba(0,0,0,0.5)] mb-4">FFmpeg Quick Commands</p>
        <div className="grid grid-cols-1 md:grid-cols-3 gap-3">
          {[
            { label: 'Convert to HLS', icon: 'mdi:film', color: 'text-pink-500', bg: 'bg-pink-50', cmd: 'ffmpeg -i input.mp4 -hls_time 10 -hls_list_size 0 output.m3u8' },
            { label: 'Transcode to 720p', icon: 'mdi:resize', color: 'text-blue-500', bg: 'bg-blue-50', cmd: 'ffmpeg -i input.mp4 -vf scale=1280:720 -c:a copy output.mp4' },
            { label: 'Live RTMP → HLS', icon: 'mdi:broadcast', color: 'text-red-500', bg: 'bg-red-50', cmd: 'ffmpeg -i rtmp://localhost/live/stream -hls_time 2 stream.m3u8' },
          ].map((item, i) => (
            <div key={i} className="rounded-xl border border-[rgba(0,0,0,0.08)] p-3 hover:border-[rgba(0,0,0,0.15)] transition-colors">
              <div className="flex items-center gap-2 mb-2">
                <div className={`w-7 h-7 ${item.bg} rounded-lg flex items-center justify-center`}>
                  <Icon icon={item.icon} className={`${item.color} text-sm`} />
                </div>
                <span className="text-[12px] font-semibold text-[rgba(0,0,0,0.7)]">{item.label}</span>
              </div>
              <code className="text-[10px] font-mono text-[rgba(0,0,0,0.55)] bg-[rgba(0,0,0,0.03)] px-2 py-1.5 rounded-lg block truncate">{item.cmd}</code>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}

// ─── Transcode Tab ────────────────────────────────────────────────────────────

interface TranscodeTabProps {
  jobs: TranscodeJob[];
  loading: boolean;
  onCreateJob: (req: CreateJobRequest) => Promise<any>;
  onUploadFile: (file: File) => Promise<ApiStorageFile>;
  onRetryJob: (id: number) => Promise<any>;
}

function TranscodeTab({ jobs, loading, onCreateJob, onUploadFile, onRetryJob }: TranscodeTabProps) {
  const [showForm, setShowForm] = useState(false);
  const [filterStatus, setFilterStatus] = useState<JobStatus | 'all'>('all');
  const [sourceMode, setSourceMode] = useState<'url' | 'upload'>('url');
  const [sourceUrl, setSourceUrl] = useState('');
  const [localFile, setLocalFile] = useState<File | null>(null);
  const [uploadProgress, setUploadProgress] = useState<'idle' | 'uploading' | 'done'>('idle');
  const [outputFmt, setOutputFmt] = useState('HLS');
  const [resolution, setResolution] = useState('1080p');
  const [bitrate, setBitrate] = useState('');
  const [enableHLS, setEnableHLS] = useState(true);
  const [submitLoading, setSubmitLoading] = useState(false);
  const [submitError, setSubmitError] = useState<string | null>(null);
  const [copiedId, setCopiedId] = useState<string | null>(null);
  const [playerUrl, setPlayerUrl] = useState<string | null>(null);
  const [dashPlayerUrl, setDashPlayerUrl] = useState<string | null>(null);
  const [videoPlayerUrl, setVideoPlayerUrl] = useState<string | null>(null);
  const [retryingId, setRetryingId] = useState<number | null>(null);
  const [expandedError, setExpandedError] = useState<string | null>(null);
  const [embedJob, setEmbedJob] = useState<TranscodeJob | null>(null);

  // Mode toggle: persisted to localStorage
  const [transcodeMode, setTranscodeMode] = useState<'simple' | 'advanced'>(() => {
    try {
      const saved = localStorage.getItem('transcode-mode');
      return saved === 'advanced' ? 'advanced' : 'simple';
    } catch { return 'simple'; }
  });

  const handleModeChange = useCallback((mode: 'simple' | 'advanced') => {
    setTranscodeMode(mode);
    try { localStorage.setItem('transcode-mode', mode); } catch {}
  }, []);

  const filtered = filterStatus === 'all' ? jobs : jobs.filter(j => j.status === filterStatus);

  const handleRetry = useCallback(async (job: TranscodeJob) => {
    setRetryingId(job.rawId);
    try { await onRetryJob(job.rawId); } catch (e: any) { console.error('Retry failed', e); } finally { setRetryingId(null); }
  }, [onRetryJob]);

  const canSubmit = sourceMode === 'url' ? !!sourceUrl.trim() : !!localFile;

  const formState: import('../components/mediaops/SimpleMode').SimpleModeFormState = {
    sourceMode, sourceUrl, localFile, uploadProgress, outputFormat: outputFmt, resolution, bitrate, enableAdaptive: enableHLS,
  };

  const handleFormChange = useCallback((updates: Partial<import('../components/mediaops/SimpleMode').SimpleModeFormState>) => {
    if (updates.sourceMode !== undefined) setSourceMode(updates.sourceMode);
    if (updates.sourceUrl !== undefined) setSourceUrl(updates.sourceUrl);
    if (updates.localFile !== undefined) setLocalFile(updates.localFile);
    if (updates.uploadProgress !== undefined) setUploadProgress(updates.uploadProgress);
    if (updates.outputFormat !== undefined) setOutputFmt(updates.outputFormat);
    if (updates.resolution !== undefined) setResolution(updates.resolution);
    if (updates.bitrate !== undefined) setBitrate(updates.bitrate);
    if (updates.enableAdaptive !== undefined) setEnableHLS(updates.enableAdaptive);
  }, []);

  const submitJob = useCallback(async (advancedState?: AdvancedModeState) => {
    if (!canSubmit) return;
    setSubmitLoading(true);
    setSubmitError(null);
    try {
      let finalSourceUrl = sourceUrl.trim();
      if (sourceMode === 'upload' && localFile) {
        setUploadProgress('uploading');
        const uploaded = await onUploadFile(localFile);
        setUploadProgress('done');
        finalSourceUrl = uploaded.filePath;
      }
      const request: CreateJobRequest = { sourceUrl: finalSourceUrl, outputFormat: outputFmt, resolution, enableAdaptive: enableHLS };
      if (bitrate.trim()) request.bitrate = bitrate.trim();

      // Include advanced fields if provided
      if (advancedState) {
        if (advancedState.presetId) request.presetId = advancedState.presetId;
        if (advancedState.codec) request.codec = advancedState.codec;
        if (advancedState.priority !== 'NORMAL') request.priority = advancedState.priority;
        if (advancedState.startTime) request.startTime = advancedState.startTime;
        if (advancedState.endTime) request.endTime = advancedState.endTime;
        if (advancedState.subtitleMode) request.subtitleMode = advancedState.subtitleMode;
        if (advancedState.watermarkText) request.watermarkText = advancedState.watermarkText;
        if (advancedState.watermarkType) {
          request.watermarkPosition = advancedState.watermarkPosition;
          request.watermarkOpacity = advancedState.watermarkOpacity / 100; // Convert from percentage to 0-1
        }
        if (advancedState.thumbnailEnabled) {
          request.thumbnailEnabled = true;
          request.thumbnailIntervalSeconds = advancedState.thumbnailIntervalSeconds;
          request.thumbnailTimestamp = advancedState.thumbnailTimestamp;
        }
        if (advancedState.audioBitrate && advancedState.audioBitrate !== '128k') request.audioBitrate = advancedState.audioBitrate;
        if (advancedState.audioSampleRate) request.audioSampleRate = advancedState.audioSampleRate;
        if (advancedState.audioChannels) request.audioChannels = advancedState.audioChannels;
        if (advancedState.customFfmpegParams) request.customFfmpegParams = advancedState.customFfmpegParams;
        if (advancedState.scheduledAt) request.scheduledAt = advancedState.scheduledAt;
        if (advancedState.encryptionEnabled) request.encryptionEnabled = true;
      }

      await onCreateJob(request);
      setShowForm(false);
      setSourceUrl('');
      setLocalFile(null);
      setBitrate('');
      setUploadProgress('idle');
    } catch (e: any) {
      setSubmitError(e.message || 'Failed to submit job');
      setUploadProgress('idle');
    } finally {
      setSubmitLoading(false);
    }
  }, [canSubmit, sourceMode, sourceUrl, localFile, outputFmt, resolution, enableHLS, onCreateJob, onUploadFile]);

  const handleCancel = useCallback(() => {
    setShowForm(false);
    setSubmitError(null);
    setSourceUrl('');
    setLocalFile(null);
    setBitrate('');
    setSourceMode('url');
    setUploadProgress('idle');
  }, []);

  const copyCmd = useCallback((job: TranscodeJob) => {
    const cmd = job.ffmpegCommand ||
      `ffmpeg -i "${job.name}" -vf scale=${job.resolution === '4K' ? '3840:2160' : job.resolution === '1080p' ? '1920:1080' : '1280:720'} -c:v libx264 -preset fast -b:v ${job.bitrate} ${job.outputFormat === 'HLS' ? '-hls_time 10 -hls_list_size 0 output.m3u8' : 'output.mp4'}`;
    navigator.clipboard.writeText(cmd);
    setCopiedId(job.id);
    setTimeout(() => setCopiedId(null), 1800);
  }, []);

  const STATUS_FILTERS: { id: JobStatus | 'all'; label: string }[] = [
    { id: 'all', label: 'All' },
    { id: 'processing', label: 'Processing' },
    { id: 'queued', label: 'Queued' },
    { id: 'completed', label: 'Completed' },
    { id: 'failed', label: 'Failed' },
  ];

  return (
    <div className="space-y-5">
      {/* Header row */}
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-2 flex-wrap">
          {STATUS_FILTERS.map(f => (
            <button key={f.id} onClick={() => setFilterStatus(f.id)}
              className={`px-3 py-1.5 rounded-full text-[12px] font-semibold border transition-all ${
                filterStatus === f.id ? 'bg-[rgba(0,0,0,0.88)] text-white border-transparent' : 'bg-white text-[rgba(0,0,0,0.55)] border-[rgba(0,0,0,0.10)] hover:bg-[rgba(0,0,0,0.03)]'
              }`}>{f.label}
            </button>
          ))}
        </div>
        <button onClick={() => setShowForm(v => !v)}
          className="flex items-center gap-2 px-4 py-2 bg-gradient-to-r from-pink-500 to-rose-600 text-white text-[12px] font-bold rounded-xl shadow hover:shadow-md transition-all hover:scale-[1.02]">
          <Icon icon="mdi:plus" className="text-base" />
          New Job
        </button>
      </div>

      {/* New Job Form */}
      <AnimatePresence>
        {showForm && (
          <motion.div initial={{ opacity: 0, y: -8 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -8 }} transition={{ duration: 0.2 }}
            className="bg-white border border-pink-100 rounded-2xl shadow-sm p-5">
            <div className="flex items-center justify-between mb-4">
              <p className="text-[13px] font-bold text-[rgba(0,0,0,0.88)] flex items-center gap-2">
                <Icon icon="mdi:film-plus" className="text-pink-500" />
                New Transcoding Job
              </p>
              {/* Mode Toggle */}
              <div className="flex items-center gap-1 bg-[rgba(0,0,0,0.05)] rounded-lg p-0.5">
                <button onClick={() => handleModeChange('simple')}
                  className={`px-3 py-1.5 rounded-md text-[11px] font-bold transition-all ${transcodeMode === 'simple' ? 'bg-white shadow text-pink-600' : 'text-[rgba(0,0,0,0.45)] hover:text-[rgba(0,0,0,0.65)]'}`}>
                  <span className="flex items-center gap-1">
                    <Icon icon="mdi:lightning-bolt" className="text-sm" />
                    Simple
                  </span>
                </button>
                <button onClick={() => handleModeChange('advanced')}
                  className={`px-3 py-1.5 rounded-md text-[11px] font-bold transition-all ${transcodeMode === 'advanced' ? 'bg-white shadow text-pink-600' : 'text-[rgba(0,0,0,0.45)] hover:text-[rgba(0,0,0,0.65)]'}`}>
                  <span className="flex items-center gap-1">
                    <Icon icon="mdi:tune-variant" className="text-sm" />
                    Advanced
                  </span>
                </button>
              </div>
            </div>

            {transcodeMode === 'simple' ? (
              <SimpleMode
                formState={formState}
                onFormChange={handleFormChange}
                onSubmit={submitJob}
                onCancel={handleCancel}
                canSubmit={canSubmit}
                submitLoading={submitLoading}
                submitError={submitError}
              />
            ) : (
              <AdvancedMode
                formState={formState}
                onFormChange={handleFormChange}
                onSubmit={submitJob}
                onCancel={handleCancel}
                canSubmit={canSubmit}
                submitLoading={submitLoading}
                submitError={submitError}
              />
            )}
          </motion.div>
        )}
      </AnimatePresence>

      {/* Jobs Table */}
      <div className="bg-white border border-[rgba(0,0,0,0.08)] rounded-2xl shadow-sm overflow-hidden">
        {loading ? (
          <div className="p-5 space-y-3">{Array.from({ length: 5 }).map((_, i) => <SkeletonBlock key={i} className="h-12" />)}</div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full">
              <thead>
                <tr className="bg-[rgba(0,0,0,0.02)] border-b border-[rgba(0,0,0,0.06)]">
                  {['File', 'Format', 'Resolution', 'Status', 'Progress', 'Duration', 'Savings', 'Created', ''].map(h => (
                    <th key={h} className="px-4 py-3 text-left text-[10px] font-bold uppercase tracking-wider text-[rgba(0,0,0,0.4)]">{h}</th>
                  ))}
                </tr>
              </thead>
              <tbody className="divide-y divide-[rgba(0,0,0,0.04)]">
                {filtered.map(job => (
                  <React.Fragment key={job.id}>
                    <tr className={`transition-colors ${job.status === 'failed' ? 'bg-red-50/40 hover:bg-red-50' : 'hover:bg-[rgba(0,0,0,0.01)]'}`}>
                      <td className="px-4 py-3">
                        <div className="flex items-center gap-2">
                          <div className="w-7 h-7 rounded-lg bg-[rgba(0,0,0,0.04)] flex items-center justify-center flex-shrink-0">
                            <Icon icon="mdi:file-video-outline" className="text-[rgba(0,0,0,0.4)] text-sm" />
                          </div>
                          <span className="text-[12px] font-semibold text-[rgba(0,0,0,0.88)] max-w-[160px] truncate">{job.name}</span>
                        </div>
                      </td>
                      <td className="px-4 py-3">
                        <span className="text-[11px] font-mono font-semibold text-[rgba(0,0,0,0.55)]">{job.inputFormat} → {job.outputFormat}</span>
                      </td>
                      <td className="px-4 py-3 text-[12px] text-[rgba(0,0,0,0.65)]">{job.resolution}</td>
                      <td className="px-4 py-3"><StatusBadge status={job.status} /></td>
                      <td className="px-4 py-3 w-28">
                        <div className="flex items-center gap-2">
                          <ProgressBar value={job.progress} color={job.status === 'failed' ? 'bg-red-400' : job.status === 'completed' ? 'bg-emerald-500' : 'bg-blue-500'} />
                          <span className="text-[10px] text-[rgba(0,0,0,0.4)] whitespace-nowrap">{job.progress}%</span>
                        </div>
                      </td>
                      <td className="px-4 py-3 text-[12px] text-[rgba(0,0,0,0.55)]">{job.duration}</td>
                      <td className="px-4 py-3">
                        <span className={`text-[12px] font-bold ${job.savings !== '—' ? 'text-emerald-600' : 'text-[rgba(0,0,0,0.3)]'}`}>{job.savings}</span>
                      </td>
                      <td className="px-4 py-3 text-[11px] text-[rgba(0,0,0,0.4)] whitespace-nowrap">{job.createdAt}</td>
                      <td className="px-4 py-3">
                        <div className="flex items-center gap-1">
                          {job.status === 'failed' && (
                            <>
                              <button onClick={() => handleRetry(job)} disabled={retryingId === job.rawId} title="Retry job"
                                className="flex items-center gap-1 px-2 py-1 rounded-lg bg-amber-50 border border-amber-200 hover:bg-amber-100 transition-colors text-amber-700 text-[11px] font-bold disabled:opacity-50">
                                <Icon icon={retryingId === job.rawId ? 'mdi:loading' : 'mdi:refresh'} className={`text-sm ${retryingId === job.rawId ? 'animate-spin' : ''}`} />
                                Retry
                              </button>
                              <button onClick={() => setExpandedError(expandedError === job.id ? null : job.id)} title="Show error details"
                                className="p-1.5 rounded-lg hover:bg-red-50 transition-colors text-red-400">
                                <Icon icon="mdi:alert-circle-outline" className="text-sm" />
                              </button>
                            </>
                          )}
                          {job.hlsUrl && (
                            <>
                              <button onClick={() => setPlayerUrl(`http://localhost:8084${job.hlsUrl}`)}
                                title="Preview HLS"
                                className="p-1.5 rounded-lg hover:bg-emerald-50 transition-colors text-[rgba(0,0,0,0.35)] hover:text-emerald-600">
                                <Icon icon="mdi:play-circle-outline" className="text-sm" />
                              </button>
                              <button onClick={() => setEmbedJob(job)}
                                title="Get embed code"
                                className="p-1.5 rounded-lg hover:bg-violet-50 transition-colors text-[rgba(0,0,0,0.35)] hover:text-violet-600">
                                <Icon icon="mdi:code-tags" className="text-sm" />
                              </button>
                            </>
                          )}
                          {job.dashUrl && (
                            <button onClick={() => setDashPlayerUrl(`http://localhost:8084${job.dashUrl}`)}
                              title="Preview DASH"
                              className="p-1.5 rounded-lg hover:bg-violet-50 transition-colors text-[rgba(0,0,0,0.35)] hover:text-violet-600">
                              <Icon icon="mdi:play-circle-outline" className="text-sm" />
                            </button>
                          )}
                          {job.videoUrl && (
                            <button onClick={() => setVideoPlayerUrl(`http://localhost:8084${job.videoUrl}`)}
                              title={`Preview ${job.outputFormat}`}
                              className="p-1.5 rounded-lg hover:bg-blue-50 transition-colors text-[rgba(0,0,0,0.35)] hover:text-blue-600">
                              <Icon icon="mdi:play-circle-outline" className="text-sm" />
                            </button>
                          )}
                          <button onClick={() => copyCmd(job)} title="Copy FFmpeg command"
                            className="p-1.5 rounded-lg hover:bg-[rgba(0,0,0,0.05)] transition-colors text-[rgba(0,0,0,0.35)] hover:text-[rgba(0,0,0,0.7)]">
                            <Icon icon={copiedId === job.id ? 'mdi:check' : 'mdi:content-copy'} className={`text-sm ${copiedId === job.id ? 'text-emerald-500' : ''}`} />
                          </button>
                        </div>
                      </td>
                    </tr>
                    {expandedError === job.id && (
                      <tr className="bg-red-50">
                        <td colSpan={9} className="px-4 py-3">
                          <div className="flex items-start gap-2">
                            <Icon icon="mdi:bug-outline" className="text-red-500 mt-0.5 flex-shrink-0" />
                            <div className="flex-1">
                              <p className="text-[11px] font-bold text-red-700 mb-0.5">FFmpeg Error Details</p>
                              <pre className="text-[10px] font-mono text-red-600 whitespace-pre-wrap break-all leading-relaxed">
                                {job.errorMessage ?? 'No error details recorded (job failed before error logging was enabled — click Retry to reprocess with full logging).'}
                              </pre>
                            </div>
                            <button onClick={() => setExpandedError(null)} className="p-1 hover:bg-red-100 rounded">
                              <Icon icon="mdi:close" className="text-red-400 text-sm" />
                            </button>
                          </div>
                        </td>
                      </tr>
                    )}
                  </React.Fragment>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {!loading && filtered.length === 0 && (
          <div className="py-16 text-center">
            <Icon icon="mdi:film-off-outline" className="text-4xl text-[rgba(0,0,0,0.15)] mx-auto mb-3" />
            <p className="text-[13px] font-semibold text-[rgba(0,0,0,0.4)]">No jobs found</p>
          </div>
        )}
      </div>

      {playerUrl && <HlsPlayer src={playerUrl} title="HLS Playback" onClose={() => setPlayerUrl(null)} />}
      {dashPlayerUrl && <DashPlayer src={dashPlayerUrl} title="DASH Playback" onClose={() => setDashPlayerUrl(null)} />}
      {videoPlayerUrl && <NativeVideoPlayer src={videoPlayerUrl} onClose={() => setVideoPlayerUrl(null)} />}
      {embedJob?.hlsUrl && (
        <EmbedModal
          hlsUrl={`http://localhost:8084${embedJob.hlsUrl}`}
          title={embedJob.name}
          onClose={() => setEmbedJob(null)}
        />
      )}
    </div>
  );
}

// ─── Browser Stream Capture ───────────────────────────────────────────────────

type CaptureSource = 'screen' | 'webcam';
type CaptureState  = 'idle' | 'previewing' | 'live' | 'error';

function BrowserStreamCapture({ streamKey, onClose }: { streamKey: string; onClose: () => void }) {
  const [captureState, setCaptureState] = useState<CaptureState>('idle');
  const [source, setSource]             = useState<CaptureSource>('screen');
  const [error, setError]               = useState<string | null>(null);
  const [bytesSent, setBytesSent]       = useState(0);

  const localVideoRef   = useRef<HTMLVideoElement>(null);
  const mediaStreamRef  = useRef<MediaStream | null>(null);
  const mediaRecorderRef = useRef<MediaRecorder | null>(null);
  const wsRef           = useRef<WebSocket | null>(null);

  const startPreview = useCallback(async (src: CaptureSource) => {
    try {
      setError(null);
      const stream = src === 'screen'
        ? await navigator.mediaDevices.getDisplayMedia({ video: { frameRate: 30 }, audio: true })
        : await navigator.mediaDevices.getUserMedia({ video: { width: 1280, height: 720, frameRate: 30 }, audio: true });

      mediaStreamRef.current = stream;
      if (localVideoRef.current) {
        localVideoRef.current.srcObject = stream;
        localVideoRef.current.muted = true;
        localVideoRef.current.play().catch(() => {});
      }
      setCaptureState('previewing');

      // Auto-stop if user closes screen share from browser UI
      stream.getVideoTracks()[0]?.addEventListener('ended', () => stopAll());
    } catch (e: any) {
      setError(e.message || 'Permission denied');
      setCaptureState('error');
    }
  }, []);

  const startLive = useCallback(() => {
    const stream = mediaStreamRef.current;
    if (!stream) return;

    const mimeType = MediaRecorder.isTypeSupported('video/webm;codecs=h264,opus')
      ? 'video/webm;codecs=h264,opus'
      : 'video/webm;codecs=vp8,opus';

    const ws = new WebSocket(`ws://${location.host}/browser-stream/${streamKey}`);
    ws.binaryType = 'arraybuffer';
    wsRef.current = ws;

    ws.onopen = () => {
      const recorder = new MediaRecorder(stream, { mimeType, videoBitsPerSecond: 1_500_000 });
      mediaRecorderRef.current = recorder;

      recorder.ondataavailable = (e) => {
        if (e.data.size > 0 && ws.readyState === WebSocket.OPEN) {
          ws.send(e.data);
          setBytesSent(prev => prev + e.data.size);
        }
      };
      recorder.start(500); // 500 ms chunks
      setCaptureState('live');
    };

    ws.onerror = () => { setError('WebSocket connection failed'); setCaptureState('error'); };
    ws.onclose = () => { if (captureState === 'live') setCaptureState('idle'); };
  }, [streamKey, captureState]);

  const stopAll = useCallback(() => {
    mediaRecorderRef.current?.stop();
    mediaRecorderRef.current = null;
    wsRef.current?.close();
    wsRef.current = null;
    mediaStreamRef.current?.getTracks().forEach(t => t.stop());
    mediaStreamRef.current = null;
    if (localVideoRef.current) localVideoRef.current.srcObject = null;
    setCaptureState('idle');
    setBytesSent(0);
    onClose();
  }, [onClose]);

  useEffect(() => () => {
    mediaRecorderRef.current?.stop();
    wsRef.current?.close();
    mediaStreamRef.current?.getTracks().forEach(t => t.stop());
  }, []);

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/80 backdrop-blur-sm">
      <div className="bg-white rounded-2xl shadow-2xl w-full max-w-2xl mx-4 overflow-hidden">
        {/* Header */}
        <div className="flex items-center justify-between px-5 py-4 border-b border-[rgba(0,0,0,0.08)]">
          <div className="flex items-center gap-2">
            {captureState === 'live' && <span className="w-2.5 h-2.5 rounded-full bg-red-500 animate-pulse" />}
            <span className="text-[14px] font-bold text-[rgba(0,0,0,0.88)]">
              {captureState === 'live' ? 'Broadcasting Live' : 'Go Live from Browser'}
            </span>
            {captureState === 'live' && (
              <span className="px-2 py-0.5 rounded-full bg-red-50 border border-red-200 text-[10px] font-bold text-red-600 uppercase">Live</span>
            )}
          </div>
          <button onClick={captureState === 'live' ? stopAll : onClose}
            className="p-1.5 rounded-lg hover:bg-[rgba(0,0,0,0.06)] transition-colors">
            <Icon icon="mdi:close" className="text-[rgba(0,0,0,0.5)] text-lg" />
          </button>
        </div>

        {/* Video preview */}
        <div className="relative bg-black" style={{ aspectRatio: '16/9' }}>
          <video ref={localVideoRef} autoPlay muted playsInline
            className="w-full h-full object-contain" />
          {captureState === 'idle' && (
            <div className="absolute inset-0 flex flex-col items-center justify-center gap-4">
              <Icon icon="mdi:video-outline" className="text-5xl text-white/30" />
              <p className="text-white/50 text-[13px]">Choose your source below</p>
            </div>
          )}
          {captureState === 'live' && (
            <div className="absolute top-3 right-3 flex items-center gap-2 bg-black/60 backdrop-blur-sm rounded-full px-3 py-1.5">
              <span className="w-2 h-2 rounded-full bg-red-500 animate-pulse" />
              <span className="text-white text-[11px] font-bold">
                {bytesSent > 1048576 ? `${(bytesSent / 1048576).toFixed(1)} MB sent` : `${(bytesSent / 1024).toFixed(0)} KB sent`}
              </span>
            </div>
          )}
        </div>

        {/* Controls */}
        <div className="p-5">
          {error && (
            <div className="mb-4 bg-red-50 border border-red-200 rounded-xl px-4 py-2.5 text-[12px] text-red-700 flex items-center gap-2">
              <Icon icon="mdi:alert-circle-outline" className="text-red-500 flex-shrink-0" />
              {error}
            </div>
          )}

          {captureState === 'idle' && (
            <div className="flex flex-col gap-3">
              <p className="text-[12px] text-[rgba(0,0,0,0.5)] font-semibold uppercase tracking-wide">Select Source</p>
              <div className="grid grid-cols-2 gap-3">
                {(['screen', 'webcam'] as CaptureSource[]).map(src => (
                  <button key={src} onClick={() => { setSource(src); startPreview(src); }}
                    className={`flex items-center gap-3 p-4 rounded-xl border-2 transition-all hover:border-red-300 hover:bg-red-50/40 ${source === src && captureState !== 'idle' ? 'border-red-400 bg-red-50' : 'border-[rgba(0,0,0,0.1)]'}`}>
                    <div className="w-10 h-10 rounded-xl bg-red-50 flex items-center justify-center flex-shrink-0">
                      <Icon icon={src === 'screen' ? 'mdi:monitor-screenshot' : 'mdi:webcam'} className="text-red-500 text-xl" />
                    </div>
                    <div className="text-left">
                      <p className="text-[13px] font-bold text-[rgba(0,0,0,0.8)]">{src === 'screen' ? 'Screen Share' : 'Webcam'}</p>
                      <p className="text-[11px] text-[rgba(0,0,0,0.4)]">{src === 'screen' ? 'Share your display' : 'Use your camera'}</p>
                    </div>
                  </button>
                ))}
              </div>
            </div>
          )}

          {captureState === 'previewing' && (
            <div className="flex items-center gap-3">
              <button onClick={startLive}
                className="flex items-center gap-2 px-6 py-2.5 bg-gradient-to-r from-red-500 to-rose-600 text-white text-[13px] font-bold rounded-xl shadow hover:shadow-md transition-all hover:scale-[1.02]">
                <Icon icon="mdi:broadcast" />
                Start Broadcasting
              </button>
              <button onClick={() => { mediaStreamRef.current?.getTracks().forEach(t => t.stop()); mediaStreamRef.current = null; if (localVideoRef.current) localVideoRef.current.srcObject = null; setCaptureState('idle'); }}
                className="px-4 py-2.5 text-[12px] font-semibold text-[rgba(0,0,0,0.5)] hover:text-[rgba(0,0,0,0.88)] transition-colors">
                Change Source
              </button>
              <p className="text-[11px] text-[rgba(0,0,0,0.4)] ml-auto">Preview looks good? Hit broadcast.</p>
            </div>
          )}

          {captureState === 'live' && (
            <div className="flex items-center gap-3">
              <button onClick={stopAll}
                className="flex items-center gap-2 px-6 py-2.5 bg-[rgba(0,0,0,0.06)] text-[rgba(0,0,0,0.7)] text-[13px] font-bold rounded-xl hover:bg-[rgba(0,0,0,0.1)] transition-colors">
                <Icon icon="mdi:stop" />
                Stop Broadcasting
              </button>
              <div className="ml-auto flex items-center gap-2 text-[12px] text-[rgba(0,0,0,0.5)]">
                <Icon icon="mdi:information-outline" className="text-sm" />
                HLS stream available in the stream card
              </div>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}

// ─── Embed Code Modal ─────────────────────────────────────────────────────────

function EmbedModal({ hlsUrl, title, onClose }: { hlsUrl: string; title: string; onClose: () => void }) {
  const [tab, setTab] = useState<'hlsjs' | 'native' | 'react'>('hlsjs');
  const [copied, setCopied] = useState(false);

  const fullUrl = hlsUrl.startsWith('http') ? hlsUrl : `http://localhost:8084${hlsUrl}`;

  const snippets: Record<string, string> = {
    hlsjs: `<!-- HLS.js (works in Chrome, Firefox, Edge) -->
<script src="https://cdn.jsdelivr.net/npm/hls.js@latest"></script>
<video id="player" controls style="width:100%; max-width:960px;" playsinline></video>
<script>
  var video = document.getElementById('player');
  if (Hls.isSupported()) {
    var hls = new Hls({ lowLatencyMode: true });
    hls.loadSource('${fullUrl}');
    hls.attachMedia(video);
  } else if (video.canPlayType('application/vnd.apple.mpegurl')) {
    video.src = '${fullUrl}'; // Safari native HLS
  }
</script>`,
    native: `<!-- HTML5 native (Safari / iOS only) -->
<video src="${fullUrl}"
  controls playsinline autoplay
  style="width:100%; max-width:960px;">
</video>`,
    react: `import Hls from 'hls.js';
import { useEffect, useRef } from 'react';

export function VideoPlayer() {
  const ref = useRef(null);
  useEffect(() => {
    const video = ref.current;
    if (!video) return;
    if (Hls.isSupported()) {
      const hls = new Hls({ lowLatencyMode: true });
      hls.loadSource('${fullUrl}');
      hls.attachMedia(video);
      return () => hls.destroy();
    } else if (video.canPlayType('application/vnd.apple.mpegurl')) {
      video.src = '${fullUrl}';
    }
  }, []);
  return <video ref={ref} controls playsInline style={{ width: '100%' }} />;
}`,
  };

  const copy = () => {
    navigator.clipboard.writeText(snippets[tab]);
    setCopied(true);
    setTimeout(() => setCopied(false), 1800);
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm" onClick={onClose}>
      <div className="bg-white rounded-2xl shadow-2xl w-full max-w-2xl mx-4 overflow-hidden" onClick={e => e.stopPropagation()}>
        <div className="flex items-center justify-between px-5 py-4 border-b border-[rgba(0,0,0,0.08)]">
          <div className="flex items-center gap-2">
            <div className="w-8 h-8 rounded-xl bg-violet-50 flex items-center justify-center">
              <Icon icon="mdi:code-tags" className="text-violet-500 text-base" />
            </div>
            <div>
              <p className="text-[14px] font-bold text-[rgba(0,0,0,0.88)]">Embed Code</p>
              <p className="text-[11px] text-[rgba(0,0,0,0.4)] truncate max-w-xs">{title}</p>
            </div>
          </div>
          <button onClick={onClose} className="p-1.5 rounded-lg hover:bg-[rgba(0,0,0,0.06)] transition-colors">
            <Icon icon="mdi:close" className="text-[rgba(0,0,0,0.4)] text-lg" />
          </button>
        </div>
        <div className="p-5">
          <div className="flex items-center gap-1 mb-4 bg-[rgba(0,0,0,0.04)] rounded-xl p-1">
            {([['hlsjs', 'HLS.js (Universal)'], ['native', 'HTML5 Native'], ['react', 'React Component']] as const).map(([id, label]) => (
              <button key={id} onClick={() => setTab(id)}
                className={`flex-1 py-1.5 rounded-lg text-[12px] font-semibold transition-all ${tab === id ? 'bg-white shadow text-[rgba(0,0,0,0.88)]' : 'text-[rgba(0,0,0,0.5)] hover:text-[rgba(0,0,0,0.7)]'}`}>
                {label}
              </button>
            ))}
          </div>
          {tab === 'hlsjs' && <p className="text-[11px] text-[rgba(0,0,0,0.45)] mb-2">Works in Chrome, Firefox, Edge. Falls back to native HLS on Safari.</p>}
          {tab === 'native' && <p className="text-[11px] text-[rgba(0,0,0,0.45)] mb-2">Safari and iOS only. For cross-browser support use the HLS.js snippet.</p>}
          {tab === 'react' && <p className="text-[11px] text-[rgba(0,0,0,0.45)] mb-2">React hook-based component. Install <code className="bg-[rgba(0,0,0,0.06)] px-1 rounded">npm install hls.js</code> first.</p>}
          <div className="relative">
            <pre className="bg-[rgba(0,0,0,0.03)] border border-[rgba(0,0,0,0.08)] rounded-xl p-4 text-[11px] font-mono text-[rgba(0,0,0,0.7)] overflow-x-auto whitespace-pre leading-relaxed max-h-72">{snippets[tab]}</pre>
            <button onClick={copy}
              className="absolute top-2 right-2 flex items-center gap-1.5 px-3 py-1.5 bg-white border border-[rgba(0,0,0,0.1)] rounded-lg text-[11px] font-semibold text-[rgba(0,0,0,0.6)] hover:bg-[rgba(0,0,0,0.03)] transition-colors shadow-sm">
              <Icon icon={copied ? 'mdi:check' : 'mdi:content-copy'} className={`text-sm ${copied ? 'text-emerald-500' : ''}`} />
              {copied ? 'Copied!' : 'Copy'}
            </button>
          </div>
          <div className="mt-3 rounded-xl bg-[rgba(0,0,0,0.02)] border border-[rgba(0,0,0,0.06)] px-3 py-2">
            <p className="text-[10px] font-bold uppercase tracking-wide text-[rgba(0,0,0,0.4)] mb-0.5">HLS Source URL</p>
            <code className="text-[11px] font-mono text-[rgba(0,0,0,0.6)] break-all">{fullUrl}</code>
          </div>
        </div>
      </div>
    </div>
  );
}

// ─── HLS Player ──────────────────────────────────────────────────────────────

function HlsPlayer({ src, title = 'Watch Live', onClose }: { src: string; title?: string; onClose: () => void }) {
  const videoRef = useRef<HTMLVideoElement>(null);

  useEffect(() => {
    const video = videoRef.current;
    if (!video) return;
    let hls: any = null;

    // Safari supports HLS natively
    if (video.canPlayType('application/vnd.apple.mpegurl')) {
      video.src = src;
      video.play().catch(() => {});
      return;
    }

    // Chrome / Firefox — use hls.js
    import('hls.js').then(({ default: Hls }) => {
      if (!Hls.isSupported()) return;
      hls = new Hls({ lowLatencyMode: true });
      hls.loadSource(src);
      hls.attachMedia(video);
      hls.on(Hls.Events.MANIFEST_PARSED, () => { video.play().catch(() => {}); });
    });

    return () => { if (hls) hls.destroy(); };
  }, [src]);

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/70 backdrop-blur-sm" onClick={onClose}>
      <div className="relative w-full max-w-3xl mx-4" onClick={e => e.stopPropagation()}>
        <div className="flex items-center justify-between mb-2 px-1">
          <div className="flex items-center gap-2">
            <span className="w-2 h-2 rounded-full bg-red-500 animate-pulse" />
            <span className="text-white text-[13px] font-semibold">{title}</span>
            <code className="text-[11px] font-mono text-white/50 truncate max-w-xs">{src}</code>
          </div>
          <button onClick={onClose} className="p-1 rounded-lg hover:bg-white/10 transition-colors">
            <Icon icon="mdi:close" className="text-white text-lg" />
          </button>
        </div>
        <video
          ref={videoRef}
          controls
          autoPlay
          muted
          playsInline
          className="w-full rounded-2xl bg-black"
          style={{ aspectRatio: '16/9' }}
        />
      </div>
    </div>
  );
}

// ─── Native Video Player (MP4 / WEBM / MKV) ──────────────────────────────────

function NativeVideoPlayer({ src, onClose }: { src: string; onClose: () => void }) {
  const ext = src.split('.').pop()?.toUpperCase() ?? 'VIDEO';
  const formatColor: Record<string, string> = {
    MP4: 'bg-blue-500', WEBM: 'bg-violet-500', MKV: 'bg-amber-500', MOV: 'bg-pink-500',
  };
  const dot = formatColor[ext] ?? 'bg-gray-500';

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/70 backdrop-blur-sm" onClick={onClose}>
      <div className="relative w-full max-w-3xl mx-4" onClick={e => e.stopPropagation()}>
        <div className="flex items-center justify-between mb-2 px-1">
          <div className="flex items-center gap-2">
            <span className={`w-2 h-2 rounded-full ${dot}`} />
            <span className="text-white text-[13px] font-semibold">{ext} Playback</span>
            <code className="text-[11px] font-mono text-white/50 truncate max-w-xs">{src}</code>
          </div>
          <button onClick={onClose} className="p-1 rounded-lg hover:bg-white/10 transition-colors">
            <Icon icon="mdi:close" className="text-white text-lg" />
          </button>
        </div>
        <video
          src={src}
          controls
          autoPlay
          muted
          playsInline
          className="w-full rounded-2xl bg-black"
          style={{ aspectRatio: '16/9' }}
        />
        {ext === 'MKV' && (
          <p className="text-white/40 text-[11px] text-center mt-2">
            MKV has limited browser support — Chrome plays most MKV files; Safari may not.
          </p>
        )}
      </div>
    </div>
  );
}

// ─── DASH Player ─────────────────────────────────────────────────────────────

function DashPlayer({ src, title = 'DASH Playback', onClose }: { src: string; title?: string; onClose: () => void }) {
  const videoRef = useRef<HTMLVideoElement>(null);
  const [currentQuality, setCurrentQuality] = useState<string | null>(null);
  const [qualityList, setQualityList] = useState<string[]>([]);
  const [switching, setSwitching] = useState(false);

  useEffect(() => {
    const video = videoRef.current;
    if (!video) return;
    let player: any = null;

    import('dashjs').then((dashjs) => {
      const factory = (dashjs as any).default ?? dashjs;
      player = factory.MediaPlayer().create();
      player.initialize(video, src, true);

      player.on(factory.MediaPlayer.events.STREAM_INITIALIZED, () => {
        const bitrates = player.getBitrateInfoListFor('video') as any[];
        if (bitrates?.length) {
          setQualityList(bitrates.map((b: any) => `${b.height}p`));
        }
      });

      player.on(factory.MediaPlayer.events.QUALITY_CHANGE_REQUESTED, () => {
        setSwitching(true);
      });

      player.on(factory.MediaPlayer.events.QUALITY_CHANGE_RENDERED, (e: any) => {
        if (e.mediaType === 'video') {
          const bitrates = player.getBitrateInfoListFor('video') as any[];
          const q = bitrates?.[e.newQuality];
          if (q) setCurrentQuality(`${q.height}p`);
          setSwitching(false);
        }
      });
    });

    return () => { if (player) { try { player.destroy(); } catch {} } };
  }, [src]);

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/70 backdrop-blur-sm" onClick={onClose}>
      <div className="relative w-full max-w-3xl mx-4" onClick={e => e.stopPropagation()}>
        <div className="flex items-center justify-between mb-2 px-1">
          <div className="flex items-center gap-2">
            <span className="w-2 h-2 rounded-full bg-violet-500 animate-pulse" />
            <span className="text-white text-[13px] font-semibold">{title}</span>
            <code className="text-[11px] font-mono text-white/50 truncate max-w-xs">{src}</code>
          </div>
          <div className="flex items-center gap-2">
            {qualityList.length > 1 && (
              <div className="flex items-center gap-1.5 bg-black/50 backdrop-blur-sm rounded-full px-3 py-1">
                <Icon icon="mdi:layers-outline" className="text-violet-400 text-xs" />
                <span className="text-[10px] text-white/60 font-semibold">ABR</span>
                <span className="text-[10px] text-white/40">·</span>
                {qualityList.map((q, i) => (
                  <span key={q} className={`text-[10px] font-bold transition-colors ${
                    currentQuality === q ? 'text-violet-300' : 'text-white/30'
                  }`}>{q}{i < qualityList.length - 1 ? '' : ''}</span>
                )).reduce<React.ReactNode[]>((acc, el, i) => i === 0 ? [el] : [...acc, <span key={`sep-${i}`} className="text-white/20 text-[10px]">·</span>, el], [])}
                {switching && <Icon icon="mdi:loading" className="text-violet-400 text-xs animate-spin ml-1" />}
              </div>
            )}
            {currentQuality && (
              <div className={`flex items-center gap-1 px-2.5 py-1 rounded-full text-[11px] font-bold transition-all ${
                switching ? 'bg-amber-500/20 border border-amber-400/40 text-amber-300' : 'bg-violet-500/20 border border-violet-400/40 text-violet-300'
              }`}>
                <Icon icon={switching ? 'mdi:swap-vertical' : 'mdi:quality-high'} className="text-xs" />
                {switching ? 'Switching…' : currentQuality}
              </div>
            )}
            <button onClick={onClose} className="p-1 rounded-lg hover:bg-white/10 transition-colors">
              <Icon icon="mdi:close" className="text-white text-lg" />
            </button>
          </div>
        </div>
        <div className="relative">
          <video
            ref={videoRef}
            controls
            muted
            playsInline
            className="w-full rounded-2xl bg-black"
            style={{ aspectRatio: '16/9' }}
          />
          {switching && (
            <div className="absolute top-3 left-3 flex items-center gap-1.5 bg-black/60 backdrop-blur-sm rounded-full px-3 py-1.5">
              <Icon icon="mdi:swap-vertical" className="text-amber-400 text-xs animate-pulse" />
              <span className="text-white text-[11px] font-semibold">Adjusting quality…</span>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}

// ─── Live Streams Tab ─────────────────────────────────────────────────────────

interface StreamsTabProps {
  streams: LiveStream[];
  loading: boolean;
  onCreateStream: (req: CreateStreamRequest) => Promise<any>;
  onStartStream: (id: number) => Promise<void>;
  onStopStream: (id: number) => Promise<void>;
}

function ObsSetupModal({ stream, onClose }: { stream: LiveStream; onClose: () => void }) {
  const [copied, setCopied] = useState<string | null>(null);
  const copy = (text: string, id: string) => { navigator.clipboard.writeText(text); setCopied(id); setTimeout(() => setCopied(null), 1800); };
  const steps = [
    'Open OBS Studio → Settings → Stream',
    'Set Service to "Custom..."',
    `Set Server to: rtmp://localhost:1935/live`,
    `Set Stream Key to the key below`,
    'Click Apply → OK',
    'Click "Start Streaming" in OBS',
    'Viewers use the HLS Playback URL',
  ];
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm" onClick={onClose}>
      <div className="bg-white rounded-2xl shadow-2xl w-full max-w-lg mx-4 overflow-hidden" onClick={e => e.stopPropagation()}>
        <div className="flex items-center justify-between px-5 py-4 border-b border-[rgba(0,0,0,0.08)]">
          <div className="flex items-center gap-2">
            <div className="w-8 h-8 rounded-xl bg-orange-50 flex items-center justify-center">
              <Icon icon="mdi:record-circle-outline" className="text-orange-500 text-base" />
            </div>
            <div>
              <p className="text-[14px] font-bold text-[rgba(0,0,0,0.88)]">OBS / RTMP Setup</p>
              <p className="text-[11px] text-[rgba(0,0,0,0.4)]">{stream.name}</p>
            </div>
          </div>
          <button onClick={onClose} className="p-1.5 rounded-lg hover:bg-[rgba(0,0,0,0.06)] transition-colors">
            <Icon icon="mdi:close" className="text-[rgba(0,0,0,0.4)] text-lg" />
          </button>
        </div>
        <div className="p-5 space-y-4">
          <div className="rounded-xl border border-[rgba(0,0,0,0.08)] overflow-hidden">
            {[
              { label: 'RTMP Server', value: 'rtmp://localhost:1935/live' },
              { label: 'Stream Key', value: stream.streamKey },
            ].map(item => (
              <div key={item.label} className="flex items-center justify-between px-3 py-2.5 border-b last:border-0 border-[rgba(0,0,0,0.05)]">
                <div>
                  <p className="text-[10px] font-bold uppercase tracking-wide text-[rgba(0,0,0,0.4)]">{item.label}</p>
                  <code className="text-[12px] font-mono text-[rgba(0,0,0,0.75)]">{item.value}</code>
                </div>
                <button onClick={() => copy(item.value, item.label)} className="p-1.5 rounded-lg hover:bg-[rgba(0,0,0,0.05)] transition-colors">
                  <Icon icon={copied === item.label ? 'mdi:check' : 'mdi:content-copy'} className={`text-sm ${copied === item.label ? 'text-emerald-500' : 'text-[rgba(0,0,0,0.3)]'}`} />
                </button>
              </div>
            ))}
          </div>
          <div className="space-y-2">
            <p className="text-[11px] font-bold uppercase tracking-wider text-[rgba(0,0,0,0.5)]">Setup Steps</p>
            {steps.map((step, i) => (
              <div key={i} className="flex items-start gap-2.5">
                <span className="w-5 h-5 rounded-full bg-orange-100 text-orange-600 text-[10px] font-bold flex items-center justify-center flex-shrink-0 mt-0.5">{i + 1}</span>
                <p className="text-[12px] text-[rgba(0,0,0,0.65)]">{step}</p>
              </div>
            ))}
          </div>
          {stream.hlsUrl && (
            <div className="rounded-xl bg-emerald-50 border border-emerald-200 p-3">
              <p className="text-[10px] font-bold uppercase tracking-wide text-emerald-700 mb-1">Viewer HLS URL (share this)</p>
              <div className="flex items-center gap-2">
                <code className="text-[11px] font-mono text-emerald-700 flex-1 truncate">{stream.hlsUrl}</code>
                <button onClick={() => copy(stream.hlsUrl, 'hls-obs')} className="p-1.5 rounded-lg hover:bg-emerald-100 transition-colors">
                  <Icon icon={copied === 'hls-obs' ? 'mdi:check' : 'mdi:content-copy'} className={`text-sm ${copied === 'hls-obs' ? 'text-emerald-600' : 'text-emerald-500'}`} />
                </button>
              </div>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}

function StreamsTab({ streams, loading, onCreateStream, onStartStream, onStopStream }: StreamsTabProps) {
  const [copied, setCopied] = useState<string | null>(null);
  const [actionLoading, setActionLoading] = useState<number | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [showForm, setShowForm] = useState(false);
  const [playerUrl, setPlayerUrl] = useState<string | null>(null);
  const [browserCaptureKey, setBrowserCaptureKey] = useState<string | null>(null);
  const [obsSetupStream, setObsSetupStream] = useState<LiveStream | null>(null);
  const [streamName, setStreamName] = useState('');
  const [streamResolution, setStreamResolution] = useState('1080p');
  const [streamBitrate, setStreamBitrate] = useState('4500k');
  const [createLoading, setCreateLoading] = useState(false);
  const [createError, setCreateError] = useState<string | null>(null);

  const handleCreate = useCallback(async () => {
    if (!streamName.trim()) return;
    setCreateLoading(true);
    setCreateError(null);
    try {
      await onCreateStream({ name: streamName.trim(), resolution: streamResolution, bitrate: streamBitrate });
      setShowForm(false);
      setStreamName('');
    } catch (e: any) {
      setCreateError(e.message || 'Failed to create stream');
    } finally {
      setCreateLoading(false);
    }
  }, [streamName, streamResolution, streamBitrate, onCreateStream]);

  const copy = useCallback((text: string, id: string) => {
    navigator.clipboard.writeText(text);
    setCopied(id);
    setTimeout(() => setCopied(null), 1800);
  }, []);

  const handleStart = useCallback(async (rawId: number) => {
    setActionLoading(rawId);
    setActionError(null);
    try { await onStartStream(rawId); } catch (e: any) { setActionError(e.message); } finally { setActionLoading(null); }
  }, [onStartStream]);

  const handleStop = useCallback(async (rawId: number) => {
    setActionLoading(rawId);
    setActionError(null);
    try { await onStopStream(rawId); } catch (e: any) { setActionError(e.message); } finally { setActionLoading(null); }
  }, [onStopStream]);

  const totalBandwidth = streams.reduce((a, s) => {
    const match = s.bandwidth.match(/^([\d.]+)\s*(\w+)/);
    if (!match) return a;
    const [, num, unit] = match;
    const multipliers: Record<string, number> = { B: 1, KB: 1e3, MB: 1e6, GB: 1e9, TB: 1e12 };
    return a + parseFloat(num) * (multipliers[unit] ?? 1);
  }, 0);

  return (
    <div className="space-y-5">
      {(actionError || createError) && (
        <div className="bg-red-50 border border-red-200 rounded-xl px-4 py-3 flex items-center gap-2">
          <Icon icon="mdi:alert-circle-outline" className="text-red-500 text-base flex-shrink-0" />
          <p className="text-[12px] text-red-700">{actionError || createError}</p>
          <button onClick={() => { setActionError(null); setCreateError(null); }} className="ml-auto"><Icon icon="mdi:close" className="text-sm text-red-400" /></button>
        </div>
      )}

      {/* Header row */}
      <div className="flex items-center justify-end">
        <button onClick={() => setShowForm(v => !v)}
          className="flex items-center gap-2 px-4 py-2 bg-gradient-to-r from-red-500 to-rose-600 text-white text-[12px] font-bold rounded-xl shadow hover:shadow-md transition-all hover:scale-[1.02]">
          <Icon icon="mdi:plus" className="text-base" />
          New Stream
        </button>
      </div>

      {/* New Stream Form */}
      <AnimatePresence>
        {showForm && (
          <motion.div initial={{ opacity: 0, y: -8 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -8 }} transition={{ duration: 0.2 }}
            className="bg-white border border-red-100 rounded-2xl shadow-sm p-5">
            <p className="text-[13px] font-bold text-[rgba(0,0,0,0.88)] mb-4 flex items-center gap-2">
              <Icon icon="mdi:broadcast" className="text-red-500" />
              Create Live Stream
            </p>
            <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
              <div className="md:col-span-1">
                <label className="block text-[11px] font-semibold text-[rgba(0,0,0,0.5)] mb-1.5 uppercase tracking-wide">Stream Name</label>
                <input value={streamName} onChange={e => setStreamName(e.target.value)}
                  placeholder="e.g. Main Stage, Game Stream"
                  className="w-full px-3 py-2.5 border border-[rgba(0,0,0,0.12)] rounded-xl text-[13px] focus:ring-2 focus:ring-red-400 focus:border-red-400 outline-none" />
              </div>
              <div>
                <label className="block text-[11px] font-semibold text-[rgba(0,0,0,0.5)] mb-1.5 uppercase tracking-wide">Resolution</label>
                <select value={streamResolution} onChange={e => setStreamResolution(e.target.value)}
                  className="w-full px-3 py-2.5 border border-[rgba(0,0,0,0.12)] rounded-xl text-[13px] focus:ring-2 focus:ring-red-400 outline-none bg-white">
                  <option value="1080p">1080p</option>
                  <option value="720p">720p</option>
                  <option value="480p">480p</option>
                </select>
              </div>
              <div>
                <label className="block text-[11px] font-semibold text-[rgba(0,0,0,0.5)] mb-1.5 uppercase tracking-wide">Bitrate</label>
                <select value={streamBitrate} onChange={e => setStreamBitrate(e.target.value)}
                  className="w-full px-3 py-2.5 border border-[rgba(0,0,0,0.12)] rounded-xl text-[13px] focus:ring-2 focus:ring-red-400 outline-none bg-white">
                  <option value="6000k">6000k (High)</option>
                  <option value="4500k">4500k (Medium)</option>
                  <option value="2500k">2500k (Low)</option>
                </select>
              </div>
            </div>
            <div className="flex items-center gap-3 mt-4">
              <button onClick={handleCreate} disabled={!streamName.trim() || createLoading}
                className="flex items-center gap-2 px-5 py-2.5 bg-gradient-to-r from-red-500 to-rose-600 text-white text-[12px] font-bold rounded-xl shadow disabled:opacity-50 transition-all hover:scale-[1.02]">
                {createLoading ? <Icon icon="mdi:loading" className="animate-spin" /> : <Icon icon="mdi:broadcast" />}
                {createLoading ? 'Creating…' : 'Create Stream'}
              </button>
              <button onClick={() => { setShowForm(false); setStreamName(''); setCreateError(null); }}
                className="px-4 py-2.5 text-[12px] font-semibold text-[rgba(0,0,0,0.5)] hover:text-[rgba(0,0,0,0.88)] transition-colors">Cancel</button>
            </div>
          </motion.div>
        )}
      </AnimatePresence>

      {/* Stats row */}
      <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
        {[
          { label: 'Live Now', value: streams.filter(s => s.status === 'live').length, icon: 'mdi:broadcast', bg: 'bg-red-50', color: 'text-red-500', border: 'border-l-red-500' },
          { label: 'Total Viewers', value: streams.reduce((a, s) => a + s.viewers, 0), icon: 'mdi:account-group-outline', bg: 'bg-violet-50', color: 'text-violet-500', border: 'border-l-violet-500' },
          { label: 'Bandwidth Used', value: formatBytes(totalBandwidth), icon: 'mdi:chart-line', bg: 'bg-sky-50', color: 'text-sky-500', border: 'border-l-sky-500' },
          { label: 'AWS IVS Saved', value: '$0 spent', icon: 'mdi:piggy-bank-outline', bg: 'bg-emerald-50', color: 'text-emerald-500', border: 'border-l-emerald-500' },
        ].map((s, i) => (
          <div key={i} className={`rounded-2xl border border-l-4 p-4 shadow-sm bg-white border-[rgba(0,0,0,0.08)] ${s.border}`}>
            <div className={`w-9 h-9 ${s.bg} rounded-xl flex items-center justify-center mb-3`}>
              <Icon icon={s.icon} className={`${s.color} text-base`} />
            </div>
            <p className="text-[10px] font-semibold uppercase tracking-wider text-[rgba(0,0,0,0.5)] mb-1">{s.label}</p>
            <p style={{ fontFamily: "'Bebas Neue','Barlow Condensed',sans-serif", fontSize: '26px', fontWeight: 400, color: 'rgb(10,10,10)' }}>{String(s.value)}</p>
          </div>
        ))}
      </div>

      {/* Stream cards */}
      {loading ? (
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
          {Array.from({ length: 2 }).map((_, i) => <SkeletonBlock key={i} className="h-56" />)}
        </div>
      ) : streams.length === 0 ? (
        <div className="bg-white border border-[rgba(0,0,0,0.08)] rounded-2xl py-16 text-center">
          <Icon icon="mdi:broadcast-off" className="text-4xl text-[rgba(0,0,0,0.15)] mx-auto mb-3" />
          <p className="text-[13px] font-semibold text-[rgba(0,0,0,0.4)]">No streams configured</p>
          <p className="text-[12px] text-[rgba(0,0,0,0.3)] mt-1">Create a stream via the API to get started</p>
        </div>
      ) : (
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
          {streams.map(stream => (
            <div key={stream.id} className={`bg-white rounded-2xl border shadow-sm overflow-hidden transition-all hover:shadow-md ${
              stream.status === 'live' ? 'border-red-200' : stream.status === 'error' ? 'border-red-200' : 'border-[rgba(0,0,0,0.08)]'
            }`}>
              <div className={`px-5 py-3 flex items-center justify-between ${
                stream.status === 'live' ? 'bg-gradient-to-r from-red-50 to-rose-50 border-b border-red-100' : 'border-b border-[rgba(0,0,0,0.06)]'
              }`}>
                <div className="flex items-center gap-2">
                  <StatusBadge status={stream.status} />
                  <span className="text-[13px] font-bold text-[rgba(0,0,0,0.88)]">{stream.name}</span>
                </div>
                {stream.status === 'live' && (
                  <div className="flex items-center gap-1.5 text-[12px] text-red-600 font-semibold">
                    <Icon icon="mdi:account-group-outline" />
                    {stream.viewers} viewers
                  </div>
                )}
              </div>

              <div className="p-5 space-y-3">
                <div className="grid grid-cols-3 gap-3">
                  {[
                    { label: 'Resolution', value: stream.resolution },
                    { label: 'Bitrate', value: stream.bitrate },
                    { label: 'Uptime', value: stream.uptime },
                  ].map(item => (
                    <div key={item.label} className="bg-[rgba(0,0,0,0.02)] rounded-xl p-3">
                      <p className="text-[10px] text-[rgba(0,0,0,0.4)] font-semibold uppercase tracking-wide mb-1">{item.label}</p>
                      <p className="text-[13px] font-bold text-[rgba(0,0,0,0.7)]">{item.value}</p>
                    </div>
                  ))}
                </div>

                <div className="rounded-xl border border-[rgba(0,0,0,0.08)] p-3">
                  <p className="text-[10px] font-bold uppercase tracking-wide text-[rgba(0,0,0,0.4)] mb-1.5">RTMP Ingest URL</p>
                  <div className="flex items-center gap-2">
                    <code className="text-[11px] font-mono text-[rgba(0,0,0,0.6)] flex-1 truncate">{stream.rtmpUrl}</code>
                    <button onClick={() => copy(stream.rtmpUrl, `rtmp-${stream.id}`)}
                      className="p-1.5 rounded-lg hover:bg-[rgba(0,0,0,0.05)] transition-colors flex-shrink-0">
                      <Icon icon={copied === `rtmp-${stream.id}` ? 'mdi:check' : 'mdi:content-copy'} className={`text-sm ${copied === `rtmp-${stream.id}` ? 'text-emerald-500' : 'text-[rgba(0,0,0,0.35)]'}`} />
                    </button>
                  </div>
                </div>

                {stream.hlsUrl && (
                  <div className="rounded-xl border border-[rgba(0,0,0,0.08)] p-3">
                    <p className="text-[10px] font-bold uppercase tracking-wide text-[rgba(0,0,0,0.4)] mb-1.5">HLS Playback URL (.m3u8)</p>
                    <div className="flex items-center gap-2">
                      <code className="text-[11px] font-mono text-[rgba(0,0,0,0.6)] flex-1 truncate">{stream.hlsUrl}</code>
                      <button onClick={() => copy(stream.hlsUrl, `hls-${stream.id}`)}
                        className="p-1.5 rounded-lg hover:bg-[rgba(0,0,0,0.05)] transition-colors flex-shrink-0">
                        <Icon icon={copied === `hls-${stream.id}` ? 'mdi:check' : 'mdi:content-copy'} className={`text-sm ${copied === `hls-${stream.id}` ? 'text-emerald-500' : 'text-[rgba(0,0,0,0.35)]'}`} />
                      </button>
                    </div>
                  </div>
                )}

                <div className="flex items-center gap-2 pt-1">
                  {stream.status === 'idle' && (
                    <>
                      <button onClick={() => setBrowserCaptureKey(stream.streamKey)}
                        className="flex items-center gap-1.5 px-4 py-2 bg-gradient-to-r from-red-500 to-rose-600 text-white text-[12px] font-bold rounded-xl shadow hover:shadow-md transition-all">
                        <Icon icon="mdi:webcam" />
                        Go Live from Browser
                      </button>
                      <button onClick={() => setObsSetupStream(stream)}
                        className="flex items-center gap-1.5 px-4 py-2 bg-[rgba(0,0,0,0.06)] text-[rgba(0,0,0,0.6)] text-[12px] font-semibold rounded-xl hover:bg-[rgba(0,0,0,0.10)] transition-all">
                        <Icon icon="mdi:record-circle-outline" />
                        OBS / RTMP
                      </button>
                      {stream.hlsUrl && (
                        <button onClick={() => setPlayerUrl(stream.hlsUrl)}
                          className="flex items-center gap-1.5 px-4 py-2 bg-violet-50 border border-violet-200 text-violet-700 text-[12px] font-semibold rounded-xl hover:bg-violet-100 transition-all">
                          <Icon icon="mdi:film-play-outline" />
                          Watch Recording
                        </button>
                      )}
                    </>
                  )}
                  {stream.status === 'live' && (
                    <>
                      <button onClick={() => handleStop(stream.rawId)} disabled={actionLoading === stream.rawId}
                        className="flex items-center gap-1.5 px-4 py-2 bg-[rgba(0,0,0,0.06)] text-[rgba(0,0,0,0.7)] text-[12px] font-semibold rounded-xl hover:bg-[rgba(0,0,0,0.1)] transition-colors disabled:opacity-60">
                        {actionLoading === stream.rawId ? <Icon icon="mdi:loading" className="animate-spin" /> : <Icon icon="mdi:stop" />}
                        Stop
                      </button>
                      {stream.hlsUrl && (
                        <button onClick={() => setPlayerUrl(stream.hlsUrl)}
                          className="flex items-center gap-1.5 px-4 py-2 bg-gradient-to-r from-pink-500 to-rose-600 text-white text-[12px] font-bold rounded-xl shadow hover:shadow-md transition-all">
                          <Icon icon="mdi:play-circle-outline" />
                          Watch Live
                        </button>
                      )}
                    </>
                  )}
                  {stream.status === 'error' && (
                    <button onClick={() => handleStart(stream.rawId)} disabled={actionLoading === stream.rawId}
                      className="flex items-center gap-1.5 px-4 py-2 bg-amber-50 border border-amber-200 text-amber-700 text-[12px] font-semibold rounded-xl hover:bg-amber-100 transition-colors disabled:opacity-60">
                      {actionLoading === stream.rawId ? <Icon icon="mdi:loading" className="animate-spin" /> : <Icon icon="mdi:refresh" />}
                      Restart
                    </button>
                  )}
                </div>
              </div>
            </div>
          ))}
        </div>
      )}

      {/* Browser capture modal */}
      {browserCaptureKey && (
        <BrowserStreamCapture
          streamKey={browserCaptureKey}
          onClose={() => setBrowserCaptureKey(null)}
        />
      )}

      {/* HLS Player modal */}
      {playerUrl && <HlsPlayer src={playerUrl} onClose={() => setPlayerUrl(null)} />}

      {/* OBS setup modal */}
      {obsSetupStream && <ObsSetupModal stream={obsSetupStream} onClose={() => setObsSetupStream(null)} />}

      {/* How it works */}
      <div className="bg-white border border-[rgba(0,0,0,0.08)] rounded-2xl shadow-sm p-5">
        <p className="text-[12px] font-bold uppercase tracking-wider text-[rgba(0,0,0,0.5)] mb-4">How Live Streaming Works</p>
        <div className="flex flex-col md:flex-row items-start md:items-center gap-3">
          {[
            { icon: 'mdi:video-outline', label: 'OBS / Encoder', sub: 'Streams RTMP', color: 'text-blue-500', bg: 'bg-blue-50' },
            { icon: 'mdi:arrow-right', label: '', sub: '', color: 'text-[rgba(0,0,0,0.2)]', bg: '' },
            { icon: 'mdi:lightning-bolt', label: 'FFmpeg Worker', sub: 'Transcodes + Segments', color: 'text-pink-500', bg: 'bg-pink-50' },
            { icon: 'mdi:arrow-right', label: '', sub: '', color: 'text-[rgba(0,0,0,0.2)]', bg: '' },
            { icon: 'mdi:playlist-play', label: 'HLS Segments', sub: '.ts + .m3u8', color: 'text-violet-500', bg: 'bg-violet-50' },
            { icon: 'mdi:arrow-right', label: '', sub: '', color: 'text-[rgba(0,0,0,0.2)]', bg: '' },
            { icon: 'mdi:play-circle-outline', label: 'Any Player', sub: 'HLS URL', color: 'text-emerald-500', bg: 'bg-emerald-50' },
          ].map((step, i) => (
            step.label ? (
              <div key={i} className="flex items-center gap-2">
                <div className={`w-10 h-10 ${step.bg} rounded-xl flex items-center justify-center`}>
                  <Icon icon={step.icon} className={`${step.color} text-lg`} />
                </div>
                <div>
                  <p className="text-[12px] font-bold text-[rgba(0,0,0,0.7)]">{step.label}</p>
                  <p className="text-[10px] text-[rgba(0,0,0,0.4)]">{step.sub}</p>
                </div>
              </div>
            ) : (
              <Icon key={i} icon={step.icon} className={`${step.color} text-xl hidden md:block`} />
            )
          ))}
        </div>
      </div>
    </div>
  );
}

// ─── Storage Tab ──────────────────────────────────────────────────────────────

interface StorageTabProps {
  files: MediaFile[];
  overview: MediaOpsOverview | null;
  loading: boolean;
  onDeleteFile: (id: number) => Promise<void>;
  onUploadFile: (file: File) => Promise<ApiStorageFile>;
}

function StorageTab({ files, overview, loading, onDeleteFile, onUploadFile }: StorageTabProps) {
  const [search, setSearch] = useState('');
  const [typeFilter, setTypeFilter] = useState<MediaFile['type'] | 'all'>('all');
  const [deletingId, setDeletingId] = useState<number | null>(null);
  const [uploadState, setUploadState] = useState<'idle' | 'uploading'>('idle');
  const uploadInputRef = React.useRef<HTMLInputElement>(null);

  const handleUpload = useCallback(async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    setUploadState('uploading');
    try { await onUploadFile(file); } finally {
      setUploadState('idle');
      if (uploadInputRef.current) uploadInputRef.current.value = '';
    }
  }, [onUploadFile]);

  const filtered = files.filter(f =>
    (typeFilter === 'all' || f.type === typeFilter) &&
    (f.name ?? '').toLowerCase().includes(search.toLowerCase())
  );

  const usedBytes = overview?.storageUsedBytes ?? 0;
  const totalBytes = 100 * 1024 * 1024 * 1024; // 100 GB
  const usedPct = Math.min((usedBytes / totalBytes) * 100, 100);
  const usedStr = formatBytes(usedBytes);
  const freeStr = formatBytes(totalBytes - usedBytes);

  const handleDelete = useCallback(async (rawId: number, name: string) => {
    if (!window.confirm(`Delete "${name}"? This cannot be undone.`)) return;
    setDeletingId(rawId);
    try { await onDeleteFile(rawId); } finally { setDeletingId(null); }
  }, [onDeleteFile]);

  const TYPE_ICONS: Record<string, { icon: string; color: string; bg: string }> = {
    video: { icon: 'mdi:file-video-outline', color: 'text-blue-500', bg: 'bg-blue-50' },
    audio: { icon: 'mdi:file-music-outline', color: 'text-violet-500', bg: 'bg-violet-50' },
    image: { icon: 'mdi:file-image-outline', color: 'text-emerald-500', bg: 'bg-emerald-50' },
    hls: { icon: 'mdi:playlist-play', color: 'text-pink-500', bg: 'bg-pink-50' },
  };

  return (
    <div className="space-y-5">
      {/* Storage usage */}
      <div className="bg-white border border-[rgba(0,0,0,0.08)] rounded-2xl shadow-sm p-5">
        <div className="flex items-center justify-between mb-4">
          <div>
            <p className="text-[13px] font-bold text-[rgba(0,0,0,0.88)]">Storage Usage</p>
            <p className="text-[12px] text-[rgba(0,0,0,0.45)]">{usedStr} used of 100 GB</p>
          </div>
          <div className="text-right">
            <p className="text-[22px] font-bold text-[rgba(0,0,0,0.88)]" style={{ fontFamily: "'Bebas Neue','Barlow Condensed',sans-serif" }}>{usedPct.toFixed(1)}%</p>
            <p className="text-[11px] text-[rgba(0,0,0,0.4)]">{freeStr} free</p>
          </div>
        </div>
        <div className="w-full h-3 bg-[rgba(0,0,0,0.06)] rounded-full overflow-hidden">
          <div className="h-full bg-gradient-to-r from-pink-500 to-rose-500 rounded-full transition-all duration-700" style={{ width: `${usedPct}%` }} />
        </div>
        <div className="grid grid-cols-4 gap-3 mt-4">
          {['VIDEO', 'HLS', 'AUDIO', 'IMAGE'].map((type, i) => {
            const typeFiles = files.filter(f => f.type === type.toLowerCase());
            const meta = [
              { label: 'Videos', color: 'bg-blue-500' },
              { label: 'HLS', color: 'bg-pink-500' },
              { label: 'Audio', color: 'bg-violet-500' },
              { label: 'Images', color: 'bg-emerald-500' },
            ][i];
            return (
              <div key={type} className="flex items-center gap-2">
                <span className={`w-2 h-2 rounded-full ${meta.color} flex-shrink-0`} />
                <div>
                  <p className="text-[11px] font-semibold text-[rgba(0,0,0,0.65)]">{meta.label}</p>
                  <p className="text-[10px] text-[rgba(0,0,0,0.4)]">{typeFiles.length} file{typeFiles.length !== 1 ? 's' : ''}</p>
                </div>
              </div>
            );
          })}
        </div>
      </div>

      {/* Filters + Search */}
      <div className="flex flex-col md:flex-row gap-3">
        <div className="relative flex-1">
          <Icon icon="mdi:magnify" className="absolute left-3 top-1/2 -translate-y-1/2 text-[rgba(0,0,0,0.3)] text-sm pointer-events-none" />
          <input value={search} onChange={e => setSearch(e.target.value)} placeholder="Search files…"
            className="w-full pl-9 pr-4 py-2.5 border border-[rgba(0,0,0,0.12)] rounded-xl text-[13px] focus:ring-2 focus:ring-pink-400 focus:border-pink-400 outline-none" />
        </div>
        <div className="flex items-center gap-2 flex-wrap">
          {(['all', 'video', 'hls', 'audio', 'image'] as const).map(t => (
            <button key={t} onClick={() => setTypeFilter(t)}
              className={`px-3 py-2 rounded-xl text-[12px] font-semibold border transition-all ${
                typeFilter === t ? 'bg-[rgba(0,0,0,0.88)] text-white border-transparent' : 'bg-white text-[rgba(0,0,0,0.55)] border-[rgba(0,0,0,0.10)] hover:bg-[rgba(0,0,0,0.03)]'
              }`}>{t.charAt(0).toUpperCase() + t.slice(1)}</button>
          ))}
          <label className={`flex items-center gap-1.5 px-4 py-2 rounded-xl text-[12px] font-bold border cursor-pointer transition-all ${
            uploadState === 'uploading'
              ? 'bg-pink-50 border-pink-200 text-pink-600 cursor-wait'
              : 'bg-gradient-to-r from-pink-500 to-rose-600 text-white border-transparent shadow hover:shadow-md hover:scale-[1.02]'
          }`}>
            <input ref={uploadInputRef} type="file" accept="video/*,audio/*,.mkv,.avi,.mov,.mp4,.webm,.m4v,.flv"
              className="hidden" disabled={uploadState === 'uploading'} onChange={handleUpload} />
            <Icon icon={uploadState === 'uploading' ? 'mdi:loading' : 'mdi:upload'} className={`text-base ${uploadState === 'uploading' ? 'animate-spin' : ''}`} />
            {uploadState === 'uploading' ? 'Uploading…' : 'Upload File'}
          </label>
        </div>
      </div>

      {/* File grid */}
      {loading ? (
        <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-4">
          {Array.from({ length: 6 }).map((_, i) => <SkeletonBlock key={i} className="h-28" />)}
        </div>
      ) : filtered.length === 0 ? (
        <div className="bg-white border border-[rgba(0,0,0,0.08)] rounded-2xl py-16 text-center">
          <Icon icon="mdi:folder-open-outline" className="text-4xl text-[rgba(0,0,0,0.15)] mx-auto mb-3" />
          <p className="text-[13px] font-semibold text-[rgba(0,0,0,0.4)]">No files found</p>
        </div>
      ) : (
        <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-4">
          {filtered.map(file => {
            const meta = TYPE_ICONS[file.type] || TYPE_ICONS.video;
            return (
              <div key={file.id} className="bg-white border border-[rgba(0,0,0,0.08)] rounded-2xl p-4 hover:shadow-md transition-all cursor-pointer group">
                <div className="flex items-start gap-3">
                  <div className={`w-11 h-11 ${meta.bg} rounded-xl flex items-center justify-center flex-shrink-0 group-hover:scale-105 transition-transform`}>
                    <Icon icon={meta.icon} className={`${meta.color} text-xl`} />
                  </div>
                  <div className="flex-1 min-w-0">
                    <p className="text-[13px] font-bold text-[rgba(0,0,0,0.88)] truncate">{file.name ?? 'Unnamed'}</p>
                    <p className="text-[11px] text-[rgba(0,0,0,0.4)]">{file.format ?? '—'} · {file.size} · {file.createdAt}</p>
                  </div>
                </div>
                <div className="flex items-center gap-2 mt-3 pt-3 border-t border-[rgba(0,0,0,0.05)]">
                  {file.resolution && (
                    <span className="px-2 py-0.5 bg-[rgba(0,0,0,0.04)] rounded-full text-[10px] font-semibold text-[rgba(0,0,0,0.5)]">{file.resolution}</span>
                  )}
                  {file.duration && (
                    <span className="px-2 py-0.5 bg-[rgba(0,0,0,0.04)] rounded-full text-[10px] font-semibold text-[rgba(0,0,0,0.5)]">
                      <Icon icon="mdi:clock-outline" className="inline text-[10px] mr-0.5" />{file.duration}
                    </span>
                  )}
                  <div className="ml-auto flex items-center gap-1">
                    <a href={`http://localhost:8084/media/${file.filePath.replace(/.*xamops-media\//, '')}`}
                      download={file.name}
                      className="p-1.5 rounded-lg hover:bg-[rgba(0,0,0,0.06)] transition-colors" title="Download">
                      <Icon icon="mdi:download-outline" className="text-sm text-[rgba(0,0,0,0.4)]" />
                    </a>
                    <button
                      onClick={() => handleDelete(file.rawId, file.name ?? 'this file')}
                      disabled={deletingId === file.rawId}
                      className="p-1.5 rounded-lg hover:bg-red-50 transition-colors disabled:opacity-50"
                      title="Delete">
                      <Icon icon={deletingId === file.rawId ? 'mdi:loading' : 'mdi:delete-outline'}
                        className={`text-sm ${deletingId === file.rawId ? 'text-red-400 animate-spin' : 'text-[rgba(0,0,0,0.4)] hover:text-red-500'}`} />
                    </button>
                  </div>
                </div>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}

// ─── Cost Savings Tab ─────────────────────────────────────────────────────────

interface CostSavingsTabProps {
  overview: MediaOpsOverview | null;
}

function CostSavingsTab({ overview }: CostSavingsTabProps) {
  const totalMonthlySavings = AWS_COMPARISON.reduce((a, r) => a + parseFloat(r.monthlySavings.replace(/[$,]/g, '')), 0);
  const totalYearSavings = AWS_COMPARISON.reduce((a, r) => a + parseFloat(r.yearSavings.replace(/[$,]/g, '')), 0);
  const actualSaved = overview ? formatSavings(overview.totalSavedVsAws) : '—';

  return (
    <div className="space-y-5">
      {/* Hero savings banner */}
      <div className="relative overflow-hidden rounded-2xl bg-gradient-to-r from-emerald-600 to-teal-700 p-6 shadow-lg">
        <div className="absolute inset-0 opacity-10">
          <div className="absolute top-0 right-20 w-40 h-40 rounded-full bg-white blur-3xl" />
          <div className="absolute bottom-0 right-0 w-32 h-24 rounded-full bg-emerald-300 blur-2xl" />
        </div>
        <div className="relative grid grid-cols-1 md:grid-cols-3 gap-6">
          <div>
            <p className="text-[11px] font-bold uppercase tracking-widest text-emerald-200 mb-1">Potential Monthly Savings</p>
            <p className="text-4xl font-bold text-white" style={{ fontFamily: "'Bebas Neue','Barlow Condensed',sans-serif" }}>${totalMonthlySavings.toLocaleString()}</p>
            <p className="text-[12px] text-emerald-200 mt-1">vs AWS at 100 hrs/mo</p>
          </div>
          <div>
            <p className="text-[11px] font-bold uppercase tracking-widest text-emerald-200 mb-1">Potential Annual Savings</p>
            <p className="text-4xl font-bold text-white" style={{ fontFamily: "'Bebas Neue','Barlow Condensed',sans-serif" }}>${totalYearSavings.toLocaleString()}</p>
            <p className="text-[12px] text-emerald-200 mt-1">Projected at current usage</p>
          </div>
          <div>
            <p className="text-[11px] font-bold uppercase tracking-widest text-emerald-200 mb-1">Actual Tracked Savings</p>
            <p className="text-4xl font-bold text-white" style={{ fontFamily: "'Bebas Neue','Barlow Condensed',sans-serif" }}>{actualSaved}</p>
            <p className="text-[12px] text-emerald-200 mt-1">Recorded by MediaOps</p>
          </div>
        </div>
      </div>

      {/* Comparison table */}
      <div className="bg-white border border-[rgba(0,0,0,0.08)] rounded-2xl shadow-sm overflow-hidden">
        <div className="px-5 py-3.5 border-b border-[rgba(0,0,0,0.06)]">
          <p className="text-[13px] font-bold text-[rgba(0,0,0,0.88)]">AWS Media Services vs FFmpeg (XamOps MediaOps)</p>
          <p className="text-[11px] text-[rgba(0,0,0,0.4)] mt-0.5">Based on estimated monthly usage at 100 hrs transcoding + 4 live channels</p>
        </div>
        <div className="overflow-x-auto">
          <table className="w-full">
            <thead>
              <tr className="bg-[rgba(0,0,0,0.02)] border-b border-[rgba(0,0,0,0.06)]">
                {['AWS Service', 'Use Case', 'AWS Price', 'MediaOps (FFmpeg)', 'Monthly Savings', '12-Month Savings', 'Saving %'].map(h => (
                  <th key={h} className="px-4 py-3 text-left text-[10px] font-bold uppercase tracking-wider text-[rgba(0,0,0,0.4)]">{h}</th>
                ))}
              </tr>
            </thead>
            <tbody className="divide-y divide-[rgba(0,0,0,0.04)]">
              {AWS_COMPARISON.map((row, i) => (
                <tr key={i} className="hover:bg-[rgba(0,0,0,0.01)] transition-colors">
                  <td className="px-4 py-3">
                    <div className="flex items-center gap-2">
                      <div className="w-7 h-7 rounded-lg bg-[#FF9900]/10 flex items-center justify-center">
                        <Icon icon="mdi:aws" className="text-[#FF9900] text-sm" />
                      </div>
                      <span className="text-[12px] font-bold text-[rgba(0,0,0,0.88)]">{row.service}</span>
                    </div>
                  </td>
                  <td className="px-4 py-3 text-[12px] text-[rgba(0,0,0,0.55)]">{row.useCase}</td>
                  <td className="px-4 py-3">
                    <span className="text-[12px] font-mono font-semibold text-red-600">{row.awsPrice}</span>
                  </td>
                  <td className="px-4 py-3">
                    <div className="flex items-center gap-1.5">
                      <Icon icon="mdi:lightning-bolt" className="text-pink-500 text-sm" />
                      <span className="text-[12px] font-mono font-semibold text-emerald-700">{row.ffmpegPrice}</span>
                    </div>
                  </td>
                  <td className="px-4 py-3"><span className="text-[13px] font-bold text-emerald-600">{row.monthlySavings}</span></td>
                  <td className="px-4 py-3"><span className="text-[13px] font-bold text-emerald-700">{row.yearSavings}</span></td>
                  <td className="px-4 py-3">
                    <div className="flex items-center gap-2">
                      <div className="flex-1 h-2 bg-[rgba(0,0,0,0.06)] rounded-full overflow-hidden w-16">
                        <div className="h-full bg-gradient-to-r from-emerald-400 to-teal-500 rounded-full" style={{ width: `${row.savingsPct}%` }} />
                      </div>
                      <span className="text-[12px] font-bold text-emerald-600">{row.savingsPct}%</span>
                    </div>
                  </td>
                </tr>
              ))}
              <tr className="bg-emerald-50 border-t-2 border-emerald-200">
                <td colSpan={4} className="px-4 py-3 text-[12px] font-bold text-emerald-800">Total (all services)</td>
                <td className="px-4 py-3 text-[14px] font-bold text-emerald-700">${totalMonthlySavings.toLocaleString()}/mo</td>
                <td className="px-4 py-3 text-[14px] font-bold text-emerald-700">${totalYearSavings.toLocaleString()}/yr</td>
                <td className="px-4 py-3 text-[14px] font-bold text-emerald-700">~98%</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>

      {/* Why FFmpeg */}
      <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
        {[
          { icon: 'mdi:currency-usd-off', title: 'Zero Licensing Cost', body: 'FFmpeg is 100% free and open-source (LGPL/GPL). No per-minute fees, no channel fees, no egress markup.', color: 'text-emerald-500', bg: 'bg-emerald-50', border: 'border-emerald-100' },
          { icon: 'mdi:server-network', title: 'Runs on Your Infra', body: 'Runs on any EC2, GCE, or on-prem server. Use Spot/Preemptible instances to cut compute costs further.', color: 'text-blue-500', bg: 'bg-blue-50', border: 'border-blue-100' },
          { icon: 'mdi:cogs', title: 'Full Format Support', body: 'Supports 500+ codecs and formats including H.264, H.265/HEVC, AV1, VP9, HLS, DASH, RTMP, SRT, and more.', color: 'text-violet-500', bg: 'bg-violet-50', border: 'border-violet-100' },
        ].map((item, i) => (
          <div key={i} className={`bg-white rounded-2xl border ${item.border} p-5 shadow-sm`}>
            <div className={`w-10 h-10 ${item.bg} rounded-xl flex items-center justify-center mb-3`}>
              <Icon icon={item.icon} className={`${item.color} text-xl`} />
            </div>
            <p className="text-[13px] font-bold text-[rgba(0,0,0,0.88)] mb-1">{item.title}</p>
            <p className="text-[12px] text-[rgba(0,0,0,0.5)] leading-relaxed">{item.body}</p>
          </div>
        ))}
      </div>
    </div>
  );
}

// ─── Main Page ────────────────────────────────────────────────────────────────

const MediaOpsPage: React.FC = () => {
  const activeTab = useActiveTab();
  const navigate = useNavigate();
  const { setActions, clearActions } = useTopbar();
  const { theme } = useTheme();
  const dark = theme === 'dark';

  const {
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
    uploadFile,
    createStream,
    startStream,
    stopStream,
    deleteFile,
    refresh,
  } = useMediaOpsData();

  // ── Notification system ──────────────────────────────────────────────────
  const {
    notifications,
    unreadCount,
    markAsRead,
    dismissToast,
    toast,
  } = useMediaOpsNotifications();

  const [showNotifications, setShowNotifications] = useState(false);
  const notificationDropdownRef = useRef<HTMLDivElement>(null);

  // Close notification dropdown when clicking outside
  useEffect(() => {
    if (!showNotifications) return;
    const handleClickOutside = (e: MouseEvent) => {
      if (notificationDropdownRef.current && !notificationDropdownRef.current.contains(e.target as Node)) {
        setShowNotifications(false);
      }
    };
    document.addEventListener('mousedown', handleClickOutside);
    return () => document.removeEventListener('mousedown', handleClickOutside);
  }, [showNotifications]);

  // Navigate to transcode tab when a notification is clicked
  const handleNotificationNavigate = useCallback((_jobId: number) => {
    navigate('/media-ops/transcode');
    setShowNotifications(false);
  }, [navigate]);

  const toggleNotifications = useCallback(() => {
    setShowNotifications(prev => !prev);
  }, []);

  useEffect(() => {
    setActions(
      <div className="flex items-center gap-2">
        <span className="w-2 h-2 rounded-full bg-pink-400 animate-pulse" />
        <span className="text-[13px] font-semibold text-[rgba(0,0,0,0.65)]">MediaOps</span>
        <span className="px-2 py-0.5 rounded-full bg-pink-100 border border-pink-200 text-[10px] font-bold text-pink-600 uppercase tracking-wider">Beta</span>
        <button onClick={refresh} title="Refresh" className="ml-1 p-1 rounded-lg hover:bg-[rgba(0,0,0,0.06)] transition-colors">
          <Icon icon="mdi:refresh" className="text-sm text-[rgba(0,0,0,0.4)]" />
        </button>
      </div>
    );
    return () => clearActions();
  }, [setActions, clearActions, refresh]);

  return (
    <main className={`flex-1 px-6 pt-4 pb-6 min-h-screen ${dark ? 'bg-[#111111]' : 'bg-white'}`}>
      {/* Toast notification */}
      <AnimatePresence>
        {toast && (
          <motion.div
            initial={{ opacity: 0, y: -12, x: 12 }}
            animate={{ opacity: 1, y: 0, x: 0 }}
            exit={{ opacity: 0, y: -12, x: 12 }}
            transition={{ duration: 0.2 }}
            className="fixed top-4 right-4 z-[100] bg-white border border-[rgba(0,0,0,0.10)] rounded-xl shadow-xl px-4 py-3 flex items-start gap-3 max-w-sm"
          >
            <div className={`w-8 h-8 rounded-lg flex items-center justify-center flex-shrink-0 ${
              toast.type === 'JOB_COMPLETED' ? 'bg-emerald-50' : 'bg-red-50'
            }`}>
              <Icon
                icon={toast.type === 'JOB_COMPLETED' ? 'mdi:check-circle' : 'mdi:alert-circle'}
                className={`text-sm ${toast.type === 'JOB_COMPLETED' ? 'text-emerald-500' : 'text-red-500'}`}
              />
            </div>
            <div className="flex-1 min-w-0">
              <p className="text-[12px] font-semibold text-[rgba(0,0,0,0.88)] truncate">{toast.message}</p>
              <p className="text-[10px] text-[rgba(0,0,0,0.4)] mt-0.5">
                {toast.type === 'JOB_COMPLETED' ? 'Job completed' : 'Job failed'}
              </p>
            </div>
            <button
              onClick={dismissToast}
              className="p-1 rounded-lg hover:bg-[rgba(0,0,0,0.04)] transition-colors flex-shrink-0"
              aria-label="Dismiss notification"
            >
              <Icon icon="mdi:close" className="text-sm text-[rgba(0,0,0,0.4)]" />
            </button>
          </motion.div>
        )}
      </AnimatePresence>

      {/* Global error banner */}
      {error && (
        <div className="mb-4 bg-amber-50 border border-amber-200 rounded-xl px-4 py-3 flex items-center gap-2">
          <Icon icon="mdi:alert-circle-outline" className="text-amber-500 text-base flex-shrink-0" />
          <p className="text-[12px] text-amber-800">Could not connect to media-ops-service: {error}</p>
          <button onClick={refresh} className="ml-auto flex items-center gap-1 text-[12px] font-semibold text-amber-700 hover:text-amber-900">
            <Icon icon="mdi:refresh" className="text-sm" /> Retry
          </button>
        </div>
      )}

      {/* Tab Bar with Notification Bell */}
      <div className={`flex items-center gap-1 mb-6 overflow-x-auto pb-px border-b ${dark ? 'border-[rgba(255,255,255,0.08)]' : 'border-[rgba(0,0,0,0.06)]'}`}>
        {TAB_DEFS.map(tab => (
          <button
            key={tab.id}
            onClick={() => navigate(tab.path)}
            className={`flex items-center gap-1.5 px-4 py-2.5 text-[13px] font-semibold whitespace-nowrap border-b-2 transition-all -mb-px ${
              activeTab === tab.id
                ? 'border-pink-500 text-pink-600'
                : `border-transparent ${dark ? 'text-[rgba(255,255,255,0.5)] hover:text-[rgba(255,255,255,0.75)] hover:border-[rgba(255,255,255,0.15)]' : 'text-[rgba(0,0,0,0.5)] hover:text-[rgba(0,0,0,0.75)] hover:border-[rgba(0,0,0,0.15)]'}`
            }`}
          >
            <Icon icon={tab.icon} className="text-base" />
            {tab.label}
          </button>
        ))}

        {/* Notification Bell — positioned at the right end of the tab bar */}
        <div className="relative ml-auto flex-shrink-0" ref={notificationDropdownRef}>
          <NotificationBell unreadCount={unreadCount} onClick={toggleNotifications} />
          <AnimatePresence>
            {showNotifications && (
              <NotificationList
                notifications={notifications}
                onMarkAsRead={markAsRead}
                onNavigateToJob={handleNotificationNavigate}
                onClose={() => setShowNotifications(false)}
              />
            )}
          </AnimatePresence>
        </div>
      </div>

      {/* Tab Content */}
      <AnimatePresence mode="wait">
        <motion.div
          key={activeTab}
          initial={{ opacity: 0, y: 8 }}
          animate={{ opacity: 1, y: 0 }}
          exit={{ opacity: 0, y: -4 }}
          transition={{ duration: 0.22, ease: [0.22, 1, 0.36, 1] }}
        >
          {activeTab === 'overview' && (
            <OverviewTab
              overview={overview}
              jobs={jobs}
              streams={streams}
              loadingOverview={loadingOverview}
              loadingJobs={loadingJobs}
              loadingStreams={loadingStreams}
            />
          )}
          {activeTab === 'transcode' && (
            <TranscodeTab jobs={jobs} loading={loadingJobs} onCreateJob={createJob} onUploadFile={uploadFile} onRetryJob={retryJob} />
          )}
          {activeTab === 'streams' && (
            <StreamsTab streams={streams} loading={loadingStreams} onCreateStream={createStream} onStartStream={startStream} onStopStream={stopStream} />
          )}
          {activeTab === 'storage' && (
            <StorageTab files={files} overview={overview} loading={loadingFiles} onDeleteFile={deleteFile} onUploadFile={uploadFile} />
          )}
          {activeTab === 'cost-savings' && (
            <CostSavingsTab overview={overview} />
          )}
        </motion.div>
      </AnimatePresence>
    </main>
  );
};

export default MediaOpsPage;
