/**
 * ProviderSelector Component
 *
 * Card for selecting the cloud provider or service to add
 */

import React from 'react';
import { Link } from 'react-router-dom';
import type { Provider } from '../../types/addAccount';

interface ProviderSelectorProps {
  selectedProvider: Provider;
  onSelectProvider: (provider: Provider) => void;
}

const ProviderSelector: React.FC<ProviderSelectorProps> = ({
  selectedProvider,
  onSelectProvider,
}) => {
  return (
    <div className="form-card fade-in">
      <div className="form-card-header">
        <div className="step-number large">1</div>
        <div>
          <h2 className="form-card-title">Choose Provider or Service</h2>
          <p className="form-card-subtitle">Select the cloud or service you want to add.</p>
        </div>
      </div>

      <div className="provider-grid">
        <button
          type="button"
          className={`provider-option ${selectedProvider === 'aws' ? 'selected' : ''}`}
          onClick={() => onSelectProvider('aws')}
        >
          <i className="fab fa-aws provider-icon-aws" />
          <h3>Amazon Web Services</h3>
        </button>

        <button
          type="button"
          className={`provider-option ${selectedProvider === 'gcp' ? 'selected' : ''}`}
          onClick={() => onSelectProvider('gcp')}
        >
          <i className="fab fa-google provider-icon-gcp" />
          <h3>Google Cloud Platform</h3>
        </button>

        <button
          type="button"
          className={`provider-option ${selectedProvider === 'azure' ? 'selected' : ''}`}
          onClick={() => onSelectProvider('azure')}
        >
          <i className="fab fa-microsoft provider-icon-azure" />
          <h3>Microsoft Azure</h3>
        </button>

        <Link to="/add-github-config" className="provider-option">
          <i className="fab fa-github provider-icon-github" />
          <h3>GitHub Actions</h3>
        </Link>

        <button
          type="button"
          className={`provider-option ${selectedProvider === 'jenkins' ? 'selected' : ''}`}
          onClick={() => onSelectProvider('jenkins')}
        >
          <i className="fab fa-jenkins provider-icon-jenkins" />
          <h3>Jenkins Server</h3>
        </button>
      </div>
    </div>
  );
};

export default ProviderSelector;
