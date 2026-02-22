import React from 'react';
import { useAccounts } from '../contexts/AccountContext';
import { useAzureAlertsData } from '../hooks/useAzureAlertsData';
import {
  AzureAlertsHeader,
  AzureAlertsKPICards,
  AzureAlertsTable,
  AzureAlertsStatusBanner,
} from '../components/AzureAlerts';

const AzureAlertsPage: React.FC = () => {
  const { selectedAccountIds } = useAccounts();
  const selectedAccountId = selectedAccountIds.length > 0 ? selectedAccountIds[0] : null;

  const { alertsData, isLoading, isRefreshing, loadAlertsData } =
    useAzureAlertsData({ selectedAccountId });

  const handleRefresh = () => {
    loadAlertsData(true);
  };

  const alerts = alertsData?.alerts ?? [];

  return (
    <main className="p-6 space-y-6">
      <AzureAlertsHeader
        selectedAccountId={selectedAccountId}
        isRefreshing={isRefreshing}
        onRefresh={handleRefresh}
      />

      {!selectedAccountId && !isLoading && (
        <div className="glass-card rounded-xl p-16 text-center">
          <div className="flex flex-col items-center">
            <div className="w-16 h-16 bg-orange-100 rounded-full flex items-center justify-center mb-4">
              <i className="fas fa-bell text-orange-500 text-2xl"></i>
            </div>
            <h3 className="text-lg font-bold text-gray-700 mb-1">No Account Selected</h3>
            <p className="text-sm text-gray-500 max-w-md">
              Please select an Azure account from the sidebar to view monitor alerts.
            </p>
          </div>
        </div>
      )}

      {selectedAccountId && (
        <>
          {/* Status banner (PERMISSION_ERROR / API_ERROR / NO_DATA) */}
          <AzureAlertsStatusBanner status={alertsData?.status ?? null} />

          {/* KPI cards */}
          <AzureAlertsKPICards alertsData={alertsData} isLoading={isLoading} />

          {/* Alerts table */}
          <AzureAlertsTable alerts={alerts} isLoading={isLoading} />
        </>
      )}
    </main>
  );
};

export default AzureAlertsPage;
