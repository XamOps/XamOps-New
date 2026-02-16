/**
 * FlexCompute Page
 *
 * Main page for CloudiDR FlexCompute capacity management.
 * Features:
 * - CloudiDR authentication integration
 * - Dashboard with KPIs and capacity table
 * - Multi-step wizard for creating new reservations
 * - CloudFormation stack deployment guide
 *
 * API Endpoints:
 * - GET /api/flexcompute/auth/status - Check CloudiDR auth
 * - POST /api/flexcompute/auth - Authenticate with CloudiDR
 * - GET /api/flexcompute/capacity - List capacities
 * - POST /api/flexcompute/capacity/search - Search capacity
 * - PUT /api/flexcompute/capacity/configure - Configure capacity
 * - POST /api/flexcompute/capacity/purchase - Purchase capacity
 */

import React, { useState, useMemo } from 'react';
import { useFlexComputeData } from '../hooks/useFlexComputeData';
import FlexComputeKPICards from '../components/FlexCompute/FlexComputeKPICards';
import FlexComputeTable from '../components/FlexCompute/FlexComputeTable';
import FlexComputeWizard from '../components/FlexCompute/FlexComputeWizard';
import CloudiDRAuthModal from '../components/FlexCompute/CloudiDRAuthModal';
import type { FlexComputeTab } from '../types/flexCompute';

const FlexComputePage: React.FC = () => {
  const [activeTab, setActiveTab] = useState<FlexComputeTab>('dashboard');
  const {
    authStatus,
    authLoading,
    authenticateCloudIDR,
    disconnectCloudIDR,
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

  // Toast notification
  const [toast, setToast] = useState<{ show: boolean; message: string; type: 'success' | 'error' }>({
    show: false,
    message: '',
    type: 'success',
  });

  const showToast = (message: string, type: 'success' | 'error' = 'success') => {
    setToast({ show: true, message, type });
    setTimeout(() => setToast({ show: false, message: '', type: 'success' }), 4000);
  };

  // Auth loading state
  if (authLoading) {
    return (
      <div className="flex-1 flex items-center justify-center min-h-screen">
        <div className="text-gray-500">
          <i className="fas fa-spinner fa-spin mr-2"></i>
          Checking CloudiDR connection...
        </div>
      </div>
    );
  }

  // Not authenticated - show auth modal
  if (!authStatus.authenticated) {
    return (
      <main className="flex-1 flex flex-col bg-transparent">
        <div className="bg-white border-b border-gray-200 px-8 py-6">
          <h1 className="text-2xl font-bold text-gray-900">Flex Compute</h1>
          <p className="text-sm text-gray-500 mt-1">Connect to CloudiDR to manage scheduled EC2 capacity</p>
        </div>
        <CloudiDRAuthModal onAuthenticate={authenticateCloudIDR} error={error} />
      </main>
    );
  }

  return (
    <main className="flex-1 flex flex-col bg-transparent">
      {/* Top Bar: Tabs + Action Buttons */}
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

          {/* Action Buttons */}
          <div className="flex items-center gap-3">
            <button
              onClick={() => fetchCapacities()}
              disabled={isLoading}
              className="px-4 py-2 text-sm font-medium text-white bg-gradient-to-r from-blue-600 to-indigo-600 rounded-lg hover:from-blue-700 hover:to-indigo-700 transition-all disabled:opacity-50"
            >
              <i className={`fas fa-sync-alt mr-2 ${isLoading ? 'animate-spin' : ''}`}></i>
              Refresh
            </button>
            <button
              onClick={async () => {
                await disconnectCloudIDR();
                showToast('Disconnected from CloudiDR');
              }}
              className="px-3 py-2 text-sm font-medium text-gray-500 hover:text-red-600 transition-colors"
              title="Disconnect CloudiDR"
            >
              <i className="fas fa-sign-out-alt"></i>
            </button>
          </div>
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
            <FlexComputeWizard
              accounts={accountOptions}
              searchCapacity={searchCapacity}
              reserveCapacity={reserveCapacity}
              configureCapacity={configureCapacity}
              purchaseCapacity={purchaseCapacity}
              isSearching={isSearching}
              isPurchasing={isPurchasing}
              onComplete={() => {
                setActiveTab('dashboard');
                showToast('Capacity reservation created successfully!', 'success');
              }}
            />
          </div>
        )}
      </div>

      {/* Toast Notification */}
      {toast.show && (
        <div
          className="fixed bottom-8 right-8 px-6 py-4 rounded-xl shadow-2xl text-white text-sm font-semibold z-50 flex items-center max-w-md animate-slide-up"
          style={{ backgroundColor: toast.type === 'success' ? '#10b981' : '#ef4444' }}
        >
          <i className={`fas ${toast.type === 'success' ? 'fa-check-circle' : 'fa-exclamation-circle'} mr-3 text-xl`}></i>
          <span>{toast.message}</span>
        </div>
      )}
    </main>
  );
};

export default FlexComputePage;
