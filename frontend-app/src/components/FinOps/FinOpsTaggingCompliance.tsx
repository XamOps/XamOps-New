/**
 * FinOps Tagging Compliance Component
 *
 * Displays tagging compliance metrics and untagged resources table with pagination
 */

import React from 'react';
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

  return (
    <div className="bg-white/95 backdrop-blur border border-gray-200 rounded-2xl p-8 shadow-md fade-in">
      <div className="flex items-center space-x-3 mb-6">
        <div className="w-10 h-10 bg-gradient-to-br from-teal-500 to-cyan-600 rounded-xl flex items-center justify-center shadow-md">
          <i className="fas fa-tags text-white"></i>
        </div>
        <div>
          <h3 className="text-xl font-bold text-gray-800">Tagging Compliance</h3>
          <p className="text-sm text-gray-600">Monitor resource tagging across your infrastructure</p>
        </div>
      </div>

      {/* Compliance KPIs */}
      <div className="grid grid-cols-1 md:grid-cols-3 gap-6 mb-8">
        {/* Compliance Percentage */}
        <div className="bg-gray-50 border border-gray-200 rounded-xl p-6">
          <div className="flex items-center justify-between mb-4">
            <h4 className="text-sm font-medium text-gray-600">Compliance Rate</h4>
            <div
              className={`p-2 rounded-lg ${
                compliancePercentage >= 80
                  ? 'bg-green-100'
                  : compliancePercentage >= 50
                  ? 'bg-yellow-100'
                  : 'bg-red-100'
              }`}
            >
              <i
                className={`fas fa-percentage ${
                  compliancePercentage >= 80
                    ? 'text-green-500'
                    : compliancePercentage >= 50
                    ? 'text-yellow-500'
                    : 'text-red-500'
                }`}
              ></i>
            </div>
          </div>
          <p
            className={`text-3xl font-bold mb-2 ${
              compliancePercentage >= 80
                ? 'text-green-600'
                : compliancePercentage >= 50
                ? 'text-yellow-600'
                : 'text-red-600'
            }`}
          >
            {compliancePercentage.toFixed(1)}%
          </p>
          <div className="w-full bg-gray-200 rounded-full h-2.5 overflow-hidden">
            <div
              className={`h-2.5 rounded-full transition-all duration-500 ${
                compliancePercentage >= 80
                  ? 'bg-gradient-to-r from-green-400 to-green-600'
                  : compliancePercentage >= 50
                  ? 'bg-gradient-to-r from-yellow-400 to-orange-500'
                  : 'bg-gradient-to-r from-red-500 to-red-600'
              }`}
              style={{ width: `${Math.min(compliancePercentage, 100)}%` }}
            ></div>
          </div>
        </div>

        {/* Total Resources Scanned */}
        <div className="bg-gray-50 border border-gray-200 rounded-xl p-6">
          <div className="flex items-center justify-between mb-4">
            <h4 className="text-sm font-medium text-gray-600">Resources Scanned</h4>
            <div className="p-2 rounded-lg bg-blue-100">
              <i className="fas fa-server text-blue-500"></i>
            </div>
          </div>
          <p className="text-3xl font-bold text-gray-900">{totalResourcesScanned.toLocaleString()}</p>
          <p className="text-sm text-gray-500 mt-2">Total resources analyzed</p>
        </div>

        {/* Untagged Resources */}
        <div className="bg-gray-50 border border-gray-200 rounded-xl p-6">
          <div className="flex items-center justify-between mb-4">
            <h4 className="text-sm font-medium text-gray-600">Untagged Resources</h4>
            <div className={`p-2 rounded-lg ${untaggedResourcesCount === 0 ? 'bg-green-100' : 'bg-red-100'}`}>
              <i
                className={`fas fa-exclamation-circle ${
                  untaggedResourcesCount === 0 ? 'text-green-500' : 'text-red-500'
                }`}
              ></i>
            </div>
          </div>
          <p
            className={`text-3xl font-bold ${untaggedResourcesCount === 0 ? 'text-green-600' : 'text-red-600'}`}
          >
            {untaggedResourcesCount.toLocaleString()}
          </p>
          <p className="text-sm text-gray-500 mt-2">Resources missing required tags</p>
        </div>
      </div>

      {/* Untagged Resources Table */}
      {untaggedResources.length > 0 && (
        <div>
          <div className="flex items-center justify-between mb-4">
            <h4 className="text-lg font-semibold text-gray-800 flex items-center">
              <i className="fas fa-list-ul text-teal-500 mr-2"></i>
              Untagged Resources Details
              <span className="ml-2 px-2 py-0.5 text-xs font-medium bg-red-100 text-red-600 rounded-full">
                {untaggedResourcesCount} total
              </span>
            </h4>
            <div className="flex items-center space-x-2">
              <label htmlFor="taggingItemsPerPage" className="text-sm text-gray-600">
                Show:
              </label>
              <select
                id="taggingItemsPerPage"
                value={pagination.itemsPerPage}
                onChange={(e) =>
                  onPaginationChange({ itemsPerPage: Number(e.target.value), currentPage: 1 })
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
                  <th className="px-6 py-4">Status</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-200">
                {paginatedResources.map((resource) => (
                  <tr key={resource.resourceId} className="hover:bg-gray-50">
                    <td className="px-6 py-4 font-mono text-xs text-gray-800">{resource.resourceId}</td>
                    <td className="px-6 py-4">
                      <span className="px-2 py-1 text-xs font-medium bg-indigo-100 text-indigo-700 rounded-full">
                        {resource.resourceType}
                      </span>
                    </td>
                    <td className="px-6 py-4">{resource.region}</td>
                    <td className="px-6 py-4">
                      <span className="px-2 py-1 text-xs font-medium bg-red-100 text-red-600 rounded-full">
                        <i className="fas fa-times-circle mr-1"></i>Missing
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

      {/* Empty State - All Tagged */}
      {untaggedResources.length === 0 && totalResourcesScanned > 0 && (
        <div className="text-center py-12 rounded-2xl border-2 border-dashed border-green-300 bg-green-50/50">
          <div className="w-16 h-16 bg-green-100 rounded-full flex items-center justify-center mx-auto mb-4">
            <i className="fas fa-check-circle text-green-500 text-2xl"></i>
          </div>
          <h4 className="text-lg font-bold text-green-800 mb-2">All Resources Tagged</h4>
          <p className="text-green-600">All scanned resources have the required tags. Great job!</p>
        </div>
      )}

      {/* No Data State */}
      {totalResourcesScanned === 0 && (
        <div className="text-center py-12 rounded-2xl border-2 border-dashed border-gray-300 bg-gray-50/50">
          <div className="w-16 h-16 bg-gray-200 rounded-full flex items-center justify-center mx-auto mb-4">
            <i className="fas fa-tags text-gray-400 text-2xl"></i>
          </div>
          <h4 className="text-lg font-bold text-gray-800 mb-2">No Tagging Data Available</h4>
          <p className="text-gray-600">Tagging compliance data will appear here once resources are scanned.</p>
        </div>
      )}
    </div>
  );
};

export default FinOpsTaggingCompliance;
