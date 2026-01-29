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
import { useNavigate } from 'react-router-dom';
import { useAccountManager } from '../hooks/useAccountManager';
import {
  AccountManagerHeader,
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
  const navigate = useNavigate();
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
    getStatusColor,
  } = useAccountManager();

  // Modal states
  const [isVerificationModalOpen, setIsVerificationModalOpen] = useState(false);
  const [isHelpModalOpen, setIsHelpModalOpen] = useState(false);
  const [verifyingAccount, setVerifyingAccount] = useState<CloudAccount | null>(null);

  // Handle add account navigation
  const handleAddAccount = useCallback(() => {
    navigate('/add-account');
  }, [navigate]);

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
        window.location.href = '/dashboard';
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
    <main className="account-manager-page">
      {/* Header */}
      <AccountManagerHeader onAddAccount={handleAddAccount} />

      {/* Content */}
      <div className="account-manager-content">
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
          getStatusColor={getStatusColor}
          onVerify={handleOpenVerificationModal}
          onRemove={handleRemoveAccount}
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

      {/* Floating Help Button */}
      <button
        onClick={() => setIsHelpModalOpen(true)}
        className="help-fab"
        aria-label="Open help guide"
      >
        <i className="fas fa-question" />
      </button>

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
    </main>
  );
};

export default AccountManagerPage;
