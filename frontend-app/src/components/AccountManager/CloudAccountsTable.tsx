/**
 * CloudAccountsTable Component
 *
 * Table displaying connected cloud accounts with actions
 */

import React, { useState } from 'react';
import { Link } from 'react-router-dom';
import { gooeyToast } from 'goey-toast';
import type { CloudAccount } from '../../types/accountManager';

interface CloudAccountsTableProps {
  accounts: CloudAccount[];
  isLoading: boolean;
  onVerify: (account: CloudAccount) => void;
  onRemove: (account: CloudAccount) => void;
  onUpdateBillingTable?: (accountDbId: number, billingTable: string) => Promise<{ success: boolean; error?: string }>;
}

const CloudAccountsTable: React.FC<CloudAccountsTableProps> = ({
  accounts,
  isLoading,
  onVerify,
  onRemove,
  onUpdateBillingTable,
}) => {
  const [billingModalAccount, setBillingModalAccount] = useState<CloudAccount | null>(null);
  const [billingTableInput, setBillingTableInput] = useState('');
  const [billingModalLoading, setBillingModalLoading] = useState(false);

  const getProviderIcon = (provider: string) => {
    switch (provider) {
      case 'AWS':
        return { icon: 'fab fa-aws', class: 'provider-icon-aws text-white' };
      case 'GCP':
        return { icon: 'fab fa-google', class: 'provider-icon-gcp text-white' };
      case 'Azure':
        return { icon: 'fab fa-microsoft', class: 'provider-icon-azure text-white' };
      default:
        return { icon: 'fas fa-cloud', class: 'provider-icon-default text-slate-500 bg-slate-100' };
    }
  };

  const getAccountId = (account: CloudAccount) => {
    return account.id || account.azureSubscriptionId || 'N/A';
  };

  const openBillingModal = (account: CloudAccount) => {
    setBillingModalAccount(account);
    setBillingTableInput(account.billingExportTable || '');
  };

  const closeBillingModal = () => {
    setBillingModalAccount(null);
    setBillingTableInput('');
    setBillingModalLoading(false);
  };

  const handleSaveBillingTable = async () => {
    if (!billingModalAccount || !onUpdateBillingTable) return;

    const trimmed = billingTableInput.trim();
    if (!trimmed) {
      gooeyToast.warning('Please enter the BigQuery billing export table name.');
      return;
    }

    const parts = trimmed.split('.');
    if (parts.length !== 3 || parts.some(p => !p.trim())) {
      gooeyToast.error('Invalid format', { description: 'Expected: project.dataset.table' });
      return;
    }

    setBillingModalLoading(true);
    const result = await onUpdateBillingTable(billingModalAccount.dbId, trimmed);
    setBillingModalLoading(false);

    if (result.success) {
      gooeyToast.success('Billing export table updated successfully!');
      closeBillingModal();
    } else {
      gooeyToast.error('Failed to update billing table', { description: result.error });
    }
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

                const isGcp = account.provider === 'GCP';
                const hasBillingTable = !!account.billingExportTable;

                return (
                  <tr
                    key={account.dbId}
                    className="group border-b border-slate-50 hover:bg-slate-50/50 transition-colors"
                  >
                    <td className="py-4 nowrap">
                      <div className="flex items-center gap-4">
                        <div className={`w-10 h-10 rounded-xl flex items-center justify-center ${providerInfo.class} shadow-sm group-hover:scale-110 transition-transform duration-300`}>
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
                        {isGcp && !hasBillingTable && (
                          <button
                            onClick={() => openBillingModal(account)}
                            className="h-8 rounded-lg px-2.5 bg-amber-50 text-amber-600 hover:bg-amber-600 hover:text-white transition-all flex items-center justify-center gap-1.5 shadow-sm border border-amber-200"
                            title="Configure BigQuery Billing Table"
                          >
                            <i className="fas fa-database text-xs" />
                            <span className="text-[10px] font-bold">Configure Billing</span>
                          </button>
                        )}
                        {isGcp && hasBillingTable && (
                          <button
                            onClick={() => openBillingModal(account)}
                            className="h-8 w-8 rounded-lg bg-slate-100 text-slate-500 hover:bg-indigo-500 hover:text-white transition-all flex items-center justify-center shadow-sm"
                            title={`Billing Table: ${account.billingExportTable}`}
                          >
                            <i className="fas fa-database text-xs" />
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

      {/* Billing Table Modal */}
      {billingModalAccount && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 backdrop-blur-sm"
          onClick={closeBillingModal}
        >
          <div
            className="bg-white rounded-2xl shadow-2xl w-full max-w-lg mx-4 overflow-hidden"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="px-6 py-5 border-b border-slate-100 bg-slate-50/50">
              <div className="flex items-center gap-3">
                <div className="w-10 h-10 rounded-xl bg-gradient-to-br from-blue-500 to-indigo-600 flex items-center justify-center shadow-lg shadow-indigo-200">
                  <i className="fas fa-database text-white" />
                </div>
                <div>
                  <h3 className="text-base font-bold text-slate-900">
                    {billingModalAccount.billingExportTable ? 'Update' : 'Configure'} Billing Export Table
                  </h3>
                  <p className="text-xs text-slate-500 mt-0.5">{billingModalAccount.name}</p>
                </div>
              </div>
            </div>
            <div className="px-6 py-5">
              <label className="block text-xs font-bold text-slate-700 mb-2">
                BigQuery Billing Export Table
              </label>
              <input
                type="text"
                value={billingTableInput}
                onChange={(e) => setBillingTableInput(e.target.value)}
                placeholder="project_id.billing_export.gcp_billing_export_v1_XXXXXX..."
                className="w-full px-4 py-3 rounded-xl border border-slate-200 text-sm font-mono focus:outline-none focus:ring-2 focus:ring-indigo-500 focus:border-indigo-500 transition-all"
                autoFocus
              />
              <p className="text-[11px] text-slate-400 mt-2">
                Format: <code className="bg-slate-100 px-1 py-0.5 rounded">project.dataset.table</code> — Find this in GCP Console under Billing &gt; Billing export &gt; BigQuery export.
              </p>
            </div>
            <div className="px-6 py-4 border-t border-slate-100 bg-slate-50/30 flex justify-end gap-3">
              <button
                onClick={closeBillingModal}
                className="px-4 py-2 rounded-xl text-xs font-bold text-slate-600 bg-white border border-slate-200 hover:bg-slate-50 transition-all"
              >
                Cancel
              </button>
              <button
                onClick={handleSaveBillingTable}
                disabled={billingModalLoading}
                className="px-4 py-2 rounded-xl text-xs font-bold text-white bg-indigo-600 hover:bg-indigo-700 transition-all shadow-lg shadow-indigo-200 disabled:opacity-50"
              >
                {billingModalLoading ? 'Saving...' : 'Save'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};

export default CloudAccountsTable;
