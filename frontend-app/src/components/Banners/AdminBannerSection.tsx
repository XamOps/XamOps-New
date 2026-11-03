import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { Icon } from '@iconify/react';
import { DialogRoot, DialogPanel } from '../ui/Dialog';
import { AlertDialog } from '../ui/AlertDialog';
import {
  type AdminBanner,
  type BannerClientOption,
  BANNER_BODY_MAX,
  BANNER_HEADING_MAX,
  createAdminBanner,
  deleteAdminBanner,
  fetchAdminBanners,
  fetchBannerClientOptions,
} from './bannerApi';

const formatDate = (iso: string) => {
  const d = new Date(iso);
  return Number.isNaN(d.getTime())
    ? ''
    : d.toLocaleString(undefined, { day: 'numeric', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit' });
};

/**
 * Top-of-dashboard section where BillOps admins write announcement banners for XamOps clients
 * and choose which accounts see them. Clients see them through DashboardAnnouncementBanner.
 */
const AdminBannerSection: React.FC = () => {
  const [banners, setBanners] = useState<AdminBanner[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [createOpen, setCreateOpen] = useState(false);
  const [pendingDelete, setPendingDelete] = useState<AdminBanner | null>(null);
  const [deleting, setDeleting] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      setBanners(await fetchAdminBanners());
      setError(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to load banners');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { load(); }, [load]);

  const confirmDelete = async () => {
    if (!pendingDelete) return;
    setDeleting(true);
    try {
      await deleteAdminBanner(pendingDelete.id);
      setBanners(prev => prev.filter(b => b.id !== pendingDelete.id));
      setPendingDelete(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to delete banner');
      setPendingDelete(null);
    } finally {
      setDeleting(false);
    }
  };

  return (
    <div className="bo-card">
      <div className="flex items-center justify-between gap-3 px-5 py-3.5 border-b border-[rgba(0,0,0,0.06)]">
        <div className="flex items-center gap-2.5 min-w-0">
          <div className="w-8 h-8 rounded-lg flex items-center justify-center bg-[#4285F4]/10 flex-shrink-0">
            <Icon icon="mdi:bullhorn-outline" width={17} color="#4285F4" />
          </div>
          <div className="min-w-0">
            <h3 className="text-[14px] font-semibold text-[rgba(0,0,0,0.88)]">Announcement Banners</h3>
            <p className="text-[11px] text-[rgba(0,0,0,0.45)]">Shown at the top of the selected XamOps accounts' dashboards</p>
          </div>
        </div>
        <button
          type="button"
          onClick={() => setCreateOpen(true)}
          className="inline-flex items-center gap-1.5 px-3.5 py-2 bg-[rgba(0,0,0,0.88)] hover:bg-black text-white rounded-lg text-[12px] font-semibold transition-colors flex-shrink-0"
        >
          <Icon icon="mdi:plus" width={15} />
          New Banner
        </button>
      </div>

      <div className="px-5 py-3">
        {error && (
          <div className="mb-2 flex items-center gap-2 text-[12px] text-red-600">
            <Icon icon="mdi:alert-circle-outline" width={15} />
            <span>{error}</span>
            <button type="button" onClick={load} className="ml-auto font-semibold underline">Retry</button>
          </div>
        )}

        {loading ? (
          <div className="flex items-center gap-2 py-3 text-[12px] text-[rgba(0,0,0,0.4)]">
            <Icon icon="mdi:loading" width={15} className="animate-spin" />
            Loading banners…
          </div>
        ) : banners.length === 0 ? (
          <p className="py-3 text-[12px] text-[rgba(0,0,0,0.4)]">
            No banners yet. Create one to show a message such as "What's new" on client dashboards.
          </p>
        ) : (
          <ul className="divide-y divide-[rgba(0,0,0,0.05)] max-h-[260px] overflow-y-auto admin-scrollbar">
            {banners.map(b => (
              <li key={b.id} className="flex items-start gap-3 py-2.5">
                <Icon icon="mdi:message-badge-outline" width={16} color="rgba(0,0,0,0.4)" className="mt-0.5 flex-shrink-0" />
                <div className="flex-1 min-w-0">
                  <div className="flex items-center gap-2 min-w-0">
                    <span className="text-[13px] font-semibold text-[rgba(0,0,0,0.85)] truncate">{b.heading}</span>
                    <span
                      className={`flex-shrink-0 px-1.5 py-px rounded text-[10px] font-semibold ${
                        b.targetAll ? 'bg-emerald-50 text-emerald-700 border border-emerald-200' : 'bg-blue-50 text-blue-700 border border-blue-200'
                      }`}
                      title={b.targetAll ? 'Every XamOps account' : b.targetClients.map(c => c.name).join(', ')}
                    >
                      {b.targetAll
                        ? 'All accounts'
                        : `${b.targetClients.length} account${b.targetClients.length === 1 ? '' : 's'}`}
                    </span>
                  </div>
                  <p className="text-[12px] text-[rgba(0,0,0,0.55)] truncate">{b.body}</p>
                  <p className="text-[10px] text-[rgba(0,0,0,0.35)] mt-0.5">
                    {formatDate(b.createdAt)}{b.createdBy ? ` · ${b.createdBy}` : ''}
                  </p>
                </div>
                <button
                  type="button"
                  onClick={() => setPendingDelete(b)}
                  className="p-1.5 rounded-md text-[rgba(0,0,0,0.35)] hover:text-rose-600 hover:bg-rose-50 transition-colors flex-shrink-0"
                  aria-label={`Delete banner ${b.heading}`}
                  title="Delete banner"
                >
                  <Icon icon="mdi:trash-can-outline" width={16} />
                </button>
              </li>
            ))}
          </ul>
        )}
      </div>

      {createOpen && (
        <CreateBannerDialog
          onClose={() => setCreateOpen(false)}
          onCreated={b => { setBanners(prev => [b, ...prev]); setCreateOpen(false); }}
        />
      )}

      <AlertDialog
        isOpen={pendingDelete !== null}
        title="Delete banner?"
        description={pendingDelete
          ? `"${pendingDelete.heading}" will be removed from every client dashboard it is shown on.`
          : undefined}
        confirmLabel={deleting ? 'Deleting…' : 'Delete'}
        confirmDisabled={deleting}
        variant="danger"
        onConfirm={confirmDelete}
        onCancel={() => { if (!deleting) setPendingDelete(null); }}
      />
    </div>
  );
};

// ── Create dialog ───────────────────────────────────────────────────────────

interface CreateBannerDialogProps {
  onClose: () => void;
  onCreated: (banner: AdminBanner) => void;
}

const CreateBannerDialog: React.FC<CreateBannerDialogProps> = ({ onClose, onCreated }) => {
  const [heading, setHeading] = useState('');
  const [body, setBody] = useState('');
  const [targetAll, setTargetAll] = useState(true);
  const [selected, setSelected] = useState<Set<number>>(new Set());
  const [search, setSearch] = useState('');

  const [options, setOptions] = useState<BannerClientOption[]>([]);
  const [optionsLoading, setOptionsLoading] = useState(true);
  const [optionsError, setOptionsError] = useState<string | null>(null);

  const [saving, setSaving] = useState(false);
  const [saveError, setSaveError] = useState<string | null>(null);

  useEffect(() => {
    let active = true;
    fetchBannerClientOptions()
      .then(o => { if (active) { setOptions(o); setOptionsError(null); } })
      .catch(e => { if (active) setOptionsError(e instanceof Error ? e.message : 'Failed to load accounts'); })
      .finally(() => { if (active) setOptionsLoading(false); });
    return () => { active = false; };
  }, []);

  const filtered = useMemo(() => {
    const q = search.trim().toLowerCase();
    return q ? options.filter(o => (o.name || '').toLowerCase().includes(q)) : options;
  }, [options, search]);

  const toggle = (id: number) => setSelected(prev => {
    const next = new Set(prev);
    if (next.has(id)) next.delete(id); else next.add(id);
    return next;
  });

  const canSave = heading.trim().length > 0
    && body.trim().length > 0
    && (targetAll || selected.size > 0)
    && !saving;

  const submit = async (e?: React.FormEvent) => {
    e?.preventDefault();
    if (!canSave) return;
    setSaving(true);
    setSaveError(null);
    try {
      const created = await createAdminBanner({
        heading: heading.trim(),
        body: body.trim(),
        targetAll,
        clientIds: targetAll ? [] : Array.from(selected),
      });
      onCreated(created);
    } catch (err) {
      setSaveError(err instanceof Error ? err.message : 'Failed to create banner');
    } finally {
      setSaving(false);
    }
  };

  const inputCls = 'w-full px-3 py-2 text-[13px] border border-[rgba(0,0,0,0.12)] rounded-lg focus:outline-none focus:border-[rgba(0,0,0,0.4)] placeholder:text-[rgba(0,0,0,0.35)]';
  const labelCls = 'flex items-center gap-1.5 text-[11px] font-semibold uppercase tracking-wide text-[rgba(0,0,0,0.5)] mb-1.5';

  return (
    <DialogRoot isOpen onClose={onClose} maxWidth="max-w-2xl">
      <DialogPanel
        title="New announcement banner"
        onClose={onClose}
        footer={
          <>
            <button
              type="button"
              onClick={onClose}
              className="inline-flex items-center gap-2 px-4 py-2 border border-[rgba(0,0,0,0.12)] rounded-md bg-white text-[rgba(0,0,0,0.6)] text-xs font-medium hover:bg-[rgba(0,0,0,0.03)] transition-colors"
            >
              Cancel
            </button>
            <button
              type="button"
              onClick={() => submit()}
              disabled={!canSave}
              className="inline-flex items-center gap-2 px-4 py-2 bg-[rgba(0,0,0,0.88)] hover:bg-black text-white rounded-md text-xs font-semibold transition-colors disabled:opacity-40 disabled:cursor-not-allowed"
            >
              <Icon icon={saving ? 'mdi:loading' : 'mdi:send-outline'} width={13} className={saving ? 'animate-spin' : ''} />
              {saving ? 'Publishing…' : 'Publish Banner'}
            </button>
          </>
        }
      >
        <form onSubmit={submit} className="space-y-4">
          <div>
            <label htmlFor="banner-heading" className={labelCls}>
              <Icon icon="mdi:format-title" width={13} /> Heading
            </label>
            <input
              id="banner-heading"
              type="text"
              value={heading}
              maxLength={BANNER_HEADING_MAX}
              onChange={e => setHeading(e.target.value)}
              placeholder="e.g. What's new"
              className={inputCls}
              autoFocus
            />
          </div>

          <div>
            <label htmlFor="banner-body" className={labelCls}>
              <Icon icon="mdi:text" width={13} /> Body
            </label>
            <textarea
              id="banner-body"
              value={body}
              maxLength={BANNER_BODY_MAX}
              onChange={e => setBody(e.target.value)}
              placeholder="The message clients see when they open the banner"
              rows={5}
              className={`${inputCls} resize-y min-h-[110px]`}
            />
            <p className="text-[10px] text-right text-[rgba(0,0,0,0.35)] mt-1">{body.length}/{BANNER_BODY_MAX}</p>
          </div>

          <div>
            <span className={labelCls}><Icon icon="mdi:account-group-outline" width={13} /> Show to</span>
            <div className="flex gap-2 mb-3" role="radiogroup" aria-label="Banner audience">
              {[
                { value: true, label: 'All accounts', icon: 'mdi:earth' },
                { value: false, label: 'Selected accounts', icon: 'mdi:account-multiple-check-outline' },
              ].map(opt => {
                const active = targetAll === opt.value;
                return (
                  <button
                    key={String(opt.value)}
                    type="button"
                    role="radio"
                    aria-checked={active}
                    onClick={() => setTargetAll(opt.value)}
                    className={`flex items-center gap-1.5 px-3 py-2 rounded-lg border text-[12px] font-medium transition-colors ${
                      active
                        ? 'border-[rgba(0,0,0,0.88)] bg-[rgba(0,0,0,0.88)] text-white'
                        : 'border-[rgba(0,0,0,0.12)] text-[rgba(0,0,0,0.6)] hover:bg-[rgba(0,0,0,0.03)]'
                    }`}
                  >
                    <Icon icon={opt.icon} width={14} />
                    {opt.label}
                  </button>
                );
              })}
            </div>

            {!targetAll && (
              optionsLoading ? (
                <div className="flex items-center justify-center gap-2 py-6 text-[12px] text-[rgba(0,0,0,0.4)]">
                  <Icon icon="mdi:loading" width={16} className="animate-spin" /> Loading accounts…
                </div>
              ) : optionsError ? (
                <div className="flex items-center justify-center gap-2 py-6 text-[12px] text-rose-600">
                  <Icon icon="mdi:alert-circle-outline" width={16} /> {optionsError}
                </div>
              ) : options.length === 0 ? (
                <p className="py-6 text-center text-[12px] text-[rgba(0,0,0,0.4)]">No accounts available.</p>
              ) : (
                <>
                  <div className="flex items-center gap-2 mb-2">
                    <div className="relative flex-1">
                      <Icon icon="mdi:magnify" width={14} color="rgba(0,0,0,0.35)" className="absolute left-2.5 top-1/2 -translate-y-1/2 pointer-events-none" />
                      <input
                        type="text"
                        value={search}
                        onChange={e => setSearch(e.target.value)}
                        placeholder="Search accounts"
                        className="w-full pl-8 pr-3 py-2 text-[12px] border border-[rgba(0,0,0,0.12)] rounded-lg focus:outline-none focus:border-[rgba(0,0,0,0.4)]"
                      />
                    </div>
                    <button
                      type="button"
                      onClick={() => setSelected(prev => new Set([...prev, ...filtered.map(o => o.id)]))}
                      className="text-[11px] font-semibold text-[rgba(0,0,0,0.6)] hover:text-black"
                    >
                      {search.trim() ? 'Select shown' : 'Select all'}
                    </button>
                    <span className="text-[rgba(0,0,0,0.15)]">|</span>
                    <button
                      type="button"
                      onClick={() => setSelected(new Set())}
                      className="text-[11px] font-semibold text-rose-500 hover:text-rose-700"
                    >
                      Clear
                    </button>
                  </div>
                  <p className="text-[10px] text-[rgba(0,0,0,0.4)] mb-2">
                    Showing {filtered.length} of {options.length} · {selected.size} selected
                  </p>
                  <div className="grid grid-cols-1 sm:grid-cols-2 gap-1.5 max-h-[220px] overflow-y-auto pr-1">
                    {filtered.map(o => (
                      <label
                        key={o.id}
                        className="flex items-center gap-2 px-2.5 py-2 border border-[rgba(0,0,0,0.06)] rounded-lg cursor-pointer hover:bg-[rgba(0,0,0,0.02)] transition-colors"
                      >
                        <input
                          type="checkbox"
                          checked={selected.has(o.id)}
                          onChange={() => toggle(o.id)}
                          className="w-3.5 h-3.5 rounded accent-[rgba(0,0,0,0.88)] cursor-pointer flex-shrink-0"
                        />
                        <Icon icon="mdi:domain" width={13} color="rgba(0,0,0,0.45)" />
                        <span className="text-[12px] font-medium text-[rgba(0,0,0,0.7)] truncate">{o.name || `Client ${o.id}`}</span>
                      </label>
                    ))}
                    {filtered.length === 0 && (
                      <p className="col-span-full py-4 text-center text-[12px] text-[rgba(0,0,0,0.4)]">No accounts match "{search}"</p>
                    )}
                  </div>
                </>
              )
            )}
          </div>

          {saveError && (
            <div className="flex items-center gap-2 text-[12px] text-rose-600">
              <Icon icon="mdi:alert-circle-outline" width={15} /> {saveError}
            </div>
          )}
        </form>
      </DialogPanel>
    </DialogRoot>
  );
};

export default AdminBannerSection;
