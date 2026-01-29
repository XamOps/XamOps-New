/**
 * AccountManagerKPICards Component
 *
 * KPI cards showing total accounts, active connections, and GitHub repos count
 */

import React from 'react';
import type { CloudAccount, GitHubConfig } from '../../types/accountManager';

interface AccountManagerKPICardsProps {
  accounts: CloudAccount[];
  githubConfigs: GitHubConfig[];
  isLoading: boolean;
  isGithubLoading: boolean;
}

const AccountManagerKPICards: React.FC<AccountManagerKPICardsProps> = ({
  accounts,
  githubConfigs,
  isLoading,
  isGithubLoading,
}) => {
  const totalAccounts = accounts.length;
  const activeConnections = accounts.filter(a => a.status === 'CONNECTED').length;
  const totalRepos = githubConfigs.length;

  return (
    <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
      {/* Total Cloud Accounts */}
      <div className="kpi-card fade-in">
        <div className="flex justify-between items-start">
          <div className="flex-1">
            <p className="kpi-label">Total Cloud Accounts</p>
            {isLoading ? (
              <div className="skeleton h-10 w-16 mt-2" />
            ) : (
              <p className="kpi-value gradient-text">{totalAccounts}</p>
            )}
            <p className="kpi-sublabel">connected accounts</p>
          </div>
          <div className="kpi-icon bg-gradient-to-br from-blue-500 to-indigo-600">
            <i className="fas fa-cloud text-white text-xl" />
          </div>
        </div>
        <div className="kpi-trend">
          <div className="flex items-center gap-1.5">
            <i className="fas fa-server text-xs" />
            <span>
              {isLoading ? '...' : `${accounts.filter(a => a.provider === 'AWS').length} AWS`}
            </span>
          </div>
          <div className="flex items-center gap-1.5">
            <i className="fab fa-google text-xs" />
            <span>
              {isLoading ? '...' : `${accounts.filter(a => a.provider === 'GCP').length} GCP`}
            </span>
          </div>
          <div className="flex items-center gap-1.5">
            <i className="fab fa-microsoft text-xs" />
            <span>
              {isLoading ? '...' : `${accounts.filter(a => a.provider === 'Azure').length} Azure`}
            </span>
          </div>
        </div>
      </div>

      {/* Active Connections */}
      <div className="kpi-card fade-in" style={{ animationDelay: '0.1s' }}>
        <div className="flex justify-between items-start">
          <div className="flex-1">
            <p className="kpi-label">Active Connections</p>
            {isLoading ? (
              <div className="skeleton h-10 w-16 mt-2" />
            ) : (
              <p className="kpi-value text-emerald-600">{activeConnections}</p>
            )}
            <p className="kpi-sublabel">verified & ready</p>
          </div>
          <div className="kpi-icon bg-gradient-to-br from-emerald-500 to-teal-600">
            <i className="fas fa-check-circle text-white text-xl" />
          </div>
        </div>
        <div className="kpi-progress-container">
          <div className="flex justify-between text-xs mb-1.5">
            <span className="text-gray-500">Connection Rate</span>
            <span className="font-semibold text-emerald-600">
              {isLoading ? '...' : `${totalAccounts > 0 ? Math.round((activeConnections / totalAccounts) * 100) : 0}%`}
            </span>
          </div>
          <div className="kpi-progress-bar">
            <div
              className="kpi-progress-fill bg-gradient-to-r from-emerald-500 to-teal-500"
              style={{
                width: isLoading ? '0%' : `${totalAccounts > 0 ? (activeConnections / totalAccounts) * 100 : 0}%`
              }}
            />
          </div>
        </div>
      </div>

      {/* GitHub Repositories */}
      <div className="kpi-card fade-in" style={{ animationDelay: '0.2s' }}>
        <div className="flex justify-between items-start">
          <div className="flex-1">
            <p className="kpi-label">GitHub Repositories</p>
            {isGithubLoading ? (
              <div className="skeleton h-10 w-16 mt-2" />
            ) : (
              <p className="kpi-value gradient-text">{totalRepos}</p>
            )}
            <p className="kpi-sublabel">connected for CI/CD</p>
          </div>
          <div className="kpi-icon bg-gradient-to-br from-gray-700 to-gray-900">
            <i className="fab fa-github text-white text-xl" />
          </div>
        </div>
        <div className="flex items-center justify-between mt-4">
          <div className="flex items-center gap-2 text-sm text-gray-500">
            <i className="fas fa-code-branch" />
            <span>Pipeline tracking enabled</span>
          </div>
          <a
            href="/add-github-config.html"
            className="text-sm font-medium text-indigo-600 hover:text-indigo-700 transition-colors"
          >
            Add Repo
            <i className="fas fa-arrow-right ml-1 text-xs" />
          </a>
        </div>
      </div>
    </div>
  );
};

export default AccountManagerKPICards;
