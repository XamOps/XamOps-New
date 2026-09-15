import React from 'react';
import { AnimatePresence, motion } from 'framer-motion';
import { Icon } from '@iconify/react';
import { useAccounts } from '../contexts/AccountContext';
import { useAzureSecurityData } from '../hooks/useAzureSecurityData';
import { AriaTabGroup } from '../components/ui';
import { useTabState } from '../hooks/useTabState';
import {
  AzureSecurityHeader,
  AzureSecurityKPICards,
  AzureSecurityAssessmentsSection,
  AzureSecurityScoreControls,
  AzureSecurityAlertsTable,
  AzureSecurityStatusBanner,
} from '../components/AzureSecurity';
import { IdentityAccessTab } from '../components/Security';

type ActiveTab = 'assessments' | 'controls' | 'alerts' | 'identity';

const AzureSecurityPage: React.FC = () => {
  const { selectedAccountIds } = useAccounts();
  const selectedAccountId = selectedAccountIds.length > 0 ? selectedAccountIds[0] : null;

  const [activeTab, setActiveTab] = useTabState<ActiveTab>('assessments');

  const { securityData, isLoading, isRefreshing, loadSecurityData } =
    useAzureSecurityData({ selectedAccountId });

  const assessments = securityData?.assessments ?? [];
  const alerts = securityData?.alerts ?? [];
  const scoreControls = securityData?.scoreControls ?? [];

  return (
    <main className="flex-1 flex flex-col min-h-screen bg-gray-50">
      <AzureSecurityHeader
        selectedAccountId={selectedAccountId}
        isRefreshing={isRefreshing}
        onRefresh={() => loadSecurityData(true)}
      />
      <div className="p-6 space-y-6">
        {!selectedAccountId && !isLoading && (
          <div className="text-center py-24 bg-white border border-gray-200 rounded-xl shadow-sm">
            <div className="w-16 h-16 rounded-xl bg-red-50 border border-red-100 flex items-center justify-center mx-auto mb-4">
              <Icon icon="mdi:shield-half-full" width={32} color="#ef4444" />
            </div>
            <h3 className="text-lg font-bold text-gray-800 mb-1">No Account Selected</h3>
            <p className="text-sm text-gray-500 max-w-md mx-auto">
              Please select an Azure account from the sidebar to view security findings.
            </p>
          </div>
        )}

        {selectedAccountId && (
          <>
            <AzureSecurityStatusBanner status={securityData?.status ?? null} />
            <AzureSecurityKPICards securityData={securityData} isLoading={isLoading} />

            <AriaTabGroup
              variant="pill"
              tabs={[
                {
                  id: 'assessments',
                  label: (
                    <>
                      <Icon icon="mdi:clipboard-list-outline" width={13} className="mr-1 inline" />
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
                  id: 'controls',
                  label: (
                    <>
                      <Icon icon="mdi:gauge" width={13} className="mr-1 inline" />
                      Score Controls
                      {!isLoading && scoreControls.length > 0 && (
                        <span className="ml-2 text-xs px-1.5 py-0.5 rounded-full bg-gray-200 text-gray-600">
                          {scoreControls.length}
                        </span>
                      )}
                    </>
                  ),
                },
                {
                  id: 'alerts',
                  label: (
                    <>
                      <Icon icon="mdi:bell-outline" width={13} className="mr-1 inline" />
                      Alerts
                      {!isLoading && alerts.length > 0 && (
                        <span className="ml-2 text-xs px-1.5 py-0.5 rounded-full bg-gray-200 text-gray-600">
                          {alerts.length}
                        </span>
                      )}
                    </>
                  ),
                },
                {
                  id: 'identity',
                  label: (
                    <>
                      <Icon icon="mdi:account-key-outline" width={13} className="mr-1 inline" />
                      Identity &amp; Access
                    </>
                  ),
                },
              ]}
              selectedKey={activeTab}
              onSelectionChange={(key) => setActiveTab(key as ActiveTab)}
            />

            <AnimatePresence mode="wait">
              {activeTab === 'assessments' && (
                <motion.div key="assessments" initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -4 }} transition={{ duration: 0.18, ease: 'easeOut' }}>
                  <AzureSecurityAssessmentsSection assessments={assessments} isLoading={isLoading} />
                </motion.div>
              )}
              {activeTab === 'controls' && (
                <motion.div key="controls" initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -4 }} transition={{ duration: 0.18, ease: 'easeOut' }}>
                  <AzureSecurityScoreControls controls={scoreControls} isLoading={isLoading} />
                </motion.div>
              )}
              {activeTab === 'alerts' && (
                <motion.div key="alerts" initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -4 }} transition={{ duration: 0.18, ease: 'easeOut' }}>
                  <AzureSecurityAlertsTable alerts={alerts} isLoading={isLoading} />
                </motion.div>
              )}
              {activeTab === 'identity' && (
                <motion.div key="identity" initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -4 }} transition={{ duration: 0.18, ease: 'easeOut' }}>
                  <IdentityAccessTab />
                </motion.div>
              )}
            </AnimatePresence>
          </>
        )}
      </div>
    </main>
  );
};

export default AzureSecurityPage;
