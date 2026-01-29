/**
 * CloudAccountsTable Component
 *
 * Table displaying connected cloud accounts with actions
 */

import React from 'react';
import type { CloudAccount } from '../../types/accountManager';

interface CloudAccountsTableProps {
  accounts: CloudAccount[];
  isLoading: boolean;
  getStatusColor: (status: string, type: 'badge' | 'icon') => string;
  onVerify: (account: CloudAccount) => void;
  onRemove: (account: CloudAccount) => void;
}

const CloudAccountsTable: React.FC<CloudAccountsTableProps> = ({
  accounts,
  isLoading,
  getStatusColor,
  onVerify,
  onRemove,
}) => {
  const getProviderIcon = (provider: string) => {
    switch (provider) {
      case 'AWS':
        return { icon: 'fab fa-aws', class: 'provider-icon-aws' };
      case 'GCP':
        return { icon: 'fab fa-google', class: 'provider-icon-gcp' };
      case 'Azure':
        return { icon: 'fab fa-microsoft', class: 'provider-icon-azure' };
      default:
        return { icon: 'fas fa-cloud', class: 'provider-icon-default' };
    }
  };

  const getAccountId = (account: CloudAccount) => {
    return account.id || account.azureSubscriptionId || 'N/A';
  };

  return (
    <div className="data-card fade-in">
      <div className="card-header">
        <div className="flex items-center gap-3">
          <div className="card-header-icon bg-gradient-to-br from-indigo-500 to-purple-600">
            <i className="fas fa-cloud text-white" />
          </div>
          <div>
            <h2 className="card-title">Connected Cloud Accounts</h2>
            <p className="card-subtitle">View and manage your cloud account connections</p>
          </div>
        </div>
        <div className="flex items-center gap-2">
          <span className="badge badge-info">
            <i className="fas fa-layer-group mr-1" />
            {accounts.length} Total
          </span>
        </div>
      </div>

      {/* Loading State */}
      {isLoading && (
        <div className="table-container">
          <div className="skeleton h-12 w-full" />
          <div className="skeleton h-14 w-full mt-1" />
          <div className="skeleton h-14 w-full mt-1" />
          <div className="skeleton h-14 w-full mt-1" />
        </div>
      )}

      {/* Empty State */}
      {!isLoading && accounts.length === 0 && (
        <div className="empty-state">
          <div className="empty-state-icon">
            <i className="fas fa-cloud" />
          </div>
          <h3 className="empty-state-title">No Cloud Accounts Connected</h3>
          <p className="empty-state-text">Start by adding your first AWS, GCP, or Azure account.</p>
          <a href="/add-account.html" className="btn-primary mt-4">
            <i className="fas fa-plus mr-2" />
            Add New Account
          </a>
        </div>
      )}

      {/* Table */}
      {!isLoading && accounts.length > 0 && (
        <div className="table-container">
          <table className="data-table">
            <thead>
              <tr>
                <th>Account Name</th>
                <th>Provider</th>
                <th>Account/Project ID</th>
                <th>Access Level</th>
                <th>Status</th>
                <th className="text-center">Actions</th>
              </tr>
            </thead>
            <tbody>
              {accounts.map((account) => {
                const providerInfo = getProviderIcon(account.provider);
                return (
                  <tr
                    key={account.dbId}
                    className={`table-row-hover ${account.status.toLowerCase()}`}
                  >
                    <td>
                      <div className="flex items-center gap-3">
                        <div className={`provider-icon ${providerInfo.class}`}>
                          <i className={`${providerInfo.icon} text-white text-sm`} />
                        </div>
                        <span className="font-semibold text-gray-900">{account.name}</span>
                      </div>
                    </td>
                    <td>
                      <span className="text-gray-700">{account.provider}</span>
                    </td>
                    <td>
                      <code className="code-inline">{getAccountId(account)}</code>
                    </td>
                    <td>
                      <div className="flex items-center gap-2">
                        <i className="fas fa-key text-gray-400 text-xs" />
                        <span className="text-gray-700">{account.access || 'Contributor'}</span>
                      </div>
                    </td>
                    <td>
                      <span className={`status-badge ${getStatusColor(account.status, 'badge')}`}>
                        <span className={`status-dot ${
                          account.status === 'CONNECTED' ? 'bg-emerald-500' :
                          account.status === 'PENDING' ? 'bg-amber-500' : 'bg-red-500'
                        }`} />
                        {account.status}
                      </span>
                    </td>
                    <td>
                      <div className="flex justify-center gap-2">
                        {account.status === 'PENDING' && account.provider === 'AWS' && (
                          <button
                            onClick={() => onVerify(account)}
                            className="btn-action btn-warning"
                          >
                            <i className="fas fa-check mr-1" />
                            Verify
                          </button>
                        )}
                        <button
                          onClick={() => onRemove(account)}
                          className="btn-action btn-danger"
                        >
                          <i className="fas fa-trash mr-1" />
                          Remove
                        </button>
                      </div>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
};

export default CloudAccountsTable;
