import React from 'react';
import { Icon } from '@iconify/react';
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
    <main className="flex-1 px-6 pt-4 pb-6 bg-white min-h-screen">
      <div className="space-y-5">
        <AzureAlertsHeader
          selectedAccountId={selectedAccountId}
          isRefreshing={isRefreshing}
          onRefresh={handleRefresh}
        />

        {!selectedAccountId && !isLoading && (
          <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-16 text-center">
            <div className="flex flex-col items-center">
              <div className="w-14 h-14 bg-orange-50 border border-orange-100 rounded-xl flex items-center justify-center mb-4">
                <Icon icon="mdi:bell-outline" width={28} className="text-orange-500" />
              </div>
              <h3 className="text-[15px] font-semibold text-[rgba(0,0,0,0.88)] mb-1">No Account Selected</h3>
              <p className="text-[13px] text-[rgba(0,0,0,0.45)] max-w-md">
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
      </div>
    </main>
  );
};

export default AzureAlertsPage;
