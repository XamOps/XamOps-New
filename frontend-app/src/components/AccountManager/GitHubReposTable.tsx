/**
 * GitHubReposTable Component
 *
 * Table displaying connected GitHub repositories
 */

import React from 'react';
import type { GitHubConfig } from '../../types/accountManager';

interface GitHubReposTableProps {
  githubConfigs: GitHubConfig[];
  isLoading: boolean;
  onRemove: (config: GitHubConfig) => void;
}

const GitHubReposTable: React.FC<GitHubReposTableProps> = ({
  githubConfigs,
  isLoading,
  onRemove,
}) => {
  return (
    <div className="data-card fade-in">
      <div className="card-header">
        <div className="flex items-center gap-3">
          <div className="card-header-icon bg-gray-800">
            <i className="fab fa-github text-white" />
          </div>
          <div>
            <h2 className="card-title">Connected GitHub Repositories</h2>
            <p className="card-subtitle">Manage repositories for CI/CD pipeline tracking</p>
          </div>
        </div>
        <a
          href="/add-github-config.html"
          className="btn-secondary btn-dark"
        >
          <i className="fas fa-plus mr-2" />
          Add Repository
        </a>
      </div>

      {/* Loading State */}
      {isLoading && (
        <div className="table-container">
          <div className="skeleton h-12 w-full" />
          <div className="skeleton h-14 w-full mt-1" />
          <div className="skeleton h-14 w-full mt-1" />
        </div>
      )}

      {/* Empty State */}
      {!isLoading && githubConfigs.length === 0 && (
        <div className="empty-state">
          <div className="empty-state-icon bg-gray-100">
            <i className="fab fa-github text-gray-400" />
          </div>
          <h3 className="empty-state-title">No GitHub Repositories Connected</h3>
          <p className="empty-state-text">
            Connect a repository to start monitoring its GitHub Actions.
          </p>
          <a href="/add-github-config.html" className="btn-secondary btn-dark mt-4">
            <i className="fas fa-plus mr-2" />
            Connect Repository
          </a>
        </div>
      )}

      {/* Table */}
      {!isLoading && githubConfigs.length > 0 && (
        <div className="table-container">
          <table className="data-table">
            <thead>
              <tr>
                <th>Repository</th>
                <th>Owner</th>
                <th className="text-center">Actions</th>
              </tr>
            </thead>
            <tbody>
              {githubConfigs.map((config) => (
                <tr key={config.id} className="table-row-hover">
                  <td>
                    <div className="flex items-center gap-3">
                      <div className="provider-icon bg-gray-800">
                        <i className="fab fa-github text-white text-sm" />
                      </div>
                      <div>
                        <span className="font-semibold text-gray-900">{config.repo}</span>
                        <a
                          href={`https://github.com/${config.owner}/${config.repo}`}
                          target="_blank"
                          rel="noopener noreferrer"
                          className="block text-xs text-indigo-600 hover:text-indigo-700"
                        >
                          View on GitHub
                          <i className="fas fa-external-link-alt ml-1 text-[10px]" />
                        </a>
                      </div>
                    </div>
                  </td>
                  <td>
                    <code className="code-inline">{config.owner}</code>
                  </td>
                  <td>
                    <div className="flex justify-center">
                      <button
                        onClick={() => onRemove(config)}
                        className="btn-action btn-danger"
                      >
                        <i className="fas fa-trash mr-1" />
                        Remove
                      </button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
};

export default GitHubReposTable;
