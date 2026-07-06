import React from 'react';
import { useAccounts } from '../contexts/AccountContext';
import { useTabState } from '../hooks/useTabState';
import { AriaTabGroup } from '../components/ui';
import { useXamSecureData } from '../hooks/useXamSecureData';
import {
  XamSecureHeader, OverviewTab, ThreatsTab, VulnerabilitiesTab,
  PostureTab, InventoryTab, ApiSecurityTab, ComplianceTab, OnboardingTab,
} from '../components/XamSecure';

type ActiveTab = 'overview' | 'threats' | 'vulnerabilities' | 'posture' | 'inventory' | 'api-security' | 'compliance' | 'onboarding';

const XamSecurePage: React.FC = () => {
  const { selectedAccountIds } = useAccounts();
  const selectedAccountId = selectedAccountIds.length > 0 ? selectedAccountIds[0] : null;
  const [activeTab, setActiveTab] = useTabState<ActiveTab>('overview');

  const {
    summary, threats, vulnerabilities, posture, inventory, compliance, onboarding, apiSecurity,
    isLoading, refresh, loadThreats, loadVulnerabilities, loadPosture, loadInventory,
  } = useXamSecureData({ selectedAccountId, activeTab });

  return (
    <main className="flex-1 flex flex-col min-h-screen bg-gray-50">
      <XamSecureHeader isLoading={isLoading} onRefresh={refresh} />
      <div className="p-6 space-y-6">
        <AriaTabGroup
          variant="pill"
          tabs={[
            { id: 'overview', label: 'Overview' },
            { id: 'threats', label: 'Threats' },
            { id: 'vulnerabilities', label: 'Vulnerabilities' },
            { id: 'posture', label: 'Posture' },
            { id: 'inventory', label: 'Inventory' },
            { id: 'api-security', label: 'API Security' },
            { id: 'compliance', label: 'Compliance' },
            { id: 'onboarding', label: 'Onboarding' },
          ]}
          selectedKey={activeTab}
          onSelectionChange={(key) => setActiveTab(key as ActiveTab)}
        />
        {activeTab === 'overview' && <OverviewTab summary={summary} isLoading={isLoading} />}
        {activeTab === 'threats' && (
          <ThreatsTab threats={threats} isLoading={isLoading} onPage={(p) => loadThreats(p)} />
        )}
        {activeTab === 'vulnerabilities' && (
          <VulnerabilitiesTab data={vulnerabilities} isLoading={isLoading}
                              onPage={(p) => loadVulnerabilities(p)} />
        )}
        {activeTab === 'posture' && (
          <PostureTab data={posture} isLoading={isLoading} onPage={(p) => loadPosture(p)} />
        )}
        {activeTab === 'inventory' && (
          <InventoryTab data={inventory} isLoading={isLoading} onPage={(p) => loadInventory(p)} />
        )}
        {activeTab === 'api-security' && <ApiSecurityTab data={apiSecurity} isLoading={isLoading} />}
        {activeTab === 'compliance' && <ComplianceTab data={compliance} isLoading={isLoading} />}
        {activeTab === 'onboarding' && <OnboardingTab accounts={onboarding} isLoading={isLoading} />}
      </div>
    </main>
  );
};

export default XamSecurePage;
