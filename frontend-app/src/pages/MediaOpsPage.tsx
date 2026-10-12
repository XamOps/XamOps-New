import React, { useState, useEffect, useCallback, useRef } from 'react';
import { createPortal } from 'react-dom';
import { useLocation, useNavigate } from 'react-router-dom';
import { AnimatePresence, motion } from 'framer-motion';
import { Icon } from '@iconify/react';
import { useTopbar } from '../contexts/TopbarContext';
import { useTheme } from '../contexts/ThemeContext';
import { useAccounts } from '../contexts/AccountContext';
import { useMediaOpsData, formatBytes, fetchUserS3Configs, type MediaOpsOverview, type CreateJobRequest, type CreateStreamRequest, type ApiStorageFile, type UserS3ConfigDto } from '../hooks/useMediaOpsData';
import { SimpleMode } from '../components/mediaops/SimpleMode';
import { AdvancedMode, type AdvancedModeState } from '../components/mediaops/AdvancedMode';
import { useMediaOpsNotifications } from '../hooks/useMediaOpsNotifications';
import { applyAuthToXhr } from '../utils/xhrAuth';
import { NotificationBell } from '../components/mediaops/NotificationBell';
import { NotificationList } from '../components/mediaops/NotificationList';
import { S3PickerModal } from '../components/mediaops/S3PickerModal';
import { ThumbnailGrid } from '../components/mediaops/ThumbnailGrid';

// ─── Types ────────────────────────────────────────────────────────────────────

type Tab = 'overview' | 'transcode' | 'streams' | 'storage' | 'cost-savings';

type JobStatus = 'queued' | 'processing' | 'paused' | 'completed' | 'failed' | 'cancelled';
type StreamStatus = 'live' | 'idle' | 'error';

interface TranscodeJob {
  id: string;
  rawId: number;
  name: string;
  /** Folder the source came from, e.g. "DRSAKSHI". Empty for uploads and pasted URLs. */
  sourceFolder: string;
  /** Full source path, e.g. "DRSAKSHI/1.mp4" — what search matches and expansion reveals. */
  sourcePath: string;
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
  thumbnailPaths?: string[];
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
  { service: 'Amazon IVS', useCase: 'Interactive Video', awsPrice: '$0.2000/hr + $0.05/GB', ffmpegPrice: '$0.00 (bundled with streaming)', monthlySavings: '$312', yearSavings: '$3,744', savingsPct: 97 },
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
  { id: 'transcode', label: 'Transcode', icon: 'mdi:movie-open-outline', path: '/media-ops/transcode' },
  { id: 'streams', label: 'Live Streams', icon: 'mdi:broadcast', path: '/media-ops/streams' },
  { id: 'storage', label: 'Storage', icon: 'mdi:folder-play-outline', path: '/media-ops/storage' },
  { id: 'cost-savings', label: 'Cost Savings', icon: 'mdi:piggy-bank-outline', path: '/media-ops/cost-savings' },
];

// Signature condensed numeral treatment (matches FinOps/Account KPI cards)
const JOB_NUM_STYLE: React.CSSProperties = {
  fontFamily: "'Bebas Neue', 'Barlow Condensed', sans-serif",
  fontSize: '30px',
  fontWeight: 400,
  lineHeight: '30px',
  color: 'rgb(10,10,10)',
  fontVariantNumeric: 'tabular-nums',
};

// Interactive stat-filter cards — clicking a card filters the jobs table
const STAT_DEFS: {
  id: JobStatus | 'all';
  label: string;
  icon: string;
  iconActiveBg: string;
  iconActiveColor: string;
  activeBorder: string;
  activeRing: string;
}[] = [
  { id: 'all',        label: 'All Jobs',   icon: 'mdi:layers-triple-outline', iconActiveBg: 'bg-[rgba(0,0,0,0.88)]', iconActiveColor: 'text-white',              activeBorder: 'border-[rgba(0,0,0,0.30)]', activeRing: 'ring-[rgba(0,0,0,0.10)]' },
  { id: 'processing', label: 'Processing', icon: 'mdi:progress-clock',        iconActiveBg: 'bg-blue-50',           iconActiveColor: 'text-blue-600',           activeBorder: 'border-blue-300',           activeRing: 'ring-blue-500/15' },
  { id: 'queued',     label: 'Queued',     icon: 'mdi:tray-full',             iconActiveBg: 'bg-[rgba(0,0,0,0.06)]', iconActiveColor: 'text-[rgba(0,0,0,0.6)]', activeBorder: 'border-[rgba(0,0,0,0.25)]', activeRing: 'ring-[rgba(0,0,0,0.08)]' },
  { id: 'completed',  label: 'Completed',  icon: 'mdi:check-circle-outline',  iconActiveBg: 'bg-emerald-50',        iconActiveColor: 'text-emerald-600',        activeBorder: 'border-emerald-300',        activeRing: 'ring-emerald-500/15' },
  { id: 'failed',     label: 'Failed',     icon: 'mdi:alert-circle-outline',  iconActiveBg: 'bg-red-50',            iconActiveColor: 'text-red-600',            activeBorder: 'border-red-300',            activeRing: 'ring-red-500/15' },
];

function StatusBadge({ status }: { status: JobStatus | StreamStatus }) {
  const map: Record<string, { label: string; cls: string; dot: string }> = {
    processing: { label: 'Processing', cls: 'bg-[rgba(0,0,0,0.04)] text-[rgba(0,0,0,0.65)] border-[rgba(0,0,0,0.08)]', dot: 'bg-[rgba(0,0,0,0.4)] animate-pulse' },
    paused: { label: 'Paused', cls: 'bg-amber-50 text-amber-700 border-amber-200', dot: 'bg-amber-500' },
    queued: { label: 'Queued', cls: 'bg-[rgba(0,0,0,0.04)] text-[rgba(0,0,0,0.55)] border-[rgba(0,0,0,0.08)]', dot: 'bg-[rgba(0,0,0,0.3)]' },
    cancelled: { label: 'Cancelled', cls: 'bg-[rgba(0,0,0,0.04)] text-[rgba(0,0,0,0.45)] border-[rgba(0,0,0,0.08)]', dot: 'bg-[rgba(0,0,0,0.25)]' },
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

function ProgressBar({ value, color = 'bg-[rgba(0,0,0,0.5)]' }: { value: number; color?: string }) {
  return (
    <div className="w-full h-1.5 bg-[rgba(0,0,0,0.06)] rounded-full overflow-hidden">
      <div className={`h-full rounded-full transition-all duration-700 ${color}`} style={{ width: `${value}%` }} />
    </div>
  );
}

function StatCard({ label, value, icon, sub, tooltip }:
  { label: string; value: string | number; icon: string; iconBg?: string; iconColor?: string; borderColor?: string; sub?: string; tooltip?: string }) {
  return (
    <div className="group relative bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-5 flex flex-col gap-0 overflow-hidden card-hover-lift">
      <div className="flex items-start justify-between mb-2">
        <p className="text-[12px] font-semibold uppercase tracking-[0.09em] leading-[15px] text-[rgba(0,0,0,0.88)]">{label}</p>
        <div className="w-8 h-8 rounded-lg bg-[rgba(0,0,0,0.04)] flex items-center justify-center flex-shrink-0">
          <Icon icon={icon} width={16} color="rgba(0,0,0,0.4)" />
        </div>
      </div>
      <p style={{ fontFamily: "'Bebas Neue','Barlow Condensed',sans-serif", fontSize: '32px', fontWeight: 400, lineHeight: '32px', color: 'rgb(10,10,10)', fontVariantNumeric: 'tabular-nums' }}>
        {String(value)}
      </p>
      {sub && <p className="text-[11px] text-[rgba(0,0,0,0.35)] mt-1">{sub}</p>}
      {tooltip && (
        <div className="absolute bottom-full left-1/2 -translate-x-1/2 mb-2 w-52 rounded-xl px-3 py-2 text-[11px] leading-snug shadow-lg z-50 opacity-0 group-hover:opacity-100 pointer-events-none transition-opacity bg-[rgba(0,0,0,0.88)] text-white">
          {tooltip}
          <div className="absolute top-full left-1/2 -translate-x-1/2 border-l-4 border-r-4 border-t-4 border-l-transparent border-r-transparent border-t-[rgba(0,0,0,0.88)]" />
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
    { label: 'Active Streams', value: overview?.liveStreams ?? '—', icon: 'mdi:broadcast', sub: 'Live right now', tooltip: 'Number of currently active RTMP/HLS live streams.' },
    { label: 'Jobs Running', value: overview?.processingJobs ?? '—', icon: 'mdi:movie-open-outline', sub: 'Transcoding now', tooltip: 'Transcoding jobs currently in progress.' },
    { label: 'Jobs Completed', value: overview?.completedJobs ?? '—', icon: 'mdi:check-circle-outline', sub: 'Total completed', tooltip: 'Jobs that finished successfully.' },
    { label: 'Total Viewers', value: overview?.totalViewers ?? '—', icon: 'mdi:account-group-outline', sub: 'Across all streams', tooltip: 'Total concurrent viewers across all live streams.' },
    { label: 'Storage Used', value: storageStr, icon: 'mdi:database-outline', sub: 'Media storage', tooltip: 'Total media storage consumed.' },
    { label: 'Saved vs AWS', value: saved, icon: 'mdi:piggy-bank-outline', sub: 'Total tracked', tooltip: 'Money saved vs AWS Media Services.' },
  ];

  return (
    <div className="space-y-6">
      {/* Hero Banner */}
      <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-5 flex flex-col md:flex-row md:items-center md:justify-between gap-4">
        <div>
          <div className="flex items-center gap-2 mb-2">
            <div className="w-8 h-8 rounded-lg bg-[rgba(0,0,0,0.04)] flex items-center justify-center">
              <Icon icon="mdi:film" width={16} color="rgba(0,0,0,0.5)" />
            </div>
            <span className="text-[11px] font-bold uppercase tracking-widest text-[rgba(0,0,0,0.55)]">MediaOps</span>
            <span className="px-2 py-0.5 rounded-full bg-[rgba(0,0,0,0.04)] border border-[rgba(0,0,0,0.08)] text-[10px] font-bold text-[rgba(0,0,0,0.45)] uppercase tracking-wider">Beta</span>
          </div>
          <h1 className="text-[18px] font-bold text-[rgba(0,0,0,0.88)] leading-tight mb-1">World-class media processing, zero AWS bill.</h1>
          <p className="text-[13px] text-[rgba(0,0,0,0.45)] max-w-lg">Replace AWS MediaConvert, MediaLive & MediaPackage with XamOps MediaOps. Transcode, stream, and store media — free.</p>
        </div>
        <div className="flex flex-col gap-2">
          <div className="flex items-center gap-2 px-4 py-2.5 rounded-xl bg-[rgba(0,0,0,0.03)] border border-[rgba(0,0,0,0.07)]">
            <Icon icon="mdi:lightning-bolt" width={14} color="rgba(0,0,0,0.4)" />
            <span className="text-[12px] text-[rgba(0,0,0,0.65)] font-semibold">Media Engine — Ready</span>
          </div>
          <div className="flex items-center gap-2 px-4 py-2.5 rounded-xl bg-[rgba(0,0,0,0.03)] border border-[rgba(0,0,0,0.07)]">
            <span className={`w-2 h-2 rounded-full ${(overview?.processingJobs ?? 0) > 0 ? 'bg-emerald-500 animate-pulse' : 'bg-[rgba(0,0,0,0.2)]'}`} />
            <span className="text-[12px] text-[rgba(0,0,0,0.65)] font-semibold">
              {(overview?.processingJobs ?? 0) > 0 ? `${overview!.processingJobs} job(s) processing` : 'Worker idle'}
            </span>
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
              <Icon icon="mdi:movie-open-outline" className="text-[rgba(0,0,0,0.4)] text-base" />
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
                    job.status === 'completed' ? 'bg-emerald-50' : job.status === 'failed' ? 'bg-red-50' : 'bg-[rgba(0,0,0,0.04)]'
                  }`}>
                    <Icon icon={job.status === 'completed' ? 'mdi:check' : job.status === 'processing' ? 'mdi:loading' : job.status === 'failed' ? 'mdi:close' : 'mdi:clock-outline'}
                      className={`text-sm ${job.status === 'completed' ? 'text-emerald-500' : job.status === 'processing' ? 'text-[rgba(0,0,0,0.4)] animate-spin' : job.status === 'failed' ? 'text-red-500' : 'text-[rgba(0,0,0,0.35)]'}`} />
                  </div>
                  <div className="flex-1 min-w-0">
                    <p className="text-[12px] font-semibold text-[rgba(0,0,0,0.88)] truncate">{job.name}</p>
                    <p className="text-[11px] text-[rgba(0,0,0,0.4)]">{job.inputFormat} → {job.outputFormat} · {job.resolution} · {job.createdAt}</p>
                    {job.status === 'processing' && <ProgressBar value={job.progress} />}
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
              <Icon icon="mdi:broadcast" className="text-[rgba(0,0,0,0.4)] text-base" />
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
        <p className="text-[12px] font-bold uppercase tracking-wider text-[rgba(0,0,0,0.5)] mb-4">Quick Commands</p>
        <div className="grid grid-cols-1 md:grid-cols-3 gap-3">
          {[
            { label: 'Convert to HLS', icon: 'mdi:film', cmd: 'ffmpeg -i input.mp4 -hls_time 10 -hls_list_size 0 output.m3u8' },
            { label: 'Transcode to 720p', icon: 'mdi:resize', cmd: 'ffmpeg -i input.mp4 -vf scale=1280:720 -c:a copy output.mp4' },
            { label: 'Live RTMP → HLS', icon: 'mdi:broadcast', cmd: 'ffmpeg -i rtmp://localhost/live/stream -hls_time 2 stream.m3u8' },
          ].map((item, i) => (
            <div key={i} className="rounded-xl border border-[rgba(0,0,0,0.08)] p-3 hover:border-[rgba(0,0,0,0.15)] transition-colors">
              <div className="flex items-center gap-2 mb-2">
                <div className="w-7 h-7 bg-[rgba(0,0,0,0.04)] rounded-lg flex items-center justify-center">
                  <Icon icon={item.icon} width={14} color="rgba(0,0,0,0.4)" />
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
  onDownloadJob: (id: number, fileName?: string) => Promise<void>;
  onCancelJob: (id: number) => Promise<void>;
  onPauseJob: (id: number) => Promise<void>;
  onResumeJob: (id: number) => Promise<void>;
}

// Backend returns paths like /media/jobs/48/master.m3u8 — use as-is (proxied via Vite → 8084).
// Only prepend /media/ for bare relative paths that don't already have it.
function resolveMediaUrl(url: string): string {
  if (!url) return '';
  if (url.startsWith('http')) return url;
  if (url.startsWith('/')) return url;          // already absolute path
  return `/media/${url}`;
}

// Player-compatibility note for a job's output format, shown next to the output URL.
function outputCompatNote(format: string): { good: boolean; text: string } {
  switch ((format || '').toUpperCase()) {
    case 'MP4':
    case 'MOV':
      return { good: true, text: 'Universal — plays in any video player app or device.' };
    case 'MP3':
    case 'AAC':
      return { good: true, text: 'Universal audio — plays in any player.' };
    case 'HLS':
    case 'DASH':
      return { good: false, text: 'Streaming format — plays in browsers & VLC. For any device/player app, transcode to MP4.' };
    case 'WEBM':
      return { good: false, text: 'Plays in browsers, Android & VLC — not Apple/QuickTime. For universal playback, use MP4.' };
    case 'MKV':
      return { good: false, text: 'Plays in VLC & most desktop players — not browsers/Apple. For universal playback, use MP4.' };
    case 'FLAC':
    case 'OGG':
      return { good: false, text: 'Plays in VLC & many players — limited on Apple. For universal audio, use MP3/AAC.' };
    default:
      return { good: true, text: 'Plays in compatible video players.' };
  }
}

function TranscodeTab({ jobs, loading, onCreateJob, onUploadFile, onRetryJob, onDownloadJob, onCancelJob, onPauseJob, onResumeJob }: TranscodeTabProps) {
  const [showForm, setShowForm] = useState(false);
  const [filterStatus, setFilterStatus] = useState<JobStatus | 'all'>('all');
  const [jobSearch, setJobSearch] = useState('');
  const [jobFormatFilter, setJobFormatFilter] = useState<string>('all');
  const [jobPage, setJobPage] = useState(1);
  const [jobPageSize, setJobPageSize] = useState(25);
  // Rows whose source path the user clicked to read in full.
  const [expandedJobNames, setExpandedJobNames] = useState<Set<string>>(new Set());
  const [sourceMode, setSourceMode] = useState<'url' | 'upload'>('url');
  const [sourceUrl, setSourceUrl] = useState('');
  const [localFile, setLocalFile] = useState<File | null>(null);
  const [uploadProgress, setUploadProgress] = useState<'idle' | 'uploading' | 'done'>('idle');
  const [outputFmts, setOutputFmts] = useState<string[]>(['HLS']);
  const [resolutions, setResolutions] = useState<string[]>(['1080p']);
  const [bitrate, setBitrate] = useState('');
  const [encodingSpeed, setEncodingSpeed] = useState('veryfast');
  const [priorityFormat, setPriorityFormat] = useState<string | null>(null);
  const [generateAdaptiveBundle, setGenerateAdaptiveBundle] = useState(false);
  const [s3PickerOpen, setS3PickerOpen] = useState(false);
  const [s3PickedFiles, setS3PickedFiles] = useState<import('../components/mediaops/S3PickerModal').S3PickedFile[]>([]);
  const [enableHLS, setEnableHLS] = useState(true);
  // Output destination, encoded as a single dropdown value:
  //   ''            → XamOps storage (default)
  //   'cfg:<id>'    → a key-based connected bucket (user_s3_configs)
  //   'acct:<name>' → a bucket in the sidebar-selected AWS account
  const { awsAccounts, selectedAccountIds } = useAccounts();
  const outputAccount = awsAccounts.find(a => selectedAccountIds.includes(a.id) && a.status === 'CONNECTED')
    ?? awsAccounts.find(a => a.status === 'CONNECTED')
    ?? null;
  const outputAccountId = outputAccount?.id ?? null;
  const [outputDest, setOutputDest] = useState<string>('');
  const [outputConfigs, setOutputConfigs] = useState<UserS3ConfigDto[]>([]);
  const [accountBuckets, setAccountBuckets] = useState<string[]>([]);
  const [accountBucketsLoading, setAccountBucketsLoading] = useState(false);
  const [accountBucketsError, setAccountBucketsError] = useState<string | null>(null);
  const [submitLoading, setSubmitLoading] = useState(false);
  const [submitError, setSubmitError] = useState<string | null>(null);
  const [copiedId, setCopiedId] = useState<string | null>(null);
  const [copiedUrlId, setCopiedUrlId] = useState<string | null>(null);
  const [playerUrl, setPlayerUrl] = useState<string | null>(null);
  const [dashPlayerUrl, setDashPlayerUrl] = useState<string | null>(null);
  const [videoPlayerUrl, setVideoPlayerUrl] = useState<string | null>(null);
  const [retryingId, setRetryingId] = useState<number | null>(null);
  const [downloadingId, setDownloadingId] = useState<number | null>(null);
  const [downloadError, setDownloadError] = useState<string | null>(null);
  const [expandedError, setExpandedError] = useState<string | null>(null);
  const [expandedUrl, setExpandedUrl] = useState<string | null>(null);
  const [expandedThumbs, setExpandedThumbs] = useState<string | null>(null);
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

  // Load output destinations when the form opens: the key-based connected buckets
  // AND all S3 buckets of the sidebar-selected AWS account (via its IAM role).
  // Default stays XamOps storage.
  useEffect(() => {
    if (!showForm) return;
    let cancelled = false;
    fetchUserS3Configs()
      .then(configs => { if (!cancelled) setOutputConfigs(configs); })
      .catch(() => { if (!cancelled) setOutputConfigs([]); });
    return () => { cancelled = true; };
  }, [showForm]);

  useEffect(() => {
    if (!showForm || !outputAccountId) { setAccountBuckets([]); return; }
    let cancelled = false;
    setAccountBucketsLoading(true);
    setAccountBucketsError(null);
    fetch(`/api/s3-browser/buckets?accountId=${encodeURIComponent(outputAccountId)}`, { credentials: 'include' })
      .then(async res => {
        if (!res.ok) throw new Error((await res.json().catch(() => ({}))).error || `Failed to load buckets (${res.status})`);
        return res.json() as Promise<Array<{ name: string }>>;
      })
      .then(list => { if (!cancelled) setAccountBuckets(list.map(b => b.name)); })
      .catch(e => { if (!cancelled) { setAccountBuckets([]); setAccountBucketsError(e.message); } })
      .finally(() => { if (!cancelled) setAccountBucketsLoading(false); });
    return () => { cancelled = true; };
  }, [showForm, outputAccountId]);

  // Paused jobs stay visible under the Processing filter — they're still active work.
  const statusFiltered = filterStatus === 'all'
    ? jobs
    : jobs.filter(j => j.status === filterStatus || (filterStatus === 'processing' && j.status === 'paused'));

  const formatOptions = Array.from(new Set(jobs.map(j => j.outputFormat).filter(f => f && f !== '—'))).sort();

  const filtered = statusFiltered.filter(j => {
    if (jobFormatFilter !== 'all' && j.outputFormat !== jobFormatFilter) return false;
    if (!jobSearch.trim()) return true;
    const q = jobSearch.trim().toLowerCase();
    // Match on the full path so "drsakshi" finds every job from that folder.
    return j.sourcePath.toLowerCase().includes(q) || j.name.toLowerCase().includes(q);
  });

  const totalPages = Math.max(1, Math.ceil(filtered.length / jobPageSize));
  const currentPage = Math.min(jobPage, totalPages);
  const pagedJobs = filtered.slice((currentPage - 1) * jobPageSize, currentPage * jobPageSize);

  // Any change to what is being filtered invalidates the current page number.
  useEffect(() => { setJobPage(1); }, [filterStatus, jobSearch, jobFormatFilter, jobPageSize]);

  const handleRetry = useCallback(async (job: TranscodeJob) => {
    setRetryingId(job.rawId);
    try { await onRetryJob(job.rawId); } catch (e: any) { console.error('Retry failed', e); } finally { setRetryingId(null); }
  }, [onRetryJob]);

  // Pause / resume / stop for running jobs. One busy-id covers all three so a row's
  // control buttons lock together while a request is in flight.
  const [controllingId, setControllingId] = useState<number | null>(null);
  const handlePause = useCallback(async (job: TranscodeJob) => {
    setControllingId(job.rawId);
    try { await onPauseJob(job.rawId); } catch (e: any) { console.error('Pause failed', e); } finally { setControllingId(null); }
  }, [onPauseJob]);
  const handleResume = useCallback(async (job: TranscodeJob) => {
    setControllingId(job.rawId);
    try { await onResumeJob(job.rawId); } catch (e: any) { console.error('Resume failed', e); } finally { setControllingId(null); }
  }, [onResumeJob]);
  const handleStop = useCallback(async (job: TranscodeJob) => {
    if (!window.confirm('Stop this job? Encoding progress will be lost — you can start it again later with Retry.')) return;
    setControllingId(job.rawId);
    try { await onCancelJob(job.rawId); } catch (e: any) { console.error('Stop failed', e); } finally { setControllingId(null); }
  }, [onCancelJob]);

  const handleDownload = useCallback(async (job: TranscodeJob) => {
    setDownloadingId(job.rawId);
    setDownloadError(null);
    try {
      await onDownloadJob(job.rawId, job.name);
    } catch (e: any) {
      console.error('Download failed', e);
      setDownloadError(e?.message || 'Download failed');
    } finally {
      setDownloadingId(null);
    }
  }, [onDownloadJob]);

  const canSubmit = s3PickedFiles.length > 0
    ? true
    : sourceMode === 'url' ? !!sourceUrl.trim() : !!localFile;

  const formState: import('../components/mediaops/SimpleMode').SimpleModeFormState = {
    sourceMode, sourceUrl, localFile, uploadProgress,
    outputFormats: outputFmts, resolutions, bitrate, encodingSpeed,
    enableAdaptive: enableHLS, generateAdaptiveBundle, priorityFormat, s3PickedFiles,
  };

  const handleFormChange = useCallback((updates: Partial<import('../components/mediaops/SimpleMode').SimpleModeFormState>) => {
    if (updates.sourceMode !== undefined) setSourceMode(updates.sourceMode);
    if (updates.sourceUrl !== undefined) setSourceUrl(updates.sourceUrl);
    if (updates.localFile !== undefined) setLocalFile(updates.localFile);
    if (updates.uploadProgress !== undefined) setUploadProgress(updates.uploadProgress);
    if (updates.outputFormats !== undefined) setOutputFmts(updates.outputFormats);
    if (updates.resolutions !== undefined) setResolutions(updates.resolutions);
    if (updates.bitrate !== undefined) setBitrate(updates.bitrate);
    if (updates.encodingSpeed !== undefined) setEncodingSpeed(updates.encodingSpeed);
    if (updates.enableAdaptive !== undefined) setEnableHLS(updates.enableAdaptive);
    if (updates.generateAdaptiveBundle !== undefined) setGenerateAdaptiveBundle(updates.generateAdaptiveBundle);
    if ('priorityFormat' in updates) setPriorityFormat(updates.priorityFormat ?? null);
    if ('s3PickedFiles' in updates) setS3PickedFiles(updates.s3PickedFiles ?? []);
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

      // Pre-upload shared assets once (subtitle, watermark) to reuse across jobs
      let subtitlePath: string | undefined;
      let watermarkImagePath: string | undefined;
      if (advancedState?.subtitleFile) {
        const uploadedSubtitle = await onUploadFile(advancedState.subtitleFile);
        subtitlePath = uploadedSubtitle.filePath;
      }
      if (advancedState?.watermarkType === 'image' && advancedState.watermarkImage) {
        const uploadedWatermark = await onUploadFile(advancedState.watermarkImage);
        watermarkImagePath = uploadedWatermark.filePath;
      }

      // Map the encoded output-destination selection onto the request.
      const applyOutputDest = (req: CreateJobRequest) => {
        if (outputDest.startsWith('cfg:')) {
          req.outputS3ConfigId = Number(outputDest.slice(4));
        } else if (outputDest.startsWith('acct:') && outputAccountId) {
          req.outputAccountId = outputAccountId;
          req.outputBucket = outputDest.slice(5);
        }
      };

      // One entry per source. Files picked from S3 carry their key so output mirrors the
      // input path, plus the bucket/region/account the object lives in so the backend can
      // re-sign the presigned URL when the job runs. A pasted URL or upload has neither and
      // keeps the jobs/<id> layout.
      type Source = {
        url: string; key?: string; bucket?: string; region?: string; accountId?: string;
      };
      const sources: Source[] = s3PickedFiles.length > 0
        ? s3PickedFiles.map(f => ({
            url: f.url, key: f.key, bucket: f.bucket, region: f.region, accountId: f.accountId,
          }))
        : [{ url: finalSourceUrl }];

      const buildRequest = (
        source: Source,
        fmt: string, res: string, jobPriority: 'HIGH' | 'NORMAL' | 'LOW',
      ): CreateJobRequest => {
        const req: CreateJobRequest = {
          sourceUrl: source.url,
          outputFormat: fmt,
          resolution: res,
          enableAdaptive: enableHLS && (fmt === 'HLS' || fmt === 'DASH'),
        };
        if (source.key) req.sourceKey = source.key;
        if (source.bucket) req.sourceBucket = source.bucket;
        if (source.region) req.sourceRegion = source.region;
        if (source.accountId) req.sourceAccountId = source.accountId;
        if (bitrate.trim()) req.bitrate = bitrate.trim();
        req.encodingSpeed = encodingSpeed;
        if (jobPriority !== 'NORMAL') req.priority = jobPriority;
        applyOutputDest(req);

        if (advancedState) {
          if (advancedState.presetId) req.presetId = advancedState.presetId;
          if (advancedState.codec) req.codec = advancedState.codec;
          if (advancedState.priority !== 'NORMAL' && jobPriority === 'NORMAL') req.priority = advancedState.priority;
          if (advancedState.startTime) req.startTime = advancedState.startTime;
          if (advancedState.endTime) req.endTime = advancedState.endTime;
          if (subtitlePath) req.subtitlePath = subtitlePath;
          if (advancedState.subtitleMode) req.subtitleMode = advancedState.subtitleMode;
          if (watermarkImagePath) {
            req.watermarkImagePath = watermarkImagePath;
            req.watermarkX = advancedState.watermarkX;
            req.watermarkY = advancedState.watermarkY;
            req.watermarkOpacity = advancedState.watermarkOpacity / 100;
            req.watermarkScale = advancedState.watermarkScale;
          } else if (advancedState.watermarkType === 'text' && advancedState.watermarkText) {
            req.watermarkText = advancedState.watermarkText;
            req.watermarkX = advancedState.watermarkX;
            req.watermarkY = advancedState.watermarkY;
            req.watermarkOpacity = advancedState.watermarkOpacity / 100;
            req.watermarkScale = advancedState.watermarkScale;
          }
          if (advancedState.thumbnailEnabled) {
            req.thumbnailEnabled = true;
            req.thumbnailIntervalSeconds = advancedState.thumbnailIntervalSeconds;
            req.thumbnailTimestamp = advancedState.thumbnailTimestamp;
          }
          if (advancedState.audioBitrate && advancedState.audioBitrate !== '128k') req.audioBitrate = advancedState.audioBitrate;
          if (advancedState.audioSampleRate) req.audioSampleRate = advancedState.audioSampleRate;
          if (advancedState.audioChannels) req.audioChannels = advancedState.audioChannels;
          if (advancedState.customFfmpegParams) req.customFfmpegParams = advancedState.customFfmpegParams;
          if (advancedState.scheduledAt) req.scheduledAt = advancedState.scheduledAt;
          if (advancedState.encryptionEnabled) req.encryptionEnabled = true;
        }
        return req;
      };

      // Build ordered list of (format, resolution) combinations
      // priorityFormat jobs go first (HIGH priority), rest are NORMAL
      const orderedFormats = priorityFormat
        ? [priorityFormat, ...outputFmts.filter(f => f !== priorityFormat)]
        : outputFmts;

      const AUDIO_ONLY = ['MP3', 'AAC', 'FLAC', 'OGG'];
      const combinations: Array<{ fmt: string; res: string; prio: 'HIGH' | 'NORMAL' | 'LOW' }> = [];
      for (const fmt of orderedFormats) {
        const isAudio = AUDIO_ONLY.includes(fmt);
        const resList = isAudio ? [''] : resolutions;
        for (const res of resList) {
          combinations.push({
            fmt,
            res: isAudio ? resolutions[0] ?? '1080p' : res,
            prio: (priorityFormat && fmt === priorityFormat) ? 'HIGH' : 'NORMAL',
          });
        }
      }

      // Every format/resolution combination is applied to every selected source.
      for (const source of sources) {
        for (const { fmt, res, prio } of combinations) {
          await onCreateJob(buildRequest(source, fmt, res, prio));
        }

        // Adaptive bundle jobs — one per HLS/DASH format (combined multi-variant, enableAdaptive=true)
        if (generateAdaptiveBundle && !enableHLS && resolutions.length > 1) {
          const bundleFormats = outputFmts.filter(f => f === 'HLS' || f === 'DASH');
          for (const fmt of bundleFormats) {
            const bundleReq: CreateJobRequest = {
              sourceUrl: source.url,
              outputFormat: fmt,
              resolution: '1080p',
              enableAdaptive: true,
            };
            if (source.key) bundleReq.sourceKey = source.key;
            if (source.bucket) bundleReq.sourceBucket = source.bucket;
            if (source.region) bundleReq.sourceRegion = source.region;
            if (source.accountId) bundleReq.sourceAccountId = source.accountId;
            if (bitrate.trim()) bundleReq.bitrate = bitrate.trim();
            bundleReq.encodingSpeed = encodingSpeed;
            applyOutputDest(bundleReq);
            await onCreateJob(bundleReq);
          }
        }
      }

      setShowForm(false);
      setSourceUrl('');
      setLocalFile(null);
      setBitrate('');
      setPriorityFormat(null);
      setUploadProgress('idle');
      setS3PickedFiles([]);
    } catch (e: any) {
      setSubmitError(e.message || 'Failed to submit job');
      setUploadProgress('idle');
    } finally {
      setSubmitLoading(false);
    }
  }, [canSubmit, sourceMode, sourceUrl, localFile, outputFmts, resolutions, enableHLS, generateAdaptiveBundle, priorityFormat, bitrate, encodingSpeed, outputDest, outputAccountId, s3PickedFiles, onCreateJob, onUploadFile]);

  const handleCancel = useCallback(() => {
    setShowForm(false);
    setSubmitError(null);
    setSourceUrl('');
    setLocalFile(null);
    setBitrate('');
    setEncodingSpeed('veryfast');
    setSourceMode('url');
    setUploadProgress('idle');
    setS3PickedFiles([]);
    setPriorityFormat(null);
    setGenerateAdaptiveBundle(false);
  }, []);

  const copyCmd = useCallback((job: TranscodeJob) => {
    const cmd = job.ffmpegCommand ||
      `ffmpeg -i "${job.name}" -vf scale=${job.resolution === '4K' ? '3840:2160' : job.resolution === '1080p' ? '1920:1080' : '1280:720'} -c:v libx264 -preset fast -b:v ${job.bitrate} ${job.outputFormat === 'HLS' ? '-hls_time 10 -hls_list_size 0 output.m3u8' : 'output.mp4'}`;
    navigator.clipboard.writeText(cmd);
    setCopiedId(job.id);
    setTimeout(() => setCopiedId(null), 1800);
  }, []);

  // Primary shareable output URL for a job (HLS → DASH → progressive video)
  const jobOutputUrl = useCallback((job: TranscodeJob): string | null => {
    const rel = job.hlsUrl || job.dashUrl || job.videoUrl;
    if (!rel) return null;
    const resolved = resolveMediaUrl(rel);
    return resolved.startsWith('http') ? resolved : `${window.location.origin}${resolved}`;
  }, []);

  const copyOutputUrl = useCallback((job: TranscodeJob) => {
    const url = jobOutputUrl(job);
    if (!url) return;
    navigator.clipboard.writeText(url);
    setCopiedUrlId(job.id);
    setTimeout(() => setCopiedUrlId(null), 1800);
  }, [jobOutputUrl]);

  const jobCounts: Record<JobStatus | 'all', number> = {
    all: jobs.length,
    // paused jobs count as processing — they're still active work, just suspended
    processing: jobs.filter(j => j.status === 'processing' || j.status === 'paused').length,
    paused: jobs.filter(j => j.status === 'paused').length,
    queued: jobs.filter(j => j.status === 'queued').length,
    completed: jobs.filter(j => j.status === 'completed').length,
    failed: jobs.filter(j => j.status === 'failed').length,
    cancelled: jobs.filter(j => j.status === 'cancelled').length,
  };

  return (
    <div className="space-y-5">
      {/* Download error toast */}
      {downloadError && (
        <div className="fixed bottom-6 right-6 z-50 flex items-center gap-2 px-4 py-2.5 bg-red-50 border border-red-200 rounded-xl shadow-lg text-[12px] font-semibold text-red-700">
          <Icon icon="mdi:alert-circle-outline" className="text-base" />
          <span>{downloadError}</span>
          <button onClick={() => setDownloadError(null)} className="ml-1 text-red-400 hover:text-red-600">
            <Icon icon="mdi:close" className="text-sm" />
          </button>
        </div>
      )}

      {/* Title + primary action */}
      <div className="flex items-end justify-between gap-3">
        <div>
          <h2 className="text-[15px] font-bold text-[rgba(0,0,0,0.88)] leading-tight">Transcoding Jobs</h2>
          <p className="text-[11.5px] text-[rgba(0,0,0,0.4)] leading-tight mt-0.5">Convert, package &amp; stream — track every job in real time</p>
        </div>
        <button onClick={() => setShowForm(v => !v)}
          className="group flex items-center gap-2 px-4 py-2 bg-[rgba(0,0,0,0.88)] text-white text-[12px] font-bold rounded-xl transition-all duration-200 hover:bg-[rgba(0,0,0,0.78)] active:scale-[0.97] shadow-sm flex-shrink-0">
          <Icon icon="mdi:plus" className={`text-base transition-transform duration-300 ${showForm ? 'rotate-[135deg]' : 'group-hover:rotate-90'}`} />
          {showForm ? 'Close' : 'New Job'}
        </button>
      </div>

      {/* Interactive stat-filter cards — click to filter the table below */}
      <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-5 gap-3">
        {STAT_DEFS.map((s, i) => {
          const active = filterStatus === s.id;
          const count = jobCounts[s.id];
          return (
            <motion.button
              key={s.id}
              onClick={() => setFilterStatus(s.id)}
              initial={{ opacity: 0, y: 8 }}
              animate={{ opacity: 1, y: 0 }}
              transition={{ duration: 0.32, delay: 0.04 + i * 0.05, ease: [0.22, 1, 0.36, 1] }}
              className={`group relative text-left bg-white border rounded-xl px-4 py-3 transition-all duration-200 active:scale-[0.98] card-hover-lift ${
                active
                  ? `${s.activeBorder} ring-2 ${s.activeRing} shadow-sm`
                  : 'border-[rgba(0,0,0,0.10)] hover:border-[rgba(0,0,0,0.18)]'
              }`}
            >
              <div className="flex items-center justify-between mb-1">
                <span className={`text-[10.5px] font-bold uppercase tracking-[0.07em] transition-colors ${active ? 'text-[rgba(0,0,0,0.7)]' : 'text-[rgba(0,0,0,0.45)] group-hover:text-[rgba(0,0,0,0.6)]'}`}>{s.label}</span>
                <div className={`w-6 h-6 rounded-lg flex items-center justify-center transition-colors ${active ? s.iconActiveBg : 'bg-[rgba(0,0,0,0.04)] group-hover:bg-[rgba(0,0,0,0.06)]'}`}>
                  <Icon icon={s.icon} className={`text-[13px] transition-colors ${active ? s.iconActiveColor : 'text-[rgba(0,0,0,0.4)]'} ${s.id === 'processing' && count > 0 ? 'animate-pulse' : ''}`} />
                </div>
              </div>
              <p style={JOB_NUM_STYLE}>{count}</p>
            </motion.button>
          );
        })}
      </div>

      {/* New Job Form */}
      <AnimatePresence>
        {showForm && (
          <motion.div initial={{ opacity: 0, y: -10, scale: 0.99 }} animate={{ opacity: 1, y: 0, scale: 1 }} exit={{ opacity: 0, y: -10, scale: 0.99 }} transition={{ duration: 0.28, ease: [0.22, 1, 0.36, 1] }}
            className="bg-white border border-[rgba(0,0,0,0.10)] rounded-2xl shadow-[0_1px_3px_rgba(0,0,0,0.04),0_12px_32px_-16px_rgba(0,0,0,0.16)] overflow-hidden">
            {/* Header */}
            <div className="flex items-center justify-between gap-4 px-5 py-4 border-b border-[rgba(0,0,0,0.06)] bg-gradient-to-b from-[rgba(0,0,0,0.018)] to-transparent">
              <div className="flex items-center gap-3 min-w-0">
                <div className="w-9 h-9 rounded-xl bg-[rgba(0,0,0,0.88)] flex items-center justify-center flex-shrink-0 shadow-sm">
                  <Icon icon="mdi:film-plus" className="text-white text-[18px]" />
                </div>
                <div className="min-w-0">
                  <p className="text-[14px] font-bold text-[rgba(0,0,0,0.88)] leading-tight">New Transcoding Job</p>
                  <p className="text-[11px] text-[rgba(0,0,0,0.4)] leading-tight mt-0.5 truncate">
                    {transcodeMode === 'simple' ? 'Quick setup — pick a format and go' : 'Full control over codecs, watermarks, trimming & more'}
                  </p>
                </div>
              </div>
              {/* Segmented mode toggle with sliding indicator */}
              <div className="flex items-center gap-0.5 bg-[rgba(0,0,0,0.045)] rounded-xl p-1 flex-shrink-0">
                {([['simple', 'mdi:lightning-bolt', 'Simple'], ['advanced', 'mdi:tune-variant', 'Advanced']] as const).map(([mode, icon, label]) => {
                  const active = transcodeMode === mode;
                  return (
                    <button key={mode} onClick={() => handleModeChange(mode)}
                      className={`relative flex items-center gap-1.5 px-3.5 py-1.5 rounded-lg text-[11.5px] font-bold transition-colors duration-200 ${active ? 'text-[rgba(0,0,0,0.88)]' : 'text-[rgba(0,0,0,0.42)] hover:text-[rgba(0,0,0,0.62)]'}`}>
                      {active && (
                        <motion.span layoutId="transcodeModePill"
                          className="absolute inset-0 bg-white rounded-lg shadow-[0_1px_2px_rgba(0,0,0,0.06),0_2px_8px_-2px_rgba(0,0,0,0.14)]"
                          transition={{ type: 'spring', stiffness: 420, damping: 34 }} />
                      )}
                      <Icon icon={icon} className="relative z-10 text-[15px]" />
                      <span className="relative z-10">{label}</span>
                    </button>
                  );
                })}
              </div>
            </div>

            {/* Body */}
            <div className="p-5">
              {/* Output destination — where the transcoded files are stored */}
              <div className="mb-5 rounded-xl border border-[rgba(0,0,0,0.08)] bg-[rgba(0,0,0,0.015)] p-3.5">
                <div className="flex items-center gap-2 mb-2">
                  <Icon icon="mdi:cloud-upload-outline" className="text-[15px] text-[rgba(0,0,0,0.55)]" />
                  <span className="text-[12px] font-bold text-[rgba(0,0,0,0.72)]">Store output in</span>
                </div>
                <div className="flex items-center gap-2.5 flex-wrap">
                  <div className="relative flex-1 min-w-[220px]">
                    <select
                      value={outputDest}
                      onChange={e => setOutputDest(e.target.value)}
                      className="w-full appearance-none rounded-lg border border-[rgba(0,0,0,0.12)] bg-white pl-3 pr-9 py-2 text-[12.5px] font-medium text-[rgba(0,0,0,0.82)] outline-none focus:border-[rgba(0,0,0,0.4)] transition-colors">
                      <option value="">XamOps storage (default)</option>
                      {outputAccount && (
                        <optgroup label={`${outputAccount.name} (${outputAccount.id})`}>
                          {accountBucketsLoading && <option disabled>Loading buckets…</option>}
                          {!accountBucketsLoading && accountBuckets.length === 0 && (
                            <option disabled>{accountBucketsError ? 'Could not load buckets' : 'No buckets found'}</option>
                          )}
                          {accountBuckets.map(b => (
                            <option key={`acct:${b}`} value={`acct:${b}`}>{b}</option>
                          ))}
                        </optgroup>
                      )}
                      {outputConfigs.length > 0 && (
                        <optgroup label="Connected buckets (access keys)">
                          {outputConfigs.map(c => (
                            <option key={`cfg:${c.id}`} value={`cfg:${c.id}`}>
                              {c.nickname} — {c.bucketName} ({c.region})
                            </option>
                          ))}
                        </optgroup>
                      )}
                    </select>
                    <Icon icon="mdi:chevron-down" className="pointer-events-none absolute right-2.5 top-1/2 -translate-y-1/2 text-[16px] text-[rgba(0,0,0,0.4)]" />
                  </div>
                </div>
                <p className="mt-2 text-[11px] leading-snug text-[rgba(0,0,0,0.42)]">
                  {outputDest.startsWith('acct:')
                    ? `Transcoded files are written to this bucket in account ${outputAccount?.id} using its connected role. The bucket stays private — playback & download stream securely through XamOps.`
                    : outputDest.startsWith('cfg:')
                      ? 'Transcoded files are uploaded to your connected bucket. The bucket stays private — playback and download stream securely through XamOps.'
                      : outputAccount
                        ? `Files are stored in XamOps storage. Pick a bucket from ${outputAccount.name} to keep output in your own AWS account.`
                        : 'Files are stored in XamOps storage. Select an AWS account in the sidebar to store output in your own account.'}
                </p>
              </div>

              <AnimatePresence mode="wait" initial={false}>
                <motion.div key={transcodeMode}
                  initial={{ opacity: 0, x: transcodeMode === 'simple' ? -10 : 10 }}
                  animate={{ opacity: 1, x: 0 }}
                  exit={{ opacity: 0, x: transcodeMode === 'simple' ? 10 : -10 }}
                  transition={{ duration: 0.2, ease: [0.22, 1, 0.36, 1] }}>
                  {transcodeMode === 'simple' ? (
                    <SimpleMode
                      formState={formState}
                      onFormChange={handleFormChange}
                      onSubmit={submitJob}
                      onCancel={handleCancel}
                      canSubmit={canSubmit}
                      submitLoading={submitLoading}
                      submitError={submitError}
                      onBrowseS3={() => setS3PickerOpen(true)}
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
                      onBrowseS3={() => setS3PickerOpen(true)}
                    />
                  )}
                </motion.div>
              </AnimatePresence>
            </div>
          </motion.div>
        )}
      </AnimatePresence>

      {s3PickerOpen && (
        <S3PickerModal
          onClose={() => setS3PickerOpen(false)}
          onSelect={(files) => {
            setSourceMode('url');
            setS3PickedFiles(files);
            // Keep the URL field in step for a single pick so the rest of the form reads
            // naturally; with several sources the picked-file list is the source of truth.
            setSourceUrl(files.length === 1 ? files[0].url : '');
            setS3PickerOpen(false);
          }}
        />
      )}

      {/* Jobs Table */}
      <div className="bg-white border border-[rgba(0,0,0,0.08)] rounded-2xl shadow-sm overflow-hidden">
        {/* Caption strip — active filter context + clear affordance */}
        <div className="flex items-center justify-between gap-3 flex-wrap px-5 py-3 border-b border-[rgba(0,0,0,0.06)] bg-gradient-to-b from-[rgba(0,0,0,0.012)] to-transparent">
          <div className="flex items-center gap-2">
            <span className="text-[12.5px] font-bold text-[rgba(0,0,0,0.82)]">{STAT_DEFS.find(s => s.id === filterStatus)?.label ?? 'All Jobs'}</span>
            <span className="px-1.5 py-0.5 rounded-md bg-[rgba(0,0,0,0.05)] text-[10.5px] font-bold text-[rgba(0,0,0,0.5)] tabular-nums">{filtered.length}</span>
          </div>

          <div className="flex items-center gap-2 flex-wrap">
            <div className="relative">
              <Icon icon="mdi:magnify" className="absolute left-2.5 top-1/2 -translate-y-1/2 text-[rgba(0,0,0,0.3)] text-sm" />
              <input type="text" value={jobSearch} onChange={e => setJobSearch(e.target.value)}
                placeholder="Search file or folder…"
                className="pl-8 pr-3 py-1.5 text-[11.5px] border border-[rgba(0,0,0,0.12)] rounded-lg focus:outline-none focus:border-blue-400 w-52" />
            </div>

            <select value={jobFormatFilter} onChange={e => setJobFormatFilter(e.target.value)}
              className="px-2.5 py-1.5 text-[11.5px] border border-[rgba(0,0,0,0.12)] rounded-lg bg-white focus:outline-none">
              <option value="all">All formats</option>
              {formatOptions.map(f => <option key={f} value={f}>{f}</option>)}
            </select>

            <select value={jobPageSize} onChange={e => setJobPageSize(Number(e.target.value))}
              className="px-2.5 py-1.5 text-[11.5px] border border-[rgba(0,0,0,0.12)] rounded-lg bg-white focus:outline-none">
              {[10, 25, 50, 100].map(n => <option key={n} value={n}>{n} / page</option>)}
            </select>

            {(filterStatus !== 'all' || jobSearch || jobFormatFilter !== 'all') && (
              <button onClick={() => { setFilterStatus('all'); setJobSearch(''); setJobFormatFilter('all'); }}
                className="flex items-center gap-1 px-2 py-1.5 text-[11px] font-semibold text-[rgba(0,0,0,0.45)] hover:text-[rgba(0,0,0,0.8)] transition-colors">
                <Icon icon="mdi:filter-remove-outline" className="text-[13px]" />
                Clear
              </button>
            )}
          </div>
        </div>
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
                {pagedJobs.map(job => (
                  <React.Fragment key={job.id}>
                    <tr className={`group/row transition-colors ${job.status === 'failed' ? 'bg-red-50/40 hover:bg-red-50' : 'hover:bg-[rgba(0,0,0,0.018)]'}`}>
                      <td className="px-4 py-3">
                        <div className="flex items-start gap-2">
                          <div className="w-7 h-7 rounded-lg bg-[rgba(0,0,0,0.04)] group-hover/row:bg-blue-50 flex items-center justify-center flex-shrink-0 transition-colors">
                            <Icon icon="mdi:file-video-outline" className="text-[rgba(0,0,0,0.4)] group-hover/row:text-blue-500 text-sm transition-colors" />
                          </div>
                          {/* Click to read the whole path — source names regularly outrun the
                              column, and a folder is often the only thing distinguishing two
                              jobs both called "1.mp4". */}
                          <button
                            onClick={() => setExpandedJobNames(prev => {
                              const next = new Set(prev);
                              if (next.has(job.id)) next.delete(job.id); else next.add(job.id);
                              return next;
                            })}
                            title={expandedJobNames.has(job.id) ? 'Click to collapse' : job.sourcePath}
                            className={`text-left ${expandedJobNames.has(job.id) ? 'max-w-[260px]' : 'max-w-[180px]'}`}>
                            {job.sourceFolder && (
                              <span className={`block text-[10px] text-[rgba(0,0,0,0.4)] ${expandedJobNames.has(job.id) ? 'break-all' : 'truncate'}`}>
                                {job.sourceFolder}/
                              </span>
                            )}
                            <span className={`block text-[12px] font-semibold text-[rgba(0,0,0,0.88)] hover:text-blue-700 ${
                              expandedJobNames.has(job.id) ? 'break-all' : 'truncate'}`}>
                              {job.name}
                            </span>
                          </button>
                        </div>
                      </td>
                      <td className="px-4 py-3">
                        <span className="text-[11px] font-mono font-semibold text-[rgba(0,0,0,0.55)]">{job.inputFormat} → {job.outputFormat}</span>
                      </td>
                      <td className="px-4 py-3 text-[12px] text-[rgba(0,0,0,0.65)]">{job.resolution}</td>
                      <td className="px-4 py-3"><StatusBadge status={job.status} /></td>
                      <td className="px-4 py-3 w-28">
                        <div className="flex items-center gap-2">
                          <ProgressBar value={job.progress} color={job.status === 'failed' ? 'bg-red-400' : job.status === 'completed' ? 'bg-emerald-500' : job.status === 'paused' ? 'bg-amber-400' : undefined} />
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
                          {job.status === 'processing' && (
                            <button onClick={() => handlePause(job)} disabled={controllingId === job.rawId} title="Pause job"
                              className="p-1.5 rounded-lg hover:bg-amber-50 transition-colors text-[rgba(0,0,0,0.35)] hover:text-amber-600 disabled:opacity-50">
                              <Icon icon={controllingId === job.rawId ? 'mdi:loading' : 'mdi:pause'} className={`text-sm ${controllingId === job.rawId ? 'animate-spin' : ''}`} />
                            </button>
                          )}
                          {job.status === 'paused' && (
                            <button onClick={() => handleResume(job)} disabled={controllingId === job.rawId} title="Resume job"
                              className="flex items-center gap-1 px-2 py-1 rounded-lg bg-emerald-50 border border-emerald-200 hover:bg-emerald-100 transition-colors text-emerald-700 text-[11px] font-bold disabled:opacity-50">
                              <Icon icon={controllingId === job.rawId ? 'mdi:loading' : 'mdi:play'} className={`text-sm ${controllingId === job.rawId ? 'animate-spin' : ''}`} />
                              Resume
                            </button>
                          )}
                          {(job.status === 'processing' || job.status === 'paused' || job.status === 'queued') && (
                            <button onClick={() => handleStop(job)} disabled={controllingId === job.rawId} title="Stop job"
                              className="p-1.5 rounded-lg hover:bg-red-50 transition-colors text-[rgba(0,0,0,0.35)] hover:text-red-500 disabled:opacity-50">
                              <Icon icon="mdi:stop" className="text-sm" />
                            </button>
                          )}
                          {(job.status === 'failed' || job.status === 'cancelled') && (
                            <button onClick={() => handleRetry(job)} disabled={retryingId === job.rawId} title={job.status === 'cancelled' ? 'Start again' : 'Retry job'}
                              className="flex items-center gap-1 px-2 py-1 rounded-lg bg-amber-50 border border-amber-200 hover:bg-amber-100 transition-colors text-amber-700 text-[11px] font-bold disabled:opacity-50">
                              <Icon icon={retryingId === job.rawId ? 'mdi:loading' : 'mdi:refresh'} className={`text-sm ${retryingId === job.rawId ? 'animate-spin' : ''}`} />
                              {job.status === 'cancelled' ? 'Start again' : 'Retry'}
                            </button>
                          )}
                          {job.status === 'failed' && (
                            <button onClick={() => setExpandedError(expandedError === job.id ? null : job.id)} title="Show error details"
                              className="p-1.5 rounded-lg hover:bg-red-50 transition-colors text-red-400">
                              <Icon icon="mdi:alert-circle-outline" className="text-sm" />
                            </button>
                          )}
                          {job.hlsUrl && (
                            <>
                              <button onClick={() => setPlayerUrl(resolveMediaUrl(job.hlsUrl!))}
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
                            <button onClick={() => setDashPlayerUrl(resolveMediaUrl(job.dashUrl!))}
                              title="Preview DASH"
                              className="p-1.5 rounded-lg hover:bg-violet-50 transition-colors text-[rgba(0,0,0,0.35)] hover:text-violet-600">
                              <Icon icon="mdi:play-circle-outline" className="text-sm" />
                            </button>
                          )}
                          {job.videoUrl && (
                            <button onClick={() => setVideoPlayerUrl(resolveMediaUrl(job.videoUrl!))}
                              title={`Preview ${job.outputFormat}`}
                              className="p-1.5 rounded-lg hover:bg-blue-50 transition-colors text-[rgba(0,0,0,0.35)] hover:text-blue-600">
                              <Icon icon="mdi:play-circle-outline" className="text-sm" />
                            </button>
                          )}
                          {jobOutputUrl(job) && (
                            <button onClick={() => setExpandedUrl(expandedUrl === job.id ? null : job.id)}
                              title="Output URL"
                              className={`p-1.5 rounded-lg transition-colors ${expandedUrl === job.id ? 'bg-sky-50 text-sky-600' : 'hover:bg-sky-50 text-[rgba(0,0,0,0.35)] hover:text-sky-600'}`}>
                              <Icon icon="mdi:link-variant" className="text-sm" />
                            </button>
                          )}
                          {job.thumbnailPaths && job.thumbnailPaths.length > 0 && (
                            <button onClick={() => setExpandedThumbs(expandedThumbs === job.id ? null : job.id)}
                              title={`Thumbnails (${job.thumbnailPaths.length})`}
                              className={`p-1.5 rounded-lg transition-colors ${expandedThumbs === job.id ? 'bg-violet-50 text-violet-600' : 'hover:bg-violet-50 text-[rgba(0,0,0,0.35)] hover:text-violet-600'}`}>
                              <Icon icon="mdi:image-multiple-outline" className="text-sm" />
                            </button>
                          )}
                          {job.status === 'completed' && (
                            <button onClick={() => handleDownload(job)} disabled={downloadingId === job.rawId}
                              title="Download video & related files (.zip)"
                              className="p-1.5 rounded-lg hover:bg-indigo-50 transition-colors text-[rgba(0,0,0,0.35)] hover:text-indigo-600 disabled:opacity-50">
                              <Icon icon={downloadingId === job.rawId ? 'mdi:loading' : 'mdi:download'} className={`text-sm ${downloadingId === job.rawId ? 'animate-spin' : ''}`} />
                            </button>
                          )}
                          <button onClick={() => copyCmd(job)} title="Copy command"
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
                              <p className="text-[11px] font-bold text-red-700 mb-0.5">Error Details</p>
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
                    {expandedUrl === job.id && jobOutputUrl(job) && (
                      <tr className="bg-sky-50/60">
                        <td colSpan={9} className="px-4 py-3">
                          <div className="flex items-center gap-3">
                            <div className="w-7 h-7 rounded-lg bg-sky-100 flex items-center justify-center flex-shrink-0">
                              <Icon icon="mdi:link-variant" className="text-sky-600 text-sm" />
                            </div>
                            <div className="flex-1 min-w-0">
                              <p className="text-[10px] font-bold uppercase tracking-wider text-[rgba(0,0,0,0.45)] mb-1">
                                {job.hlsUrl ? 'HLS Output URL (.m3u8)' : job.dashUrl ? 'DASH Output URL (.mpd)' : `${job.outputFormat} Output URL`}
                              </p>
                              <input readOnly value={jobOutputUrl(job) ?? ''}
                                onFocus={e => e.currentTarget.select()}
                                className="w-full px-3 py-2 bg-white border border-[rgba(0,0,0,0.12)] rounded-lg text-[11px] font-mono text-[rgba(0,0,0,0.7)] outline-none focus:ring-2 focus:ring-sky-500/25 focus:border-sky-500" />
                              {(() => {
                                const c = outputCompatNote(job.outputFormat);
                                return (
                                  <p className={`text-[10px] mt-1 flex items-center gap-1 ${c.good ? 'text-emerald-600' : 'text-[rgba(0,0,0,0.45)]'}`}>
                                    <Icon icon={c.good ? 'mdi:check-circle-outline' : 'mdi:information-outline'} className="text-[11px] flex-shrink-0" />
                                    {c.text}
                                  </p>
                                );
                              })()}
                            </div>
                            <button onClick={() => copyOutputUrl(job)}
                              className="flex items-center gap-1.5 px-3 py-2 rounded-lg bg-[rgba(0,0,0,0.88)] text-white text-[11px] font-bold hover:bg-[rgba(0,0,0,0.78)] active:scale-[0.97] transition-all flex-shrink-0">
                              <Icon icon={copiedUrlId === job.id ? 'mdi:check' : 'mdi:content-copy'} className="text-sm" />
                              {copiedUrlId === job.id ? 'Copied' : 'Copy'}
                            </button>
                            <a href={jobOutputUrl(job) ?? '#'} target="_blank" rel="noreferrer"
                              className="flex items-center gap-1.5 px-3 py-2 rounded-lg bg-white border border-[rgba(0,0,0,0.12)] text-[rgba(0,0,0,0.65)] text-[11px] font-semibold hover:bg-[rgba(0,0,0,0.03)] active:scale-[0.97] transition-all flex-shrink-0">
                              <Icon icon="mdi:open-in-new" className="text-sm" />
                              Open
                            </a>
                            <button onClick={() => setExpandedUrl(null)} className="p-1 hover:bg-[rgba(0,0,0,0.06)] rounded flex-shrink-0">
                              <Icon icon="mdi:close" className="text-[rgba(0,0,0,0.4)] text-sm" />
                            </button>
                          </div>
                        </td>
                      </tr>
                    )}
                    {expandedThumbs === job.id && job.thumbnailPaths && job.thumbnailPaths.length > 0 && (
                      <tr className="bg-violet-50/40">
                        <td colSpan={9} className="px-4 py-3">
                          <div className="flex items-start gap-2">
                            <div className="flex-1 min-w-0">
                              <ThumbnailGrid thumbnailPaths={job.thumbnailPaths} jobId={job.rawId} />
                            </div>
                            <button onClick={() => setExpandedThumbs(null)} className="p-1 hover:bg-[rgba(0,0,0,0.06)] rounded flex-shrink-0">
                              <Icon icon="mdi:close" className="text-[rgba(0,0,0,0.4)] text-sm" />
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

        {/* Pagination — only worth showing once there is more than one page. */}
        {!loading && filtered.length > 0 && totalPages > 1 && (
          <div className="flex items-center justify-between gap-3 flex-wrap px-5 py-3 border-t border-[rgba(0,0,0,0.06)] bg-[rgba(0,0,0,0.012)]">
            <p className="text-[11.5px] text-[rgba(0,0,0,0.5)] tabular-nums">
              {(currentPage - 1) * jobPageSize + 1}–{Math.min(currentPage * jobPageSize, filtered.length)} of {filtered.length}
            </p>
            <div className="flex items-center gap-1">
              <button onClick={() => setJobPage(1)} disabled={currentPage === 1}
                className="p-1.5 rounded-lg hover:bg-[rgba(0,0,0,0.06)] disabled:opacity-30 disabled:hover:bg-transparent transition-colors" title="First page">
                <Icon icon="mdi:page-first" className="text-[rgba(0,0,0,0.55)] text-base" />
              </button>
              <button onClick={() => setJobPage(p => Math.max(1, p - 1))} disabled={currentPage === 1}
                className="p-1.5 rounded-lg hover:bg-[rgba(0,0,0,0.06)] disabled:opacity-30 disabled:hover:bg-transparent transition-colors" title="Previous page">
                <Icon icon="mdi:chevron-left" className="text-[rgba(0,0,0,0.55)] text-base" />
              </button>
              <span className="px-2 text-[11.5px] font-semibold text-[rgba(0,0,0,0.6)] tabular-nums">
                Page {currentPage} of {totalPages}
              </span>
              <button onClick={() => setJobPage(p => Math.min(totalPages, p + 1))} disabled={currentPage === totalPages}
                className="p-1.5 rounded-lg hover:bg-[rgba(0,0,0,0.06)] disabled:opacity-30 disabled:hover:bg-transparent transition-colors" title="Next page">
                <Icon icon="mdi:chevron-right" className="text-[rgba(0,0,0,0.55)] text-base" />
              </button>
              <button onClick={() => setJobPage(totalPages)} disabled={currentPage === totalPages}
                className="p-1.5 rounded-lg hover:bg-[rgba(0,0,0,0.06)] disabled:opacity-30 disabled:hover:bg-transparent transition-colors" title="Last page">
                <Icon icon="mdi:page-last" className="text-[rgba(0,0,0,0.55)] text-base" />
              </button>
            </div>
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
          hlsUrl={resolveMediaUrl(embedJob.hlsUrl!)}
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
    <ModalPortal>
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
                    className={`flex items-center gap-3 p-4 rounded-xl border-2 transition-all hover:border-[rgba(0,0,0,0.2)] hover:bg-[rgba(0,0,0,0.02)] ${source === src && captureState !== 'idle' ? 'border-[rgba(0,0,0,0.3)] bg-[rgba(0,0,0,0.03)]' : 'border-[rgba(0,0,0,0.1)]'}`}>
                    <div className="w-10 h-10 rounded-xl bg-[rgba(0,0,0,0.04)] flex items-center justify-center flex-shrink-0">
                      <Icon icon={src === 'screen' ? 'mdi:monitor-screenshot' : 'mdi:webcam'} width={20} color="rgba(0,0,0,0.4)" />
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
                className="flex items-center gap-2 px-6 py-2.5 bg-[rgba(0,0,0,0.88)] text-white text-[13px] font-bold rounded-xl transition-all hover:bg-[rgba(0,0,0,0.75)]">
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
    </ModalPortal>
  );
}

// ─── Embed Code Modal ─────────────────────────────────────────────────────────

function EmbedModal({ hlsUrl, title, onClose }: { hlsUrl: string; title: string; onClose: () => void }) {
  const [tab, setTab] = useState<'hlsjs' | 'native' | 'react'>('hlsjs');
  const [copied, setCopied] = useState(false);

  const fullUrl = hlsUrl.startsWith('http') ? hlsUrl : `/media/${hlsUrl.replace(/^\//, '')}`;

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
    <ModalPortal>
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
    </ModalPortal>
  );
}

// ─── Modal portal ────────────────────────────────────────────────────────────

/**
 * Renders a modal into document.body and freezes the page behind it.
 *
 * `position: fixed` is resolved against the nearest ancestor carrying a transform, not
 * the viewport — and this page animates its sections with framer-motion, which sets one.
 * A player rendered inline therefore anchored itself to that animated box: the backdrop
 * covered only part of the screen and the whole overlay slid away as the page scrolled.
 * Portalling to body puts it back on the viewport, and locking scroll stops the table
 * moving underneath a playing video.
 */
function ModalPortal({ children }: { children: React.ReactNode }) {
  useEffect(() => {
    const previous = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => { document.body.style.overflow = previous; };
  }, []);
  return createPortal(children, document.body);
}

// ─── HLS Player ──────────────────────────────────────────────────────────────

function HlsPlayer({ src, title = 'Watch Live', onClose }: { src: string; title?: string; onClose: () => void }) {
  const videoRef = useRef<HTMLVideoElement>(null);
  const hlsRef = useRef<any>(null);
  const [qualityLevels, setQualityLevels] = useState<string[]>([]);
  const [activeLevel, setActiveLevel] = useState<number>(-1); // -1 = auto
  const [showQualityMenu, setShowQualityMenu] = useState(false);

  // Parse master.m3u8 directly to detect quality variants — reliable regardless of hls.js event timing.
  useEffect(() => {
    if (!src.includes('.m3u8')) return;
    let cancelled = false;
    fetch(src)
      .then(r => r.text())
      .then(text => {
        if (cancelled) return;
        const heights: number[] = [];
        text.split('\n').forEach(line => {
          const m = line.match(/RESOLUTION=\d+x(\d+)/i);
          if (m) heights.push(parseInt(m[1], 10));
        });
        if (heights.length > 1) {
          setQualityLevels(heights.map(h => `${h}p`));
          setActiveLevel(-1);
        }
      })
      .catch(() => {/* non-fatal */});
    return () => { cancelled = true; };
  }, [src]);

  // Wire up hls.js for playback; quality switching uses hlsRef.current.currentLevel.
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
      hls = new Hls({
        lowLatencyMode: false,
        autoStartLoad: true,
        // hls.js fetches the playlist and every segment over XHR, which the fetch patch
        // in main.tsx does not intercept. Without this the playback proxy sees
        // unauthenticated requests and returns 401/403 — the whole video stays black.
        xhrSetup: (xhr: XMLHttpRequest) => applyAuthToXhr(xhr),
      });
      hlsRef.current = hls;
      hls.loadSource(src);
      hls.attachMedia(video);

      hls.on(Hls.Events.MANIFEST_PARSED, () => {
        video.play().catch(() => {});
      });

      hls.on(Hls.Events.LEVEL_SWITCHED, (_evt: any, data: any) => {
        setActiveLevel(data.level);
      });
    });

    return () => {
      if (hls) hls.destroy();
      hlsRef.current = null;
    };
  }, [src]);

  const switchQuality = (idx: number) => {
    if (hlsRef.current) hlsRef.current.currentLevel = idx;
    setActiveLevel(idx);
    setShowQualityMenu(false);
  };

  const currentQualityLabel = activeLevel === -1
    ? 'Auto'
    : (qualityLevels[activeLevel] ?? `Level ${activeLevel + 1}`);

  return (
    <ModalPortal>
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/70 backdrop-blur-sm" onClick={onClose}>
      <div className="relative w-full max-w-3xl mx-4" onClick={e => e.stopPropagation()}>
        <div className="flex items-center justify-between mb-2 px-1">
          <div className="flex items-center gap-2">
            <span className="w-2 h-2 rounded-full bg-red-500 animate-pulse" />
            <span className="text-white text-[13px] font-semibold">{title}</span>
            <code className="text-[11px] font-mono text-white/50 truncate max-w-xs">{src}</code>
          </div>
          <div className="flex items-center gap-2">
            {qualityLevels.length > 1 && (
              <div className="relative">
                <button
                  onClick={e => { e.stopPropagation(); setShowQualityMenu(v => !v); }}
                  className="flex items-center gap-1.5 px-3 py-1.5 bg-black/50 hover:bg-black/70 backdrop-blur-sm rounded-full text-[11px] font-bold text-white/80 hover:text-white transition-all border border-white/10">
                  <Icon icon="mdi:quality-high" className="text-sm text-blue-400" />
                  {currentQualityLabel}
                  <Icon icon="mdi:chevron-down" className="text-xs text-white/50" />
                </button>
                {showQualityMenu && (
                  <div className="absolute top-full right-0 mt-1 bg-[rgba(10,10,10,0.95)] border border-white/10 rounded-xl overflow-hidden shadow-2xl z-50 min-w-[110px]">
                    <button
                      onClick={() => switchQuality(-1)}
                      className={`w-full px-3 py-2 text-left text-[12px] font-semibold transition-colors flex items-center gap-2 ${activeLevel === -1 ? 'text-blue-400 bg-white/10' : 'text-white/70 hover:bg-white/05 hover:text-white'}`}>
                      <Icon icon="mdi:auto-fix" className="text-xs" />
                      Auto
                    </button>
                    {qualityLevels.map((q, i) => (
                      <button key={q}
                        onClick={() => switchQuality(i)}
                        className={`w-full px-3 py-2 text-left text-[12px] font-semibold transition-colors flex items-center gap-2 ${activeLevel === i ? 'text-blue-400 bg-white/10' : 'text-white/70 hover:bg-white/05 hover:text-white'}`}>
                        <Icon icon="mdi:quality-high" className="text-xs" />
                        {q}
                      </button>
                    ))}
                  </div>
                )}
              </div>
            )}
            <button onClick={onClose} className="p-1 rounded-lg hover:bg-white/10 transition-colors">
              <Icon icon="mdi:close" className="text-white text-lg" />
            </button>
          </div>
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
    </ModalPortal>
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
    <ModalPortal>
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
    </ModalPortal>
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
      // Same reason as the hls.js xhrSetup above: dash.js loads the manifest and every
      // segment over XHR, which the fetch patch in main.tsx never sees.
      player.extend('RequestModifier', () => ({
        modifyRequestURL: (url: string) => url,
        modifyRequestHeader: (xhr: XMLHttpRequest) => { applyAuthToXhr(xhr); return xhr; },
      }), true);
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
    <ModalPortal>
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
    </ModalPortal>
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
    <ModalPortal>
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
                <span className="w-5 h-5 rounded-full bg-[rgba(0,0,0,0.06)] text-[rgba(0,0,0,0.55)] text-[10px] font-bold flex items-center justify-center flex-shrink-0 mt-0.5">{i + 1}</span>
                <p className="text-[12px] text-[rgba(0,0,0,0.65)]">{step}</p>
              </div>
            ))}
          </div>
          {stream.hlsUrl && (
            <div className="rounded-xl bg-[rgba(0,0,0,0.02)] border border-[rgba(0,0,0,0.07)] p-3">
              <p className="text-[10px] font-bold uppercase tracking-wide text-[rgba(0,0,0,0.4)] mb-1">Viewer HLS URL (share this)</p>
              <div className="flex items-center gap-2">
                <code className="text-[11px] font-mono text-[rgba(0,0,0,0.65)] flex-1 truncate">{stream.hlsUrl}</code>
                <button onClick={() => copy(stream.hlsUrl, 'hls-obs')} className="p-1.5 rounded-lg hover:bg-[rgba(0,0,0,0.05)] transition-colors">
                  <Icon icon={copied === 'hls-obs' ? 'mdi:check' : 'mdi:content-copy'} className={`text-sm ${copied === 'hls-obs' ? 'text-emerald-500' : 'text-[rgba(0,0,0,0.35)]'}`} />
                </button>
              </div>
            </div>
          )}
        </div>
      </div>
    </div>
    </ModalPortal>
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

      {/* Title + primary action */}
      <div className="flex items-end justify-between gap-3">
        <div>
          <h2 className="text-[15px] font-bold text-[rgba(0,0,0,0.88)] leading-tight">Live Streams</h2>
          <p className="text-[11.5px] text-[rgba(0,0,0,0.4)] leading-tight mt-0.5">Broadcast RTMP &amp; HLS in real time — zero AWS IVS cost</p>
        </div>
        <button onClick={() => setShowForm(v => !v)}
          className="group flex items-center gap-2 px-4 py-2 bg-[rgba(0,0,0,0.88)] text-white text-[12px] font-bold rounded-xl transition-all duration-200 hover:bg-[rgba(0,0,0,0.78)] active:scale-[0.97] shadow-sm flex-shrink-0">
          <Icon icon="mdi:plus" className={`text-base transition-transform duration-300 ${showForm ? 'rotate-[135deg]' : 'group-hover:rotate-90'}`} />
          {showForm ? 'Close' : 'New Stream'}
        </button>
      </div>

      {/* New Stream Form */}
      <AnimatePresence>
        {showForm && (
          <motion.div initial={{ opacity: 0, y: -10, scale: 0.99 }} animate={{ opacity: 1, y: 0, scale: 1 }} exit={{ opacity: 0, y: -10, scale: 0.99 }} transition={{ duration: 0.28, ease: [0.22, 1, 0.36, 1] }}
            className="bg-white border border-[rgba(0,0,0,0.10)] rounded-2xl shadow-[0_1px_3px_rgba(0,0,0,0.04),0_12px_32px_-16px_rgba(0,0,0,0.16)] overflow-hidden">
            <div className="flex items-center gap-3 px-5 py-4 border-b border-[rgba(0,0,0,0.06)] bg-gradient-to-b from-[rgba(0,0,0,0.018)] to-transparent">
              <div className="w-9 h-9 rounded-xl bg-[rgba(0,0,0,0.88)] flex items-center justify-center flex-shrink-0 shadow-sm">
                <Icon icon="mdi:broadcast" className="text-white text-[18px]" />
              </div>
              <div>
                <p className="text-[14px] font-bold text-[rgba(0,0,0,0.88)] leading-tight">Create Live Stream</p>
                <p className="text-[11px] text-[rgba(0,0,0,0.4)] leading-tight mt-0.5">Generates an RTMP ingest endpoint + HLS playback URL</p>
              </div>
            </div>
            <div className="p-5">
              <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
                <div className="md:col-span-1">
                  <label className="block text-[11px] font-semibold text-[rgba(0,0,0,0.5)] mb-1.5 uppercase tracking-wide">Stream Name</label>
                  <input value={streamName} onChange={e => setStreamName(e.target.value)}
                    placeholder="e.g. Main Stage, Game Stream"
                    className="w-full px-3 py-2.5 border border-[rgba(0,0,0,0.12)] rounded-xl text-[13px] focus:ring-2 focus:ring-blue-500/25 focus:border-blue-500 outline-none transition-shadow" />
                </div>
                <div>
                  <label className="block text-[11px] font-semibold text-[rgba(0,0,0,0.5)] mb-1.5 uppercase tracking-wide">Resolution</label>
                  <select value={streamResolution} onChange={e => setStreamResolution(e.target.value)}
                    className="w-full px-3 py-2.5 border border-[rgba(0,0,0,0.12)] rounded-xl text-[13px] focus:ring-2 focus:ring-blue-500/25 focus:border-blue-500 outline-none bg-white transition-shadow">
                    <option value="1080p">1080p</option>
                    <option value="720p">720p</option>
                    <option value="480p">480p</option>
                  </select>
                </div>
                <div>
                  <label className="block text-[11px] font-semibold text-[rgba(0,0,0,0.5)] mb-1.5 uppercase tracking-wide">Bitrate</label>
                  <select value={streamBitrate} onChange={e => setStreamBitrate(e.target.value)}
                    className="w-full px-3 py-2.5 border border-[rgba(0,0,0,0.12)] rounded-xl text-[13px] focus:ring-2 focus:ring-blue-500/25 focus:border-blue-500 outline-none bg-white transition-shadow">
                    <option value="6000k">6000k (High)</option>
                    <option value="4500k">4500k (Medium)</option>
                    <option value="2500k">2500k (Low)</option>
                  </select>
                </div>
              </div>
              <div className="flex items-center gap-3 mt-4">
                <button onClick={handleCreate} disabled={!streamName.trim() || createLoading}
                  className="flex items-center gap-2 px-5 py-2.5 bg-[rgba(0,0,0,0.88)] text-white text-[12px] font-bold rounded-xl disabled:opacity-50 transition-all duration-200 hover:bg-[rgba(0,0,0,0.78)] active:scale-[0.97] shadow-sm">
                  {createLoading ? <Icon icon="mdi:loading" className="animate-spin" /> : <Icon icon="mdi:broadcast" />}
                  {createLoading ? 'Creating…' : 'Create Stream'}
                </button>
                <button onClick={() => { setShowForm(false); setStreamName(''); setCreateError(null); }}
                  className="px-4 py-2.5 text-[12px] font-semibold text-[rgba(0,0,0,0.5)] hover:text-[rgba(0,0,0,0.88)] transition-colors">Cancel</button>
              </div>
            </div>
          </motion.div>
        )}
      </AnimatePresence>

      {/* Stats row */}
      <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
        {[
          { label: 'Live Now', value: streams.filter(s => s.status === 'live').length, icon: 'mdi:broadcast', live: streams.some(s => s.status === 'live') },
          { label: 'Total Viewers', value: streams.reduce((a, s) => a + s.viewers, 0), icon: 'mdi:account-group-outline', live: false },
          { label: 'Bandwidth Used', value: formatBytes(totalBandwidth), icon: 'mdi:chart-line', live: false },
          { label: 'AWS IVS Saved', value: '$0 spent', icon: 'mdi:piggy-bank-outline', live: false },
        ].map((s, i) => (
          <motion.div key={i}
            initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }}
            transition={{ duration: 0.32, delay: 0.04 + i * 0.05, ease: [0.22, 1, 0.36, 1] }}
            className="bg-white border border-[rgba(0,0,0,0.10)] rounded-xl shadow-sm p-5 card-hover-lift">
            <div className="flex items-start justify-between mb-2">
              <p className="text-[12px] font-semibold uppercase tracking-[0.09em] leading-[15px] text-[rgba(0,0,0,0.88)]">{s.label}</p>
              <div className={`w-8 h-8 rounded-lg flex items-center justify-center flex-shrink-0 ${s.live ? 'bg-red-50' : 'bg-[rgba(0,0,0,0.04)]'}`}>
                <Icon icon={s.icon} width={16} className={s.live ? 'text-red-500 animate-pulse' : 'text-[rgba(0,0,0,0.4)]'} />
              </div>
            </div>
            <p style={{ fontFamily: "'Bebas Neue','Barlow Condensed',sans-serif", fontSize: '32px', fontWeight: 400, lineHeight: '32px', color: 'rgb(10,10,10)', fontVariantNumeric: 'tabular-nums' }}>{String(s.value)}</p>
          </motion.div>
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
          {streams.map((stream, si) => (
            <motion.div key={stream.id}
              initial={{ opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }}
              transition={{ duration: 0.32, delay: 0.05 + si * 0.06, ease: [0.22, 1, 0.36, 1] }}
              className={`bg-white rounded-2xl border shadow-sm overflow-hidden transition-all hover:shadow-md card-hover-lift ${
              stream.status === 'live' ? 'border-red-200' : stream.status === 'error' ? 'border-red-200' : 'border-[rgba(0,0,0,0.08)]'
            }`}>
              <div className="px-5 py-3 flex items-center justify-between border-b border-[rgba(0,0,0,0.06)]">
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
                        className="flex items-center gap-1.5 px-4 py-2 bg-[rgba(0,0,0,0.88)] text-white text-[12px] font-bold rounded-xl transition-all hover:bg-[rgba(0,0,0,0.75)]">
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
                          className="flex items-center gap-1.5 px-4 py-2 bg-[rgba(0,0,0,0.04)] border border-[rgba(0,0,0,0.08)] text-[rgba(0,0,0,0.65)] text-[12px] font-semibold rounded-xl hover:bg-[rgba(0,0,0,0.07)] transition-all">
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
                          className="flex items-center gap-1.5 px-4 py-2 bg-[rgba(0,0,0,0.88)] text-white text-[12px] font-bold rounded-xl transition-all hover:bg-[rgba(0,0,0,0.75)]">
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
            </motion.div>
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
            { icon: 'mdi:video-outline', label: 'OBS / Encoder', sub: 'Streams RTMP' },
            { icon: 'mdi:arrow-right', label: '', sub: '' },
            { icon: 'mdi:lightning-bolt', label: 'Media Worker', sub: 'Transcodes + Segments' },
            { icon: 'mdi:arrow-right', label: '', sub: '' },
            { icon: 'mdi:playlist-play', label: 'HLS Segments', sub: '.ts + .m3u8' },
            { icon: 'mdi:arrow-right', label: '', sub: '' },
            { icon: 'mdi:play-circle-outline', label: 'Any Player', sub: 'HLS URL' },
          ].map((step, i) => (
            step.label ? (
              <div key={i} className="flex items-center gap-2">
                <div className="w-10 h-10 bg-[rgba(0,0,0,0.04)] rounded-xl flex items-center justify-center">
                  <Icon icon={step.icon} width={18} color="rgba(0,0,0,0.4)" />
                </div>
                <div>
                  <p className="text-[12px] font-bold text-[rgba(0,0,0,0.7)]">{step.label}</p>
                  <p className="text-[10px] text-[rgba(0,0,0,0.4)]">{step.sub}</p>
                </div>
              </div>
            ) : (
              <Icon key={i} icon={step.icon} className="text-[rgba(0,0,0,0.2)] text-xl hidden md:block" />
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
    video: { icon: 'mdi:file-video-outline', color: 'text-[rgba(0,0,0,0.4)]', bg: 'bg-[rgba(0,0,0,0.04)]' },
    audio: { icon: 'mdi:file-music-outline', color: 'text-[rgba(0,0,0,0.4)]', bg: 'bg-[rgba(0,0,0,0.04)]' },
    image: { icon: 'mdi:file-image-outline', color: 'text-[rgba(0,0,0,0.4)]', bg: 'bg-[rgba(0,0,0,0.04)]' },
    hls: { icon: 'mdi:playlist-play', color: 'text-[rgba(0,0,0,0.4)]', bg: 'bg-[rgba(0,0,0,0.04)]' },
  };

  return (
    <div className="space-y-5">
      {/* Title */}
      <div>
        <h2 className="text-[15px] font-bold text-[rgba(0,0,0,0.88)] leading-tight">Media Storage</h2>
        <p className="text-[11.5px] text-[rgba(0,0,0,0.4)] leading-tight mt-0.5">Browse, upload &amp; manage every asset — sourced &amp; transcoded</p>
      </div>

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
          <div className="h-full bg-[rgba(0,0,0,0.7)] rounded-full transition-all duration-700" style={{ width: `${usedPct}%` }} />
        </div>
        <div className="grid grid-cols-4 gap-3 mt-4">
          {['VIDEO', 'HLS', 'AUDIO', 'IMAGE'].map((type, i) => {
            const typeFiles = files.filter(f => f.type === type.toLowerCase());
            const meta = [
              { label: 'Videos', color: 'bg-[rgba(0,0,0,0.3)]' },
              { label: 'HLS', color: 'bg-[rgba(0,0,0,0.45)]' },
              { label: 'Audio', color: 'bg-[rgba(0,0,0,0.2)]' },
              { label: 'Images', color: 'bg-[rgba(0,0,0,0.15)]' },
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
            className="w-full pl-9 pr-4 py-2.5 border border-[rgba(0,0,0,0.12)] rounded-xl text-[13px] focus:ring-2 focus:ring-blue-500/25 focus:border-blue-500 outline-none transition-shadow" />
        </div>
        <div className="flex items-center gap-2 flex-wrap">
          {(['all', 'video', 'hls', 'audio', 'image'] as const).map(t => (
            <button key={t} onClick={() => setTypeFilter(t)}
              className={`px-3 py-2 rounded-xl text-[12px] font-semibold border transition-all duration-150 active:scale-[0.96] ${
                typeFilter === t ? 'bg-[rgba(0,0,0,0.88)] text-white border-transparent shadow-sm' : 'bg-white text-[rgba(0,0,0,0.55)] border-[rgba(0,0,0,0.10)] hover:bg-[rgba(0,0,0,0.03)] hover:border-[rgba(0,0,0,0.18)]'
              }`}>{t.charAt(0).toUpperCase() + t.slice(1)}</button>
          ))}
          <label className={`flex items-center gap-1.5 px-4 py-2 rounded-xl text-[12px] font-bold border cursor-pointer transition-all duration-200 active:scale-[0.97] ${
            uploadState === 'uploading'
              ? 'bg-[rgba(0,0,0,0.04)] border-[rgba(0,0,0,0.08)] text-[rgba(0,0,0,0.45)] cursor-wait'
              : 'bg-[rgba(0,0,0,0.88)] text-white border-transparent hover:bg-[rgba(0,0,0,0.78)] shadow-sm'
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
          {filtered.map((file, fi) => {
            const meta = TYPE_ICONS[file.type] || TYPE_ICONS.video;
            return (
              <motion.div key={file.id}
                initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }}
                transition={{ duration: 0.3, delay: Math.min(fi * 0.035, 0.3), ease: [0.22, 1, 0.36, 1] }}
                className="bg-white border border-[rgba(0,0,0,0.08)] rounded-2xl p-4 hover:shadow-md transition-all cursor-pointer group card-hover-lift">
                <div className="flex items-start gap-3">
                  <div className={`w-11 h-11 ${meta.bg} rounded-xl flex items-center justify-center flex-shrink-0 group-hover:scale-105 group-hover:bg-blue-50 transition-all`}>
                    <Icon icon={meta.icon} className={`${meta.color} group-hover:text-blue-500 text-xl transition-colors`} />
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
                    <a href={file.filePath.startsWith('http') ? file.filePath : `/media/${file.filePath.replace(/.*xamops-media\//, '')}`}
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
              </motion.div>
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

  const EMERALD_NUM = { fontFamily: "'Bebas Neue','Barlow Condensed',sans-serif", fontSize: '34px', fontWeight: 400, lineHeight: '34px', color: 'rgb(5,150,105)', fontVariantNumeric: 'tabular-nums' } as React.CSSProperties;
  const DARK_NUM = { fontFamily: "'Bebas Neue','Barlow Condensed',sans-serif", fontSize: '34px', fontWeight: 400, lineHeight: '34px', color: 'rgb(10,10,10)', fontVariantNumeric: 'tabular-nums' } as React.CSSProperties;

  return (
    <div className="space-y-5">
      {/* Title */}
      <div>
        <h2 className="text-[15px] font-bold text-[rgba(0,0,0,0.88)] leading-tight">Cost Savings</h2>
        <p className="text-[11.5px] text-[rgba(0,0,0,0.4)] leading-tight mt-0.5">What AWS would charge for the same pipeline — and what you keep</p>
      </div>

      {/* Hero savings banner */}
      <motion.div initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} transition={{ duration: 0.35, ease: [0.22, 1, 0.36, 1] }}
        className="bg-white border border-[rgba(0,0,0,0.10)] rounded-2xl shadow-sm p-5">
        <div className="grid grid-cols-1 md:grid-cols-3 gap-6 divide-y md:divide-y-0 md:divide-x divide-[rgba(0,0,0,0.06)]">
          {[
            { label: 'Potential Monthly Savings', icon: 'mdi:calendar-month-outline', value: `$${totalMonthlySavings.toLocaleString()}`, sub: 'vs AWS at 100 hrs/mo', style: EMERALD_NUM, pad: 'md:pr-6' },
            { label: 'Potential Annual Savings', icon: 'mdi:calendar-star', value: `$${totalYearSavings.toLocaleString()}`, sub: 'Projected at current usage', style: EMERALD_NUM, pad: 'md:px-6 pt-4 md:pt-0' },
            { label: 'Actual Tracked Savings', icon: 'mdi:check-decagram-outline', value: actualSaved, sub: 'Recorded by MediaOps', style: DARK_NUM, pad: 'md:pl-6 pt-4 md:pt-0' },
          ].map((c, i) => (
            <div key={i} className={c.pad}>
              <div className="flex items-center gap-2 mb-2">
                <div className={`w-7 h-7 rounded-lg flex items-center justify-center ${i < 2 ? 'bg-emerald-50' : 'bg-[rgba(0,0,0,0.04)]'}`}>
                  <Icon icon={c.icon} className={`text-[15px] ${i < 2 ? 'text-emerald-500' : 'text-[rgba(0,0,0,0.4)]'}`} />
                </div>
                <p className="text-[12px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.88)]">{c.label}</p>
              </div>
              <p style={c.style}>{c.value}</p>
              <p className="text-[11px] text-[rgba(0,0,0,0.35)] mt-1">{c.sub}</p>
            </div>
          ))}
        </div>
      </motion.div>

      {/* Comparison table */}
      <div className="bg-white border border-[rgba(0,0,0,0.08)] rounded-2xl shadow-sm overflow-hidden">
        <div className="px-5 py-3.5 border-b border-[rgba(0,0,0,0.06)]">
          <p className="text-[13px] font-bold text-[rgba(0,0,0,0.88)]">AWS Media Services vs XamOps MediaOps</p>
          <p className="text-[11px] text-[rgba(0,0,0,0.4)] mt-0.5">Based on estimated monthly usage at 100 hrs transcoding + 4 live channels</p>
        </div>
        <div className="overflow-x-auto">
          <table className="w-full">
            <thead>
              <tr className="bg-[rgba(0,0,0,0.02)] border-b border-[rgba(0,0,0,0.06)]">
                {['AWS Service', 'Use Case', 'AWS Price', 'MediaOps Price', 'Monthly Savings', '12-Month Savings', 'Saving %'].map(h => (
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
                      <Icon icon="mdi:lightning-bolt" className="text-emerald-500 text-sm" />
                      <span className="text-[12px] font-mono font-semibold text-emerald-700">{row.ffmpegPrice}</span>
                    </div>
                  </td>
                  <td className="px-4 py-3"><span className="text-[13px] font-bold text-emerald-600">{row.monthlySavings}</span></td>
                  <td className="px-4 py-3"><span className="text-[13px] font-bold text-emerald-700">{row.yearSavings}</span></td>
                  <td className="px-4 py-3">
                    <div className="flex items-center gap-2">
                      <div className="flex-1 h-2 bg-[rgba(0,0,0,0.06)] rounded-full overflow-hidden w-16">
                        <div className="h-full bg-emerald-500 rounded-full" style={{ width: `${row.savingsPct}%` }} />
                      </div>
                      <span className="text-[12px] font-bold text-emerald-600">{row.savingsPct}%</span>
                    </div>
                  </td>
                </tr>
              ))}
              <tr className="bg-[rgba(0,0,0,0.02)] border-t-2 border-[rgba(0,0,0,0.08)]">
                <td colSpan={4} className="px-4 py-3 text-[12px] font-bold text-[rgba(0,0,0,0.88)]">Total (all services)</td>
                <td className="px-4 py-3 text-[14px] font-bold text-emerald-600">${totalMonthlySavings.toLocaleString()}/mo</td>
                <td className="px-4 py-3 text-[14px] font-bold text-emerald-600">${totalYearSavings.toLocaleString()}/yr</td>
                <td className="px-4 py-3 text-[14px] font-bold text-emerald-600">~98%</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>

      {/* Why MediaOps */}
      <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
        {[
          { icon: 'mdi:currency-usd-off', title: 'Zero Licensing Cost', body: 'Our media engine has zero per-minute fees, no channel fees, and no egress markup — you only pay for compute.', tint: 'bg-emerald-50', color: 'text-emerald-500' },
          { icon: 'mdi:server-network', title: 'Runs on Your Infra', body: 'Runs on any EC2, GCE, or on-prem server. Use Spot/Preemptible instances to cut compute costs further.', tint: 'bg-blue-50', color: 'text-blue-500' },
          { icon: 'mdi:cogs', title: 'Full Format Support', body: 'Supports 500+ codecs and formats including H.264, H.265/HEVC, AV1, VP9, HLS, DASH, RTMP, SRT, and more.', tint: 'bg-violet-50', color: 'text-violet-500' },
        ].map((item, i) => (
          <motion.div key={i}
            initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }}
            transition={{ duration: 0.32, delay: 0.06 + i * 0.06, ease: [0.22, 1, 0.36, 1] }}
            className="bg-white border border-[rgba(0,0,0,0.10)] rounded-xl shadow-sm p-5 card-hover-lift">
            <div className={`w-9 h-9 ${item.tint} rounded-lg flex items-center justify-center mb-3`}>
              <Icon icon={item.icon} width={18} className={item.color} />
            </div>
            <p className="text-[13px] font-bold text-[rgba(0,0,0,0.88)] mb-1">{item.title}</p>
            <p className="text-[12px] text-[rgba(0,0,0,0.5)] leading-relaxed">{item.body}</p>
          </motion.div>
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
        <span className="w-2 h-2 rounded-full bg-[rgba(0,0,0,0.3)]" />
        <span className="text-[13px] font-semibold text-[rgba(0,0,0,0.65)]">MediaOps</span>
        <span className="px-2 py-0.5 rounded-full bg-[rgba(0,0,0,0.04)] border border-[rgba(0,0,0,0.08)] text-[10px] font-bold text-[rgba(0,0,0,0.45)] uppercase tracking-wider">Beta</span>
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
      <div className={`flex items-center gap-1 mb-6 border-b ${dark ? 'border-[rgba(255,255,255,0.08)]' : 'border-[rgba(0,0,0,0.06)]'}`}>
        {TAB_DEFS.map(tab => (
          <button
            key={tab.id}
            onClick={() => navigate(tab.path)}
            className={`flex items-center gap-1.5 px-4 py-2.5 text-[13px] font-semibold whitespace-nowrap border-b-2 transition-all -mb-px ${
              activeTab === tab.id
                ? 'border-[rgba(0,0,0,0.88)] text-[rgba(0,0,0,0.88)]'
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
            <TranscodeTab jobs={jobs} loading={loadingJobs} onCreateJob={createJob} onUploadFile={uploadFile} onRetryJob={retryJob} onDownloadJob={downloadJob} onCancelJob={cancelJob} onPauseJob={pauseJob} onResumeJob={resumeJob} />
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
