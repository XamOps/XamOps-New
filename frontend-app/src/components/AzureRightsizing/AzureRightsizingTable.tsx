import React, { useState, useMemo } from 'react';
import type { AzureRightsizingRecommendation, RightsizingStatus } from '../../types/azureRightsizing';

interface AzureRightsizingTableProps {
  recommendations: AzureRightsizingRecommendation[];
  status: RightsizingStatus | null;
  isLoading: boolean;
}

type ImpactFilter = 'All' | 'High' | 'Medium' | 'Low';

const formatINR = (value: number) =>
  '₹' + value.toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });

// Map Azure resource types to icons
const getResourceIcon = (resourceType: string): string => {
  const t = (resourceType || '').toLowerCase();
  if (t.includes('virtualmachine') || t.includes('vm')) return 'virtual-machine.png';
  if (t.includes('managedcluster') || t.includes('aks'))  return 'azure-aks.png';
  if (t.includes('storageaccount'))                        return 'azure-storage-account.png';
  if (t.includes('disk'))                                  return 'disk-storage.png';
  if (t.includes('container'))                             return 'azure-container-instances.png';
  return 'azure-icon.png'; // generic Azure icon for savings plans, etc.
};

const SkeletonRows: React.FC = () => (
  <>
    {[...Array(5)].map((_, i) => (
      <tr key={i} className="animate-pulse">
        {[...Array(4)].map((_, j) => (
          <td key={j} className="px-6 py-4">
            <div className="h-3 bg-gray-200 rounded w-full"></div>
          </td>
        ))}
      </tr>
    ))}
  </>
);

const EmptyState: React.FC<{ status: RightsizingStatus | null; hasFilter: boolean }> = ({ status, hasFilter }) => {
  if (hasFilter) {
    return (
      <div className="flex flex-col items-center">
        <div className="w-16 h-16 bg-gray-100 rounded-full flex items-center justify-center mb-4">
          <i className="fas fa-compress-arrows-alt text-gray-400 text-2xl"></i>
        </div>
        <h3 className="text-lg font-bold text-gray-700 mb-1">No matching recommendations</h3>
        <p className="text-sm text-gray-500 max-w-md">Try adjusting the impact filter or search term.</p>
      </div>
    );
  }

  if (status === 'PERMISSION_ERROR') {
    return (
      <div className="flex flex-col items-center">
        <div className="w-16 h-16 bg-red-100 rounded-full flex items-center justify-center mb-4">
          <i className="fas fa-lock text-red-500 text-2xl"></i>
        </div>
        <h3 className="text-lg font-bold text-gray-700 mb-1">Permission Required</h3>
        <p className="text-sm text-gray-500 max-w-md text-center">
          The service principal lacks access to Azure Advisor. Assign the{' '}
          <span className="font-semibold text-blue-700">"Advisor Reviews Reader"</span> or{' '}
          <span className="font-semibold text-blue-700">"Reader"</span> role on the subscription.
        </p>
      </div>
    );
  }

  if (status === 'API_ERROR') {
    return (
      <div className="flex flex-col items-center">
        <div className="w-16 h-16 bg-amber-100 rounded-full flex items-center justify-center mb-4">
          <i className="fas fa-exclamation-triangle text-amber-500 text-2xl"></i>
        </div>
        <h3 className="text-lg font-bold text-gray-700 mb-1">Unable to Reach Azure Advisor</h3>
        <p className="text-sm text-gray-500 max-w-md text-center">
          An error occurred while contacting the Azure Advisor API. Check the backend logs, then try refreshing.
        </p>
      </div>
    );
  }

  if (status === 'NO_DATA') {
    return (
      <div className="flex flex-col items-center">
        <div className="w-16 h-16 bg-green-100 rounded-full flex items-center justify-center mb-4">
          <i className="fas fa-check-circle text-green-500 text-2xl"></i>
        </div>
        <h3 className="text-lg font-bold text-gray-700 mb-1">No Cost Recommendations</h3>
        <p className="text-sm text-gray-500 max-w-md text-center">
          Azure Advisor has no cost recommendations for this subscription — your resources appear well-optimized.
        </p>
      </div>
    );
  }

  return (
    <div className="flex flex-col items-center">
      <div className="w-16 h-16 bg-gray-100 rounded-full flex items-center justify-center mb-4">
        <i className="fas fa-compress-arrows-alt text-gray-400 text-2xl"></i>
      </div>
      <h3 className="text-lg font-bold text-gray-700 mb-1">No cost recommendations from Azure Advisor</h3>
    </div>
  );
};

const AzureRightsizingTable: React.FC<AzureRightsizingTableProps> = ({
  recommendations,
  status,
  isLoading,
}) => {
  const [search, setSearch] = useState('');
  const [impactFilter, setImpactFilter] = useState<ImpactFilter>('All');

  const filtered = useMemo(() => {
    return recommendations.filter(r => {
      const matchSearch = !search ||
        r.resourceName.toLowerCase().includes(search.toLowerCase());
      const matchImpact = impactFilter === 'All' || r.impact === impactFilter;
      return matchSearch && matchImpact;
    });
  }, [recommendations, search, impactFilter]);

  const impactOptions: ImpactFilter[] = ['All', 'High', 'Medium', 'Low'];
  const hasFilter = !!(search || impactFilter !== 'All');

  return (
    <div className="glass-card-table p-8">
      {/* Header */}
      <div className="flex items-center justify-between mb-4">
        <div className="flex items-center space-x-2 text-sm text-gray-600 dark:text-gray-400">
          <img src="/icons/azure-icon.png" alt="Azure" className="w-4 h-4" />
          <span className="text-xs italic">These recommendations are coming directly from Azure Advisor</span>
        </div>
      </div>

      {/* Toolbar */}
      <div className="flex flex-wrap gap-3 items-center justify-between mb-4">
        <div className="flex items-center gap-2 flex-wrap">
          {impactOptions.map(opt => (
            <button
              key={opt}
              onClick={() => setImpactFilter(opt)}
              className={`px-3 py-1.5 rounded-full text-xs font-semibold transition-all ${
                impactFilter === opt
                  ? opt === 'High'
                    ? 'bg-red-600 text-white shadow-sm'
                    : opt === 'Medium'
                    ? 'bg-amber-500 text-white shadow-sm'
                    : opt === 'Low'
                    ? 'bg-green-600 text-white shadow-sm'
                    : 'bg-blue-600 text-white shadow-sm'
                  : 'bg-gray-100 text-gray-600 hover:bg-gray-200'
              }`}
            >
              {opt}
              {opt !== 'All' && (
                <span className="ml-1 opacity-80">
                  ({recommendations.filter(r => r.impact === opt).length})
                </span>
              )}
            </button>
          ))}
        </div>

        <div className="relative">
          <i className="fas fa-search absolute left-3 top-1/2 -translate-y-1/2 text-gray-400 text-xs"></i>
          <input
            type="text"
            placeholder="Search resources..."
            value={search}
            onChange={e => setSearch(e.target.value)}
            className="pl-8 pr-4 py-2 text-sm border border-gray-200 rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-500 bg-white w-52"
          />
        </div>
      </div>

      {/* Table */}
      <div
        className="table-scroll-container"
        onScroll={(e) => {
          const target = e.currentTarget;
          const isAtEnd = target.scrollLeft + target.clientWidth >= target.scrollWidth - 10;
          if (isAtEnd) target.classList.add('scrolled-to-end');
          else target.classList.remove('scrolled-to-end');
        }}
      >
        <div className="table-enhanced">
          <table className="w-full text-sm" style={{ tableLayout: 'fixed' }}>
            <colgroup>
              <col style={{ width: '24%' }} />
              <col style={{ width: '28%' }} />
              <col style={{ width: '14%' }} />
              <col style={{ width: '34%' }} />
            </colgroup>
            <thead className="text-xs !text-gray-700 dark:!text-gray-300 uppercase !bg-gradient-to-r !from-gray-50 !to-gray-100 dark:!from-gray-800 dark:!to-gray-900 sticky top-0 z-10">
              <tr>
                <th className="px-6 py-4 text-left font-bold">Resource</th>
                <th className="px-6 py-4 text-left font-bold">Current → Recommended</th>
                <th className="px-6 py-4 text-left font-bold">Savings</th>
                <th className="px-6 py-4 text-left font-bold">Recommendation</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-200 dark:divide-gray-700">
              {isLoading ? (
                <SkeletonRows />
              ) : filtered.length === 0 ? (
                <tr>
                  <td colSpan={4} className="px-6 py-16 text-center">
                    <EmptyState status={status} hasFilter={hasFilter && recommendations.length > 0} />
                  </td>
                </tr>
              ) : (
                filtered.map((rec, idx) => (
                  <tr key={rec.recommendationId || idx} className="hover:bg-gray-50 dark:hover:bg-gray-800/30 transition-all">

                    {/* Resource */}
                    <td className="px-6 py-4 first-cell-indicator">
                      <div className="flex items-center space-x-3">
                        <div className="w-8 h-8 rounded-lg flex-shrink-0">
                          <img
                            src={`/icons/${getResourceIcon(rec.resourceType)}`}
                            alt={rec.resourceType}
                            className="w-full h-full rounded-lg object-cover"
                          />
                        </div>
                        <div className="flex flex-col min-w-0">
                          <span
                            className="font-medium text-gray-800 dark:text-gray-200 truncate"
                            title={rec.resourceName}
                          >
                            {rec.resourceName || '—'}
                          </span>
                          {(rec.location || rec.resourceType) && (
                            <span className="text-xs text-gray-500 dark:text-gray-400 truncate">
                              {[rec.resourceType, rec.location].filter(Boolean).join(' · ')}
                            </span>
                          )}
                        </div>
                      </div>
                    </td>

                    {/* Current → Recommended */}
                    <td className="px-6 py-4">
                      {rec.currentSku || rec.targetSku ? (
                        <div className="flex items-center gap-2">
                          <div className="text-left min-w-0">
                            <p className="font-bold text-red-600 dark:text-red-400 text-xs truncate">
                              {rec.currentSku || '—'}
                            </p>
                          </div>
                          <i className="fas fa-arrow-right text-gray-400 dark:text-gray-500 text-xs flex-shrink-0"></i>
                          <div className="text-left min-w-0">
                            <p className="font-bold text-green-600 dark:text-green-400 text-xs truncate">
                              {rec.targetSku || '—'}
                            </p>
                          </div>
                        </div>
                      ) : (
                        <span className="text-gray-400 text-xs">—</span>
                      )}
                      {rec.impact && (
                        <p className={`text-xs mt-1 font-medium ${
                          rec.impact === 'High' ? 'text-red-500' :
                          rec.impact === 'Medium' ? 'text-amber-500' : 'text-green-500'
                        }`}>
                          {rec.impact} Impact
                        </p>
                      )}
                    </td>

                    {/* Savings */}
                    <td className="px-6 py-4">
                      {rec.monthlySavings > 0 ? (
                        <div className="flex flex-col space-y-1">
                          <span className="savings-badge px-3 py-1 text-xs font-semibold">
                            {formatINR(rec.monthlySavings)}
                          </span>
                          <span className="text-xs text-gray-500">/month</span>
                        </div>
                      ) : (
                        <span className="text-gray-400">—</span>
                      )}
                    </td>

                    {/* Recommendation */}
                    <td className="px-6 py-4">
                      <p className="text-sm text-gray-900 dark:text-gray-100 reason-text">
                        {rec.problem || '—'}
                      </p>
                    </td>

                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>
      </div>

      {!isLoading && filtered.length > 0 && (
        <div className="mt-3 text-xs text-gray-500">
          Showing {filtered.length} of {recommendations.length} recommendations
        </div>
      )}
    </div>
  );
};

export default AzureRightsizingTable;
