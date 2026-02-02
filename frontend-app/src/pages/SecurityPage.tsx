/**
 * Security Page
 *
 * Converted from: security.html
 *
 * This is the main Security Center page with:
 * - Security findings by severity (Critical, High, Medium, Low)
 * - Compliance framework filtering
 * - Prowler deep scan status
 * - Export to CSV functionality
 * - Real-time polling for scan status
 *
 * API Endpoints:
 * - GET /api/xamops/security/findings?accountId={accountId}&forceRefresh={boolean}
 * - GET /api/xamops/security/prowler/status?accountId={accountId}
 */

import React, { useEffect } from 'react';
import { useAccounts } from '../contexts/AccountContext';
import { useTopbar } from '../contexts/TopbarContext';
import { useSecurityData } from '../hooks/useSecurityData';
import type { SeverityLevel } from '../types/security';
import clsx from 'clsx';

// Component imports
import {
  SecurityKPICards,
  SecurityComplianceFilter,
  SecurityFindingsSection,
  SecurityProwlerBanner,
  SecurityEmptyState,
} from '../components/Security';

// Styles
import '../styles/security.css';

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

  // Topbar Integration
  const { setActions, clearActions } = useTopbar();

  useEffect(() => {
    setActions(
      <div className="flex items-center gap-3">
        {prowlerStatus.status === 'RUNNING' && (
          <div className="flex items-center gap-2 px-3 py-1.5 bg-blue-50 text-blue-700 rounded-lg text-xs font-medium border border-blue-100 animate-pulse">
            <div className="w-2 h-2 bg-blue-500 rounded-full"></div>
            <span>Deep Scan Running...</span>
          </div>
        )}

        <button
          onClick={exportData}
          disabled={isExporting}
          className="flex items-center gap-2 px-4 py-2 bg-white text-emerald-600 text-xs font-bold rounded-xl border border-emerald-100 shadow-sm hover:shadow-md transition-all disabled:opacity-50"
        >
          <i className={clsx('fas', isExporting ? 'fa-spinner fa-spin' : 'fa-file-excel')}></i>
          <span>{isExporting ? 'Exporting...' : 'Export Report'}</span>
        </button>

        <button
          onClick={() => loadFindings(true)}
          disabled={isRefreshing}
          className="flex items-center gap-2 px-4 py-2 bg-indigo-600 text-white text-xs font-bold rounded-xl shadow-lg shadow-indigo-200 hover:bg-indigo-700 transition-all disabled:opacity-50 active:scale-95"
        >
          <i className={clsx('fas fa-sync-alt', isRefreshing && 'animate-spin')}></i>
          <span>{isRefreshing ? 'Scanning...' : 'Refresh Scan'}</span>
        </button>
      </div>
    );
    return () => clearActions();
  }, [setActions, clearActions, isRefreshing, isExporting, prowlerStatus.status, exportData, loadFindings]);

  return (
    <main className="security-page">
      <SecurityProwlerBanner prowlerStatus={prowlerStatus} />

      <div className="security-content">
        {/* No Account State */}
        {!selectedAccountId && !isLoading && (
          <div className="security-no-account">
            <div className="security-no-account-icon">
              <i className="fas fa-shield-alt"></i>
            </div>
            <h4>No Account Selected</h4>
            <p>Please select an account from the sidebar to view security findings.</p>
          </div>
        )}

        {/* KPI Cards */}
        {selectedAccountId && (
          <SecurityKPICards
            totalFindings={filteredFindings.length}
            severityCounts={severityCounts}
            isLoading={isLoading}
          />
        )}

        {/* Compliance Filter */}
        {selectedAccountId && !isLoading && filteredFindings.length > 0 && (
          <SecurityComplianceFilter
            selectedCompliance={selectedCompliance}
            complianceFrameworks={complianceFrameworks}
            onComplianceChange={setSelectedCompliance}
          />
        )}

        {/* Loading State */}
        {selectedAccountId && isLoading && (
          <div className="security-skeleton security-skeleton-table"></div>
        )}

        {/* Empty State */}
        {selectedAccountId && !isLoading && filteredFindings.length === 0 && (
          <SecurityEmptyState onRefresh={() => loadFindings(true)} />
        )}

        {/* Findings Sections */}
        {selectedAccountId && !isLoading && filteredFindings.length > 0 && (
          <div className="security-findings-container">
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
