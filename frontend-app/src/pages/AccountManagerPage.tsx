/**
 * AccountManagerPage
 *
 * Main Account Manager page component
 * Converted from: account-manager.html
 *
 * Features:
 * - View and manage cloud accounts (AWS, GCP, Azure)
 * - View and manage GitHub repositories for CI/CD
 * - View and manage Jenkins servers
 * - Verify pending AWS accounts
 * - Help modal with onboarding guides
 *
 * All API endpoints and functionality preserved from original.
 */

import React, { useState, useCallback } from 'react';
import { Link } from 'react-router-dom';
import { useAccountManager } from '../hooks/useAccountManager';
import { useTopbar } from '../contexts/TopbarContext';
import {
  AccountManagerKPICards,
  CloudAccountsTable,
  GitHubReposTable,
  JenkinsServersTable,
  VerificationModal,
  HelpModal,
} from '../components/AccountManager';
import type { CloudAccount, GitHubConfig, JenkinsConfig } from '../types/accountManager';

// Import styles
import '../styles/accountManager.css';

const AccountManagerPage: React.FC = () => {
  const {
    accounts,
    githubConfigs,
    jenkinsConfigs,
    isLoading,
    isGithubLoading,
    isJenkinsLoading,
    removeAccount,
    removeGitHubConfig,
    removeJenkinsConfig,
    verifyAccount,
    updateBillingTable,
  } = useAccountManager();

  console.log('🏗️ AccountManagerPage render:', {
    accountsCount: accounts.length,
    githubCount: githubConfigs.length,
    jenkinsCount: jenkinsConfigs.length,
    isLoading,
    isGithubLoading,
    isJenkinsLoading
  });

  const { setActions, clearActions, setOnHelpClick } = useTopbar();

  React.useEffect(() => {
    console.log('🚀 AccountManagerPage Mounted!');

    // Register Topbar actions
    setActions(
      <div className="flex items-center gap-3">
        <Link
          to="/add-account"
          className="flex items-center gap-2 px-4 py-2 bg-indigo-600 text-white rounded-xl text-xs font-bold hover:bg-indigo-700 transition-all shadow-lg shadow-indigo-500/20"
        >
          <i className="fas fa-plus"></i>
          <span>Add Account</span>
        </Link>
        <Link
          to="/add-github-config"
          className="flex items-center gap-2 px-4 py-2 bg-slate-800 text-white rounded-xl text-xs font-bold hover:bg-slate-900 transition-all shadow-lg shadow-slate-500/10"
        >
          <i className="fab fa-github"></i>
          <span>Add Repo</span>
        </Link>
      </div>
    );

    // Register help button click handler
    setOnHelpClick(() => setIsHelpModalOpen(true));

    return () => {
      clearActions();
      setOnHelpClick(null);
    };
  }, [setActions, clearActions, setOnHelpClick]);

  // Modal states
  const [isVerificationModalOpen, setIsVerificationModalOpen] = useState(false);
  const [isHelpModalOpen, setIsHelpModalOpen] = useState(false);
  const [verifyingAccount, setVerifyingAccount] = useState<CloudAccount | null>(null);

  // Handle verify account
  const handleOpenVerificationModal = useCallback((account: CloudAccount) => {
    setVerifyingAccount(account);
    setIsVerificationModalOpen(true);
  }, []);

  const handleCloseVerificationModal = useCallback(() => {
    setIsVerificationModalOpen(false);
    setVerifyingAccount(null);
  }, []);

  // Handle remove account
  const handleRemoveAccount = useCallback(async (account: CloudAccount) => {
    if (!confirm(`Are you sure you want to remove the account "${account.name}"?`)) {
      return;
    }

    try {
      await removeAccount(account);
      alert('Account removed successfully!');

      // If the removed account was selected, redirect to dashboard
      const selectedIds = JSON.parse(sessionStorage.getItem('selectedAccountIds') || '[]');
      if (selectedIds.includes(account.id)) {
        globalThis.location.href = '/dashboard';
      }
    } catch (err) {
      alert(`Error: ${err instanceof Error ? err.message : 'Failed to remove account'}`);
    }
  }, [removeAccount]);

  // Handle remove GitHub config
  const handleRemoveGitHubConfig = useCallback(async (config: GitHubConfig) => {
    if (!confirm(`Are you sure you want to remove the repository "${config.owner}/${config.repo}"?`)) {
      return;
    }

    try {
      await removeGitHubConfig(config);
      alert('Repository connection removed.');
    } catch (err) {
      alert(`Error: ${err instanceof Error ? err.message : 'Failed to remove repository'}`);
    }
  }, [removeGitHubConfig]);

  // Handle remove Jenkins config
  const handleRemoveJenkinsConfig = useCallback(async (config: JenkinsConfig) => {
    if (!confirm(`Remove Jenkins server at ${config.jenkinsUrl}?`)) {
      return;
    }

    try {
      await removeJenkinsConfig(config);
      alert('Jenkins server removed.');
    } catch (err) {
      alert(`Error: ${err instanceof Error ? err.message : 'Failed to remove server'}`);
    }
  }, [removeJenkinsConfig]);

  return (
    <div className="account-manager-page pb-12">
      {/* Content */}
      <div className="account-manager-content pt-6">
        {/* KPI Cards */}
        <AccountManagerKPICards
          accounts={accounts}
          githubConfigs={githubConfigs}
          isLoading={isLoading}
          isGithubLoading={isGithubLoading}
        />

        {/* Cloud Accounts Table */}
        <CloudAccountsTable
          accounts={accounts}
          isLoading={isLoading}
          onVerify={handleOpenVerificationModal}
          onRemove={handleRemoveAccount}
          onUpdateBillingTable={updateBillingTable}
        />

        {/* GitHub Repositories Table */}
        <GitHubReposTable
          githubConfigs={githubConfigs}
          isLoading={isGithubLoading}
          onRemove={handleRemoveGitHubConfig}
        />

        {/* Jenkins Servers Table */}
        <JenkinsServersTable
          jenkinsConfigs={jenkinsConfigs}
          isLoading={isJenkinsLoading}
          onRemove={handleRemoveJenkinsConfig}
        />
      </div>

      {/* Verification Modal */}
      <VerificationModal
        isOpen={isVerificationModalOpen}
        account={verifyingAccount}
        onClose={handleCloseVerificationModal}
        onVerify={verifyAccount}
      />

      {/* Help Modal */}
      <HelpModal
        isOpen={isHelpModalOpen}
        onClose={() => setIsHelpModalOpen(false)}
      />
    </div>
  );
};

export default AccountManagerPage;
