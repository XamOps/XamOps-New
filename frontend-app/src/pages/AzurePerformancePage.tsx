import React from 'react';
import { AnimatePresence, motion } from 'framer-motion';
import { Icon } from '@iconify/react';
import { useAccounts } from '../contexts/AccountContext';
import { useAzurePerformanceData } from '../hooks/useAzurePerformanceData';
import {
  AzurePerformanceHeader,
  AzurePerformanceKPICards,
  AzurePerformanceInsightsTable,
  AzurePerformanceStatusBanner,
} from '../components/AzurePerformance';

const AzurePerformancePage: React.FC = () => {
  const { selectedAccountIds } = useAccounts();
  const selectedAccountId = selectedAccountIds.length > 0 ? selectedAccountIds[0] : null;

  const { performanceData, isLoading, isRefreshing, loadPerformanceData } =
    useAzurePerformanceData({ selectedAccountId });

  const handleRefresh = () => {
    loadPerformanceData(true);
  };

  const insights = performanceData?.insights ?? [];

  return (
    <main className="flex-1 p-6 bg-white min-h-screen">
      <motion.div initial={{ opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }} transition={{ duration: 0.28, ease: [0.22, 1, 0.36, 1] }} className="space-y-6">
        <AzurePerformanceHeader
          selectedAccountId={selectedAccountId}
          isRefreshing={isRefreshing}
          onRefresh={handleRefresh}
        />

        <AnimatePresence mode="wait">
          {!selectedAccountId && !isLoading && (
            <motion.div key="no-account" initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -4 }} transition={{ duration: 0.18, ease: 'easeOut' }}>
              <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-16 text-center">
                <div className="flex flex-col items-center">
                  <div className="w-14 h-14 bg-indigo-50 border border-indigo-100 rounded-xl flex items-center justify-center mx-auto mb-4">
                    <Icon icon="mdi:chart-line" width={28} className="text-indigo-600" />
                  </div>
                  <h3 className="text-lg font-bold text-gray-700 mb-1">No Account Selected</h3>
                  <p className="text-sm text-gray-500 max-w-md">
                    Please select an Azure account from the sidebar to view performance insights.
                  </p>
                </div>
              </div>
            </motion.div>
          )}

          {selectedAccountId && (
            <motion.div key="content" initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -4 }} transition={{ duration: 0.18, ease: 'easeOut' }} className="space-y-6">
              {/* Status banner (PERMISSION_ERROR / API_ERROR / NO_DATA) */}
              <AzurePerformanceStatusBanner status={performanceData?.status ?? null} />

              {/* KPI cards */}
              <AzurePerformanceKPICards performanceData={performanceData} isLoading={isLoading} />

              {/* Insights table */}
              <AzurePerformanceInsightsTable insights={insights} isLoading={isLoading} />
            </motion.div>
          )}
        </AnimatePresence>
      </motion.div>
    </main>
  );
};

export default AzurePerformancePage;
