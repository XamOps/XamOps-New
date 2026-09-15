import React, { useEffect } from 'react';
import { AnimatePresence, motion } from 'framer-motion';
import { Icon } from '@iconify/react';
import { useAccounts } from '../contexts/AccountContext';
import { useTopbar } from '../contexts/TopbarContext';
import { useSecurityData } from '../hooks/useSecurityData';
import type { SeverityLevel } from '../types/security';
import {
  SecurityKPICards,
  SecurityComplianceFilter,
  SecurityFindingsSection,
  SecurityProwlerBanner,
  SecurityEmptyState,
  IdentityAccessTab,
} from '../components/Security';
import { AriaTabGroup } from '../components/ui';
import { useTabState } from '../hooks/useTabState';

const SEVERITY_LEVELS: SeverityLevel[] = ['Critical', 'High', 'Medium', 'Low'];

type ActiveTab = 'findings' | 'identity';

const SecurityPage: React.FC = () => {
  const { selectedAccountIds } = useAccounts();
  const selectedAccountId = selectedAccountIds.length > 0 ? selectedAccountIds[0] : null;

  const {
    filteredFindings,
    isLoading,
    isRefreshing,
    isExporting,
    prowlerStatus,
    selectedCompliance,
    complianceFrameworks,
    severityCounts,
    openSeverities,
    loadFindings,
    exportData,
    setSelectedCompliance,
    toggleSeverity,
    getFindingsBySeverity,
  } = useSecurityData({ selectedAccountId });

  const { setActions, clearActions } = useTopbar();
  const [activeTab, setActiveTab] = useTabState<ActiveTab>('findings');

  useEffect(() => {
    // Scoped to the findings tab on purpose. Both actions operate on the compliance scan —
    // "Export Report" writes findings to Excel and "Refresh Scan" re-runs Prowler — so on the
    // Identity tab they would claim to refresh data they do not touch. The Identity canvas
    // carries its own refresh control instead.
    if (activeTab !== 'findings') {
      clearActions();
      return;
    }

    setActions(
      <div className="flex items-center gap-3">
        {prowlerStatus.status === 'RUNNING' && (
          <div className="flex items-center gap-2 px-3 py-1.5 bg-blue-50 text-blue-700 rounded-lg text-xs font-medium border border-blue-100 animate-pulse">
            <div className="w-2 h-2 bg-blue-500 rounded-full" />
            <span>Deep Scan Running...</span>
          </div>
        )}
        <button
          onClick={exportData}
          disabled={isExporting}
          className="flex items-center gap-2 px-3 py-1.5 bg-white border border-[rgba(0,0,0,0.12)] text-[rgba(0,0,0,0.7)] text-xs font-medium rounded-md hover:bg-[rgba(0,0,0,0.02)] transition-colors disabled:opacity-50"
        >
          <Icon icon={isExporting ? 'mdi:loading' : 'mdi:microsoft-excel'} width={14} className={isExporting ? 'animate-spin' : ''} />
          <span>{isExporting ? 'Exporting...' : 'Export Report'}</span>
        </button>
        <button
          onClick={() => loadFindings(true)}
          disabled={isRefreshing}
          className="flex items-center gap-2 px-3 py-1.5 bg-[rgba(0,0,0,0.88)] text-white text-xs font-medium rounded-md hover:bg-black transition-colors disabled:opacity-50"
        >
          <Icon icon="mdi:refresh" width={14} className={isRefreshing ? 'animate-spin' : ''} />
          <span>{isRefreshing ? 'Scanning...' : 'Refresh Scan'}</span>
        </button>
      </div>
    );
    return () => clearActions();
  }, [setActions, clearActions, activeTab, isRefreshing, isExporting, prowlerStatus.status, exportData, loadFindings]);

  const tabs = [
    { id: 'findings', label: 'Findings', icon: 'mdi:shield-search' },
    { id: 'identity', label: 'Identity & Access', icon: 'mdi:account-key-outline' },
  ];

  return (
    <main className="flex-1 flex flex-col min-h-screen bg-white">
      <SecurityProwlerBanner prowlerStatus={prowlerStatus} />
      <div className="px-6 pt-4 pb-6 space-y-6">
        {!selectedAccountId && !isLoading && (
          <div className="text-center py-24 bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm">
            <div className="w-14 h-14 rounded-xl bg-indigo-50 border border-indigo-100 flex items-center justify-center mx-auto mb-4">
              <Icon icon="mdi:shield-half-full" width={28} color="#4f46e5" />
            </div>
            <h4 className="text-[15px] font-semibold text-[rgba(0,0,0,0.88)] mb-1">No Account Selected</h4>
            <p className="text-sm text-[rgba(0,0,0,0.5)]">Please select an account from the sidebar to view security findings.</p>
          </div>
        )}

        {/* Neutral variant: this page's colour budget is spent on severity, so an indigo
            accent here would read as a fifth status rather than as navigation. */}
        {selectedAccountId && (
          <AriaTabGroup
            variant="neutral"
            tabs={tabs}
            selectedKey={activeTab}
            onSelectionChange={(key) => setActiveTab(key as ActiveTab)}
          />
        )}

        {selectedAccountId && activeTab === 'identity' && (
          <motion.div
            key="identity"
            initial={{ opacity: 0, y: 8 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ duration: 0.18, ease: 'easeOut' }}
          >
            <IdentityAccessTab />
          </motion.div>
        )}

        {selectedAccountId && activeTab === 'findings' && (
          <SecurityKPICards
            totalFindings={filteredFindings.length}
            severityCounts={severityCounts}
            isLoading={isLoading}
          />
        )}

        {selectedAccountId && activeTab === 'findings' && !isLoading && filteredFindings.length > 0 && (
          <SecurityComplianceFilter
            selectedCompliance={selectedCompliance}
            complianceFrameworks={complianceFrameworks}
            onComplianceChange={setSelectedCompliance}
          />
        )}

        <AnimatePresence mode="wait">
          {selectedAccountId && activeTab === 'findings' && isLoading && (
            <motion.div key="loading" initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }} transition={{ duration: 0.15 }}>
              <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-6 animate-pulse">
                <div className="h-4 bg-[rgba(0,0,0,0.06)] rounded w-40 mb-4" />
                <div className="space-y-3">
                  {[...Array(5)].map((_, i) => <div key={i} className="h-10 bg-[rgba(0,0,0,0.04)] rounded" />)}
                </div>
              </div>
            </motion.div>
          )}

          {selectedAccountId && activeTab === 'findings' && !isLoading && filteredFindings.length === 0 && (
            <motion.div key="empty" initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -4 }} transition={{ duration: 0.18, ease: 'easeOut' }}>
              <SecurityEmptyState onRefresh={() => loadFindings(true)} />
            </motion.div>
          )}

          {selectedAccountId && activeTab === 'findings' && !isLoading && filteredFindings.length > 0 && (
            <motion.div key="findings" initial={{ opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -4 }} transition={{ duration: 0.28, ease: [0.22, 1, 0.36, 1] }}>
              <div className="space-y-4">
                {SEVERITY_LEVELS.map((severity, idx) => (
                  <div key={severity} style={{ animation: `fadeInUp 0.3s ease ${idx * 0.06}s both` }}>
                    <SecurityFindingsSection
                      severity={severity}
                      findings={getFindingsBySeverity(severity)}
                      isOpen={openSeverities.includes(severity)}
                      onToggle={() => toggleSeverity(severity)}
                    />
                  </div>
                ))}
              </div>
            </motion.div>
          )}
        </AnimatePresence>
      </div>
    </main>
  );
};

export default SecurityPage;
