/**
 * AzureForm Component
 *
 * Form for adding an Azure account using Service Principal
 * Includes PowerShell script instructions and JSON credentials input
 */

import React, { useState } from 'react';
import type { AzureDetails } from '../../types/addAccount';

interface AzureFormProps {
  azureDetails: AzureDetails;
  isLoading: boolean;
  onAzureDetailsChange: (details: Partial<AzureDetails>) => void;
  onSubmit: () => void;
  onCopyToClipboard: (text: string) => Promise<boolean>;
}

interface CopyButtonState {
  [key: string]: boolean;
}

const AzureForm: React.FC<AzureFormProps> = ({
  azureDetails,
  isLoading,
  onAzureDetailsChange,
  onSubmit,
  onCopyToClipboard,
}) => {
  const [copiedStates, setCopiedStates] = useState<CopyButtonState>({});

  const handleCopy = async (text: string, id: string) => {
    const success = await onCopyToClipboard(text);
    if (success) {
      setCopiedStates((prev) => ({ ...prev, [id]: true }));
      setTimeout(() => {
        setCopiedStates((prev) => ({ ...prev, [id]: false }));
      }, 2000);
    }
  };

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    onSubmit();
  };

  const commands = [
    {
      id: 'connect',
      code: 'Connect-AzAccount -UseDeviceAuthentication',
    },
    {
      id: 'context',
      code: 'Set-AzContext -Subscription "YOUR_SUBSCRIPTION_ID"',
      highlight: '"YOUR_SUBSCRIPTION_ID"',
    },
    {
      id: 'download',
      code: 'Invoke-WebRequest -Uri "https://xamops-azure-connector1.s3.ap-south-1.amazonaws.com/setup-xamops-connection1.ps" -OutFile "setup-xamops-connection.ps1"',
    },
    {
      id: 'source',
      code: '. .\\setup-xamops-connection.ps1',
    },
    {
      id: 'setup',
      code: 'Setup-XamOpsConnection -SubscriptionId "YOUR_SUBSCRIPTION_ID" -ApplicationName "XamOps" -CreateSpCredential $true',
      highlight: '"YOUR_SUBSCRIPTION_ID"',
    },
  ];

  return (
    <div className="form-card fade-in">
      {/* Instructions Section */}
      <div className="azure-instructions">
        <div className="form-card-header">
          <div className="step-number large">2</div>
          <div>
            <h2 className="form-card-title">Run Azure Setup Script</h2>
            <p className="form-card-subtitle">
              Run these commands one by one in Azure Cloud Shell to get your credentials.
            </p>
          </div>
        </div>

        <p>
          Open the{' '}
          <a href="https://shell.azure.com/" target="_blank" rel="noopener noreferrer">
            Azure Cloud Shell
          </a>
          , select <strong>PowerShell</strong>, and run the following commands one at a time.
        </p>

        {commands.map((cmd) => (
          <div key={cmd.id} className="code-block">
            <button
              type="button"
              className={`copy-button ${copiedStates[cmd.id] ? 'copied' : ''}`}
              onClick={() => handleCopy(cmd.code.replace(/<[^>]*>/g, ''), cmd.id)}
            >
              {copiedStates[cmd.id] ? 'Copied!' : 'Copy'}
            </button>
            <pre>
              {cmd.highlight ? (
                <>
                  {cmd.code.split(cmd.highlight)[0]}
                  <span className="highlight">{cmd.highlight}</span>
                  {cmd.code.split(cmd.highlight)[1]}
                </>
              ) : (
                cmd.code
              )}
            </pre>
          </div>
        ))}
      </div>

      {/* Credentials Form */}
      <form
        onSubmit={handleSubmit}
        style={{ borderTop: '1px solid var(--aa-glass-border)', paddingTop: '2rem', marginTop: '2rem' }}
      >
        <div className="form-card-header">
          <div className="step-number large">3</div>
          <div>
            <h2 className="form-card-title">Enter Details & Connect</h2>
            <p className="form-card-subtitle">
              Paste the JSON output from the script below.
            </p>
          </div>
        </div>

        <div className="form-group">
          <label htmlFor="azureAccountName" className="form-label">
            <i className="fas fa-tag" style={{ color: 'var(--aa-azure-blue)' }} />
            Account Name
          </label>
          <input
            type="text"
            id="azureAccountName"
            className="form-input"
            placeholder="e.g., Azure Production Subscription"
            value={azureDetails.accountName}
            onChange={(e) => onAzureDetailsChange({ accountName: e.target.value })}
            required
          />
        </div>

        <div className="form-group">
          <label htmlFor="azureCredentialsJson" className="form-label">
            <i className="fas fa-key" style={{ color: 'var(--aa-azure-blue)' }} />
            Azure Credentials (JSON)
          </label>
          <textarea
            id="azureCredentialsJson"
            className="form-input"
            placeholder="Paste the JSON output from the Cloud Shell script here..."
            value={azureDetails.azureCredentialsJson}
            onChange={(e) =>
              onAzureDetailsChange({ azureCredentialsJson: e.target.value })
            }
            rows={8}
            required
          />
        </div>

        <div className="form-actions">
          <button
            type="submit"
            className="btn-primary azure"
            disabled={isLoading}
          >
            {isLoading ? (
              <>
                <span className="loading-spinner" />
                Connecting...
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
    </div>
  );
};

export default AzureForm;
