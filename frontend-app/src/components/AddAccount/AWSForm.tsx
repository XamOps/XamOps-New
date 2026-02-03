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
        <div className="form-card-header">
          <div className="step-number large">4</div>
          <div>
            <h2 className="form-card-title">Verify Connection</h2>
            <p className="form-card-subtitle">
              Enter the AWS Account ID and Role Name to complete setup.
            </p>
          </div>
        </div>

        <div className="success-message" style={{ marginBottom: '1.5rem', background: '#f0fdf4', borderRadius: '0.75rem', padding: '1rem', border: '1px solid #bbf7d0' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: '0.75rem', color: '#166534' }}>
            <i className="fas fa-check-circle" style={{ fontSize: '1.25rem' }} />
            <div>
              <strong style={{ display: 'block', fontSize: '0.875rem' }}>Stack Launched Successfully!</strong>
              <span style={{ fontSize: '0.875rem' }}>Please enter the details created by the CloudFormation stack below.</span>
            </div>
          </div>
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
                <>
                  <i className="fas fa-check" />
                  <span>Verify & Connect</span>
                </>
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
        <div className="step-number large">2</div>
        <div>
          <h2 className="form-card-title">Configure Access</h2>
          <p className="form-card-subtitle">
            Set up your AWS account connection details.
          </p>
        </div>
      </div>

      <form onSubmit={handleSubmit}>
        <div className="form-group">
          <label htmlFor="accountName" className="form-label">
            <i className="fas fa-tag" style={{ color: 'var(--aa-primary-gradient)' }} />
            Account Name
          </label>
          <input
            type="text"
            id="accountName"
            className="form-input"
            placeholder="e.g. AWS Production"
            value={accountName}
            onChange={(e) => onAccountNameChange(e.target.value)}
            required
          />
          <p className="form-hint">
            A friendly name to identify this account in XamOps.
          </p>
        </div>

        <div className="form-group">
          <label className="form-label">
            <i className="fas fa-shield-alt" style={{ color: 'var(--aa-primary-gradient)' }} />
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
                Read-Only Access (Recommended)
              </label>
              <p className="radio-option-description">
                Grants visibility into costs, usage, and inventory without modification rights.
              </p>
            </div>
          </div>
        </div>

        <div className="form-group" style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '1rem', background: 'rgba(255,255,255,0.5)', borderRadius: '0.75rem', border: '1px solid var(--aa-glass-border)' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: '0.75rem' }}>
            <i className="fas fa-file-contract" style={{ color: '#64748b' }} />
            <span style={{ fontSize: '0.875rem', color: '#334155', fontWeight: 500 }}>IAM Policies</span>
          </div>
          <button type="button" className="btn-link" onClick={onOpenPolicyModal}>
            View Required Permissions
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
              <>
                <span>Launch CloudFormation Stack</span>
                <i className="fas fa-external-link-alt" />
              </>
            )}
          </button>
        </div>
      </form>
    </div>
  );
};

export default AWSForm;
