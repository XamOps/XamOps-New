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

import React, { useEffect, useMemo, useCallback, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import { AnimatePresence, motion } from 'framer-motion';
import { Icon } from '@iconify/react';
import { useAddAccount } from '../hooks/useAddAccount';
import { useTopbarActions } from '../contexts/TopbarContext';
import {
  ConnectionSteps,
  AWSForm,
  GCPForm,
  AzureForm,
  JenkinsForm,
  GitHubForm,
  PolicyModal,
  IntegrationsSection,
  DataSourcesGrid,
  XamSecureSection,
} from '../components/AddAccount';
import type { Provider } from '../types/addAccount';

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
    autoConnectStatus,
    autoConnected,
    isPolicyModalOpen,
    verificationError,
    verificationDetails,
    curSource,
    setCurSource,
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

  const [showFormView, setShowFormView] = React.useState(false);
  const shellRef = useRef<HTMLElement>(null);

  const providerInfo = useMemo(() => {
    switch (provider) {
      case 'aws':
        return { name: 'AWS', icon: 'mdi:aws', colorClass: 'aws' };
      case 'gcp':
        return { name: 'GCP', icon: 'mdi:google', colorClass: 'gcp' };
      case 'azure':
        return { name: 'Azure', icon: 'mdi:microsoft', colorClass: 'azure' };
      case 'jenkins':
        return { name: 'Jenkins', icon: 'simple-icons:jenkins', colorClass: 'jenkins' };
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

  // Handle provider selection from grid → switch to form view
  const handleGridProviderSelect = useCallback((p: Provider) => {
    setProvider(p);
    setShowFormView(true);
  }, [setProvider]);

  // Back to grid view
  const handleBackToGrid = useCallback(() => {
    setShowFormView(false);
  }, []);

  // Lock outer scroll and propagate a defined height through every ancestor
  // wrapper so height:100% resolves correctly down to .aa-form-scroll.
  useEffect(() => {
    const shell = document.getElementById('main-content');
    const pageShell = shellRef.current;
    if (!shell || !pageShell) return;

    // Collect every wrapper element strictly between this page's root
    // (.aa-page-shell) and #main-content that needs height:100% + overflow:hidden
    // to form a proper height chain.
    //
    // Walk UP from the page shell rather than guessing by child index: siblings
    // like <TrialBanner> can be inserted between <Topbar> and the content wrapper,
    // which previously caused these styles to be applied to the banner (inflating
    // it to full height) instead of the real content wrappers.
    const wrappers: HTMLElement[] = [];
    let el: HTMLElement | null = pageShell.parentElement;
    while (el && el !== shell) {
      wrappers.push(el);
      el = el.parentElement;
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
        {showFormView ? (
          <button
            onClick={handleBackToGrid}
            className="flex items-center gap-2 px-4 py-2 text-xs font-medium text-[rgba(0,0,0,0.6)] bg-[rgba(0,0,0,0.04)] border border-[rgba(0,0,0,0.08)] hover:bg-[rgba(0,0,0,0.07)] rounded-md transition-colors"
          >
            <Icon icon="mdi:arrow-left" width={14} />
            Back to Integrations
          </button>
        ) : (
          <button
            onClick={handleBackToAccounts}
            className="flex items-center gap-2 px-4 py-2 text-xs font-medium text-[rgba(0,0,0,0.6)] bg-[rgba(0,0,0,0.04)] border border-[rgba(0,0,0,0.08)] hover:bg-[rgba(0,0,0,0.07)] rounded-md transition-colors"
          >
            <Icon icon="mdi:arrow-left" width={14} />
            Back to Accounts
          </button>
        )}
        {showFormView && provider && (
          <div className={`flex items-center gap-2 px-3 py-1.5 rounded-md text-xs font-medium aa-provider-badge-topbar ${providerInfo.colorClass}`}>
            <Icon icon={providerInfo.icon} width={14} />
            <span>{providerInfo.name}</span>
          </div>
        )}
      </div>
    );

    return () => clearActions();
  }, [provider, providerInfo, showFormView, setActions, clearActions, handleBackToAccounts, handleBackToGrid]);

  const renderProviderForm = () => {
    switch (provider) {
      case 'aws':
        return (
          <AWSForm
            accountName={accountName}
            accessType={accessType}
            isLoading={isLoading}
            isVerifying={isVerifying}
            autoConnectStatus={autoConnectStatus}
            autoConnected={autoConnected}
            verificationDetails={verificationDetails}
            verificationError={verificationError}
            onAccountNameChange={setAccountName}
            onAccessTypeChange={setAccessType}
            onVerificationDetailsChange={setVerificationDetails}
            onLaunchStack={launchStack}
            onVerifyAccount={verifyAccount}
            onOpenPolicyModal={() => setIsPolicyModalOpen(true)}
            curSource={curSource}
            onCurSourceChange={setCurSource}
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

  return (
    <main ref={shellRef} className="aa-page-shell">
      {showFormView ? (
        <>
          {/* ── Form View: centered, no sidebar ── */}
          <div className="flex-1 overflow-y-auto scrollbar-hide">
            <div className="max-w-3xl mx-auto px-8 py-8">
              {/* Horizontal stepper */}
              <ConnectionSteps provider={provider} currentStep={currentStep} />

              {/* Form */}
              <div className="mt-8">
                <AnimatePresence mode="wait">
                  <motion.div
                    key={provider || 'empty'}
                    initial={{ opacity: 0, y: 12 }}
                    animate={{ opacity: 1, y: 0 }}
                    exit={{ opacity: 0, y: -12 }}
                    transition={{ duration: 0.2, ease: 'easeInOut' }}
                  >
                    {renderProviderForm()}
                  </motion.div>
                </AnimatePresence>
              </div>
            </div>
          </div>
        </>
      ) : (
        /* ── Grid View: Vantage-style integrations page ── */
        <div className="flex-1 overflow-y-auto p-8 space-y-10">
          {/* Data Sources */}
          <DataSourcesGrid onSelectProvider={handleGridProviderSelect} />

          {/* Security Monitoring — enables XamSecure on an already-connected account, so it
              sits after Data Sources: there is nothing to protect until an account exists. */}
          <XamSecureSection />

          {/* Apps (Slack, Google Chat, Teams) */}
          <IntegrationsSection />
        </div>
      )}

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
