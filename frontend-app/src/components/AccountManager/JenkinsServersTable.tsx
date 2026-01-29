/**
 * JenkinsServersTable Component
 *
 * Table displaying connected Jenkins servers
 */

import React from 'react';
import type { JenkinsConfig } from '../../types/accountManager';

interface JenkinsServersTableProps {
  jenkinsConfigs: JenkinsConfig[];
  isLoading: boolean;
  onRemove: (config: JenkinsConfig) => void;
}

const JenkinsServersTable: React.FC<JenkinsServersTableProps> = ({
  jenkinsConfigs,
  isLoading,
  onRemove,
}) => {
  return (
    <div className="data-card fade-in">
      <div className="card-header">
        <div className="flex items-center gap-3">
          <div className="card-header-icon bg-gray-700">
            <i className="fab fa-jenkins text-white" />
          </div>
          <div>
            <h2 className="card-title">Connected Jenkins Servers</h2>
            <p className="card-subtitle">Manage Jenkins instances for pipeline tracking</p>
          </div>
        </div>
        <a
          href="/add-account.html"
          className="btn-secondary btn-dark"
        >
          <i className="fas fa-plus mr-2" />
          Add Server
        </a>
      </div>

      {/* Loading State */}
      {isLoading && (
        <div className="table-container">
          <div className="skeleton h-12 w-full" />
          <div className="skeleton h-14 w-full mt-1" />
        </div>
      )}

      {/* Empty State */}
      {!isLoading && jenkinsConfigs.length === 0 && (
        <div className="empty-state">
          <div className="empty-state-icon bg-gray-100">
            <i className="fab fa-jenkins text-gray-400" />
          </div>
          <h3 className="empty-state-title">No Jenkins Servers Connected</h3>
          <p className="empty-state-text">
            Connect a server to start monitoring build jobs.
          </p>
        </div>
      )}

      {/* Table */}
      {!isLoading && jenkinsConfigs.length > 0 && (
        <div className="table-container">
          <table className="data-table">
            <thead>
              <tr>
                <th>Server URL</th>
                <th>Username</th>
                <th className="text-center">Actions</th>
              </tr>
            </thead>
            <tbody>
              {jenkinsConfigs.map((config) => (
                <tr key={config.id} className="table-row-hover">
                  <td>
                    <div className="flex items-center gap-3">
                      <div className="provider-icon bg-gray-200">
                        <i className="fab fa-jenkins text-gray-700 text-sm" />
                      </div>
                      <div>
                        <span className="font-semibold text-gray-900">{config.jenkinsUrl}</span>
                        <a
                          href={config.jenkinsUrl}
                          target="_blank"
                          rel="noopener noreferrer"
                          className="block text-xs text-indigo-600 hover:text-indigo-700"
                        >
                          Open Dashboard
                          <i className="fas fa-external-link-alt ml-1 text-[10px]" />
                        </a>
                      </div>
                    </div>
                  </td>
                  <td>
                    <code className="code-inline">{config.username}</code>
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

export default JenkinsServersTable;
