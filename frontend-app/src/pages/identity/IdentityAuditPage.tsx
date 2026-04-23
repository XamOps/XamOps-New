import React, { useEffect, useState, useCallback } from 'react';
import { useTheme } from '../../contexts/ThemeContext';
import { listAuditLogs, type AuditLogEntry, type PagedAuditLog } from '../../api/identityApi';
import clsx from 'clsx';
import '../../styles/accessops.css';

// ── Helpers ────────────────────────────────────────────────────────────────────

const ACTION_META: Record<string, { label: string; icon: string; color: string }> = {
  USER_INVITED:           { label: 'User Invited',         icon: 'fas fa-user-plus',    color: 'ap-badge-green' },
  INVITE_INITIATED:       { label: 'Invite Initiated',     icon: 'fas fa-paper-plane',  color: 'ap-badge-blue'  },
  USER_DEACTIVATED:       { label: 'User Deactivated',     icon: 'fas fa-user-slash',   color: 'ap-badge-red'   },
  DEACTIVATE_INITIATED:   { label: 'Deactivate Initiated', icon: 'fas fa-ban',          color: 'ap-badge-red'   },
  USER_ADDED_TO_GROUP:    { label: 'Added to Group',       icon: 'fas fa-user-check',   color: 'ap-badge-green' },
  USER_REMOVED_FROM_GROUP:{ label: 'Removed from Group',   icon: 'fas fa-user-minus',   color: 'ap-badge-yellow'},
  TENANT_PROVISIONED:     { label: 'Tenant Provisioned',   icon: 'fas fa-building',     color: 'ap-badge-blue'  },
};

function formatRelativeTime(timestamp: string): string {
  const diff = Date.now() - new Date(timestamp).getTime();
  const m = Math.floor(diff / 60000);
  if (m < 1) return 'just now';
  if (m < 60) return `${m}m ago`;
  const h = Math.floor(m / 60);
  if (h < 24) return `${h}h ago`;
  const d = Math.floor(h / 24);
  return `${d}d ago`;
}

function downloadCsv(entries: AuditLogEntry[]) {
  const headers = ['ID', 'Action', 'Actor User ID', 'Target Email', 'Group', 'Timestamp'];
  const rows = entries.map(e => [
    e.id,
    e.action,
    e.actorUserId,
    e.targetUserEmail ?? '',
    e.groupName ?? '',
    new Date(e.timestamp).toISOString(),
  ]);
  const csv = [headers, ...rows].map(r => r.join(',')).join('\n');
  const blob = new Blob([csv], { type: 'text/csv' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = `identity-audit-${Date.now()}.csv`;
  a.click();
  URL.revokeObjectURL(url);
}

// ── Main Page ──────────────────────────────────────────────────────────────────

const IdentityAuditPage: React.FC = () => {
  const { theme } = useTheme();
  const [data, setData] = useState<PagedAuditLog | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [page, setPage] = useState(0);
  const [actionFilter, setActionFilter] = useState('all');
  const PAGE_SIZE = 50;
  const isDark = theme === 'dark';

  const load = useCallback(async (p: number) => {
    setLoading(true);
    setError(null);
    try {
      const result = await listAuditLogs(p, PAGE_SIZE);
      setData(result);
    } catch (e: any) {
      setError(e.message ?? 'Failed to load audit log.');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { load(page); }, [load, page]);

  const entries = data?.content ?? [];

  const filtered = actionFilter === 'all'
    ? entries
    : entries.filter(e => e.action === actionFilter);

  const uniqueActions = [...new Set(entries.map(e => e.action))].sort();

  return (
    <div className={clsx('accessops-page', isDark ? 'ap-dark' : 'ap-light')}>

      {/* Header */}
      <div className="ap-header">
        <div>
          <h1 className={clsx('ap-page-title', isDark ? 'text-slate-100' : 'text-gray-900')}>
            Audit Log
          </h1>
          <p className={clsx('ap-page-sub', isDark ? 'text-slate-400' : 'text-gray-500')}>
            Immutable record of all identity management actions
          </p>
        </div>
        <button
          className={clsx('btn-secondary', isDark ? 'btn-secondary-dark' : '')}
          onClick={() => entries.length > 0 && downloadCsv(entries)}
          disabled={entries.length === 0}
        >
          <i className="fas fa-download mr-2" />
          Export CSV
        </button>
      </div>

      {error && (
        <div className="ap-error">
          <i className="fas fa-exclamation-triangle mr-2" />{error}
        </div>
      )}

      {/* Table Card */}
      <div className={clsx('ap-table-card', isDark ? 'ap-card-dark' : 'ap-card-light')}>

        {/* Filter */}
        <div className="ap-filters">
          <select
            className={clsx('ap-select', isDark ? 'ap-select-dark' : 'ap-select-light')}
            value={actionFilter}
            onChange={e => setActionFilter(e.target.value)}
          >
            <option value="all">All Actions</option>
            {uniqueActions.map(a => (
              <option key={a} value={a}>
                {ACTION_META[a]?.label ?? a}
              </option>
            ))}
          </select>

          {data && (
            <span className={clsx('text-sm ml-auto', isDark ? 'text-slate-400' : 'text-gray-500')}>
              {data.totalElements.toLocaleString()} total entries
            </span>
          )}
        </div>

        {/* Table */}
        <div className="overflow-x-auto">
          <table className="ap-table">
            <thead className={clsx('ap-thead', isDark ? 'ap-thead-dark' : 'ap-thead-light')}>
              <tr>
                <th>Action</th>
                <th>Target User</th>
                <th>Group</th>
                <th>When</th>
              </tr>
            </thead>
            <tbody className={clsx('divide-y', isDark ? 'divide-slate-700' : 'divide-gray-100')}>
              {loading ? (
                [1, 2, 3, 4, 5].map(i => (
                  <tr key={i}>
                    {[1, 2, 3, 4].map(j => (
                      <td key={j} className="px-6 py-4">
                        <div className="skeleton-block h-4 rounded w-3/4" />
                      </td>
                    ))}
                  </tr>
                ))
              ) : filtered.length === 0 ? (
                <tr>
                  <td colSpan={4} className="ap-empty">
                    <i className="fas fa-clipboard-list text-3xl mb-3 opacity-30" />
                    <p>No audit entries found</p>
                  </td>
                </tr>
              ) : (
                filtered.map(entry => {
                  const meta = ACTION_META[entry.action] ?? {
                    label: entry.action,
                    icon: 'fas fa-circle',
                    color: 'ap-badge-blue',
                  };
                  return (
                    <tr key={entry.id} className={clsx('ap-tr', isDark ? 'ap-tr-dark' : 'ap-tr-light')}>
                      <td className="px-6 py-4">
                        <span className={clsx('ap-badge', meta.color)}>
                          <i className={clsx(meta.icon, 'mr-1.5 text-xs')} />
                          {meta.label}
                        </span>
                      </td>
                      <td className={clsx('px-6 py-4 text-sm', isDark ? 'text-slate-300' : 'text-gray-700')}>
                        {entry.targetUserEmail ?? <span className="text-gray-400">—</span>}
                      </td>
                      <td className="px-6 py-4">
                        {entry.groupName
                          ? (
                            <span className={clsx('ap-tag', isDark ? 'ap-tag-dark' : 'ap-tag-light')}>
                              {entry.groupName}
                            </span>
                          )
                          : <span className={clsx('text-sm', isDark ? 'text-slate-500' : 'text-gray-400')}>—</span>
                        }
                      </td>
                      <td className="px-6 py-4">
                        <div>
                          <p className={clsx('text-sm', isDark ? 'text-slate-300' : 'text-gray-700')}>
                            {formatRelativeTime(entry.timestamp)}
                          </p>
                          <p className={clsx('text-xs mt-0.5', isDark ? 'text-slate-500' : 'text-gray-400')}>
                            {new Date(entry.timestamp).toLocaleString()}
                          </p>
                        </div>
                      </td>
                    </tr>
                  );
                })
              )}
            </tbody>
          </table>
        </div>

        {/* Pagination */}
        {data && data.totalPages > 1 && (
          <div className={clsx('ap-pagination', isDark ? 'border-slate-700' : 'border-gray-100')}>
            <button
              className={clsx('ap-page-btn', isDark ? 'ap-page-dark' : 'ap-page-light')}
              onClick={() => setPage(p => Math.max(0, p - 1))}
              disabled={page === 0 || loading}
            >
              <i className="fas fa-chevron-left" />
            </button>
            <span className={clsx('text-sm px-4', isDark ? 'text-slate-400' : 'text-gray-600')}>
              Page {page + 1} of {data.totalPages}
            </span>
            <button
              className={clsx('ap-page-btn', isDark ? 'ap-page-dark' : 'ap-page-light')}
              onClick={() => setPage(p => Math.min(data.totalPages - 1, p + 1))}
              disabled={page >= data.totalPages - 1 || loading}
            >
              <i className="fas fa-chevron-right" />
            </button>
          </div>
        )}
      </div>
    </div>
  );
};

export default IdentityAuditPage;
