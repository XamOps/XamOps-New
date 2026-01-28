/**
 * FinOps Insights Component
 *
 * Displays cost anomalies, rightsizing recommendations, and wasted resources
 */

import React from 'react';
import type {
  CostAnomaly,
  RightsizingRecommendation,
  WastedResource,
  PaginationState,
} from '../../types/finops';
import Pagination from './Pagination';

interface FinOpsInsightsProps {
  costAnomalies: CostAnomaly[];
  rightsizingRecommendations: RightsizingRecommendation[];
  wastedResources: WastedResource[];
  rightsizingPagination: PaginationState;
  wastedPagination: PaginationState;
  onRightsizingPaginationChange: (updates: Partial<PaginationState>) => void;
  onWastedPaginationChange: (updates: Partial<PaginationState>) => void;
}

const FinOpsInsights: React.FC<FinOpsInsightsProps> = ({
  costAnomalies,
  rightsizingRecommendations,
  wastedResources,
  rightsizingPagination,
  wastedPagination,
  onRightsizingPaginationChange,
  onWastedPaginationChange,
}) => {
  const formatCurrency = (value: number): string => {
    return value.toLocaleString('en-US', {
      style: 'currency',
      currency: 'USD',
    });
  };

  // Rightsizing pagination
  const rightsizingTotalPages = Math.ceil(rightsizingRecommendations.length / rightsizingPagination.itemsPerPage);
  const rightsizingStart = (rightsizingPagination.currentPage - 1) * rightsizingPagination.itemsPerPage;
  const rightsizingEnd = rightsizingStart + rightsizingPagination.itemsPerPage;
  const paginatedRightsizing = rightsizingRecommendations.slice(rightsizingStart, rightsizingEnd);

  // Wasted resources pagination
  const wastedTotalPages = Math.ceil(wastedResources.length / wastedPagination.itemsPerPage);
  const wastedStart = (wastedPagination.currentPage - 1) * wastedPagination.itemsPerPage;
  const wastedEnd = wastedStart + wastedPagination.itemsPerPage;
  const paginatedWasted = wastedResources.slice(wastedStart, wastedEnd);

  return (
    <div className="bg-white/95 backdrop-blur border border-gray-200 rounded-2xl p-8 shadow-md fade-in">
      <div className="flex items-center space-x-3 mb-6">
        <div className="w-10 h-10 bg-gradient-to-br from-yellow-500 to-orange-600 rounded-xl flex items-center justify-center shadow-md">
          <i className="fas fa-lightbulb text-white"></i>
        </div>
        <div>
          <h3 className="text-xl font-bold text-gray-800">Insights & Opportunities</h3>
          <p className="text-sm text-gray-600">Optimize your cloud spending</p>
        </div>
      </div>

      {/* Cost Anomalies */}
      {costAnomalies.length > 0 && (
        <div className="mb-8">
          <h4 className="text-lg font-semibold text-gray-800 mb-4 flex items-center">
            <i className="fas fa-exclamation-triangle text-red-500 mr-2"></i>
            Cost Anomaly Detection
          </h4>
          <div className="overflow-x-auto rounded-lg border border-gray-200">
            <table className="w-full text-sm text-left text-gray-600">
              <thead className="bg-gray-50 text-xs text-gray-700 uppercase font-semibold">
                <tr>
                  <th className="px-6 py-4">Service</th>
                  <th className="px-6 py-4">Date Range</th>
                  <th className="px-6 py-4">Unexpected Spend</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-200">
                {costAnomalies.map((anomaly) => (
                  <tr key={anomaly.anomalyId} className="hover:bg-gray-50">
                    <td className="px-6 py-4 font-semibold">{anomaly.service}</td>
                    <td className="px-6 py-4">{`${anomaly.startDate} to ${anomaly.endDate}`}</td>
                    <td className="px-6 py-4 text-red-600 font-bold">
                      {formatCurrency(anomaly.unexpectedSpend)}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {/* Rightsizing Recommendations */}
      {rightsizingRecommendations.length > 0 && (
        <div className="mb-8">
          <div className="flex items-center justify-between mb-4">
            <h4 className="text-lg font-semibold text-gray-800 flex items-center">
              <i className="fas fa-cogs text-green-500 mr-2"></i>
              Rightsizing Recommendations
              <span className="ml-2 px-2 py-0.5 text-xs font-medium bg-green-100 text-green-600 rounded-full">
                {rightsizingRecommendations.length} total
              </span>
            </h4>
            <div className="flex items-center space-x-2">
              <label htmlFor="rightsizingItemsPerPage" className="text-sm text-gray-600">
                Show:
              </label>
              <select
                id="rightsizingItemsPerPage"
                value={rightsizingPagination.itemsPerPage}
                onChange={(e) =>
                  onRightsizingPaginationChange({ itemsPerPage: Number(e.target.value), currentPage: 1 })
                }
                className="px-3 py-1.5 text-sm border border-gray-300 rounded-lg focus:ring-2 focus:ring-indigo-200 focus:border-indigo-500"
              >
                <option value="10">10</option>
                <option value="25">25</option>
                <option value="50">50</option>
                <option value="100">100</option>
              </select>
            </div>
          </div>
          <div className="overflow-x-auto rounded-lg border border-gray-200">
            <table className="w-full text-sm text-left text-gray-600">
              <thead className="bg-gray-50 text-xs text-gray-700 uppercase font-semibold">
                <tr>
                  <th className="px-6 py-4">Resource ID</th>
                  <th className="px-6 py-4">Service</th>
                  <th className="px-6 py-4">Current</th>
                  <th className="px-6 py-4">Recommended</th>
                  <th className="px-6 py-4">Savings</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-200">
                {paginatedRightsizing.map((rec) => (
                  <tr key={rec.resourceId} className="hover:bg-gray-50">
                    <td className="px-6 py-4 font-mono text-xs text-gray-800">{rec.resourceId}</td>
                    <td className="px-6 py-4">{rec.service}</td>
                    <td className="px-6 py-4">{rec.currentType}</td>
                    <td className="px-6 py-4 text-green-700 font-semibold">{rec.recommendedType}</td>
                    <td className="px-6 py-4 text-green-700 font-bold">
                      {formatCurrency(rec.estimatedMonthlySavings)}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <Pagination
            currentPage={rightsizingPagination.currentPage}
            totalPages={rightsizingTotalPages}
            totalItems={rightsizingRecommendations.length}
            itemsPerPage={rightsizingPagination.itemsPerPage}
            onPageChange={(page) => onRightsizingPaginationChange({ currentPage: page })}
          />
        </div>
      )}

      {/* Wasted Resources */}
      {wastedResources.length > 0 && (
        <div>
          <div className="flex items-center justify-between mb-4">
            <h4 className="text-lg font-semibold text-gray-800 flex items-center">
              <i className="fas fa-trash-alt text-red-500 mr-2"></i>
              Wasted Resources
              <span className="ml-2 px-2 py-0.5 text-xs font-medium bg-red-100 text-red-600 rounded-full">
                {wastedResources.length} total
              </span>
            </h4>
            <div className="flex items-center space-x-2">
              <label htmlFor="wastedItemsPerPage" className="text-sm text-gray-600">
                Show:
              </label>
              <select
                id="wastedItemsPerPage"
                value={wastedPagination.itemsPerPage}
                onChange={(e) =>
                  onWastedPaginationChange({ itemsPerPage: Number(e.target.value), currentPage: 1 })
                }
                className="px-3 py-1.5 text-sm border border-gray-300 rounded-lg focus:ring-2 focus:ring-indigo-200 focus:border-indigo-500"
              >
                <option value="10">10</option>
                <option value="25">25</option>
                <option value="50">50</option>
                <option value="100">100</option>
              </select>
            </div>
          </div>
          <div className="overflow-x-auto rounded-lg border border-gray-200">
            <table className="w-full text-sm text-left text-gray-600">
              <thead className="bg-gray-50 text-xs text-gray-700 uppercase font-semibold">
                <tr>
                  <th className="px-6 py-4">Resource ID</th>
                  <th className="px-6 py-4">Type</th>
                  <th className="px-6 py-4">Region</th>
                  <th className="px-6 py-4">Reason</th>
                  <th className="px-6 py-4">Savings</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-200">
                {paginatedWasted.map((res) => (
                  <tr key={res.resourceId} className="hover:bg-gray-50">
                    <td className="px-6 py-4 font-mono text-xs text-gray-800">{res.resourceId}</td>
                    <td className="px-6 py-4">{res.resourceType}</td>
                    <td className="px-6 py-4">{res.region}</td>
                    <td className="px-6 py-4">{res.reason}</td>
                    <td className="px-6 py-4 text-red-600 font-bold">{formatCurrency(res.monthlySavings)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <Pagination
            currentPage={wastedPagination.currentPage}
            totalPages={wastedTotalPages}
            totalItems={wastedResources.length}
            itemsPerPage={wastedPagination.itemsPerPage}
            onPageChange={(page) => onWastedPaginationChange({ currentPage: page })}
          />
        </div>
      )}
    </div>
  );
};

export default FinOpsInsights;
