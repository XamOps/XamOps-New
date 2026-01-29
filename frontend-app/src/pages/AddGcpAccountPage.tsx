/**
 * Add GCP Account Page
 *
 * React component for adding a new GCP account to XamOps
 * Converted from: add-gcp-account.html
 *
 * Features:
 * - Complete step-by-step setup guide
 * - Service account creation script
 * - BigQuery billing export instructions
 * - Real-time validation
 * - Professional, modern UI
 */

import React from 'react';
import { useAddGcpAccount } from '../hooks/useAddGcpAccount';

const AddGcpAccountPage: React.FC = () => {
  const {
    accountName,
    projectId,
    serviceAccountKey,
    billingTable,
    isLoading,
    copyButtonText,
    showScript,
    showGuide,
    commandText,
    fullScript,
    setAccountName,
    setProjectId,
    setServiceAccountKey,
    setBillingTable,
    setShowScript,
    setShowGuide,
    copyCommand,
    addAccount,
    goBack,
  } = useAddGcpAccount();

  return (
    <div className="add-gcp-account-page">
      {/* Header */}
      <header className="add-gcp-header">
        <div className="header-info">
          <div className="gcp-header-icon">
            <i className="fab fa-google"></i>
          </div>
          <div className="header-text">
            <h1>Add New GCP Account</h1>
            <p>Connect your Google Cloud project with full visibility</p>
          </div>
        </div>
        <button onClick={goBack} className="gcp-back-button">
          <i className="fas fa-arrow-left"></i>
          Back
        </button>
      </header>

      {/* Main Content */}
      <div className="add-gcp-content">
        <div className="add-gcp-container">
          {/* Setup Guide Accordion */}
          <div className="gcp-form-card">
            <div className="card-header">
              <div className="card-header-left">
                <i className="fas fa-book-open"></i>
                <h2>Complete Setup Guide</h2>
              </div>
              <button
                type="button"
                onClick={() => setShowGuide(!showGuide)}
                className="toggle-guide-btn"
              >
                <i className={`fas ${showGuide ? 'fa-chevron-up' : 'fa-chevron-down'}`}></i>
                <span>{showGuide ? 'Hide Guide' : 'Show Step-by-Step Guide'}</span>
              </button>
            </div>

            {showGuide && (
              <div className="guide-content">
                {/* Step 1: Create Service Account */}
                <div className="guide-section">
                  <div className="guide-step">
                    <div className="gcp-step-number">1</div>
                    <div className="step-content">
                      <h3>Create Service Account & Enable APIs</h3>
                      <p>
                        Run the automated script in Google Cloud Shell to create a service account
                        with read-only access and enable all required APIs.
                      </p>

                      <div className="info-box warning">
                        <div className="info-box-content">
                          <i className="fas fa-lightbulb"></i>
                          <div>
                            <strong>Script automatically:</strong>
                            <ul>
                              <li>Enables 62 GCP APIs</li>
                              <li>
                                Creates service account:{' '}
                                <code>xamops@PROJECT_ID.iam.gserviceaccount.com</code>
                              </li>
                              <li>Grants Viewer + Resource Monitoring roles</li>
                              <li>Generates and displays JSON key</li>
                            </ul>
                          </div>
                        </div>
                      </div>

                      <div className="instruction-group">
                        <strong>Command to run:</strong>
                        <div className="command-block">
                          <div className="command-body">
                            <code>{commandText}</code>
                          </div>
                        </div>
                      </div>
                    </div>
                  </div>
                </div>

                {/* Step 2: BigQuery Billing Export */}
                <div className="guide-section">
                  <div className="guide-step">
                    <div className="gcp-step-number">2</div>
                    <div className="step-content">
                      <h3>Enable BigQuery Billing Export</h3>
                      <p>Export billing data to BigQuery to track costs in XamOps dashboard.</p>

                      <div className="instruction-group">
                        <strong>1. Go to Billing Export:</strong>
                        <ul>
                          <li>
                            Open{' '}
                            <a
                              href="https://console.cloud.google.com/billing"
                              target="_blank"
                              rel="noopener noreferrer"
                            >
                              GCP Console → Billing
                            </a>
                          </li>
                          <li>Select your billing account</li>
                          <li>
                            Go to <strong>Billing export</strong> → <strong>BigQuery export</strong>
                          </li>
                        </ul>
                      </div>

                      <div className="instruction-group">
                        <strong>2. Enable Detailed Usage Cost Export:</strong>
                        <ul>
                          <li>
                            Click <strong>Edit settings</strong> for "Standard usage cost"
                          </li>
                          <li>
                            Select project: <code>YOUR_PROJECT_ID</code>
                          </li>
                          <li>
                            Create or select dataset: <code>billing_export</code>
                          </li>
                          <li>
                            Click <strong>Save</strong>
                          </li>
                        </ul>
                      </div>

                      <div className="instruction-group">
                        <strong>3. Note the table name (format):</strong>
                        <div className="table-format">
                          PROJECT_ID.billing_export.gcp_billing_export_v1_XXXXXX_XXXXXX_XXXXXX
                        </div>
                        <p className="table-hint">
                          You'll need this exact table name later in the form below.
                        </p>
                      </div>
                    </div>
                  </div>
                </div>

                {/* Step 3: Grant BigQuery Access */}
                <div className="guide-section">
                  <div className="guide-step">
                    <div className="gcp-step-number">3</div>
                    <div className="step-content">
                      <h3>Grant BigQuery Dataset Access</h3>
                      <p>Give XamOps service account permission to read billing data.</p>

                      <div className="instruction-group">
                        <strong>Steps:</strong>
                        <ul>
                          <li>
                            Go to{' '}
                            <a
                              href="https://console.cloud.google.com/bigquery"
                              target="_blank"
                              rel="noopener noreferrer"
                            >
                              BigQuery Console
                            </a>
                          </li>
                          <li>
                            Find your <code>billing_export</code> dataset
                          </li>
                          <li>
                            Click the ⋮ menu → <strong>Share</strong> → <strong>Permissions</strong>
                          </li>
                          <li>
                            Click <strong>Add Principal</strong>
                          </li>
                          <li>
                            Enter service account:{' '}
                            <code>xamops@YOUR_PROJECT_ID.iam.gserviceaccount.com</code>
                          </li>
                          <li>
                            Select role: <strong>BigQuery Data Viewer</strong>
                          </li>
                          <li>
                            Click <strong>Save</strong>
                          </li>
                        </ul>
                      </div>
                    </div>
                  </div>
                </div>

                {/* Step 4: Verify Permissions */}
                <div className="guide-section">
                  <div className="guide-step">
                    <div className="gcp-step-number">4</div>
                    <div className="step-content">
                      <h3>Verify Permissions (Optional)</h3>
                      <p>Confirm all permissions are correctly set.</p>

                      <div className="instruction-group">
                        <strong>Check Service Account Roles:</strong>
                        <div className="command-block">
                          <div className="command-body">
                            <code>
                              gcloud projects get-iam-policy YOUR_PROJECT_ID \{'\n'}
                              &nbsp;&nbsp;--flatten="bindings[].members" \{'\n'}
                              &nbsp;&nbsp;--filter="bindings.members:xamops@*"
                            </code>
                          </div>
                        </div>
                      </div>

                      <div className="instruction-group">
                        <strong>Expected roles:</strong>
                        <ul>
                          <li>
                            <code>roles/viewer</code> - Read access to all resources
                          </li>
                          <li>
                            <code>roles/monitoring.viewer</code> - Read metrics and monitoring data
                          </li>
                        </ul>
                      </div>
                    </div>
                  </div>
                </div>

                {/* Summary */}
                <div className="info-box success">
                  <div className="info-box-content">
                    <i className="fas fa-check-circle"></i>
                    <div>
                      <strong>Setup Checklist</strong>
                      <div className="checklist">
                        <div className="checklist-item">
                          <span className="check">✅</span> Service account created with Viewer role
                        </div>
                        <div className="checklist-item">
                          <span className="check">✅</span> 62 GCP APIs enabled
                        </div>
                        <div className="checklist-item">
                          <span className="check">✅</span> BigQuery billing export enabled
                        </div>
                        <div className="checklist-item">
                          <span className="check">✅</span> Service account has BigQuery Data Viewer
                          role
                        </div>
                        <div className="checklist-item">
                          <span className="check">✅</span> JSON key generated and ready to paste
                        </div>
                      </div>
                    </div>
                  </div>
                </div>
              </div>
            )}
          </div>

          {/* Account Details Form */}
          <div className="gcp-form-card">
            <h2 style={{ fontSize: '1.25rem', fontWeight: 700, marginBottom: '1.5rem' }}>
              Account Details
            </h2>
            <form
              onSubmit={(e) => {
                e.preventDefault();
                addAccount();
              }}
            >
              {/* Account Name */}
              <div className="gcp-form-group">
                <label htmlFor="accountName" className="gcp-form-label">
                  <i className="fas fa-tag"></i>
                  Account Name
                </label>
                <input
                  type="text"
                  id="accountName"
                  value={accountName}
                  onChange={(e) => setAccountName(e.target.value)}
                  placeholder="e.g. Production GCP"
                  className="gcp-form-input"
                  required
                />
              </div>

              {/* Project ID */}
              <div className="gcp-form-group">
                <label htmlFor="projectId" className="gcp-form-label">
                  <i className="fas fa-folder"></i>
                  GCP Project ID
                </label>
                <input
                  type="text"
                  id="projectId"
                  value={projectId}
                  onChange={(e) => setProjectId(e.target.value)}
                  placeholder="your-gcp-project-id"
                  className="gcp-form-input"
                  required
                />
                <p className="gcp-form-hint">
                  Find this in GCP Console or run:{' '}
                  <code>gcloud config get-value project</code>
                </p>
              </div>

              {/* Setup Command */}
              <div className="setup-command-section">
                <div className="setup-command-header">
                  <h3 className="setup-command-title">
                    <i className="fas fa-terminal"></i>
                    Step 1: Run this command in Google Cloud Shell
                  </h3>
                  <span className="automated-badge">
                    <i className="fas fa-bolt"></i>
                    Automated
                  </span>
                </div>

                <div className="gcp-code-block">
                  <div className="code-block-header">
                    <span>BASH</span>
                    <button
                      type="button"
                      onClick={copyCommand}
                      className={`copy-btn ${copyButtonText === 'Copied!' ? 'copied' : ''}`}
                    >
                      <i className="fas fa-copy"></i>
                      <span>{copyButtonText}</span>
                    </button>
                  </div>
                  <div className="code-block-body">
                    <code>{commandText}</code>
                  </div>
                </div>

                <button
                  type="button"
                  onClick={() => setShowScript(!showScript)}
                  className="view-script-btn"
                >
                  <i className={`fas ${showScript ? 'fa-chevron-up' : 'fa-chevron-down'}`}></i>
                  <span>{showScript ? 'Hide Full Script' : 'View Full Script'}</span>
                </button>

                {showScript && (
                  <div className="script-viewer">
                    <div className="script-viewer-header">
                      <span>create_serviceaccount.sh</span>
                      <span>View-Only</span>
                    </div>
                    <pre>{fullScript}</pre>
                  </div>
                )}
              </div>

              {/* Service Account Key */}
              <div className="gcp-form-group">
                <label htmlFor="serviceAccountKey" className="gcp-form-label">
                  <i className="fas fa-key"></i>
                  Step 2: Service Account Key (JSON)
                </label>
                <p className="gcp-form-hint">
                  Copy the JSON key from the script output and paste it here:
                </p>
                <textarea
                  id="serviceAccountKey"
                  value={serviceAccountKey}
                  onChange={(e) => setServiceAccountKey(e.target.value)}
                  className="gcp-form-input mono"
                  placeholder={`{
  "type": "service_account",
  "project_id": "your-project-id",
  "private_key_id": "...",
  ...
}`}
                  required
                />
              </div>

              {/* BigQuery Billing Table */}
              <div className="gcp-form-group">
                <label htmlFor="billingTable" className="gcp-form-label">
                  <i className="fas fa-database"></i>
                  Step 3: BigQuery Billing Export Table
                </label>
                <p className="gcp-form-hint">
                  Enter the full table name from Step 2 of the guide (format:{' '}
                  <code>project.dataset.table</code>)
                </p>
                <input
                  type="text"
                  id="billingTable"
                  value={billingTable}
                  onChange={(e) => setBillingTable(e.target.value)}
                  placeholder="project_id.billing_export.gcp_billing_export_v1_XXXXXX_XXXXXX_XXXXXX"
                  className="gcp-form-input mono"
                  required
                />
              </div>

              {/* Action Buttons */}
              <div className="gcp-form-actions">
                <button type="button" onClick={goBack} className="gcp-btn-cancel">
                  Cancel
                </button>
                <button type="submit" className="gcp-btn-submit" disabled={isLoading}>
                  {isLoading ? (
                    <>
                      <div className="gcp-spinner"></div>
                      <span>Adding Account...</span>
                    </>
                  ) : (
                    <>
                      <i className="fas fa-check"></i>
                      <span>Add Account</span>
                    </>
                  )}
                </button>
              </div>
            </form>
          </div>
        </div>
      </div>
    </div>
  );
};

export default AddGcpAccountPage;
