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
          <h2 className="form-card-title">Google Cloud Setup</h2>
          <p className="form-card-subtitle">
            Connect your GCP project using a Service Account
          </p>
        </div>
      </div>

      <div className="gcp-info-box">
        <div className="gcp-info-icon">
          <i className="fab fa-google" />
        </div>
        <div className="gcp-info-content">
          <h4>Service Account Authentication</h4>
          <p>
            You'll create a dedicated Service Account with the required permissions
            to securely access your Google Cloud resources and billing data.
          </p>
          <ul className="gcp-features-list">
            <li><i className="fas fa-check" /><span>Cost and billing analytics</span></li>
            <li><i className="fas fa-check" /><span>Resource inventory tracking</span></li>
            <li><i className="fas fa-check" /><span>Performance monitoring</span></li>
          </ul>
        </div>
      </div>

      <div className="form-actions">
        <Link to="/add-gcp-account" className="btn-primary gcp">
          <span>Continue to GCP Setup</span>
          <i className="fas fa-arrow-right" />
        </Link>
      </div>
    </div>
  );
};

export default GCPForm;
