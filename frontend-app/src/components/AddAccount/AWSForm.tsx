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
  isAutoConnecting: boolean;
  autoConnected: boolean;
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
  isAutoConnecting,
  autoConnected,
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

  // ── Success celebration screen ────────────────────────────────────────────
  if (autoConnected) {
    return (
      <div className="form-card fade-in eb-success-card">
        {/* Floating decorative dots */}
        <div className="eb-confetti-dot" style={{ top: '12%', left: '8%', width: 10, height: 10, background: '#10b981', animationDelay: '0s' }} />
        <div className="eb-confetti-dot" style={{ top: '20%', right: '12%', width: 7, height: 7, background: '#818cf8', animationDelay: '0.6s' }} />
        <div className="eb-confetti-dot" style={{ bottom: '18%', left: '15%', width: 9, height: 9, background: '#a855f7', animationDelay: '1.1s' }} />
        <div className="eb-confetti-dot" style={{ bottom: '25%', right: '8%', width: 6, height: 6, background: '#10b981', animationDelay: '1.7s' }} />
        <div className="eb-confetti-dot" style={{ top: '50%', left: '4%', width: 5, height: 5, background: '#f59e0b', animationDelay: '0.9s' }} />

        <div className="eb-success-check">
          <i className="fas fa-check" />
        </div>

        <h2 className="eb-success-title">Account Connected!</h2>

        <div className="eb-account-pill">
          <i className="fab fa-aws" />
          <span>{accountName}</span>
        </div>

        <p className="eb-success-desc">
          Your AWS account has been automatically verified and connected to XamOps
          via EventBridge. You can now start monitoring your cloud resources.
        </p>

        <button
          className="btn-primary"
          onClick={() => { window.location.href = '/account-manager'; }}
        >
          <i className="fas fa-th-large" />
          <span>Go to Account Manager</span>
        </button>
      </div>
    );
  }

  // ── Waiting / manual verification screen ─────────────────────────────────
  if (isVerifying) {
    return (
      <div className="form-card fade-in">
        <div className="form-card-header">
          <div className="step-number large">4</div>
          <div>
            <h2 className="form-card-title">
              {isAutoConnecting ? 'Connecting Your Account' : 'Verify Connection'}
            </h2>
            <p className="form-card-subtitle">
              {isAutoConnecting
                ? 'EventBridge will auto-verify once your stack completes'
                : 'Enter the details from your CloudFormation stack outputs'}
            </p>
          </div>
        </div>

        {/* Auto-connecting pulsing banner */}
        {isAutoConnecting && (
          <div className="eb-auto-banner">
            <div className="eb-pulse-ring">
              <i className="fas fa-sync-alt" />
            </div>
            <div className="eb-auto-text">
              <h4>Connecting automatically via EventBridge</h4>
              <p>Waiting for your CloudFormation stack to complete — usually 1–3 minutes. No action needed.</p>
            </div>
          </div>
        )}

        <div className="success-message">
          <i className="fas fa-check-circle" />
          <div>
            <strong>Stack Launched Successfully!</strong>
            <span>
              {isAutoConnecting
                ? 'Your account will connect here automatically once the stack finishes.'
                : 'Enter the AWS Account ID and Role Name from the stack outputs below.'}
            </span>
          </div>
        </div>

        {/* Manual verification form — shown only when not auto-connecting */}
        {!isAutoConnecting && (
          <form onSubmit={handleSubmit}>
            <div className="form-group">
              <label htmlFor="awsAccountId" className="form-label">
                <i className="fas fa-id-card" />
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
              <p className="form-hint">12-digit AWS account identifier</p>
            </div>

            <div className="form-group">
              <label htmlFor="roleName" className="form-label">
                <i className="fas fa-user-shield" />
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
              <p className="form-hint">Name of the IAM role created by the stack</p>
            </div>

            {verificationError && (
              <div className="error-message">
                <i className="fas fa-exclamation-circle" />
                <span>{verificationError}</span>
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
        )}
      </div>
    );
  }

  // ── Initial form ──────────────────────────────────────────────────────────
  return (
    <div className="form-card fade-in">
      <div className="form-card-header">
        <div className="step-number large">2</div>
        <div>
          <h2 className="form-card-title">Configure AWS Access</h2>
          <p className="form-card-subtitle">
            Set up a secure cross-account IAM role using CloudFormation
          </p>
        </div>
      </div>

      <form onSubmit={handleSubmit}>
        <div className="form-group">
          <label htmlFor="accountName" className="form-label">
            <i className="fas fa-tag" />
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
            A friendly name to identify this account in XamOps
          </p>
        </div>

        <div className="form-group">
          <label className="form-label">
            <i className="fas fa-shield-alt" />
            Access Level
          </label>
          <label
            htmlFor="read-only"
            className={`radio-option ${accessType === 'read-only' ? 'selected' : ''}`}
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
              <span className="radio-option-label">
                Read-Only Access (Recommended)
              </span>
              <p className="radio-option-description">
                Grants visibility into costs, usage, and inventory without modification rights
              </p>
            </div>
          </label>
          <label
            htmlFor="read-write"
            className={`radio-option ${accessType === 'read-write' ? 'selected' : ''}`}
          >
            <input
              type="radio"
              id="read-write"
              name="access-type"
              value="read-write"
              checked={accessType === 'read-write'}
              onChange={() => onAccessTypeChange('read-write')}
            />
            <div className="radio-option-content">
              <span className="radio-option-label">
                Read &amp; Write Access
              </span>
              <p className="radio-option-description">
                Grants full visibility plus the ability to manage resources, configure auto-spotting, create users, and perform operational actions
              </p>
            </div>
          </label>
        </div>

        <div className="iam-policies-box">
          <div className="info-content">
            <i className="fas fa-file-contract" />
            <span>IAM Policies</span>
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
                <span>Generating...</span>
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
