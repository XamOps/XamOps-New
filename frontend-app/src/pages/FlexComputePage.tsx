/**
 * FlexCompute Page
 *
 * Main page for CloudiDR FlexCompute capacity management.
 * Features:
 * - Dashboard with KPIs and capacity table
 * - Multi-step wizard for creating new reservations
 *
 * CloudiDR authentication is handled automatically by the backend
 * using credentials from application.properties.
 */

import React, { useState, useMemo } from 'react';
import { gooeyToast } from 'goey-toast';
import { useFlexComputeData } from '../hooks/useFlexComputeData';
import { useAccounts } from '../contexts/AccountContext';
import { AriaTabGroup } from '../components/ui';
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

  // Map CloudiDR linked accounts for the wizard dropdown.
  // Only show accounts that are also present in the sidebar (AccountContext),
  // i.e. the account is both connected to CloudiDR and registered in XamOps.
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

  // Only show capacities whose account is in both CloudiDR and the sidebar dropdown
  const allowedAccountNames = useMemo(
    () => new Set(accountOptions.map((a) => a.alias)),
    [accountOptions]
  );

  const filteredCapacities = useMemo(
    () => (capacities || []).filter((c) => allowedAccountNames.has(c.accountName)),
    [capacities, allowedAccountNames]
  );

  // When client-side filtering removes items, the server-side totalElements/totalPages
  // no longer matches what's visible. Use 0 when nothing passes the filter so the
  // header count doesn't show a misleading number (e.g. "(31)" with 0 rows).
  const hasUnfilteredData = (capacities || []).length > 0;
  const displayTotalElements = filteredCapacities.length === 0 && hasUnfilteredData ? 0 : totalElements;
  const displayTotalPages    = filteredCapacities.length === 0 && hasUnfilteredData ? 0 : totalPages;

  // Re-compute KPIs from kpiCapacities filtered to only the allowed accounts,
  // so cards don't show figures from CloudiDR accounts the selected user can't see.
  const displayKpis = useMemo(() => {
    if (allowedAccountNames.size === 0) {
      return { totalSavings: 0, totalCost: 0, awsCost: 0, totalUsageHours: 0, remainingHours: 0 };
    }
    const filtered = (kpiCapacities || []).filter((c) => allowedAccountNames.has(c.accountName));
    if (filtered.length === 0) return kpis; // fallback to raw if names don't match yet
    return filtered.reduce(
      (acc, item) => ({
        totalSavings:    acc.totalSavings    + (item.totalSavings    || 0),
        totalCost:       acc.totalCost       + (item.totalCost       || 0),
        awsCost:         acc.awsCost         + (item.awsPrice        || 0),
        totalUsageHours: acc.totalUsageHours + (item.totalUsageHours || 0),
        remainingHours:  acc.remainingHours  + (item.remainingHours  || 0),
      }),
      { totalSavings: 0, totalCost: 0, awsCost: 0, totalUsageHours: 0, remainingHours: 0 }
    );
  }, [kpiCapacities, allowedAccountNames, kpis]);

  // Pricing pre-loaded from an existing READY_FOR_PURCHASE capacity
  const [continuePricing, setContinuePricing] = useState<CapacityPricing | null>(null);

  const handleContinuePurchase = (cap: FlexComputeCapacity) => {
    // Build a CapacityPricing from the existing capacity record
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


  return (
    <main className="flex-1 flex flex-col bg-transparent">
      {/* Top Bar: Tabs + Refresh */}
      <div className="bg-white border-b border-gray-200 px-8">
        <div className="flex items-center justify-between">
          {/* Tabs */}
          <AriaTabGroup
            tabs={[
              { id: 'dashboard', label: 'Dashboard',       icon: 'fas fa-tachometer-alt' },
              { id: 'create',    label: 'New Reservation', icon: 'fas fa-plus-circle' },
            ]}
            selectedKey={activeTab}
            onSelectionChange={(key) => setActiveTab(key as FlexComputeTab)}
          />

          {/* Refresh Button */}
          <button
            onClick={() => fetchCapacities()}
            disabled={isLoading}
            className="px-4 py-2 text-sm font-medium text-white bg-gradient-to-r from-blue-600 to-indigo-600 rounded-lg hover:from-blue-700 hover:to-indigo-700 transition-all disabled:opacity-50"
          >
            <i className={`fas fa-sync-alt mr-2 ${isLoading ? 'animate-spin' : ''}`}></i>
            Refresh
          </button>
        </div>
      </div>

      {/* Error Banner */}
      {error && (
        <div className="mx-8 mt-4 p-4 bg-red-50 border border-red-200 rounded-xl text-sm text-red-700 flex items-center gap-2">
          <i className="fas fa-exclamation-circle"></i>
          {error}
        </div>
      )}

      {/* Tab Content */}
      <div className="flex-1 p-8">
        {activeTab === 'dashboard' && (
          <div className="space-y-8">
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
          <div className="flex-compute-wizard">
            {continuePricing && (
              <div className="mb-4 px-4 py-3 bg-yellow-50 border border-yellow-300 rounded-xl text-sm text-yellow-800 flex items-center gap-2">
                <i className="fas fa-info-circle"></i>
                Resuming purchase for <strong className="mx-1">{continuePricing.capacityName}</strong> — review the details and confirm.
                <button
                  onClick={() => setContinuePricing(null)}
                  className="ml-auto text-yellow-600 hover:text-yellow-800 text-xs underline"
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
      </div>

    </main>
  );
};

export default FlexComputePage;
