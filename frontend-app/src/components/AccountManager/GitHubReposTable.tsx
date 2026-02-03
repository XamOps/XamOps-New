/**
 * GitHubReposTable Component
 *
 * Table displaying connected GitHub repositories
 */

import React from 'react';
import { Link } from 'react-router-dom';
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
    <div className="data-card overflow-hidden">
      <div className="card-header border-b border-slate-100 bg-slate-50/30 px-6 py-5">
        <div className="flex items-center gap-4">
          <div className="card-header-icon bg-gradient-to-br from-slate-700 to-slate-900 shadow-slate-200 shadow-lg">
            <i className="fab fa-github text-white" />
          </div>
          <div>
            <h2 className="text-lg font-bold text-slate-900 leading-tight">Connected GitHub Repositories</h2>
            <p className="text-xs font-medium text-slate-400 mt-0.5 uppercase tracking-wider">CI/CD Pipeline Tracking</p>
          </div>
        </div>
        <div className="flex items-center gap-3">
          <Link
            to="/add-github-config"
            className="flex items-center gap-2 px-4 py-2 bg-indigo-600 text-white rounded-xl text-[10px] font-bold uppercase tracking-wider hover:bg-indigo-700 transition-all shadow-lg shadow-indigo-500/20"
          >
            <i className="fas fa-plus" />
            <span>Add Repository</span>
          </Link>
        </div>
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
          <Link to="/add-github-config" className="btn-secondary btn-dark mt-4">
            <i className="fas fa-plus mr-2" />
            Connect Repository
          </Link>
        </div>
      )}

      {/* Table */}
      {!isLoading && githubConfigs.length > 0 && (
        <div className="table-container">
          <table className="data-table">
            <thead>
              <tr>
                <th className="nowrap">Repository</th>
                <th className="nowrap">Owner</th>
                <th className="text-right nowrap">Actions</th>
              </tr>
            </thead>
            <tbody>
              {githubConfigs.map((config) => (
                <tr key={config.id} className="group border-b border-slate-50 hover:bg-slate-50/50 transition-colors">
                  <td className="py-4 nowrap">
                    <div className="flex items-center gap-4">
                      <div className="w-10 h-10 rounded-xl bg-slate-800 flex items-center justify-center text-white shadow-sm group-hover:scale-110 transition-transform duration-300">
                        <i className="fab fa-github text-lg" />
                      </div>
                      <div className="flex flex-col">
                        <span className="font-bold text-slate-900 leading-tight nowrap">{config.repo}</span>
                        <a
                          href={`https://github.com/${config.owner}/${config.repo}`}
                          target="_blank"
                          rel="noopener noreferrer"
                          className="inline-flex items-center gap-1 text-[10px] font-bold text-indigo-600 hover:text-indigo-700 mt-1 uppercase tracking-wider nowrap"
                        >
                          View Source
                          <i className="fas fa-external-link-alt text-[9px]" />
                        </a>
                      </div>
                    </div>
                  </td>
                  <td className="nowrap">
                    <code className="text-[11px] font-mono font-bold px-2 py-1 bg-slate-100 text-slate-600 rounded-lg border border-slate-200">
                      {config.owner}
                    </code>
                  </td>
                  <td className="text-right nowrap">
                    <div className="flex justify-end">
                      <button
                        onClick={() => onRemove(config)}
                        className="h-8 w-8 rounded-lg bg-rose-50 text-rose-500 hover:bg-rose-500 hover:text-white transition-all flex items-center justify-center shadow-sm"
                        title="Remove Repository"
                      >
                        <i className="fas fa-trash text-xs" />
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
