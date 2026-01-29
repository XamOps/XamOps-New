/**
 * VerificationModal Component
 *
 * Modal for verifying AWS account connections
 */

import React, { useState, useEffect } from 'react';
import type { CloudAccount, VerificationDetails } from '../../types/accountManager';

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

  if (!isOpen) return null;

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal-content max-w-lg" onClick={(e) => e.stopPropagation()}>
        {/* Header */}
        <div className="modal-header">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 bg-gradient-to-br from-amber-500 to-orange-600 rounded-xl flex items-center justify-center">
              <i className="fas fa-check-double text-white text-lg" />
            </div>
            <div>
              <h3 className="text-xl font-bold text-gray-900">Verify AWS Account Connection</h3>
              <p className="text-sm text-gray-500">Complete the account verification</p>
            </div>
          </div>
          <button
            onClick={onClose}
            className="text-gray-400 hover:text-gray-600 transition-colors"
          >
            <i className="fas fa-times text-xl" />
          </button>
        </div>

        {/* Body */}
        <div className="modal-body">
          <div className="bg-blue-50 border border-blue-100 rounded-xl p-4 mb-6">
            <div className="flex gap-3">
              <i className="fas fa-info-circle text-blue-500 mt-0.5" />
              <p className="text-sm text-blue-700">
                After deploying the CloudFormation stack in your AWS account, enter the details
                from the stack's "Outputs" tab to complete the connection.
              </p>
            </div>
          </div>

          <form onSubmit={handleSubmit} className="space-y-5">
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
              <p className="text-xs text-gray-500 mt-1">12-digit AWS account identifier</p>
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
              <p className="text-xs text-gray-500 mt-1">Name of the IAM role created by CloudFormation</p>
            </div>

            {error && (
              <div className="bg-red-50 border border-red-100 rounded-xl p-4">
                <div className="flex gap-3">
                  <i className="fas fa-exclamation-circle text-red-500 mt-0.5" />
                  <p className="text-sm text-red-700">{error}</p>
                </div>
              </div>
            )}

            {/* Footer */}
            <div className="flex justify-end gap-3 pt-4 border-t border-gray-100">
              <button
                type="button"
                onClick={onClose}
                className="btn-secondary"
                disabled={isVerifying}
              >
                Cancel
              </button>
              <button
                type="submit"
                className="btn-primary"
                disabled={isVerifying}
              >
                {isVerifying ? (
                  <>
                    <span className="loading-spinner mr-2" />
                    Verifying...
                  </>
                ) : (
                  <>
                    <i className="fas fa-check-circle mr-2" />
                    Verify & Connect
                  </>
                )}
              </button>
            </div>
          </form>
        </div>
      </div>
    </div>
  );
};

export default VerificationModal;
