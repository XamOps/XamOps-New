/**
 * My Profile Page - React Component
 *
 * Converted from: my-profile.html
 *
 * Features:
 * - User profile display with cloud accounts
 * - Password change functionality
 * - Team member management (for primary users)
 * - Cloud account access control per team member
 * - Role-based API routing (XAMOPS/BILLOPS)
 *
 * All backend connections and functionality preserved
 */

import React from 'react';
import { useMyProfile } from '../hooks/useMyProfile';
import { TeamUser, CloudAccount } from '../types/myProfile';

const ProfileSkeleton: React.FC = () => {
  return (
    <div className="w-full">
      <div className="flex items-center mb-10">
        <div className="skeleton h-12 w-12 rounded-2xl mr-4"></div>
        <div className="skeleton h-10 w-64 rounded-lg"></div>
      </div>

      <div className="rounded-3xl mb-12 overflow-hidden bg-white border border-slate-100">
        <div className="p-10 bg-slate-50 flex items-center">
          <div className="skeleton h-24 w-24 rounded-3xl mr-8"></div>
          <div>
            <div className="skeleton h-10 w-48 rounded-lg mb-3"></div>
            <div className="skeleton h-6 w-64 rounded-md mb-4"></div>
            <div className="skeleton h-8 w-32 rounded-xl"></div>
          </div>
        </div>
        <div className="p-10">
          <div className="grid grid-cols-1 md:grid-cols-2 gap-8 mb-10">
            <div className="p-6 border border-slate-50 rounded-2xl">
              <div className="skeleton h-4 w-24 mb-3"></div>
              <div className="skeleton h-6 w-40"></div>
            </div>
            <div className="p-6 border border-slate-50 rounded-2xl">
              <div className="skeleton h-4 w-24 mb-3"></div>
              <div className="skeleton h-6 w-40"></div>
            </div>
          </div>
          <div className="pt-8 border-t border-slate-50">
            <div className="skeleton h-12 w-56 rounded-xl"></div>
          </div>
        </div>
      </div>

      <div className="flex justify-between items-end mb-10">
        <div>
          <div className="flex items-center mb-2">
            <div className="skeleton h-10 w-10 rounded-2xl mr-4"></div>
            <div className="skeleton h-8 w-64 rounded-lg"></div>
          </div>
          <div className="skeleton h-4 w-96 rounded-md"></div>
        </div>
        <div className="skeleton h-12 w-48 rounded-xl"></div>
      </div>
    </div>
  );
};

const MyProfilePage: React.FC = () => {
  const {
    profile,
    teamUsers,
    selectedUser,
    isEditing,
    showPasswordModal,
    passwordForm,
    loading,
    error,
    newTeamUser,
    editTeamUser,
    cancelEdit,
    saveTeamUser,
    deleteTeamUser,
    changePassword,
    updateSelectedUser,
    updatePasswordForm,
    toggleAccountId,
    setShowPasswordModal
  } = useMyProfile();

  if (error) {
    return (
      <div className="flex items-center justify-center flex-1 bg-[#f8fafc] p-10">
        <div className="bg-red-50 text-red-700 p-8 rounded-3xl border border-red-100 max-w-md text-center shadow-sm">
          <i className="fas fa-circle-exclamation text-4xl mb-4"></i>
          <h2 className="text-2xl font-bold mb-2">Access Denied</h2>
          <p className="font-medium mb-6">{error}</p>
          <button onClick={() => window.location.reload()} className="px-6 py-2 bg-red-600 text-white rounded-xl font-bold hover:bg-red-700 transition-colors">
            Try Reauthenticating
          </button>
        </div>
      </div>
    );
  }

  return (
    <main id="profile-main-container" className="flex-1 w-full flex flex-col items-stretch p-6 lg:p-8 bg-[#f8fafc] my-profile-page">
      {loading && !profile.username ? (
        <ProfileSkeleton />
      ) : (
        <div className="w-full">
          {/* Profile Card */}
          <div className="rounded-3xl mb-12 overflow-hidden profile-card-container">
            <div className="profile-header-gradient p-10 text-white">
              <div className="flex items-center">
                <div className="profile-avatar">
                  <span className="text-5xl font-black">
                    {profile.username ? profile.username.charAt(0).toUpperCase() : 'U'}
                  </span>
                </div>
                <div className="ml-8">
                  <div className="flex items-center gap-3 mb-2">
                    <h2 className="text-4xl font-extrabold tracking-tight">{profile.username || 'Loading...'}</h2>
                    {profile.isPrimaryUser && (
                      <span className="bg-white/20 backdrop-blur-md px-3 py-1 rounded-lg text-xs font-bold uppercase tracking-widest border border-white/20 flex items-center gap-2">
                        <i className="fas fa-crown text-amber-300"></i>
                        Admin
                      </span>
                    )}
                  </div>
                  <p className="text-white/80 text-lg mb-4 flex items-center">
                    <i className="fas fa-envelope-open mr-3"></i>
                    {profile.email || ''}
                  </p>
                  <div className="flex gap-3">
                    <span className="inline-flex items-center px-4 py-1.5 bg-white/10 backdrop-blur-md rounded-xl text-sm font-bold border border-white/10">
                      <i className="fas fa-user-shield mr-2"></i>
                      {profile.role || ''}
                    </span>
                  </div>
                </div>
              </div>
            </div>

            <div className="p-10">
              <div className="grid grid-cols-1 md:grid-cols-2 gap-8 mb-10">
                <div className="profile-info-item group">
                  <label className="profile-info-label">
                    <i className="fas fa-building-columns mr-2"></i>
                    Organization
                  </label>
                  <p className="profile-info-value">{profile.clientName || 'N/A'}</p>
                </div>
                <div className="profile-info-item group">
                  <label className="profile-info-label">
                    <i className="fas fa-fingerprint mr-2"></i>
                    Account Type
                  </label>
                  <p className="profile-info-value text-indigo-600">
                    {profile.isPrimaryUser ? 'Primary Administrator' : 'Standard Team Member'}
                  </p>
                </div>
              </div>

              {/* Connected Cloud Accounts */}
              <div className="mb-10">
                <label className="profile-info-label mb-5 flex items-center">
                  <i className="fas fa-network-wired mr-2"></i>
                  Authorized Cloud Environments
                </label>
                <div className="flex flex-wrap gap-4">
                  {profile.cloudAccounts && profile.cloudAccounts.length > 0 ? (
                    profile.cloudAccounts.map((account: CloudAccount) => (
                      <span
                        key={account.id}
                        className={`cloud-account-badge ${account.provider === 'AWS'
                          ? 'cloud-account-aws'
                          : account.provider === 'GCP'
                            ? 'cloud-account-gcp'
                            : 'cloud-account-azure'
                          }`}
                      >
                        <i
                          className={`mr-3 text-lg ${account.provider === 'AWS'
                            ? 'fab fa-aws'
                            : account.provider === 'GCP'
                              ? 'fab fa-google'
                              : 'fab fa-microsoft'
                            }`}
                        ></i>
                        {account.name}
                      </span>
                    ))
                  ) : (
                    <div className="p-6 bg-slate-50 rounded-2xl border-2 border-dashed border-slate-200 text-slate-400 w-full text-center font-medium">
                      <i className="fas fa-cloud-slash mr-2"></i>
                      No cloud environments linked to this profile
                    </div>
                  )}
                </div>
              </div>

              {/* Change Password Button */}
              <div className="pt-8 border-t border-slate-100">
                <button
                  onClick={() => setShowPasswordModal(true)}
                  className="btn-change-password group"
                >
                  <i className="fas fa-shield-halved mr-3 text-indigo-600 transition-transform group-hover:rotate-12"></i>
                  Security & Password Settings
                </button>
              </div>
            </div>
          </div>

          {/* Team Users Section (Only for Primary Users) */}
          {profile.isPrimaryUser && profile.role !== 'ROLE_BILLOPS_ADMIN' && (
            <div className="team-members-section">
              <div className="flex justify-between items-end mb-10">
                <div>
                  <h2 className="text-3xl font-extrabold text-slate-900 flex items-center tracking-tight mb-2">
                    <i className="fas fa-users-gear mr-4 text-indigo-600 bg-indigo-50 p-3 rounded-2xl"></i>
                    Team Management
                  </h2>
                  <p className="text-slate-500 font-medium">Manage access and account permissions for your organization's team members.</p>
                </div>
                <button
                  onClick={newTeamUser}
                  className="btn-add-team-member"
                >
                  <i className="fas fa-plus-circle mr-3"></i>
                  Provision New Member
                </button>
              </div>

              {/* Team User Form */}
              {isEditing && (
                <div className="bg-white p-10 rounded-3xl shadow-2xl mb-12 border border-slate-200 team-user-form relative z-10">
                  <h3 className="text-2xl font-black text-slate-900 mb-8 flex items-center">
                    <i className="fas fa-user-plus mr-3 text-indigo-600"></i>
                    {selectedUser.id ? 'Modify Permissions' : 'Onboard Team Member'}
                  </h3>
                  <form
                    onSubmit={(e) => {
                      e.preventDefault();
                      saveTeamUser();
                    }}
                  >
                    <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
                      <div className="form-group">
                        <label className="form-label">
                          <i className="fas fa-user mr-2"></i>
                          Username
                        </label>
                        <input
                          type="text"
                          value={selectedUser.username}
                          onChange={(e) => updateSelectedUser('username', e.target.value)}
                          required
                          className="form-input"
                          placeholder="Enter username"
                        />
                      </div>
                      <div className="form-group">
                        <label className="form-label">
                          <i className="fas fa-envelope mr-2"></i>
                          Email
                        </label>
                        <input
                          type="email"
                          value={selectedUser.email}
                          onChange={(e) => updateSelectedUser('email', e.target.value)}
                          required
                          className="form-input"
                          placeholder="Enter email address"
                        />
                      </div>
                      <div className="form-group">
                        <label className="form-label">
                          <i className="fas fa-lock mr-2"></i>
                          Password
                        </label>
                        <input
                          type="password"
                          value={selectedUser.password}
                          onChange={(e) => updateSelectedUser('password', e.target.value)}
                          required={!selectedUser.id}
                          className="form-input"
                          placeholder={
                            selectedUser.id
                              ? 'Leave blank to keep current password'
                              : 'Enter password'
                          }
                        />
                      </div>
                      <div className="form-group">
                        <label className="form-label">
                          <i className="fas fa-user-tag mr-2"></i>
                          Role
                        </label>
                        <input
                          type="text"
                          value={profile.role}
                          disabled
                          className="form-input-disabled"
                        />
                        <p className="form-helper-text">Role is inherited from your account</p>
                      </div>
                    </div>

                    {/* Cloud Account Access Selection */}
                    {profile.cloudAccounts && profile.cloudAccounts.length > 0 && (
                      <div className="mt-6">
                        <label className="form-label mb-3 block">
                          <i className="fas fa-cloud mr-2"></i>
                          Cloud Account Access
                        </label>
                        <p className="text-sm text-gray-500 mb-3">
                          Select which cloud accounts this team member can access
                        </p>
                        <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
                          {profile.cloudAccounts.map((account: CloudAccount) => (
                            <label
                              key={account.id}
                              className="cloud-account-checkbox"
                            >
                              <input
                                type="checkbox"
                                checked={selectedUser.accountIds.includes(account.id)}
                                onChange={() => toggleAccountId(account.id)}
                                className="checkbox-input"
                              />
                              <div className="ml-3 flex items-center">
                                <i
                                  className={`mr-2 ${account.provider === 'AWS'
                                    ? 'fab fa-aws text-orange-500'
                                    : account.provider === 'GCP'
                                      ? 'fab fa-google text-blue-500'
                                      : 'fab fa-microsoft text-sky-500'
                                    }`}
                                ></i>
                                <span className="text-sm font-medium text-gray-700">
                                  {account.name}
                                </span>
                              </div>
                            </label>
                          ))}
                        </div>
                      </div>
                    )}

                    <div className="mt-8 flex justify-end space-x-4">
                      <button
                        type="button"
                        onClick={cancelEdit}
                        className="btn-cancel"
                      >
                        <i className="fas fa-times mr-2"></i>
                        Cancel
                      </button>
                      <button type="submit" className="btn-save">
                        <i className="fas fa-check mr-2"></i>
                        Save Team Member
                      </button>
                    </div>
                  </form>
                </div>
              )}

              {/* Team Users Table */}
              <div className="bg-white rounded-xl shadow-md overflow-hidden border border-gray-100">
                <div className="overflow-x-auto">
                  <table className="team-users-table">
                    <thead>
                      <tr>
                        <th>
                          <i className="fas fa-signature mr-2 text-indigo-500"></i>
                          Member
                        </th>
                        <th>
                          <i className="fas fa-at mr-2 text-indigo-500"></i>
                          Email Address
                        </th>
                        <th>
                          <i className="fas fa-user-gear mr-2 text-indigo-500"></i>
                          Access Role
                        </th>
                        <th>
                          <i className="fas fa-key mr-2 text-indigo-500"></i>
                          Environments
                        </th>
                        <th>
                          <i className="fas fa-sliders mr-2 text-indigo-500"></i>
                          Actions
                        </th>
                      </tr>
                    </thead>
                    <tbody>
                      {teamUsers.length > 0 ? (
                        teamUsers.map((user: TeamUser) => (
                          <tr key={user.id}>
                            <td className="font-medium text-gray-900">
                              <i className="fas fa-user-circle mr-2 text-indigo-500"></i>
                              {user.username}
                            </td>
                            <td className="text-gray-600">{user.email}</td>
                            <td>
                              <span className="role-badge">{user.role}</span>
                            </td>
                            <td>
                              {user.cloudAccounts && user.cloudAccounts.length > 0 ? (
                                <div className="flex flex-wrap gap-2">
                                  {user.cloudAccounts.map((account: CloudAccount) => (
                                    <span
                                      key={account.id}
                                      className={`cloud-account-badge-small ${account.provider === 'AWS'
                                        ? 'cloud-account-aws'
                                        : account.provider === 'GCP'
                                          ? 'cloud-account-gcp'
                                          : 'cloud-account-azure'
                                        }`}
                                    >
                                      <i
                                        className={`mr-1 ${account.provider === 'AWS'
                                          ? 'fab fa-aws'
                                          : account.provider === 'GCP'
                                            ? 'fab fa-google'
                                            : 'fab fa-microsoft'
                                          }`}
                                      ></i>
                                      {account.name}
                                    </span>
                                  ))}
                                </div>
                              ) : (
                                <span className="text-xs text-gray-400 italic">No access</span>
                              )}
                            </td>
                            <td>
                              <div className="flex space-x-3">
                                <button
                                  onClick={() => editTeamUser(user)}
                                  className="action-btn action-btn-edit"
                                >
                                  <i className="fas fa-edit mr-1"></i>
                                  Edit
                                </button>
                                <button
                                  onClick={() => deleteTeamUser(user.id)}
                                  className="action-btn action-btn-delete"
                                >
                                  <i className="fas fa-trash-alt mr-1"></i>
                                  Delete
                                </button>
                              </div>
                            </td>
                          </tr>
                        ))
                      ) : (
                        <tr>
                          <td colSpan={5} className="text-center py-12">
                            <i className="fas fa-users text-5xl text-gray-300 mb-3 block"></i>
                            <p className="text-gray-500 text-lg">
                              No team members yet. Add one to get started!
                            </p>
                          </td>
                        </tr>
                      )}
                    </tbody>
                  </table>
                </div>
              </div>
            </div>
          )}

          {/* Password Change Modal */}
          {showPasswordModal && (
            <div className="modal-overlay">
              <div className="modal-backdrop" onClick={() => setShowPasswordModal(false)} />
              <div className="modal-content">
                <div className="modal-header">
                  <h3 className="modal-title">
                    <i className="fas fa-shield-halved mr-3 text-indigo-600"></i>
                    Security Authentication
                  </h3>
                  <button
                    onClick={() => setShowPasswordModal(false)}
                    className="modal-close-btn"
                  >
                    <i className="fas fa-times"></i>
                  </button>
                </div>
                <form
                  onSubmit={(e) => {
                    e.preventDefault();
                    changePassword();
                  }}
                >
                  <div className="modal-body">
                    <div className="form-group">
                      <label className="form-label">
                        <i className="fas fa-lock mr-2"></i>
                        Current Password
                      </label>
                      <input
                        type="password"
                        value={passwordForm.oldPassword}
                        onChange={(e) => updatePasswordForm('oldPassword', e.target.value)}
                        required
                        className="form-input"
                        placeholder="Enter current password"
                      />
                    </div>
                    <div className="form-group">
                      <label className="form-label">
                        <i className="fas fa-key mr-2"></i>
                        New Password
                      </label>
                      <input
                        type="password"
                        value={passwordForm.newPassword}
                        onChange={(e) => updatePasswordForm('newPassword', e.target.value)}
                        required
                        className="form-input"
                        placeholder="Enter new password (min 6 characters)"
                      />
                    </div>
                    <div className="form-group">
                      <label className="form-label">
                        <i className="fas fa-check-circle mr-2"></i>
                        Confirm New Password
                      </label>
                      <input
                        type="password"
                        value={passwordForm.confirmPassword}
                        onChange={(e) => updatePasswordForm('confirmPassword', e.target.value)}
                        required
                        className="form-input"
                        placeholder="Re-enter new password"
                      />
                    </div>
                  </div>
                  <div className="modal-footer">
                    <button
                      type="button"
                      onClick={() => setShowPasswordModal(false)}
                      className="btn-cancel"
                    >
                      Cancel
                    </button>
                    <button type="submit" className="btn-primary-gradient">
                      <i className="fas fa-check mr-2"></i>
                      Update Password
                    </button>
                  </div>
                </form>
              </div>
            </div>
          )}
        </div>
      )}
    </main>
  );
};

export default MyProfilePage;
