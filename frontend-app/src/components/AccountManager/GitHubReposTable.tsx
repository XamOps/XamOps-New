import React from 'react';
import { Link } from 'react-router-dom';
import { Icon } from '@iconify/react';
import { ExternalLink } from 'lucide-react';
import type { GitHubConfig } from '../../types/accountManager';

interface GitHubReposTableProps {
  githubConfigs: GitHubConfig[];
  isLoading: boolean;
  onRemove: (config: GitHubConfig) => void;
}

const GitHubReposTable: React.FC<GitHubReposTableProps> = ({ githubConfigs, isLoading, onRemove }) => {
  return (
    <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm overflow-hidden">
      {/* Header */}
      <div className="flex items-center justify-between px-6 py-4 border-b border-[rgba(0,0,0,0.06)]">
        <div className="flex items-center gap-3">
          <div className="w-8 h-8 bg-[rgba(0,0,0,0.04)] border border-[rgba(0,0,0,0.08)] rounded-lg flex items-center justify-center">
            <Icon icon="mdi:github" width={16} color="rgba(0,0,0,0.7)" />
          </div>
          <div>
            <h2 className="text-sm font-bold text-[rgba(0,0,0,0.88)] leading-tight">Connected GitHub Repositories</h2>
            <p className="text-[10px] font-medium text-[rgba(0,0,0,0.4)] uppercase tracking-wider">CI/CD Pipeline Tracking</p>
          </div>
        </div>
        <Link
          to="/add-account"
          className="flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium bg-[rgba(0,0,0,0.88)] hover:bg-black text-white rounded-md transition-colors"
        >
          <Icon icon="mdi:plus" width={14} />
          Add Repository
        </Link>
      </div>

      {/* Loading */}
      {isLoading && (
        <div className="p-6 space-y-2">
          {[1, 2].map(i => <div key={i} className="h-14 bg-[rgba(0,0,0,0.06)] rounded-xl animate-pulse" />)}
        </div>
      )}

      {/* Empty */}
      {!isLoading && githubConfigs.length === 0 && (
        <div className="flex flex-col items-center justify-center py-16 px-6 text-center">
          <div className="w-14 h-14 bg-[rgba(0,0,0,0.04)] border border-[rgba(0,0,0,0.08)] rounded-xl flex items-center justify-center mb-4">
            <Icon icon="mdi:source-branch" width={28} color="rgba(0,0,0,0.3)" />
          </div>
          <h3 className="text-sm font-bold text-[rgba(0,0,0,0.7)] mb-1">No GitHub Repositories Connected</h3>
          <p className="text-sm text-[rgba(0,0,0,0.4)] max-w-xs mb-5">Connect a repository to start monitoring its GitHub Actions.</p>
          <Link
            to="/add-account"
            className="flex items-center gap-1.5 px-4 py-2 text-xs font-medium bg-[rgba(0,0,0,0.04)] border border-[rgba(0,0,0,0.08)] text-[rgba(0,0,0,0.7)] rounded-md hover:bg-[rgba(0,0,0,0.06)] transition-colors"
          >
            <Icon icon="mdi:plus" width={14} />
            Connect Repository
          </Link>
        </div>
      )}

      {/* Table */}
      {!isLoading && githubConfigs.length > 0 && (
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="bg-[rgba(0,0,0,0.02)] border-b border-[rgba(0,0,0,0.06)] text-left">
                {['Repository', 'Owner', ''].map((h, idx) => (
                  <th
                    key={h}
                    className={`px-4 py-3 text-xs font-semibold text-[rgba(0,0,0,0.4)] uppercase tracking-wider whitespace-nowrap ${idx === 2 ? 'text-right' : ''}`}
                  >
                    {h}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody className="divide-y divide-[rgba(0,0,0,0.04)]">
              {githubConfigs.map((config) => (
                <tr
                  key={config.id}
                  className="hover:bg-[rgba(0,0,0,0.025)] transition-colors"
                >
                  <td className="px-4 py-3.5 whitespace-nowrap">
                    <div className="flex items-center gap-3">
                      <div className="w-9 h-9 rounded-xl bg-[rgba(0,0,0,0.88)] flex items-center justify-center flex-shrink-0">
                        <Icon icon="mdi:github" width={16} color="white" />
                      </div>
                      <div>
                        <p className="font-bold text-[rgba(0,0,0,0.88)] leading-tight">{config.repo}</p>
                        <a
                          href={`https://github.com/${config.owner}/${config.repo}`}
                          target="_blank"
                          rel="noopener noreferrer"
                          className="inline-flex items-center gap-1 text-[10px] font-bold text-indigo-600 hover:text-indigo-700 mt-0.5 uppercase tracking-wider"
                        >
                          View Source
                          <ExternalLink className="w-2.5 h-2.5" />
                        </a>
                      </div>
                    </div>
                  </td>
                  <td className="px-4 py-3.5 whitespace-nowrap">
                    <code className="text-[11px] font-mono font-semibold px-2 py-1 bg-[rgba(0,0,0,0.04)] text-[rgba(0,0,0,0.6)] rounded-lg border border-[rgba(0,0,0,0.08)]">
                      {config.owner}
                    </code>
                  </td>
                  <td className="px-4 py-3.5 whitespace-nowrap text-right">
                    <button
                      onClick={() => onRemove(config)}
                      title="Remove Repository"
                      className="h-8 w-8 rounded-lg bg-rose-50 border border-rose-100 text-rose-500 hover:bg-rose-500 hover:text-white hover:border-rose-500 transition-all flex items-center justify-center ml-auto"
                    >
                      <Icon icon="mdi:trash-can-outline" width={14} />
                    </button>
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
