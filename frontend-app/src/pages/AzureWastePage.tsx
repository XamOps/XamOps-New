import React from 'react';
import { AnimatePresence, motion } from 'framer-motion';
import { Icon } from '@iconify/react';
import { useAccounts } from '../contexts/AccountContext';
import { useAzureWasteData } from '../hooks/useAzureWasteData';
import { AzureWasteHeader, AzureWasteKPICards, AzureWasteResourceGroup } from '../components/AzureWaste';

const AzureWastePage: React.FC = () => {
  const { selectedAccountIds } = useAccounts();
  const selectedAccountId = selectedAccountIds.length > 0 ? selectedAccountIds[0] : null;

  const { wasteGroups, allItems, isLoading, isRefreshing, loadWasteData } = useAzureWasteData({ selectedAccountId });

  return (
    <main className="flex-1 px-6 pt-4 pb-6 space-y-5">
      <AzureWasteHeader
        selectedAccountId={selectedAccountId}
        isRefreshing={isRefreshing}
        onRefresh={() => loadWasteData(true)}
      />

      {/* No account */}
      {!selectedAccountId && !isLoading && (
        <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-10 text-center">
          <div className="w-12 h-12 bg-[rgba(0,0,0,0.04)] rounded-full flex items-center justify-center mx-auto mb-3">
            <Icon icon="mdi:recycle" width={22} color="rgba(0,0,0,0.3)" />
          </div>
          <p className="text-[14px] font-semibold text-[rgba(0,0,0,0.88)] mb-1">No Account Selected</p>
          <p className="text-[13px] text-[rgba(0,0,0,0.45)]">Select an Azure account from the sidebar to scan for waste.</p>
        </div>
      )}

      {selectedAccountId && (
        <>
          <motion.div
            initial={{ opacity: 0, y: 8 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ duration: 0.18, ease: 'easeOut' }}
          >
            <AzureWasteKPICards allItems={allItems} isLoading={isLoading} />
          </motion.div>

          <AnimatePresence mode="wait">
            {isLoading && (
              <motion.div
                key="loading"
                initial={{ opacity: 0 }}
                animate={{ opacity: 1 }}
                exit={{ opacity: 0 }}
                transition={{ duration: 0.15 }}
                className="space-y-3"
              >
                {Array.from({ length: 3 }).map((_, i) => (
                  <div key={i} className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-5 animate-pulse">
                    <div className="flex items-center gap-3">
                      <div className="w-8 h-8 rounded-lg bg-[rgba(0,0,0,0.06)]" />
                      <div className="flex-1">
                        <div className="h-3 w-32 bg-[rgba(0,0,0,0.06)] rounded mb-2" />
                        <div className="h-2 w-20 bg-[rgba(0,0,0,0.04)] rounded" />
                      </div>
                    </div>
                  </div>
                ))}
              </motion.div>
            )}

            {!isLoading && (
              <motion.div
                key="content"
                initial={{ opacity: 0, y: 10 }}
                animate={{ opacity: 1, y: 0 }}
                transition={{ duration: 0.28, ease: [0.22, 1, 0.36, 1] }}
                className="space-y-3"
              >
                {allItems.length === 0 ? (
                  <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-10 text-center">
                    <div className="w-12 h-12 rounded-full flex items-center justify-center mx-auto mb-3" style={{ backgroundColor: '#16A34A14' }}>
                      <Icon icon="mdi:check-circle-outline" width={24} color="#16A34A" />
                    </div>
                    <p className="text-[15px] font-semibold text-[rgba(0,0,0,0.88)] mb-1">No Waste Detected</p>
                    <p className="text-[13px] text-[rgba(0,0,0,0.45)]">Your Azure resources appear clean — no idle disks, stopped VMs, or orphaned IPs found.</p>
                  </div>
                ) : (
                  <div className="space-y-3">
                    {wasteGroups.map(group => (
                      <AzureWasteResourceGroup key={group.type} group={group} />
                    ))}
                  </div>
                )}
              </motion.div>
            )}
          </AnimatePresence>
        </>
      )}
    </main>
  );
};

export default AzureWastePage;
