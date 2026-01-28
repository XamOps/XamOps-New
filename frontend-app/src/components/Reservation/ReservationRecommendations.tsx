/**
 * ReservationRecommendations Component
 *
 * Purchase recommendations with filters and data table
 *
 * Converted from: reservation.html (recommendations tab)
 */

import React from 'react';
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
            <h3 className="text-lg font-black text-gray-900 flex items-center gap-2">
              <i className="fas fa-sliders-h text-purple-600"></i>
              Recommendation Parameters
            </h3>
            <p className="text-xs text-gray-500 mt-1 font-medium">
              Customize your purchase recommendations
            </p>
          </div>
          {isLoading && (
            <div className="flex items-center gap-2 px-4 py-2 bg-indigo-50 rounded-lg">
              <i className="fas fa-circle-notch fa-spin text-indigo-600"></i>
              <span className="text-sm font-semibold text-indigo-700">Recalculating...</span>
            </div>
          )}
        </div>

        <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
          {/* Term Length */}
          <div>
            <label className="block text-xs font-bold text-gray-600 uppercase tracking-wider mb-2">
              <i className="fas fa-calendar-alt text-indigo-500 text-[10px] mr-1"></i>
              Term Length
            </label>
            <select
              value={filters.term}
              onChange={(e) => {
                handleFilterUpdate('term', e.target.value);
                onFetchRecommendations();
              }}
              className="block w-full text-sm rounded-lg border-2 border-gray-200 bg-white shadow-sm hover:border-indigo-400 focus:border-indigo-500 focus:ring-2 focus:ring-indigo-200 py-2.5 font-medium transition-all"
            >
              <option value="ONE_YEAR">1 Year</option>
              <option value="THREE_YEARS">3 Years</option>
            </select>
          </div>

          {/* Payment Option */}
          <div>
            <label className="block text-xs font-bold text-gray-600 uppercase tracking-wider mb-2">
              <i className="fas fa-credit-card text-purple-500 text-[10px] mr-1"></i>
              Payment Option
            </label>
            <select
              value={filters.paymentOption}
              onChange={(e) => {
                handleFilterUpdate('paymentOption', e.target.value);
                onFetchRecommendations();
              }}
              className="block w-full text-sm rounded-lg border-2 border-gray-200 bg-white shadow-sm hover:border-indigo-400 focus:border-indigo-500 focus:ring-2 focus:ring-indigo-200 py-2.5 font-medium transition-all"
            >
              <option value="NO_UPFRONT">No Upfront</option>
              <option value="PARTIAL_UPFRONT">Partial Upfront</option>
              <option value="ALL_UPFRONT">All Upfront</option>
            </select>
          </div>

          {/* Lookback Period */}
          <div>
            <label className="block text-xs font-bold text-gray-600 uppercase tracking-wider mb-2">
              <i className="fas fa-history text-blue-500 text-[10px] mr-1"></i>
              Lookback Period
            </label>
            <select
              value={filters.lookback}
              onChange={(e) => {
                handleFilterUpdate('lookback', e.target.value);
                onFetchRecommendations();
              }}
              className="block w-full text-sm rounded-lg border-2 border-gray-200 bg-white shadow-sm hover:border-indigo-400 focus:border-indigo-500 focus:ring-2 focus:ring-indigo-200 py-2.5 font-medium transition-all"
            >
              <option value="SEVEN_DAYS">Past 7 Days</option>
              <option value="THIRTY_DAYS">Past 30 Days</option>
              <option value="SIXTY_DAYS">Past 60 Days</option>
            </select>
          </div>

          {/* Offering Class */}
          <div>
            <label className="block text-xs font-bold text-gray-600 uppercase tracking-wider mb-2">
              <i className="fas fa-tag text-green-500 text-[10px] mr-1"></i>
              Offering Class
            </label>
            <select
              value={filters.offeringClass}
              onChange={(e) => {
                handleFilterUpdate('offeringClass', e.target.value);
                onFetchRecommendations();
              }}
              className="block w-full text-sm rounded-lg border-2 border-gray-200 bg-white shadow-sm hover:border-indigo-400 focus:border-indigo-500 focus:ring-2 focus:ring-indigo-200 py-2.5 font-medium transition-all"
            >
              <option value="STANDARD">Standard</option>
              <option value="CONVERTIBLE">Convertible</option>
            </select>
          </div>
        </div>
      </div>

      {/* Recommendations Table */}
      <div className="glass-card rounded-xl overflow-hidden border-t-4 border-t-green-500">
        <div className="px-6 py-5 border-b border-gray-100 bg-gradient-to-r from-gray-50/50 to-white">
          <h3 className="text-lg font-black text-gray-900 flex items-center gap-2">
            <i className="fas fa-lightbulb text-green-600"></i>
            Purchase Recommendations
          </h3>
          <p className="text-xs text-gray-500 mt-1 font-medium">
            Optimized reservation purchase suggestions
          </p>
        </div>
        <table className="w-full text-sm text-left text-slate-500 pro-table">
          <thead>
            <tr>
              <th className="px-6 py-3">Instance Family</th>
              <th className="px-6 py-3">Region</th>
              <th className="px-6 py-3">Platform</th>
              <th className="px-6 py-3">Terms</th>
              <th className="px-6 py-3">On-Demand Cost</th>
              <th className="px-6 py-3">Est. Monthly Cost</th>
              <th className="px-6 py-3">Potential Savings</th>
            </tr>
          </thead>
          <tbody>
            {isLoading ? (
              <tr>
                <td colSpan={7} className="text-center py-16">
                  <div className="flex flex-col items-center gap-3">
                    <div className="loader"></div>
                    <div className="text-gray-600 font-semibold">Analyzing usage patterns...</div>
                    <div className="text-gray-400 text-xs">This may take a moment</div>
                  </div>
                </td>
              </tr>
            ) : !isLoading && recommendations && recommendations.length > 0 ? (
              recommendations.map((rec) => (
                <tr key={`${rec.instanceFamily}-${rec.region}`}>
                  <td className="px-6 py-4">
                    <div className="font-bold text-gray-800 text-base">{rec.instanceFamily}</div>
                    <div className="text-xs text-gray-500 mt-1 flex items-center gap-1">
                      <i className="fas fa-server text-indigo-500 text-[10px]"></i>
                      <span>{rec.recommendedInstances} instances</span>
                    </div>
                  </td>
                  <td className="px-6 py-4 font-medium text-gray-700">{rec.region}</td>
                  <td className="px-6 py-4 font-medium text-gray-700">{rec.platform}</td>
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
                  <td className="px-6 py-4 line-through text-gray-400 font-medium">
                    {formatCurrency(rec.onDemandCost, false)}
                  </td>
                  <td className="px-6 py-4 font-bold text-gray-900 text-base">
                    {formatCurrency(rec.estimatedMonthlyCost, false)}
                  </td>
                  <td className="px-6 py-4">
                    <div className="flex items-center gap-2">
                      <i className="fas fa-arrow-down text-green-600"></i>
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
                    <div className="w-20 h-20 bg-gradient-to-br from-green-100 to-emerald-100 rounded-full flex items-center justify-center mb-4 shadow-md">
                      <i className="fas fa-check text-green-600 text-3xl"></i>
                    </div>
                    <h3 className="text-gray-900 font-black text-lg">Fully Optimized</h3>
                    <p className="text-gray-500 text-sm mt-2 max-w-md text-center font-medium">
                      Great news! We couldn't find any additional reservation opportunities based on
                      your current usage patterns and filter settings.
                    </p>
                    <div className="mt-4 text-xs text-gray-400">
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
