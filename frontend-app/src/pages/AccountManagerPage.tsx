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
import { Icon } from '@iconify/react';
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


  const { setActions, clearActions, setOnHelpClick } = useTopbar();

  React.useEffect(() => {
    // Register Topbar actions
    setActions(
      <Link
        to="/add-account"
        className="flex items-center gap-2 px-4 py-2 bg-[rgba(0,0,0,0.88)] hover:bg-black text-white rounded-md text-xs font-medium transition-colors"
      >
        <Icon icon="mdi:plus" width={14} />
        Add Account
      </Link>
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
    <div className="px-6 pb-12 pt-6">
      <div className="space-y-6">
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
