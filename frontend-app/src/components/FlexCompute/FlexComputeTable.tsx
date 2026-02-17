/**
 * FlexCompute Table Component
 *
 * Displays capacity list with pagination, search, status filter, and actions
 */

import React, { useState, useMemo } from 'react';
import type { FlexComputeCapacity } from '../../types/flexCompute';
import ViewCapacityDetailModal from './ViewCapacityDetailModal';

interface FlexComputeTableProps {
  capacities: FlexComputeCapacity[];
  isLoading: boolean;
  onCancelCapacity: (cidrId: string) => Promise<boolean>;
  onExtendCapacity: (cidrId: string, endDate: string, endTime: string, utcOffset: string) => Promise<boolean>;
  onContinuePurchase?: (capacity: FlexComputeCapacity) => void;
  // Pagination
  currentPage: number;
  pageSize: number;
  totalElements: number;
  totalPages: number;
  onPageChange: (page: number, pageSize: number, statuses: string[]) => void;
}

const STATUS_OPTIONS = [
  { value: 'ACTIVE', label: 'Active', color: 'bg-green-100 text-green-700' },
  { value: 'COMPLETED', label: 'Completed', color: 'bg-blue-100 text-blue-700' },
  { value: 'EXPIRED', label: 'Expired', color: 'bg-gray-100 text-gray-600' },
  { value: 'CANCELED', label: 'Cancelled', color: 'bg-red-100 text-red-700' },
];

const statusBadge = (status: string) => {
  const styles: Record<string, string> = {
    active: 'bg-green-100 text-green-700',
    running: 'bg-green-100 text-green-700',
    completed: 'bg-blue-100 text-blue-700',
    scheduled: 'bg-blue-100 text-blue-700',
    pending: 'bg-yellow-100 text-yellow-700',
    ready_for_purchase: 'bg-yellow-100 text-yellow-700',
    stopped: 'bg-gray-100 text-gray-600',
    expired: 'bg-gray-100 text-gray-600',
    expired_not_purchased: 'bg-gray-100 text-gray-600',
    terminated: 'bg-red-100 text-red-700',
    canceled: 'bg-red-100 text-red-700',
    cancelled: 'bg-red-100 text-red-700',
  };
  const normalized = (status || '').toLowerCase().replace(/ /g, '_');
  const style = styles[normalized] || 'bg-gray-100 text-gray-600';
  return (
    <span className={`px-2.5 py-1 rounded-full text-xs font-semibold ${style}`}>
      {status || 'N/A'}
    </span>
  );
};

const formatCurrency = (value: number): string => {
  return new Intl.NumberFormat('en-US', {
    style: 'currency',
    currency: 'USD',
    minimumFractionDigits: 2,
  }).format(value || 0);
};

const PAGE_SIZE_OPTIONS = [5, 10, 20, 50];

const isReadyForPurchase = (cap: FlexComputeCapacity): boolean => {
  const status = (cap.policyStatus || '').toLowerCase().replace(/ /g, '_');
  const opState = (cap.policyOperatingState || '').toLowerCase().replace(/ /g, '_');
  return status === 'ready_for_purchase' || opState === 'ready_for_purchase';
};

const FlexComputeTable: React.FC<FlexComputeTableProps> = ({
  capacities,
  isLoading,
  onCancelCapacity,
  onExtendCapacity,
  onContinuePurchase,
  currentPage,
  pageSize,
  totalElements,
  totalPages,
  onPageChange,
}) => {
  const [openDropdown, setOpenDropdown] = useState<string | null>(null);
  const [cancelling, setCancelling] = useState<string | null>(null);
  const [selectedCapacity, setSelectedCapacity] = useState<FlexComputeCapacity | null>(null);
  const [searchQuery, setSearchQuery] = useState('');
  const [selectedStatuses, setSelectedStatuses] = useState<string[]>([]);

  const handleCancel = async (cidrId: string, capacityName: string) => {
    if (!window.confirm(`Are you sure you want to cancel "${capacityName}"? This action cannot be undone.`)) {
      return;
    }
    setCancelling(cidrId);
    setOpenDropdown(null);
    try {
      await onCancelCapacity(cidrId);
    } finally {
      setCancelling(null);
    }
  };

  const toggleStatus = (status: string) => {
    const newStatuses = selectedStatuses.includes(status)
      ? selectedStatuses.filter((s) => s !== status)
      : [...selectedStatuses, status];
    setSelectedStatuses(newStatuses);
    onPageChange(1, pageSize, newStatuses);
  };

  const clearFilters = () => {
    setSelectedStatuses([]);
    setSearchQuery('');
    onPageChange(1, pageSize, []);
  };

  // Client-side search filtering (search by name, id, instance type, account)
  const filteredCapacities = useMemo(() => {
    if (!searchQuery.trim()) return capacities;
    const q = searchQuery.toLowerCase();
    return capacities.filter(
      (cap) =>
        (cap.name || '').toLowerCase().includes(q) ||
        (cap.id || '').toLowerCase().includes(q) ||
        (cap.instanceType || '').toLowerCase().includes(q) ||
        (cap.accountName || '').toLowerCase().includes(q) ||
        (cap.regionZoneId || '').toLowerCase().includes(q)
    );
  }, [capacities, searchQuery]);

  const startItem = (currentPage - 1) * pageSize + 1;
  const endItem = Math.min(currentPage * pageSize, totalElements);

  return (
    <>
    <div className="bg-white rounded-2xl shadow-sm border border-gray-200 overflow-hidden">
      {/* Header with search and filters */}
      <div className="px-6 py-4 border-b border-gray-200 space-y-4">
        <div className="flex items-center justify-between">
          <h3 className="text-lg font-semibold text-gray-900">
            Scheduled Capacities
            <span className="ml-2 text-sm font-normal text-gray-500">({totalElements})</span>
          </h3>
          <div className="flex items-center gap-2">
            <select
              value={pageSize}
              onChange={(e) => onPageChange(1, parseInt(e.target.value), selectedStatuses)}
              className="px-3 py-1.5 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-blue-500 focus:border-blue-500 outline-none bg-white"
            >
              {PAGE_SIZE_OPTIONS.map((size) => (
                <option key={size} value={size}>{size} per page</option>
              ))}
            </select>
          </div>
        </div>

        {/* Search bar + Status filter chips */}
        <div className="flex flex-col sm:flex-row items-start sm:items-center gap-3">
          {/* Search */}
          <div className="relative flex-1 w-full sm:max-w-xs">
            <i className="fas fa-search absolute left-3 top-1/2 -translate-y-1/2 text-gray-400 text-sm"></i>
            <input
              type="text"
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              placeholder="Search by name, ID, instance type..."
              className="w-full pl-9 pr-4 py-2 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-blue-500 focus:border-blue-500 outline-none"
            />
            {searchQuery && (
              <button
                onClick={() => setSearchQuery('')}
                className="absolute right-3 top-1/2 -translate-y-1/2 text-gray-400 hover:text-gray-600"
              >
                <i className="fas fa-times text-xs"></i>
              </button>
            )}
          </div>

          {/* Status filter chips */}
          <div className="flex items-center gap-2 flex-wrap">
            <span className="text-xs text-gray-500 font-medium">Status:</span>
            {STATUS_OPTIONS.map((opt) => {
              const isSelected = selectedStatuses.includes(opt.value);
              return (
                <button
                  key={opt.value}
                  onClick={() => toggleStatus(opt.value)}
                  className={`px-3 py-1 rounded-full text-xs font-medium border transition-colors ${
                    isSelected
                      ? `${opt.color} border-current`
                      : 'bg-white text-gray-500 border-gray-300 hover:bg-gray-50'
                  }`}
                >
                  {opt.label}
                </button>
              );
            })}
            {(selectedStatuses.length > 0 || searchQuery) && (
              <button
                onClick={clearFilters}
                className="px-2 py-1 text-xs text-gray-500 hover:text-gray-700 transition-colors"
              >
                Clear all
              </button>
            )}
          </div>
        </div>
      </div>

      {/* Loading state */}
      {isLoading ? (
        <div className="p-8">
          <div className="space-y-4">
            {[...Array(5)].map((_, i) => (
              <div key={i} className="animate-pulse flex space-x-4">
                <div className="h-4 bg-gray-200 rounded w-1/12"></div>
                <div className="h-4 bg-gray-200 rounded w-2/12"></div>
                <div className="h-4 bg-gray-200 rounded w-2/12"></div>
                <div className="h-4 bg-gray-200 rounded w-1/12"></div>
                <div className="h-4 bg-gray-200 rounded w-1/12"></div>
                <div className="h-4 bg-gray-200 rounded w-1/12"></div>
                <div className="h-4 bg-gray-200 rounded w-1/12"></div>
                <div className="h-4 bg-gray-200 rounded w-2/12"></div>
                <div className="h-4 bg-gray-200 rounded w-1/12"></div>
              </div>
            ))}
          </div>
        </div>
      ) : filteredCapacities.length === 0 ? (
        <div className="p-16 text-center">
          <div className="w-16 h-16 bg-gray-100 rounded-full flex items-center justify-center mx-auto mb-4">
            <i className="fas fa-search text-gray-400 text-2xl"></i>
          </div>
          <h3 className="text-lg font-semibold text-gray-800 mb-1">No results found</h3>
          <p className="text-sm text-gray-500">
            {searchQuery ? 'Try a different search term or ' : ''}
            {selectedStatuses.length > 0 ? 'adjust your status filters.' : 'No capacity reservations yet.'}
          </p>
          {(searchQuery || selectedStatuses.length > 0) && (
            <button
              onClick={clearFilters}
              className="mt-3 px-4 py-2 text-sm text-blue-600 hover:text-blue-700 font-medium"
            >
              Clear filters
            </button>
          )}
        </div>
      ) : (
        <>
        {/* Table */}
        <div className="overflow-x-auto">
          <table className="w-full">
            <thead>
              <tr className="bg-gray-50 text-left">
                <th className="px-6 py-3 text-xs font-semibold text-gray-500 uppercase tracking-wider">Id</th>
                <th className="px-6 py-3 text-xs font-semibold text-gray-500 uppercase tracking-wider">Name</th>
                <th className="px-6 py-3 text-xs font-semibold text-gray-500 uppercase tracking-wider">Account Name</th>
                <th className="px-6 py-3 text-xs font-semibold text-gray-500 uppercase tracking-wider">Policy Status</th>
                <th className="px-6 py-3 text-xs font-semibold text-gray-500 uppercase tracking-wider">Instance State</th>
                <th className="px-6 py-3 text-xs font-semibold text-gray-500 uppercase tracking-wider">Instance Type</th>
                <th className="px-6 py-3 text-xs font-semibold text-gray-500 uppercase tracking-wider">Qty</th>
                <th className="px-6 py-3 text-xs font-semibold text-gray-500 uppercase tracking-wider">Region / Zone-Id</th>
                <th className="px-6 py-3 text-xs font-semibold text-gray-500 uppercase tracking-wider">Est. Savings</th>
                <th className="px-6 py-3 text-xs font-semibold text-gray-500 uppercase tracking-wider">Actions</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-200">
              {filteredCapacities.map((cap) => (
                <tr key={cap.id} className="hover:bg-gray-50 transition-colors">
                  <td className="px-6 py-4 text-sm font-mono text-gray-600">{cap.id?.substring(0, 8)}...</td>
                  <td className="px-6 py-4 text-sm font-medium text-gray-900">{cap.name}</td>
                  <td className="px-6 py-4 text-sm text-gray-600">{cap.accountName}</td>
                  <td className="px-6 py-4">{statusBadge(cap.policyStatus)}</td>
                  <td className="px-6 py-4">{statusBadge(cap.instanceState)}</td>
                  <td className="px-6 py-4 text-sm font-mono text-gray-700">{cap.instanceType}</td>
                  <td className="px-6 py-4 text-sm text-gray-700">{cap.quantities}</td>
                  <td className="px-6 py-4 text-sm text-gray-600">{cap.regionZoneId}</td>
                  <td className="px-6 py-4 text-sm font-semibold text-green-600">{formatCurrency(cap.estimatedSavings)}</td>
                  <td className="px-6 py-4 relative">
                    <button
                      onClick={() => setOpenDropdown(openDropdown === cap.id ? null : cap.id)}
                      className="text-gray-400 hover:text-gray-600 transition-colors p-2"
                      disabled={cancelling === cap.id}
                    >
                      {cancelling === cap.id ? (
                        <i className="fas fa-spinner fa-spin"></i>
                      ) : (
                        <i className="fas fa-ellipsis-v"></i>
                      )}
                    </button>

                    {/* Dropdown Menu */}
                    {openDropdown === cap.id && (
                      <>
                        <div
                          className="fixed inset-0 z-10"
                          onClick={() => setOpenDropdown(null)}
                        ></div>
                        <div className="absolute right-0 mt-2 w-52 bg-white rounded-lg shadow-lg border border-gray-200 py-1 z-20">
                          <button
                            onClick={() => {
                              setOpenDropdown(null);
                              setSelectedCapacity(cap);
                            }}
                            className="w-full text-left px-4 py-2 text-sm text-gray-700 hover:bg-gray-100 transition-colors flex items-center gap-2"
                          >
                            <i className="fas fa-eye w-4"></i>
                            View Detail
                          </button>
                          {isReadyForPurchase(cap) && onContinuePurchase && (
                            <button
                              onClick={() => {
                                setOpenDropdown(null);
                                onContinuePurchase(cap);
                              }}
                              className="w-full text-left px-4 py-2 text-sm text-green-700 hover:bg-green-50 transition-colors flex items-center gap-2 font-medium"
                            >
                              <i className="fas fa-shopping-cart w-4"></i>
                              Continue Purchase
                            </button>
                          )}
                          <button
                            onClick={() => handleCancel(cap.id, cap.name)}
                            disabled={cancelling === cap.id}
                            className="w-full text-left px-4 py-2 text-sm text-red-600 hover:bg-red-50 transition-colors flex items-center gap-2 disabled:opacity-50 disabled:cursor-not-allowed"
                          >
                            <i className="fas fa-times-circle w-4"></i>
                            Cancel
                          </button>
                        </div>
                      </>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>

        {/* Pagination */}
        <div className="px-6 py-4 border-t border-gray-200 flex items-center justify-between">
          <p className="text-sm text-gray-600">
            Showing <span className="font-medium">{startItem}</span> to <span className="font-medium">{endItem}</span> of{' '}
            <span className="font-medium">{totalElements}</span> results
          </p>
          <div className="flex items-center gap-2">
            <button
              onClick={() => onPageChange(1, pageSize, selectedStatuses)}
              disabled={currentPage <= 1}
              className="px-2.5 py-1.5 text-sm border border-gray-300 rounded-lg hover:bg-gray-50 disabled:opacity-40 disabled:cursor-not-allowed transition-colors"
              title="First page"
            >
              <i className="fas fa-angle-double-left"></i>
            </button>
            <button
              onClick={() => onPageChange(currentPage - 1, pageSize, selectedStatuses)}
              disabled={currentPage <= 1}
              className="px-3 py-1.5 text-sm border border-gray-300 rounded-lg hover:bg-gray-50 disabled:opacity-40 disabled:cursor-not-allowed transition-colors"
            >
              <i className="fas fa-angle-left mr-1"></i>
              Prev
            </button>

            {/* Page numbers */}
            {Array.from({ length: Math.min(totalPages, 5) }, (_, i) => {
              let pageNum: number;
              if (totalPages <= 5) {
                pageNum = i + 1;
              } else if (currentPage <= 3) {
                pageNum = i + 1;
              } else if (currentPage >= totalPages - 2) {
                pageNum = totalPages - 4 + i;
              } else {
                pageNum = currentPage - 2 + i;
              }
              return (
                <button
                  key={pageNum}
                  onClick={() => onPageChange(pageNum, pageSize, selectedStatuses)}
                  className={`w-9 h-9 text-sm rounded-lg transition-colors ${
                    pageNum === currentPage
                      ? 'bg-blue-600 text-white font-semibold'
                      : 'border border-gray-300 text-gray-700 hover:bg-gray-50'
                  }`}
                >
                  {pageNum}
                </button>
              );
            })}

            <button
              onClick={() => onPageChange(currentPage + 1, pageSize, selectedStatuses)}
              disabled={currentPage >= totalPages}
              className="px-3 py-1.5 text-sm border border-gray-300 rounded-lg hover:bg-gray-50 disabled:opacity-40 disabled:cursor-not-allowed transition-colors"
            >
              Next
              <i className="fas fa-angle-right ml-1"></i>
            </button>
            <button
              onClick={() => onPageChange(totalPages, pageSize, selectedStatuses)}
              disabled={currentPage >= totalPages}
              className="px-2.5 py-1.5 text-sm border border-gray-300 rounded-lg hover:bg-gray-50 disabled:opacity-40 disabled:cursor-not-allowed transition-colors"
              title="Last page"
            >
              <i className="fas fa-angle-double-right"></i>
            </button>
          </div>
        </div>
        </>
      )}
    </div>

    {/* View Detail Modal */}
    {selectedCapacity && (
      <ViewCapacityDetailModal
        capacity={selectedCapacity}
        onClose={() => setSelectedCapacity(null)}
        onExtend={onExtendCapacity}
      />
    )}
    </>
  );
};

export default FlexComputeTable;
