import React, { useState } from 'react';
import { Icon } from '@iconify/react';
import type { AzurePerformanceInsight } from '../../types/azurePerformance';

interface AzurePerformanceInsightsTableProps {
  insights: AzurePerformanceInsight[];
  isLoading: boolean;
}

type SeverityFilter = 'All' | 'High' | 'Medium' | 'Low';

const SEVERITY_BADGE: Record<string, string> = {
  High: 'bg-red-100 text-red-700 border-red-200',
  Medium: 'bg-amber-100 text-amber-700 border-amber-200',
  Low: 'bg-blue-100 text-blue-700 border-blue-200',
};

const FILTER_TABS: SeverityFilter[] = ['All', 'High', 'Medium', 'Low'];

const SkeletonRow = () => (
  <tr className="border-b border-gray-100 animate-pulse">
    <td className="px-4 py-4"><div className="h-3 bg-gray-200 rounded w-32"></div></td>
    <td className="px-4 py-4"><div className="h-3 bg-gray-200 rounded w-20"></div></td>
    <td className="px-4 py-4"><div className="h-5 bg-gray-200 rounded w-16"></div></td>
    <td className="px-4 py-4"><div className="h-3 bg-gray-200 rounded w-40"></div></td>
    <td className="px-4 py-4"><div className="h-3 bg-gray-200 rounded w-48"></div></td>
  </tr>
);

const AzurePerformanceInsightsTable: React.FC<AzurePerformanceInsightsTableProps> = ({ insights, isLoading }) => {
  const [activeFilter, setActiveFilter] = useState<SeverityFilter>('All');

  if (isLoading) {
    return (
      <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm overflow-hidden">
        <div className="px-5 py-3 border-b border-gray-100">
          <div className="h-4 bg-gray-200 rounded w-36 animate-pulse"></div>
        </div>
        <table className="w-full text-sm">
          <tbody>
            <SkeletonRow />
            <SkeletonRow />
            <SkeletonRow />
          </tbody>
        </table>
      </div>
    );
  }

  const filtered = activeFilter === 'All'
    ? insights
    : insights.filter(i => i.severity === activeFilter);

  return (
    <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm overflow-hidden">
      {/* Header + filter tabs */}
      <div className="px-5 py-3 border-b border-gray-100 flex items-center justify-between flex-wrap gap-3">
        <span className="text-sm font-bold text-gray-700">Performance Recommendations</span>
        <div className="flex gap-1">
          {FILTER_TABS.map(tab => {
            const count = tab === 'All' ? insights.length : insights.filter(i => i.severity === tab).length;
            const isActive = activeFilter === tab;
            const activeColor = tab === 'High' ? 'bg-red-600'
              : tab === 'Medium' ? 'bg-amber-500'
              : tab === 'Low' ? 'bg-blue-500'
              : 'bg-indigo-600';
            return (
              <button
                key={tab}
                onClick={() => setActiveFilter(tab)}
                className={`px-3 py-1 text-xs font-semibold rounded-lg transition-all ${
                  isActive
                    ? `${activeColor} text-white shadow-sm`
                    : 'bg-gray-100 text-gray-600 hover:bg-gray-200'
                }`}
              >
                {tab}
                {count > 0 && (
                  <span className={`ml-1.5 text-xs px-1 py-0.5 rounded-full ${
                    isActive ? 'bg-white/30 text-white' : 'bg-gray-200 text-gray-600'
                  }`}>
                    {count}
                  </span>
                )}
              </button>
            );
          })}
        </div>
      </div>

      {filtered.length === 0 ? (
        <div className="p-12 text-center">
          <div className="w-14 h-14 bg-emerald-50 border border-emerald-100 rounded-xl flex items-center justify-center mx-auto mb-3">
            <Icon icon="mdi:check-circle-outline" width={28} className="text-emerald-500" />
          </div>
          <h3 className="text-base font-bold text-gray-700 mb-1">
            {activeFilter === 'All' ? 'No Performance Issues' : `No ${activeFilter} Impact Issues`}
          </h3>
          <p className="text-sm text-gray-500">
            {activeFilter === 'All'
              ? 'Azure Advisor found no performance recommendations for this subscription.'
              : `No ${activeFilter.toLowerCase()} impact recommendations found.`}
          </p>
        </div>
      ) : (
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead className="bg-gray-50 border-b border-gray-100">
              <tr>
                <th className="px-4 py-2 text-left text-xs font-bold uppercase tracking-wider text-gray-500">Resource</th>
                <th className="px-4 py-2 text-left text-xs font-bold uppercase tracking-wider text-gray-500">Type</th>
                <th className="px-4 py-2 text-left text-xs font-bold uppercase tracking-wider text-gray-500">Severity</th>
                <th className="px-4 py-2 text-left text-xs font-bold uppercase tracking-wider text-gray-500">Issue</th>
                <th className="px-4 py-2 text-left text-xs font-bold uppercase tracking-wider text-gray-500">Recommendation</th>
              </tr>
            </thead>
            <tbody>
              {filtered.map((insight, idx) => {
                const badgeClass = SEVERITY_BADGE[insight.severity] ?? 'bg-gray-100 text-gray-600 border-gray-200';
                return (
                  <tr key={insight.insightId || idx} className="border-t border-gray-100 hover:bg-gray-50 transition-colors">
                    <td className="px-4 py-3">
                      <span className="text-gray-800 font-medium text-xs truncate max-w-[160px] block" title={insight.resourceName}>
                        {insight.resourceName || '—'}
                      </span>
                    </td>
                    <td className="px-4 py-3">
                      <span className="text-xs text-gray-500 bg-gray-100 px-2 py-0.5 rounded">
                        {insight.resourceType || '—'}
                      </span>
                    </td>
                    <td className="px-4 py-3">
                      <span className={`inline-flex items-center px-2 py-0.5 rounded-full text-xs font-semibold border ${badgeClass}`}>
                        {insight.severity || '—'}
                      </span>
                    </td>
                    <td className="px-4 py-3">
                      <span className="text-gray-700 text-xs line-clamp-2 max-w-[220px]" title={insight.description}>
                        {insight.description || '—'}
                      </span>
                    </td>
                    <td className="px-4 py-3">
                      <span className="text-gray-600 text-xs line-clamp-2 max-w-[220px]" title={insight.recommendation}>
                        {insight.recommendation || '—'}
                      </span>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
};

export default AzurePerformanceInsightsTable;
