import React, { useEffect, useState } from 'react';
import { Icon } from '@iconify/react';
import { useDspmAudit, type DspmAuditFilters } from '../../hooks/useDspmAdmin';
import type { DspmAuditView } from '../../types/dspm';

/**
 * Actions that change configuration, as opposed to the one that records a read.
 *
 * The audit filter defaults to these. `CREDENTIAL_USED` is produced on every customer page load and
 * would bury the handful of configuration changes an operator came to review — but it is still
 * selectable, because "who read this customer's inventory" is the other question the trail exists to
 * answer.
 */
const MUTATION_ACTIONS = [
  'MAPPING_CREATED', 'MAPPING_UPDATED', 'MAPPING_DELETED', 'MAPPING_VERIFIED',
  'KEY_ROTATED', 'PROJECT_REPOINTED', 'ENTITLEMENT_GRANTED', 'ENTITLEMENT_WITHDRAWN',
];

const ACTION_LABELS: Record<string, string> = {
  CREDENTIAL_USED: 'Data read',
  MAPPING_CREATED: 'Mapping created',
  MAPPING_UPDATED: 'Mapping updated',
  MAPPING_DELETED: 'Mapping deleted',
  MAPPING_VERIFIED: 'Connection verified',
  KEY_ROTATED: 'Key rotated',
  PROJECT_REPOINTED: 'Project repointed',
  ENTITLEMENT_GRANTED: 'Add-on granted',
  ENTITLEMENT_WITHDRAWN: 'Add-on withdrawn',
};

/** Actions worth drawing the eye to. A repoint or a rotation is what an audit review is looking for. */
const NOTABLE_ACTIONS = new Set(['PROJECT_REPOINTED', 'KEY_ROTATED', 'MAPPING_DELETED']);

const ACTOR_META: Record<string, { icon: string; cls: string }> = {
  OPERATOR: { icon: 'mdi:shield-account-outline', cls: 'text-amber-700' },
  CUSTOMER: { icon: 'mdi:account-outline', cls: 'text-[rgba(0,0,0,0.55)]' },
  SYSTEM: { icon: 'mdi:cog-outline', cls: 'text-[rgba(0,0,0,0.4)]' },
};

const formatWhen = (iso: string) => {
  const d = new Date(iso);
  return d.toLocaleString(undefined, {
    year: 'numeric', month: 'short', day: '2-digit', hour: '2-digit', minute: '2-digit',
  });
};

const ActionCell: React.FC<{ row: DspmAuditView }> = ({ row }) => {
  const label = ACTION_LABELS[row.action] ?? row.action;
  const notable = NOTABLE_ACTIONS.has(row.action);
  return (
    <span className={`text-xs ${notable ? 'font-semibold text-[rgba(0,0,0,0.88)]' : 'text-[rgba(0,0,0,0.7)]'}`}>
      {label}
    </span>
  );
};

const PAGE_SIZE = 50;

/**
 * The DSPM access audit trail.
 *
 * This is the replacement for the CloudTrail we gave up by encrypting keys in-application rather than
 * holding them in Secrets Manager. It is the only record of who caused a customer's sensitive-data
 * inventory to be read, and of every configuration change behind it.
 */
const DspmAuditTable: React.FC = () => {
  const { audit, loading, error, loadAudit } = useDspmAudit();

  const [filters, setFilters] = useState<DspmAuditFilters>({ days: 30 });
  const [page, setPage] = useState(0);
  const [showReads, setShowReads] = useState(false);

  useEffect(() => {
    // An empty `action` means everything; the mutation-only default is expressed by asking for reads to
    // be excluded, which the API cannot do directly — so when reads are hidden we page through the
    // mutation actions one filter at a time is not viable. Instead the default asks for no action filter
    // and hides read rows client-side only when the operator has not chosen a specific action.
    void loadAudit(filters, page, PAGE_SIZE);
  }, [loadAudit, filters, page]);

  const rows = (audit?.data ?? []).filter(row =>
    showReads || filters.action ? true : row.action !== 'CREDENTIAL_USED');

  return (
    <section className="bg-white border border-[rgba(0,0,0,0.08)] rounded-2xl overflow-hidden">
      <header className="flex flex-wrap items-center gap-3 px-5 py-4 border-b border-[rgba(0,0,0,0.06)]">
        <div className="flex items-center gap-2">
          <Icon icon="mdi:history" width={16} className="text-[rgba(0,0,0,0.5)]" />
          <h2 className="text-sm font-bold text-[rgba(0,0,0,0.88)]">Access audit</h2>
        </div>

        <div className="ml-auto flex flex-wrap items-center gap-2">
          <select
            value={filters.action ?? ''}
            onChange={e => { setPage(0); setFilters(f => ({ ...f, action: e.target.value || null })); }}
            className="px-2.5 py-1.5 text-xs bg-[rgba(0,0,0,0.03)] border border-[rgba(0,0,0,0.08)] rounded-lg text-[rgba(0,0,0,0.7)] focus:outline-none focus:ring-1 focus:ring-[rgba(0,0,0,0.2)]"
            aria-label="Filter by action"
          >
            <option value="">All changes</option>
            {MUTATION_ACTIONS.map(a => (
              <option key={a} value={a}>{ACTION_LABELS[a]}</option>
            ))}
            <option value="CREDENTIAL_USED">Data reads</option>
          </select>

          <select
            value={filters.outcome ?? ''}
            onChange={e => { setPage(0); setFilters(f => ({ ...f, outcome: e.target.value || null })); }}
            className="px-2.5 py-1.5 text-xs bg-[rgba(0,0,0,0.03)] border border-[rgba(0,0,0,0.08)] rounded-lg text-[rgba(0,0,0,0.7)] focus:outline-none focus:ring-1 focus:ring-[rgba(0,0,0,0.2)]"
            aria-label="Filter by outcome"
          >
            <option value="">Any outcome</option>
            <option value="SUCCESS">Success</option>
            <option value="FAILURE">Failure</option>
          </select>

          <select
            value={String(filters.days ?? '')}
            onChange={e => {
              setPage(0);
              setFilters(f => ({ ...f, days: e.target.value ? Number(e.target.value) : null }));
            }}
            className="px-2.5 py-1.5 text-xs bg-[rgba(0,0,0,0.03)] border border-[rgba(0,0,0,0.08)] rounded-lg text-[rgba(0,0,0,0.7)] focus:outline-none focus:ring-1 focus:ring-[rgba(0,0,0,0.2)]"
            aria-label="Time window"
          >
            <option value="7">Last 7 days</option>
            <option value="30">Last 30 days</option>
            <option value="90">Last 90 days</option>
            <option value="">All time</option>
          </select>

          {!filters.action && (
            <label className="flex items-center gap-1.5 text-xs text-[rgba(0,0,0,0.55)] cursor-pointer">
              <input
                type="checkbox" checked={showReads}
                onChange={e => setShowReads(e.target.checked)}
                className="accent-[rgba(0,0,0,0.88)]"
              />
              Include reads
            </label>
          )}
        </div>
      </header>

      {error && (
        <div className="flex items-center gap-2 px-5 py-3 bg-rose-50 text-xs text-rose-700">
          <Icon icon="mdi:alert-circle-outline" width={14} />
          {error}
        </div>
      )}

      <div className="overflow-x-auto">
        <table className="w-full text-left">
          <thead>
            <tr className="border-b border-[rgba(0,0,0,0.06)] bg-[rgba(0,0,0,0.015)]">
              {['When', 'Customer', 'Action', 'Actor', 'Detail', 'Outcome'].map(h => (
                <th key={h} className="px-4 py-2.5 text-[10px] font-semibold uppercase tracking-wider text-[rgba(0,0,0,0.45)] whitespace-nowrap">
                  {h}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {loading && (
              <tr><td colSpan={6} className="px-4 py-10 text-center text-xs text-[rgba(0,0,0,0.4)]">
                Loading audit trail…
              </td></tr>
            )}

            {!loading && rows.length === 0 && (
              <tr><td colSpan={6} className="px-4 py-10 text-center text-xs text-[rgba(0,0,0,0.4)]">
                No audit entries for this filter.
              </td></tr>
            )}

            {!loading && rows.map(row => {
              const actor = ACTOR_META[row.actorType ?? 'SYSTEM'] ?? ACTOR_META.SYSTEM;
              return (
                <tr key={row.id} className="border-b border-[rgba(0,0,0,0.04)] hover:bg-[rgba(0,0,0,0.015)]">
                  <td className="px-4 py-2.5 text-xs text-[rgba(0,0,0,0.55)] whitespace-nowrap">
                    {formatWhen(row.createdAt)}
                  </td>
                  <td className="px-4 py-2.5 text-xs text-[rgba(0,0,0,0.7)]">
                    {/* A row whose client has since been deleted still matters — that sequence is
                        exactly what an audit log exists to preserve — so it renders by id. */}
                    {row.clientName ?? (row.clientId ? `Client ${row.clientId}` : '—')}
                  </td>
                  <td className="px-4 py-2.5 whitespace-nowrap">
                    <ActionCell row={row} />
                  </td>
                  <td className="px-4 py-2.5 whitespace-nowrap">
                    <span className={`inline-flex items-center gap-1 text-xs ${actor.cls}`}>
                      <Icon icon={actor.icon} width={12} />
                      {row.actorUsername ?? row.actorType ?? 'system'}
                      {row.impersonated && (
                        <span
                          title="Acting through an impersonated session"
                          className="ml-1 px-1.5 py-0.5 rounded bg-amber-50 border border-amber-200 text-amber-700 text-[10px] font-semibold"
                        >
                          impersonated
                        </span>
                      )}
                    </span>
                  </td>
                  <td className="px-4 py-2.5 text-xs text-[rgba(0,0,0,0.5)] max-w-[22rem]">
                    <span className="block truncate" title={row.detail ?? row.endpoint ?? undefined}>
                      {row.detail ?? row.endpoint ?? '—'}
                    </span>
                  </td>
                  <td className="px-4 py-2.5 whitespace-nowrap">
                    {row.outcome === 'SUCCESS' ? (
                      <span className="inline-flex items-center gap-1 text-xs text-[rgba(0,0,0,0.45)]">
                        <Icon icon="mdi:check" width={12} />
                        OK
                      </span>
                    ) : (
                      <span
                        className="inline-flex items-center gap-1 text-xs font-semibold text-rose-700"
                        title={row.failureReason ?? undefined}
                      >
                        <Icon icon="mdi:close-circle-outline" width={12} />
                        {row.failureReason ?? 'Failed'}
                      </span>
                    )}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>

      {audit && audit.totalPages > 1 && (
        <div className="flex items-center justify-between px-5 py-3 border-t border-[rgba(0,0,0,0.06)] text-xs text-[rgba(0,0,0,0.5)]">
          <span>
            Page {audit.page + 1} of {audit.totalPages} · {audit.total.toLocaleString()} entries
          </span>
          <div className="flex gap-2">
            <button
              disabled={audit.page === 0} onClick={() => setPage(p => Math.max(0, p - 1))}
              className="px-3 py-1 border border-[rgba(0,0,0,0.12)] rounded-lg disabled:opacity-40 hover:bg-[rgba(0,0,0,0.03)]"
            >
              Prev
            </button>
            <button
              disabled={audit.page + 1 >= audit.totalPages} onClick={() => setPage(p => p + 1)}
              className="px-3 py-1 border border-[rgba(0,0,0,0.12)] rounded-lg disabled:opacity-40 hover:bg-[rgba(0,0,0,0.03)]"
            >
              Next
            </button>
          </div>
        </div>
      )}
    </section>
  );
};

export default DspmAuditTable;
