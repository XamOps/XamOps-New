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
          { title: 'Select Provider', description: 'Choose GCP to start setup' },
          { title: 'Authenticate', description: 'Securely sign in to Google Cloud' },
          { title: 'Configure Access', description: 'Set permissions & billing export' },
        ];
      case 'azure':
        return [
          { title: 'Select Provider', description: 'Choose Azure to start setup' },
          { title: 'Run Script', description: 'Execute setup script in Cloud Shell' },
          { title: 'Connect', description: 'Paste credentials to finish' },
        ];
      case 'jenkins':
        return [
          { title: 'Server Details', description: 'Enter Jenkins URL & token' },
          { title: 'Connect', description: 'Validate and save connection' },
        ];
      default:
        // Default initial view or when no provider selected
        return [
          { title: 'Select Provider', description: 'Choose a service to connect' },
          { title: 'Configure', description: 'Enter connection details' },
          { title: 'Verify', description: 'Test and save connection' },
        ];
    }
  };

  const steps = getSteps();

  return (
    <div className="glass-card steps-sidebar" style={{ padding: '2rem' }}>
      <h3 style={{ marginBottom: '2rem', fontSize: '1.25rem' }}>Connection Process</h3>
      <div className="fade-in" style={{ position: 'relative' }}>
        {steps.map((step, index) => (
          <div key={index} className="step-item" style={{ position: 'relative', paddingBottom: index === steps.length - 1 ? 0 : '2rem' }}>
            {/* Connecting Line */}
            {index !== steps.length - 1 && (
              <div
                style={{
                  position: 'absolute',
                  left: '1rem',
                  top: '2.5rem',
                  bottom: '-0.5rem',
                  width: '2px',
                  background: '#e2e8f0',
                  transform: 'translateX(-50%)'
                }}
              />
            )}

            <div
              className="step-number"
              style={{
                position: 'relative',
                zIndex: 2,
                background: index === 0 ? 'var(--aa-primary-gradient)' : '#fff',
                color: index === 0 ? '#fff' : '#94a3b8',
                border: index === 0 ? 'none' : '2px solid #e2e8f0',
                boxShadow: index === 0 ? 'var(--aa-shadow-md)' : 'none'
              }}
            >
              {index + 1}
            </div>

            <div className="step-content">
              <h4 style={{ color: index === 0 ? '#1e293b' : '#64748b' }}>{step.title}</h4>
              <p style={{ color: index === 0 ? '#475569' : '#94a3b8' }}>{step.description}</p>
            </div>
          </div>
        ))}
      </div>
    </div>
  );
};

export default ConnectionSteps;
