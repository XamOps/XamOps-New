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
import FlexComputeKPICards from '../components/FlexCompute/FlexComputeKPICards';
import FlexComputeTable from '../components/FlexCompute/FlexComputeTable';
import FlexComputeWizard from '../components/FlexCompute/FlexComputeWizard';
import type { FlexComputeTab, FlexComputeCapacity, CapacityPricing } from '../types/flexCompute';

const FlexComputePage: React.FC = () => {
  const [activeTab, setActiveTab] = useState<FlexComputeTab>('dashboard');
  const {
    capacities,
    kpis,
    isLoading,
    fetchCapacities,
    searchCapacity,
    reserveCapacity,
    configureCapacity,
    purchaseCapacity,
    cancelCapacity,
    extendCapacity,
    currentPage,
    pageSize,
    totalElements,
    totalPages,
    isSearching,
    isPurchasing,
    error,
    linkedAccounts,
  } = useFlexComputeData();

  // Map CloudiDR linked accounts for the wizard dropdown (with accountRegions)
  const accountOptions = useMemo(() => {
    return (linkedAccounts || []).map((acc) => ({
      id: acc.accountId,
      cspAccountId: acc.accountId,
      alias: acc.accountName || `${acc.provider} ${acc.accountId}`,
      accountRegions: acc.accountRegions,
    }));
  }, [linkedAccounts]);

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
          <nav className="flex space-x-8" aria-label="Tabs">
            <button
              onClick={() => setActiveTab('dashboard')}
              className={`py-4 px-1 border-b-2 font-medium text-sm transition-colors ${
                activeTab === 'dashboard'
                  ? 'border-blue-500 text-blue-600'
                  : 'border-transparent text-gray-500 hover:text-gray-700 hover:border-gray-300'
              }`}
            >
              <i className="fas fa-tachometer-alt mr-2"></i>
              Dashboard
            </button>
            <button
              onClick={() => setActiveTab('create')}
              className={`py-4 px-1 border-b-2 font-medium text-sm transition-colors ${
                activeTab === 'create'
                  ? 'border-blue-500 text-blue-600'
                  : 'border-transparent text-gray-500 hover:text-gray-700 hover:border-gray-300'
              }`}
            >
              <i className="fas fa-plus-circle mr-2"></i>
              New Reservation
            </button>
          </nav>

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
            <FlexComputeKPICards kpis={kpis} />
            <FlexComputeTable
              capacities={capacities}
              isLoading={isLoading}
              onCancelCapacity={cancelCapacity}
              onExtendCapacity={extendCapacity}
              onContinuePurchase={handleContinuePurchase}
              currentPage={currentPage}
              pageSize={pageSize}
              totalElements={totalElements}
              totalPages={totalPages}
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
