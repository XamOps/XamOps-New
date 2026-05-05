import React from 'react';
import { Link } from 'react-router-dom';
import { motion } from 'framer-motion';
import { GitBranch, Trash2, ExternalLink, Plus } from 'lucide-react';
import { Card } from '../ui/card';
import { Button } from '../ui/button';
import type { GitHubConfig } from '../../types/accountManager';

interface GitHubReposTableProps {
  githubConfigs: GitHubConfig[];
  isLoading: boolean;
  onRemove: (config: GitHubConfig) => void;
}

const rowVariants = {
  hidden: { opacity: 0, x: -10 },
  visible: (i: number) => ({ opacity: 1, x: 0, transition: { delay: i * 0.05, duration: 0.25 } }),
};

const GitHubReposTable: React.FC<GitHubReposTableProps> = ({ githubConfigs, isLoading, onRemove }) => {
  return (
    <Card className="overflow-hidden shadow-sm">
      <div className="h-1 bg-gradient-to-r from-slate-700 to-slate-900" />

      {/* Header */}
      <div className="flex items-center justify-between px-6 py-4 border-b border-slate-100 bg-slate-50/40">
        <div className="flex items-center gap-3">
          <div className="w-9 h-9 rounded-xl bg-gradient-to-br from-slate-700 to-slate-900 flex items-center justify-center shadow-md shadow-slate-300">
            <i className="fab fa-github text-white text-sm" />
          </div>
          <div>
            <h2 className="text-base font-bold text-slate-900 leading-tight">Connected GitHub Repositories</h2>
            <p className="text-[10px] font-medium text-slate-400 uppercase tracking-wider">CI/CD Pipeline Tracking</p>
          </div>
        </div>
        <Link to="/add-account">
          <Button size="sm" variant="default" className="gap-1.5 text-[10px] font-bold uppercase tracking-wider">
            <Plus className="w-3 h-3" />
            Add Repository
          </Button>
        </Link>
      </div>

      {/* Loading */}
      {isLoading && (
        <div className="p-6 space-y-2">
          {[1, 2].map(i => <div key={i} className="h-14 bg-slate-100 rounded-xl animate-pulse" />)}
        </div>
      )}

      {/* Empty */}
      {!isLoading && githubConfigs.length === 0 && (
        <div className="flex flex-col items-center justify-center py-16 px-6 text-center">
          <div className="w-16 h-16 rounded-full bg-slate-100 flex items-center justify-center mb-4">
            <GitBranch className="w-7 h-7 text-slate-300" />
          </div>
          <h3 className="text-base font-bold text-slate-700 mb-1">No GitHub Repositories Connected</h3>
          <p className="text-sm text-slate-400 max-w-xs mb-5">Connect a repository to start monitoring its GitHub Actions.</p>
          <Link to="/add-account">
            <Button size="sm" variant="outline" className="gap-2">
              <Plus className="w-3.5 h-3.5" />
              Connect Repository
            </Button>
          </Link>
        </div>
      )}

      {/* Table */}
      {!isLoading && githubConfigs.length > 0 && (
        <div className="overflow-x-auto">
          <table className="w-full text-sm border-collapse">
            <thead>
              <tr className="bg-slate-50/60">
                {['Repository', 'Owner', ''].map(h => (
                  <th key={h} className="px-4 py-3 text-left text-[10px] font-bold text-slate-400 uppercase tracking-widest whitespace-nowrap last:text-right">
                    {h}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {githubConfigs.map((config, i) => (
                <motion.tr
                  key={config.id}
                  custom={i}
                  variants={rowVariants}
                  initial="hidden"
                  animate="visible"
                  className="group border-b border-slate-50 hover:bg-slate-50/60 transition-colors"
                >
                  <td className="px-4 py-3.5 whitespace-nowrap">
                    <div className="flex items-center gap-3">
                      <div className="w-9 h-9 rounded-xl bg-slate-800 flex items-center justify-center text-white shadow-sm group-hover:scale-110 transition-transform duration-200 flex-shrink-0">
                        <i className="fab fa-github text-sm" />
                      </div>
                      <div>
                        <p className="font-bold text-slate-900 leading-tight">{config.repo}</p>
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
                    <code className="text-[11px] font-mono font-semibold px-2 py-1 bg-slate-100 text-slate-600 rounded-lg border border-slate-200">
                      {config.owner}
                    </code>
                  </td>
                  <td className="px-4 py-3.5 whitespace-nowrap text-right">
                    <button
                      onClick={() => onRemove(config)}
                      title="Remove Repository"
                      className="h-8 w-8 rounded-lg bg-rose-50 border border-rose-100 text-rose-500 hover:bg-rose-500 hover:text-white hover:border-rose-500 transition-all flex items-center justify-center ml-auto"
                    >
                      <Trash2 className="w-3.5 h-3.5" />
                    </button>
                  </td>
                </motion.tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </Card>
  );
};

export default GitHubReposTable;
