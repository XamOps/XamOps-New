/**
 * JenkinsServersTable Component
 *
 * Table displaying connected Jenkins servers
 */

import React from 'react';
import { Link } from 'react-router-dom';
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
    <div className="data-card overflow-hidden">
      <div className="card-header border-b border-slate-100 bg-slate-50/30 px-6 py-5">
        <div className="flex items-center gap-4">
          <div className="card-header-icon bg-gradient-to-br from-indigo-500 to-indigo-700 shadow-indigo-200 shadow-lg">
            <i className="fab fa-jenkins text-white" />
          </div>
          <div>
            <h2 className="text-lg font-bold text-slate-900 leading-tight">Connected Jenkins Servers</h2>
            <p className="text-xs font-medium text-slate-400 mt-0.5 uppercase tracking-wider">Pipeline Automation Center</p>
          </div>
        </div>
        <div className="flex items-center gap-3">
          <Link
            to="/add-account"
            className="flex items-center gap-2 px-4 py-2 bg-indigo-600 text-white rounded-xl text-[10px] font-bold uppercase tracking-wider hover:bg-indigo-700 transition-all shadow-lg shadow-indigo-500/20"
          >
            <i className="fas fa-plus" />
            <span>Add Server</span>
          </Link>
        </div>
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
                <th className="nowrap">Server URL</th>
                <th className="nowrap">Username</th>
                <th className="text-right nowrap">Actions</th>
              </tr>
            </thead>
            <tbody>
              {jenkinsConfigs.map((config) => (
                <tr key={config.id} className="group border-b border-slate-50 hover:bg-slate-50/50 transition-colors">
                  <td className="py-4 nowrap">
                    <div className="flex items-center gap-4">
                      <div className="w-10 h-10 rounded-xl bg-indigo-50 flex items-center justify-center text-indigo-600 shadow-sm group-hover:scale-110 transition-transform duration-300">
                        <i className="fab fa-jenkins text-lg" />
                      </div>
                      <div className="flex flex-col">
                        <span className="font-bold text-slate-900 leading-tight nowrap">{config.jenkinsUrl}</span>
                        <a
                          href={config.jenkinsUrl}
                          target="_blank"
                          rel="noopener noreferrer"
                          className="inline-flex items-center gap-1 text-[10px] font-bold text-indigo-600 hover:text-indigo-700 mt-1 uppercase tracking-wider nowrap"
                        >
                          Open Dashboard
                          <i className="fas fa-external-link-alt text-[9px]" />
                        </a>
                      </div>
                    </div>
                  </td>
                  <td className="nowrap">
                    <code className="text-[11px] font-mono font-bold px-2 py-1 bg-slate-100 text-slate-600 rounded-lg border border-slate-200">
                      {config.username}
                    </code>
                  </td>
                  <td className="text-right nowrap">
                    <div className="flex justify-end">
                      <button
                        onClick={() => onRemove(config)}
                        className="h-8 w-8 rounded-lg bg-rose-50 text-rose-500 hover:bg-rose-500 hover:text-white transition-all flex items-center justify-center shadow-sm"
                        title="Remove Server"
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

export default JenkinsServersTable;
