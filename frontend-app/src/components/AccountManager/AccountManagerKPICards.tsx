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

  if (isLoading) {
    return (
      <div className="bg-white border border-[rgba(0,0,0,0.08)] rounded-xl overflow-hidden">
        <div className="grid grid-cols-3 divide-x divide-[rgba(0,0,0,0.06)]">
          {[1, 2, 3].map(i => (
            <div key={i} className="p-5 animate-pulse">
              <div className="h-2.5 w-24 bg-[rgba(0,0,0,0.06)] rounded mb-3" />
              <div className="h-7 w-12 bg-[rgba(0,0,0,0.06)] rounded mb-2" />
              <div className="h-2 w-32 bg-[rgba(0,0,0,0.04)] rounded" />
            </div>
          ))}
        </div>
      </div>
    );
  }

  return (
    <div className="bg-white border border-[rgba(0,0,0,0.08)] rounded-xl overflow-hidden">
      <div className="grid grid-cols-1 md:grid-cols-3 divide-x divide-[rgba(0,0,0,0.06)]">

        {/* Total Cloud Accounts */}
        <div className="p-5 flex items-start gap-3">
          <div className="w-9 h-9 rounded-lg bg-indigo-50 border border-indigo-100 flex items-center justify-center flex-shrink-0 mt-0.5">
            <Icon icon="mdi:cloud-outline" width={20} className="text-indigo-600" />
          </div>
          <div className="min-w-0">
            <p className="text-[10px] uppercase tracking-wider font-semibold text-[rgba(0,0,0,0.45)] mb-1.5">
              CLOUD ACCOUNTS
            </p>
            <p className="text-[26px] font-bold text-black leading-none mb-2">{totalAccounts}</p>
            <div className="flex items-center gap-2">
              <span className="inline-flex items-center gap-1 text-[10px] font-semibold text-[rgba(0,0,0,0.6)]">
                <img src="/icons/aws-icon.png" alt="AWS" className="w-3.5 h-3.5 object-contain" /> {awsCount}
              </span>
              <span className="inline-flex items-center gap-1 text-[10px] font-semibold text-[rgba(0,0,0,0.6)]">
                <img src="/icons/gcp-icon.png" alt="GCP" className="w-3.5 h-3.5 object-contain" /> {gcpCount}
              </span>
              {azureCount > 0 && (
                <span className="inline-flex items-center gap-1 text-[10px] font-semibold text-[rgba(0,0,0,0.6)]">
                  <img src="/icons/azure-icon.png" alt="Azure" className="w-3.5 h-3.5 object-contain" /> {azureCount}
                </span>
              )}
            </div>
          </div>
        </div>

        {/* Active Connections */}
        <div className="p-5 flex items-start gap-3">
          <div className="w-9 h-9 rounded-lg bg-emerald-50 border border-emerald-100 flex items-center justify-center flex-shrink-0 mt-0.5">
            <Icon icon="mdi:signal" width={20} className="text-emerald-600" />
          </div>
          <div className="min-w-0 flex-1">
            <p className="text-[10px] uppercase tracking-wider font-semibold text-[rgba(0,0,0,0.45)] mb-1.5">
              ACTIVE CONNECTIONS
            </p>
            <p className="text-[26px] font-bold text-emerald-600 leading-none mb-2">{activeConnections}</p>
            <div className="flex items-center gap-2">
              <div className="flex-1 h-1 bg-[rgba(0,0,0,0.06)] rounded-full overflow-hidden">
                <div className="h-full bg-emerald-500 rounded-full transition-all duration-500" style={{ width: `${healthRate}%` }} />
              </div>
              <span className="text-[10px] font-semibold text-emerald-600">{healthRate}%</span>
            </div>
          </div>
        </div>

        {/* GitHub Repositories */}
        <div className="p-5 flex items-start gap-3">
          <div className="w-9 h-9 rounded-lg bg-[rgba(0,0,0,0.04)] border border-[rgba(0,0,0,0.08)] flex items-center justify-center flex-shrink-0 mt-0.5">
            <Icon icon="mdi:github" width={20} className="text-[rgba(0,0,0,0.7)]" />
          </div>
          <div className="min-w-0 flex-1">
            <p className="text-[10px] uppercase tracking-wider font-semibold text-[rgba(0,0,0,0.45)] mb-1.5">
              GITHUB REPOSITORIES
            </p>
            <p className="text-[26px] font-bold text-black leading-none mb-2">
              {isGithubLoading ? '–' : totalRepos}
            </p>
            <div className="flex items-center justify-between">
              <span className="flex items-center gap-1.5 text-[10px] font-medium text-[rgba(0,0,0,0.4)]">
                <span className="w-1.5 h-1.5 rounded-full bg-emerald-500" />
                Webhooks active
              </span>
              <Link to="/add-account" className="text-[10px] font-semibold text-black hover:underline flex items-center gap-0.5">
                + Add Repo
              </Link>
            </div>
          </div>
        </div>

      </div>
    </div>
  );
};

export default AccountManagerKPICards;
