import React, { useMemo } from 'react';
import { useParams } from 'react-router-dom';
import { useAccounts } from '../contexts/AccountContext';
import { useXamSecureData } from '../hooks/useXamSecureData';
import {
  XamSecureHeader, IssuesTab, AttackSurfaceTab, OverviewTab, ThreatsTab, VulnerabilitiesTab,
  ConfigurationsTab, InventoryTab, ApiSecurityTab, ComponentsTab, ThreatPoliciesTab, WorkflowsTab,
  OnboardingTab,
} from '../components/XamSecure';

export type XamSecureTab =
  | 'issues' | 'overview' | 'attack-surface' | 'threats' | 'vulnerabilities' | 'posture'
  | 'inventory' | 'api-security' | 'components' | 'threat-policies' | 'workflows' | 'compliance' | 'onboarding';

export const XAMSECURE_TABS: { id: XamSecureTab; label: string }[] = [
  { id: 'overview', label: 'Overview' },
  { id: 'issues', label: 'Issues' },
  { id: 'inventory', label: 'Inventory' },
  { id: 'attack-surface', label: 'Attack Surface' },
  { id: 'vulnerabilities', label: 'Vulnerabilities' },
  { id: 'posture', label: 'Configurations' },
  { id: 'api-security', label: 'API Security' },
  { id: 'components', label: 'Components' },
  { id: 'threats', label: 'Threats' },
  { id: 'threat-policies', label: 'Threat Policies' },
  { id: 'workflows', label: 'Workflows' },
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
    inventoryCategories, networkTopology, compliance, onboarding, apiSecurity, components,
    threatPolicies, workflows, stories,
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
                       stories={stories} vulnDashboard={vulnDashboard} isLoading={isLoading} />
        )}
        {activeTab === 'threats' && (
          <ThreatsTab threats={threats} isLoading={isLoading} onPage={(p) => loadThreats(p)} onArchive={archiveThreat} />
        )}
        {activeTab === 'vulnerabilities' && (
          <VulnerabilitiesTab dashboard={vulnDashboard} byCve={vulnByCve} isLoading={isLoading}
                              onCvePage={(p) => loadVulnByCve(p)} loadCveFindings={loadCveFindings} />
        )}
        {activeTab === 'posture' && (
          <ConfigurationsTab posture={posture} compliance={compliance} isLoading={isLoading}
                             onPage={(p) => loadPosture(p)} />
        )}
        {activeTab === 'inventory' && (
          <InventoryTab data={inventory} categories={inventoryCategories} network={networkTopology}
                        isLoading={isLoading} onLoad={(page, category) => loadInventory(page, category)} />
        )}
        {activeTab === 'api-security' && <ApiSecurityTab data={apiSecurity} isLoading={isLoading} />}
        {activeTab === 'components' && <ComponentsTab data={components} isLoading={isLoading} />}
        {activeTab === 'threat-policies' && <ThreatPoliciesTab data={threatPolicies} isLoading={isLoading} />}
        {activeTab === 'workflows' && <WorkflowsTab data={workflows} isLoading={isLoading} />}
        {activeTab === 'onboarding' && <OnboardingTab accounts={onboarding} isLoading={isLoading} />}
      </div>
    </main>
  );
};

export default XamSecurePage;
