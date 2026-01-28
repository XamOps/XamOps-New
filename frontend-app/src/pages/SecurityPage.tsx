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

import React from 'react';
import { useAccounts } from '../contexts/AccountContext';
import { useSecurityData } from '../hooks/useSecurityData';
import type { SeverityLevel } from '../types/security';

// Component imports
import {
  SecurityHeader,
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

  return (
    <main className="security-page">
      <SecurityHeader
        prowlerStatus={prowlerStatus}
        isRefreshing={isRefreshing}
        isExporting={isExporting}
        onExport={exportData}
        onRefresh={() => loadFindings(true)}
      />

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
