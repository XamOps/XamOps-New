/**
 * AWSForm Component
 *
 * Form for adding an AWS account using CloudFormation cross-account role
 * Includes both initial form and verification step
 */

import React from 'react';
import type { AccessType, VerificationDetails } from '../../types/addAccount';

interface AWSFormProps {
  accountName: string;
  accessType: AccessType;
  isLoading: boolean;
  isVerifying: boolean;
  verificationDetails: VerificationDetails;
  verificationError: string;
  onAccountNameChange: (name: string) => void;
  onAccessTypeChange: (type: AccessType) => void;
  onVerificationDetailsChange: (details: Partial<VerificationDetails>) => void;
  onLaunchStack: () => void;
  onVerifyAccount: () => void;
  onOpenPolicyModal: () => void;
}

const AWSForm: React.FC<AWSFormProps> = ({
  accountName,
  accessType,
  isLoading,
  isVerifying,
  verificationDetails,
  verificationError,
  onAccountNameChange,
  onAccessTypeChange,
  onVerificationDetailsChange,
  onLaunchStack,
  onVerifyAccount,
  onOpenPolicyModal,
}) => {
  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    if (isVerifying) {
      onVerifyAccount();
    } else {
      onLaunchStack();
    }
  };

  if (isVerifying) {
    return (
      <div className="form-card fade-in">
        <div className="success-message" style={{ marginBottom: '1.5rem' }}>
          <h2>Stack Launched!</h2>
          <p>Complete the final step to verify the connection.</p>
        </div>

        <form onSubmit={handleSubmit}>
          <div className="form-group">
            <label htmlFor="awsAccountId" className="form-label">
              AWS Account ID
            </label>
            <input
              type="text"
              id="awsAccountId"
              className="form-input"
              placeholder="123456789012"
              value={verificationDetails.awsAccountId}
              onChange={(e) =>
                onVerificationDetailsChange({ awsAccountId: e.target.value })
              }
              required
            />
          </div>

          <div className="form-group">
            <label htmlFor="roleName" className="form-label">
              IAM Role Name
            </label>
            <input
              type="text"
              id="roleName"
              className="form-input"
              placeholder="XamOps-ReadOnly-Role"
              value={verificationDetails.roleName}
              onChange={(e) =>
                onVerificationDetailsChange({ roleName: e.target.value })
              }
              required
            />
          </div>

          {verificationError && (
            <div className="error-message">
              <i className="fas fa-exclamation-circle" />
              {verificationError}
            </div>
          )}

          <div className="form-actions">
            <button
              type="submit"
              className="btn-primary"
              disabled={isLoading}
            >
              {isLoading ? (
                <>
                  <span className="loading-spinner" />
                  Verifying...
                </>
              ) : (
                'Verify & Connect'
              )}
            </button>
          </div>
        </form>
      </div>
    );
  }

  return (
    <div className="form-card fade-in">
      <div className="form-card-header">
        <div
          className="header-icon aws"
          style={{ width: '2.5rem', height: '2.5rem', borderRadius: '0.75rem' }}
        >
          <i className="fas fa-cog" style={{ fontSize: '1rem' }} />
        </div>
        <div>
          <h2 className="form-card-title">Cross-Account Role Connection</h2>
          <p className="form-card-subtitle">
            Configure secure access to your AWS account
          </p>
        </div>
      </div>

      <form onSubmit={handleSubmit}>
        <div className="form-group">
          <label htmlFor="accountName" className="form-label">
            <i className="fas fa-user-tag" />
            Account Name
          </label>
          <input
            type="text"
            id="accountName"
            className="form-input"
            placeholder="e.g. AWS Production, AWS Development"
            value={accountName}
            onChange={(e) => onAccountNameChange(e.target.value)}
            required
          />
          <p className="form-hint">
            Choose a descriptive name to identify this account
          </p>
        </div>

        <div className="form-group">
          <label className="form-label">
            <i className="fas fa-key" />
            Access Level
          </label>
          <div
            className={`radio-option ${accessType === 'read-only' ? 'selected' : ''}`}
            onClick={() => onAccessTypeChange('read-only')}
          >
            <input
              type="radio"
              id="read-only"
              name="access-type"
              value="read-only"
              checked={accessType === 'read-only'}
              onChange={() => onAccessTypeChange('read-only')}
            />
            <div className="radio-option-content">
              <label htmlFor="read-only" className="radio-option-label">
                Read-Only Access
              </label>
              <p className="radio-option-description">
                Full visibility with comprehensive cost analysis and optimization insights
              </p>
            </div>
          </div>
        </div>

        <div className="form-group">
          <label className="form-label">
            <i className="fas fa-shield-alt" />
            Policies
          </label>
          <button type="button" className="btn-link" onClick={onOpenPolicyModal}>
            View Policies
          </button>
        </div>

        <div className="form-actions">
          <button
            type="submit"
            className="btn-primary"
            disabled={isLoading || !accountName.trim()}
          >
            {isLoading ? (
              <>
                <span className="loading-spinner" />
                Generating...
              </>
            ) : (
              'Launch Stack'
            )}
          </button>
        </div>
      </form>
    </div>
  );
};

export default AWSForm;
