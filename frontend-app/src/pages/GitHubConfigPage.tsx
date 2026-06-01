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
 *
 * All API endpoints and functionality preserved from original.
 */

import React, { useEffect, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import { Icon } from '@iconify/react';
import { useGitHubConfig } from '../hooks/useGitHubConfig';
import { useTopbarActions } from '../contexts/TopbarContext';

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

  const handleBackToAddAccount = useCallback(() => {
    navigate('/add-account');
  }, [navigate]);

  useEffect(() => {
    setActions(
      <div className="flex items-center gap-3">
        <button
          onClick={handleBackToAddAccount}
          className="flex items-center gap-2 px-4 py-2 text-xs font-medium text-[rgba(0,0,0,0.6)] bg-[rgba(0,0,0,0.04)] border border-[rgba(0,0,0,0.08)] hover:bg-[rgba(0,0,0,0.07)] rounded-md transition-colors"
        >
          <Icon icon="mdi:arrow-left" width={14} />
          Back to Add Account
        </button>
        <div className="flex items-center gap-2 px-3 py-1.5 rounded-md text-xs font-medium bg-[rgba(0,0,0,0.88)] text-white">
          <Icon icon="mdi:github" width={14} />
          GitHub
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
    <div className="px-6 pb-12 pt-6">
      <div className="max-w-2xl mx-auto space-y-4">

        {/* Security info banner */}
        <div className="flex items-start gap-3 p-4 bg-emerald-50 border border-emerald-100 rounded-xl">
          <div className="w-9 h-9 rounded-lg bg-emerald-100 border border-emerald-200 flex items-center justify-center flex-shrink-0">
            <Icon icon="mdi:shield-check-outline" width={16} color="#059669" />
          </div>
          <div>
            <h3 className="text-sm font-bold text-emerald-800 mb-0.5">Security Information</h3>
            <p className="text-xs text-emerald-700 leading-relaxed">
              Your Personal Access Token will be stored securely encrypted. We recommend using a token with minimal required permissions.
            </p>
          </div>
        </div>

        {/* Main form card */}
        <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm overflow-hidden">
          {/* Card header */}
          <div className="flex items-center gap-3 px-6 py-4 border-b border-[rgba(0,0,0,0.06)]">
            <div className="w-10 h-10 rounded-xl bg-[rgba(0,0,0,0.88)] flex items-center justify-center flex-shrink-0">
              <Icon icon="mdi:github" width={20} color="white" />
            </div>
            <div>
              <h2 className="text-sm font-bold text-[rgba(0,0,0,0.88)]">Repository Details</h2>
              <p className="text-xs text-[rgba(0,0,0,0.4)] mt-0.5">
                Provide a Personal Access Token (PAT) with{' '}
                <code className="px-1 py-0.5 bg-[rgba(0,0,0,0.04)] border border-[rgba(0,0,0,0.08)] rounded text-[10px] font-mono">repo:status</code>
                {' '}or full{' '}
                <code className="px-1 py-0.5 bg-[rgba(0,0,0,0.04)] border border-[rgba(0,0,0,0.08)] rounded text-[10px] font-mono">repo</code> scope.
              </p>
            </div>
          </div>

          <form onSubmit={handleSubmit} className="p-6 space-y-5">
            {/* GitHub Owner */}
            <div>
              <label htmlFor="githubOwner" className="flex items-center gap-1.5 text-[10px] font-semibold text-[rgba(0,0,0,0.5)] uppercase tracking-wider mb-1.5">
                <Icon icon="mdi:folder-outline" width={11} />
                GitHub Owner (User or Organization)
              </label>
              <input
                type="text"
                id="githubOwner"
                placeholder="e.g., 'my-organization'"
                value={githubDetails.owner}
                onChange={(e) => setGitHubDetails({ owner: e.target.value })}
                disabled={isLoading}
                required
                className="w-full px-3 py-2 text-xs bg-[rgba(0,0,0,0.03)] border border-[rgba(0,0,0,0.08)] rounded-lg text-[rgba(0,0,0,0.88)] placeholder-[rgba(0,0,0,0.3)] focus:outline-none focus:ring-1 focus:ring-[rgba(0,0,0,0.2)] focus:border-[rgba(0,0,0,0.2)] disabled:opacity-60 transition-colors"
              />
              <p className="mt-1 text-[11px] text-[rgba(0,0,0,0.4)]">Enter the GitHub username or organization name that owns the repository.</p>
            </div>

            {/* Repository Name */}
            <div>
              <label htmlFor="githubRepo" className="flex items-center gap-1.5 text-[10px] font-semibold text-[rgba(0,0,0,0.5)] uppercase tracking-wider mb-1.5">
                <Icon icon="mdi:source-branch" width={11} />
                Repository Name
              </label>
              <input
                type="text"
                id="githubRepo"
                placeholder="e.g., 'my-awesome-project'"
                value={githubDetails.repo}
                onChange={(e) => setGitHubDetails({ repo: e.target.value })}
                disabled={isLoading}
                required
                className="w-full px-3 py-2 text-xs bg-[rgba(0,0,0,0.03)] border border-[rgba(0,0,0,0.08)] rounded-lg text-[rgba(0,0,0,0.88)] placeholder-[rgba(0,0,0,0.3)] focus:outline-none focus:ring-1 focus:ring-[rgba(0,0,0,0.2)] focus:border-[rgba(0,0,0,0.2)] disabled:opacity-60 transition-colors"
              />
              <p className="mt-1 text-[11px] text-[rgba(0,0,0,0.4)]">The name of the repository you want to monitor.</p>
            </div>

            {/* Personal Access Token */}
            <div>
              <label htmlFor="githubPat" className="flex items-center gap-1.5 text-[10px] font-semibold text-[rgba(0,0,0,0.5)] uppercase tracking-wider mb-1.5">
                <Icon icon="mdi:key-outline" width={11} />
                Personal Access Token (PAT)
              </label>
              <input
                type="password"
                id="githubPat"
                placeholder="ghp_xxxxxxxxxxxxxxxxxxxx"
                value={githubDetails.pat}
                onChange={(e) => setGitHubDetails({ pat: e.target.value })}
                disabled={isLoading}
                required
                className="w-full px-3 py-2 text-xs bg-[rgba(0,0,0,0.03)] border border-[rgba(0,0,0,0.08)] rounded-lg text-[rgba(0,0,0,0.88)] placeholder-[rgba(0,0,0,0.3)] focus:outline-none focus:ring-1 focus:ring-[rgba(0,0,0,0.2)] focus:border-[rgba(0,0,0,0.2)] disabled:opacity-60 transition-colors"
              />
              <p className="mt-1 text-[11px] text-[rgba(0,0,0,0.4)] flex items-center gap-1">
                <Icon icon="mdi:lock-outline" width={10} />
                Your token will be stored securely encrypted.
              </p>
            </div>

            {/* Error */}
            {error && (
              <div className="flex items-center gap-2 px-3 py-2.5 bg-rose-50 border border-rose-100 rounded-lg">
                <Icon icon="mdi:alert-circle-outline" width={14} color="#F43F5E" className="flex-shrink-0" />
                <p className="text-xs text-rose-600">{error}</p>
              </div>
            )}

            {/* How to create a PAT */}
            <div className="p-4 bg-[rgba(0,0,0,0.02)] border border-[rgba(0,0,0,0.08)] rounded-xl">
              <h4 className="flex items-center gap-2 text-xs font-bold text-[rgba(0,0,0,0.7)] mb-3">
                <Icon icon="mdi:information-outline" width={13} color="#4F46E5" />
                How to create a Personal Access Token
              </h4>
              <ol className="space-y-2 pl-0 list-none counter-reset-step">
                {[
                  <>Go to GitHub Settings → Developer settings → Personal access tokens</>,
                  <>Click "Generate new token (classic)"</>,
                  <>Give it a descriptive name (e.g., "XamOps Integration")</>,
                  <>Select the <code className="px-1 py-0.5 bg-[rgba(0,0,0,0.06)] border border-[rgba(0,0,0,0.08)] rounded text-[10px] font-mono">repo:status</code> scope (or full <code className="px-1 py-0.5 bg-[rgba(0,0,0,0.06)] border border-[rgba(0,0,0,0.08)] rounded text-[10px] font-mono">repo</code> for private repos)</>,
                  <>Click "Generate token" and copy it immediately</>,
                ].map((step, i) => (
                  <li key={i} className="flex items-start gap-2.5 text-xs text-[rgba(0,0,0,0.5)]">
                    <span className="flex-shrink-0 w-5 h-5 rounded-full bg-[rgba(0,0,0,0.08)] text-[rgba(0,0,0,0.5)] text-[10px] font-bold flex items-center justify-center mt-0.5">{i + 1}</span>
                    <span className="leading-relaxed">{step}</span>
                  </li>
                ))}
              </ol>
            </div>

            {/* Actions */}
            <div className="flex items-center justify-end gap-3 pt-2">
              <button
                type="button"
                onClick={() => navigate('/add-account')}
                className="inline-flex items-center gap-2 px-4 py-2 border border-[rgba(0,0,0,0.12)] rounded-md bg-white text-[rgba(0,0,0,0.5)] text-xs font-medium hover:bg-[rgba(0,0,0,0.03)] transition-colors"
              >
                <Icon icon="mdi:close" width={13} />
                Cancel
              </button>
              <button
                type="submit"
                disabled={isLoading}
                className="inline-flex items-center gap-2 px-4 py-2 bg-[rgba(0,0,0,0.88)] hover:bg-black text-white rounded-md text-xs font-semibold transition-colors disabled:opacity-60"
              >
                {isLoading ? (
                  <>
                    <Icon icon="mdi:loading" width={13} className="animate-spin" />
                    Connecting...
                  </>
                ) : (
                  <>
                    <Icon icon="mdi:github" width={13} />
                    Connect GitHub Repository
                  </>
                )}
              </button>
            </div>
          </form>
        </div>

        {/* What you'll get */}
        <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-5">
          <h3 className="text-sm font-bold text-[rgba(0,0,0,0.88)] mb-4">What you'll get</h3>
          <ul className="grid grid-cols-1 sm:grid-cols-2 gap-3">
            {[
              'Real-time GitHub Actions workflow status',
              'Build success/failure notifications',
              'CI/CD pipeline monitoring',
              'Integration with XamOps dashboard',
            ].map((feature) => (
              <li key={feature} className="flex items-center gap-2 text-xs text-[rgba(0,0,0,0.7)]">
                <Icon icon="mdi:check-circle-outline" width={14} color="#059669" className="flex-shrink-0" />
                {feature}
              </li>
            ))}
          </ul>
        </div>

      </div>
    </div>
  );
};

export default GitHubConfigPage;
