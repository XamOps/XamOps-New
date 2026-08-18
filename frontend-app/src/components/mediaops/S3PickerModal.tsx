import { useState, useEffect } from 'react';
import { Icon } from '@iconify/react';
import { useAccounts } from '../../contexts/AccountContext';

interface S3Bucket { name: string; createdAt: string | null; }

interface S3FileItem {
  key: string; name: string; sizeBytes: number; formattedSize: string;
  lastModified: string | null; extension: string; bucket: string; region: string;
}

interface S3Folder { name: string; prefix: string; }

interface S3BrowseResult {
  bucket: string; prefix: string; region: string;
  folders: S3Folder[]; files: S3FileItem[];
}

async function apiFetchBuckets(accountId: string): Promise<S3Bucket[]> {
  const res = await fetch(`/api/s3-browser/buckets?accountId=${encodeURIComponent(accountId)}`, { credentials: 'include' });
  if (!res.ok) { const e = await res.json().catch(() => ({})); throw new Error((e as any).error || `HTTP ${res.status}`); }
  return res.json();
}

/** One level of the bucket — the folders and videos directly under `prefix`. */
async function apiBrowse(accountId: string, bucket: string, prefix = ''): Promise<S3BrowseResult> {
  const params = new URLSearchParams({ accountId, bucket });
  if (prefix) params.set('prefix', prefix);
  const res = await fetch(`/api/s3-browser/browse?${params}`, { credentials: 'include' });
  if (!res.ok) { const e = await res.json().catch(() => ({})); throw new Error((e as any).error || `HTTP ${res.status}`); }
  return res.json();
}

/** Recursive listing, used only for search — folders are flattened away here on purpose. */
async function apiSearchFiles(accountId: string, bucket: string, prefix = ''): Promise<S3FileItem[]> {
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
  /** Full key, e.g. "DRSAKSHI/1.mp4" — mirrored into the output path so results are identifiable. */
  key: string;
  /** Presigned URL the transcoder reads the source from. */
  url: string;
}

interface S3PickerModalProps {
  /** Receives every file the user checked; one job set is created per file. */
  onSelect: (files: S3PickedFile[]) => void;
  onClose: () => void;
}

/** "a/b/c/" -> [{label:'a',prefix:'a/'},{label:'b',prefix:'a/b/'},{label:'c',prefix:'a/b/c/'}] */
function breadcrumbsFor(prefix: string): { label: string; prefix: string }[] {
  if (!prefix) return [];
  const parts = prefix.split('/').filter(Boolean);
  return parts.map((label, i) => ({ label, prefix: parts.slice(0, i + 1).join('/') + '/' }));
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
  const [prefix, setPrefix] = useState('');
  const [folders, setFolders] = useState<S3Folder[]>([]);
  const [files, setFiles] = useState<S3FileItem[]>([]);
  const [loadingFiles, setLoadingFiles] = useState(false);
  const [filesError, setFilesError] = useState<string | null>(null);
  const [search, setSearch] = useState('');
  const [debouncedSearch, setDebouncedSearch] = useState('');
  const [selecting, setSelecting] = useState(false);
  const [selectError, setSelectError] = useState<string | null>(null);
  // Checked files, keyed by "bucket/key" so a selection survives navigating between
  // folders — you can gather sources from several folders before submitting.
  const [checked, setChecked] = useState<Map<string, S3FileItem>>(new Map());
  // Files whose name the user tapped to read in full; long S3 keys are otherwise
  // truncated to a width that hides the part that distinguishes them.
  const [expanded, setExpanded] = useState<Set<string>>(new Set());

  const searching = debouncedSearch.trim().length > 0;

  useEffect(() => {
    if (!activeAccountId) return;
    setLoadingBuckets(true); setBucketsError(null); setBuckets([]);
    setSelectedBucket(null); setPrefix(''); setFolders([]); setFiles([]);
    apiFetchBuckets(activeAccountId)
      .then(data => { setBuckets(data); setLoadingBuckets(false); })
      .catch(e => { setBucketsError(e.message); setLoadingBuckets(false); });
  }, [activeAccountId]);

  // Searching hits the recursive endpoint, so hold off until typing settles.
  useEffect(() => {
    const t = setTimeout(() => setDebouncedSearch(search), 300);
    return () => clearTimeout(t);
  }, [search]);

  useEffect(() => {
    if (!activeAccountId || !selectedBucket) { setFolders([]); setFiles([]); return; }

    let cancelled = false;
    setLoadingFiles(true); setFilesError(null);

    const request = searching
      // Search looks below the folder you are standing in, not the whole bucket.
      ? apiSearchFiles(activeAccountId, selectedBucket, prefix).then(f => ({ folders: [], files: f }))
      : apiBrowse(activeAccountId, selectedBucket, prefix).then(r => ({ folders: r.folders, files: r.files }));

    request
      .then(({ folders, files }) => {
        if (cancelled) return;
        setFolders(folders); setFiles(files); setLoadingFiles(false);
      })
      .catch(e => {
        if (cancelled) return;
        setFilesError(e.message); setLoadingFiles(false);
      });

    return () => { cancelled = true; };
  }, [activeAccountId, selectedBucket, prefix, searching, debouncedSearch]);

  const idOf = (file: S3FileItem) => `${file.bucket}/${file.key}`;

  const toggleChecked = (file: S3FileItem) => {
    setSelectError(null);
    setChecked(prev => {
      const next = new Map(prev);
      const id = idOf(file);
      if (next.has(id)) next.delete(id); else next.set(id, file);
      return next;
    });
  };

  const toggleExpanded = (file: S3FileItem) => {
    setExpanded(prev => {
      const next = new Set(prev);
      const id = idOf(file);
      if (next.has(id)) next.delete(id); else next.add(id);
      return next;
    });
  };

  /** Presigns every checked file and hands them all to the caller in one go. */
  const confirmSelection = async () => {
    if (!activeAccountId || checked.size === 0) return;
    setSelecting(true); setSelectError(null);
    const files = Array.from(checked.values());
    try {
      const picked = await Promise.all(files.map(async file => ({
        name: file.name,
        formattedSize: file.formattedSize,
        bucket: file.bucket,
        key: file.key,
        url: await apiPresign(activeAccountId, file.bucket, file.key, file.region, 720),
      })));
      onSelect(picked);
    } catch (e: any) {
      setSelectError(e.message);
      setSelecting(false);
    }
  };

  const openFolder = (folderPrefix: string) => {
    setSearch(''); setDebouncedSearch('');
    setExpanded(new Set());
    setPrefix(folderPrefix);
  };

  const visibleFiles = searching
    ? files.filter(f => f.key.toLowerCase().includes(debouncedSearch.toLowerCase()))
    : files;

  const crumbs = breadcrumbsFor(prefix);
  const selectedCount = checked.size;

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
            <select value={selectedBucket ?? ''} onChange={e => { setSelectedBucket(e.target.value || null); setPrefix(''); setSearch(''); }}
              disabled={loadingBuckets || buckets.length === 0}
              className="w-full px-3 py-1.5 text-[12px] border border-[rgba(0,0,0,0.12)] rounded-lg bg-white focus:outline-none disabled:opacity-60">
              <option value="">— Select a bucket —</option>
              {buckets.map(b => <option key={b.name} value={b.name}>{b.name}</option>)}
            </select>
          </div>
          {selectedBucket && (
            <div className="relative">
              <Icon icon="mdi:magnify" className="absolute left-2.5 top-1/2 -translate-y-1/2 text-[rgba(0,0,0,0.3)] text-sm" />
              <input type="text" placeholder={prefix ? 'Search in folder…' : 'Search bucket…'} value={search} onChange={e => setSearch(e.target.value)}
                className="pl-8 pr-3 py-1.5 text-[12px] border border-[rgba(0,0,0,0.12)] rounded-lg focus:outline-none w-40" />
            </div>
          )}
        </div>

        {/* Breadcrumbs */}
        {selectedBucket && (
          <div className="px-5 py-2 border-b border-[rgba(0,0,0,0.06)] flex items-center gap-1 flex-wrap flex-shrink-0 bg-[rgba(0,0,0,0.015)]">
            <button onClick={() => openFolder('')}
              className={`flex items-center gap-1 px-2 py-1 rounded-md text-[11px] font-semibold transition-colors ${
                prefix ? 'text-[rgba(0,0,0,0.55)] hover:bg-[rgba(0,0,0,0.05)]' : 'text-[rgba(0,0,0,0.88)]'}`}>
              <Icon icon="mdi:bucket-outline" className="text-[13px]" />
              {selectedBucket}
            </button>
            {crumbs.map((c, i) => (
              <div key={c.prefix} className="flex items-center gap-1">
                <Icon icon="mdi:chevron-right" className="text-[rgba(0,0,0,0.25)] text-[13px]" />
                <button onClick={() => openFolder(c.prefix)}
                  className={`px-2 py-1 rounded-md text-[11px] font-semibold transition-colors ${
                    i === crumbs.length - 1
                      ? 'text-[rgba(0,0,0,0.88)]'
                      : 'text-[rgba(0,0,0,0.55)] hover:bg-[rgba(0,0,0,0.05)]'}`}>
                  {c.label}
                </button>
              </div>
            ))}
            {searching && (
              <span className="ml-1 px-2 py-0.5 rounded-md bg-[rgba(0,0,0,0.06)] text-[10px] font-semibold text-[rgba(0,0,0,0.5)]">
                searching {prefix ? 'this folder' : 'whole bucket'}
              </span>
            )}
          </div>
        )}

        {/* Error banners */}
        {(bucketsError || selectError) && (
          <div className="px-5 py-2 bg-red-50 border-b border-red-100 flex items-center gap-2 flex-shrink-0">
            <Icon icon="mdi:alert-circle-outline" className="text-red-500 text-sm flex-shrink-0" />
            <p className="text-[11px] text-red-700">{bucketsError || selectError}</p>
          </div>
        )}

        {/* Listing */}
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
          ) : folders.length === 0 && visibleFiles.length === 0 ? (
            <div className="flex flex-col items-center justify-center py-12 text-center">
              <Icon icon="mdi:video-off-outline" className="text-4xl text-[rgba(0,0,0,0.1)] mb-3" />
              <p className="text-[12px] text-[rgba(0,0,0,0.35)]">
                {searching ? 'No videos match your search' : prefix ? 'This folder is empty' : 'No video files found in this bucket'}
              </p>
              <p className="text-[10px] text-[rgba(0,0,0,0.25)] mt-1">Supported: MP4, MOV, MKV, AVI, WebM, TS and more</p>
            </div>
          ) : (
            <>
              <div className="flex items-center justify-between mb-3">
                <p className="text-[10px] text-[rgba(0,0,0,0.35)]">
                  {folders.length > 0 && `${folders.length} folder${folders.length !== 1 ? 's' : ''}`}
                  {folders.length > 0 && visibleFiles.length > 0 && ' · '}
                  {visibleFiles.length > 0 && `${visibleFiles.length} video${visibleFiles.length !== 1 ? 's' : ''}`}
                </p>
                {visibleFiles.length > 0 && (
                  <button
                    onClick={() => {
                      const allChecked = visibleFiles.every(f => checked.has(idOf(f)));
                      setChecked(prev => {
                        const next = new Map(prev);
                        for (const f of visibleFiles) {
                          if (allChecked) next.delete(idOf(f)); else next.set(idOf(f), f);
                        }
                        return next;
                      });
                    }}
                    className="text-[10px] font-semibold text-[rgba(0,0,0,0.5)] hover:text-blue-700 transition-colors">
                    {visibleFiles.every(f => checked.has(idOf(f))) ? 'Clear all' : 'Select all'}
                  </button>
                )}
              </div>

              {/* Folders first, mirroring the S3 console */}
              {folders.length > 0 && (
                <div className="grid grid-cols-1 sm:grid-cols-2 gap-2.5 mb-3">
                  {folders.map(folder => (
                    <button key={folder.prefix} onClick={() => openFolder(folder.prefix)}
                      className="flex items-center gap-3 bg-white border border-[rgba(0,0,0,0.07)] rounded-xl px-3 py-2.5 hover:border-[rgba(0,0,0,0.2)] hover:bg-[rgba(0,0,0,0.015)] transition-colors text-left">
                      <div className="w-9 h-9 bg-[rgba(0,0,0,0.04)] rounded-lg flex items-center justify-center flex-shrink-0">
                        <Icon icon="mdi:folder-outline" className="text-[rgba(0,0,0,0.45)] text-lg" />
                      </div>
                      <div className="flex-1 min-w-0">
                        <p className="text-[12px] font-semibold text-[rgba(0,0,0,0.88)] truncate" title={folder.name}>{folder.name}</p>
                        <p className="text-[10px] text-[rgba(0,0,0,0.4)]">Folder</p>
                      </div>
                      <Icon icon="mdi:chevron-right" className="text-[rgba(0,0,0,0.3)] text-lg flex-shrink-0" />
                    </button>
                  ))}
                </div>
              )}

              <div className="grid grid-cols-1 sm:grid-cols-2 gap-2.5">
                {visibleFiles.map(file => {
                  const id = idOf(file);
                  const isChecked = checked.has(id);
                  const isExpanded = expanded.has(id);
                  return (
                    <div key={id} onClick={() => toggleChecked(file)}
                      className={`flex items-start gap-3 border rounded-xl px-3 py-2.5 cursor-pointer transition-colors ${
                        isChecked
                          ? 'bg-blue-50/70 border-blue-400'
                          : 'bg-[rgba(0,0,0,0.02)] border-[rgba(0,0,0,0.07)] hover:border-[rgba(0,0,0,0.15)]'}`}>
                      <div className={`w-5 h-5 mt-1 rounded-md border flex items-center justify-center flex-shrink-0 transition-colors ${
                        isChecked ? 'bg-blue-600 border-blue-600' : 'bg-white border-[rgba(0,0,0,0.2)]'}`}>
                        {isChecked && <Icon icon="mdi:check" className="text-white text-[13px]" />}
                      </div>
                      <div className="w-9 h-9 bg-[rgba(0,0,0,0.04)] rounded-lg flex items-center justify-center flex-shrink-0">
                        <Icon icon="mdi:file-video-outline" className="text-[rgba(0,0,0,0.35)] text-lg" />
                      </div>
                      <div className="flex-1 min-w-0">
                        {/* Tap the name to read a long one in full — S3 keys routinely run
                            past the card width, hiding the part that identifies the file. */}
                        <p onClick={e => { e.stopPropagation(); toggleExpanded(file); }}
                          title={isExpanded ? 'Click to collapse' : 'Click to see full name'}
                          className={`text-[12px] font-semibold text-[rgba(0,0,0,0.88)] hover:text-blue-700 ${
                            isExpanded ? 'break-all' : 'truncate'}`}>
                          {file.name}
                        </p>
                        {/* When searching, the folder path is the only thing telling three
                            identically-named files apart, so show it. */}
                        {(searching || isExpanded) && file.key.includes('/') && (
                          <p className={`text-[10px] text-[rgba(0,0,0,0.35)] ${isExpanded ? 'break-all' : 'truncate'}`}>
                            {file.key.slice(0, file.key.lastIndexOf('/'))}
                          </p>
                        )}
                        <p className="text-[10px] text-[rgba(0,0,0,0.4)]">
                          {file.formattedSize} · {file.extension.slice(1).toUpperCase()}
                          {file.lastModified ? ` · ${new Date(file.lastModified).toLocaleDateString()}` : ''}
                        </p>
                      </div>
                    </div>
                  );
                })}
              </div>
            </>
          )}
        </div>

        {/* Selection footer — the count is cumulative across folders, so it stays visible
            even while standing in a folder where nothing is checked. */}
        {selectedCount > 0 && (
          <div className="flex items-center justify-between gap-3 px-5 py-3 border-t border-[rgba(0,0,0,0.08)] bg-[rgba(0,0,0,0.02)] flex-shrink-0">
            <p className="text-[11.5px] font-semibold text-[rgba(0,0,0,0.65)]">
              {selectedCount} video{selectedCount !== 1 ? 's' : ''} selected
              <button onClick={() => setChecked(new Map())}
                className="ml-2 font-normal text-[rgba(0,0,0,0.4)] hover:text-red-600 transition-colors">
                clear
              </button>
            </p>
            <button onClick={confirmSelection} disabled={selecting}
              className="flex items-center gap-1.5 px-4 py-2 text-[12px] font-bold bg-[rgba(0,0,0,0.88)] text-white rounded-xl hover:bg-[rgba(0,0,0,0.7)] transition-colors disabled:opacity-50">
              {selecting
                ? <><Icon icon="mdi:loading" className="animate-spin text-sm" /> Preparing…</>
                : <><Icon icon="mdi:check" className="text-sm" /> Use {selectedCount} video{selectedCount !== 1 ? 's' : ''}</>}
            </button>
          </div>
        )}
      </div>
    </div>
  );
}
