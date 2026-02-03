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

import React from 'react';
import { useAddAccount } from '../hooks/useAddAccount';
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
      <div className="add-account-content container">
        <div className="split-layout">
          {/* Left Column - Connection Steps */}
          <div className="sidebar-column">
            <ConnectionSteps provider={provider} />
          </div>

          {/* Right Column - Forms */}
          <div className="content-column">
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
