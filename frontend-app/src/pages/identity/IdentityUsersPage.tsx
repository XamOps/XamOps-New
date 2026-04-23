import React, { useEffect, useState, useCallback } from 'react';
import { useTheme } from '../../contexts/ThemeContext';
import {
  listUsers, listGroups, inviteUser, deactivateUser,
  type IdentityUser, type IdentityGroup, type InviteUserPayload,
} from '../../api/identityApi';
import clsx from 'clsx';
import '../../styles/accessops.css';

// ── Skeleton ───────────────────────────────────────────────────────────────────

const RowSkeleton = () => (
  <tr>
    {[1, 2, 3, 4, 5].map(i => (
      <td key={i} className="px-6 py-4">
        <div className="skeleton-block h-4 rounded w-3/4" />
      </td>
    ))}
  </tr>
);

// ── Invite Modal ───────────────────────────────────────────────────────────────

interface InviteModalProps {
  groups: IdentityGroup[];
  onClose: () => void;
  onSuccess: () => void;
  theme: string;
}

const InviteModal: React.FC<InviteModalProps> = ({ groups, onClose, onSuccess, theme }) => {
  const [form, setForm] = useState<InviteUserPayload>({ name: '', email: '', groupId: '' });
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setSubmitting(true);
    setError(null);
    try {
      await inviteUser(form);
      onSuccess();
      onClose();
    } catch (err: any) {
      setError(err.message ?? 'Failed to invite user.');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div
        className={clsx('modal-card', theme === 'dark' ? 'modal-dark' : 'modal-light')}
        onClick={e => e.stopPropagation()}
      >
        <div className="modal-header">
          <h3 className="modal-title">Invite User</h3>
          <button className="modal-close" onClick={onClose}>✕</button>
        </div>

        <p className={clsx('modal-subtitle', theme === 'dark' ? 'text-slate-400' : 'text-gray-500')}>
          The user will receive an email to set their own password.
          AWS access is granted automatically based on the selected group.
        </p>

        <form onSubmit={handleSubmit} className="modal-form">
          <div className="form-group">
            <label className="form-label">Full Name</label>
            <input
              className={clsx('form-input', theme === 'dark' ? 'input-dark' : 'input-light')}
              type="text"
              placeholder="Jane Doe"
              value={form.name}
              onChange={e => setForm(f => ({ ...f, name: e.target.value }))}
              required
            />
          </div>

          <div className="form-group">
            <label className="form-label">Work Email</label>
            <input
              className={clsx('form-input', theme === 'dark' ? 'input-dark' : 'input-light')}
              type="email"
              placeholder="jane@company.com"
              value={form.email}
              onChange={e => setForm(f => ({ ...f, email: e.target.value }))}
              required
            />
          </div>

          <div className="form-group">
            <label className="form-label">Add to Group</label>
            <select
              className={clsx('form-input', theme === 'dark' ? 'input-dark' : 'input-light')}
              value={form.groupId}
              onChange={e => setForm(f => ({ ...f, groupId: e.target.value }))}
              required
            >
              <option value="">Select a group…</option>
              {groups.map(g => (
                <option key={g.pk} value={g.pk}>
                  {g.name}
                  {g.awsAccountName ? ` — ${g.awsAccountName}` : ''}
                </option>
              ))}
            </select>
          </div>

          {error && <p className="form-error">{error}</p>}

          <div className="modal-actions">
            <button
              type="button"
              className={clsx('btn-secondary', theme === 'dark' ? 'btn-secondary-dark' : '')}
              onClick={onClose}
            >
              Cancel
            </button>
            <button
              type="submit"
              className="btn-primary"
              disabled={submitting}
            >
              {submitting ? 'Sending invite…' : 'Invite User'}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
};

// ── Deactivate Confirmation Modal ──────────────────────────────────────────────

interface DeactivateModalProps {
  user: IdentityUser;
  onClose: () => void;
  onConfirm: () => void;
  theme: string;
}

const DeactivateModal: React.FC<DeactivateModalProps> = ({ user, onClose, onConfirm, theme }) => {
  const [confirming, setConfirming] = useState(false);

  const handleConfirm = async () => {
    setConfirming(true);
    await onConfirm();
    setConfirming(false);
  };

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div
        className={clsx('modal-card modal-card-sm', theme === 'dark' ? 'modal-dark' : 'modal-light')}
        onClick={e => e.stopPropagation()}
      >
        <div className="modal-header">
          <h3 className="modal-title text-red-500">Deactivate User</h3>
          <button className="modal-close" onClick={onClose}>✕</button>
        </div>
        <p className={clsx('modal-subtitle', theme === 'dark' ? 'text-slate-300' : 'text-gray-700')}>
          Deactivating <strong>{user.email}</strong> will immediately revoke their login access.
          AWS console access will be automatically removed via SCIM within seconds.
        </p>
        <p className={clsx('mt-2 text-sm', theme === 'dark' ? 'text-slate-400' : 'text-gray-500')}>
          This action cannot be undone without manual re-activation.
        </p>
        <div className="modal-actions mt-6">
          <button
            className={clsx('btn-secondary', theme === 'dark' ? 'btn-secondary-dark' : '')}
            onClick={onClose}
          >
            Cancel
          </button>
          <button
            className="btn-danger"
            onClick={handleConfirm}
            disabled={confirming}
          >
            {confirming ? 'Deactivating…' : 'Deactivate'}
          </button>
        </div>
      </div>
    </div>
  );
};

// ── Main Page ──────────────────────────────────────────────────────────────────

const IdentityUsersPage: React.FC = () => {
  const { theme } = useTheme();
  const [users, setUsers] = useState<IdentityUser[]>([]);
  const [groups, setGroups] = useState<IdentityGroup[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [search, setSearch] = useState('');
  const [statusFilter, setStatusFilter] = useState<'all' | 'active' | 'inactive'>('all');
  const [showInviteModal, setShowInviteModal] = useState(false);
  const [deactivateTarget, setDeactivateTarget] = useState<IdentityUser | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [u, g] = await Promise.all([listUsers(), listGroups()]);
      setUsers(u);
      setGroups(g);
    } catch (e: any) {
      setError(e.message ?? 'Failed to load identity data.');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { load(); }, [load]);

  const handleDeactivate = async () => {
    if (!deactivateTarget) return;
    try {
      await deactivateUser(deactivateTarget.pk);
      setDeactivateTarget(null);
      await load();
    } catch (e: any) {
      setError(e.message ?? 'Failed to deactivate user.');
      setDeactivateTarget(null);
    }
  };

  const filtered = users.filter(u => {
    const matchesSearch =
      !search ||
      u.name.toLowerCase().includes(search.toLowerCase()) ||
      u.email.toLowerCase().includes(search.toLowerCase());
    const matchesStatus =
      statusFilter === 'all' ||
      (statusFilter === 'active' && u.isActive) ||
      (statusFilter === 'inactive' && !u.isActive);
    return matchesSearch && matchesStatus;
  });

  const total = users.length;
  const active = users.filter(u => u.isActive).length;
  const inactive = users.filter(u => !u.isActive).length;

  const isDark = theme === 'dark';

  return (
    <div className={clsx('accessops-page', isDark ? 'ap-dark' : 'ap-light')}>

      {/* Header */}
      <div className="ap-header">
        <div>
          <h1 className={clsx('ap-page-title', isDark ? 'text-slate-100' : 'text-gray-900')}>
            Users
          </h1>
          <p className={clsx('ap-page-sub', isDark ? 'text-slate-400' : 'text-gray-500')}>
            Manage your team's identity and AWS access
          </p>
        </div>
        <button
          className="btn-primary"
          onClick={() => setShowInviteModal(true)}
        >
          <i className="fas fa-user-plus mr-2" />
          Invite User
        </button>
      </div>

      {/* KPI Cards */}
      <div className="ap-kpi-row">
        {[
          { label: 'Total Users', value: total, icon: 'fas fa-users', color: 'text-indigo-400' },
          { label: 'Active', value: active, icon: 'fas fa-check-circle', color: 'text-emerald-400' },
          { label: 'Inactive', value: inactive, icon: 'fas fa-ban', color: 'text-rose-400' },
        ].map(card => (
          <div key={card.label} className={clsx('ap-kpi-card', isDark ? 'ap-kpi-dark' : 'ap-kpi-light')}>
            <div className="flex-1">
              <p className={clsx('ap-kpi-label', isDark ? 'text-slate-400' : 'text-gray-500')}>
                {card.label}
              </p>
              <p className={clsx('ap-kpi-value', isDark ? 'text-slate-100' : 'text-gray-900')}>
                {loading ? '–' : card.value}
              </p>
            </div>
            <i className={clsx(card.icon, 'text-2xl', card.color)} />
          </div>
        ))}
      </div>

      {/* Error */}
      {error && (
        <div className="ap-error">
          <i className="fas fa-exclamation-triangle mr-2" />
          {error}
        </div>
      )}

      {/* Table Card */}
      <div className={clsx('ap-table-card', isDark ? 'ap-card-dark' : 'ap-card-light')}>

        {/* Filters */}
        <div className="ap-filters">
          <div className="ap-search-wrap">
            <i className={clsx('fas fa-search ap-search-icon', isDark ? 'text-slate-500' : 'text-gray-400')} />
            <input
              className={clsx('ap-search', isDark ? 'ap-search-dark' : 'ap-search-light')}
              type="text"
              placeholder="Search by name or email…"
              value={search}
              onChange={e => setSearch(e.target.value)}
            />
          </div>
          <select
            className={clsx('ap-select', isDark ? 'ap-select-dark' : 'ap-select-light')}
            value={statusFilter}
            onChange={e => setStatusFilter(e.target.value as any)}
          >
            <option value="all">All Status</option>
            <option value="active">Active</option>
            <option value="inactive">Inactive</option>
          </select>
        </div>

        {/* Table */}
        <div className="overflow-x-auto">
          <table className="ap-table">
            <thead className={clsx('ap-thead', isDark ? 'ap-thead-dark' : 'ap-thead-light')}>
              <tr>
                <th>User</th>
                <th>Email</th>
                <th>Status</th>
                <th>Groups</th>
                <th>Last Login</th>
                <th className="text-right">Action</th>
              </tr>
            </thead>
            <tbody className={clsx('divide-y', isDark ? 'divide-slate-700' : 'divide-gray-100')}>
              {loading ? (
                [1, 2, 3, 4, 5].map(i => <RowSkeleton key={i} />)
              ) : filtered.length === 0 ? (
                <tr>
                  <td colSpan={6} className="ap-empty">
                    <i className="fas fa-users text-3xl mb-3 opacity-30" />
                    <p>No users found</p>
                  </td>
                </tr>
              ) : (
                filtered.map(user => (
                  <tr
                    key={user.pk}
                    className={clsx('ap-tr', isDark ? 'ap-tr-dark' : 'ap-tr-light')}
                  >
                    <td className="px-6 py-4">
                      <div className="ap-user-cell">
                        <div className={clsx('ap-avatar', isDark ? 'ap-avatar-dark' : 'ap-avatar-light')}>
                          {user.name.charAt(0).toUpperCase()}
                        </div>
                        <span className={clsx('font-medium text-sm', isDark ? 'text-slate-200' : 'text-gray-900')}>
                          {user.name}
                        </span>
                      </div>
                    </td>
                    <td className={clsx('px-6 py-4 text-sm', isDark ? 'text-slate-300' : 'text-gray-700')}>
                      {user.email}
                    </td>
                    <td className="px-6 py-4">
                      <span className={clsx('ap-badge', user.isActive ? 'ap-badge-green' : 'ap-badge-red')}>
                        <span className={clsx('ap-badge-dot', user.isActive ? 'bg-emerald-400' : 'bg-rose-400')} />
                        {user.isActive ? 'Active' : 'Inactive'}
                      </span>
                    </td>
                    <td className="px-6 py-4">
                      <div className="flex flex-wrap gap-1">
                        {user.groupsObj?.map(g => (
                          <span
                            key={g.pk}
                            className={clsx('ap-tag', isDark ? 'ap-tag-dark' : 'ap-tag-light')}
                          >
                            {g.name}
                          </span>
                        )) ?? <span className="text-sm text-gray-400">—</span>}
                      </div>
                    </td>
                    <td className={clsx('px-6 py-4 text-sm', isDark ? 'text-slate-400' : 'text-gray-500')}>
                      {user.lastLogin
                        ? new Date(user.lastLogin).toLocaleDateString()
                        : 'Never'}
                    </td>
                    <td className="px-6 py-4 text-right">
                      {user.isActive && (
                        <button
                          className="ap-deactivate-btn"
                          onClick={() => setDeactivateTarget(user)}
                          title="Deactivate user"
                        >
                          <i className="fas fa-user-slash mr-1" />
                          Deactivate
                        </button>
                      )}
                    </td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>
      </div>

      {/* Modals */}
      {showInviteModal && (
        <InviteModal
          groups={groups}
          theme={theme}
          onClose={() => setShowInviteModal(false)}
          onSuccess={load}
        />
      )}
      {deactivateTarget && (
        <DeactivateModal
          user={deactivateTarget}
          theme={theme}
          onClose={() => setDeactivateTarget(null)}
          onConfirm={handleDeactivate}
        />
      )}
    </div>
  );
};

export default IdentityUsersPage;
