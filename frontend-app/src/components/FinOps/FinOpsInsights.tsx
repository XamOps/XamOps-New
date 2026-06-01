/**
 * FinOps Insights Component
 *
 * Displays cost anomalies, rightsizing recommendations, and wasted resources
 */

import React from 'react';
import { Icon } from '@iconify/react';
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
    return value.toLocaleString('en-US', { style: 'currency', currency: 'USD' });
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
    <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-5">
      {/* Section header */}
      <div className="flex items-center gap-2 pb-4 border-b border-[rgba(0,0,0,0.06)] mb-5">
        <div className="w-7 h-7 flex items-center justify-center rounded-md border border-[rgba(0,0,0,0.06)]">
          <Icon icon="mdi:lightbulb-outline" width={16} color="#F4A429" />
        </div>
        <div>
          <p className="text-[15px] font-semibold text-[rgba(0,0,0,0.88)]">Insights & Opportunities</p>
          <p className="text-[11px] text-[rgba(0,0,0,0.4)] flex items-center gap-1.5 mt-0.5">
            <span className="w-1.5 h-1.5 rounded-full bg-yellow-400 animate-pulse inline-block"></span>
            Optimize your cloud spending
          </p>
        </div>
      </div>

      {/* Cost Anomalies */}
      {costAnomalies.length > 0 && (
        <div className="mb-7">
          <div className="flex items-center gap-2 mb-3">
            <span className="w-2 h-2 rounded-full bg-red-500 inline-block"></span>
            <h4 className="text-[13px] font-semibold text-[rgba(0,0,0,0.88)]">Cost Anomaly Detection</h4>
          </div>
          <div className="overflow-x-auto">
            <table className="w-full">
              <thead>
                <tr className="border-b border-[rgba(0,0,0,0.06)]">
                  <th className="text-left px-4 py-2.5 text-[12px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.6)]">Service</th>
                  <th className="text-left px-4 py-2.5 text-[12px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.6)]">Date Range</th>
                  <th className="text-left px-4 py-2.5 text-[12px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.6)]">Unexpected Spend</th>
                </tr>
              </thead>
              <tbody>
                {costAnomalies.map((anomaly) => (
                  <tr key={anomaly.anomalyId} className="border-b border-[rgba(0,0,0,0.04)] hover:bg-[rgba(0,0,0,0.02)]">
                    <td className="px-4 py-3 text-[14px] font-semibold text-[rgba(0,0,0,0.88)]">{anomaly.service}</td>
                    <td className="px-4 py-3 text-[14px] text-[rgba(0,0,0,0.88)]">{`${anomaly.startDate} to ${anomaly.endDate}`}</td>
                    <td className="px-4 py-3 text-red-600 font-semibold text-[14px]">
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
        <div className="mb-7">
          <div className="flex items-center justify-between mb-3">
            <div className="flex items-center gap-2">
              <span className="w-2 h-2 rounded-full bg-green-500 inline-block"></span>
              <h4 className="text-[13px] font-semibold text-[rgba(0,0,0,0.88)]">
                Rightsizing Recommendations
              </h4>
              <span className="px-2 py-0.5 text-[11px] font-medium bg-green-50 text-green-600 border border-green-100 rounded-full">
                {rightsizingRecommendations.length} total
              </span>
            </div>
            <div className="flex items-center gap-2">
              <label htmlFor="rightsizingItemsPerPage" className="text-[12px] text-[rgba(0,0,0,0.45)]">
                Show:
              </label>
              <select
                id="rightsizingItemsPerPage"
                value={rightsizingPagination.itemsPerPage}
                onChange={(e) =>
                  onRightsizingPaginationChange({ itemsPerPage: Number(e.target.value), currentPage: 1 })
                }
                className="px-2 py-1 text-[12px] border border-[rgba(0,0,0,0.12)] rounded-lg focus:ring-2 focus:ring-blue-200 focus:border-blue-500 outline-none"
              >
                <option value="10">10</option>
                <option value="25">25</option>
                <option value="50">50</option>
                <option value="100">100</option>
              </select>
            </div>
          </div>
          <div className="overflow-x-auto">
            <table className="w-full">
              <thead>
                <tr className="border-b border-[rgba(0,0,0,0.06)]">
                  <th className="text-left px-4 py-2.5 text-[12px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.6)]">Resource ID</th>
                  <th className="text-left px-4 py-2.5 text-[12px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.6)]">Service</th>
                  <th className="text-left px-4 py-2.5 text-[12px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.6)]">Current</th>
                  <th className="text-left px-4 py-2.5 text-[12px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.6)]">Recommended</th>
                  <th className="text-left px-4 py-2.5 text-[12px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.6)]">Savings</th>
                </tr>
              </thead>
              <tbody>
                {paginatedRightsizing.map((rec) => (
                  <tr key={rec.resourceId} className="border-b border-[rgba(0,0,0,0.04)] hover:bg-[rgba(0,0,0,0.02)]">
                    <td className="px-4 py-3 font-mono text-[12px] text-[rgba(0,0,0,0.88)]">{rec.resourceId}</td>
                    <td className="px-4 py-3 text-[14px] text-[rgba(0,0,0,0.88)]">{rec.service}</td>
                    <td className="px-4 py-3 text-[14px] text-[rgba(0,0,0,0.88)]">{rec.currentType}</td>
                    <td className="px-4 py-3 text-[14px] text-[#158045] font-semibold">{rec.recommendedType}</td>
                    <td className="px-4 py-3 text-[14px] text-[#158045] font-semibold">
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
          <div className="flex items-center justify-between mb-3">
            <div className="flex items-center gap-2">
              <span className="w-2 h-2 rounded-full bg-red-500 inline-block"></span>
              <h4 className="text-[13px] font-semibold text-[rgba(0,0,0,0.88)]">
                Wasted Resources
              </h4>
              <span className="px-2 py-0.5 text-[11px] font-medium bg-red-50 text-red-600 border border-red-100 rounded-full">
                {wastedResources.length} total
              </span>
            </div>
            <div className="flex items-center gap-2">
              <label htmlFor="wastedItemsPerPage" className="text-[12px] text-[rgba(0,0,0,0.45)]">
                Show:
              </label>
              <select
                id="wastedItemsPerPage"
                value={wastedPagination.itemsPerPage}
                onChange={(e) =>
                  onWastedPaginationChange({ itemsPerPage: Number(e.target.value), currentPage: 1 })
                }
                className="px-2 py-1 text-[12px] border border-[rgba(0,0,0,0.12)] rounded-lg focus:ring-2 focus:ring-blue-200 focus:border-blue-500 outline-none"
              >
                <option value="10">10</option>
                <option value="25">25</option>
                <option value="50">50</option>
                <option value="100">100</option>
              </select>
            </div>
          </div>
          <div className="overflow-x-auto">
            <table className="w-full">
              <thead>
                <tr className="border-b border-[rgba(0,0,0,0.06)]">
                  <th className="text-left px-4 py-2.5 text-[12px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.6)]">Resource ID</th>
                  <th className="text-left px-4 py-2.5 text-[12px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.6)]">Type</th>
                  <th className="text-left px-4 py-2.5 text-[12px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.6)]">Region</th>
                  <th className="text-left px-4 py-2.5 text-[12px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.6)]">Reason</th>
                  <th className="text-left px-4 py-2.5 text-[12px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.6)]">Savings</th>
                </tr>
              </thead>
              <tbody>
                {paginatedWasted.map((res) => (
                  <tr key={res.resourceId} className="border-b border-[rgba(0,0,0,0.04)] hover:bg-[rgba(0,0,0,0.02)]">
                    <td className="px-4 py-3 font-mono text-[12px] text-[rgba(0,0,0,0.88)]">{res.resourceId}</td>
                    <td className="px-4 py-3 text-[14px] text-[rgba(0,0,0,0.88)]">{res.resourceType}</td>
                    <td className="px-4 py-3 text-[14px] text-[rgba(0,0,0,0.88)]">{res.region}</td>
                    <td className="px-4 py-3 text-[14px] text-[rgba(0,0,0,0.88)]">{res.reason}</td>
                    <td className="px-4 py-3 text-red-600 font-semibold text-[14px]">{formatCurrency(res.monthlySavings)}</td>
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
