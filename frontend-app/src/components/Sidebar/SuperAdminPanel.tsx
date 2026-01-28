/**
 * SuperAdminPanel Component
 *
 * Tenant switching and user impersonation for super admins
 * Converted from: _sidebar.html (super admin section)
 */

import React from 'react';
import { useAuth } from '../../contexts/AuthContext';

const SuperAdminPanel: React.FC = () => {
  const {
    tenantList,
    tenantUsers,
    selectedTenant,
    impersonateUser,
    switchTenant,
    switchUser,
    resetContext,
    fetchTenantUsers,
  } = useAuth();

  const handleTenantChange = (e: React.ChangeEvent<HTMLSelectElement>) => {
    const tenantId = e.target.value;
    if (tenantId) {
      fetchTenantUsers(tenantId);
    }
  };

  return (
    <div className="px-4 py-3 bg-gradient-to-r from-purple-500 to-indigo-600 text-white rounded-lg">
      <h3 className="text-sm font-semibold mb-3 flex items-center">
        <i className="fas fa-crown mr-2" />
        Super Admin Panel
      </h3>

      {/* Tenant Selector */}
      <div className="mb-3">
        <label className="text-xs font-medium mb-1 block">Select Tenant</label>
        <select
          value={selectedTenant}
          onChange={handleTenantChange}
          className="w-full px-3 py-2 bg-white text-gray-900 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-white"
        >
          <option value="">-- Select Tenant --</option>
          {tenantList.map(tenant => (
            <option key={tenant.id} value={tenant.id}>
              {tenant.name}
            </option>
          ))}
        </select>
      </div>

      {/* User Impersonation (Only show if tenant is selected) */}
      {selectedTenant && (
        <div className="mb-3">
          <label className="text-xs font-medium mb-1 block">Impersonate User</label>
          <select
            value={impersonateUser}
            onChange={e => {
              const userId = e.target.value;
              if (userId) switchUser(userId);
            }}
            className="w-full px-3 py-2 bg-white text-gray-900 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-white"
          >
            <option value="">-- Select User --</option>
            {tenantUsers.map(user => (
              <option key={user.id} value={user.id}>
                {user.username} ({user.email})
              </option>
            ))}
          </select>
        </div>
      )}

      {/* Action Buttons */}
      <div className="flex gap-2">
        <button
          onClick={() => {
            const tenantSelect = document.querySelector<HTMLSelectElement>(
              'select[value="' + selectedTenant + '"]'
            );
            if (tenantSelect) {
              switchTenant(selectedTenant);
            }
          }}
          disabled={!selectedTenant}
          className="flex-1 px-3 py-2 bg-white text-indigo-600 rounded-lg text-sm font-medium hover:bg-gray-100 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
        >
          Switch
        </button>
        <button
          onClick={resetContext}
          className="flex-1 px-3 py-2 bg-red-500 text-white rounded-lg text-sm font-medium hover:bg-red-600 transition-colors"
        >
          Reset
        </button>
      </div>

      {/* Current Context Display */}
      {(selectedTenant || impersonateUser) && (
        <div className="mt-3 pt-3 border-t border-white/20 text-xs">
          {selectedTenant && (
            <div className="mb-1">
              <span className="font-medium">Tenant:</span>{' '}
              {tenantList.find(t => t.id === selectedTenant)?.name || selectedTenant}
            </div>
          )}
          {impersonateUser && (
            <div>
              <span className="font-medium">User:</span>{' '}
              {tenantUsers.find(u => u.id === impersonateUser)?.username || impersonateUser}
            </div>
          )}
        </div>
      )}
    </div>
  );
};

export default SuperAdminPanel;
