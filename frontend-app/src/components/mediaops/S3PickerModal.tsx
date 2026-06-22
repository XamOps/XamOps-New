import { useState, useEffect } from 'react';
import { Icon } from '@iconify/react';
import { useAccounts } from '../../contexts/AccountContext';

interface S3Bucket { name: string; createdAt: string | null; }

interface S3FileItem {
  key: string; name: string; sizeBytes: number; formattedSize: string;
  lastModified: string | null; extension: string; bucket: string; region: string;
}

async function apiFetchBuckets(accountId: string): Promise<S3Bucket[]> {
  const res = await fetch(`/api/s3-browser/buckets?accountId=${encodeURIComponent(accountId)}`, { credentials: 'include' });
  if (!res.ok) { const e = await res.json().catch(() => ({})); throw new Error((e as any).error || `HTTP ${res.status}`); }
  return res.json();
}

async function apiFetchFiles(accountId: string, bucket: string, prefix = ''): Promise<S3FileItem[]> {
  const params = new URLSearchParams({ accountId, bucket });
  if (prefix) params.set('prefix', prefix);
  const res = await fetch(`/api/s3-browser/files?${params}`, { credentials: 'include' });
  if (!res.ok) { const e = await res.json().catch(() => ({})); throw new Error((e as any).error || `HTTP ${res.status}`); }
  return res.json();
}

async function apiPresign(accountId: string, bucket: string, key: string, region: string, expiryMinutes: number): Promise<string> {
  const res = await fetch('/api/s3-browser/presign', {
    method: 'POST', credentials: 'include',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ accountId, bucket, key, region, expiryMinutes }),
  });
  if (!res.ok) { const e = await res.json().catch(() => ({})); throw new Error((e as any).error || 'Presign failed'); }
  return (await res.json()).url;
}

export interface S3PickedFile {
  name: string;
  formattedSize: string;
  bucket: string;
}

interface S3PickerModalProps {
  onSelect: (url: string, file: S3PickedFile) => void;
  onClose: () => void;
}

export function S3PickerModal({ onSelect, onClose }: S3PickerModalProps) {
  const { accounts, selectedAccountIds } = useAccounts();
  const awsAccounts = accounts.filter(a => a.provider === 'AWS' && a.status === 'CONNECTED');
  const defaultAccountId = selectedAccountIds.find(id => awsAccounts.some(a => a.id === id)) ?? awsAccounts[0]?.id ?? null;

  const [activeAccountId, setActiveAccountId] = useState<string | null>(defaultAccountId);
  const [buckets, setBuckets] = useState<S3Bucket[]>([]);
  const [loadingBuckets, setLoadingBuckets] = useState(false);
  const [bucketsError, setBucketsError] = useState<string | null>(null);
  const [selectedBucket, setSelectedBucket] = useState<string | null>(null);
  const [files, setFiles] = useState<S3FileItem[]>([]);
  const [loadingFiles, setLoadingFiles] = useState(false);
  const [filesError, setFilesError] = useState<string | null>(null);
  const [search, setSearch] = useState('');
  const [selectingKey, setSelectingKey] = useState<string | null>(null);
  const [selectError, setSelectError] = useState<string | null>(null);

  useEffect(() => {
    if (!activeAccountId) return;
    setLoadingBuckets(true); setBucketsError(null); setBuckets([]); setSelectedBucket(null); setFiles([]);
    apiFetchBuckets(activeAccountId)
      .then(data => { setBuckets(data); setLoadingBuckets(false); })
      .catch(e => { setBucketsError(e.message); setLoadingBuckets(false); });
  }, [activeAccountId]);

  useEffect(() => {
    if (!activeAccountId || !selectedBucket) { setFiles([]); return; }
    setLoadingFiles(true); setFilesError(null);
    apiFetchFiles(activeAccountId, selectedBucket)
      .then(data => { setFiles(data); setLoadingFiles(false); })
      .catch(e => { setFilesError(e.message); setLoadingFiles(false); });
  }, [activeAccountId, selectedBucket]);

  const handleSelect = async (file: S3FileItem) => {
    if (!activeAccountId) return;
    setSelectingKey(file.key); setSelectError(null);
    try {
      const url = await apiPresign(activeAccountId, file.bucket, file.key, file.region, 720);
      onSelect(url, { name: file.name, formattedSize: file.formattedSize, bucket: file.bucket });
    } catch (e: any) {
      setSelectError(e.message);
      setSelectingKey(null);
    }
  };

  const filtered = files.filter(f => f.name.toLowerCase().includes(search.toLowerCase()));

  if (awsAccounts.length === 0) {
    return (
      <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 p-4" onClick={onClose}>
        <div className="bg-white rounded-2xl shadow-2xl w-full max-w-sm p-8 text-center" onClick={e => e.stopPropagation()}>
          <Icon icon="mdi:aws" className="text-5xl text-[rgba(0,0,0,0.12)] mx-auto mb-4" />
          <p className="text-[14px] font-bold text-[rgba(0,0,0,0.5)]">No AWS accounts connected</p>
          <p className="text-[12px] text-[rgba(0,0,0,0.35)] mt-1">Connect an AWS account from Settings → Add Account.</p>
          <button onClick={onClose} className="mt-5 px-5 py-2 text-[12px] font-semibold border border-[rgba(0,0,0,0.12)] rounded-xl hover:bg-[rgba(0,0,0,0.03)]">Close</button>
        </div>
      </div>
    );
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 p-4" onClick={onClose}>
      <div className="bg-white rounded-2xl shadow-2xl w-full max-w-3xl max-h-[85vh] flex flex-col overflow-hidden" onClick={e => e.stopPropagation()}>

        {/* Header */}
        <div className="flex items-center justify-between px-5 py-4 border-b border-[rgba(0,0,0,0.08)] flex-shrink-0">
          <div className="flex items-center gap-2">
            <Icon icon="mdi:bucket-outline" className="text-[rgba(0,0,0,0.4)]" />
            <p className="text-[13px] font-bold text-[rgba(0,0,0,0.88)]">Browse S3 — Select Source Video</p>
          </div>
          <button onClick={onClose} className="p-1.5 rounded-lg hover:bg-[rgba(0,0,0,0.05)]">
            <Icon icon="mdi:close" className="text-[rgba(0,0,0,0.4)]" />
          </button>
        </div>

        {/* Selectors */}
        <div className="px-5 py-3 border-b border-[rgba(0,0,0,0.06)] flex flex-wrap gap-3 items-end flex-shrink-0">
          <div className="flex-1 min-w-[180px]">
            <label className="block text-[10px] font-semibold text-[rgba(0,0,0,0.45)] uppercase tracking-wide mb-1">AWS Account</label>
            <select value={activeAccountId ?? ''} onChange={e => setActiveAccountId(e.target.value || null)}
              className="w-full px-3 py-1.5 text-[12px] border border-[rgba(0,0,0,0.12)] rounded-lg bg-white focus:outline-none">
              {awsAccounts.map(a => <option key={a.id} value={a.id}>{a.name} ({a.id})</option>)}
            </select>
          </div>
          <div className="flex-1 min-w-[180px]">
            <label className="block text-[10px] font-semibold text-[rgba(0,0,0,0.45)] uppercase tracking-wide mb-1">
              S3 Bucket {loadingBuckets && <Icon icon="mdi:loading" className="inline ml-1 animate-spin text-[10px]" />}
            </label>
            <select value={selectedBucket ?? ''} onChange={e => setSelectedBucket(e.target.value || null)}
              disabled={loadingBuckets || buckets.length === 0}
              className="w-full px-3 py-1.5 text-[12px] border border-[rgba(0,0,0,0.12)] rounded-lg bg-white focus:outline-none disabled:opacity-60">
              <option value="">— Select a bucket —</option>
              {buckets.map(b => <option key={b.name} value={b.name}>{b.name}</option>)}
            </select>
          </div>
          {selectedBucket && (
            <div className="relative">
              <Icon icon="mdi:magnify" className="absolute left-2.5 top-1/2 -translate-y-1/2 text-[rgba(0,0,0,0.3)] text-sm" />
              <input type="text" placeholder="Search…" value={search} onChange={e => setSearch(e.target.value)}
                className="pl-8 pr-3 py-1.5 text-[12px] border border-[rgba(0,0,0,0.12)] rounded-lg focus:outline-none w-36" />
            </div>
          )}
        </div>

        {/* Error banners */}
        {(bucketsError || selectError) && (
          <div className="px-5 py-2 bg-red-50 border-b border-red-100 flex items-center gap-2 flex-shrink-0">
            <Icon icon="mdi:alert-circle-outline" className="text-red-500 text-sm flex-shrink-0" />
            <p className="text-[11px] text-red-700">{bucketsError || selectError}</p>
          </div>
        )}

        {/* File list */}
        <div className="flex-1 overflow-y-auto p-5">
          {!selectedBucket ? (
            <div className="flex flex-col items-center justify-center py-12 text-center">
              <Icon icon="mdi:bucket-outline" className="text-4xl text-[rgba(0,0,0,0.1)] mb-3" />
              <p className="text-[12px] text-[rgba(0,0,0,0.35)]">Select a bucket to browse videos</p>
            </div>
          ) : loadingFiles ? (
            <div className="grid grid-cols-2 gap-3">
              {Array.from({ length: 6 }).map((_, i) => <div key={i} className="h-16 bg-[rgba(0,0,0,0.05)] rounded-xl animate-pulse" />)}
            </div>
          ) : filesError ? (
            <div className="flex flex-col items-center justify-center py-12 text-center">
              <Icon icon="mdi:alert-circle-outline" className="text-3xl text-red-300 mb-2" />
              <p className="text-[12px] text-red-600">{filesError}</p>
            </div>
          ) : filtered.length === 0 ? (
            <div className="flex flex-col items-center justify-center py-12 text-center">
              <Icon icon="mdi:video-off-outline" className="text-4xl text-[rgba(0,0,0,0.1)] mb-3" />
              <p className="text-[12px] text-[rgba(0,0,0,0.35)]">{search ? 'No videos match your search' : 'No video files found in this bucket'}</p>
              <p className="text-[10px] text-[rgba(0,0,0,0.25)] mt-1">Supported: MP4, MOV, MKV, AVI, WebM, TS and more</p>
            </div>
          ) : (
            <>
              <p className="text-[10px] text-[rgba(0,0,0,0.35)] mb-3">{filtered.length} video{filtered.length !== 1 ? 's' : ''}</p>
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-2.5">
                {filtered.map(file => (
                  <div key={file.key}
                    className="flex items-center gap-3 bg-[rgba(0,0,0,0.02)] border border-[rgba(0,0,0,0.07)] rounded-xl px-3 py-2.5 hover:border-[rgba(0,0,0,0.15)] transition-colors">
                    <div className="w-9 h-9 bg-[rgba(0,0,0,0.04)] rounded-lg flex items-center justify-center flex-shrink-0">
                      <Icon icon="mdi:file-video-outline" className="text-[rgba(0,0,0,0.35)] text-lg" />
                    </div>
                    <div className="flex-1 min-w-0">
                      <p className="text-[12px] font-semibold text-[rgba(0,0,0,0.88)] truncate" title={file.name}>{file.name}</p>
                      <p className="text-[10px] text-[rgba(0,0,0,0.4)]">
                        {file.formattedSize} · {file.extension.slice(1).toUpperCase()}
                        {file.lastModified ? ` · ${new Date(file.lastModified).toLocaleDateString()}` : ''}
                      </p>
                    </div>
                    <button onClick={() => handleSelect(file)}
                      disabled={selectingKey !== null}
                      className="flex items-center gap-1 px-3 py-1.5 text-[11px] font-bold bg-[rgba(0,0,0,0.88)] text-white rounded-lg hover:bg-[rgba(0,0,0,0.7)] transition-colors disabled:opacity-50 flex-shrink-0">
                      {selectingKey === file.key
                        ? <><Icon icon="mdi:loading" className="animate-spin text-sm" /> Loading…</>
                        : <><Icon icon="mdi:check" className="text-sm" /> Select</>}
                    </button>
                  </div>
                ))}
              </div>
            </>
          )}
        </div>
      </div>
    </div>
  );
}
