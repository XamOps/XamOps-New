/**
 * PolicyModal Component
 *
 * Modal showing the IAM policies that will be attached to the AWS role
 */

import React from 'react';
import { DialogRoot, DialogPanel } from '../ui';

interface PolicyModalProps {
  isOpen: boolean;
  onClose: () => void;
}

const PolicyModal: React.FC<PolicyModalProps> = ({ isOpen, onClose }) => {
  return (
    <DialogRoot isOpen={isOpen} onClose={onClose}>
      <DialogPanel
        title={<><i className="fas fa-shield-alt mr-2" />Required IAM Policies</>}
        onClose={onClose}
        footer={
          <button className="btn-secondary" onClick={onClose}><span>Got it</span></button>
        }
      >
        <p className="modal-description mb-4">
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
      </DialogPanel>
    </DialogRoot>
  );
};

export default PolicyModal;
