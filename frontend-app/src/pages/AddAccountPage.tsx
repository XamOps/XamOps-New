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
import { AnimatePresence, motion } from 'framer-motion';
import { Icon } from '@iconify/react';
import { useAddAccount } from '../hooks/useAddAccount';
import { useTopbarActions } from '../contexts/TopbarContext';
import {
  ProviderSelector,
  ConnectionSteps,
  AWSForm,
  GCPForm,
  AzureForm,
  JenkinsForm,
  GitHubForm,
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
    isAutoConnecting,
    autoConnected,
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
        return { name: 'AWS', icon: 'mdi:aws', colorClass: 'aws' };
      case 'gcp':
        return { name: 'GCP', icon: 'mdi:google', colorClass: 'gcp' };
      case 'azure':
        return { name: 'Azure', icon: 'mdi:microsoft', colorClass: 'azure' };
      case 'jenkins':
        return { name: 'Jenkins', icon: 'mdi:jenkins', colorClass: 'jenkins' };
      case 'github':
        return { name: 'GitHub', icon: 'mdi:github', colorClass: 'github' };
      default:
        return { name: 'Cloud', icon: 'mdi:cloud-outline', colorClass: 'default' };
    }
  }, [provider]);

  // Memoize the navigate handler to prevent re-renders
  const handleBackToAccounts = useCallback(() => {
    navigate('/account-manager');
  }, [navigate]);

  // Lock outer scroll and propagate a defined height through every ancestor
  // wrapper so height:100% resolves correctly down to .aa-form-scroll.
  useEffect(() => {
    const shell = document.getElementById('main-content');
    if (!shell) return;

    // Collect every element between #main-content and this page's root
    // that needs height:100% + overflow:hidden to form a proper chain.
    const wrappers: HTMLElement[] = [];

    // Walk down: shell's children after <Topbar> (first child)
    let el: HTMLElement | null = shell.querySelector(':scope > *:nth-child(2)');
    while (el && !el.classList.contains('aa-page-shell')) {
      wrappers.push(el);
      el = el.firstElementChild as HTMLElement | null;
    }

    // Save originals and apply
    const originals = wrappers.map((w) => ({
      display: w.style.display,
      flexDirection: w.style.flexDirection,
      height: w.style.height,
      overflow: w.style.overflow,
      paddingTop: w.style.paddingTop,
    }));

    wrappers.forEach((w) => {
      w.style.display = 'flex';
      w.style.flexDirection = 'column';
      w.style.height = '100%';
      w.style.overflow = 'hidden';
      w.style.paddingTop = '0';
    });

    const prevShellOverflow = shell.style.overflowY;
    shell.style.overflowY = 'hidden';

    return () => {
      shell.style.overflowY = prevShellOverflow;
      wrappers.forEach((w, i) => {
        w.style.display = originals[i].display;
        w.style.flexDirection = originals[i].flexDirection;
        w.style.height = originals[i].height;
        w.style.overflow = originals[i].overflow;
        w.style.paddingTop = originals[i].paddingTop;
      });
    };
  }, []);

  // Set topbar actions
  useEffect(() => {
    setActions(
      <div className="flex items-center gap-3">
        <button
          onClick={handleBackToAccounts}
          className="flex items-center gap-2 px-4 py-2 text-xs font-medium text-[rgba(0,0,0,0.6)] bg-[rgba(0,0,0,0.04)] border border-[rgba(0,0,0,0.08)] hover:bg-[rgba(0,0,0,0.07)] rounded-md transition-colors"
        >
          <Icon icon="mdi:arrow-left" width={14} />
          Back to Accounts
        </button>
        {provider && (
          <div className={`flex items-center gap-2 px-3 py-1.5 rounded-md text-xs font-medium aa-provider-badge-topbar ${providerInfo.colorClass}`}>
            <Icon icon={providerInfo.icon} width={14} />
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
            isAutoConnecting={isAutoConnecting}
            autoConnected={autoConnected}
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
      case 'github':
        return <GitHubForm />;
      default:
        return null;
    }
  };

  const currentStep = useMemo(() => {
    if (autoConnected) return 3;
    if (isVerifying) return 3;
    if (isLoading) return 2;
    if (provider) return 1;
    return 0;
  }, [autoConnected, isVerifying, isLoading, provider]);

  const mainVariants = {
    hidden: {},
    visible: { transition: { staggerChildren: 0.12, delayChildren: 0.1 } },
  };

  const itemVariants = {
    hidden: { opacity: 0, y: 20 },
    visible: { opacity: 1, y: 0, transition: { duration: 0.4, ease: 'easeOut' as const } },
  };

  return (
    <main className="aa-page-shell">
      {/* ── Row 1: Provider Selector — sticky, never scrolls ── */}
      <motion.div
        className="aa-sticky-top"
        variants={mainVariants}
        initial="hidden"
        animate="visible"
      >
        <motion.div variants={itemVariants} className="aa-provider-row">
          <ProviderSelector
            selectedProvider={provider}
            onSelectProvider={setProvider}
          />
        </motion.div>
      </motion.div>

      {/* ── Row 2: Sidebar (sticky) + Form (scrollable) ── */}
      <div className="aa-body-row">
        {/* Sidebar sticks alongside the form area */}
        <motion.aside
          className="aa-sidebar-sticky"
          initial={{ opacity: 0, x: -20 }}
          animate={{ opacity: 1, x: 0 }}
          transition={{ duration: 0.4, delay: 0.15 }}
        >
          <ConnectionSteps provider={provider} currentStep={currentStep} />
        </motion.aside>

        {/* Form: the ONLY thing that scrolls */}
        <div className="aa-form-scroll">
          <AnimatePresence mode="wait">
            <motion.div
              key={provider || 'empty'}
              initial={{ opacity: 0, x: 40 }}
              animate={{ opacity: 1, x: 0 }}
              exit={{ opacity: 0, x: -40 }}
              transition={{ duration: 0.25, ease: 'easeInOut' }}
            >
              {renderProviderForm()}
            </motion.div>
          </AnimatePresence>
        </div>
      </div>

      {/* Policy Modal */}
      <PolicyModal
        isOpen={isPolicyModalOpen}
        onClose={() => setIsPolicyModalOpen(false)}
        accessType={accessType}
      />
    </main>
  );
};

export default AddAccountPage;
