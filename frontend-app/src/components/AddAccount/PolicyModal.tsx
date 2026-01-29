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
    <div className="modal-overlay" onClick={handleOverlayClick}>
      <div className="modal-content" style={{ maxWidth: '48rem' }}>
        <div className="modal-header">
          <h2 className="modal-title">IAM Policies</h2>
          <button className="modal-close" onClick={onClose}>
            <i className="fas fa-times" />
          </button>
        </div>

        <div className="modal-body">
          <p style={{ color: '#6b7280', marginBottom: '1.5rem', fontSize: '0.875rem' }}>
            The CloudFormation stack will create an IAM role (e.g., <code>XamOpsRole</code>)
            with these two policies attached.
          </p>

          <div className="policy-section">
            <h3>1. AWSReadOnlyAccess</h3>
            <div className="policy-box">
              <p>
                <span className="label">Policy Name:</span> <code>AWSReadOnlyAccess</code> (AWS Managed Policy)
              </p>
              <p>
                <span className="label">ARN:</span> <code>arn:aws:iam::aws:policy/ReadOnlyAccess</code>
              </p>
              <p style={{ marginTop: '0.75rem', color: '#374151' }}>
                This policy provides broad read-only access to all AWS services and resources.
              </p>
            </div>
          </div>
        </div>

        <div className="modal-footer">
          <button className="btn-primary" onClick={onClose}>
            Close
          </button>
        </div>
      </div>
    </div>
  );
};

export default PolicyModal;
