/**
 * Waste Management Page
 *
 * Converted from: waste.html
 *
 * This is the main Waste Management page for identifying and eliminating unused cloud resources.
 *
 * Features:
 * - KPI cards (Potential Savings, Wasted Resources Count, Highest Waste Service)
 * - Grouped resource display by service type
 * - Expandable resource details
 * - Real-time data fetching from backend
 * - Resource scanning with force refresh
 * - Session storage caching for better performance
 */

import React, { useState, useMemo, useCallback } from 'react';
import { useAccounts } from '../contexts/AccountContext';
import { useWasteData } from '../hooks/useWasteData';
import type { ServiceGroup, WastedResource } from '../types/waste';
import { AlertDialog } from '../components/ui/AlertDialog';
import apiClient from '../utils/apiClient';

// Component imports
import WasteHeader from '../components/Waste/WasteHeader';
import WasteKPICards from '../components/Waste/WasteKPICards';
import WasteResourceGroup from '../components/Waste/WasteResourceGroup';

export const WastePage: React.FC = () => {
  const { selectedAccountIds, accounts } = useAccounts();
  const selectedAccountId = selectedAccountIds.length > 0 ? selectedAccountIds.join(',') : null;

  // Determine if the active account has read-write access
  const selectedAccount = accounts.find(a => a.id === selectedAccountIds[0]);
  const canDelete = selectedAccount?.access === 'read-write';

  // Get waste data
  const { wastedResources, isLoading, isRefreshing, error, loadWastedResources, removeResource } = useWasteData({
    selectedAccountId,
  });

  // Track which groups are expanded
  const [expandedGroups, setExpandedGroups] = useState<Set<string>>(new Set());

  // Delete confirmation state
  const [deleteTarget, setDeleteTarget] = useState<WastedResource | null>(null);
  const [isDeleting, setIsDeleting] = useState(false);

  // Group resources by service type
  const groupedResources = useMemo(() => {
    if (!Array.isArray(wastedResources)) return [];

    const groups = wastedResources.reduce((acc, resource) => {
      const key = resource.resourceType || 'Other';
      if (!acc[key]) {
        acc[key] = {
          serviceType: key,
          resources: [],
          open: expandedGroups.has(key),
        };
      }
      acc[key].resources.push(resource);
      return acc;
    }, {} as Record<string, ServiceGroup>);

    return Object.values(groups).sort((a, b) => a.serviceType.localeCompare(b.serviceType));
  }, [wastedResources, expandedGroups]);

  // Toggle group expansion
  const toggleGroup = (serviceType: string) => {
    setExpandedGroups((prev) => {
      const newSet = new Set(prev);
      if (newSet.has(serviceType)) {
        newSet.delete(serviceType);
      } else {
        newSet.add(serviceType);
      }
      return newSet;
    });
  };

  // Refresh handler
  const handleRefresh = () => {
    loadWastedResources(true);
  };

  // Delete handler — opens the confirmation dialog
  const handleDeleteRequest = useCallback((resource: WastedResource) => {
    setDeleteTarget(resource);
  }, []);

  // Confirmed delete — calls API and removes resource from local list
  const handleDeleteConfirm = useCallback(async () => {
    if (!deleteTarget || !selectedAccountId) return;
    setIsDeleting(true);
    try {
      await apiClient.delete(
        `/api/xamops/waste/delete?accountId=${selectedAccountId}&resourceId=${encodeURIComponent(deleteTarget.resourceId)}&resourceType=${encodeURIComponent(deleteTarget.resourceType)}&region=${encodeURIComponent(deleteTarget.region)}`
      );
      // Immediately remove from the displayed list and cache
      removeResource(deleteTarget.resourceId);
    } catch (err) {
      console.error('Failed to delete waste resource:', err);
    } finally {
      setIsDeleting(false);
      setDeleteTarget(null);
    }
  }, [deleteTarget, selectedAccountId, removeResource]);

  // Show error state
  if (error) {
    return (
      <main className="flex-1 flex flex-col min-h-screen bg-transparent">
        <div className="fixed inset-0 flex items-center justify-center bg-red-50 text-red-700 z-50">
          <div className="bg-white p-8 rounded-lg shadow-xl max-w-md w-full mx-4">
            <div className="flex items-center justify-center w-16 h-16 mx-auto mb-4 bg-red-100 rounded-full">
              <i className="fas fa-exclamation-triangle text-red-600 text-2xl"></i>
            </div>
            <h2 className="text-2xl font-bold text-center mb-4 text-gray-800">Error</h2>
            <p className="text-center text-gray-600 mb-6">{error.message}</p>
            <button
              onClick={() => window.location.reload()}
              className="w-full bg-red-600 text-white py-2 px-4 rounded-lg hover:bg-red-700 transition-colors"
            >
              Try Again
            </button>
          </div>
        </div>
      </main>
    );
  }

  return (
    <main className="flex-1 flex flex-col min-h-screen bg-transparent">
      <WasteHeader isRefreshing={isRefreshing} onRefresh={handleRefresh} />

      <div className="flex-1 p-8 space-y-8">
        {/* No Account State */}
        {!selectedAccountId && !isLoading && (
          <div className="text-center py-12 rounded-2xl border-2 border-dashed border-gray-300 bg-gray-50/50">
            <div className="w-16 h-16 bg-gray-200 rounded-full flex items-center justify-center mx-auto mb-4">
              <i className="fas fa-recycle text-gray-400 text-2xl"></i>
            </div>
            <h4 className="text-lg font-bold text-gray-800 mb-2">No Account Selected</h4>
            <p className="text-gray-600">
              Please select an account from the sidebar to view waste management data.
            </p>
          </div>
        )}

        {/* Loading State */}
        {selectedAccountId && isLoading && (
          <div className="space-y-8">
            {/* Loading KPI Cards */}
            <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
              {[...Array(3)].map((_, i) => (
                <div key={i} className="animate-pulse bg-gray-200 h-32 w-full rounded-xl"></div>
              ))}
            </div>

            {/* Loading Resource Groups */}
            <div className="space-y-4">
              {[...Array(3)].map((_, i) => (
                <div key={i} className="service-group p-6">
                  <div className="flex items-center justify-between animate-pulse">
                    <div className="flex items-center space-x-4 flex-1">
                      <div className="h-6 w-6 rounded bg-gray-300"></div>
                      <div className="h-12 w-12 rounded-xl bg-gray-300"></div>
                      <div className="h-6 w-40 rounded-lg bg-gray-300"></div>
                      <div className="h-6 w-16 rounded-full bg-gray-300"></div>
                    </div>
                    <div className="h-10 w-28 rounded-lg bg-gray-300"></div>
                  </div>
                </div>
              ))}
            </div>
          </div>
        )}

        {/* Waste Data Content */}
        {selectedAccountId && !isLoading && (
          <>
            {/* KPI Cards */}
            <WasteKPICards wastedResources={wastedResources} isLoading={isLoading} />

            {/* No Waste Found State */}
            {groupedResources.length === 0 && (
              <div className="text-center py-20 px-8 rounded-2xl bg-white/80 shadow-sm">
                <div className="w-24 h-24 bg-gradient-to-br from-green-500 to-emerald-600 rounded-full flex items-center justify-center mx-auto mb-6 shadow-lg">
                  <i className="fas fa-leaf text-white text-3xl"></i>
                </div>
                <h3 className="text-2xl font-bold text-gray-800 mb-2">No Waste Found!</h3>
                <p className="text-gray-600">Your cloud environment is perfectly optimized.</p>
              </div>
            )}

            {/* Resource Groups */}
            <div className="space-y-6">
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
          </>
        )}
      </div>

      {/* Delete confirmation dialog */}
      <AlertDialog
        isOpen={deleteTarget !== null}
        variant="danger"
        title="Delete resource?"
        description={
          deleteTarget && (
            <>
              <strong>{deleteTarget.resourceName || deleteTarget.resourceId}</strong> (
              {deleteTarget.resourceType} · {deleteTarget.region}) will be permanently deleted from
              AWS. This action cannot be undone.
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
