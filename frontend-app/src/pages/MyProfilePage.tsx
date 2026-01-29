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

  if (loading) {
    return (
      <div className="flex items-center justify-center h-screen">
        <div className="text-center">
          <div className="animate-spin rounded-full h-12 w-12 border-b-2 border-indigo-600 mx-auto mb-4"></div>
          <p className="text-gray-600">Loading profile...</p>
        </div>
      </div>
    );
  }

  if (error) {
    return (
      <div className="flex items-center justify-center h-screen">
        <div className="text-center">
          <i className="fas fa-exclamation-triangle text-5xl text-red-500 mb-4"></i>
          <p className="text-red-600">Failed to load profile: {error}</p>
        </div>
      </div>
    );
  }

  return (
    <main className="flex-1 overflow-y-auto p-6 lg:p-8 bg-gradient-to-br from-slate-50 to-slate-100 my-profile-page">
      <div className="max-w-7xl mx-auto">
        <h1 className="text-3xl font-bold mb-6 text-gray-800 flex items-center">
          <i className="fas fa-user-circle mr-3 text-indigo-600"></i>
          My Profile
        </h1>

        {/* Profile Card */}
        <div className="bg-white rounded-xl shadow-lg mb-8 overflow-hidden profile-card-container">
          <div className="profile-header-gradient p-8 text-white">
            <div className="flex items-center">
              <div className="profile-avatar">
                <span className="text-4xl font-bold">
                  {profile.username ? profile.username.charAt(0).toUpperCase() : 'U'}
                </span>
              </div>
              <div className="ml-6">
                <h2 className="text-3xl font-bold mb-1">{profile.username || 'Loading...'}</h2>
                <p className="text-white/90 text-lg mb-2">
                  <i className="fas fa-envelope mr-2"></i>
                  {profile.email || ''}
                </p>
                <span className="inline-flex items-center px-4 py-2 bg-white/20 backdrop-blur-sm rounded-full text-sm font-semibold">
                  <i className="fas fa-user-tag mr-2"></i>
                  {profile.role || ''}
                </span>
              </div>
            </div>
          </div>

          <div className="p-8">
            <div className="grid grid-cols-1 md:grid-cols-2 gap-8 mb-8">
              <div className="profile-info-item">
                <label className="profile-info-label">
                  <i className="fas fa-building mr-2"></i>
                  Client / Organization
                </label>
                <p className="profile-info-value">{profile.clientName || 'N/A'}</p>
              </div>
              <div className="profile-info-item">
                <label className="profile-info-label">
                  <i className="fas fa-shield-alt mr-2"></i>
                  User Type
                </label>
                <p className="profile-info-value">
                  {profile.isPrimaryUser ? (
                    <span className="text-purple-600 font-semibold">
                      <i className="fas fa-crown mr-2"></i>
                      Primary User (Admin)
                    </span>
                  ) : (
                    'Team Member'
                  )}
                </p>
              </div>
            </div>

            {/* Connected Cloud Accounts */}
            <div className="mb-8">
              <label className="profile-info-label mb-3 block">
                <i className="fas fa-cloud mr-2"></i>
                Connected Cloud Accounts
              </label>
              <div className="flex flex-wrap gap-3">
                {profile.cloudAccounts && profile.cloudAccounts.length > 0 ? (
                  profile.cloudAccounts.map((account: CloudAccount) => (
                    <span
                      key={account.id}
                      className={`cloud-account-badge ${
                        account.provider === 'AWS'
                          ? 'cloud-account-aws'
                          : account.provider === 'GCP'
                          ? 'cloud-account-gcp'
                          : 'cloud-account-azure'
                      }`}
                    >
                      <i
                        className={`mr-2 ${
                          account.provider === 'AWS'
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
                  <span className="text-gray-400 italic">
                    <i className="fas fa-info-circle mr-2"></i>
                    No cloud accounts connected
                  </span>
                )}
              </div>
            </div>

            {/* Change Password Button */}
            <div className="pt-6 border-t border-gray-200">
              <button
                onClick={() => setShowPasswordModal(true)}
                className="btn-change-password"
              >
                <i className="fas fa-key mr-2"></i>
                Change Password
              </button>
            </div>
          </div>
        </div>

        {/* Team Users Section (Only for Primary Users) */}
        {profile.isPrimaryUser && (
          <div className="team-members-section">
            <div className="flex justify-between items-center mb-6">
              <h2 className="text-2xl font-bold text-gray-800 flex items-center">
                <i className="fas fa-users mr-3 text-indigo-600"></i>
                Team Members
              </h2>
              <button
                onClick={newTeamUser}
                className="btn-add-team-member"
              >
                <i className="fas fa-user-plus mr-2"></i>
                Add Team Member
              </button>
            </div>

            {/* Team User Form */}
            {isEditing && (
              <div className="bg-white p-8 rounded-xl shadow-lg mb-8 border border-indigo-100 team-user-form">
                <h3 className="text-xl font-bold text-gray-800 mb-6 flex items-center">
                  <i className="fas fa-user-edit mr-2 text-indigo-600"></i>
                  {selectedUser.id ? 'Edit Team Member' : 'Add Team Member'}
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
                                className={`mr-2 ${
                                  account.provider === 'AWS'
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
                        <i className="fas fa-user mr-2"></i>
                        Username
                      </th>
                      <th>
                        <i className="fas fa-envelope mr-2"></i>
                        Email
                      </th>
                      <th>
                        <i className="fas fa-user-tag mr-2"></i>
                        Role
                      </th>
                      <th>
                        <i className="fas fa-cloud mr-2"></i>
                        Cloud Accounts
                      </th>
                      <th>
                        <i className="fas fa-cog mr-2"></i>
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
                                    className={`cloud-account-badge-small ${
                                      account.provider === 'AWS'
                                        ? 'cloud-account-aws'
                                        : account.provider === 'GCP'
                                        ? 'cloud-account-gcp'
                                        : 'cloud-account-azure'
                                    }`}
                                  >
                                    <i
                                      className={`mr-1 ${
                                        account.provider === 'AWS'
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
                  <i className="fas fa-key mr-2"></i>
                  Change Password
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
    </main>
  );
};

export default MyProfilePage;
