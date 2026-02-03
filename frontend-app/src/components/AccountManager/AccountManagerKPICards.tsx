/**
 * AccountManagerKPICards Component
 *
 * KPI cards showing total accounts, active connections, and GitHub repos count
 */

import React from 'react';
import { Link } from 'react-router-dom';
import type { CloudAccount, GitHubConfig } from '../../types/accountManager';

interface AccountManagerKPICardsProps {
  accounts: CloudAccount[];
  githubConfigs: GitHubConfig[];
  isLoading: boolean;
  isGithubLoading: boolean;
}

const AccountManagerKPICards: React.FC<AccountManagerKPICardsProps> = ({
  accounts = [],
  githubConfigs = [],
  isLoading,
  isGithubLoading,
}) => {
  console.log('🏗️ AccountManagerKPICards render:', {
    accounts: accounts?.length || 0,
    github: githubConfigs?.length || 0,
    isLoading,
    isGithubLoading
  });

  const totalAccounts = accounts?.length || 0;
  const activeConnections = (accounts || []).filter(a => a.status === 'CONNECTED').length;
  const totalRepos = githubConfigs?.length || 0;

  return (
    <div className="grid grid-cols-1 md:grid-cols-3 gap-8">
      {/* Total Cloud Accounts */}
      <div className="kpi-card group">
        <div className="flex justify-between items-start mb-3">
          <div className="flex-1">
            <p className="kpi-label uppercase tracking-widest text-[10px] font-bold text-slate-400 mb-0.5">Total Cloud Accounts</p>
            {isLoading ? (
              <div className="skeleton h-10 w-16 mt-2" />
            ) : (
              <p className="text-4xl font-extrabold tracking-tight text-slate-900 leading-none">
                {totalAccounts}
              </p>
            )}
            <p className="text-[11px] text-slate-500 mt-1 font-medium italic">multi-cloud connections</p>
          </div>
          <div className="w-12 h-12 rounded-2xl bg-indigo-50 flex items-center justify-center text-indigo-600 transition-transform group-hover:scale-110 duration-300 shadow-sm border border-indigo-100/50">
            <i className="fas fa-cloud text-xl" />
          </div>
        </div>
        <div className="flex items-center gap-4 pt-3 border-t border-slate-100">
          <div className="flex items-center gap-1.5 px-2 py-1 rounded-lg bg-orange-50 text-orange-600 text-[10px] font-bold">
            <i className="fab fa-aws" />
            <span>{isLoading ? '..' : (accounts || []).filter(a => a.provider === 'AWS').length}</span>
          </div>
          <div className="flex items-center gap-1.5 px-2 py-1 rounded-lg bg-blue-50 text-blue-600 text-[10px] font-bold">
            <i className="fab fa-google" />
            <span>{isLoading ? '..' : (accounts || []).filter(a => a.provider === 'GCP').length}</span>
          </div>
          <div className="flex items-center gap-1.5 px-2 py-1 rounded-lg bg-cyan-50 text-cyan-600 text-[10px] font-bold">
            <i className="fab fa-microsoft" />
            <span>{isLoading ? '..' : (accounts || []).filter(a => a.provider === 'Azure').length}</span>
          </div>
        </div>
      </div>

      {/* Active Connections */}
      <div className="kpi-card group">
        <div className="flex justify-between items-start mb-3">
          <div className="flex-1">
            <p className="kpi-label uppercase tracking-widest text-[10px] font-bold text-slate-400 mb-0.5">Active Connections</p>
            {isLoading ? (
              <div className="skeleton h-10 w-16 mt-2" />
            ) : (
              <p className="text-4xl font-extrabold tracking-tight text-emerald-600 leading-none">
                {activeConnections}
              </p>
            )}
            <p className="text-[11px] text-slate-500 mt-1 font-medium italic">verified & live</p>
          </div>
          <div className="w-12 h-12 rounded-2xl bg-emerald-50 flex items-center justify-center text-emerald-600 transition-transform group-hover:scale-110 duration-300 shadow-sm border border-emerald-100/50">
            <i className="fas fa-signal text-xl" />
          </div>
        </div>
        <div className="space-y-2 pt-3 border-t border-slate-100">
          <div className="flex justify-between text-[10px] font-bold uppercase tracking-wider mb-1">
            <span className="text-slate-400 text-[9px]">Health Rate</span>
            <span className="text-emerald-600">
              {isLoading ? '..' : `${totalAccounts > 0 ? Math.round((activeConnections / totalAccounts) * 100) : 0}%`}
            </span>
          </div>
          <div className="h-1.5 w-full bg-slate-100 rounded-full overflow-hidden">
            <div
              className="h-full bg-gradient-to-r from-emerald-400 to-teal-500 rounded-full transition-all duration-1000"
              style={{
                width: isLoading ? '0%' : `${totalAccounts > 0 ? (activeConnections / totalAccounts) * 100 : 0}%`
              }}
            />
          </div>
        </div>
      </div>

      {/* GitHub Repositories */}
      <div className="kpi-card group">
        <div className="flex justify-between items-start mb-3">
          <div className="flex-1">
            <p className="kpi-label uppercase tracking-widest text-[10px] font-bold text-slate-400 mb-0.5">GitHub Repositories</p>
            {isGithubLoading ? (
              <div className="skeleton h-10 w-16 mt-2" />
            ) : (
              <p className="text-4xl font-extrabold tracking-tight text-slate-900 leading-none">
                {totalRepos}
              </p>
            )}
            <p className="text-[11px] text-slate-500 mt-1 font-medium italic">connected repos</p>
          </div>
          <div className="w-12 h-12 rounded-2xl bg-slate-100 flex items-center justify-center text-slate-800 transition-transform group-hover:scale-110 duration-300 shadow-sm border border-slate-200/50">
            <i className="fab fa-github text-xl" />
          </div>
        </div>
        <div className="flex items-center justify-between pt-3 border-t border-slate-100">
          <div className="flex items-center gap-2 text-xs font-semibold text-slate-600">
            <div className="w-1.5 h-1.5 rounded-full bg-emerald-500 animate-pulse" />
            <span className="text-[10px] uppercase tracking-wide">Webhooks Active</span>
          </div>
          <Link
            to="/add-github-config"
            className="flex items-center gap-1 text-[10px] font-bold text-indigo-600 hover:text-indigo-700 transition-colors uppercase tracking-widest"
          >
            Add Repo
            <i className="fas fa-plus text-[9px]" />
          </Link>
        </div>
      </div>
    </div>
  );
};

export default AccountManagerKPICards;
