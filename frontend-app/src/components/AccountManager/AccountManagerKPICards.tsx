import React from 'react';
import { Link } from 'react-router-dom';
import { motion } from 'framer-motion';
import { Cloud, Signal, GitBranch, Plus } from 'lucide-react';
import { Card, CardContent } from '../ui/card';
import type { CloudAccount, GitHubConfig } from '../../types/accountManager';

interface AccountManagerKPICardsProps {
  accounts: CloudAccount[];
  githubConfigs: GitHubConfig[];
  isLoading: boolean;
  isGithubLoading: boolean;
}

const cardVariants = {
  hidden: { opacity: 0, y: 20 },
  visible: (i: number) => ({
    opacity: 1, y: 0,
    transition: { delay: i * 0.08, duration: 0.4, ease: 'easeOut' as const },
  }),
};

const AccountManagerKPICards: React.FC<AccountManagerKPICardsProps> = ({
  accounts = [],
  githubConfigs = [],
  isLoading,
  isGithubLoading,
}) => {
  const totalAccounts = accounts.length;
  const activeConnections = accounts.filter(a => a.status === 'CONNECTED').length;
  const totalRepos = githubConfigs.length;
  const healthRate = totalAccounts > 0 ? Math.round((activeConnections / totalAccounts) * 100) : 0;

  const awsCount = accounts.filter(a => a.provider === 'AWS').length;
  const gcpCount = accounts.filter(a => a.provider === 'GCP').length;
  const azureCount = accounts.filter(a => a.provider === 'Azure').length;

  return (
    <div className="grid grid-cols-1 md:grid-cols-3 gap-5">
      {/* Total Cloud Accounts */}
      <motion.div custom={0} variants={cardVariants} initial="hidden" animate="visible">
        <Card className="overflow-hidden shadow-sm hover:shadow-md transition-shadow duration-300 group">
          <div className="h-1 bg-gradient-to-r from-indigo-500 to-violet-500" />
          <CardContent className="p-5">
            <div className="flex items-start justify-between mb-4">
              <div>
                <p className="text-[10px] font-bold text-slate-400 uppercase tracking-widest mb-1">Total Cloud Accounts</p>
                {isLoading ? (
                  <div className="h-10 w-16 bg-slate-100 rounded-lg animate-pulse mt-1" />
                ) : (
                  <p className="text-4xl font-extrabold text-slate-900 leading-none">{totalAccounts}</p>
                )}
                <p className="text-[11px] text-slate-400 mt-1 italic">multi-cloud connections</p>
              </div>
              <div className="w-11 h-11 rounded-xl bg-indigo-50 border border-indigo-100 flex items-center justify-center group-hover:scale-110 transition-transform duration-300">
                <Cloud className="w-5 h-5 text-indigo-500" />
              </div>
            </div>
            <div className="flex items-center gap-3 pt-3 border-t border-slate-100">
              <div className="flex items-center gap-1.5 px-2.5 py-1 rounded-lg bg-orange-50 border border-orange-100 text-orange-600 text-[10px] font-bold">
                <i className="fab fa-aws text-[11px]" />
                <span>{isLoading ? '–' : awsCount}</span>
              </div>
              <div className="flex items-center gap-1.5 px-2.5 py-1 rounded-lg bg-blue-50 border border-blue-100 text-blue-600 text-[10px] font-bold">
                <i className="fab fa-google text-[11px]" />
                <span>{isLoading ? '–' : gcpCount}</span>
              </div>
              <div className="flex items-center gap-1.5 px-2.5 py-1 rounded-lg bg-cyan-50 border border-cyan-100 text-cyan-600 text-[10px] font-bold">
                <i className="fab fa-microsoft text-[11px]" />
                <span>{isLoading ? '–' : azureCount}</span>
              </div>
            </div>
          </CardContent>
        </Card>
      </motion.div>

      {/* Active Connections */}
      <motion.div custom={1} variants={cardVariants} initial="hidden" animate="visible">
        <Card className="overflow-hidden shadow-sm hover:shadow-md transition-shadow duration-300 group">
          <div className="h-1 bg-gradient-to-r from-emerald-400 to-teal-500" />
          <CardContent className="p-5">
            <div className="flex items-start justify-between mb-4">
              <div>
                <p className="text-[10px] font-bold text-slate-400 uppercase tracking-widest mb-1">Active Connections</p>
                {isLoading ? (
                  <div className="h-10 w-16 bg-slate-100 rounded-lg animate-pulse mt-1" />
                ) : (
                  <p className="text-4xl font-extrabold text-emerald-600 leading-none">{activeConnections}</p>
                )}
                <p className="text-[11px] text-slate-400 mt-1 italic">verified & live</p>
              </div>
              <div className="w-11 h-11 rounded-xl bg-emerald-50 border border-emerald-100 flex items-center justify-center group-hover:scale-110 transition-transform duration-300">
                <Signal className="w-5 h-5 text-emerald-500" />
              </div>
            </div>
            <div className="pt-3 border-t border-slate-100 space-y-1.5">
              <div className="flex justify-between text-[10px] font-bold uppercase tracking-wider">
                <span className="text-slate-400">Health Rate</span>
                <span className="text-emerald-600">{isLoading ? '–' : `${healthRate}%`}</span>
              </div>
              <div className="h-1.5 w-full bg-slate-100 rounded-full overflow-hidden">
                <motion.div
                  className="h-full bg-gradient-to-r from-emerald-400 to-teal-500 rounded-full"
                  initial={{ width: 0 }}
                  animate={{ width: isLoading ? '0%' : `${healthRate}%` }}
                  transition={{ duration: 0.8, ease: 'easeOut', delay: 0.3 }}
                />
              </div>
            </div>
          </CardContent>
        </Card>
      </motion.div>

      {/* GitHub Repositories */}
      <motion.div custom={2} variants={cardVariants} initial="hidden" animate="visible">
        <Card className="overflow-hidden shadow-sm hover:shadow-md transition-shadow duration-300 group">
          <div className="h-1 bg-gradient-to-r from-slate-700 to-slate-900" />
          <CardContent className="p-5">
            <div className="flex items-start justify-between mb-4">
              <div>
                <p className="text-[10px] font-bold text-slate-400 uppercase tracking-widest mb-1">GitHub Repositories</p>
                {isGithubLoading ? (
                  <div className="h-10 w-16 bg-slate-100 rounded-lg animate-pulse mt-1" />
                ) : (
                  <p className="text-4xl font-extrabold text-slate-900 leading-none">{totalRepos}</p>
                )}
                <p className="text-[11px] text-slate-400 mt-1 italic">connected repos</p>
              </div>
              <div className="w-11 h-11 rounded-xl bg-slate-100 border border-slate-200 flex items-center justify-center group-hover:scale-110 transition-transform duration-300">
                <GitBranch className="w-5 h-5 text-slate-700" />
              </div>
            </div>
            <div className="flex items-center justify-between pt-3 border-t border-slate-100">
              <div className="flex items-center gap-2 text-[10px] font-semibold text-slate-500 uppercase tracking-wide">
                <div className="w-1.5 h-1.5 rounded-full bg-emerald-500 animate-pulse" />
                Webhooks Active
              </div>
              <Link
                to="/add-account"
                className="flex items-center gap-1 text-[10px] font-bold text-indigo-600 hover:text-indigo-700 transition-colors uppercase tracking-widest"
              >
                <Plus className="w-3 h-3" />
                Add Repo
              </Link>
            </div>
          </CardContent>
        </Card>
      </motion.div>
    </div>
  );
};

export default AccountManagerKPICards;
