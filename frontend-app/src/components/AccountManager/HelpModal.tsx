/**
 * HelpModal Component
 *
 * Help modal with accordion sections for AWS, GCP, and Azure onboarding guides
 */

import React, { useState } from 'react';
import { DialogRoot, DialogPanel } from '../ui';

interface HelpModalProps {
  isOpen: boolean;
  onClose: () => void;
}

interface AccordionSectionProps {
  title: string;
  icon: string;
  iconBg: string;
  isOpen: boolean;
  onToggle: () => void;
  children: React.ReactNode;
}

const AccordionSection: React.FC<AccordionSectionProps> = ({
  title,
  icon,
  iconBg,
  isOpen,
  onToggle,
  children,
}) => {
  return (
    <div className="border border-gray-200 rounded-xl overflow-hidden">
      <button
        onClick={onToggle}
        className="accordion-header w-full flex items-center justify-between p-4 hover:bg-gray-50 transition-colors"
      >
        <div className="flex items-center gap-3">
          <div className={`w-8 h-8 ${iconBg} rounded-lg flex items-center justify-center`}>
            <i className={`${icon} text-white text-sm`} />
          </div>
          <h4 className="font-semibold text-gray-900">{title}</h4>
        </div>
        <i className={`fas fa-chevron-${isOpen ? 'up' : 'down'} text-gray-400`} />
      </button>
      {isOpen && (
        <div className="accordion-content p-5 border-t border-gray-100 bg-gray-50/50">
          {children}
        </div>
      )}
    </div>
  );
};

const CodeBlock: React.FC<{ children: string }> = ({ children }) => (
  <pre className="code-block text-xs overflow-x-auto">{children}</pre>
);

const HelpModal: React.FC<HelpModalProps> = ({ isOpen, onClose }) => {
  const [openSection, setOpenSection] = useState<string | null>(null);

  const toggleSection = (section: string) => {
    setOpenSection(openSection === section ? null : section);
  };

  return (
    <DialogRoot isOpen={isOpen} onClose={onClose} maxWidth="max-w-4xl">
      <DialogPanel
        title={
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 bg-gradient-to-br from-blue-500 to-indigo-600 rounded-xl flex items-center justify-center">
              <i className="fas fa-life-ring text-white text-lg" />
            </div>
            <div>
              <div className="text-xl font-bold text-gray-900">XamOps Help Desk</div>
              <div className="text-sm text-gray-500">Pre-Onboarding Guide</div>
            </div>
          </div>
        }
        onClose={onClose}
        footer={
          <button onClick={onClose} className="btn-primary">
            <i className="fas fa-check mr-2" />
            Got It
          </button>
        }
      >
        <div className="overflow-y-auto max-h-[60vh]">
          {/* Introduction */}
          <div className="prose prose-sm max-w-none mb-6">
            <p className="text-gray-600 leading-relaxed">
              Welcome to the XamOps Tool Help Desk! This guide supports clients onboarding to our
              Managed Partner Account (MPA) under the Partner-Led Billing (PLB) model across AWS, GCP,
              and Azure. We handle each customer account with utmost care, ensuring isolation, security,
              and autonomy with zero-trust principles.
            </p>
            <p className="text-gray-600 leading-relaxed mt-3">
              We collaborate through personalized consultations to customize integrations. XamOps
              differentiates with "+1 level of security": real-time dashboards, simulation tools,
              and delegated controls. Contact support at{' '}
              <a href="mailto:support@xamops.com" className="text-indigo-600 hover:text-indigo-700">
                support@xamops.com
              </a>.
            </p>
          </div>

          {/* Provider Guides */}
          <div className="space-y-4">
            {/* AWS Section */}
            <AccordionSection
              title="AWS Pre-Onboarding Guide"
              icon="fab fa-aws"
              iconBg="bg-gradient-to-br from-orange-500 to-amber-600"
              isOpen={openSection === 'aws'}
              onToggle={() => toggleSection('aws')}
            >
              <div className="space-y-4 text-sm text-gray-700">
                <p>
                  At XamOps, we handle each AWS customer account with a structured, secure, and
                  collaborative approach. Your account is isolated in a dedicated Organizational Unit (OU)
                  within our AWS Organization.
                </p>

                <h5 className="font-semibold text-gray-900 mt-4">Step 1: Initial Consultation & Connection</h5>
                <ul className="list-disc pl-5 space-y-2">
                  <li><strong>Invitation:</strong> We send a secure invitation from our MPA's management account</li>
                  <li><strong>Dedicated OU:</strong> Your accounts live in a sub-OU (e.g., `YourCompany-OU`)</li>
                  <li><strong>Billing:</strong> PLB enables centralized invoicing with tagged cost breakdowns</li>
                </ul>

                <h5 className="font-semibold text-gray-900 mt-4">Step 2: Governed Environment with Control Tower</h5>
                <ul className="list-disc pl-5 space-y-2">
                  <li>Log Archive Account with immutable S3 buckets for CloudTrail logs</li>
                  <li>Drift Detection monitors for unauthorized changes</li>
                </ul>

                <h5 className="font-semibold text-gray-900 mt-4">Example SCP: Deny IAM User Creation</h5>
                <CodeBlock>{`{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "DenyIAMUserCreation",
      "Effect": "Deny",
      "Action": ["iam:CreateUser", "iam:CreateAccessKey"],
      "Resource": "*"
    }
  ]
}`}</CodeBlock>
              </div>
            </AccordionSection>

            {/* GCP Section */}
            <AccordionSection
              title="GCP Pre-Onboarding Guide"
              icon="fab fa-google"
              iconBg="bg-gradient-to-br from-blue-500 to-green-500"
              isOpen={openSection === 'gcp'}
              onToggle={() => toggleSection('gcp')}
            >
              <div className="space-y-4 text-sm text-gray-700">
                <p>
                  Each GCP customer account is managed with isolation in mind, using dedicated folders
                  within our Google Cloud Organization. This folder structure acts as a policy boundary.
                </p>

                <h5 className="font-semibold text-gray-900 mt-4">Step 1: Initial Consultation & Connection</h5>
                <ul className="list-disc pl-5 space-y-2">
                  <li><strong>Dedicated Folder:</strong> Top-level `Clients` and sub-folder `YourCompany-Folder`</li>
                  <li><strong>Billing:</strong> Centralized with labels for cost tracking</li>
                </ul>

                <h5 className="font-semibold text-gray-900 mt-4">Example Policies</h5>
                <CodeBlock>{`constraint: "constraints/compute.requireShieldedVm"
enforcement: DENY

constraint: "constraints/iam.disableServiceAccountKeyCreation"
enforcement: DENY`}</CodeBlock>
              </div>
            </AccordionSection>

            {/* Azure Section */}
            <AccordionSection
              title="Azure Pre-Onboarding Guide"
              icon="fab fa-microsoft"
              iconBg="bg-gradient-to-br from-blue-600 to-cyan-600"
              isOpen={openSection === 'azure'}
              onToggle={() => toggleSection('azure')}
            >
              <div className="space-y-4 text-sm text-gray-700">
                <p>
                  Each Azure account is isolated in a dedicated Management Group within our tenant.
                  MPA manages billing, but you control operations via delegated RBAC.
                </p>

                <h5 className="font-semibold text-gray-900 mt-4">Step 1: Initial Consultation & Connection</h5>
                <ul className="list-disc pl-5 space-y-2">
                  <li><strong>Management Group:</strong> `Clients` and `YourCompany-MG`</li>
                  <li><strong>Billing:</strong> Centralized with tags</li>
                </ul>

                <h5 className="font-semibold text-gray-900 mt-4">Example Policy: Deny Public Blob Access</h5>
                <CodeBlock>{`{
  "properties": {
    "policyRule": {
      "if": {
        "allOf": [
          {"field": "type", "equals": "Microsoft.Storage/storageAccounts"},
          {"field": "Microsoft.Storage/storageAccounts/allowBlobPublicAccess", "equals": "true"}
        ]
      },
      "then": {"effect": "deny"}
    }
  }
}`}</CodeBlock>
              </div>
            </AccordionSection>
          </div>

          {/* Common Questions */}
          <div className="mt-6 p-4 bg-indigo-50 rounded-xl">
            <h4 className="font-semibold text-indigo-900 mb-3">
              <i className="fas fa-question-circle mr-2" />
              Common Questions
            </h4>
            <ul className="text-sm text-indigo-800 space-y-2">
              <li><strong>Data Isolation:</strong> Dedicated OUs/folders/management groups with scoped policies</li>
              <li><strong>Identity Control:</strong> Federation with your IdP or delegated directory</li>
              <li><strong>Billing:</strong> Transparent tagged costs and budgets</li>
              <li><strong>Testing:</strong> XamOps simulators for policies before applying</li>
            </ul>
          </div>
        </div>
      </DialogPanel>
    </DialogRoot>
  );
};

export default HelpModal;
