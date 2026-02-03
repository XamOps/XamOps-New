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
import { AddAccountHeader } from '../components/AddAccount';

const AddGcpAccountPage: React.FC = () => {
  const {
    accountName,
    projectId,
    serviceAccountKey,
    billingTable,
    isLoading,
    copyButtonText,
    showGuide,
    commandText,
    setAccountName,
    setProjectId,
    setServiceAccountKey,
    setBillingTable,
    setShowGuide,
    copyCommand,
    addAccount,
    goBack,
  } = useAddGcpAccount();

  return (
    <div className="add-account-page fade-in">
      {/* Glass Background Elements */}
      <div className="glass-shape shape-1"></div>
      <div className="glass-shape shape-2"></div>

      {/* Header */}
      <AddAccountHeader provider="gcp" headerText="Connect your Google Cloud project with full visibility" />

      {/* Main Content */}
      <div className="add-account-content container">
        <div className="split-layout">

          {/* Left Sidebar - Steps */}
          <div className="sidebar-column">
            <div className="glass-card steps-sidebar" style={{ padding: '2rem' }}>
              <h3 style={{ marginBottom: '2rem', fontSize: '1.25rem' }}>Setup Guide</h3>
              <div className="info-box warning" style={{ marginBottom: '1.5rem' }}>
                <p style={{ fontSize: '0.875rem' }}><i className="fas fa-info-circle"></i> Follow the setup guide to generate the required credentials.</p>
              </div>

              <div className="fade-in" style={{ position: 'relative' }}>
                {[
                  { title: 'Create Service Account', description: 'Run script in Cloud Shell' },
                  { title: 'Enable Billing Export', description: 'Configure BigQuery export' },
                  { title: 'Grant Permissions', description: 'Authorize service account' },
                  { title: 'Verify & Connect', description: 'Enter details below' }
                ].map((step, index) => (
                  <div key={index} className="step-item" style={{ position: 'relative', paddingBottom: index === 3 ? 0 : '2rem' }}>
                    {/* Connecting Line */}
                    {index !== 3 && (
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
                        background: index === 3 ? 'var(--aa-primary-gradient)' : '#fff',
                        color: index === 3 ? '#fff' : '#94a3b8',
                        border: index === 3 ? 'none' : '2px solid #e2e8f0',
                        boxShadow: index === 3 ? 'var(--aa-shadow-md)' : 'none'
                      }}
                    >
                      {index + 1}
                    </div>

                    <div className="step-content">
                      <h4 style={{ color: index === 3 ? '#1e293b' : '#64748b' }}>{step.title}</h4>
                      <p style={{ color: index === 3 ? '#475569' : '#94a3b8' }}>{step.description}</p>
                    </div>
                  </div>
                ))}
              </div>

              <button
                type="button"
                onClick={() => setShowGuide(!showGuide)}
                className="btn-secondary"
                style={{ width: '100%', marginTop: '1.5rem', justifyContent: 'center' }}
              >
                <i className={`fas ${showGuide ? 'fa-book-open' : 'fa-book'}`}></i>
                <span>{showGuide ? 'Hide Guide Details' : 'View Detailed Guide'}</span>
              </button>
            </div>
          </div>

          {/* Right Content */}
          <div className="content-column">

            {showGuide && (
              <div className="form-card fade-in" style={{ marginBottom: '2rem' }}>
                <div className="form-card-header">
                  <div className="header-icon" style={{ background: '#e0f2fe', color: '#0284c7' }}>
                    <i className="fas fa-book"></i>
                  </div>
                  <div>
                    <h2 className="form-card-title">Detailed Setup Instructions</h2>
                    <p className="form-card-subtitle">Follow these specific steps in your GCP Console.</p>
                  </div>
                </div>

                <div className="guide-content">
                  {/* Step 1: Create Service Account */}
                  <div className="guide-section">
                    <div className="guide-step">
                      <div className="step-content">
                        <h3 style={{ fontSize: '1rem', fontWeight: 600, color: '#334155', marginBottom: '0.5rem' }}>1. Create Service Account & Enable APIs</h3>
                        <p style={{ fontSize: '0.875rem', color: '#64748b', marginBottom: '1rem' }}>
                          Run the automated script in Google Cloud Shell. It enables 62 APIs and creates a service account.
                        </p>
                        <div className="instruction-group">
                          <strong>Command to run:</strong>
                          <div className="code-block" style={{ marginTop: '0.5rem' }}>
                            <button
                              type="button"
                              className={`copy-button ${copyButtonText === 'Copied!' ? 'copied' : ''}`}
                              onClick={copyCommand}
                            >
                              {copyButtonText === 'Copied!' ? 'Copied!' : 'Copy'}
                            </button>
                            <pre>{commandText}</pre>
                          </div>
                        </div>
                      </div>
                    </div>
                  </div>

                  {/* Step 2: BigQuery Billing Export */}
                  <div className="guide-section" style={{ marginTop: '1.5rem', paddingTop: '1.5rem', borderTop: '1px solid #e2e8f0' }}>
                    <div className="guide-step">
                      <div className="step-content">
                        <h3 style={{ fontSize: '1rem', fontWeight: 600, color: '#334155', marginBottom: '0.5rem' }}>2. Enable BigQuery Billing Export</h3>
                        <ul style={{ paddingLeft: '1.5rem', fontSize: '0.875rem', color: '#475569' }}>
                          <li style={{ marginBottom: '0.5rem' }}>Go to <a href="https://console.cloud.google.com/billing" target="_blank" rel="noopener noreferrer" style={{ color: '#6366f1' }}>GCP Console → Billing</a></li>
                          <li style={{ marginBottom: '0.5rem' }}>Navigate to <strong>Billing export</strong> → <strong>BigQuery export</strong></li>
                          <li style={{ marginBottom: '0.5rem' }}>Edit "Standard usage cost", select project, create or select dataset <code>billing_export</code>, and Save.</li>
                          <li>Note the table name for later.</li>
                        </ul>
                      </div>
                    </div>
                  </div>

                  {/* Step 3: Grant BigQuery Access */}
                  <div className="guide-section" style={{ marginTop: '1.5rem', paddingTop: '1.5rem', borderTop: '1px solid #e2e8f0' }}>
                    <div className="guide-step">
                      <div className="step-content">
                        <h3 style={{ fontSize: '1rem', fontWeight: 600, color: '#334155', marginBottom: '0.5rem' }}>3. Grant Access</h3>
                        <p style={{ fontSize: '0.875rem', color: '#64748b', marginBottom: '0.5rem' }}>
                          In BigQuery Console, share the <code>billing_export</code> dataset with the new service account (<code>xamops@...</code>) giving it the <strong>BigQuery Data Viewer</strong> role.
                        </p>
                      </div>
                    </div>
                  </div>
                </div>
              </div>
            )}

            <div className="form-card fade-in">
              <div className="form-card-header">
                <div className="step-number large">4</div>
                <div>
                  <h2 className="form-card-title">Enter Account Details</h2>
                  <p className="form-card-subtitle">
                    Enter the details generated from the steps above.
                  </p>
                </div>
              </div>

              <form
                onSubmit={(e) => {
                  e.preventDefault();
                  addAccount();
                }}
              >
                {/* Account Name */}
                <div className="form-group">
                  <label htmlFor="accountName" className="form-label">
                    <i className="fas fa-tag" style={{ color: 'var(--aa-gcp-gradient)' }}></i>
                    Account Name
                  </label>
                  <input
                    type="text"
                    id="accountName"
                    value={accountName}
                    onChange={(e) => setAccountName(e.target.value)}
                    placeholder="e.g. Production GCP"
                    className="form-input"
                    required
                  />
                </div>

                {/* Project ID */}
                <div className="form-group">
                  <label htmlFor="projectId" className="form-label">
                    <i className="fas fa-folder" style={{ color: 'var(--aa-gcp-gradient)' }}></i>
                    GCP Project ID
                  </label>
                  <input
                    type="text"
                    id="projectId"
                    value={projectId}
                    onChange={(e) => setProjectId(e.target.value)}
                    placeholder="your-gcp-project-id"
                    className="form-input"
                    required
                  />
                </div>

                {/* Service Account Key */}
                <div className="form-group">
                  <label htmlFor="serviceAccountKey" className="form-label">
                    <i className="fas fa-key" style={{ color: 'var(--aa-gcp-gradient)' }}></i>
                    Service Account Key (JSON)
                  </label>
                  <textarea
                    id="serviceAccountKey"
                    value={serviceAccountKey}
                    onChange={(e) => setServiceAccountKey(e.target.value)}
                    className="form-input mono"
                    placeholder={`{
  "type": "service_account",
  "project_id": "your-project-id",
  ...
}`}
                    rows={6}
                    required
                  />
                </div>

                {/* BigQuery Billing Table */}
                <div className="form-group">
                  <label htmlFor="billingTable" className="form-label">
                    <i className="fas fa-database" style={{ color: 'var(--aa-gcp-gradient)' }}></i>
                    BigQuery Billing Export Table
                  </label>
                  <input
                    type="text"
                    id="billingTable"
                    value={billingTable}
                    onChange={(e) => setBillingTable(e.target.value)}
                    placeholder="project_id.billing_export.gcp_billing_export_v1_XXXXXX..."
                    className="form-input mono"
                    required
                  />
                </div>

                {/* Action Buttons */}
                <div className="form-actions">
                  <button type="button" onClick={goBack} className="btn-secondary">
                    Cancel
                  </button>
                  <button type="submit" className="btn-primary" disabled={isLoading} style={{ background: 'var(--aa-gcp-gradient)' }}>
                    {isLoading ? (
                      <>
                        <span className="loading-spinner"></span>
                        <span>Adding...</span>
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
    </div>
  );
};

export default AddGcpAccountPage;
