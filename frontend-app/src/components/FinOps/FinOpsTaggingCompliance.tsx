/**
 * FinOps Tagging Compliance Component
 *
 * Displays tagging compliance metrics and untagged resources table with pagination
 */

import React from 'react';
import { Icon } from '@iconify/react';
import type { TaggingCompliance, PaginationState } from '../../types/finops';
import Pagination from './Pagination';

interface FinOpsTaggingComplianceProps {
  taggingCompliance: TaggingCompliance;
  pagination: PaginationState;
  onPaginationChange: (updates: Partial<PaginationState>) => void;
}

const FinOpsTaggingCompliance: React.FC<FinOpsTaggingComplianceProps> = ({
  taggingCompliance,
  pagination,
  onPaginationChange,
}) => {
  const compliancePercentage = taggingCompliance?.compliancePercentage || 0;
  const totalResourcesScanned = taggingCompliance?.totalResourcesScanned || 0;
  const untaggedResourcesCount = taggingCompliance?.untaggedResourcesCount || 0;
  const untaggedResources = taggingCompliance?.untaggedResources || [];

  // Pagination
  const totalPages = Math.ceil(untaggedResourcesCount / pagination.itemsPerPage);
  const start = (pagination.currentPage - 1) * pagination.itemsPerPage;
  const end = start + pagination.itemsPerPage;
  const paginatedResources = untaggedResources.slice(start, end);

  const complianceColor =
    compliancePercentage >= 80
      ? { text: '#158045', bar: '#16a34a', bg: '#f0fdf4', icon: '#158045' }
      : compliancePercentage >= 50
      ? { text: '#92400e', bar: '#f59e0b', bg: '#fffbeb', icon: '#d97706' }
      : { text: '#991b1b', bar: '#dc2626', bg: '#fef2f2', icon: '#dc2626' };

  return (
    <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-5">
      {/* Section header */}
      <div className="flex items-center gap-2 pb-4 border-b border-[rgba(0,0,0,0.06)] mb-5">
        <div className="w-7 h-7 flex items-center justify-center rounded-md border border-[rgba(0,0,0,0.06)]">
          <Icon icon="mdi:tag-multiple-outline" width={16} color="#0EA5E9" />
        </div>
        <div>
          <p className="text-[15px] font-semibold text-[rgba(0,0,0,0.88)]">Tagging Compliance</p>
          <p className="text-[11px] text-[rgba(0,0,0,0.4)] flex items-center gap-1.5 mt-0.5">
            <span className="w-1.5 h-1.5 rounded-full bg-sky-400 animate-pulse inline-block"></span>
            Monitor resource tagging across your infrastructure
          </p>
        </div>
      </div>

      {/* Compliance KPIs */}
      <div className="grid grid-cols-1 md:grid-cols-3 gap-4 mb-6">
        {/* Compliance Percentage */}
        <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl p-4">
          <div className="flex items-start justify-between">
            <div>
              <p className="text-[10px] text-[rgba(0,0,0,0.35)] uppercase tracking-wide font-semibold">
                Compliance Rate
              </p>
              <p
                className="text-[28px] font-extrabold mt-1 leading-tight"
                style={{
                  fontFamily: "'Barlow Condensed', 'Inter', sans-serif",
                  color: complianceColor.text,
                }}
              >
                {compliancePercentage.toFixed(1)}%
              </p>
              <div className="mt-2 bg-[rgba(0,0,0,0.06)] rounded-full h-1.5 overflow-hidden w-24">
                <div
                  className="h-1.5 rounded-full transition-all duration-500"
                  style={{
                    width: `${Math.min(compliancePercentage, 100)}%`,
                    backgroundColor: complianceColor.bar,
                  }}
                />
              </div>
            </div>
            <div
              className="w-10 h-10 rounded-xl flex items-center justify-center flex-shrink-0"
              style={{ backgroundColor: complianceColor.bg }}
            >
              <Icon icon="mdi:percent" width={20} color={complianceColor.icon} />
            </div>
          </div>
        </div>

        {/* Total Resources Scanned */}
        <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl p-4">
          <div className="flex items-start justify-between">
            <div>
              <p className="text-[10px] text-[rgba(0,0,0,0.35)] uppercase tracking-wide font-semibold">
                Resources Scanned
              </p>
              <p
                className="text-[28px] font-extrabold text-[rgba(0,0,0,0.88)] mt-1 leading-tight"
                style={{ fontFamily: "'Barlow Condensed', 'Inter', sans-serif" }}
              >
                {totalResourcesScanned.toLocaleString()}
              </p>
              <p className="text-[11px] text-[rgba(0,0,0,0.4)] mt-1">Total resources analyzed</p>
            </div>
            <div
              className="w-10 h-10 rounded-xl flex items-center justify-center flex-shrink-0"
              style={{ backgroundColor: '#4285F414' }}
            >
              <Icon icon="mdi:server-outline" width={20} color="#4285F4" />
            </div>
          </div>
        </div>

        {/* Untagged Resources */}
        <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl p-4">
          <div className="flex items-start justify-between">
            <div>
              <p className="text-[10px] text-[rgba(0,0,0,0.35)] uppercase tracking-wide font-semibold">
                Untagged Resources
              </p>
              <p
                className={`text-[28px] font-extrabold mt-1 leading-tight`}
                style={{
                  fontFamily: "'Barlow Condensed', 'Inter', sans-serif",
                  color: untaggedResourcesCount === 0 ? '#158045' : '#dc2626',
                }}
              >
                {untaggedResourcesCount.toLocaleString()}
              </p>
              <p className="text-[11px] text-[rgba(0,0,0,0.4)] mt-1">Missing required tags</p>
            </div>
            <div
              className="w-10 h-10 rounded-xl flex items-center justify-center flex-shrink-0"
              style={{ backgroundColor: untaggedResourcesCount === 0 ? '#f0fdf4' : '#fef2f2' }}
            >
              <Icon
                icon={untaggedResourcesCount === 0 ? 'mdi:check-circle-outline' : 'mdi:alert-circle-outline'}
                width={20}
                color={untaggedResourcesCount === 0 ? '#158045' : '#dc2626'}
              />
            </div>
          </div>
        </div>
      </div>

      {/* Untagged Resources Table */}
      {untaggedResources.length > 0 && (
        <div>
          <div className="flex items-center justify-between mb-3">
            <div className="flex items-center gap-2">
              <span className="w-2 h-2 rounded-full bg-red-500 inline-block"></span>
              <h4 className="text-[13px] font-semibold text-[rgba(0,0,0,0.88)]">
                Untagged Resources Details
              </h4>
              <span className="px-2 py-0.5 text-[11px] font-medium bg-red-50 text-red-600 border border-red-100 rounded-full">
                {untaggedResourcesCount} total
              </span>
            </div>
            <div className="flex items-center gap-2">
              <label htmlFor="taggingItemsPerPage" className="text-[12px] text-[rgba(0,0,0,0.45)]">
                Show:
              </label>
              <select
                id="taggingItemsPerPage"
                value={pagination.itemsPerPage}
                onChange={(e) =>
                  onPaginationChange({ itemsPerPage: Number(e.target.value), currentPage: 1 })
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
                  <th className="text-left px-4 py-2.5 text-[12px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.6)]">Status</th>
                </tr>
              </thead>
              <tbody>
                {paginatedResources.map((resource) => (
                  <tr key={resource.resourceId} className="border-b border-[rgba(0,0,0,0.04)] hover:bg-[rgba(0,0,0,0.02)]">
                    <td className="px-4 py-3 font-mono text-[12px] text-[rgba(0,0,0,0.88)]">{resource.resourceId}</td>
                    <td className="px-4 py-3">
                      <span className="px-2 py-0.5 text-[11px] font-medium bg-indigo-50 text-indigo-700 border border-indigo-100 rounded-full">
                        {resource.resourceType}
                      </span>
                    </td>
                    <td className="px-4 py-3 text-[14px] text-[rgba(0,0,0,0.88)]">{resource.region}</td>
                    <td className="px-4 py-3">
                      <span className="px-2 py-0.5 text-[11px] font-medium bg-red-50 text-red-600 border border-red-100 rounded-full inline-flex items-center gap-1">
                        <Icon icon="mdi:close-circle-outline" width={12} />
                        Missing
                      </span>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <Pagination
            currentPage={pagination.currentPage}
            totalPages={totalPages}
            totalItems={untaggedResourcesCount}
            itemsPerPage={pagination.itemsPerPage}
            onPageChange={(page) => onPaginationChange({ currentPage: page })}
          />
        </div>
      )}

      {/* Empty State — All Tagged */}
      {untaggedResources.length === 0 && totalResourcesScanned > 0 && (
        <div className="text-center py-10 rounded-xl border-2 border-dashed border-green-200">
          <div className="w-12 h-12 bg-green-50 rounded-full flex items-center justify-center mx-auto mb-3">
            <Icon icon="mdi:check-circle-outline" width={24} color="#158045" />
          </div>
          <p className="text-[14px] font-semibold text-[rgba(0,0,0,0.88)] mb-1">All Resources Tagged</p>
          <p className="text-[13px] text-[rgba(0,0,0,0.45)]">
            All scanned resources have the required tags. Great job!
          </p>
        </div>
      )}

      {/* No Data State */}
      {totalResourcesScanned === 0 && (
        <div className="text-center py-10 rounded-xl border-2 border-dashed border-[rgba(0,0,0,0.1)]">
          <div className="w-12 h-12 bg-[rgba(0,0,0,0.04)] rounded-full flex items-center justify-center mx-auto mb-3">
            <Icon icon="mdi:tag-multiple-outline" width={22} color="rgba(0,0,0,0.3)" />
          </div>
          <p className="text-[14px] font-semibold text-[rgba(0,0,0,0.88)] mb-1">No Tagging Data Available</p>
          <p className="text-[13px] text-[rgba(0,0,0,0.45)]">
            Tagging compliance data will appear here once resources are scanned.
          </p>
        </div>
      )}
    </div>
  );
};

export default FinOpsTaggingCompliance;
