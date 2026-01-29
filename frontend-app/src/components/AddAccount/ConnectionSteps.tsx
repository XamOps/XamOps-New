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
}

const ConnectionSteps: React.FC<ConnectionStepsProps> = ({ provider }) => {
  const getSteps = (): Step[] => {
    switch (provider) {
      case 'aws':
        return [
          { title: 'Configure Access', description: 'Choose your account settings' },
          { title: 'Generate Stack', description: 'Create CloudFormation template' },
          { title: 'Deploy in AWS', description: 'Launch stack in your account' },
          { title: 'Verify Connection', description: 'Complete the setup process' },
        ];
      case 'gcp':
        return [
          { title: 'Select Provider', description: 'You are here' },
          { title: 'Provide Credentials', description: 'Enter GCP project details' },
          { title: 'Verify & Connect', description: 'Finalize the connection' },
        ];
      case 'azure':
        return [
          { title: 'Select Provider', description: 'You are here' },
          { title: 'Run Azure Script', description: 'Execute script in Cloud Shell' },
          { title: 'Paste JSON & Connect', description: 'Finalize the connection' },
        ];
      case 'jenkins':
        return [
          { title: 'Server Details', description: 'Enter URL and credentials' },
          { title: 'Connect', description: 'Validate and save' },
        ];
      default:
        return [];
    }
  };

  const steps = getSteps();

  return (
    <div className="glass-card steps-sidebar" style={{ padding: '1.5rem' }}>
      <h3>Connection Process</h3>
      <div className="fade-in">
        {steps.map((step, index) => (
          <div key={index} className="step-item">
            <div className="step-number">{index + 1}</div>
            <div className="step-content">
              <h4>{step.title}</h4>
              <p>{step.description}</p>
            </div>
          </div>
        ))}
      </div>
    </div>
  );
};

export default ConnectionSteps;
