/**
 * Custom hook for My Profile page
 *
 * Handles all profile-related logic:
 * - Fetching user profile and team members
 * - Managing team users (CRUD operations)
 * - Changing password
 * - Form state management
 */

import { useState, useEffect, useCallback } from 'react';
import { UserProfile, TeamUser, TeamUserForm, PasswordChangeForm } from '../types/myProfile';

/**
 * Get CSRF token from cookies
 */
const getCsrfToken = (): string => {
  return document.cookie
    .split('; ')
    .find(row => row.startsWith('XSRF-TOKEN='))
    ?.split('=')[1] || '';
};

/**
 * Determine API base URL based on user role
 */
const getApiBase = (): string => {
  const roles = JSON.parse(sessionStorage.getItem('cached_user_roles') || '[]');
  if (roles.includes('ROLE_XAMOPS')) {
    return '/api/xamops/team';
  } else if (roles.includes('ROLE_BILLOPS') || roles.includes('ROLE_BILLOPS_ADMIN')) {
    return '/api/billops/team';
  }
  // Default to xamops
  return '/api/xamops/team';
};

export const useMyProfile = () => {
  const [profile, setProfile] = useState<UserProfile>({
    id: null,
    username: '',
    email: '',
    role: '',
    clientName: '',
    isPrimaryUser: false,
    cloudAccounts: []
  });

  const [teamUsers, setTeamUsers] = useState<TeamUser[]>([]);
  const [selectedUser, setSelectedUser] = useState<TeamUserForm>({
    id: null,
    username: '',
    email: '',
    password: '',
    accountIds: [],
    allowedTabs: []
  });

  const [isEditing, setIsEditing] = useState(false);
  const [showPasswordModal, setShowPasswordModal] = useState(false);
  const [passwordForm, setPasswordForm] = useState<PasswordChangeForm>({
    oldPassword: '',
    newPassword: '',
    confirmPassword: ''
  });

  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const apiBase = getApiBase();

  /**
   * Fetch user profile
   */
  const fetchProfile = useCallback(async () => {
    try {
      setLoading(true);
      setError(null);

      const response = await fetch(`${apiBase}/profile`, {
        method: 'GET',
        credentials: 'include'
      });

      if (!response.ok) {
        throw new Error(`HTTP ${response.status}`);
      }

      const data = await response.json();
      setProfile(data);

      // If primary user, also fetch team users
      if (data.isPrimaryUser) {
        await fetchTeamUsers();
      }
    } catch (err) {
      console.error('Failed to fetch profile:', err);
      setError(err instanceof Error ? err.message : 'Failed to fetch profile');
    } finally {
      setLoading(false);
    }
  }, [apiBase]);

  /**
   * Fetch team users
   */
  const fetchTeamUsers = useCallback(async () => {
    try {
      const response = await fetch(`${apiBase}/users`, {
        method: 'GET',
        credentials: 'include'
      });

      if (!response.ok) {
        throw new Error(`HTTP ${response.status}`);
      }

      const data = await response.json();
      setTeamUsers(data || []);
    } catch (err) {
      console.error('Failed to fetch team users:', err);
    }
  }, [apiBase]);

  /**
   * Initialize - fetch profile on mount
   */
  useEffect(() => {
    fetchProfile();
  }, [fetchProfile]);

  /**
   * Create new team user
   */
  const newTeamUser = useCallback(() => {
    setSelectedUser({
      id: null,
      username: '',
      email: '',
      password: '',
      accountIds: [],
      allowedTabs: []
    });
    setIsEditing(true);
  }, []);

  /**
   * Edit existing team user
   */
  const editTeamUser = useCallback((user: TeamUser) => {
    setSelectedUser({
      id: user.id,
      username: user.username,
      email: user.email,
      password: '',
      accountIds: user.accountIds || [],
      allowedTabs: user.allowedTabs || []
    });
    setIsEditing(true);
  }, []);

  /**
   * Cancel edit
   */
  const cancelEdit = useCallback(() => {
    setIsEditing(false);
    setSelectedUser({
      id: null,
      username: '',
      email: '',
      password: '',
      accountIds: [],
      allowedTabs: []
    });
  }, []);

  /**
   * Save team user (create or update)
   */
  const saveTeamUser = useCallback(async () => {
    if (!selectedUser.username?.trim()) {
      alert('Username is required!');
      return;
    }
    if (!selectedUser.email?.trim()) {
      alert('Email is required!');
      return;
    }
    if (!selectedUser.id && !selectedUser.password?.trim()) {
      alert('Password is required for new users!');
      return;
    }

    try {
      const payload: any = { ...selectedUser };
      if (payload.id && !payload.password?.trim()) {
        delete payload.password;
      }

      const url = payload.id
        ? `${apiBase}/users/${payload.id}`
        : `${apiBase}/users`;
      const method = payload.id ? 'PUT' : 'POST';

      const response = await fetch(url, {
        method,
        credentials: 'include',
        headers: {
          'Content-Type': 'application/json',
          'X-XSRF-TOKEN': getCsrfToken()
        },
        body: JSON.stringify(payload)
      });

      if (!response.ok) {
        const text = await response.text();
        throw new Error(text || `HTTP ${response.status}`);
      }

      await fetchTeamUsers();
      cancelEdit();
    } catch (err) {
      console.error('Save failed:', err);
      alert('Failed to save: ' + (err instanceof Error ? err.message : 'Unknown error'));
    }
  }, [selectedUser, apiBase, fetchTeamUsers, cancelEdit]);

  /**
   * Delete team user
   */
  const deleteTeamUser = useCallback(async (id: number) => {
    if (!confirm('Are you sure you want to delete this team member?')) {
      return;
    }

    try {
      const response = await fetch(`${apiBase}/users/${id}`, {
        method: 'DELETE',
        credentials: 'include',
        headers: { 'X-XSRF-TOKEN': getCsrfToken() }
      });

      if (!response.ok) {
        throw new Error(`HTTP ${response.status}`);
      }

      await fetchTeamUsers();
    } catch (err) {
      console.error('Delete failed:', err);
      alert('Delete failed: ' + (err instanceof Error ? err.message : 'Unknown error'));
    }
  }, [apiBase, fetchTeamUsers]);

  /**
   * Change password
   */
  const changePassword = useCallback(async () => {
    if (passwordForm.newPassword !== passwordForm.confirmPassword) {
      alert('New passwords do not match!');
      return;
    }

    if (passwordForm.newPassword.length < 6) {
      alert('Password must be at least 6 characters!');
      return;
    }

    try {
      const response = await fetch(`${apiBase}/profile/password`, {
        method: 'PUT',
        credentials: 'include',
        headers: {
          'Content-Type': 'application/json',
          'X-XSRF-TOKEN': getCsrfToken()
        },
        body: JSON.stringify({
          oldPassword: passwordForm.oldPassword,
          newPassword: passwordForm.newPassword
        })
      });

      if (!response.ok) {
        const text = await response.text();
        throw new Error(text || `HTTP ${response.status}`);
      }

      alert('Password updated successfully!');
      setShowPasswordModal(false);
      setPasswordForm({
        oldPassword: '',
        newPassword: '',
        confirmPassword: ''
      });
    } catch (err) {
      console.error('Password change failed:', err);
      alert('Failed to change password: ' + (err instanceof Error ? err.message : 'Unknown error'));
    }
  }, [passwordForm, apiBase]);

  /**
   * Update selected user field
   */
  const updateSelectedUser = useCallback((field: keyof TeamUserForm, value: any) => {
    setSelectedUser(prev => ({ ...prev, [field]: value }));
  }, []);

  /**
   * Update password form field
   */
  const updatePasswordForm = useCallback((field: keyof PasswordChangeForm, value: string) => {
    setPasswordForm(prev => ({ ...prev, [field]: value }));
  }, []);

  /**
   * Toggle account ID in selected user's accountIds
   */
  const toggleAccountId = useCallback((accountId: number) => {
    setSelectedUser(prev => {
      const accountIds = [...prev.accountIds];
      const index = accountIds.indexOf(accountId);

      if (index === -1) {
        accountIds.push(accountId);
      } else {
        accountIds.splice(index, 1);
      }

      return { ...prev, accountIds };
    });
  }, []);

  /**
   * Toggle tab key in selected user's allowedTabs
   */
  const toggleTab = useCallback((tabKey: string) => {
    setSelectedUser(prev => {
      const allowedTabs = [...prev.allowedTabs];
      const index = allowedTabs.indexOf(tabKey);

      if (index === -1) {
        allowedTabs.push(tabKey);
      } else {
        allowedTabs.splice(index, 1);
      }

      return { ...prev, allowedTabs };
    });
  }, []);

  return {
    // State
    profile,
    teamUsers,
    selectedUser,
    isEditing,
    showPasswordModal,
    passwordForm,
    loading,
    error,

    // Actions
    fetchProfile,
    fetchTeamUsers,
    newTeamUser,
    editTeamUser,
    cancelEdit,
    saveTeamUser,
    deleteTeamUser,
    changePassword,
    updateSelectedUser,
    updatePasswordForm,
    toggleAccountId,
    toggleTab,
    setShowPasswordModal
  };
};
