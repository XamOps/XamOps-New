/**
 * JenkinsForm Component
 *
 * Form for adding a Jenkins server connection
 */

import React from 'react';
import type { JenkinsDetails } from '../../types/addAccount';

interface JenkinsFormProps {
  jenkinsDetails: JenkinsDetails;
  isLoading: boolean;
  onJenkinsDetailsChange: (details: Partial<JenkinsDetails>) => void;
  onSubmit: () => void;
}

const JenkinsForm: React.FC<JenkinsFormProps> = ({
  jenkinsDetails,
  isLoading,
  onJenkinsDetailsChange,
  onSubmit,
}) => {
  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    onSubmit();
  };

  return (
    <div className="form-card fade-in">
      <div className="form-card-header">
        <div
          className="header-icon jenkins"
          style={{ width: '2.5rem', height: '2.5rem', borderRadius: '0.75rem' }}
        >
          <i className="fab fa-jenkins" style={{ fontSize: '1rem' }} />
        </div>
        <div>
          <h2 className="form-card-title">Connect Jenkins Server</h2>
          <p className="form-card-subtitle">Enter your Jenkins URL and API Token</p>
        </div>
      </div>

      <form onSubmit={handleSubmit}>
        <div className="form-group">
          <label htmlFor="jenkinsUrl" className="form-label">
            Jenkins URL
          </label>
          <input
            type="url"
            id="jenkinsUrl"
            className="form-input"
            placeholder="http://jenkins.yourcompany.com:8080"
            value={jenkinsDetails.url}
            onChange={(e) => onJenkinsDetailsChange({ url: e.target.value })}
            required
          />
          <p className="form-hint">
            Include http:// or https:// and the port if necessary.
          </p>
        </div>

        <div className="form-group">
          <label htmlFor="jenkinsUsername" className="form-label">
            Username
          </label>
          <input
            type="text"
            id="jenkinsUsername"
            className="form-input"
            placeholder="admin"
            value={jenkinsDetails.username}
            onChange={(e) => onJenkinsDetailsChange({ username: e.target.value })}
            required
          />
        </div>

        <div className="form-group">
          <label htmlFor="jenkinsToken" className="form-label">
            API Token
          </label>
          <input
            type="password"
            id="jenkinsToken"
            className="form-input"
            placeholder="Enter your User API Token"
            value={jenkinsDetails.token}
            onChange={(e) => onJenkinsDetailsChange({ token: e.target.value })}
            required
          />
          <p className="form-hint">
            Found in Jenkins User Profile → Configure → API Token.
          </p>
        </div>

        <div className="form-actions">
          <button type="submit" className="btn-primary" disabled={isLoading}>
            {isLoading ? (
              <>
                <span className="loading-spinner" />
                Connecting...
              </>
            ) : (
              'Connect Jenkins'
            )}
          </button>
        </div>
      </form>
    </div>
  );
};

export default JenkinsForm;
