/**
 * ReservationInventory Component
 *
 * Inventory table with tabs for Reserved Instances and Savings Plans
 *
 * Converted from: reservation.html (inventory section)
 */

import React, { useState } from 'react';
import { Icon } from '@iconify/react';
import type {
  ReservationInventoryItem,
  SavingsPlan,
  InventoryTabType,
} from '../../types/reservation';

interface ReservationInventoryProps {
  inventory: ReservationInventoryItem[];
  savingsPlans: SavingsPlan[];
}

const ReservationInventory: React.FC<ReservationInventoryProps> = ({
  inventory,
  savingsPlans,
}) => {
  const [inventoryTab, setInventoryTab] = useState<InventoryTabType>('ris');

  // Helper functions
  const formatDate = (dateString: string): string => {
    if (!dateString) return 'N/A';
    const date = new Date(dateString);
    return isNaN(date.getTime())
      ? dateString
      : date.toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' });
  };

  const isExpiringSoon = (endDate: string): boolean => {
    if (!endDate) return false;
    const today = new Date();
    const expiry = new Date(endDate);
    const threshold = new Date();
    threshold.setDate(today.getDate() + 60);
    return expiry > today && expiry < threshold;
  };

  const formatCurrency = (val: string, isHourly = true): string => {
    if (!val) return 'N/A';
    const numStr = String(val).replace(/[^0-9.]/g, '');
    const num = parseFloat(numStr);
    if (isNaN(num)) return val;
    const decimals = isHourly && num < 1 ? 4 : 2;
    return (
      '$' +
      num.toLocaleString(undefined, { minimumFractionDigits: decimals, maximumFractionDigits: decimals }) +
      (isHourly ? '/hr' : '')
    );
  };

  return (
    <div className="glass-card rounded-xl overflow-hidden border-t-4 border-t-purple-500">
      <div className="px-6 py-5 border-b border-[rgba(0,0,0,0.06)] flex justify-between items-center bg-gradient-to-r from-[rgba(0,0,0,0.025)] to-white">
        <div>
          <h3 className="text-lg font-black text-[rgba(0,0,0,0.88)] flex items-center gap-2">
            <Icon icon="mdi:database-outline" className="text-purple-600" width={20} />
            Current Inventory
          </h3>
          <p className="text-xs text-[rgba(0,0,0,0.5)] mt-1 font-medium">Active reservations and plans</p>
        </div>

        <div className="flex bg-[rgba(0,0,0,0.06)] p-1 rounded-xl shadow-inner">
          <button
            onClick={() => setInventoryTab('ris')}
            className={`px-5 py-2 text-xs font-bold rounded-md transition-all ${
              inventoryTab === 'ris'
                ? 'bg-gradient-to-br from-purple-500 to-indigo-500 text-white shadow-md'
                : 'text-[rgba(0,0,0,0.6)] hover:text-[rgba(0,0,0,0.88)]'
            }`}
          >
            <Icon icon="mdi:server-outline" width={12} className="inline mr-1" />
            Reserved Instances
          </button>
          <button
            onClick={() => setInventoryTab('sps')}
            className={`px-5 py-2 text-xs font-bold rounded-md transition-all ${
              inventoryTab === 'sps'
                ? 'bg-gradient-to-br from-purple-500 to-indigo-500 text-white shadow-md'
                : 'text-[rgba(0,0,0,0.6)] hover:text-[rgba(0,0,0,0.88)]'
            }`}
          >
            <Icon icon="mdi:piggy-bank-outline" width={12} className="inline mr-1" />
            Savings Plans
          </button>
        </div>
      </div>

      {/* Reserved Instances Table */}
      {inventoryTab === 'ris' && (
        <div className="overflow-x-auto">
          <table className="w-full text-sm text-left text-slate-500 pro-table">
            <thead className="bg-[rgba(0,0,0,0.02)] border-b border-[rgba(0,0,0,0.06)]">
              <tr>
                <th className="px-6 py-3 text-[rgba(0,0,0,0.4)]">Details</th>
                <th className="px-6 py-3 text-[rgba(0,0,0,0.4)]">Scope / AZ</th>
                <th className="px-6 py-3 text-[rgba(0,0,0,0.4)]">Count / Offering</th>
                <th className="px-6 py-3 text-[rgba(0,0,0,0.4)]">State</th>
                <th className="px-6 py-3 text-[rgba(0,0,0,0.4)]">Utilization</th>
                <th className="px-6 py-3 text-[rgba(0,0,0,0.4)]">Expiry</th>
              </tr>
            </thead>
            <tbody className="divide-[rgba(0,0,0,0.04)]">
              {inventory && inventory.length > 0 ? (
                inventory.map((item) => (
                  <tr key={item.reservationId} className="hover:bg-[rgba(0,0,0,0.025)] border-b border-[rgba(0,0,0,0.04)]">
                    <td className="px-6 py-4">
                      <div className="font-semibold text-slate-800">{item.instanceType}</div>
                      <div className="text-xs text-slate-400 font-mono mt-0.5">
                        {item.reservationId}
                      </div>
                    </td>
                    <td className="px-6 py-4">
                      <div>{item.scope}</div>
                      <div className="text-xs text-slate-500">{item.availabilityZone || '-'}</div>
                    </td>
                    <td className="px-6 py-4">
                      <div className="font-medium">{item.instanceCount} Inst</div>
                      <div className="text-xs text-slate-500">{item.offeringType}</div>
                    </td>
                    <td className="px-6 py-4">
                      <span
                        className={`status-badge ${
                          item.state === 'active'
                            ? 'status-active'
                            : item.state === 'retired' || item.state === 'expired'
                            ? 'bg-gradient-to-r from-gray-400 to-gray-500 text-white'
                            : 'status-expiring'
                        }`}
                      >
                        {item.state}
                      </span>
                    </td>
                    <td className="px-6 py-4">
                      <div className="flex items-center gap-2">
                        <div className="w-20 bg-[rgba(0,0,0,0.06)] rounded-full h-2 shadow-inner overflow-hidden">
                          <div
                            className={`h-2 rounded-full transition-all duration-500 ${
                              item.utilizationPercentage >= 90
                                ? 'bg-gradient-to-r from-green-500 to-emerald-500'
                                : item.utilizationPercentage < 70
                                ? 'bg-gradient-to-r from-red-500 to-rose-500'
                                : 'bg-gradient-to-r from-yellow-500 to-amber-500'
                            }`}
                            style={{ width: `${item.utilizationPercentage || 0}%` }}
                          ></div>
                        </div>
                        <span className="text-xs font-bold text-[rgba(0,0,0,0.7)]">
                          {(item.utilizationPercentage || 0).toFixed(0)}%
                        </span>
                      </div>
                    </td>
                    <td className="px-6 py-4">
                      <div
                        className={`font-medium ${
                          isExpiringSoon(item.end) ? 'text-red-600' : ''
                        }`}
                      >
                        {formatDate(item.end)}
                      </div>
                      {isExpiringSoon(item.end) && (
                        <div className="text-[10px] text-red-600 font-bold flex items-center gap-1 mt-0.5">
                          <Icon icon="mdi:alert-circle-outline" width={10} />
                          Expiring Soon
                        </div>
                      )}
                    </td>
                  </tr>
                ))
              ) : (
                <tr>
                  <td colSpan={6} className="text-center py-16">
                    <div className="flex flex-col items-center">
                      <div className="w-14 h-14 bg-[rgba(0,0,0,0.04)] border border-[rgba(0,0,0,0.08)] rounded-xl flex items-center justify-center mb-4">
                        <Icon icon="mdi:inbox-outline" className="text-[rgba(0,0,0,0.4)]" width={24} />
                      </div>
                      <p className="text-[rgba(0,0,0,0.6)] font-semibold mb-1">No Reserved Instances Found</p>
                      <p className="text-[rgba(0,0,0,0.4)] text-sm">No data available for this account</p>
                    </div>
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      )}

      {/* Savings Plans Table */}
      {inventoryTab === 'sps' && (
        <div className="overflow-x-auto">
          <table className="w-full text-sm text-left text-slate-500 pro-table">
            <thead className="bg-[rgba(0,0,0,0.02)] border-b border-[rgba(0,0,0,0.06)]">
              <tr>
                <th className="px-6 py-3 text-[rgba(0,0,0,0.4)]">Plan Type</th>
                <th className="px-6 py-3 text-[rgba(0,0,0,0.4)]">Region</th>
                <th className="px-6 py-3 text-[rgba(0,0,0,0.4)]">Commitment</th>
                <th className="px-6 py-3 text-[rgba(0,0,0,0.4)]">State</th>
                <th className="px-6 py-3 text-[rgba(0,0,0,0.4)]">Utilization</th>
                <th className="px-6 py-3 text-[rgba(0,0,0,0.4)]">Used / Unused</th>
                <th className="px-6 py-3 text-[rgba(0,0,0,0.4)]">Net Savings</th>
                <th className="px-6 py-3 text-[rgba(0,0,0,0.4)]">Term</th>
                <th className="px-6 py-3 text-[rgba(0,0,0,0.4)]">Expiry</th>
              </tr>
            </thead>
            <tbody className="divide-[rgba(0,0,0,0.04)]">
              {savingsPlans && savingsPlans.length > 0 ? (
                savingsPlans.map((sp) => (
                  <tr key={sp.savingsPlanId} className="hover:bg-[rgba(0,0,0,0.025)] border-b border-[rgba(0,0,0,0.04)]">
                    <td className="px-6 py-4">
                      <div className="font-semibold text-slate-800">{sp.type}</div>
                      <div className="text-xs text-slate-400 font-mono mt-0.5">
                        {sp.savingsPlanId}
                      </div>
                    </td>
                    <td className="px-6 py-4">{sp.region || 'Global'}</td>
                    <td className="px-6 py-4">
                      <div className="font-medium text-slate-900">{formatCurrency(sp.commitment)}</div>
                      <div className="text-xs text-slate-500">{sp.paymentOption}</div>
                    </td>
                    <td className="px-6 py-4">
                      <span
                        className={`status-badge ${
                          sp.state === 'active'
                            ? 'status-active'
                            : 'bg-gradient-to-r from-gray-400 to-gray-500 text-white'
                        }`}
                      >
                        {sp.state}
                      </span>
                    </td>
                    <td className="px-6 py-4">
                      {sp.utilizationPercentage != null ? (
                        <div className="flex items-center gap-2">
                          <div className="w-20 bg-[rgba(0,0,0,0.06)] rounded-full h-2 overflow-hidden">
                            <div
                              className={`h-2 rounded-full transition-all duration-500 ${
                                sp.utilizationPercentage >= 90
                                  ? 'bg-emerald-500'
                                  : sp.utilizationPercentage < 70
                                  ? 'bg-red-500'
                                  : 'bg-amber-500'
                              }`}
                              style={{ width: `${sp.utilizationPercentage}%` }}
                            ></div>
                          </div>
                          <span className="text-xs font-bold text-[rgba(0,0,0,0.7)]">
                            {sp.utilizationPercentage.toFixed(0)}%
                          </span>
                        </div>
                      ) : (
                        <span className="text-xs text-[rgba(0,0,0,0.3)]">N/A</span>
                      )}
                    </td>
                    <td className="px-6 py-4">
                      {sp.usedCommitment != null ? (
                        <div className="text-xs font-medium">
                          <div className="text-green-700">${sp.usedCommitment}/hr used</div>
                          <div className="text-red-500 mt-0.5">${sp.unusedCommitment ?? '0'}/hr unused</div>
                        </div>
                      ) : (
                        <span className="text-xs text-[rgba(0,0,0,0.3)]">N/A</span>
                      )}
                    </td>
                    <td className="px-6 py-4">
                      {sp.netSavings != null ? (
                        <div className="font-semibold text-green-600 text-sm">
                          ${parseFloat(sp.netSavings).toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })}
                        </div>
                      ) : (
                        <span className="text-xs text-[rgba(0,0,0,0.3)]">N/A</span>
                      )}
                    </td>
                    <td className="px-6 py-4">
                      <div className="text-xs text-[rgba(0,0,0,0.6)] font-medium">
                        <span>{formatDate(sp.start)}</span> → <span>{formatDate(sp.end)}</span>
                      </div>
                    </td>
                    <td className="px-6 py-4">
                      <div
                        className={`font-medium ${
                          isExpiringSoon(sp.end) ? 'text-red-600' : ''
                        }`}
                      >
                        {formatDate(sp.end)}
                      </div>
                      {isExpiringSoon(sp.end) && (
                        <div className="text-[10px] text-red-600 font-bold flex items-center gap-1 mt-0.5">
                          <Icon icon="mdi:alert-circle-outline" width={10} />
                          Expiring Soon
                        </div>
                      )}
                    </td>
                  </tr>
                ))
              ) : (
                <tr>
                  <td colSpan={9} className="text-center py-16">
                    <div className="flex flex-col items-center">
                      <div className="w-14 h-14 bg-[rgba(0,0,0,0.04)] border border-[rgba(0,0,0,0.08)] rounded-xl flex items-center justify-center mb-4">
                        <Icon icon="mdi:piggy-bank-outline" className="text-[rgba(0,0,0,0.4)]" width={24} />
                      </div>
                      <p className="text-[rgba(0,0,0,0.6)] font-semibold mb-1">No Savings Plans Found</p>
                      <p className="text-[rgba(0,0,0,0.4)] text-sm">
                        No active savings plans for this account
                      </p>
                    </div>
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
};

export default ReservationInventory;
