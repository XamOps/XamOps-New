import React, { useState } from 'react';
import { Link } from 'react-router-dom';
import { motion, AnimatePresence } from 'framer-motion';
import { Cloud, Database, Trash2, CheckCircle2, Loader2, AlertCircle, X } from 'lucide-react';
import { gooeyToast } from 'goey-toast';
import { Card } from '../ui/card';
import { Button } from '../ui/button';
import { Input } from '../ui/input';
import { Label } from '../ui/label';
import { Badge } from '../ui/badge';
import type { CloudAccount } from '../../types/accountManager';

interface CloudAccountsTableProps {
  accounts: CloudAccount[];
  isLoading: boolean;
  onVerify: (account: CloudAccount) => void;
  onRemove: (account: CloudAccount) => void;
  onUpdateBillingTable?: (accountDbId: number, billingTable: string) => Promise<{ success: boolean; error?: string }>;
}

const providerConfig: Record<string, { icon: string; bg: string; text: string }> = {
  AWS:   { icon: 'fab fa-aws',       bg: 'bg-gradient-to-br from-orange-400 to-orange-600', text: 'text-white' },
  GCP:   { icon: 'fab fa-google',    bg: 'bg-gradient-to-br from-blue-400 to-green-500',    text: 'text-white' },
  Azure: { icon: 'fab fa-microsoft', bg: 'bg-gradient-to-br from-cyan-500 to-blue-600',     text: 'text-white' },
};

const rowVariants = {
  hidden: { opacity: 0, x: -10 },
  visible: (i: number) => ({ opacity: 1, x: 0, transition: { delay: i * 0.05, duration: 0.25 } }),
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
      <Card className="overflow-hidden shadow-sm">
        {/* Header */}
        <div className="h-1 bg-gradient-to-r from-indigo-500 to-violet-500" />
        <div className="flex items-center justify-between px-6 py-4 border-b border-slate-100 bg-slate-50/40">
          <div className="flex items-center gap-3">
            <div className="w-9 h-9 rounded-xl bg-gradient-to-br from-indigo-500 to-violet-600 flex items-center justify-center shadow-md shadow-indigo-200">
              <Cloud className="w-4 h-4 text-white" />
            </div>
            <div>
              <h2 className="text-base font-bold text-slate-900 leading-tight">Connected Cloud Accounts</h2>
              <p className="text-[10px] font-medium text-slate-400 uppercase tracking-wider">Multi-cloud inventory</p>
            </div>
          </div>
          <Badge variant="outline" className="text-[10px] font-bold uppercase tracking-wider">
            {accounts.length} Accounts
          </Badge>
        </div>

        {/* Loading */}
        {isLoading && (
          <div className="p-6 space-y-2">
            {[1, 2, 3].map(i => <div key={i} className="h-14 bg-slate-100 rounded-xl animate-pulse" />)}
          </div>
        )}

        {/* Empty */}
        {!isLoading && accounts.length === 0 && (
          <div className="flex flex-col items-center justify-center py-16 px-6 text-center">
            <div className="w-16 h-16 rounded-full bg-indigo-50 flex items-center justify-center mb-4">
              <Cloud className="w-7 h-7 text-indigo-300" />
            </div>
            <h3 className="text-base font-bold text-slate-700 mb-1">No Cloud Accounts Connected</h3>
            <p className="text-sm text-slate-400 max-w-xs mb-5">Start by adding your first AWS, GCP, or Azure account.</p>
            <Link to="/add-account">
              <Button size="sm" className="gap-2">
                <span>Add New Account</span>
              </Button>
            </Link>
          </div>
        )}

        {/* Table */}
        {!isLoading && accounts.length > 0 && (
          <div className="overflow-x-auto">
            <table className="w-full text-sm border-collapse">
              <thead>
                <tr className="bg-slate-50/60">
                  {['Account Name', 'Account / Project ID', 'Access Level', 'Status', ''].map(h => (
                    <th key={h} className="px-4 py-3 text-left text-[10px] font-bold text-slate-400 uppercase tracking-widest whitespace-nowrap last:text-right">
                      {h}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {accounts.map((account, i) => {
                  const p = providerConfig[account.provider] || { icon: 'fas fa-cloud', bg: 'bg-slate-200', text: 'text-slate-500' };
                  const isConnected = account.status === 'CONNECTED';
                  const isPending   = account.status === 'PENDING';
                  const isGcp       = account.provider === 'GCP';
                  const hasBilling  = !!account.billingExportTable;

                  return (
                    <motion.tr
                      key={account.dbId}
                      custom={i}
                      variants={rowVariants}
                      initial="hidden"
                      animate="visible"
                      className="group border-b border-slate-50 hover:bg-slate-50/60 transition-colors"
                    >
                      {/* Name */}
                      <td className="px-4 py-3.5 whitespace-nowrap">
                        <div className="flex items-center gap-3">
                          <div className={`w-9 h-9 rounded-xl flex items-center justify-center ${p.bg} ${p.text} shadow-sm group-hover:scale-110 transition-transform duration-200 flex-shrink-0`}>
                            <i className={`${p.icon} text-sm`} />
                          </div>
                          <div>
                            <p className="font-bold text-slate-900 leading-tight">{account.name}</p>
                            <p className="text-[10px] font-bold text-slate-400 uppercase tracking-wider mt-0.5">{account.provider}</p>
                          </div>
                        </div>
                      </td>

                      {/* ID */}
                      <td className="px-4 py-3.5 whitespace-nowrap">
                        <code className="text-[11px] font-mono font-semibold px-2 py-1 bg-slate-100 text-slate-600 rounded-lg border border-slate-200">
                          {getAccountId(account)}
                        </code>
                      </td>

                      {/* Access */}
                      <td className="px-4 py-3.5 whitespace-nowrap">
                        <div className="flex items-center gap-1.5 text-xs font-semibold text-slate-500">
                          <i className="fas fa-shield-alt text-slate-300 text-[11px]" />
                          {account.access || 'Contributor'}
                        </div>
                      </td>

                      {/* Status */}
                      <td className="px-4 py-3.5 whitespace-nowrap">
                        <span className={`inline-flex items-center gap-1.5 px-2.5 py-1 rounded-full border text-[10px] font-bold uppercase tracking-wider ${
                          isConnected ? 'bg-emerald-50 border-emerald-100 text-emerald-600' :
                          isPending   ? 'bg-amber-50 border-amber-100 text-amber-600' :
                                        'bg-rose-50 border-rose-100 text-rose-600'
                        }`}>
                          <span className={`w-1.5 h-1.5 rounded-full ${isConnected ? 'bg-emerald-500 animate-pulse' : isPending ? 'bg-amber-500' : 'bg-rose-500'}`} />
                          {account.status}
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
                              <CheckCircle2 className="w-3.5 h-3.5" />
                            </button>
                          )}
                          {isGcp && account.cspManaged && (
                            <Badge variant="outline" className="text-xs text-blue-600 border-blue-300 bg-blue-50">
                              CSP Managed
                            </Badge>
                          )}
                          {isGcp && !account.cspManaged && !hasBilling && (
                            <button
                              onClick={() => openBillingModal(account)}
                              title="Configure Billing Table"
                              className="h-8 px-2.5 rounded-lg bg-amber-50 border border-amber-100 text-amber-600 hover:bg-amber-500 hover:text-white hover:border-amber-500 transition-all flex items-center gap-1.5 text-[10px] font-bold"
                            >
                              <Database className="w-3 h-3" />
                              Configure Billing
                            </button>
                          )}
                          {isGcp && !account.cspManaged && hasBilling && (
                            <button
                              onClick={() => openBillingModal(account)}
                              title={`Billing: ${account.billingExportTable}`}
                              className="h-8 w-8 rounded-lg bg-slate-100 border border-slate-200 text-slate-500 hover:bg-indigo-500 hover:text-white hover:border-indigo-500 transition-all flex items-center justify-center"
                            >
                              <Database className="w-3.5 h-3.5" />
                            </button>
                          )}
                          <button
                            onClick={() => onRemove(account)}
                            title="Remove Account"
                            className="h-8 w-8 rounded-lg bg-rose-50 border border-rose-100 text-rose-500 hover:bg-rose-500 hover:text-white hover:border-rose-500 transition-all flex items-center justify-center"
                          >
                            <Trash2 className="w-3.5 h-3.5" />
                          </button>
                        </div>
                      </td>
                    </motion.tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </Card>

      {/* Billing Modal */}
      <AnimatePresence>
        {billingModalAccount && (
          <motion.div
            className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 backdrop-blur-sm"
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            onClick={closeBillingModal}
          >
            <motion.div
              className="bg-white rounded-2xl shadow-2xl w-full max-w-lg mx-4 overflow-hidden"
              initial={{ opacity: 0, scale: 0.95, y: 10 }}
              animate={{ opacity: 1, scale: 1, y: 0 }}
              exit={{ opacity: 0, scale: 0.95, y: 10 }}
              transition={{ type: 'spring', stiffness: 300, damping: 25 }}
              onClick={e => e.stopPropagation()}
            >
              <div className="h-1 bg-gradient-to-r from-blue-500 to-indigo-600" />
              <div className="flex items-center justify-between px-6 py-4 border-b border-slate-100">
                <div className="flex items-center gap-3">
                  <div className="w-9 h-9 rounded-xl bg-gradient-to-br from-blue-500 to-indigo-600 flex items-center justify-center shadow-md shadow-indigo-200">
                    <Database className="w-4 h-4 text-white" />
                  </div>
                  <div>
                    <h3 className="text-base font-bold text-slate-900">
                      {billingModalAccount.billingExportTable ? 'Update' : 'Configure'} Billing Export Table
                    </h3>
                    <p className="text-xs text-slate-400">{billingModalAccount.name}</p>
                  </div>
                </div>
                <button onClick={closeBillingModal} className="w-8 h-8 rounded-lg hover:bg-slate-100 flex items-center justify-center text-slate-400 hover:text-slate-600 transition-colors">
                  <X className="w-4 h-4" />
                </button>
              </div>
              <div className="px-6 py-5 space-y-3">
                <Label htmlFor="billingTableInput" className="flex items-center gap-1.5">
                  <Database className="w-3.5 h-3.5 text-slate-400" />
                  BigQuery Billing Export Table
                </Label>
                <Input
                  id="billingTableInput"
                  value={billingTableInput}
                  onChange={e => setBillingTableInput(e.target.value)}
                  placeholder="project_id.billing_export.gcp_billing_export_v1_XXXXXX"
                  className="font-mono text-xs"
                  autoFocus
                />
                <p className="text-[11px] text-slate-400">
                  Format: <code className="bg-slate-100 px-1 py-0.5 rounded">project.dataset.table</code> — Find this in GCP Console under Billing › Billing export › BigQuery export.
                </p>
                {billingTableInput && billingTableInput.split('.').length !== 3 && (
                  <div className="flex items-center gap-2 text-xs text-amber-600 bg-amber-50 border border-amber-100 px-3 py-2 rounded-lg">
                    <AlertCircle className="w-3.5 h-3.5 flex-shrink-0" />
                    Must be in format: project.dataset.table
                  </div>
                )}
              </div>
              <div className="flex justify-end gap-2 px-6 py-4 border-t border-slate-100 bg-slate-50/40">
                <Button variant="outline" size="sm" onClick={closeBillingModal}>Cancel</Button>
                <Button size="sm" onClick={handleSaveBillingTable} disabled={billingModalLoading} className="gap-2">
                  {billingModalLoading ? <><Loader2 className="w-3.5 h-3.5 animate-spin" />Saving...</> : 'Save'}
                </Button>
              </div>
            </motion.div>
          </motion.div>
        )}
      </AnimatePresence>
    </>
  );
};

export default CloudAccountsTable;
