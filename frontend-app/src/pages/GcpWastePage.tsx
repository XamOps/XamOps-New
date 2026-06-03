import React, { useState, useMemo } from 'react';
import { AnimatePresence, motion } from 'framer-motion';
import { Icon } from '@iconify/react';
import { useAccounts } from '../contexts/AccountContext';
import { useGcpWasteData } from '../hooks/useGcpWasteData';
import type { GcpWasteGroup } from '../types/gcpWaste';
import { GcpWasteHeader, GcpWasteKPICards, GcpWasteResourceGroup } from '../components/GcpWaste';

const GcpWastePage: React.FC = () => {
  const { selectedAccountIds } = useAccounts();
  const selectedAccountId = selectedAccountIds.length > 0 ? selectedAccountIds[0] : null;

  const { wasteItems, isLoading, isRefreshing, isPartial, loadWasteReport } = useGcpWasteData({ selectedAccountId });

  const [expandedGroups, setExpandedGroups] = useState<Set<string>>(new Set());

  const groupedResources = useMemo((): GcpWasteGroup[] => {
    if (!Array.isArray(wasteItems)) return [];
    const groups = wasteItems.reduce((acc, resource) => {
      const key = resource.type || 'Other';
      if (!acc[key]) acc[key] = { type: key, resources: [], open: true, totalSavings: 0 };
      acc[key].resources.push(resource);
      acc[key].totalSavings += resource.monthlySavings || 0;
      return acc;
    }, {} as Record<string, GcpWasteGroup>);
    return Object.values(groups).sort((a, b) => b.totalSavings - a.totalSavings);
  }, [wasteItems]);

  React.useEffect(() => {
    if (groupedResources.length > 0 && expandedGroups.size === 0) {
      setExpandedGroups(new Set(groupedResources.map(g => g.type)));
    }
  }, [groupedResources]);

  const toggleGroup = (type: string) => {
    setExpandedGroups(prev => {
      const next = new Set(prev);
      next.has(type) ? next.delete(type) : next.add(type);
      return next;
    });
  };

  return (
    <main className="flex-1 px-6 pt-4 pb-6 space-y-5">
      <GcpWasteHeader isRefreshing={isRefreshing} onRefresh={() => loadWasteReport(true)} />

      {/* No account */}
      {!selectedAccountId && !isLoading && (
        <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-10 text-center">
          <div className="w-12 h-12 bg-[rgba(0,0,0,0.04)] rounded-full flex items-center justify-center mx-auto mb-3">
            <Icon icon="mdi:recycle" width={22} color="rgba(0,0,0,0.3)" />
          </div>
          <p className="text-[14px] font-semibold text-[rgba(0,0,0,0.88)] mb-1">No Account Selected</p>
          <p className="text-[13px] text-[rgba(0,0,0,0.45)]">Select a GCP account from the sidebar to view waste data.</p>
        </div>
      )}

      <AnimatePresence mode="wait">
        {/* Loading */}
        {selectedAccountId && isLoading && wasteItems.length === 0 && (
          <motion.div
            key="loading"
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            transition={{ duration: 0.15 }}
            className="space-y-5"
          >
            <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4">
              {Array.from({ length: 3 }).map((_, i) => (
                <div key={i} className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-5 h-[110px] animate-pulse">
                  <div className="h-2.5 w-24 bg-[rgba(0,0,0,0.06)] rounded mb-3" />
                  <div className="h-7 w-28 bg-[rgba(0,0,0,0.06)] rounded mb-3" />
                  <div className="h-2 w-16 bg-[rgba(0,0,0,0.04)] rounded" />
                </div>
              ))}
            </div>
            <div className="space-y-3">
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
            </div>
          </motion.div>
        )}

        {/* Content */}
        {selectedAccountId && !isLoading && (
          <motion.div
            key="content"
            initial={{ opacity: 0, y: 10 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ duration: 0.28, ease: [0.22, 1, 0.36, 1] }}
            className="space-y-5"
          >
            {isPartial && (
              <div className="bg-amber-50 border border-amber-200 rounded-xl px-5 py-3.5 flex items-center gap-3">
                <Icon icon="mdi:alert-outline" width={18} color="#D97706" />
                <p className="text-[13px] text-amber-800 flex-1">
                  Some detectors timed out — results may be incomplete.{' '}
                  <button
                    onClick={() => loadWasteReport(true)}
                    className="font-semibold underline underline-offset-2 hover:text-amber-900"
                  >
                    Force Refresh
                  </button>{' '}
                  to run a full scan.
                </p>
              </div>
            )}

            <GcpWasteKPICards wasteItems={wasteItems} isLoading={isLoading} />

            {groupedResources.length === 0 ? (
              <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-10 text-center">
                <div className="w-12 h-12 rounded-full flex items-center justify-center mx-auto mb-3" style={{ backgroundColor: '#16A34A14' }}>
                  <Icon icon="mdi:leaf" width={24} color="#16A34A" />
                </div>
                <p className="text-[15px] font-semibold text-[rgba(0,0,0,0.88)] mb-1">No Waste Found</p>
                <p className="text-[13px] text-[rgba(0,0,0,0.45)]">Your GCP environment is looking clean and optimized.</p>
              </div>
            ) : (
              <div className="space-y-3">
                {groupedResources.map(group => (
                  <GcpWasteResourceGroup
                    key={group.type}
                    type={group.type}
                    resources={group.resources}
                    isOpen={expandedGroups.has(group.type)}
                    onToggle={() => toggleGroup(group.type)}
                  />
                ))}
              </div>
            )}
          </motion.div>
        )}
      </AnimatePresence>
    </main>
  );
};

export default GcpWastePage;
