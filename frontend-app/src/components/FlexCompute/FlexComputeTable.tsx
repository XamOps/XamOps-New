/**
 * FlexCompute Table Component
 *
 * Displays capacity list with columns matching requirements
 */

import React from 'react';
import type { FlexComputeCapacity } from '../../types/flexCompute';

interface FlexComputeTableProps {
  capacities: FlexComputeCapacity[];
  isLoading: boolean;
}

const statusBadge = (status: string) => {
  const styles: Record<string, string> = {
    active: 'bg-green-100 text-green-700',
    running: 'bg-green-100 text-green-700',
    scheduled: 'bg-blue-100 text-blue-700',
    pending: 'bg-yellow-100 text-yellow-700',
    stopped: 'bg-gray-100 text-gray-600',
    terminated: 'bg-red-100 text-red-700',
  };
  const style = styles[status?.toLowerCase()] || 'bg-gray-100 text-gray-600';
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

const FlexComputeTable: React.FC<FlexComputeTableProps> = ({ capacities, isLoading }) => {
  if (isLoading) {
    return (
      <div className="bg-white rounded-2xl shadow-sm border border-gray-200 p-8">
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
    );
  }

  if (capacities.length === 0) {
    return (
      <div className="bg-white rounded-2xl shadow-sm border border-gray-200 p-16 text-center">
        <div className="w-20 h-20 bg-gray-100 rounded-full flex items-center justify-center mx-auto mb-6">
          <i className="fas fa-server text-gray-400 text-3xl"></i>
        </div>
        <h3 className="text-xl font-bold text-gray-800 mb-2">No Capacity Reservations</h3>
        <p className="text-gray-500 max-w-md mx-auto">
          You haven't created any capacity reservations yet. Switch to the "New Reservation" tab to get started.
        </p>
      </div>
    );
  }

  return (
    <div className="bg-white rounded-2xl shadow-sm border border-gray-200 overflow-hidden">
      <div className="px-6 py-4 border-b border-gray-200">
        <h3 className="text-lg font-semibold text-gray-900">
          Scheduled Capacities
          <span className="ml-2 text-sm font-normal text-gray-500">({capacities.length})</span>
        </h3>
      </div>
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
            {capacities.map((cap) => (
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
                <td className="px-6 py-4">
                  <button className="text-gray-400 hover:text-blue-600 transition-colors" title="View Details">
                    <i className="fas fa-eye"></i>
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
};

export default FlexComputeTable;
