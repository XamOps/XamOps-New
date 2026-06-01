import React, { useState } from 'react';
import { Icon } from '@iconify/react';
import { DialogRoot, DialogPanel } from '../ui';

interface SyncAccountModalProps {
  isOpen: boolean;
  onClose: () => void;
  onSync: (accountId: string, accountName: string, regions: string) => Promise<void>;
}

const inputCls = 'w-full px-3 py-2.5 border border-[rgba(0,0,0,0.15)] rounded-lg text-[13px] text-[rgba(0,0,0,0.88)] focus:ring-2 focus:ring-indigo-400 focus:border-indigo-400 outline-none bg-white';
const labelCls = 'block text-[12px] font-semibold text-[rgba(0,0,0,0.65)] mb-1.5 flex items-center gap-1.5';

const SyncAccountModal: React.FC<SyncAccountModalProps> = ({ isOpen, onClose, onSync }) => {
  const [accountId,   setAccountId]   = useState('');
  const [accountName, setAccountName] = useState('');
  const [regions,     setRegions]     = useState('');
  const [isSyncing,   setIsSyncing]   = useState(false);
  const [error,       setError]       = useState('');

  const handleSync = async () => {
    if (!accountId || accountId.length !== 12) {
      setError('Please enter a valid 12-digit AWS account ID');
      return;
    }
    setIsSyncing(true);
    setError('');
    try {
      await onSync(accountId, accountName, regions);
      setAccountId(''); setAccountName(''); setRegions('');
      onClose();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to sync account');
    } finally {
      setIsSyncing(false);
    }
  };

  const handleClose = () => {
    setAccountId(''); setAccountName(''); setRegions(''); setError('');
    onClose();
  };

  return (
    <DialogRoot isOpen={isOpen} onClose={handleClose}>
      <DialogPanel
        title={
          <div className="flex items-center gap-2">
            <Icon icon="mdi:sync" width={16} color="#6366F1" />
            Sync Customer Account
          </div>
        }
        onClose={handleClose}
        footer={
          <>
            <button
              onClick={handleClose}
              className="px-5 py-2.5 text-[13px] font-semibold text-[rgba(0,0,0,0.7)] bg-[rgba(0,0,0,0.04)] border border-[rgba(0,0,0,0.08)] rounded-md hover:bg-[rgba(0,0,0,0.06)] transition-colors"
            >
              Cancel
            </button>
            <button
              onClick={handleSync}
              disabled={isSyncing || !accountId || accountId.length !== 12}
              className="flex items-center gap-2 px-5 py-2.5 text-[13px] font-bold text-white bg-[rgba(0,0,0,0.88)] hover:bg-black rounded-md transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
            >
              <Icon icon={isSyncing ? 'mdi:loading' : 'mdi:sync'} width={14} className={isSyncing ? 'animate-spin' : ''} />
              {isSyncing ? 'Syncing...' : 'Sync Account'}
            </button>
          </>
        }
      >
        <div className="space-y-4">
          <div>
            <label className={labelCls}>
              <Icon icon="mdi:office-building-outline" width={12} color="#6366F1" />
              AWS Account ID <span className="text-red-500">*</span>
            </label>
            <input
              type="text"
              value={accountId}
              onChange={(e) => setAccountId(e.target.value.replace(/\D/g, '').slice(0, 12))}
              maxLength={12}
              placeholder="123456789012"
              className={`${inputCls} font-mono text-[15px]`}
            />
            <p className="text-[11px] text-[rgba(0,0,0,0.4)] mt-1">Enter the 12-digit AWS account ID</p>
          </div>

          <div>
            <label className={labelCls}>
              <Icon icon="mdi:tag-outline" width={12} color="#6366F1" />
              Account Name <span className="text-[rgba(0,0,0,0.35)] font-normal">(Optional)</span>
            </label>
            <input
              type="text"
              value={accountName}
              onChange={(e) => setAccountName(e.target.value)}
              placeholder="Production Account"
              className={inputCls}
            />
          </div>

          <div>
            <label className={labelCls}>
              <Icon icon="mdi:earth" width={12} color="#6366F1" />
              Regions <span className="text-[rgba(0,0,0,0.35)] font-normal">(Optional)</span>
            </label>
            <input
              type="text"
              value={regions}
              onChange={(e) => setRegions(e.target.value)}
              placeholder="us-east-1,eu-west-1 or 'all'"
              className={inputCls}
            />
            <p className="text-[11px] text-[rgba(0,0,0,0.4)] mt-1">Leave empty for all regions</p>
          </div>

          <div className="p-4 bg-indigo-50 border border-indigo-100 rounded-xl flex items-start gap-3">
            <Icon icon="mdi:information-outline" width={15} color="#4F46E5" className="mt-0.5 flex-shrink-0" />
            <div className="text-[13px] text-[rgba(0,0,0,0.65)]">
              <p className="font-bold text-[rgba(0,0,0,0.88)] mb-1">When to sync:</p>
              <p>After the customer has successfully deployed the CloudFormation stack in their AWS account.</p>
            </div>
          </div>

          {error && (
            <div className="p-4 bg-red-50 border border-red-200 rounded-xl flex items-start gap-3">
              <Icon icon="mdi:alert-circle-outline" width={15} color="#DC2626" className="mt-0.5 flex-shrink-0" />
              <p className="text-[13px] text-red-700 font-medium">{error}</p>
            </div>
          )}
        </div>
      </DialogPanel>
    </DialogRoot>
  );
};

export default SyncAccountModal;
