/**
 * CloudAccountsTable Component
 *
 * Table displaying connected cloud accounts with actions
 */

import React from 'react';
import { Link } from 'react-router-dom';
import type { CloudAccount } from '../../types/accountManager';

interface CloudAccountsTableProps {
  accounts: CloudAccount[];
  isLoading: boolean;
  onVerify: (account: CloudAccount) => void;
  onRemove: (account: CloudAccount) => void;
}

const CloudAccountsTable: React.FC<CloudAccountsTableProps> = ({
  accounts,
  isLoading,
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
    <div className="data-card overflow-hidden">
      <div className="card-header border-b border-slate-100 bg-slate-50/30 px-6 py-5">
        <div className="flex items-center gap-4">
          <div className="card-header-icon bg-gradient-to-br from-indigo-500 to-purple-600 shadow-indigo-200 shadow-lg">
            <i className="fas fa-cloud text-white" />
          </div>
          <div>
            <h2 className="text-lg font-bold text-slate-900 leading-tight">Connected Cloud Accounts</h2>
            <p className="text-xs font-medium text-slate-400 mt-0.5 uppercase tracking-wider">Multi-cloud inventory system</p>
          </div>
        </div>
        <div className="flex items-center gap-3">
          <div className="flex items-center gap-2 px-3 py-1.5 rounded-full bg-indigo-50 border border-indigo-100 text-indigo-700 text-[10px] font-bold uppercase tracking-wider">
            <i className="fas fa-layer-group" />
            <span>{accounts.length} Accounts</span>
          </div>
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
          <Link to="/add-account" className="btn-primary mt-4">
            <i className="fas fa-plus mr-2" />
            Add New Account
          </Link>
        </div>
      )}

      {/* Table */}
      {!isLoading && accounts.length > 0 && (
        <div className="table-container">
          <table className="data-table">
            <thead>
              <tr>
                <th className="nowrap">Account Name</th>
                <th className="nowrap">Account/Project ID</th>
                <th className="nowrap">Access Level</th>
                <th className="nowrap">Status</th>
                <th className="text-right nowrap">Actions</th>
              </tr>
            </thead>
            <tbody>
              {accounts.map((account) => {
                const providerInfo = getProviderIcon(account.provider);
                const isConnected = account.status === 'CONNECTED';
                const isPending = account.status === 'PENDING';

                const statusClasses = isConnected ? 'bg-emerald-50 border-emerald-100 text-emerald-600' :
                  isPending ? 'bg-amber-50 border-amber-100 text-amber-600' :
                    'bg-rose-50 border-rose-100 text-rose-600';

                const dotClasses = isConnected ? 'bg-emerald-500 animate-pulse' :
                  isPending ? 'bg-amber-500' : 'bg-rose-500';

                return (
                  <tr
                    key={account.dbId}
                    className="group border-b border-slate-50 hover:bg-slate-50/50 transition-colors"
                  >
                    <td className="py-4 nowrap">
                      <div className="flex items-center gap-4">
                        <div className={`w-10 h-10 rounded-xl flex items-center justify-center text-white ${providerInfo.class} shadow-sm group-hover:scale-110 transition-transform duration-300`}>
                          <i className={`${providerInfo.icon} text-lg`} />
                        </div>
                        <div className="flex flex-col">
                          <span className="font-bold text-slate-900 leading-tight nowrap">{account.name}</span>
                          <span className="text-[10px] font-bold text-slate-400 mt-1 uppercase tracking-wider">{account.provider}</span>
                        </div>
                      </div>
                    </td>
                    <td className="nowrap">
                      <code className="text-[11px] font-mono font-bold px-2 py-1 bg-slate-100 text-slate-600 rounded-lg border border-slate-200">
                        {getAccountId(account)}
                      </code>
                    </td>
                    <td className="nowrap">
                      <div className="flex items-center gap-2 text-xs font-semibold text-slate-600">
                        <i className="fas fa-shield-alt text-slate-300" />
                        <span>{account.access || 'Contributor'}</span>
                      </div>
                    </td>
                    <td className="nowrap">
                      <div className={`inline-flex items-center gap-1.5 px-3 py-1.5 rounded-xl border font-bold text-[10px] uppercase tracking-wider ${statusClasses}`}>
                        <div className={`w-1.5 h-1.5 rounded-full ${dotClasses}`} />
                        {account.status}
                      </div>
                    </td>
                    <td className="text-right nowrap">
                      <div className="flex justify-end gap-2">
                        {isPending && account.provider === 'AWS' && (
                          <button
                            onClick={() => onVerify(account)}
                            className="h-8 w-8 rounded-lg bg-amber-100 text-amber-600 hover:bg-amber-600 hover:text-white transition-all flex items-center justify-center shadow-sm"
                            title="Verify Connection"
                          >
                            <i className="fas fa-check text-xs" />
                          </button>
                        )}
                        <button
                          onClick={() => onRemove(account)}
                          className="h-8 w-8 rounded-lg bg-rose-50 text-rose-500 hover:bg-rose-500 hover:text-white transition-all flex items-center justify-center shadow-sm"
                          title="Remove Account"
                        >
                          <i className="fas fa-trash text-xs" />
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
