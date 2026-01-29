/**
 * GCPForm Component
 *
 * Form for adding a GCP account - redirects to dedicated GCP setup page
 */

import React from 'react';
import { Link } from 'react-router-dom';

const GCPForm: React.FC = () => {
  return (
    <div className="form-card fade-in">
      <div className="form-card-header">
        <div className="step-number large">2</div>
        <div>
          <h2 className="form-card-title">GCP Connection Details</h2>
          <p className="form-card-subtitle">
            Connect using a dedicated Service Account.
          </p>
        </div>
      </div>

      <div style={{ marginBottom: '1.5rem' }}>
        <p style={{ color: '#374151', fontSize: '0.875rem', lineHeight: '1.6' }}>
          To connect your Google Cloud project, you will be redirected to a dedicated
          page to provide a Service Account Key with the required permissions.
        </p>
      </div>

      <div className="form-actions">
        <Link to="/add-gcp-account" className="btn-primary gcp">
          Continue to GCP Setup
        </Link>
      </div>
    </div>
  );
};

export default GCPForm;
