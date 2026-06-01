import React, { useEffect } from 'react';
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
} from '../components/Security';

const SEVERITY_LEVELS: SeverityLevel[] = ['Critical', 'High', 'Medium', 'Low'];

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

  useEffect(() => {
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
  }, [setActions, clearActions, isRefreshing, isExporting, prowlerStatus.status, exportData, loadFindings]);

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

        {selectedAccountId && (
          <SecurityKPICards
            totalFindings={filteredFindings.length}
            severityCounts={severityCounts}
            isLoading={isLoading}
          />
        )}

        {selectedAccountId && !isLoading && filteredFindings.length > 0 && (
          <SecurityComplianceFilter
            selectedCompliance={selectedCompliance}
            complianceFrameworks={complianceFrameworks}
            onComplianceChange={setSelectedCompliance}
          />
        )}

        {selectedAccountId && isLoading && (
          <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-6 animate-pulse">
            <div className="h-4 bg-[rgba(0,0,0,0.06)] rounded w-40 mb-4" />
            <div className="space-y-3">
              {[...Array(5)].map((_, i) => <div key={i} className="h-10 bg-[rgba(0,0,0,0.04)] rounded" />)}
            </div>
          </div>
        )}

        {selectedAccountId && !isLoading && filteredFindings.length === 0 && (
          <SecurityEmptyState onRefresh={() => loadFindings(true)} />
        )}

        {selectedAccountId && !isLoading && filteredFindings.length > 0 && (
          <div className="space-y-4">
            {SEVERITY_LEVELS.map((severity) => (
              <SecurityFindingsSection
                key={severity}
                severity={severity}
                findings={getFindingsBySeverity(severity)}
                isOpen={openSeverities.includes(severity)}
                onToggle={() => toggleSeverity(severity)}
              />
            ))}
          </div>
        )}
      </div>
    </main>
  );
};

export default SecurityPage;
