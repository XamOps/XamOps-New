import React, { useState } from 'react';

interface SyncAccountModalProps {
  isOpen: boolean;
  onClose: () => void;
  onSync: (accountId: string, accountName: string, regions: string) => Promise<void>;
}

const SyncAccountModal: React.FC<SyncAccountModalProps> = ({ isOpen, onClose, onSync }) => {
  const [accountId, setAccountId] = useState('');
  const [accountName, setAccountName] = useState('');
  const [regions, setRegions] = useState('');
  const [isSyncing, setIsSyncing] = useState(false);
  const [error, setError] = useState('');

  const handleSync = async () => {
    if (!accountId || accountId.length !== 12) {
      setError('Please enter a valid 12-digit AWS account ID');
      return;
    }

    setIsSyncing(true);
    setError('');

    try {
      await onSync(accountId, accountName, regions);
      // Reset form
      setAccountId('');
      setAccountName('');
      setRegions('');
      onClose();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to sync account');
    } finally {
      setIsSyncing(false);
    }
  };

  const handleClose = () => {
    setAccountId('');
    setAccountName('');
    setRegions('');
    setError('');
    onClose();
  };

  if (!isOpen) return null;

  return (
    <div className="fixed inset-0 z-50 overflow-y-auto" onClick={handleClose}>
      <div className="flex items-center justify-center min-h-screen px-4 pt-4 pb-20 text-center sm:block sm:p-0">
        {/* Backdrop */}
        <div className="fixed inset-0 transition-opacity bg-gray-900 bg-opacity-75"></div>

        <span className="hidden sm:inline-block sm:align-middle sm:h-screen">&#8203;</span>

        {/* Modal */}
        <div
          onClick={(e) => e.stopPropagation()}
          className="inline-block align-bottom bg-white rounded-xl text-left overflow-hidden shadow-2xl transform transition-all sm:my-8 sm:align-middle sm:max-w-lg sm:w-full"
        >
          {/* Header */}
          <div className="bg-gradient-to-r from-blue-500 to-blue-600 px-6 py-5">
            <div className="flex items-center justify-between">
              <div className="flex items-center space-x-3">
                <i className="fas fa-sync text-white text-3xl"></i>
                <h3 className="text-2xl font-bold text-white">Sync Customer Account</h3>
              </div>
              <button onClick={handleClose} className="text-white hover:text-gray-200 transition-colors">
                <i className="fas fa-times text-2xl"></i>
              </button>
            </div>
          </div>

          {/* Body */}
          <div className="px-6 py-6 space-y-5">
            {/* Account ID */}
            <div>
              <label className="block text-sm font-bold text-gray-800 mb-2 flex items-center">
                <i className="fas fa-building text-blue-600 mr-2"></i>
                AWS Account ID *
              </label>
              <input
                type="text"
                value={accountId}
                onChange={(e) => setAccountId(e.target.value.replace(/\D/g, '').slice(0, 12))}
                maxLength={12}
                placeholder="123456789012"
                className="w-full px-4 py-3 border-2 border-gray-300 rounded-lg focus:ring-2 focus:ring-blue-500 focus:border-blue-500 transition-all text-lg font-mono"
              />
              <p className="text-xs text-gray-600 mt-1">Enter the 12-digit AWS account ID</p>
            </div>

            {/* Account Name */}
            <div>
              <label className="block text-sm font-bold text-gray-800 mb-2 flex items-center">
                <i className="fas fa-tag text-blue-600 mr-2"></i>
                Account Name (Optional)
              </label>
              <input
                type="text"
                value={accountName}
                onChange={(e) => setAccountName(e.target.value)}
                placeholder="Production Account"
                className="w-full px-4 py-3 border-2 border-gray-300 rounded-lg focus:ring-2 focus:ring-blue-500 focus:border-blue-500 transition-all"
              />
            </div>

            {/* Regions */}
            <div>
              <label className="block text-sm font-bold text-gray-800 mb-2 flex items-center">
                <i className="fas fa-globe text-blue-600 mr-2"></i>
                Regions (Optional)
              </label>
              <input
                type="text"
                value={regions}
                onChange={(e) => setRegions(e.target.value)}
                placeholder="us-east-1,eu-west-1 or 'all'"
                className="w-full px-4 py-3 border-2 border-gray-300 rounded-lg focus:ring-2 focus:ring-blue-500 focus:border-blue-500 transition-all"
              />
              <p className="text-xs text-gray-600 mt-1">Leave empty for all regions</p>
            </div>

            {/* Info */}
            <div className="p-4 bg-blue-50 border-2 border-blue-200 rounded-xl">
              <div className="flex items-start space-x-3">
                <i className="fas fa-info-circle text-blue-600 mt-1 text-xl"></i>
                <div className="text-sm text-gray-800">
                  <p className="font-bold text-blue-900 mb-1">When to sync:</p>
                  <p>
                    After the customer has successfully deployed the CloudFormation stack in their AWS account.
                  </p>
                </div>
              </div>
            </div>

            {/* Error */}
            {error && (
              <div className="p-4 bg-red-50 border-2 border-red-200 rounded-xl">
                <div className="flex items-start space-x-3">
                  <i className="fas fa-exclamation-triangle text-red-600 mt-1"></i>
                  <p className="text-sm text-red-700 font-medium">{error}</p>
                </div>
              </div>
            )}
          </div>

          {/* Footer */}
          <div className="bg-gray-50 px-6 py-4 flex justify-end space-x-3 border-t-2 border-gray-200">
            <button
              onClick={handleClose}
              className="px-5 py-2 text-gray-700 hover:text-gray-900 font-semibold transition-colors"
            >
              Cancel
            </button>
            <button
              onClick={handleSync}
              disabled={isSyncing || !accountId || accountId.length !== 12}
              className="px-6 py-2 bg-blue-600 hover:bg-blue-700 text-white rounded-lg font-bold transition-colors disabled:opacity-50 disabled:cursor-not-allowed flex items-center space-x-2 shadow-lg hover:shadow-xl"
            >
              <i className={`fas ${isSyncing ? 'fa-spinner fa-spin' : 'fa-sync'}`}></i>
              <span>{isSyncing ? 'Syncing...' : 'Sync Account'}</span>
            </button>
          </div>
        </div>
      </div>
    </div>
  );
};

export default SyncAccountModal;
