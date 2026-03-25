/**
 * PolicyModal Component
 *
 * Modal showing the IAM policies that will be attached to the AWS role
 */

import React from 'react';
import { DialogRoot, DialogPanel } from '../ui';
import type { AccessType } from '../../types/addAccount';

interface PolicyModalProps {
  isOpen: boolean;
  onClose: () => void;
  accessType?: AccessType;
}

const PolicyModal: React.FC<PolicyModalProps> = ({ isOpen, onClose, accessType = 'read-only' }) => {
  const isReadWrite = accessType === 'read-write';

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
          The CloudFormation stack creates an IAM role with the following managed {isReadWrite ? 'and inline policies' : 'policy'} attached:
        </p>

        {/* AWS Managed Policy */}
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

        {/* Read-Write Additional Policies */}
        {isReadWrite && (
          <>
            <div className="policy-section" style={{ marginTop: '16px' }}>
              <div className="policy-header">
                <i className="fas fa-bolt" />
                <h3>EC2 &amp; Auto Spotting</h3>
                <span className="policy-badge">Inline</span>
              </div>
              <div className="policy-box">
                <p className="policy-description">
                  Allows XamOps to start, stop, modify, and terminate EC2 instances. Enables
                  Spot Fleet management, Auto Scaling group configuration, and launch template
                  operations for automated cost optimization (auto-spotting).
                </p>
              </div>
            </div>

            <div className="policy-section" style={{ marginTop: '16px' }}>
              <div className="policy-header">
                <i className="fas fa-users" />
                <h3>IAM User Management</h3>
                <span className="policy-badge">Inline</span>
              </div>
              <div className="policy-box">
                <p className="policy-description">
                  Allows XamOps to create and delete IAM users, manage access keys, assign
                  policies, and configure login profiles for automated user provisioning and
                  access governance.
                </p>
              </div>
            </div>

            <div className="policy-section" style={{ marginTop: '16px' }}>
              <div className="policy-header">
                <i className="fas fa-database" />
                <h3>Resource Write Operations</h3>
                <span className="policy-badge">Inline</span>
              </div>
              <div className="policy-box">
                <p className="policy-description">
                  Grants write access across RDS (start/stop/snapshot), Lambda (update code &amp;
                  configuration), ECS (update services), EKS (node group scaling), S3 (object
                  management), CloudWatch (alarms &amp; dashboards), SNS/SQS, and budget management.
                </p>
              </div>
            </div>

            <div className="policy-section" style={{ marginTop: '16px' }}>
              <div className="policy-header">
                <i className="fas fa-ship" />
                <h3>EKS Admin Access</h3>
                <span className="policy-badge">Inline</span>
              </div>
              <div className="policy-box">
                <p className="policy-description">
                  Adds the XamOps role to the <code>system:masters</code> group in the EKS
                  aws-auth ConfigMap, granting full cluster admin privileges for workload
                  management and configuration.
                </p>
              </div>
            </div>
          </>
        )}
      </DialogPanel>
    </DialogRoot>
  );
};

export default PolicyModal;
