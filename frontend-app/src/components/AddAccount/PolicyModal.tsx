/**
 * PolicyModal Component
 *
 * Modal showing the IAM policies that will be attached to the AWS role
 */

import React from 'react';

interface PolicyModalProps {
  isOpen: boolean;
  onClose: () => void;
}

const PolicyModal: React.FC<PolicyModalProps> = ({ isOpen, onClose }) => {
  if (!isOpen) return null;

  const handleOverlayClick = (e: React.MouseEvent) => {
    if (e.target === e.currentTarget) {
      onClose();
    }
  };

  return (
    <div className="modal-overlay" onClick={handleOverlayClick} role="dialog" aria-modal="true">
      <div className="modal-content" style={{ maxWidth: '36rem' }}>
        <div className="modal-header">
          <div className="modal-header-content">
            <div className="modal-icon">
              <i className="fas fa-shield-alt" />
            </div>
            <h2 className="modal-title">Required IAM Policies</h2>
          </div>
          <button className="modal-close" onClick={onClose} aria-label="Close modal">
            <i className="fas fa-times" />
          </button>
        </div>

        <div className="modal-body">
          <p className="modal-description">
            The CloudFormation stack creates an IAM role with the following managed policy attached:
          </p>

          <div className="policy-section">
            <div className="policy-header">
              <i className="fab fa-aws" />
              <h3>ReadOnlyAccess</h3>
              <span className="policy-badge">AWS Managed</span>
            </div>
            <div className="policy-box">
              <div className="policy-detail">
                <span className="label">Policy ARN</span>
                <code>arn:aws:iam::aws:policy/ReadOnlyAccess</code>
              </div>
              <p className="policy-description">
                Provides read-only access to AWS services and resources. This allows XamOps
                to view your costs, usage, and resource inventory without any modification rights.
              </p>
            </div>
          </div>
        </div>

        <div className="modal-footer">
          <button className="btn-secondary" onClick={onClose}>
            <span>Got it</span>
          </button>
        </div>
      </div>
    </div>
  );
};

export default PolicyModal;
