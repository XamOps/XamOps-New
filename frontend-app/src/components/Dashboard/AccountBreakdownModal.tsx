/**
 * AccountBreakdownModal Component
 *
 * Modal for displaying detailed account breakdowns
 * Supports different view modes: cost, resource, waste, alerts, savings
 */

import React from 'react';
import { createPortal } from 'react-dom';
import type { ViewMode, SubAccount } from '../../types/dashboard';

interface AccountBreakdownModalProps {
  show: boolean;
  onClose: () => void;
  provider: string;
  viewMode: ViewMode;
  accountList: SubAccount[];
  onSelectAccount: (accountId: string, mode: ViewMode) => void;
}

export const AccountBreakdownModal: React.FC<AccountBreakdownModalProps> = ({
  show,
  onClose,
  provider,
  viewMode,
  accountList,
  onSelectAccount,
}) => {
  if (!show) return null;

  const formatCurrency = (value: number | undefined) => {
    if (value === null || value === undefined || isNaN(value)) return '$0.00';
    return new Intl.NumberFormat('en-US', {
      style: 'currency',
      currency: 'USD',
      maximumFractionDigits: 0,
    }).format(value);
  };

  const formatNumber = (value: number | undefined) => {
    if (value === null || value === undefined || isNaN(value)) return '0';
    return new Intl.NumberFormat('en-US', {
      notation: 'compact',
      compactDisplay: 'short',
    }).format(value);
  };

  return createPortal(
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4" aria-labelledby="modal-title" role="dialog" aria-modal="true">
      <div className="fixed inset-0 bg-gray-500 bg-opacity-75 transition-opacity" onClick={onClose}></div>
      <div className="relative bg-white rounded-xl text-left shadow-2xl transform transition-all w-full max-w-2xl border border-gray-200 overflow-y-auto max-h-[90vh]">
        <div className="bg-white px-4 pt-5 pb-4 sm:p-6 sm:pb-4">
          <div className="flex justify-between items-center mb-4 border-b border-gray-100 pb-3">
            <h3 className="text-lg leading-6 font-bold text-gray-900 flex items-center gap-2" id="modal-title">
              <i className="fas fa-layer-group text-indigo-600"></i>
              <span>{provider} Accounts Breakdown</span>
            </h3>
            <button
              onClick={onClose}
              className="text-gray-400 hover:text-gray-500 transition-colors w-8 h-8 flex items-center justify-center rounded-full hover:bg-gray-100"
            >
              <i className="fas fa-times"></i>
            </button>
          </div>
          <div className="mt-2 max-h-[60vh] overflow-y-auto custom-scrollbar">
            <table className="min-w-full divide-y divide-gray-200">
              <thead className="bg-gray-50 sticky top-0">
                <tr>
                  <th className="px-6 py-3 text-left text-xs font-bold text-gray-500 uppercase tracking-wider">
                    Account / ID
                  </th>
                  {viewMode === 'cost' && (
                    <>
                      <th className="px-6 py-3 text-right text-xs font-bold text-gray-500 uppercase tracking-wider">
                        Spend (MTD)
                      </th>
                      <th className="px-6 py-3 text-right text-xs font-bold text-gray-500 uppercase tracking-wider">
                        Forecast
                      </th>
                    </>
                  )}
                  {viewMode === 'resource' && (
                    <th className="px-6 py-3 text-right text-xs font-bold text-gray-500 uppercase tracking-wider">
                      Resources
                    </th>
                  )}
                  {viewMode === 'waste' && (
                    <th className="px-6 py-3 text-right text-xs font-bold text-gray-500 uppercase tracking-wider">
                      Waste Items
                    </th>
                  )}
                  {viewMode === 'alerts' && (
                    <th className="px-6 py-3 text-right text-xs font-bold text-gray-500 uppercase tracking-wider">
                      Active Alerts
                    </th>
                  )}
                  {viewMode === 'savings' && (
                    <th className="px-6 py-3 text-right text-xs font-bold text-gray-500 uppercase tracking-wider">
                      Potential Savings
                    </th>
                  )}
                  <th className="px-6 py-3"></th>
                </tr>
              </thead>
              <tbody className="bg-white divide-y divide-gray-100">
                {accountList && accountList.length > 0 ? (
                  accountList.map((acc) => (
                    <tr key={acc.id} className="hover:bg-indigo-50/50 transition-colors">
                      <td className="px-6 py-4 whitespace-nowrap">
                        <div className="text-sm font-semibold text-gray-900">{acc.name}</div>
                        <div className="text-xs text-gray-500 font-mono">{acc.id}</div>
                      </td>

                      {viewMode === 'cost' && (
                        <>
                          <td className="px-6 py-4 whitespace-nowrap text-right text-sm font-bold text-gray-900">
                            {formatCurrency(acc.monthToDateSpend)}
                          </td>
                          <td className="px-6 py-4 whitespace-nowrap text-right text-sm text-gray-500">
                            {formatCurrency(acc.forecastedSpend)}
                          </td>
                        </>
                      )}

                      {viewMode === 'resource' && (
                        <td className="px-6 py-4 whitespace-nowrap text-right text-sm font-bold text-gray-900">
                          {formatNumber(acc.resourceInventory?.totalCount || acc.resourceCount || 0)}
                        </td>
                      )}

                      {viewMode === 'waste' && (
                        <td className="px-6 py-4 whitespace-nowrap text-right text-sm font-bold text-orange-600">
                          {acc.optimizationSummary?.criticalAlerts || 0}
                        </td>
                      )}

                      {viewMode === 'alerts' && (
                        <td className="px-6 py-4 whitespace-nowrap text-right text-sm font-bold text-red-600">
                          {acc.optimizationSummary?.criticalAlerts || 0}
                        </td>
                      )}

                      {viewMode === 'savings' && (
                        <td className="px-6 py-4 whitespace-nowrap text-right text-sm font-bold text-green-600">
                          {formatCurrency(acc.optimizationSummary?.totalPotentialSavings || 0)}
                        </td>
                      )}

                      <td className="px-6 py-4 text-right whitespace-nowrap">
                        <button
                          onClick={() => onSelectAccount(acc.id, viewMode)}
                          className="text-indigo-600 hover:text-indigo-900 text-xs font-bold border border-indigo-100 px-3 py-1 rounded hover:bg-indigo-50 transition-colors"
                        >
                          Select
                        </button>
                      </td>
                    </tr>
                  ))
                ) : (
                  <tr>
                    <td colSpan={6} className="px-6 py-10 text-center text-gray-500">
                      No account data available.
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>
        </div>
      </div>
    </div>,
    document.body
  );
};
