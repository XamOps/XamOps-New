import React, { useState, useMemo, useCallback, useRef, useEffect } from 'react';
import { AnimatePresence, motion } from 'framer-motion';
import { Icon } from '@iconify/react';
import { useAccounts } from '../contexts/AccountContext';
import { useWasteData } from '../hooks/useWasteData';
import type { ServiceGroup, WastedResource } from '../types/waste';
import { AlertDialog } from '../components/ui/AlertDialog';
import apiClient from '../utils/apiClient';
import WasteHeader from '../components/Waste/WasteHeader';
import WasteKPICards from '../components/Waste/WasteKPICards';
import WasteResourceGroup from '../components/Waste/WasteResourceGroup';

const RevealSection: React.FC<{ children: React.ReactNode; delay?: number }> = ({ children, delay = 0 }) => {
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    const timer = setTimeout(() => {
      const observer = new IntersectionObserver(([entry]) => {
        if (entry.isIntersecting) { el.classList.add('is-visible'); observer.unobserve(el); }
      }, { threshold: 0.06 });
      observer.observe(el);
      return () => observer.disconnect();
    }, 60);
    return () => clearTimeout(timer);
  }, []);
  return <div ref={ref} className="section-reveal" style={delay ? { transitionDelay: `${delay}s` } : undefined}>{children}</div>;
};

export const WastePage: React.FC = () => {
  const { selectedAccountIds, accounts } = useAccounts();
  const selectedAccountId = selectedAccountIds.length > 0 ? selectedAccountIds.join(',') : null;
  const selectedAccount = accounts.find(a => a.id === selectedAccountIds[0]);
  const canDelete = selectedAccount?.access === 'read-write';

  const { wastedResources, isLoading, isRefreshing, error, loadWastedResources, removeResource } = useWasteData({ selectedAccountId });

  const [expandedGroups, setExpandedGroups] = useState<Set<string>>(new Set());
  const [deleteTarget, setDeleteTarget] = useState<WastedResource | null>(null);
  const [isDeleting, setIsDeleting] = useState(false);

  const groupedResources = useMemo(() => {
    if (!Array.isArray(wastedResources)) return [];
    const groups = wastedResources.reduce((acc, resource) => {
      const key = resource.resourceType || 'Other';
      if (!acc[key]) acc[key] = { serviceType: key, resources: [], open: expandedGroups.has(key) };
      acc[key].resources.push(resource);
      return acc;
    }, {} as Record<string, ServiceGroup>);
    return Object.values(groups).sort((a, b) => {
      const savA = a.resources.reduce((s, r) => s + (r.monthlySavings || 0), 0);
      const savB = b.resources.reduce((s, r) => s + (r.monthlySavings || 0), 0);
      return savB - savA;
    });
  }, [wastedResources, expandedGroups]);

  const toggleGroup = (serviceType: string) => {
    setExpandedGroups(prev => {
      const next = new Set(prev);
      next.has(serviceType) ? next.delete(serviceType) : next.add(serviceType);
      return next;
    });
  };

  const handleDeleteRequest = useCallback((resource: WastedResource) => {
    setDeleteTarget(resource);
  }, []);

  const handleDeleteConfirm = useCallback(async () => {
    if (!deleteTarget || !selectedAccountId) return;
    setIsDeleting(true);
    try {
      await apiClient.delete(
        `/api/xamops/waste/delete?accountId=${selectedAccountId}&resourceId=${encodeURIComponent(deleteTarget.resourceId)}&resourceType=${encodeURIComponent(deleteTarget.resourceType)}&region=${encodeURIComponent(deleteTarget.region)}`
      );
      removeResource(deleteTarget.resourceId);
    } catch (err) {
      console.error('Failed to delete waste resource:', err);
    } finally {
      setIsDeleting(false);
      setDeleteTarget(null);
    }
  }, [deleteTarget, selectedAccountId, removeResource]);

  if (error) {
    return (
      <main className="flex-1 px-6 pt-4 pb-6">
        <WasteHeader isRefreshing={false} onRefresh={() => loadWastedResources(true)} />
        <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-10 flex flex-col items-center text-center">
          <div className="w-12 h-12 rounded-full bg-rose-50 flex items-center justify-center mb-4">
            <Icon icon="mdi:alert-circle-outline" width={24} color="#DC2626" />
          </div>
          <p className="text-[15px] font-semibold text-[rgba(0,0,0,0.88)] mb-1">Failed to load waste data</p>
          <p className="text-[13px] text-[rgba(0,0,0,0.45)] mb-5">{error.message}</p>
          <button
            onClick={() => loadWastedResources(true)}
            className="px-4 py-2 rounded-xl text-xs font-bold transition-colors"
            style={{ backgroundColor: '#1BE68B', color: 'rgb(0,0,0)' }}
          >
            Try Again
          </button>
        </div>
      </main>
    );
  }

  return (
    <main className="flex-1 px-6 pt-4 pb-6 space-y-5">
      <WasteHeader isRefreshing={isRefreshing} onRefresh={() => loadWastedResources(true)} />

      {/* No account selected */}
      {!selectedAccountId && !isLoading && (
        <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-10 text-center">
          <div className="w-12 h-12 bg-[rgba(0,0,0,0.04)] rounded-full flex items-center justify-center mx-auto mb-3">
            <Icon icon="mdi:recycle" width={22} color="rgba(0,0,0,0.3)" />
          </div>
          <p className="text-[14px] font-semibold text-[rgba(0,0,0,0.88)] mb-1">No Account Selected</p>
          <p className="text-[13px] text-[rgba(0,0,0,0.45)]">Select an account from the sidebar to view waste data.</p>
        </div>
      )}

      <AnimatePresence mode="wait">
        {/* Loading */}
        {selectedAccountId && isLoading && (
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
            <WasteKPICards wastedResources={wastedResources} isLoading={isLoading} />

            {groupedResources.length === 0 ? (
              <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-10 text-center">
                <div className="w-12 h-12 rounded-full flex items-center justify-center mx-auto mb-3" style={{ backgroundColor: '#16A34A14' }}>
                  <Icon icon="mdi:leaf" width={24} color="#16A34A" />
                </div>
                <p className="text-[15px] font-semibold text-[rgba(0,0,0,0.88)] mb-1">No Waste Found</p>
                <p className="text-[13px] text-[rgba(0,0,0,0.45)]">Your cloud environment is perfectly optimized.</p>
              </div>
            ) : (
              <div className="space-y-3">
                {groupedResources.map((group) => (
                  <WasteResourceGroup
                    key={group.serviceType}
                    serviceType={group.serviceType}
                    resources={group.resources}
                    isOpen={expandedGroups.has(group.serviceType)}
                    onToggle={() => toggleGroup(group.serviceType)}
                    canDelete={canDelete}
                    onDelete={handleDeleteRequest}
                  />
                ))}
              </div>
            )}
          </motion.div>
        )}
      </AnimatePresence>

      <AlertDialog
        isOpen={deleteTarget !== null}
        variant="danger"
        title="Delete resource?"
        description={
          deleteTarget && (
            <>
              <strong>{deleteTarget.resourceName || deleteTarget.resourceId}</strong> ({deleteTarget.resourceType} · {deleteTarget.region}) will be permanently deleted from AWS. This action cannot be undone.
            </>
          )
        }
        confirmLabel={isDeleting ? 'Deleting…' : 'Yes, Delete'}
        cancelLabel="Cancel"
        onConfirm={handleDeleteConfirm}
        onCancel={() => setDeleteTarget(null)}
      />
    </main>
  );
};

export default WastePage;
