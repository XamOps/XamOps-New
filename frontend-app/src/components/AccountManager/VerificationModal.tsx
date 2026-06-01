/**
 * VerificationModal Component
 *
 * Modal for verifying AWS account connections
 */

import React, { useState, useEffect } from 'react';
import { Icon } from '@iconify/react';
import type { CloudAccount, VerificationDetails } from '../../types/accountManager';
import { DialogRoot, DialogPanel } from '../ui';

interface VerificationModalProps {
  isOpen: boolean;
  account: CloudAccount | null;
  onClose: () => void;
  onVerify: (details: VerificationDetails) => Promise<{ success: boolean; error?: string }>;
}

const VerificationModal: React.FC<VerificationModalProps> = ({
  isOpen,
  account,
  onClose,
  onVerify,
}) => {
  const [awsAccountId, setAwsAccountId] = useState('');
  const [roleName, setRoleName] = useState('');
  const [isVerifying, setIsVerifying] = useState(false);
  const [error, setError] = useState('');

  // Reset form when modal opens with a new account
  useEffect(() => {
    if (isOpen && account) {
      setAwsAccountId('');
      setRoleName('');
      setError('');
    }
  }, [isOpen, account]);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setIsVerifying(true);
    setError('');

    const result = await onVerify({
      awsAccountId,
      roleName,
      externalId: account?.externalId,
    });

    setIsVerifying(false);

    if (result.success) {
      onClose();
    } else {
      setError(result.error || 'Verification failed.');
    }
  };

  return (
    <DialogRoot isOpen={isOpen} onClose={onClose} maxWidth="max-w-lg">
      <DialogPanel
        title={
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 bg-gradient-to-br from-amber-500 to-orange-600 rounded-xl flex items-center justify-center">
              <Icon icon="mdi:check-all" width={22} color="white" />
            </div>
            <div>
              <div className="text-xl font-bold text-[rgba(0,0,0,0.88)]">Verify AWS Account Connection</div>
              <div className="text-sm text-[rgba(0,0,0,0.5)]">Complete the account verification</div>
            </div>
          </div>
        }
        onClose={onClose}
        footer={
          <>
            <button type="button" onClick={onClose} className="btn-secondary" disabled={isVerifying}>
              Cancel
            </button>
            <button type="submit" form="verification-form" className="btn-primary" disabled={isVerifying}>
              {isVerifying ? (
                <><span className="loading-spinner mr-2" />Verifying...</>
              ) : (
                <><Icon icon="mdi:check-circle-outline" width={16} className="inline mr-2" />Verify & Connect</>
              )}
            </button>
          </>
        }
      >
        <div className="bg-blue-50 border border-blue-100 rounded-xl p-4 mb-6">
          <div className="flex gap-3">
            <Icon icon="mdi:information-outline" width={16} color="#3B82F6" className="mt-0.5 flex-shrink-0" />
            <p className="text-sm text-blue-700">
              After deploying the CloudFormation stack in your AWS account, enter the details
              from the stack's "Outputs" tab to complete the connection.
            </p>
          </div>
        </div>

        <form id="verification-form" onSubmit={handleSubmit} className="space-y-5">
          <div>
            <label htmlFor="awsAccountId" className="form-label">
              AWS Account ID
            </label>
            <input
              type="text"
              id="awsAccountId"
              value={awsAccountId}
              onChange={(e) => setAwsAccountId(e.target.value)}
              placeholder="123456789012"
              className="form-input"
              required
              pattern="[0-9]{12}"
              title="AWS Account ID must be 12 digits"
            />
            <p className="text-xs text-[rgba(0,0,0,0.5)] mt-1">12-digit AWS account identifier</p>
          </div>

          <div>
            <label htmlFor="roleName" className="form-label">
              IAM Role Name
            </label>
            <input
              type="text"
              id="roleName"
              value={roleName}
              onChange={(e) => setRoleName(e.target.value)}
              placeholder="XamOps-ReadOnly-Role"
              className="form-input"
              required
            />
            <p className="text-xs text-[rgba(0,0,0,0.5)] mt-1">Name of the IAM role created by CloudFormation</p>
          </div>

          {error && (
            <div className="bg-red-50 border border-red-100 rounded-xl p-4">
              <div className="flex gap-3">
                <Icon icon="mdi:alert-circle-outline" width={16} color="#EF4444" className="mt-0.5 flex-shrink-0" />
                <p className="text-sm text-red-700">{error}</p>
              </div>
            </div>
          )}
        </form>
      </DialogPanel>
    </DialogRoot>
  );
};

export default VerificationModal;
