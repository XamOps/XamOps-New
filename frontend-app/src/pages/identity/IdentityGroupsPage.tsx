import React, { useEffect, useState, useCallback } from 'react';
import { useTheme } from '../../contexts/ThemeContext';
import {
  listGroups, listUsers, addUserToGroup, removeUserFromGroup,
  type IdentityGroup, type IdentityUser,
} from '../../api/identityApi';
import clsx from 'clsx';
import '../../styles/accessops.css';

// ── Add User to Group modal ────────────────────────────────────────────────────

interface AddUserModalProps {
  group: IdentityGroup;
  allUsers: IdentityUser[];
  theme: string;
  onClose: () => void;
  onSuccess: () => void;
}

const AddUserModal: React.FC<AddUserModalProps> = ({ group, allUsers, theme, onClose, onSuccess }) => {
  const [selectedPk, setSelectedPk] = useState<number | ''>('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const currentMemberPks = new Set(group.usersObj?.map(u => u.pk) ?? []);
  const eligible = allUsers.filter(u => u.isActive && !currentMemberPks.has(u.pk));

  const handleAdd = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!selectedPk) return;
    setSubmitting(true);
    setError(null);
    try {
      await addUserToGroup(group.pk, selectedPk as number);
      onSuccess();
      onClose();
    } catch (err: any) {
      setError(err.message ?? 'Failed to add user to group.');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div
        className={clsx('modal-card modal-card-sm', theme === 'dark' ? 'modal-dark' : 'modal-light')}
        onClick={e => e.stopPropagation()}
      >
        <div className="modal-header">
          <h3 className="modal-title">Add User to {group.name}</h3>
          <button className="modal-close" onClick={onClose}>✕</button>
        </div>

        {group.awsAccountName && (
          <div className={clsx('ap-info-banner', theme === 'dark' ? 'ap-info-dark' : 'ap-info-light')}>
            <i className="fab fa-aws mr-2" />
            Adding this user will grant <strong>{group.permissionSetName}</strong> access to{' '}
            <strong>{group.awsAccountName}</strong> via SCIM.
          </div>
        )}

        <form onSubmit={handleAdd} className="modal-form">
          <div className="form-group">
            <label className="form-label">Select User</label>
            <select
              className={clsx('form-input', theme === 'dark' ? 'input-dark' : 'input-light')}
              value={selectedPk}
              onChange={e => setSelectedPk(Number(e.target.value))}
              required
            >
              <option value="">Choose an active user…</option>
              {eligible.map(u => (
                <option key={u.pk} value={u.pk}>{u.name} — {u.email}</option>
              ))}
            </select>
            {eligible.length === 0 && (
              <p className="text-sm text-gray-400 mt-1">All active users are already in this group.</p>
            )}
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
            <button type="submit" className="btn-primary" disabled={submitting || !selectedPk}>
              {submitting ? 'Adding…' : 'Add to Group'}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
};

// ── Group Detail Side Panel ────────────────────────────────────────────────────

interface GroupPanelProps {
  group: IdentityGroup;
  allUsers: IdentityUser[];
  theme: string;
  onClose: () => void;
  onRefresh: () => void;
}

const GroupPanel: React.FC<GroupPanelProps> = ({ group, allUsers, theme, onClose, onRefresh }) => {
  const [showAddModal, setShowAddModal] = useState(false);
  const [removingPk, setRemovingPk] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);
  const isDark = theme === 'dark';

  const handleRemove = async (userId: number) => {
    setRemovingPk(userId);
    setError(null);
    try {
      await removeUserFromGroup(group.pk, userId);
      onRefresh();
    } catch (e: any) {
      setError(e.message ?? 'Failed to remove user.');
    } finally {
      setRemovingPk(null);
    }
  };

  return (
    <>
      <div className="ap-panel-backdrop" onClick={onClose} />
      <div className={clsx('ap-panel', isDark ? 'ap-panel-dark' : 'ap-panel-light')}>

        {/* Panel header */}
        <div className="ap-panel-header">
          <div>
            <h2 className={clsx('ap-panel-title', isDark ? 'text-slate-100' : 'text-gray-900')}>
              {group.name}
            </h2>
            {group.awsAccountName && (
              <p className={clsx('ap-panel-sub', isDark ? 'text-slate-400' : 'text-gray-500')}>
                <i className="fab fa-aws mr-1" />
                {group.awsAccountName} · {group.permissionSetName}
              </p>
            )}
          </div>
          <button className="modal-close" onClick={onClose}>✕</button>
        </div>

        {/* AWS badge */}
        {group.awsAccountId && (
          <div className={clsx('ap-info-banner mb-4', isDark ? 'ap-info-dark' : 'ap-info-light')}>
            <i className="fas fa-info-circle mr-2" />
            Membership changes automatically sync to AWS IAM Identity Center via SCIM.
          </div>
        )}

        {error && <p className="form-error mb-4">{error}</p>}

        {/* Members */}
        <div className="ap-panel-members-header">
          <h3 className={clsx('font-semibold text-sm', isDark ? 'text-slate-300' : 'text-gray-700')}>
            Members ({group.usersObj?.length ?? 0})
          </h3>
          <button
            className="btn-primary btn-sm"
            onClick={() => setShowAddModal(true)}
          >
            <i className="fas fa-plus mr-1" /> Add User
          </button>
        </div>

        <div className="ap-member-list">
          {(!group.usersObj || group.usersObj.length === 0) ? (
            <p className={clsx('text-sm py-4 text-center', isDark ? 'text-slate-500' : 'text-gray-400')}>
              No members yet
            </p>
          ) : (
            group.usersObj.map(member => (
              <div
                key={member.pk}
                className={clsx('ap-member-row', isDark ? 'ap-member-dark' : 'ap-member-light')}
              >
                <div className="ap-user-cell gap-3">
                  <div className={clsx('ap-avatar ap-avatar-sm', isDark ? 'ap-avatar-dark' : 'ap-avatar-light')}>
                    {member.name.charAt(0).toUpperCase()}
                  </div>
                  <div>
                    <p className={clsx('text-sm font-medium', isDark ? 'text-slate-200' : 'text-gray-900')}>
                      {member.name}
                    </p>
                    <p className={clsx('text-xs', isDark ? 'text-slate-400' : 'text-gray-500')}>
                      {member.email}
                    </p>
                  </div>
                </div>
                <button
                  className="ap-remove-btn"
                  onClick={() => handleRemove(member.pk)}
                  disabled={removingPk === member.pk}
                  title="Remove from group"
                >
                  {removingPk === member.pk
                    ? <i className="fas fa-spinner fa-spin" />
                    : <i className="fas fa-times" />}
                </button>
              </div>
            ))
          )}
        </div>
      </div>

      {showAddModal && (
        <AddUserModal
          group={group}
          allUsers={allUsers}
          theme={theme}
          onClose={() => setShowAddModal(false)}
          onSuccess={() => { setShowAddModal(false); onRefresh(); }}
        />
      )}
    </>
  );
};

// ── Main Page ──────────────────────────────────────────────────────────────────

const IdentityGroupsPage: React.FC = () => {
  const { theme } = useTheme();
  const [groups, setGroups] = useState<IdentityGroup[]>([]);
  const [allUsers, setAllUsers] = useState<IdentityUser[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [selectedGroup, setSelectedGroup] = useState<IdentityGroup | null>(null);
  const isDark = theme === 'dark';

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [g, u] = await Promise.all([listGroups(), listUsers()]);
      setGroups(g);
      setAllUsers(u);
      // Keep selected group data fresh after reload
      if (selectedGroup) {
        const fresh = g.find(x => x.pk === selectedGroup.pk);
        if (fresh) setSelectedGroup(fresh);
      }
    } catch (e: any) {
      setError(e.message ?? 'Failed to load groups.');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { load(); }, [load]);

  return (
    <div className={clsx('accessops-page', isDark ? 'ap-dark' : 'ap-light')}>

      {/* Header */}
      <div className="ap-header">
        <div>
          <h1 className={clsx('ap-page-title', isDark ? 'text-slate-100' : 'text-gray-900')}>
            Groups
          </h1>
          <p className={clsx('ap-page-sub', isDark ? 'text-slate-400' : 'text-gray-500')}>
            Each group maps to an AWS account and permission set via SCIM
          </p>
        </div>
      </div>

      {error && (
        <div className="ap-error">
          <i className="fas fa-exclamation-triangle mr-2" />{error}
        </div>
      )}

      {/* Groups Table */}
      <div className={clsx('ap-table-card', isDark ? 'ap-card-dark' : 'ap-card-light')}>
        <div className="overflow-x-auto">
          <table className="ap-table">
            <thead className={clsx('ap-thead', isDark ? 'ap-thead-dark' : 'ap-thead-light')}>
              <tr>
                <th>Group Name</th>
                <th>Members</th>
                <th>AWS Account</th>
                <th>Permission Set</th>
                <th className="text-right">Manage</th>
              </tr>
            </thead>
            <tbody className={clsx('divide-y', isDark ? 'divide-slate-700' : 'divide-gray-100')}>
              {loading ? (
                [1, 2, 3].map(i => (
                  <tr key={i}>
                    {[1, 2, 3, 4, 5].map(j => (
                      <td key={j} className="px-6 py-4">
                        <div className="skeleton-block h-4 rounded w-3/4" />
                      </td>
                    ))}
                  </tr>
                ))
              ) : groups.length === 0 ? (
                <tr>
                  <td colSpan={5} className="ap-empty">
                    <i className="fas fa-layer-group text-3xl mb-3 opacity-30" />
                    <p>No groups found</p>
                  </td>
                </tr>
              ) : (
                groups.map(group => (
                  <tr
                    key={group.pk}
                    className={clsx(
                      'ap-tr cursor-pointer',
                      isDark ? 'ap-tr-dark' : 'ap-tr-light',
                      selectedGroup?.pk === group.pk
                        ? isDark ? 'bg-slate-700/50' : 'bg-indigo-50'
                        : ''
                    )}
                    onClick={() => setSelectedGroup(group)}
                  >
                    <td className="px-6 py-4">
                      <div className="ap-user-cell">
                        <div className={clsx('ap-group-icon', isDark ? 'ap-group-icon-dark' : 'ap-group-icon-light')}>
                          <i className="fas fa-layer-group text-xs" />
                        </div>
                        <span className={clsx('font-medium text-sm', isDark ? 'text-slate-200' : 'text-gray-900')}>
                          {group.name}
                        </span>
                      </div>
                    </td>
                    <td className="px-6 py-4">
                      <span className={clsx('ap-tag', isDark ? 'ap-tag-dark' : 'ap-tag-light')}>
                        {group.numPk ?? group.usersObj?.length ?? 0} users
                      </span>
                    </td>
                    <td className={clsx('px-6 py-4 text-sm', isDark ? 'text-slate-300' : 'text-gray-700')}>
                      {group.awsAccountName
                        ? <><i className="fab fa-aws mr-1 text-orange-400" />{group.awsAccountName}</>
                        : <span className="text-gray-400">—</span>}
                    </td>
                    <td className="px-6 py-4">
                      {group.permissionSetName
                        ? (
                          <span className={clsx('ap-badge ap-badge-blue')}>
                            {group.permissionSetName}
                          </span>
                        )
                        : <span className={clsx('text-sm', isDark ? 'text-slate-500' : 'text-gray-400')}>—</span>}
                    </td>
                    <td className="px-6 py-4 text-right">
                      <button
                        className={clsx('ap-action-btn', isDark ? 'ap-action-dark' : 'ap-action-light')}
                        onClick={e => { e.stopPropagation(); setSelectedGroup(group); }}
                      >
                        <i className="fas fa-users-cog mr-1" /> Manage
                      </button>
                    </td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>
      </div>

      {/* Side panel */}
      {selectedGroup && (
        <GroupPanel
          group={selectedGroup}
          allUsers={allUsers}
          theme={theme}
          onClose={() => setSelectedGroup(null)}
          onRefresh={load}
        />
      )}
    </div>
  );
};

export default IdentityGroupsPage;
