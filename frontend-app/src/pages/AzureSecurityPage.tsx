import React, { useState } from 'react';
import { useAccounts } from '../contexts/AccountContext';
import { useAzureSecurityData } from '../hooks/useAzureSecurityData';
import { AriaTabGroup } from '../components/ui';
import {
  AzureSecurityHeader,
  AzureSecurityKPICards,
  AzureSecurityAssessmentsSection,
  AzureSecurityAlertsTable,
  AzureSecurityStatusBanner,
} from '../components/AzureSecurity';

type ActiveTab = 'assessments' | 'alerts';

const AzureSecurityPage: React.FC = () => {
  const { selectedAccountIds } = useAccounts();
  const selectedAccountId = selectedAccountIds.length > 0 ? selectedAccountIds[0] : null;

  const [activeTab, setActiveTab] = useState<ActiveTab>('assessments');

  const { securityData, isLoading, isRefreshing, loadSecurityData } =
    useAzureSecurityData({ selectedAccountId });

  const handleRefresh = () => {
    loadSecurityData(true);
  };

  const assessments = securityData?.assessments ?? [];
  const alerts = securityData?.alerts ?? [];

  return (
    <main className="p-6 space-y-6">
      <AzureSecurityHeader
        selectedAccountId={selectedAccountId}
        isRefreshing={isRefreshing}
        onRefresh={handleRefresh}
      />

      {!selectedAccountId && !isLoading && (
        <div className="glass-card rounded-xl p-16 text-center">
          <div className="flex flex-col items-center">
            <div className="w-16 h-16 bg-red-100 rounded-full flex items-center justify-center mb-4">
              <i className="fas fa-shield-alt text-red-500 text-2xl"></i>
            </div>
            <h3 className="text-lg font-bold text-gray-700 mb-1">No Account Selected</h3>
            <p className="text-sm text-gray-500 max-w-md">
              Please select an Azure account from the sidebar to view security findings.
            </p>
          </div>
        </div>
      )}

      {selectedAccountId && (
        <>
          {/* Status banner (PERMISSION_ERROR / API_ERROR / NO_DATA) */}
          <AzureSecurityStatusBanner status={securityData?.status ?? null} />

          {/* KPI cards */}
          <AzureSecurityKPICards securityData={securityData} isLoading={isLoading} />

          {/* Tab navigation */}
          <AriaTabGroup
            variant="pill"
            tabs={[
              {
                id: 'assessments',
                label: (
                  <>
                    <i className="fas fa-clipboard-list mr-1" />
                    Assessments
                    {!isLoading && assessments.length > 0 && (
                      <span className="ml-2 text-xs px-1.5 py-0.5 rounded-full bg-gray-200 text-gray-600">
                        {assessments.length}
                      </span>
                    )}
                  </>
                ),
              },
              {
                id: 'alerts',
                label: (
                  <>
                    <i className="fas fa-bell mr-1" />
                    Alerts
                    {!isLoading && alerts.length > 0 && (
                      <span className="ml-2 text-xs px-1.5 py-0.5 rounded-full bg-gray-200 text-gray-600">
                        {alerts.length}
                      </span>
                    )}
                  </>
                ),
              },
            ]}
            selectedKey={activeTab}
            onSelectionChange={(key) => setActiveTab(key as ActiveTab)}
          />

          {/* Tab content */}
          {activeTab === 'assessments' && (
            <AzureSecurityAssessmentsSection assessments={assessments} isLoading={isLoading} />
          )}
          {activeTab === 'alerts' && (
            <AzureSecurityAlertsTable alerts={alerts} isLoading={isLoading} />
          )}
        </>
      )}
    </main>
  );
};

export default AzureSecurityPage;
