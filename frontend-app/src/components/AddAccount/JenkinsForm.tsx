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
        <div className="step-number large">2</div>
        <div>
          <h2 className="form-card-title">Connect Jenkins Server</h2>
          <p className="form-card-subtitle">
            Enter your Jenkins server URL and API credentials
          </p>
        </div>
      </div>

      <form onSubmit={handleSubmit}>
        <div className="form-group">
          <label htmlFor="jenkinsUrl" className="form-label">
            <i className="fas fa-server" />
            Jenkins URL
          </label>
          <input
            type="url"
            id="jenkinsUrl"
            className="form-input"
            placeholder="https://jenkins.yourcompany.com"
            value={jenkinsDetails.url}
            onChange={(e) => onJenkinsDetailsChange({ url: e.target.value })}
            required
          />
          <p className="form-hint">
            Full URL including protocol (http/https) and port if applicable
          </p>
        </div>

        <div className="form-group">
          <label htmlFor="jenkinsUsername" className="form-label">
            <i className="fas fa-user" />
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
          <p className="form-hint">Your Jenkins username</p>
        </div>

        <div className="form-group">
          <label htmlFor="jenkinsToken" className="form-label">
            <i className="fas fa-key" />
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
            Generate at: User Profile → Configure → API Token
          </p>
        </div>

        <div className="form-actions">
          <button type="submit" className="btn-primary jenkins" disabled={isLoading}>
            {isLoading ? (
              <>
                <span className="loading-spinner" />
                <span>Connecting...</span>
              </>
            ) : (
              <>
                <i className="fas fa-plug" />
                <span>Connect Jenkins</span>
              </>
            )}
          </button>
        </div>
      </form>
    </div>
  );
};

export default JenkinsForm;
