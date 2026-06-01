/**
 * ReservationRecommendations Component
 *
 * Purchase recommendations with filters and data table
 *
 * Converted from: reservation.html (recommendations tab)
 */

import React from 'react';
import { Icon } from '@iconify/react';
import type { PurchaseRecommendation, RecommendationFilters } from '../../types/reservation';

interface ReservationRecommendationsProps {
  recommendations: PurchaseRecommendation[];
  filters: RecommendationFilters;
  isLoading: boolean;
  onFilterChange: (filters: RecommendationFilters) => void;
  onFetchRecommendations: () => void;
}

const ReservationRecommendations: React.FC<ReservationRecommendationsProps> = ({
  recommendations,
  filters,
  isLoading,
  onFilterChange,
  onFetchRecommendations,
}) => {
  const formatCurrency = (val: string, isHourly = true): string => {
    if (!val) return 'N/A';
    const numStr = String(val).replace(/[^0-9.]/g, '');
    const num = parseFloat(numStr);
    if (isNaN(num)) return val;
    const decimals = isHourly && num < 1 ? 4 : 2;
    return (
      '$' +
      num.toLocaleString(undefined, {
        minimumFractionDigits: decimals,
        maximumFractionDigits: decimals,
      }) +
      (isHourly ? '/hr' : '')
    );
  };

  const handleFilterUpdate = (key: keyof RecommendationFilters, value: string) => {
    const updatedFilters = { ...filters, [key]: value };
    onFilterChange(updatedFilters);
  };

  return (
    <>
      {/* Recommendation Parameters */}
      <div className="glass-card rounded-xl p-5 mb-6 border-t-4 border-t-purple-500">
        <div className="flex items-center justify-between mb-4">
          <div>
            <h3 className="text-lg font-black text-[rgba(0,0,0,0.88)] flex items-center gap-2">
              <Icon icon="mdi:tune-variant" className="text-purple-600" width={20} />
              Recommendation Parameters
            </h3>
            <p className="text-xs text-[rgba(0,0,0,0.5)] mt-1 font-medium">
              Customize your purchase recommendations
            </p>
          </div>
          {isLoading && (
            <div className="flex items-center gap-2 px-4 py-2 bg-indigo-50 rounded-lg">
              <Icon icon="mdi:loading" className="animate-spin text-indigo-600" width={16} />
              <span className="text-sm font-semibold text-indigo-700">Recalculating...</span>
            </div>
          )}
        </div>

        <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
          {/* Term Length */}
          <div>
            <label className="block text-xs font-bold text-[rgba(0,0,0,0.6)] uppercase tracking-wider mb-2">
              <Icon icon="mdi:calendar-outline" className="text-indigo-500 inline mr-1" width={10} />
              Term Length
            </label>
            <select
              value={filters.term}
              onChange={(e) => {
                handleFilterUpdate('term', e.target.value);
                onFetchRecommendations();
              }}
              className="block w-full text-sm rounded-md bg-[rgba(0,0,0,0.04)] border border-[rgba(0,0,0,0.08)] py-2.5 font-medium transition-all focus:outline-none focus:border-[rgba(0,0,0,0.2)]"
            >
              <option value="ONE_YEAR">1 Year</option>
              <option value="THREE_YEARS">3 Years</option>
            </select>
          </div>

          {/* Payment Option */}
          <div>
            <label className="block text-xs font-bold text-[rgba(0,0,0,0.6)] uppercase tracking-wider mb-2">
              <Icon icon="mdi:credit-card-outline" className="text-purple-500 inline mr-1" width={10} />
              Payment Option
            </label>
            <select
              value={filters.paymentOption}
              onChange={(e) => {
                handleFilterUpdate('paymentOption', e.target.value);
                onFetchRecommendations();
              }}
              className="block w-full text-sm rounded-md bg-[rgba(0,0,0,0.04)] border border-[rgba(0,0,0,0.08)] py-2.5 font-medium transition-all focus:outline-none focus:border-[rgba(0,0,0,0.2)]"
            >
              <option value="NO_UPFRONT">No Upfront</option>
              <option value="PARTIAL_UPFRONT">Partial Upfront</option>
              <option value="ALL_UPFRONT">All Upfront</option>
            </select>
          </div>

          {/* Lookback Period */}
          <div>
            <label className="block text-xs font-bold text-[rgba(0,0,0,0.6)] uppercase tracking-wider mb-2">
              <Icon icon="mdi:history" className="text-blue-500 inline mr-1" width={10} />
              Lookback Period
            </label>
            <select
              value={filters.lookback}
              onChange={(e) => {
                handleFilterUpdate('lookback', e.target.value);
                onFetchRecommendations();
              }}
              className="block w-full text-sm rounded-md bg-[rgba(0,0,0,0.04)] border border-[rgba(0,0,0,0.08)] py-2.5 font-medium transition-all focus:outline-none focus:border-[rgba(0,0,0,0.2)]"
            >
              <option value="SEVEN_DAYS">Past 7 Days</option>
              <option value="THIRTY_DAYS">Past 30 Days</option>
              <option value="SIXTY_DAYS">Past 60 Days</option>
            </select>
          </div>

          {/* Offering Class */}
          <div>
            <label className="block text-xs font-bold text-[rgba(0,0,0,0.6)] uppercase tracking-wider mb-2">
              <Icon icon="mdi:tag-outline" className="text-green-500 inline mr-1" width={10} />
              Offering Class
            </label>
            <select
              value={filters.offeringClass}
              onChange={(e) => {
                handleFilterUpdate('offeringClass', e.target.value);
                onFetchRecommendations();
              }}
              className="block w-full text-sm rounded-md bg-[rgba(0,0,0,0.04)] border border-[rgba(0,0,0,0.08)] py-2.5 font-medium transition-all focus:outline-none focus:border-[rgba(0,0,0,0.2)]"
            >
              <option value="STANDARD">Standard</option>
              <option value="CONVERTIBLE">Convertible</option>
            </select>
          </div>
        </div>
      </div>

      {/* Recommendations Table */}
      <div className="glass-card rounded-xl overflow-hidden border-t-4 border-t-green-500">
        <div className="px-6 py-5 border-b border-[rgba(0,0,0,0.06)] bg-gradient-to-r from-[rgba(0,0,0,0.025)] to-white">
          <h3 className="text-lg font-black text-[rgba(0,0,0,0.88)] flex items-center gap-2">
            <Icon icon="mdi:lightbulb-outline" className="text-green-600" width={20} />
            Purchase Recommendations
          </h3>
          <p className="text-xs text-[rgba(0,0,0,0.5)] mt-1 font-medium">
            Optimized reservation purchase suggestions
          </p>
        </div>
        <table className="w-full text-sm text-left text-slate-500 pro-table">
          <thead className="bg-[rgba(0,0,0,0.02)] border-b border-[rgba(0,0,0,0.06)]">
            <tr>
              <th className="px-6 py-3 text-[rgba(0,0,0,0.4)]">Instance Family</th>
              <th className="px-6 py-3 text-[rgba(0,0,0,0.4)]">Region</th>
              <th className="px-6 py-3 text-[rgba(0,0,0,0.4)]">Platform</th>
              <th className="px-6 py-3 text-[rgba(0,0,0,0.4)]">Terms</th>
              <th className="px-6 py-3 text-[rgba(0,0,0,0.4)]">On-Demand Cost</th>
              <th className="px-6 py-3 text-[rgba(0,0,0,0.4)]">Est. Monthly Cost</th>
              <th className="px-6 py-3 text-[rgba(0,0,0,0.4)]">Potential Savings</th>
            </tr>
          </thead>
          <tbody className="divide-[rgba(0,0,0,0.04)]">
            {isLoading ? (
              <tr>
                <td colSpan={7} className="text-center py-16">
                  <div className="flex flex-col items-center gap-3">
                    <div className="loader"></div>
                    <div className="text-[rgba(0,0,0,0.6)] font-semibold">Analyzing usage patterns...</div>
                    <div className="text-[rgba(0,0,0,0.4)] text-xs">This may take a moment</div>
                  </div>
                </td>
              </tr>
            ) : !isLoading && recommendations && recommendations.length > 0 ? (
              recommendations.map((rec) => (
                <tr key={`${rec.instanceFamily}-${rec.region}`} className="hover:bg-[rgba(0,0,0,0.025)] border-b border-[rgba(0,0,0,0.04)]">
                  <td className="px-6 py-4">
                    <div className="font-bold text-[rgba(0,0,0,0.88)] text-base">{rec.instanceFamily}</div>
                    <div className="text-xs text-[rgba(0,0,0,0.5)] mt-1 flex items-center gap-1">
                      <Icon icon="mdi:server-outline" className="text-indigo-500" width={10} />
                      <span>{rec.recommendedInstances} instances</span>
                    </div>
                  </td>
                  <td className="px-6 py-4 font-medium text-[rgba(0,0,0,0.7)]">{rec.region}</td>
                  <td className="px-6 py-4 font-medium text-[rgba(0,0,0,0.7)]">{rec.platform}</td>
                  <td className="px-6 py-4">
                    <div className="text-xs font-semibold bg-gradient-to-br from-indigo-50 to-purple-50 text-indigo-700 rounded-lg px-3 py-1.5 inline-block border border-indigo-200">
                      <span>
                        {filters.term.replace('ONE_YEAR', '1 year').replace('THREE_YEARS', '3 years')}
                      </span>{' '}
                      /{' '}
                      <span>
                        {filters.paymentOption.replace(/_/g, ' ').toLowerCase()}
                      </span>
                    </div>
                  </td>
                  <td className="px-6 py-4 line-through text-[rgba(0,0,0,0.4)] font-medium">
                    {formatCurrency(rec.onDemandCost, false)}
                  </td>
                  <td className="px-6 py-4 font-bold text-[rgba(0,0,0,0.88)] text-base">
                    {formatCurrency(rec.estimatedMonthlyCost, false)}
                  </td>
                  <td className="px-6 py-4">
                    <div className="flex items-center gap-2">
                      <Icon icon="mdi:arrow-down" className="text-green-600" width={16} />
                      <div className="font-black text-green-600 text-base">
                        {formatCurrency(rec.monthlySavings, false)}
                      </div>
                    </div>
                    <div className="text-[10px] font-bold text-green-700 bg-gradient-to-br from-green-50 to-emerald-50 px-2 py-1 rounded-full inline-block mt-1 border border-green-200">
                      PER MONTH
                    </div>
                  </td>
                </tr>
              ))
            ) : (
              <tr>
                <td colSpan={7} className="text-center py-20">
                  <div className="flex flex-col items-center">
                    <div className="w-14 h-14 bg-[rgba(0,0,0,0.04)] border border-[rgba(0,0,0,0.08)] rounded-xl flex items-center justify-center mb-4 shadow-md">
                      <Icon icon="mdi:check" className="text-green-600" width={28} />
                    </div>
                    <h3 className="text-[rgba(0,0,0,0.88)] font-black text-lg">Fully Optimized</h3>
                    <p className="text-[rgba(0,0,0,0.5)] text-sm mt-2 max-w-md text-center font-medium">
                      Great news! We couldn't find any additional reservation opportunities based on
                      your current usage patterns and filter settings.
                    </p>
                    <div className="mt-4 text-xs text-[rgba(0,0,0,0.4)]">
                      Try adjusting filters to explore other scenarios
                    </div>
                  </div>
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
    </>
  );
};

export default ReservationRecommendations;
