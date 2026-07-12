import React, { useMemo } from 'react';
import { useParams } from 'react-router-dom';
import { useAccounts } from '../contexts/AccountContext';
import { useXamSecureData } from '../hooks/useXamSecureData';
import {
  XamSecureHeader, IssuesTab, AttackSurfaceTab, OverviewTab, ThreatsTab, VulnerabilitiesTab,
  PostureTab, InventoryTab, ApiSecurityTab, ComplianceTab, OnboardingTab,
} from '../components/XamSecure';

export type XamSecureTab =
  | 'issues' | 'overview' | 'attack-surface' | 'threats' | 'vulnerabilities' | 'posture'
  | 'inventory' | 'api-security' | 'compliance' | 'onboarding';

export const XAMSECURE_TABS: { id: XamSecureTab; label: string }[] = [
  { id: 'overview', label: 'Overview' },
  { id: 'issues', label: 'Issues' },
  { id: 'inventory', label: 'Inventory' },
  { id: 'attack-surface', label: 'Attack Surface' },
  { id: 'vulnerabilities', label: 'Vulnerabilities' },
  { id: 'posture', label: 'Posture' },
  { id: 'api-security', label: 'API Security' },
  { id: 'threats', label: 'Threats' },
  { id: 'compliance', label: 'Compliance' },
  { id: 'onboarding', label: 'Onboarding' },
];

const VALID_TABS = new Set<string>(XAMSECURE_TABS.map((t) => t.id));

const XamSecurePage: React.FC = () => {
  const { selectedAccountIds } = useAccounts();
  const selectedAccountId = selectedAccountIds.length > 0 ? selectedAccountIds[0] : null;
  const { tab } = useParams<{ tab?: string }>();

  const activeTab = useMemo<XamSecureTab>(
    () => (tab && VALID_TABS.has(tab) ? (tab as XamSecureTab) : 'issues'),
    [tab]
  );

  const {
    summary, issues, attackSurface, threats, vulnDashboard, vulnByCve, posture, inventory,
    inventoryCategories, networkTopology, compliance, onboarding, apiSecurity, stories,
    isLoading, refresh, loadIssueDetail, loadThreats, loadVulnByCve, loadCveFindings, loadPosture, loadInventory,
    archiveThreat,
  } = useXamSecureData({ selectedAccountId, activeTab });

  return (
    <main className="flex-1 flex flex-col min-h-screen bg-gray-50">
      <XamSecureHeader isLoading={isLoading} onRefresh={refresh} />
      <div className="p-6 space-y-6">
        {activeTab === 'issues' && (
          <IssuesTab data={issues} isLoading={isLoading} loadDetail={loadIssueDetail} />
        )}
        {activeTab === 'attack-surface' && (
          <AttackSurfaceTab data={attackSurface} isLoading={isLoading} loadDetail={loadIssueDetail} />
        )}
        {activeTab === 'overview' && (
          <OverviewTab summary={summary} issues={issues} threats={threats}
                       stories={stories} isLoading={isLoading} />
        )}
        {activeTab === 'threats' && (
          <ThreatsTab threats={threats} isLoading={isLoading} onPage={(p) => loadThreats(p)} onArchive={archiveThreat} />
        )}
        {activeTab === 'vulnerabilities' && (
          <VulnerabilitiesTab dashboard={vulnDashboard} byCve={vulnByCve} isLoading={isLoading}
                              onCvePage={(p) => loadVulnByCve(p)} loadCveFindings={loadCveFindings} />
        )}
        {activeTab === 'posture' && (
          <PostureTab data={posture} isLoading={isLoading} onPage={(p) => loadPosture(p)} />
        )}
        {activeTab === 'inventory' && (
          <InventoryTab data={inventory} categories={inventoryCategories} network={networkTopology}
                        isLoading={isLoading} onLoad={(page, category) => loadInventory(page, category)} />
        )}
        {activeTab === 'api-security' && <ApiSecurityTab data={apiSecurity} isLoading={isLoading} />}
        {activeTab === 'compliance' && <ComplianceTab data={compliance} isLoading={isLoading} />}
        {activeTab === 'onboarding' && <OnboardingTab accounts={onboarding} isLoading={isLoading} />}
      </div>
    </main>
  );
};

export default XamSecurePage;
