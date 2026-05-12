import React from 'react';
import { Link } from 'react-router-dom';
import { motion } from 'framer-motion';
import { Server, Trash2, ExternalLink, Plus } from 'lucide-react';
import { Card } from '../ui/card';
import { Button } from '../ui/button';
import type { JenkinsConfig } from '../../types/accountManager';

interface JenkinsServersTableProps {
  jenkinsConfigs: JenkinsConfig[];
  isLoading: boolean;
  onRemove: (config: JenkinsConfig) => void;
}

const rowVariants = {
  hidden: { opacity: 0, x: -10 },
  visible: (i: number) => ({ opacity: 1, x: 0, transition: { delay: i * 0.05, duration: 0.25 } }),
};

const JenkinsServersTable: React.FC<JenkinsServersTableProps> = ({ jenkinsConfigs, isLoading, onRemove }) => {
  return (
    <Card className="overflow-hidden shadow-sm">
      <div className="h-1 bg-gradient-to-r from-indigo-400 to-indigo-600" />

      {/* Header */}
      <div className="flex items-center justify-between px-6 py-4 border-b border-slate-100 bg-slate-50/40">
        <div className="flex items-center gap-3">
          <div className="w-9 h-9 rounded-xl bg-gradient-to-br from-indigo-500 to-indigo-700 flex items-center justify-center shadow-md shadow-indigo-200">
            <i className="fab fa-jenkins text-white text-sm" />
          </div>
          <div>
            <h2 className="text-base font-bold text-slate-900 leading-tight">Connected Jenkins Servers</h2>
            <p className="text-[10px] font-medium text-slate-400 uppercase tracking-wider">Pipeline Automation Center</p>
          </div>
        </div>
        <Link to="/add-account">
          <Button size="sm" className="gap-1.5 text-[10px] font-bold uppercase tracking-wider">
            <Plus className="w-3 h-3" />
            Add Server
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
      {!isLoading && jenkinsConfigs.length === 0 && (
        <div className="flex flex-col items-center justify-center py-16 px-6 text-center">
          <div className="w-16 h-16 rounded-full bg-indigo-50 flex items-center justify-center mb-4">
            <Server className="w-7 h-7 text-indigo-200" />
          </div>
          <h3 className="text-base font-bold text-slate-700 mb-1">No Jenkins Servers Connected</h3>
          <p className="text-sm text-slate-400 max-w-xs">Connect a server to start monitoring build jobs.</p>
        </div>
      )}

      {/* Table */}
      {!isLoading && jenkinsConfigs.length > 0 && (
        <div className="overflow-x-auto">
          <table className="w-full text-sm border-collapse">
            <thead>
              <tr className="bg-slate-50/60">
                {['Server URL', 'Username', ''].map(h => (
                  <th key={h} className="px-4 py-3 text-left text-[10px] font-bold text-slate-400 uppercase tracking-widest whitespace-nowrap last:text-right">
                    {h}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {jenkinsConfigs.map((config, i) => (
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
                      <div className="w-9 h-9 rounded-xl bg-indigo-50 border border-indigo-100 flex items-center justify-center text-indigo-600 shadow-sm group-hover:scale-110 transition-transform duration-200 flex-shrink-0">
                        <i className="fab fa-jenkins text-sm" />
                      </div>
                      <div>
                        <p className="font-bold text-slate-900 leading-tight">{config.jenkinsUrl}</p>
                        <a
                          href={config.jenkinsUrl}
                          target="_blank"
                          rel="noopener noreferrer"
                          className="inline-flex items-center gap-1 text-[10px] font-bold text-indigo-600 hover:text-indigo-700 mt-0.5 uppercase tracking-wider"
                        >
                          Open Dashboard
                          <ExternalLink className="w-2.5 h-2.5" />
                        </a>
                      </div>
                    </div>
                  </td>
                  <td className="px-4 py-3.5 whitespace-nowrap">
                    <code className="text-[11px] font-mono font-semibold px-2 py-1 bg-slate-100 text-slate-600 rounded-lg border border-slate-200">
                      {config.username}
                    </code>
                  </td>
                  <td className="px-4 py-3.5 whitespace-nowrap text-right">
                    <button
                      onClick={() => onRemove(config)}
                      title="Remove Server"
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

export default JenkinsServersTable;
