/**
 * GitHubConfigPage
 *
 * Page for connecting a GitHub repository to monitor GitHub Actions
 * Converted from: add-github-config.html
 *
 * Features:
 * - Connect GitHub repository via Personal Access Token (PAT)
 * - Form validation
 * - Loading states
 * - Error handling with user-friendly messages
 * - Professional, modern UI with glass morphism design
 *
 * What changed:
 * - Migrated from Alpine.js to React with TypeScript
 * - Uses React Router for navigation
 * - Integrated with existing React infrastructure
 * - Enhanced UI with existing addAccount styling patterns
 *
 * What stayed the same:
 * - API endpoint: POST /api/cicd/config/github
 * - Same business logic preserved exactly
 * - Same validation and error handling
 * - Same success flow (redirect to account-manager)
 */

import React, { useEffect, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import { useGitHubConfig } from '../hooks/useGitHubConfig';
import { useTopbarActions } from '../contexts/TopbarContext';

// Import styles
import '../styles/addAccount.css';
import '../styles/githubConfig.css';

const GitHubConfigPage: React.FC = () => {
  const navigate = useNavigate();
  const { setActions, clearActions } = useTopbarActions();

  const {
    githubDetails,
    isLoading,
    error,
    setGitHubDetails,
    connectGitHub,
  } = useGitHubConfig();

  useEffect(() => {
    console.log('🚀 [GitHubConfig] Page Mounted');
    console.log('📍 [GitHubConfig] Current URL:', globalThis.location.pathname);
  }, []);

  // Memoize the navigate handler
  const handleBackToAddAccount = useCallback(() => {
    navigate('/add-account');
  }, [navigate]);

  // Set topbar actions
  useEffect(() => {
    setActions(
      <div className="flex items-center gap-3">
        <button
          onClick={handleBackToAddAccount}
          className="flex items-center gap-2 px-4 py-2 text-sm font-medium text-slate-600 bg-slate-100 hover:bg-slate-200 rounded-xl transition-all"
        >
          <i className="fas fa-arrow-left"></i>
          <span>Back to Add Account</span>
        </button>
        <div className="flex items-center gap-2 px-4 py-2 rounded-xl text-sm font-medium bg-gradient-to-r from-gray-800/10 to-gray-700/10 border border-gray-300 text-gray-700">
          <i className="fab fa-github"></i>
          <span>GitHub</span>
        </div>
      </div>
    );

    return () => clearActions();
  }, [setActions, clearActions, handleBackToAddAccount]);

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    connectGitHub();
  };

  return (
    <main className="github-config-page">
      {/* Content */}
      <div className="github-config-content">
        <div className="github-config-container">
          {/* Info Card */}
          <div className="github-info-card fade-in">
            <div className="info-icon">
              <i className="fas fa-shield-alt" />
            </div>
            <div className="info-content">
              <h3>Security Information</h3>
              <p>
                Your Personal Access Token will be stored securely encrypted. We recommend using a
                token with minimal required permissions.
              </p>
            </div>
          </div>

          {/* Main Form Card */}
          <div className="form-card github-form-card fade-in">
            <div className="form-card-header">
              <div className="github-header-icon">
                <i className="fab fa-github" />
              </div>
              <div>
                <h2 className="form-card-title">Repository Details</h2>
                <p className="form-card-subtitle">
                  Provide a Personal Access Token (PAT) with{' '}
                  <code className="inline-code">repo:status</code> or full{' '}
                  <code className="inline-code">repo</code> scope.
                </p>
              </div>
            </div>

            <form onSubmit={handleSubmit}>
              {/* GitHub Owner */}
              <div className="form-group">
                <label htmlFor="githubOwner" className="form-label">
                  <i className="fas fa-folder mr-2" />
                  GitHub Owner (User or Organization)
                </label>
                <input
                  type="text"
                  id="githubOwner"
                  className="form-input"
                  placeholder="e.g., 'my-organization'"
                  value={githubDetails.owner}
                  onChange={(e) => setGitHubDetails({ owner: e.target.value })}
                  disabled={isLoading}
                  required
                />
                <p className="form-hint">
                  Enter the GitHub username or organization name that owns the repository.
                </p>
              </div>

              {/* Repository Name */}
              <div className="form-group">
                <label htmlFor="githubRepo" className="form-label">
                  <i className="fas fa-code-branch mr-2" />
                  Repository Name
                </label>
                <input
                  type="text"
                  id="githubRepo"
                  className="form-input"
                  placeholder="e.g., 'my-awesome-project'"
                  value={githubDetails.repo}
                  onChange={(e) => setGitHubDetails({ repo: e.target.value })}
                  disabled={isLoading}
                  required
                />
                <p className="form-hint">
                  The name of the repository you want to monitor.
                </p>
              </div>

              {/* Personal Access Token */}
              <div className="form-group">
                <label htmlFor="githubPat" className="form-label">
                  <i className="fas fa-key mr-2" />
                  Personal Access Token (PAT)
                </label>
                <input
                  type="password"
                  id="githubPat"
                  className="form-input"
                  placeholder="ghp_xxxxxxxxxxxxxxxxxxxx"
                  value={githubDetails.pat}
                  onChange={(e) => setGitHubDetails({ pat: e.target.value })}
                  disabled={isLoading}
                  required
                />
                <p className="form-hint">
                  <i className="fas fa-lock mr-2" />
                  Your token will be stored securely encrypted.
                </p>
              </div>

              {/* Error Message */}
              {error && (
                <div className="error-message">
                  <i className="fas fa-exclamation-circle" />
                  {error}
                </div>
              )}

              {/* Token Help Section */}
              <div className="token-help-section">
                <h4>
                  <i className="fas fa-info-circle mr-2" />
                  How to create a Personal Access Token
                </h4>
                <ol>
                  <li>Go to GitHub Settings &rarr; Developer settings &rarr; Personal access tokens</li>
                  <li>Click "Generate new token (classic)"</li>
                  <li>Give it a descriptive name (e.g., "XamOps Integration")</li>
                  <li>Select the <code className="inline-code">repo:status</code> scope (or full <code className="inline-code">repo</code> for private repos)</li>
                  <li>Click "Generate token" and copy it immediately</li>
                </ol>
              </div>

              {/* Submit Button */}
              <div className="form-actions">
                <button
                  type="button"
                  onClick={() => navigate('/add-account')}
                  className="btn-secondary"
                >
                  <i className="fas fa-times" />
                  Cancel
                </button>
                <button type="submit" className="btn-primary github" disabled={isLoading}>
                  {isLoading ? (
                    <>
                      <span className="loading-spinner mr-2" />
                      Connecting...
                    </>
                  ) : (
                    <>
                      <i className="fab fa-github" />
                      Connect GitHub Repository
                    </>
                  )}
                </button>
              </div>
            </form>
          </div>

          {/* Features Card */}
          <div className="github-features-card fade-in">
            <h3>What you'll get</h3>
            <ul>
              <li>
                <i className="fas fa-check-circle" />
                <span>Real-time GitHub Actions workflow status</span>
              </li>
              <li>
                <i className="fas fa-check-circle" />
                <span>Build success/failure notifications</span>
              </li>
              <li>
                <i className="fas fa-check-circle" />
                <span>CI/CD pipeline monitoring</span>
              </li>
              <li>
                <i className="fas fa-check-circle" />
                <span>Integration with XamOps dashboard</span>
              </li>
            </ul>
          </div>
        </div>
      </div>
    </main>
  );
};

export default GitHubConfigPage;
