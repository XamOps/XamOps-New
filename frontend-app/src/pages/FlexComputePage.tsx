import React, { useState, useMemo, useEffect } from 'react';
import { Icon } from '@iconify/react';
import { gooeyToast } from 'goey-toast';
import { useFlexComputeData } from '../hooks/useFlexComputeData';
import { useAccounts } from '../contexts/AccountContext';
import { useTopbarActions } from '../contexts/TopbarContext';
import FlexComputeKPICards from '../components/FlexCompute/FlexComputeKPICards';
import FlexComputeTable from '../components/FlexCompute/FlexComputeTable';
import FlexComputeWizard from '../components/FlexCompute/FlexComputeWizard';
import type { FlexComputeTab, FlexComputeCapacity, CapacityPricing } from '../types/flexCompute';
import { useTabState } from '../hooks/useTabState';

const FlexComputePage: React.FC = () => {
  const [activeTab, setActiveTab] = useTabState<FlexComputeTab>('dashboard');
  const { accounts } = useAccounts();
  const {
    capacities,
    kpis,
    kpiCapacities,
    isLoading,
    fetchCapacities,
    searchCapacity,
    reserveCapacity,
    configureCapacity,
    purchaseCapacity,
    cancelCapacity,
    extendCapacity,
    renewCapacity,
    currentPage,
    pageSize,
    totalElements,
    totalPages,
    isSearching,
    isPurchasing,
    error,
    linkedAccounts,
  } = useFlexComputeData();

  const sidebarAccountIds = useMemo(() => new Set(accounts.map((a) => a.id)), [accounts]);

  const accountOptions = useMemo(() => {
    return (linkedAccounts || [])
      .filter((acc) => sidebarAccountIds.has(acc.accountId))
      .map((acc) => ({
        id: acc.accountId,
        cspAccountId: acc.accountId,
        alias: acc.accountName || `${acc.provider} ${acc.accountId}`,
        accountRegions: acc.accountRegions,
      }));
  }, [linkedAccounts, sidebarAccountIds]);

  const allowedAccountNames = useMemo(
    () => new Set(accountOptions.map((a) => a.alias)),
    [accountOptions]
  );

  const filteredCapacities = useMemo(
    () => (capacities || []).filter((c) => allowedAccountNames.has(c.accountName)),
    [capacities, allowedAccountNames]
  );

  const hasUnfilteredData = (capacities || []).length > 0;
  const displayTotalElements = filteredCapacities.length === 0 && hasUnfilteredData ? 0 : totalElements;
  const displayTotalPages = filteredCapacities.length === 0 && hasUnfilteredData ? 0 : totalPages;

  const displayKpis = useMemo(() => {
    if (allowedAccountNames.size === 0) {
      return { totalSavings: 0, totalCost: 0, awsCost: 0, totalUsageHours: 0, remainingHours: 0 };
    }
    const filtered = (kpiCapacities || []).filter((c) => allowedAccountNames.has(c.accountName));
    if (filtered.length === 0) return kpis;
    return filtered.reduce(
      (acc, item) => ({
        totalSavings: acc.totalSavings + (item.totalSavings || 0),
        totalCost: acc.totalCost + (item.totalCost || 0),
        awsCost: acc.awsCost + (item.awsPrice || 0),
        totalUsageHours: acc.totalUsageHours + (item.totalUsageHours || 0),
        remainingHours: acc.remainingHours + (item.remainingHours || 0),
      }),
      { totalSavings: 0, totalCost: 0, awsCost: 0, totalUsageHours: 0, remainingHours: 0 }
    );
  }, [kpiCapacities, allowedAccountNames, kpis]);

  const [continuePricing, setContinuePricing] = useState<CapacityPricing | null>(null);

  const handleContinuePurchase = (cap: FlexComputeCapacity) => {
    const [regionCode, zoneId] = (cap.regionZoneId || '/').split('/');
    const pricing: CapacityPricing = {
      capacityId: cap.id,
      capacityName: cap.name,
      instanceType: cap.instanceType,
      region: cap.region || regionCode || '',
      zoneId: cap.zoneId || zoneId || '',
      quantity: cap.quantities,
      platform: cap.platform || '',
      tenancy: cap.tenancy || '',
      ourPrice: cap.ourPrice || 0,
      awsPrice: cap.awsPrice || 0,
      savingsPercent: cap.savingsPercent || 0,
      fees: 0,
      total: cap.ourPrice || 0,
      startDate: cap.startDate,
      endDate: cap.endDate,
      status: cap.policyStatus,
    };
    setContinuePricing(pricing);
    setActiveTab('create');
  };

  const { setActions, clearActions } = useTopbarActions();

  useEffect(() => {
    setActions(
      <div className="flex items-center gap-2">
        <span className="w-1.5 h-1.5 rounded-full bg-indigo-500" />
        <span className="text-[11px] font-medium uppercase tracking-wide text-[rgba(0,0,0,0.4)]">FlexCompute</span>
        <span className="text-[rgba(0,0,0,0.15)] text-[12px] select-none">|</span>
        <button
          onClick={() => fetchCapacities()}
          disabled={isLoading}
          className="flex items-center gap-1 text-[11px] font-semibold text-[rgba(0,0,0,0.5)] hover:text-[rgba(0,0,0,0.88)] transition-colors disabled:opacity-40"
        >
          <svg className={`w-3 h-3 ${isLoading ? 'animate-spin' : ''}`} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5">
            <path d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15" strokeLinecap="round" strokeLinejoin="round" />
          </svg>
          Refresh
        </button>
      </div>
    );
    return () => clearActions();
  }, [isLoading, fetchCapacities]);

  return (
    <main className="flex-1 px-6 pt-4 pb-6 space-y-5">
      {/* Tab Switcher */}
      <div className="flex items-center gap-1 bg-[rgba(0,0,0,0.03)] border border-[rgba(0,0,0,0.07)] rounded-xl p-1 w-fit">
        {([
          { id: 'dashboard', label: 'Dashboard', icon: 'mdi:view-dashboard-outline' },
          { id: 'create', label: 'New Reservation', icon: 'mdi:plus-circle-outline' },
        ] as const).map((tab) => (
          <button
            key={tab.id}
            onClick={() => setActiveTab(tab.id)}
            className={`flex items-center gap-1.5 px-4 py-2 rounded-lg text-[12px] font-semibold transition-all ${activeTab === tab.id
                ? 'bg-white shadow-sm border border-[rgba(0,0,0,0.08)] text-[rgba(0,0,0,0.88)]'
                : 'text-[rgba(0,0,0,0.45)] hover:text-[rgba(0,0,0,0.65)]'
              }`}
          >
            <Icon icon={tab.icon} width={13} />
            {tab.label}
          </button>
        ))}
      </div>

      {/* Error Banner */}
      {error && (
        <div className="p-4 bg-red-50 border border-red-200 rounded-xl text-[13px] text-red-700 flex items-center gap-2">
          <Icon icon="mdi:alert-circle-outline" width={16} />
          {error}
        </div>
      )}

      {activeTab === 'dashboard' && (
        <div className="space-y-5">
          <FlexComputeKPICards kpis={displayKpis} />
          <FlexComputeTable
            capacities={filteredCapacities}
            isLoading={isLoading}
            onCancelCapacity={cancelCapacity}
            onExtendCapacity={extendCapacity}
            onContinuePurchase={handleContinuePurchase}
            currentPage={currentPage}
            pageSize={pageSize}
            totalElements={displayTotalElements}
            totalPages={displayTotalPages}
            onPageChange={(page, size, statuses) => fetchCapacities(page, size, statuses)}
          />
        </div>
      )}

      {activeTab === 'create' && (
        <div>
          {continuePricing && (
            <div className="mb-4 px-4 py-3 bg-amber-50 border border-amber-200 rounded-xl text-[13px] text-amber-800 flex items-center gap-2">
              <Icon icon="mdi:information-outline" width={16} className="flex-shrink-0" />
              Resuming purchase for <strong className="mx-1">{continuePricing.capacityName}</strong> — review the details and confirm.
              <button
                onClick={() => setContinuePricing(null)}
                className="ml-auto text-amber-600 hover:text-amber-800 text-[12px] underline"
              >
                Start new reservation instead
              </button>
            </div>
          )}
          <FlexComputeWizard
            key={continuePricing?.capacityId || 'new'}
            accounts={accountOptions}
            searchCapacity={searchCapacity}
            reserveCapacity={reserveCapacity}
            configureCapacity={configureCapacity}
            purchaseCapacity={purchaseCapacity}
            renewCapacity={renewCapacity}
            isSearching={isSearching}
            isPurchasing={isPurchasing}
            initialPricing={continuePricing}
            onComplete={() => {
              setContinuePricing(null);
              setActiveTab('dashboard');
              gooeyToast.success('Capacity reservation created successfully!');
            }}
          />
        </div>
      )}
    </main>
  );
};

export default FlexComputePage;
