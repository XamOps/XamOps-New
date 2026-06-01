import React from 'react';
import { Link } from 'react-router-dom';
import { Icon } from '@iconify/react';
import type { CloudAccount, GitHubConfig } from '../../types/accountManager';

interface AccountManagerKPICardsProps {
  accounts: CloudAccount[];
  githubConfigs: GitHubConfig[];
  isLoading: boolean;
  isGithubLoading: boolean;
}

const CloudSVG = () => (
  <svg className="absolute right-2 bottom-2 opacity-[0.07]" width="80" height="60" viewBox="0 0 80 60">
    <path d="M65 48H20a18 18 0 010-36c1 0 2 0 3 .1A24 24 0 0168 28h-.5A16 16 0 0165 48z" fill="currentColor" />
  </svg>
);

const BEBAS: React.CSSProperties = {
  fontFamily: "'Bebas Neue', 'Barlow Condensed', sans-serif",
  fontSize: '32px',
  fontWeight: 400,
  lineHeight: '32px',
  color: 'rgb(10,10,10)',
  fontVariantNumeric: 'tabular-nums',
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
    <div className="grid grid-cols-1 md:grid-cols-3 gap-5 mb-6">
      {/* Total Cloud Accounts */}
      <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl p-5 shadow-sm relative overflow-hidden hover:shadow-md transition-shadow">
        <div className="flex items-center justify-between mb-3">
          <span className="text-[12px] font-semibold uppercase tracking-[0.09em] leading-[15px] text-[rgba(0,0,0,0.5)]">
            Total Cloud Accounts
          </span>
          <div className="w-8 h-8 bg-indigo-50 border border-indigo-100 rounded-lg flex items-center justify-center">
            <Icon icon="mdi:cloud-outline" width={16} color="#4F46E5" />
          </div>
        </div>
        {isLoading ? (
          <div className="h-8 w-16 bg-[rgba(0,0,0,0.06)] rounded-lg animate-pulse" />
        ) : (
          <p style={BEBAS}>{totalAccounts}</p>
        )}
        <div className="flex items-center gap-2 mt-3 pt-3 border-t border-[rgba(0,0,0,0.06)]">
          <div className="flex items-center gap-1.5 px-2 py-0.5 rounded-full bg-orange-50 border border-orange-100 text-orange-600 text-[10px] font-bold">
            <Icon icon="mdi:aws" width={11} />
            <span>{isLoading ? '–' : awsCount}</span>
          </div>
          <div className="flex items-center gap-1.5 px-2 py-0.5 rounded-full bg-blue-50 border border-blue-100 text-blue-600 text-[10px] font-bold">
            <Icon icon="mdi:google" width={11} />
            <span>{isLoading ? '–' : gcpCount}</span>
          </div>
          <div className="flex items-center gap-1.5 px-2 py-0.5 rounded-full bg-cyan-50 border border-cyan-100 text-cyan-600 text-[10px] font-bold">
            <Icon icon="mdi:microsoft" width={11} />
            <span>{isLoading ? '–' : azureCount}</span>
          </div>
        </div>
        <CloudSVG />
      </div>

      {/* Active Connections */}
      <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl p-5 shadow-sm relative overflow-hidden hover:shadow-md transition-shadow">
        <div className="flex items-center justify-between mb-3">
          <span className="text-[12px] font-semibold uppercase tracking-[0.09em] leading-[15px] text-[rgba(0,0,0,0.5)]">
            Active Connections
          </span>
          <div className="w-8 h-8 bg-emerald-50 border border-emerald-100 rounded-lg flex items-center justify-center">
            <Icon icon="mdi:signal" width={16} color="#059669" />
          </div>
        </div>
        {isLoading ? (
          <div className="h-8 w-16 bg-[rgba(0,0,0,0.06)] rounded-lg animate-pulse" />
        ) : (
          <p style={{ ...BEBAS, color: activeConnections > 0 ? '#059669' : 'rgb(10,10,10)' }}>{activeConnections}</p>
        )}
        <div className="mt-3 pt-3 border-t border-[rgba(0,0,0,0.06)] space-y-1.5">
          <div className="flex justify-between text-[10px] font-semibold uppercase tracking-wider">
            <span className="text-[rgba(0,0,0,0.4)]">Health Rate</span>
            <span className="text-emerald-600">{isLoading ? '–' : `${healthRate}%`}</span>
          </div>
          <div className="h-1.5 w-full bg-[rgba(0,0,0,0.06)] rounded-full overflow-hidden">
            <div
              className="h-full bg-emerald-500 rounded-full transition-all duration-700"
              style={{ width: isLoading ? '0%' : `${healthRate}%` }}
            />
          </div>
        </div>
        <CloudSVG />
      </div>

      {/* GitHub Repositories */}
      <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl p-5 shadow-sm relative overflow-hidden hover:shadow-md transition-shadow">
        <div className="flex items-center justify-between mb-3">
          <span className="text-[12px] font-semibold uppercase tracking-[0.09em] leading-[15px] text-[rgba(0,0,0,0.5)]">
            GitHub Repositories
          </span>
          <div className="w-8 h-8 bg-[rgba(0,0,0,0.04)] border border-[rgba(0,0,0,0.08)] rounded-lg flex items-center justify-center">
            <Icon icon="mdi:github" width={16} color="rgba(0,0,0,0.7)" />
          </div>
        </div>
        {isGithubLoading ? (
          <div className="h-8 w-16 bg-[rgba(0,0,0,0.06)] rounded-lg animate-pulse" />
        ) : (
          <p style={BEBAS}>{totalRepos}</p>
        )}
        <div className="flex items-center justify-between mt-3 pt-3 border-t border-[rgba(0,0,0,0.06)]">
          <div className="flex items-center gap-2 text-[10px] font-semibold text-[rgba(0,0,0,0.5)] uppercase tracking-wide">
            <div className="w-1.5 h-1.5 rounded-full bg-emerald-500 animate-pulse" />
            Webhooks Active
          </div>
          <Link
            to="/add-account"
            className="flex items-center gap-1 text-[10px] font-bold text-indigo-600 hover:text-indigo-700 transition-colors uppercase tracking-widest"
          >
            <Icon icon="mdi:plus" width={12} />
            Add Repo
          </Link>
        </div>
        <CloudSVG />
      </div>
    </div>
  );
};

export default AccountManagerKPICards;
