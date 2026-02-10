/**
 * AddAccountPage
 *
 * Main Add Account page component
 * Converted from: add-account.html
 *
 * Features:
 * - Multi-provider support (AWS, GCP, Azure, Jenkins)
 * - AWS CloudFormation cross-account role setup
 * - Azure Service Principal setup with PowerShell scripts
 * - Jenkins server connection
 * - GCP redirect to dedicated setup page
 * - Professional, modern UI with glass morphism design
 *
 * What changed:
 * - Migrated from Alpine.js to React with TypeScript
 * - Split into reusable sub-components
 * - Used custom hook for state management
 * - Added proper type safety
 * - Used React Router for navigation
 *
 * What stayed the same:
 * - All API endpoints remain identical
 * - Same business logic preserved exactly
 * - Same validation and error handling
 * - Same CloudFormation stack launch flow
 * - Same visual design with enhancements
 */

import React, { useEffect, useMemo, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAddAccount } from '../hooks/useAddAccount';
import { useTopbarActions } from '../contexts/TopbarContext';
import {
  ProviderSelector,
  ConnectionSteps,
  AWSForm,
  GCPForm,
  AzureForm,
  JenkinsForm,
  PolicyModal,
} from '../components/AddAccount';

// Import styles
import '../styles/addAccount.css';

const AddAccountPage: React.FC = () => {
  const navigate = useNavigate();
  const { setActions, clearActions } = useTopbarActions();

  const {
    // State
    provider,
    accountName,
    accessType,
    isLoading,
    isVerifying,
    isPolicyModalOpen,
    verificationError,
    verificationDetails,
    azureDetails,
    jenkinsDetails,

    // Setters
    setProvider,
    setAccountName,
    setAccessType,
    setIsPolicyModalOpen,
    setVerificationDetails,
    setAzureDetails,
    setJenkinsDetails,

    // Actions
    launchStack,
    verifyAccount,
    addAzureAccount,
    addJenkinsAccount,
    copyToClipboard,
  } = useAddAccount();

  const providerInfo = useMemo(() => {
    switch (provider) {
      case 'aws':
        return { name: 'AWS', icon: 'fab fa-aws', colorClass: 'aws' };
      case 'gcp':
        return { name: 'GCP', icon: 'fab fa-google', colorClass: 'gcp' };
      case 'azure':
        return { name: 'Azure', icon: 'fab fa-microsoft', colorClass: 'azure' };
      case 'jenkins':
        return { name: 'Jenkins', icon: 'fab fa-jenkins', colorClass: 'jenkins' };
      default:
        return { name: 'Cloud', icon: 'fas fa-cloud', colorClass: 'default' };
    }
  }, [provider]);

  // Memoize the navigate handler to prevent re-renders
  const handleBackToAccounts = useCallback(() => {
    navigate('/account-manager');
  }, [navigate]);

  // Set topbar actions
  useEffect(() => {
    setActions(
      <div className="flex items-center gap-3">
        <button
          onClick={handleBackToAccounts}
          className="flex items-center gap-2 px-4 py-2 text-sm font-medium text-slate-600 bg-slate-100 hover:bg-slate-200 rounded-xl transition-all"
        >
          <i className="fas fa-arrow-left"></i>
          <span>Back to Accounts</span>
        </button>
        {provider && (
          <div className={`flex items-center gap-2 px-4 py-2 rounded-xl text-sm font-medium aa-provider-badge-topbar ${providerInfo.colorClass}`}>
            <i className={providerInfo.icon}></i>
            <span>{providerInfo.name}</span>
          </div>
        )}
      </div>
    );

    return () => clearActions();
  }, [provider, providerInfo, setActions, clearActions, handleBackToAccounts]);

  const renderProviderForm = () => {
    switch (provider) {
      case 'aws':
        return (
          <AWSForm
            accountName={accountName}
            accessType={accessType}
            isLoading={isLoading}
            isVerifying={isVerifying}
            verificationDetails={verificationDetails}
            verificationError={verificationError}
            onAccountNameChange={setAccountName}
            onAccessTypeChange={setAccessType}
            onVerificationDetailsChange={setVerificationDetails}
            onLaunchStack={launchStack}
            onVerifyAccount={verifyAccount}
            onOpenPolicyModal={() => setIsPolicyModalOpen(true)}
          />
        );
      case 'gcp':
        return <GCPForm />;
      case 'azure':
        return (
          <AzureForm
            azureDetails={azureDetails}
            isLoading={isLoading}
            onAzureDetailsChange={setAzureDetails}
            onSubmit={addAzureAccount}
            onCopyToClipboard={copyToClipboard}
          />
        );
      case 'jenkins':
        return (
          <JenkinsForm
            jenkinsDetails={jenkinsDetails}
            isLoading={isLoading}
            onJenkinsDetailsChange={setJenkinsDetails}
            onSubmit={addJenkinsAccount}
          />
        );
      default:
        return null;
    }
  };

  return (
    <main className="add-account-page fade-in">
      {/* Content */}
      <div className="aa-content">
        <div className="aa-layout">
          {/* Left Column - Connection Steps */}
          <aside className="aa-sidebar">
            <ConnectionSteps provider={provider} />
          </aside>

          {/* Right Column - Forms */}
          <div className="aa-main">
            {/* Provider Selector */}
            <ProviderSelector
              selectedProvider={provider}
              onSelectProvider={setProvider}
            />

            {/* Provider-specific Form */}
            {renderProviderForm()}
          </div>
        </div>
      </div>

      {/* Policy Modal */}
      <PolicyModal
        isOpen={isPolicyModalOpen}
        onClose={() => setIsPolicyModalOpen(false)}
      />
    </main>
  );
};

export default AddAccountPage;
