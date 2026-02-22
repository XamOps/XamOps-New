import React, { useState } from 'react';
import type { AzureReservationItem, AzureSavingsPlanItem, AzureInventoryTabType } from '../../types/azureReservations';

interface AzureReservationInventoryProps {
  reservations: AzureReservationItem[];
  savingsPlans: AzureSavingsPlanItem[];
}

const StateBadge: React.FC<{ state: string }> = ({ state }) => {
  const lower = state.toLowerCase();
  let colorClass = 'bg-gray-100 text-gray-700';
  if (lower === 'succeeded') colorClass = 'bg-green-100 text-green-700';
  else if (lower === 'cancelled' || lower === 'canceled') colorClass = 'bg-red-100 text-red-700';
  else if (lower === 'expired') colorClass = 'bg-gray-200 text-gray-600';
  else if (lower === 'pending') colorClass = 'bg-yellow-100 text-yellow-700';

  return (
    <span className={`inline-flex items-center px-2 py-0.5 rounded-full text-xs font-semibold ${colorClass}`}>
      {state}
    </span>
  );
};

const formatTerm = (term: string) => {
  if (term === 'P1Y') return '1 Year';
  if (term === 'P3Y') return '3 Years';
  return term;
};

const formatDate = (dateStr: string) => {
  if (!dateStr) return '—';
  try {
    return new Date(dateStr).toLocaleDateString('en-IN', {
      year: 'numeric',
      month: 'short',
      day: 'numeric',
    });
  } catch {
    return dateStr;
  }
};

const AzureReservationInventory: React.FC<AzureReservationInventoryProps> = ({
  reservations,
  savingsPlans,
}) => {
  const [activeTab, setActiveTab] = useState<AzureInventoryTabType>('ris');

  return (
    <div className="glass-card rounded-xl shadow-sm overflow-hidden">
      {/* Tab nav */}
      <div className="border-b border-gray-100 px-6 pt-4">
        <nav className="flex gap-1">
          <button
            onClick={() => setActiveTab('ris')}
            className={`px-4 py-2.5 text-sm font-semibold rounded-t-lg transition-all ${
              activeTab === 'ris'
                ? 'bg-blue-50 text-blue-700 border-b-2 border-blue-600'
                : 'text-gray-500 hover:text-gray-700 hover:bg-gray-50'
            }`}
          >
            <i className="fas fa-server mr-2"></i>
            Reserved Instances
            {reservations.length > 0 && (
              <span className="ml-2 bg-blue-600 text-white text-xs px-1.5 py-0.5 rounded-full">
                {reservations.length}
              </span>
            )}
          </button>
          <button
            onClick={() => setActiveTab('sps')}
            className={`px-4 py-2.5 text-sm font-semibold rounded-t-lg transition-all ${
              activeTab === 'sps'
                ? 'bg-purple-50 text-purple-700 border-b-2 border-purple-600'
                : 'text-gray-500 hover:text-gray-700 hover:bg-gray-50'
            }`}
          >
            <i className="fas fa-piggy-bank mr-2"></i>
            Savings Plans
            {savingsPlans.length > 0 && (
              <span className="ml-2 bg-purple-600 text-white text-xs px-1.5 py-0.5 rounded-full">
                {savingsPlans.length}
              </span>
            )}
          </button>
        </nav>
      </div>

      {/* Reserved Instances table */}
      {activeTab === 'ris' && (
        <div className="overflow-x-auto">
          {reservations.length === 0 ? (
            <div className="flex flex-col items-center justify-center py-16 text-center">
              <div className="w-16 h-16 bg-gray-100 rounded-full flex items-center justify-center mb-4">
                <i className="fas fa-server text-gray-400 text-2xl"></i>
              </div>
              <h3 className="text-lg font-bold text-gray-700 mb-1">No Reserved Instances found</h3>
              <p className="text-sm text-gray-500 max-w-sm">
                No Azure Reserved VM Instances were found. This could mean none have been
                purchased, or the service principal lacks "Reservation Reader" role.
              </p>
            </div>
          ) : (
            <table className="w-full text-sm">
              <thead>
                <tr className="bg-gray-50 text-gray-600 font-semibold text-xs uppercase tracking-wider">
                  <th className="px-6 py-3 text-left">Name / SKU</th>
                  <th className="px-6 py-3 text-left">Quantity</th>
                  <th className="px-6 py-3 text-left">State</th>
                  <th className="px-6 py-3 text-left">Term</th>
                  <th className="px-6 py-3 text-left">Billing</th>
                  <th className="px-6 py-3 text-left">Location</th>
                  <th className="px-6 py-3 text-left">Expires</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-50">
                {reservations.map((item, idx) => (
                  <tr key={item.reservationId || idx} className="hover:bg-gray-50 transition-colors">
                    <td className="px-6 py-4">
                      <div className="font-semibold text-gray-900">{item.displayName || item.sku}</div>
                      {item.displayName && item.sku && item.displayName !== item.sku && (
                        <div className="text-xs text-gray-500 mt-0.5">{item.sku}</div>
                      )}
                    </td>
                    <td className="px-6 py-4 text-gray-700 font-medium">{item.quantity}</td>
                    <td className="px-6 py-4">
                      <StateBadge state={item.state} />
                    </td>
                    <td className="px-6 py-4 text-gray-700">{formatTerm(item.term)}</td>
                    <td className="px-6 py-4 text-gray-700">{item.billingPlan || '—'}</td>
                    <td className="px-6 py-4 text-gray-700 capitalize">{item.location || '—'}</td>
                    <td className="px-6 py-4 text-gray-700">{formatDate(item.expiryDate)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      )}

      {/* Savings Plans table */}
      {activeTab === 'sps' && (
        <div className="overflow-x-auto">
          {savingsPlans.length === 0 ? (
            <div className="flex flex-col items-center justify-center py-16 text-center">
              <div className="w-16 h-16 bg-gray-100 rounded-full flex items-center justify-center mb-4">
                <i className="fas fa-piggy-bank text-gray-400 text-2xl"></i>
              </div>
              <h3 className="text-lg font-bold text-gray-700 mb-1">No Savings Plans found</h3>
              <p className="text-sm text-gray-500 max-w-sm">
                No Azure Savings Plans were found. This could mean none have been purchased,
                or the service principal lacks "Reservation Reader" role.
              </p>
            </div>
          ) : (
            <table className="w-full text-sm">
              <thead>
                <tr className="bg-gray-50 text-gray-600 font-semibold text-xs uppercase tracking-wider">
                  <th className="px-6 py-3 text-left">Name</th>
                  <th className="px-6 py-3 text-left">Commitment</th>
                  <th className="px-6 py-3 text-left">Term</th>
                  <th className="px-6 py-3 text-left">Payment</th>
                  <th className="px-6 py-3 text-left">State</th>
                  <th className="px-6 py-3 text-left">Applied Scope</th>
                  <th className="px-6 py-3 text-left">Expires</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-50">
                {savingsPlans.map((item, idx) => (
                  <tr key={item.savingsPlanId || idx} className="hover:bg-gray-50 transition-colors">
                    <td className="px-6 py-4 font-semibold text-gray-900">{item.displayName}</td>
                    <td className="px-6 py-4 text-gray-700 font-medium">{item.commitment}</td>
                    <td className="px-6 py-4 text-gray-700">{formatTerm(item.term)}</td>
                    <td className="px-6 py-4 text-gray-700">{item.paymentOption || '—'}</td>
                    <td className="px-6 py-4">
                      <StateBadge state={item.state} />
                    </td>
                    <td className="px-6 py-4 text-gray-700">{item.appliedScope || '—'}</td>
                    <td className="px-6 py-4 text-gray-700">{formatDate(item.expiryDate)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      )}
    </div>
  );
};

export default AzureReservationInventory;
