/**
 * ConnectionSteps Component
 *
 * Sidebar showing the connection process steps for each provider
 */

import React from 'react';
import type { Provider } from '../../types/addAccount';

interface ConnectionStepsProps {
  provider: Provider;
}

interface Step {
  title: string;
  description: string;
  icon: string;
}

const stepsConfig: Record<string, Step[]> = {
  aws: [
    { title: 'Configure Access', description: 'Set account name and permissions', icon: 'fas fa-cog' },
    { title: 'Launch Stack', description: 'Deploy CloudFormation template', icon: 'fas fa-rocket' },
    { title: 'Deploy in AWS', description: 'Create IAM role in your account', icon: 'fab fa-aws' },
    { title: 'Verify Connection', description: 'Complete setup and connect', icon: 'fas fa-check-circle' },
  ],
  gcp: [
    { title: 'Select GCP', description: 'Choose Google Cloud Platform', icon: 'fab fa-google' },
    { title: 'Create Service Account', description: 'Run setup script in Cloud Shell', icon: 'fas fa-terminal' },
    { title: 'Configure Billing', description: 'Set up BigQuery export', icon: 'fas fa-file-invoice-dollar' },
  ],
  azure: [
    { title: 'Select Azure', description: 'Choose Microsoft Azure', icon: 'fab fa-microsoft' },
    { title: 'Run Setup Script', description: 'Execute in Azure Cloud Shell', icon: 'fas fa-terminal' },
    { title: 'Enter Credentials', description: 'Paste JSON and connect', icon: 'fas fa-key' },
  ],
  jenkins: [
    { title: 'Server Details', description: 'Enter Jenkins URL', icon: 'fas fa-server' },
    { title: 'Authentication', description: 'Provide API credentials', icon: 'fas fa-lock' },
  ],
  default: [
    { title: 'Select Provider', description: 'Choose a cloud or service', icon: 'fas fa-cloud' },
    { title: 'Configure', description: 'Enter connection details', icon: 'fas fa-sliders-h' },
    { title: 'Connect', description: 'Verify and save', icon: 'fas fa-plug' },
  ],
};

const ConnectionSteps: React.FC<ConnectionStepsProps> = ({ provider }) => {
  const steps = stepsConfig[provider] || stepsConfig.default;

  return (
    <div className="steps-sidebar">
      <h3>Setup Guide</h3>
      <div className="steps-list">
        {steps.map((step, index) => (
          <div
            key={index}
            className={`step-item ${index === 0 ? 'active' : ''}`}
            style={{
              paddingBottom: index === steps.length - 1 ? 0 : '1.5rem',
              position: 'relative',
            }}
          >
            {/* Connecting Line */}
            {index !== steps.length - 1 && (
              <div className="step-connector" />
            )}

            <div className={`step-number ${index === 0 ? 'active' : ''}`}>
              {index === 0 ? (
                <i className={step.icon} style={{ fontSize: '0.625rem' }} />
              ) : (
                index + 1
              )}
            </div>

            <div className="step-content">
              <h4 className={index === 0 ? 'active' : ''}>{step.title}</h4>
              <p className={index === 0 ? 'active' : ''}>{step.description}</p>
            </div>
          </div>
        ))}
      </div>
    </div>
  );
};

export default ConnectionSteps;
