import React, { useState, useEffect } from 'react';
import { Icon } from '@iconify/react';
import { useAccounts } from '../../contexts/AccountContext';

// ── Types ─────────────────────────────────────────────────────────────────────

interface S3Bucket {
  name: string;
  createdAt: string | null;
}

interface S3FileItem {
  key: string;
  name: string;
  sizeBytes: number;
  formattedSize: string;
  lastModified: string | null;
  extension: string;
  bucket: string;
  region: string;
}

// ── API helpers ───────────────────────────────────────────────────────────────

async function apiFetchBuckets(accountId: string): Promise<S3Bucket[]> {
  const res = await fetch(`/api/s3-browser/buckets?accountId=${encodeURIComponent(accountId)}`, {
    credentials: 'include',
  });
  if (!res.ok) {
    const err = await res.json().catch(() => ({}));
    throw new Error((err as any).error || `Failed to list buckets (${res.status})`);
  }
  return res.json();
}

async function apiFetchFiles(
  accountId: string,
  bucket: string,
  prefix = ''
): Promise<S3FileItem[]> {
  const params = new URLSearchParams({ accountId, bucket });
  if (prefix) params.set('prefix', prefix);
  const res = await fetch(`/api/s3-browser/files?${params}`, { credentials: 'include' });
  if (!res.ok) {
    const err = await res.json().catch(() => ({}));
    throw new Error((err as any).error || `Failed to list files (${res.status})`);
  }
  return res.json();
}

async function apiPresign(
  accountId: string,
  bucket: string,
  key: string,
  region: string,
  expiryMinutes: number
): Promise<string> {
  const res = await fetch('/api/s3-browser/presign', {
    method: 'POST',
    credentials: 'include',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ accountId, bucket, key, region, expiryMinutes }),
  });
  if (!res.ok) {
    const err = await res.json().catch(() => ({}));
    throw new Error((err as any).error || 'Failed to generate preview URL');
  }
  const data = await res.json();
  return data.url;
}

// ── Preview modal ─────────────────────────────────────────────────────────────

interface PreviewModalProps {
  file: S3FileItem;
  accountId: string;
  onClose: () => void;
  onTranscode: (file: S3FileItem, presignedUrl: string) => void;
}

function PreviewModal({ file, accountId, onClose, onTranscode }: PreviewModalProps) {
  const [url, setUrl] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    apiPresign(accountId, file.bucket, file.key, file.region, 60)
      .then(u => { setUrl(u); setLoading(false); })
      .catch(e => { setError(e.message); setLoading(false); });
  }, [accountId, file]);

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 p-4" onClick={onClose}>
      <div className="bg-white rounded-2xl shadow-2xl w-full max-w-2xl overflow-hidden" onClick={e => e.stopPropagation()}>
        <div className="flex items-center justify-between px-5 py-4 border-b border-[rgba(0,0,0,0.08)]">
          <div className="flex items-center gap-2 min-w-0">
            <Icon icon="mdi:play-circle-outline" className="text-[rgba(0,0,0,0.5)] flex-shrink-0" />
            <p className="text-[13px] font-bold text-[rgba(0,0,0,0.88)] truncate">{file.name}</p>
          </div>
          <button onClick={onClose} className="p-1.5 rounded-lg hover:bg-[rgba(0,0,0,0.05)] ml-3">
            <Icon icon="mdi:close" className="text-[rgba(0,0,0,0.4)]" />
          </button>
        </div>

        <div className="bg-black aspect-video flex items-center justify-center">
          {loading && (
            <div className="flex flex-col items-center gap-2 text-white/60">
              <Icon icon="mdi:loading" className="text-3xl animate-spin" />
              <p className="text-[12px]">Generating preview…</p>
            </div>
          )}
          {error && (
            <div className="flex flex-col items-center gap-2 text-red-400">
              <Icon icon="mdi:alert-circle-outline" className="text-3xl" />
              <p className="text-[12px] text-center px-4">{error}</p>
            </div>
          )}
          {url && !loading && (
            <video src={url} controls autoPlay className="w-full h-full object-contain">
              Your browser does not support video playback.
            </video>
          )}
        </div>

        <div className="px-5 py-4 flex items-center justify-between">
          <div className="text-[11px] text-[rgba(0,0,0,0.4)] space-x-3">
            <span>{file.formattedSize}</span>
            <span className="font-mono uppercase">{file.extension.slice(1)}</span>
            <span>{file.bucket}</span>
            {file.lastModified && <span>{new Date(file.lastModified).toLocaleDateString()}</span>}
          </div>
          {url && (
            <button
              onClick={() => onTranscode(file, url)}
              className="flex items-center gap-1.5 px-4 py-2 text-[12px] font-bold bg-[rgba(0,0,0,0.88)] text-white rounded-xl hover:bg-[rgba(0,0,0,0.75)] transition-colors"
            >
              <Icon icon="mdi:movie-open-outline" className="text-sm" />
              Transcode This
            </button>
          )}
        </div>
      </div>
    </div>
  );
}

// ── Transcode dialog ──────────────────────────────────────────────────────────

interface TranscodeDialogProps {
  file: S3FileItem;
  presignedUrl: string;
  onClose: () => void;
  onSubmit: (sourceUrl: string, opts: { outputFormat: string; resolution: string; enableAdaptive: boolean }) => Promise<void>;
}

function TranscodeDialog({ file, presignedUrl, onClose, onSubmit }: TranscodeDialogProps) {
  const [outputFormat, setOutputFormat] = useState('HLS');
  const [resolution, setResolution] = useState('1080p');
  const [enableAdaptive, setEnableAdaptive] = useState(false);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setLoading(true);
    setError(null);
    try {
      await onSubmit(presignedUrl, { outputFormat, resolution, enableAdaptive });
      onClose();
    } catch (err: any) {
      setError(err.message);
      setLoading(false);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 p-4" onClick={onClose}>
      <div className="bg-white rounded-2xl shadow-2xl w-full max-w-md overflow-hidden" onClick={e => e.stopPropagation()}>
        <div className="flex items-center justify-between px-5 py-4 border-b border-[rgba(0,0,0,0.08)]">
          <div className="flex items-center gap-2">
            <Icon icon="mdi:movie-open-outline" className="text-[rgba(0,0,0,0.5)]" />
            <p className="text-[13px] font-bold text-[rgba(0,0,0,0.88)]">Transcode from S3</p>
          </div>
          <button onClick={onClose} className="p-1.5 rounded-lg hover:bg-[rgba(0,0,0,0.05)]">
            <Icon icon="mdi:close" className="text-[rgba(0,0,0,0.4)]" />
          </button>
        </div>

        <form onSubmit={handleSubmit} className="p-5 space-y-4">
          <div className="bg-[rgba(0,0,0,0.03)] rounded-xl px-3 py-2">
            <p className="text-[10px] font-semibold text-[rgba(0,0,0,0.4)] uppercase tracking-wide mb-0.5">Source</p>
            <p className="text-[12px] font-semibold text-[rgba(0,0,0,0.75)] truncate">{file.name}</p>
            <p className="text-[10px] text-[rgba(0,0,0,0.35)]">{file.bucket} · {file.formattedSize}</p>
          </div>

          <div className="grid grid-cols-2 gap-3">
            <div>
              <label className="block text-[11px] font-semibold text-[rgba(0,0,0,0.55)] uppercase tracking-wide mb-1">Output Format</label>
              <select value={outputFormat} onChange={e => setOutputFormat(e.target.value)}
                className="w-full px-3 py-2 text-[13px] border border-[rgba(0,0,0,0.12)] rounded-xl bg-white focus:outline-none focus:ring-2 focus:ring-[rgba(0,0,0,0.12)]">
                {['HLS', 'DASH', 'MP4', 'WEBM', 'MKV'].map(f => <option key={f} value={f}>{f}</option>)}
              </select>
            </div>
            <div>
              <label className="block text-[11px] font-semibold text-[rgba(0,0,0,0.55)] uppercase tracking-wide mb-1">Resolution</label>
              <select value={resolution} onChange={e => setResolution(e.target.value)}
                className="w-full px-3 py-2 text-[13px] border border-[rgba(0,0,0,0.12)] rounded-xl bg-white focus:outline-none focus:ring-2 focus:ring-[rgba(0,0,0,0.12)]">
                {['4K', '1440p', '1080p', '720p', '480p', '360p'].map(r => <option key={r} value={r}>{r}</option>)}
              </select>
            </div>
          </div>

          {(outputFormat === 'HLS' || outputFormat === 'DASH') && (
            <label className="flex items-center gap-2 cursor-pointer select-none">
              <div onClick={() => setEnableAdaptive(a => !a)}
                className={`w-9 h-5 rounded-full transition-colors relative flex-shrink-0 ${enableAdaptive ? 'bg-[rgba(0,0,0,0.88)]' : 'bg-[rgba(0,0,0,0.15)]'}`}>
                <div className={`absolute top-0.5 w-4 h-4 rounded-full bg-white shadow transition-transform ${enableAdaptive ? 'translate-x-4' : 'translate-x-0.5'}`} />
              </div>
              <span className="text-[12px] font-semibold text-[rgba(0,0,0,0.7)]">Adaptive streaming (multi-bitrate)</span>
            </label>
          )}

          {error && (
            <div className="bg-red-50 border border-red-200 rounded-xl px-3 py-2 flex items-center gap-2">
              <Icon icon="mdi:alert-circle-outline" className="text-red-500 text-sm flex-shrink-0" />
              <p className="text-[12px] text-red-700">{error}</p>
            </div>
          )}

          <div className="flex items-center gap-2 pt-1">
            <button type="button" onClick={onClose}
              className="flex-1 px-4 py-2 text-[13px] font-semibold border border-[rgba(0,0,0,0.12)] rounded-xl hover:bg-[rgba(0,0,0,0.03)] transition-colors">
              Cancel
            </button>
            <button type="submit" disabled={loading}
              className="flex-1 px-4 py-2 text-[13px] font-bold bg-[rgba(0,0,0,0.88)] text-white rounded-xl hover:bg-[rgba(0,0,0,0.75)] transition-colors disabled:opacity-60 flex items-center justify-center gap-2">
              {loading && <Icon icon="mdi:loading" className="animate-spin text-sm" />}
              {loading ? 'Queuing…' : 'Start Transcode'}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}

// ── File card ─────────────────────────────────────────────────────────────────

const EXT_ICONS: Record<string, { icon: string; color: string; bg: string }> = {
  '.mp4':  { icon: 'mdi:file-video', color: 'text-blue-500',   bg: 'bg-blue-50' },
  '.mov':  { icon: 'mdi:file-video', color: 'text-purple-500', bg: 'bg-purple-50' },
  '.mkv':  { icon: 'mdi:file-video', color: 'text-orange-500', bg: 'bg-orange-50' },
  '.avi':  { icon: 'mdi:file-video', color: 'text-yellow-600', bg: 'bg-yellow-50' },
  '.webm': { icon: 'mdi:file-video', color: 'text-green-500',  bg: 'bg-green-50' },
};
const DEFAULT_ICON = { icon: 'mdi:file-video-outline', color: 'text-[rgba(0,0,0,0.35)]', bg: 'bg-[rgba(0,0,0,0.04)]' };

function FileCard({ file, onPreview, onTranscode }: {
  file: S3FileItem;
  onPreview: (f: S3FileItem) => void;
  onTranscode: (f: S3FileItem) => void;
}) {
  const meta = EXT_ICONS[file.extension] || DEFAULT_ICON;
  return (
    <div className="bg-white border border-[rgba(0,0,0,0.08)] rounded-2xl p-4 hover:shadow-md transition-all group">
      <div className="flex items-start gap-3">
        <div className={`w-11 h-11 ${meta.bg} rounded-xl flex items-center justify-center flex-shrink-0 group-hover:scale-105 transition-transform`}>
          <Icon icon={meta.icon} className={`${meta.color} text-xl`} />
        </div>
        <div className="flex-1 min-w-0">
          <p className="text-[13px] font-bold text-[rgba(0,0,0,0.88)] truncate" title={file.key}>{file.name}</p>
          <p className="text-[11px] text-[rgba(0,0,0,0.4)]">
            {file.formattedSize} · {file.extension.slice(1).toUpperCase()}
            {file.lastModified ? ` · ${new Date(file.lastModified).toLocaleDateString()}` : ''}
          </p>
          <p className="text-[10px] text-[rgba(0,0,0,0.25)] font-mono truncate mt-0.5" title={file.key}>{file.key}</p>
        </div>
      </div>
      <div className="flex items-center gap-2 mt-3 pt-3 border-t border-[rgba(0,0,0,0.05)]">
        <button onClick={() => onPreview(file)}
          className="flex items-center gap-1.5 px-3 py-1.5 text-[11px] font-semibold border border-[rgba(0,0,0,0.12)] rounded-lg hover:bg-[rgba(0,0,0,0.03)] transition-colors">
          <Icon icon="mdi:play-circle-outline" className="text-sm" /> Preview
        </button>
        <button onClick={() => onTranscode(file)}
          className="flex items-center gap-1.5 px-3 py-1.5 text-[11px] font-semibold bg-[rgba(0,0,0,0.88)] text-white rounded-lg hover:bg-[rgba(0,0,0,0.75)] transition-colors">
          <Icon icon="mdi:movie-open-outline" className="text-sm" /> Transcode
        </button>
      </div>
    </div>
  );
}

// ── Main S3 Import Tab ────────────────────────────────────────────────────────

interface S3ImportTabProps {
  onCreateJob: (req: any) => Promise<any>;
}

export function S3ImportTab({ onCreateJob }: S3ImportTabProps) {
  const { accounts, selectedAccountIds } = useAccounts();

  // Only AWS accounts that are connected
  const awsAccounts = accounts.filter(a => a.provider === 'AWS' && a.status === 'CONNECTED');

  // Pick the first selected AWS account, or fallback to first connected AWS account
  const defaultAccountId = selectedAccountIds.find(id => awsAccounts.some(a => a.id === id))
    ?? awsAccounts[0]?.id ?? null;

  const [activeAccountId, setActiveAccountId] = useState<string | null>(defaultAccountId);
  const [buckets, setBuckets] = useState<S3Bucket[]>([]);
  const [loadingBuckets, setLoadingBuckets] = useState(false);
  const [bucketsError, setBucketsError] = useState<string | null>(null);
  const [selectedBucket, setSelectedBucket] = useState<string | null>(null);

  const [files, setFiles] = useState<S3FileItem[]>([]);
  const [loadingFiles, setLoadingFiles] = useState(false);
  const [filesError, setFilesError] = useState<string | null>(null);
  const [search, setSearch] = useState('');

  const [previewFile, setPreviewFile] = useState<S3FileItem | null>(null);
  const [transcodeFile, setTranscodeFile] = useState<S3FileItem | null>(null);
  const [transcodePresignedUrl, setTranscodePresignedUrl] = useState('');
  const [presigning, setPresigning] = useState(false);
  const [successMsg, setSuccessMsg] = useState<string | null>(null);

  // Load buckets when account changes
  useEffect(() => {
    if (!activeAccountId) return;
    setLoadingBuckets(true);
    setBucketsError(null);
    setBuckets([]);
    setSelectedBucket(null);
    setFiles([]);
    apiFetchBuckets(activeAccountId)
      .then(data => { setBuckets(data); setLoadingBuckets(false); })
      .catch(e => { setBucketsError(e.message); setLoadingBuckets(false); });
  }, [activeAccountId]);

  // Load files when bucket changes
  useEffect(() => {
    if (!activeAccountId || !selectedBucket) { setFiles([]); return; }
    setLoadingFiles(true);
    setFilesError(null);
    apiFetchFiles(activeAccountId, selectedBucket)
      .then(data => { setFiles(data); setLoadingFiles(false); })
      .catch(e => { setFilesError(e.message); setLoadingFiles(false); });
  }, [activeAccountId, selectedBucket]);

  const handleTranscodeClick = async (file: S3FileItem) => {
    if (!activeAccountId) return;
    setPresigning(true);
    try {
      const url = await apiPresign(activeAccountId, file.bucket, file.key, file.region, 720);
      setTranscodeFile(file);
      setTranscodePresignedUrl(url);
    } catch {
      // will show error in next step
    } finally {
      setPresigning(false);
    }
  };

  const handleSubmitTranscode = async (
    sourceUrl: string,
    opts: { outputFormat: string; resolution: string; enableAdaptive: boolean }
  ) => {
    // Send where the object lives alongside the presigned URL. The URL expires, so the
    // backend re-signs from these when the job actually starts — otherwise a job that
    // queues behind others or gets retried later fails with a 403 it can never recover from.
    await onCreateJob({
      sourceUrl,
      ...(transcodeFile ? {
        sourceKey: transcodeFile.key,
        sourceBucket: transcodeFile.bucket,
        sourceRegion: transcodeFile.region,
        sourceAccountId: activeAccountId ?? undefined,
      } : {}),
      ...opts,
    });
    setSuccessMsg('Job queued! Check the Transcode tab for progress.');
    setTimeout(() => setSuccessMsg(null), 5000);
  };

  const filtered = files.filter(f => f.name.toLowerCase().includes(search.toLowerCase()));
  const activeAccount = awsAccounts.find(a => a.id === activeAccountId);

  // No AWS accounts connected
  if (awsAccounts.length === 0) {
    return (
      <div className="bg-white border border-[rgba(0,0,0,0.08)] rounded-2xl py-20 text-center">
        <Icon icon="mdi:aws" className="text-5xl text-[rgba(0,0,0,0.12)] mx-auto mb-4" />
        <p className="text-[14px] font-bold text-[rgba(0,0,0,0.5)]">No AWS accounts connected</p>
        <p className="text-[12px] text-[rgba(0,0,0,0.35)] mt-1">
          Connect an AWS account from <span className="font-semibold">Settings → Add Account</span> to browse S3 videos.
        </p>
      </div>
    );
  }

  return (
    <div className="space-y-5">
      {successMsg && (
        <div className="bg-emerald-50 border border-emerald-200 rounded-xl px-4 py-3 flex items-center gap-2">
          <Icon icon="mdi:check-circle-outline" className="text-emerald-500 flex-shrink-0" />
          <p className="text-[12px] text-emerald-800 font-semibold">{successMsg}</p>
        </div>
      )}

      {/* Account + Bucket selector */}
      <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-5">
        <p className="text-[12px] font-bold text-[rgba(0,0,0,0.88)] uppercase tracking-wide mb-4">Browse S3 Videos</p>

        <div className="flex flex-wrap gap-3 items-end">
          {/* Account picker */}
          <div className="flex-1 min-w-[200px]">
            <label className="block text-[11px] font-semibold text-[rgba(0,0,0,0.45)] uppercase tracking-wide mb-1">AWS Account</label>
            <select
              value={activeAccountId ?? ''}
              onChange={e => setActiveAccountId(e.target.value || null)}
              className="w-full px-3 py-2 text-[13px] border border-[rgba(0,0,0,0.12)] rounded-xl bg-white focus:outline-none focus:ring-2 focus:ring-[rgba(0,0,0,0.1)]"
            >
              {awsAccounts.map(a => (
                <option key={a.id} value={a.id}>{a.name} ({a.id})</option>
              ))}
            </select>
          </div>

          {/* Bucket picker */}
          <div className="flex-1 min-w-[200px]">
            <label className="block text-[11px] font-semibold text-[rgba(0,0,0,0.45)] uppercase tracking-wide mb-1">
              S3 Bucket
              {loadingBuckets && <Icon icon="mdi:loading" className="inline ml-1 animate-spin text-[10px]" />}
            </label>
            <select
              value={selectedBucket ?? ''}
              onChange={e => setSelectedBucket(e.target.value || null)}
              disabled={loadingBuckets || buckets.length === 0}
              className="w-full px-3 py-2 text-[13px] border border-[rgba(0,0,0,0.12)] rounded-xl bg-white focus:outline-none focus:ring-2 focus:ring-[rgba(0,0,0,0.1)] disabled:opacity-60"
            >
              <option value="">— Select a bucket —</option>
              {buckets.map(b => <option key={b.name} value={b.name}>{b.name}</option>)}
            </select>
          </div>
        </div>

        {bucketsError && (
          <div className="mt-3 bg-red-50 border border-red-200 rounded-xl px-3 py-2 flex items-center gap-2">
            <Icon icon="mdi:alert-circle-outline" className="text-red-500 text-sm flex-shrink-0" />
            <p className="text-[12px] text-red-700">{bucketsError}</p>
          </div>
        )}
      </div>

      {/* File browser */}
      {selectedBucket && (
        <div className="space-y-4">
          <div className="flex items-center gap-3 flex-wrap">
            <div className="flex items-center gap-2">
              <Icon icon="mdi:bucket" className="text-[rgba(0,0,0,0.4)]" />
              <p className="text-[13px] font-bold text-[rgba(0,0,0,0.75)]">{selectedBucket}</p>
              {activeAccount && (
                <span className="text-[11px] text-[rgba(0,0,0,0.35)]">· {activeAccount.name}</span>
              )}
            </div>
            <div className="ml-auto flex items-center gap-2">
              <div className="relative">
                <Icon icon="mdi:magnify" className="absolute left-2.5 top-1/2 -translate-y-1/2 text-[rgba(0,0,0,0.3)] text-sm" />
                <input
                  type="text"
                  placeholder="Search videos…"
                  value={search}
                  onChange={e => setSearch(e.target.value)}
                  className="pl-8 pr-3 py-1.5 text-[12px] border border-[rgba(0,0,0,0.1)] rounded-xl focus:outline-none w-48"
                />
              </div>
              <button
                onClick={() => {
                  if (!activeAccountId || !selectedBucket) return;
                  setLoadingFiles(true);
                  setFilesError(null);
                  apiFetchFiles(activeAccountId, selectedBucket)
                    .then(data => { setFiles(data); setLoadingFiles(false); })
                    .catch(e => { setFilesError(e.message); setLoadingFiles(false); });
                }}
                className="p-2 rounded-xl border border-[rgba(0,0,0,0.1)] hover:bg-[rgba(0,0,0,0.03)]"
                title="Refresh"
              >
                <Icon icon="mdi:refresh" className={`text-sm text-[rgba(0,0,0,0.4)] ${loadingFiles ? 'animate-spin' : ''}`} />
              </button>
            </div>
          </div>

          {filesError && (
            <div className="bg-red-50 border border-red-200 rounded-xl px-4 py-3 flex items-center gap-2">
              <Icon icon="mdi:alert-circle-outline" className="text-red-500 flex-shrink-0" />
              <p className="text-[12px] text-red-700">{filesError}</p>
            </div>
          )}

          {loadingFiles ? (
            <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-4">
              {Array.from({ length: 6 }).map((_, i) => (
                <div key={i} className="bg-[rgba(0,0,0,0.05)] rounded-2xl h-28 animate-pulse" />
              ))}
            </div>
          ) : filtered.length === 0 && !filesError ? (
            <div className="bg-white border border-[rgba(0,0,0,0.08)] rounded-2xl py-16 text-center">
              <Icon icon="mdi:video-off-outline" className="text-4xl text-[rgba(0,0,0,0.12)] mx-auto mb-3" />
              <p className="text-[13px] font-semibold text-[rgba(0,0,0,0.4)]">
                {search ? 'No videos match your search' : 'No video files found in this bucket'}
              </p>
              <p className="text-[11px] text-[rgba(0,0,0,0.3)] mt-1">
                Supported: MP4, MOV, MKV, AVI, WebM, M4V, FLV, WMV, TS and more
              </p>
            </div>
          ) : (
            <>
              <p className="text-[11px] text-[rgba(0,0,0,0.35)]">
                {filtered.length} video{filtered.length !== 1 ? 's' : ''} found
              </p>
              <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-4">
                {filtered.map(file => (
                  <FileCard
                    key={file.key}
                    file={file}
                    onPreview={setPreviewFile}
                    onTranscode={handleTranscodeClick}
                  />
                ))}
              </div>
            </>
          )}
        </div>
      )}

      {/* Modals */}
      {previewFile && activeAccountId && (
        <PreviewModal
          file={previewFile}
          accountId={activeAccountId}
          onClose={() => setPreviewFile(null)}
          onTranscode={(file, url) => {
            setPreviewFile(null);
            setTranscodeFile(file);
            setTranscodePresignedUrl(url);
          }}
        />
      )}

      {transcodeFile && !presigning && (
        <TranscodeDialog
          file={transcodeFile}
          presignedUrl={transcodePresignedUrl}
          onClose={() => { setTranscodeFile(null); setTranscodePresignedUrl(''); }}
          onSubmit={handleSubmitTranscode}
        />
      )}

      {presigning && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40">
          <div className="bg-white rounded-2xl px-6 py-5 flex items-center gap-3 shadow-xl">
            <Icon icon="mdi:loading" className="animate-spin text-xl text-[rgba(0,0,0,0.5)]" />
            <p className="text-[13px] font-semibold text-[rgba(0,0,0,0.7)]">Preparing transcode…</p>
          </div>
        </div>
      )}
    </div>
  );
}
