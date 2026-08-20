import React, { useState } from 'react';
import { Link } from 'react-router-dom';
import { Icon } from '@iconify/react';
import { gooeyToast } from 'goey-toast';
import type { CloudAccount } from '../../types/accountManager';

interface CloudAccountsTableProps {
  accounts: CloudAccount[];
  isLoading: boolean;
  onVerify: (account: CloudAccount) => void;
  onRemove: (account: CloudAccount) => void;
  onUpdateBillingTable?: (accountDbId: number, billingTable: string) => Promise<{ success: boolean; error?: string }>;
}

const providerConfig: Record<string, { icon: string; bg: string; color: string }> = {
  AWS:   { icon: '/icons/aws-icon.png',   bg: 'bg-[#FF9900]/10', color: 'text-[#FF9900]' },
  GCP:   { icon: '/icons/gcp-icon.png',   bg: 'bg-[#4285F4]/10', color: 'text-[#4285F4]' },
  Azure: { icon: '/icons/azure-icon.png', bg: 'bg-[#0078D4]/10', color: 'text-[#0078D4]' },
};

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

  const getAccountId = (account: CloudAccount) =>
    account.id || account.azureSubscriptionId || 'N/A';

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
    if (!trimmed) { gooeyToast.warning('Please enter the BigQuery billing export table name.'); return; }
    const parts = trimmed.split('.');
    if (parts.length !== 3 || parts.some(p => !p.trim())) {
      gooeyToast.error('Invalid format', { description: 'Expected: project.dataset.table' });
      return;
    }
    setBillingModalLoading(true);
    const result = await onUpdateBillingTable(billingModalAccount.dbId, trimmed);
    setBillingModalLoading(false);
    if (result.success) { gooeyToast.success('Billing export table updated!'); closeBillingModal(); }
    else gooeyToast.error('Failed to update billing table', { description: result.error });
  };

  return (
    <>
      <div className="bg-white border border-[rgba(0,0,0,0.08)] rounded-xl overflow-hidden">
        {/* Header */}
        <div className="flex items-center justify-between px-5 py-3.5 border-b border-[rgba(0,0,0,0.06)]">
          <div className="flex items-center gap-2">
            <Icon icon="mdi:cloud-check-outline" width={18} className="text-[rgba(0,0,0,0.4)]" />
            <h2 className="text-[14px] font-semibold text-black">Connected Accounts</h2>
          </div>
          <span className="text-[11px] text-[rgba(0,0,0,0.4)] font-medium">
            {accounts.length} accounts
          </span>
        </div>

        {/* Loading */}
        {isLoading && (
          <div className="p-6 space-y-2">
            {[1, 2, 3].map(i => <div key={i} className="h-14 bg-[rgba(0,0,0,0.06)] rounded-xl animate-pulse" />)}
          </div>
        )}

        {/* Empty */}
        {!isLoading && accounts.length === 0 && (
          <div className="flex flex-col items-center justify-center py-16 px-6 text-center">
            <div className="w-14 h-14 bg-[rgba(0,0,0,0.04)] border border-[rgba(0,0,0,0.08)] rounded-xl flex items-center justify-center mb-4">
              <Icon icon="mdi:cloud-outline" width={28} color="rgba(0,0,0,0.3)" />
            </div>
            <h3 className="text-sm font-bold text-[rgba(0,0,0,0.7)] mb-1">No Cloud Accounts Connected</h3>
            <p className="text-sm text-[rgba(0,0,0,0.4)] max-w-xs mb-5">Start by adding your first AWS, GCP, or Azure account.</p>
            <Link
              to="/add-account"
              className="px-4 py-2 bg-[rgba(0,0,0,0.88)] hover:bg-black text-white text-xs font-medium rounded-md transition-colors"
            >
              Add New Account
            </Link>
          </div>
        )}

        {/* Table */}
        {!isLoading && accounts.length > 0 && (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="bg-[rgba(0,0,0,0.02)] border-b border-[rgba(0,0,0,0.06)] text-left">
                  {['Account Name', 'Account / Project ID', 'Access Level', 'Status', ''].map((h, idx) => (
                    <th
                      key={h}
                      className={`px-4 py-3 text-xs font-semibold text-[rgba(0,0,0,0.4)] uppercase tracking-wider whitespace-nowrap ${idx === 4 ? 'text-right' : ''}`}
                    >
                      {h}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody className="divide-y divide-[rgba(0,0,0,0.04)]">
                {accounts.map((account) => {
                  const p = providerConfig[account.provider] || { icon: 'mdi:cloud-outline', bg: 'bg-[rgba(0,0,0,0.06)]' };
                  const isConnected = account.status === 'CONNECTED';
                  const isPending   = account.status === 'PENDING';
                  const isGcp       = account.provider === 'GCP';
                  const hasBilling  = !!account.billingExportTable;
                  // Older backends omit billingSource; cspManaged carries the same meaning
                  // there, so fall back to it rather than mislabelling every GCP account.
                  const billingSource = account.billingSource
                    ?? (account.cspManaged ? 'XAMMER_CSP_DATASET' : 'OWN_EXPORT');
                  const isXammerExport = billingSource === 'XAMMER_EXPORT';

                  return (
                    <tr
                      key={account.dbId}
                      className="hover:bg-[rgba(0,0,0,0.025)] transition-colors"
                    >
                      {/* Name */}
                      <td className="px-4 py-3.5 whitespace-nowrap">
                        <div className="flex items-center gap-3">
                          <img src={p.icon} alt={account.provider} className="w-6 h-6 object-contain flex-shrink-0" />
                          <div>
                            <p className="text-[13px] font-semibold text-black leading-tight">{account.name}</p>
                            <p className="text-[10px] text-[rgba(0,0,0,0.4)] mt-0.5">{account.provider}</p>
                          </div>
                        </div>
                      </td>

                      {/* ID */}
                      <td className="px-4 py-3.5 whitespace-nowrap">
                        <span className="text-[12px] font-mono text-[rgba(0,0,0,0.55)]">
                          {getAccountId(account)}
                        </span>
                      </td>

                      {/* Access */}
                      <td className="px-4 py-3.5 whitespace-nowrap">
                        <span className="text-[12px] text-[rgba(0,0,0,0.55)]">
                          {account.access || 'Contributor'}
                        </span>
                      </td>

                      {/* Status */}
                      <td className="px-4 py-3.5 whitespace-nowrap">
                        <span className={`inline-flex items-center gap-1.5 text-[11px] font-medium ${
                          isConnected ? 'text-emerald-600' :
                          isPending   ? 'text-amber-600' :
                                        'text-rose-600'
                        }`}>
                          <span className={`w-1.5 h-1.5 rounded-full ${isConnected ? 'bg-emerald-500' : isPending ? 'bg-amber-500' : 'bg-rose-500'}`} />
                          {isConnected ? 'Connected' : isPending ? 'Pending' : account.status}
                        </span>
                      </td>

                      {/* Actions */}
                      <td className="px-4 py-3.5 whitespace-nowrap">
                        <div className="flex items-center justify-end gap-2">
                          {isPending && account.provider === 'AWS' && (
                            <button
                              onClick={() => onVerify(account)}
                              title="Verify Connection"
                              className="h-8 w-8 rounded-lg bg-amber-50 border border-amber-100 text-amber-600 hover:bg-amber-500 hover:text-white hover:border-amber-500 transition-all flex items-center justify-center"
                            >
                              <Icon icon="mdi:check-circle-outline" width={14} />
                            </button>
                          )}
                          {isGcp && account.cspManaged && account.billingDataAvailable === true && (
                            <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-md border border-blue-200 bg-blue-50 text-[10px] font-semibold text-blue-600">
                              CSP Managed
                            </span>
                          )}
                          {isGcp && account.cspManaged && account.billingDataAvailable === false && (
                            <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-md border border-amber-200 bg-amber-50 text-[10px] font-semibold text-amber-600" title="Project not found in Sisl billing dataset yet">
                              No Billing Data
                            </span>
                          )}
                          {isGcp && account.cspManaged && account.billingDataAvailable == null && (
                            <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-md border border-[rgba(0,0,0,0.1)] bg-[rgba(0,0,0,0.04)] text-[10px] font-semibold text-[rgba(0,0,0,0.4)]">
                              Pending Sync
                            </span>
                          )}
                          {isGcp && isXammerExport && (
                            <span
                              className="inline-flex items-center gap-1 px-2 py-0.5 rounded-md border border-blue-200 bg-blue-50 text-[10px] font-semibold text-blue-600"
                              title="Billed by Xammer — costs read from a BigQuery billing export table"
                            >
                              Xammer Billed
                            </span>
                          )}
                          {isGcp && !account.cspManaged && !hasBilling && (
                            <button
                              onClick={() => openBillingModal(account)}
                              title="Configure Billing Table"
                              className="h-8 px-2.5 rounded-lg bg-amber-50 border border-amber-100 text-amber-600 hover:bg-amber-500 hover:text-white hover:border-amber-500 transition-all flex items-center gap-1.5 text-[10px] font-bold"
                            >
                              <Icon icon="mdi:database-outline" width={12} />
                              Configure Billing
                            </button>
                          )}
                          {isGcp && !account.cspManaged && hasBilling && (
                            <button
                              onClick={() => openBillingModal(account)}
                              title={`Billing: ${account.billingExportTable}`}
                              className="h-8 w-8 rounded-lg bg-[rgba(0,0,0,0.04)] border border-[rgba(0,0,0,0.08)] text-[rgba(0,0,0,0.5)] hover:bg-indigo-500 hover:text-white hover:border-indigo-500 transition-all flex items-center justify-center"
                            >
                              <Icon icon="mdi:database-outline" width={14} />
                            </button>
                          )}
                          <button
                            onClick={() => onRemove(account)}
                            title="Remove Account"
                            className="h-8 w-8 rounded-lg bg-rose-50 border border-rose-100 text-rose-500 hover:bg-rose-500 hover:text-white hover:border-rose-500 transition-all flex items-center justify-center"
                          >
                            <Icon icon="mdi:trash-can-outline" width={14} />
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

      {/* Billing Modal */}
      {billingModalAccount && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 backdrop-blur-sm"
          onClick={closeBillingModal}
        >
          <div
            className="bg-white rounded-xl shadow-2xl w-full max-w-lg mx-4 overflow-hidden"
            onClick={e => e.stopPropagation()}
          >
            <div className="flex items-center justify-between px-6 py-4 border-b border-[rgba(0,0,0,0.06)]">
              <div className="flex items-center gap-3">
                <div className="w-8 h-8 bg-indigo-50 border border-indigo-100 rounded-lg flex items-center justify-center">
                  <Icon icon="mdi:database-outline" width={16} color="#4F46E5" />
                </div>
                <div>
                  <h3 className="text-sm font-bold text-[rgba(0,0,0,0.88)]">
                    {billingModalAccount.billingExportTable ? 'Update' : 'Configure'} Billing Export Table
                  </h3>
                  <p className="text-xs text-[rgba(0,0,0,0.4)]">{billingModalAccount.name}</p>
                </div>
              </div>
              <button
                onClick={closeBillingModal}
                className="w-8 h-8 rounded-lg hover:bg-[rgba(0,0,0,0.04)] flex items-center justify-center text-[rgba(0,0,0,0.4)] hover:text-[rgba(0,0,0,0.7)] transition-colors"
              >
                <Icon icon="mdi:close" width={16} />
              </button>
            </div>
            <div className="px-6 py-5 space-y-3">
              <label htmlFor="billingTableInput" className="flex items-center gap-1.5 text-xs font-semibold text-[rgba(0,0,0,0.7)]">
                <Icon icon="mdi:database-outline" width={14} color="rgba(0,0,0,0.4)" />
                BigQuery Billing Export Table
              </label>
              <input
                id="billingTableInput"
                value={billingTableInput}
                onChange={e => setBillingTableInput(e.target.value)}
                placeholder="project_id.billing_export.gcp_billing_export_v1_XXXXXX"
                className="w-full font-mono text-xs bg-[rgba(0,0,0,0.04)] border border-[rgba(0,0,0,0.08)] rounded-lg px-3 py-2 focus:outline-none focus:border-[rgba(0,0,0,0.2)] text-[rgba(0,0,0,0.88)]"
                autoFocus
              />
              <p className="text-[11px] text-[rgba(0,0,0,0.4)]">
                Format: <code className="bg-[rgba(0,0,0,0.04)] px-1 py-0.5 rounded">project.dataset.table</code> — Find this in GCP Console under Billing › Billing export › BigQuery export.
              </p>
              {billingTableInput && billingTableInput.split('.').length !== 3 && (
                <div className="flex items-center gap-2 text-xs text-amber-600 bg-amber-50 border border-amber-100 px-3 py-2 rounded-lg">
                  <Icon icon="mdi:alert-circle-outline" width={14} className="flex-shrink-0" />
                  Must be in format: project.dataset.table
                </div>
              )}
            </div>
            <div className="flex justify-end gap-2 px-6 py-4 border-t border-[rgba(0,0,0,0.06)] bg-[rgba(0,0,0,0.02)]">
              <button
                onClick={closeBillingModal}
                className="px-3 py-1.5 text-xs font-medium bg-[rgba(0,0,0,0.04)] border border-[rgba(0,0,0,0.08)] text-[rgba(0,0,0,0.7)] rounded-md hover:bg-[rgba(0,0,0,0.06)] transition-colors"
              >
                Cancel
              </button>
              <button
                onClick={handleSaveBillingTable}
                disabled={billingModalLoading}
                className="px-3 py-1.5 text-xs font-medium bg-[rgba(0,0,0,0.88)] hover:bg-black text-white rounded-md transition-colors disabled:opacity-50 flex items-center gap-1.5"
              >
                {billingModalLoading
                  ? <><Icon icon="mdi:loading" width={14} className="animate-spin" />Saving...</>
                  : 'Save'
                }
              </button>
            </div>
          </div>
        </div>
      )}
    </>
  );
};

export default CloudAccountsTable;
