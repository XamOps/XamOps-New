import React, { useMemo, useState } from 'react';
import { Icon } from '@iconify/react';
import { AlertDialog } from '../components/ui';
import { useDspmAdmin, useDspmClientOptions } from '../hooks/useDspmAdmin';
import {
  ConnectionBadge, EntitlementSwitch, ExpiryBadge, KeyBadge,
} from '../components/Dspm/DspmAdminBadges';
import DspmMappingModal from '../components/Dspm/DspmMappingModal';
import DspmAuditTable from '../components/Dspm/DspmAuditTable';
import type { DspmMappingAdminView, DspmMappingRequest } from '../types/dspm';

/**
 * Internal operator console for the Matters.ai DSPM add-on.
 *
 * <h2>Why this is its own page</h2>
 * A Matters API key is issued against exactly one account and one project, and the vendor exposes no way
 * to name a project on a request — so the credential is necessarily per-customer, and this page is where
 * every one of them is recorded. With one key per customer and a 180-day cap on key lifetime, expiries
 * arrive continuously; a table sortable by expiry is the only place anyone would notice one coming before
 * the customer does.
 *
 * Two independent switches govern each customer and the distinction matters: the **add-on** is the
 * commercial grant, the **connection** is whether the credential works. A customer can be entitled with
 * no mapping yet — the normal state between signing and provisioning — and that must not read as an
 * error.
 */

const Tile: React.FC<{
  icon: string;
  label: string;
  value: number | string;
  tone?: 'neutral' | 'warn' | 'bad';
}> = ({ icon, label, value, tone = 'neutral' }) => {
  const valueCls = tone === 'bad'
    ? 'text-rose-700'
    : tone === 'warn' ? 'text-amber-700' : 'text-[rgba(0,0,0,0.88)]';
  return (
    <div className="flex items-center gap-3 px-4 py-3 bg-white border border-[rgba(0,0,0,0.08)] rounded-xl">
      <Icon icon={icon} width={18} className="text-[rgba(0,0,0,0.35)] flex-shrink-0" />
      <div className="min-w-0">
        <div className={`text-lg font-bold leading-tight ${valueCls}`}>{value}</div>
        <div className="text-[11px] text-[rgba(0,0,0,0.45)] truncate">{label}</div>
      </div>
    </div>
  );
};

const DspmAdminPage: React.FC = () => {
  const {
    mappings, loading, error, busyClientId,
    loadMappings, saveMapping, deleteMapping, verifyMapping, setEntitlement, clearError,
  } = useDspmAdmin();
  const { clients, loadingClients, loadClients } = useDspmClientOptions();

  const [search, setSearch] = useState('');
  const [editing, setEditing] = useState<{ clientId: number; clientName: string | null;
    existing: DspmMappingAdminView | null } | null>(null);
  const [picking, setPicking] = useState(false);
  const [pendingDelete, setPendingDelete] = useState<DspmMappingAdminView | null>(null);
  const [verifyResult, setVerifyResult] = useState<string | null>(null);

  const rows = useMemo(() => {
    const list = mappings ?? [];
    const needle = search.trim().toLowerCase();
    if (!needle) return list;
    return list.filter(m =>
      (m.clientName ?? '').toLowerCase().includes(needle)
      || m.mattersProjectId.toLowerCase().includes(needle)
      || (m.mattersProjectName ?? '').toLowerCase().includes(needle));
  }, [mappings, search]);

  const stats = useMemo(() => {
    const list = mappings ?? [];
    return {
      total: list.length,
      connected: list.filter(m => m.connectionStatus === 'CONNECTED').length,
      // Everything that needs a human: a bad credential, a denied scope, or our own encryption fault.
      // UNREACHABLE is excluded on purpose — a vendor outage resolves itself.
      attention: list.filter(m =>
        m.connectionStatus === 'INVALID_KEY'
        || m.connectionStatus === 'FORBIDDEN'
        || m.connectionStatus === 'DECRYPT_FAILED').length,
      expiring: list.filter(m => m.expiryState === 'WARNING' || m.expiryState === 'EXPIRED').length,
    };
  }, [mappings]);

  const openEditor = (clientId: number, clientName: string | null,
                      existing: DspmMappingAdminView | null) => {
    setPicking(false);
    setEditing({ clientId, clientName, existing });
  };

  const handleSave = async (request: DspmMappingRequest) => {
    if (!editing) return;
    // Errors propagate to the modal, which keeps itself open and shows them — a 409 naming a conflicting
    // customer is the whole reason the operator needs to stay on the form.
    await saveMapping(editing.clientId, request);
    setEditing(null);
  };

  const handleVerify = async (mapping: DspmMappingAdminView) => {
    const result = await verifyMapping(mapping.clientId);
    if (!result) return;
    setVerifyResult(result.connectionStatus === 'CONNECTED'
      ? `${mapping.clientName ?? 'Client'} connected successfully.`
      : `${mapping.clientName ?? 'Client'}: ${result.lastError ?? result.connectionStatus}`);
  };

  const unmappedClients = (clients ?? []).filter(c => !c.mapped);

  return (
    <div className="px-6 pb-12 pt-6 space-y-5">
      <AlertDialog
        isOpen={!!pendingDelete}
        title="Remove this DSPM mapping?"
        description={
          `${pendingDelete?.clientName ?? 'This customer'} will immediately lose access to their data `
          + 'security inventory. The stored API key is deleted and cannot be recovered — Matters shows a '
          + 'key once at creation, so re-connecting requires minting a new one.'
        }
        confirmLabel="Remove mapping"
        variant="danger"
        onConfirm={async () => {
          if (pendingDelete) await deleteMapping(pendingDelete.clientId);
          setPendingDelete(null);
        }}
        onCancel={() => setPendingDelete(null)}
      />

      {/* ── Header ── */}
      <div className="flex flex-wrap items-center gap-3">
        <div className="flex items-center gap-3">
          <div className="w-10 h-10 rounded-xl bg-[rgba(0,0,0,0.88)] flex items-center justify-center">
            <Icon icon="mdi:database-lock-outline" width={20} color="white" />
          </div>
          <div>
            <h1 className="text-base font-bold text-[rgba(0,0,0,0.88)]">XamLens (DSPM)</h1>
            <p className="text-xs text-[rgba(0,0,0,0.45)]">
              Customer organisation to Matters project mappings
            </p>
          </div>
        </div>

        <div className="ml-auto flex items-center gap-2">
          <button
            onClick={() => { void loadMappings(); }}
            className="inline-flex items-center gap-1.5 px-3 py-2 text-xs font-semibold text-[rgba(0,0,0,0.6)] border border-[rgba(0,0,0,0.12)] rounded-lg hover:bg-[rgba(0,0,0,0.03)] transition-colors"
          >
            <Icon icon="mdi:refresh" width={13} className={loading ? 'animate-spin' : ''} />
            Refresh
          </button>
          <button
            onClick={() => { setPicking(true); void loadClients(); }}
            className="inline-flex items-center gap-1.5 px-3 py-2 text-xs font-semibold text-white bg-[rgba(0,0,0,0.88)] rounded-lg hover:bg-black transition-colors focus:outline-none focus-visible:ring-2 focus-visible:ring-[rgba(0,0,0,0.3)]"
          >
            <Icon icon="mdi:plus" width={13} />
            Add mapping
          </button>
        </div>
      </div>

      {error && (
        <div className="flex items-start gap-3 p-4 bg-rose-50 border border-rose-100 rounded-xl text-sm text-rose-700">
          <Icon icon="mdi:alert-circle-outline" width={16} className="flex-shrink-0 mt-0.5" />
          <span className="flex-1">{error}</span>
          <button onClick={clearError} className="p-1 rounded hover:bg-rose-100" aria-label="Dismiss">
            <Icon icon="mdi:close" width={14} />
          </button>
        </div>
      )}

      {verifyResult && (
        <div className="flex items-start gap-3 p-4 bg-[rgba(0,0,0,0.03)] border border-[rgba(0,0,0,0.08)] rounded-xl text-sm text-[rgba(0,0,0,0.7)]">
          <Icon icon="mdi:information-outline" width={16} className="flex-shrink-0 mt-0.5" />
          <span className="flex-1">{verifyResult}</span>
          <button
            onClick={() => setVerifyResult(null)}
            className="p-1 rounded hover:bg-[rgba(0,0,0,0.05)]" aria-label="Dismiss"
          >
            <Icon icon="mdi:close" width={14} />
          </button>
        </div>
      )}

      {/* ── Tiles ── */}
      <div className="grid grid-cols-2 lg:grid-cols-4 gap-3">
        <Tile icon="mdi:database-outline" label="Mapped customers" value={stats.total} />
        <Tile icon="mdi:check-circle-outline" label="Connected" value={stats.connected} />
        <Tile
          icon="mdi:key-alert-outline" label="Need attention" value={stats.attention}
          tone={stats.attention > 0 ? 'bad' : 'neutral'}
        />
        <Tile
          icon="mdi:clock-alert-outline" label="Key expiring or expired" value={stats.expiring}
          tone={stats.expiring > 0 ? 'warn' : 'neutral'}
        />
      </div>

      {/* ── Mappings ── */}
      <section className="bg-white border border-[rgba(0,0,0,0.08)] rounded-2xl overflow-hidden">
        <header className="flex flex-wrap items-center gap-3 px-5 py-4 border-b border-[rgba(0,0,0,0.06)]">
          <div className="flex items-center gap-2">
            <Icon icon="mdi:link-variant" width={16} className="text-[rgba(0,0,0,0.5)]" />
            <h2 className="text-sm font-bold text-[rgba(0,0,0,0.88)]">Project mappings</h2>
          </div>
          <div className="ml-auto relative">
            <Icon
              icon="mdi:magnify" width={13}
              className="absolute left-2.5 top-1/2 -translate-y-1/2 text-[rgba(0,0,0,0.3)]"
            />
            <input
              type="search" value={search} onChange={e => setSearch(e.target.value)}
              placeholder="Search customer or project"
              className="pl-8 pr-3 py-1.5 w-60 text-xs bg-[rgba(0,0,0,0.03)] border border-[rgba(0,0,0,0.08)] rounded-lg text-[rgba(0,0,0,0.88)] placeholder-[rgba(0,0,0,0.3)] focus:outline-none focus:ring-1 focus:ring-[rgba(0,0,0,0.2)]"
            />
          </div>
        </header>

        <div className="overflow-x-auto">
          <table className="w-full text-left">
            <thead>
              <tr className="border-b border-[rgba(0,0,0,0.06)] bg-[rgba(0,0,0,0.015)]">
                {['Customer', 'Matters project', 'Key', 'Expiry', 'Connection', 'Add-on', ''].map((h, i) => (
                  <th
                    key={i}
                    className="px-4 py-2.5 text-[10px] font-semibold uppercase tracking-wider text-[rgba(0,0,0,0.45)] whitespace-nowrap"
                  >
                    {h}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {loading && (
                <tr><td colSpan={7} className="px-4 py-12 text-center text-xs text-[rgba(0,0,0,0.4)]">
                  Loading mappings…
                </td></tr>
              )}

              {!loading && rows.length === 0 && (
                <tr><td colSpan={7} className="px-4 py-12 text-center">
                  <Icon icon="mdi:database-off-outline" width={32} className="mx-auto text-[rgba(0,0,0,0.15)]" />
                  <p className="mt-2 text-xs font-semibold text-[rgba(0,0,0,0.6)]">
                    {mappings?.length ? 'No mappings match that search' : 'No DSPM mappings yet'}
                  </p>
                  {!mappings?.length && (
                    <p className="mt-1 text-xs text-[rgba(0,0,0,0.4)]">
                      Create the project in Matters first, then record it here.
                    </p>
                  )}
                </td></tr>
              )}

              {!loading && rows.map(m => {
                const busy = busyClientId === m.clientId;
                return (
                  <tr key={m.id} className="border-b border-[rgba(0,0,0,0.04)] hover:bg-[rgba(0,0,0,0.015)]">
                    <td className="px-4 py-3">
                      <div className="text-xs font-semibold text-[rgba(0,0,0,0.88)]">
                        {m.clientName ?? `Client ${m.clientId}`}
                      </div>
                      {!m.enabled && (
                        <span className="mt-0.5 inline-flex items-center gap-1 text-[10px] font-semibold text-[rgba(0,0,0,0.4)]">
                          <Icon icon="mdi:pause-circle-outline" width={10} />
                          Connection paused
                        </span>
                      )}
                    </td>

                    <td className="px-4 py-3">
                      <div className="text-xs text-[rgba(0,0,0,0.7)]">
                        {m.mattersProjectName ?? '—'}
                      </div>
                      <div
                        className="font-mono text-[10px] text-[rgba(0,0,0,0.4)] truncate max-w-[14rem]"
                        title={m.mattersProjectId}
                      >
                        {m.mattersProjectId}
                      </div>
                    </td>

                    <td className="px-4 py-3">
                      <KeyBadge env={m.apiKeyEnv} lastFour={m.apiKeyLastFour} keyId={m.apiKeyId} />
                    </td>

                    <td className="px-4 py-3">
                      <ExpiryBadge state={m.expiryState} days={m.daysUntilExpiry} />
                    </td>

                    <td className="px-4 py-3">
                      <ConnectionBadge status={m.connectionStatus} />
                      {m.lastError && m.connectionStatus !== 'CONNECTED' && (
                        <div
                          className="mt-1 text-[10px] text-[rgba(0,0,0,0.4)] truncate max-w-[16rem]"
                          title={m.lastError}
                        >
                          {m.lastError}
                        </div>
                      )}
                    </td>

                    <td className="px-4 py-3">
                      <EntitlementSwitch
                        enabled={m.entitled} disabled={busy}
                        onChange={next => { void setEntitlement(m.clientId, next); }}
                      />
                    </td>

                    <td className="px-4 py-3">
                      <div className="flex items-center justify-end gap-1">
                        <button
                          onClick={() => { void handleVerify(m); }}
                          disabled={busy}
                          title="Test the stored credential against Matters"
                          className="inline-flex items-center gap-1 px-2 py-1.5 text-[11px] font-semibold text-[rgba(0,0,0,0.6)] border border-[rgba(0,0,0,0.12)] rounded-lg hover:bg-[rgba(0,0,0,0.04)] disabled:opacity-40 transition-colors"
                        >
                          <Icon
                            icon={busy ? 'mdi:loading' : 'mdi:lan-connect'} width={12}
                            className={busy ? 'animate-spin' : ''}
                          />
                          Verify
                        </button>
                        <button
                          onClick={() => openEditor(m.clientId, m.clientName, m)}
                          title="Edit mapping"
                          className="p-1.5 rounded-lg text-[rgba(0,0,0,0.45)] hover:bg-[rgba(0,0,0,0.05)] transition-colors"
                        >
                          <Icon icon="mdi:pencil-outline" width={14} />
                        </button>
                        <button
                          onClick={() => setPendingDelete(m)}
                          title="Remove mapping"
                          className="p-1.5 rounded-lg text-[rgba(0,0,0,0.45)] hover:bg-rose-50 hover:text-rose-600 transition-colors"
                        >
                          <Icon icon="mdi:trash-can-outline" width={14} />
                        </button>
                      </div>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      </section>

      <DspmAuditTable />

      {/* ── Client picker ── */}
      {picking && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
          <div className="fixed inset-0 bg-black/40 backdrop-blur-sm" onClick={() => setPicking(false)} />
          <div
            className="relative z-10 w-full max-w-md max-h-[70vh] flex flex-col bg-white border border-[rgba(0,0,0,0.12)] rounded-2xl shadow-xl"
            onClick={e => e.stopPropagation()}
          >
            <div className="flex items-center gap-3 px-5 py-4 border-b border-[rgba(0,0,0,0.06)]">
              <h2 className="text-sm font-bold text-[rgba(0,0,0,0.88)]">Select a customer</h2>
              <button
                onClick={() => setPicking(false)}
                className="ml-auto p-2 rounded-lg hover:bg-[rgba(0,0,0,0.05)] text-[rgba(0,0,0,0.4)]"
                aria-label="Close"
              >
                <Icon icon="mdi:close" width={16} />
              </button>
            </div>
            <div className="flex-1 overflow-y-auto p-2">
              {loadingClients && (
                <p className="px-3 py-6 text-center text-xs text-[rgba(0,0,0,0.4)]">Loading customers…</p>
              )}
              {!loadingClients && unmappedClients.length === 0 && (
                <p className="px-3 py-6 text-center text-xs text-[rgba(0,0,0,0.4)]">
                  Every customer already has a mapping. Edit an existing one instead.
                </p>
              )}
              {!loadingClients && unmappedClients.map(c => (
                <button
                  key={c.clientId}
                  onClick={() => openEditor(c.clientId, c.clientName, null)}
                  className="w-full flex items-center gap-2 px-3 py-2.5 text-left rounded-lg hover:bg-[rgba(0,0,0,0.04)] transition-colors"
                >
                  <Icon icon="mdi:domain" width={14} className="text-[rgba(0,0,0,0.3)]" />
                  <span className="flex-1 text-xs text-[rgba(0,0,0,0.8)] truncate">
                    {c.clientName ?? `Client ${c.clientId}`}
                  </span>
                  {c.entitled && (
                    <span className="px-1.5 py-0.5 rounded bg-[rgba(0,0,0,0.05)] text-[10px] font-semibold text-[rgba(0,0,0,0.5)]">
                      add-on on
                    </span>
                  )}
                </button>
              ))}
            </div>
          </div>
        </div>
      )}

      {editing && (
        <DspmMappingModal
          clientId={editing.clientId}
          clientName={editing.clientName}
          existing={editing.existing}
          saving={busyClientId === editing.clientId}
          onCancel={() => setEditing(null)}
          onSave={handleSave}
        />
      )}
    </div>
  );
};

export default DspmAdminPage;
