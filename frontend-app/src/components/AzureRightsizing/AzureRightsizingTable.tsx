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

const ImpactBadge: React.FC<{ impact: string }> = ({ impact }) => {
  let cls = 'bg-gray-100 text-gray-700';
  if (impact === 'High') cls = 'bg-red-100 text-red-700';
  else if (impact === 'Medium') cls = 'bg-amber-100 text-amber-700';
  else if (impact === 'Low') cls = 'bg-green-100 text-green-700';
  return (
    <span className={`inline-flex items-center px-2 py-0.5 rounded-full text-xs font-semibold ${cls}`}>
      {impact}
    </span>
  );
};

const SkeletonRows: React.FC = () => (
  <>
    {[...Array(5)].map((_, i) => (
      <tr key={i} className="animate-pulse">
        {[...Array(7)].map((_, j) => (
          <td key={j} className="px-4 py-4">
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
          <span className="font-semibold text-blue-700">"Reader"</span> role on the subscription
          to enable rightsizing recommendations.
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
          An error occurred while contacting the Azure Advisor API. Check the backend logs for details,
          then try refreshing.
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
          Azure Advisor has no Cost recommendations for this subscription —
          your resources appear to be well-optimized.
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
      <p className="text-sm text-gray-500 max-w-md text-center">
        Azure Advisor may have no cost recommendations for this subscription,
        or the service principal lacks "Reader" role access to Advisor.
      </p>
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
    <div className="glass-card rounded-xl shadow-sm overflow-hidden">
      {/* Toolbar */}
      <div className="px-6 py-4 border-b border-gray-100 flex flex-wrap gap-3 items-center justify-between">
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
      <div className="overflow-x-auto">
        <table className="w-full text-sm">
          <thead>
            <tr className="bg-gray-50 text-gray-600 font-semibold text-xs uppercase tracking-wider">
              <th className="px-4 py-3 text-left">Resource Name</th>
              <th className="px-4 py-3 text-left">Type</th>
              <th className="px-4 py-3 text-left">Current → Recommended</th>
              <th className="px-4 py-3 text-left">CPU %</th>
              <th className="px-4 py-3 text-right">Savings/mo</th>
              <th className="px-4 py-3 text-left">Impact</th>
              <th className="px-4 py-3 text-left">Recommendation</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-50">
            {isLoading ? (
              <SkeletonRows />
            ) : filtered.length === 0 ? (
              <tr>
                <td colSpan={7} className="px-6 py-16 text-center">
                  <EmptyState status={status} hasFilter={hasFilter && recommendations.length > 0} />
                </td>
              </tr>
            ) : (
              filtered.map((rec, idx) => (
                <tr key={rec.recommendationId || idx} className="hover:bg-gray-50 transition-colors">
                  <td className="px-4 py-4">
                    <div className="font-semibold text-gray-900 max-w-[160px] truncate" title={rec.resourceName}>
                      {rec.resourceName || '—'}
                    </div>
                    {rec.location && (
                      <div className="text-xs text-gray-400 mt-0.5">{rec.location}</div>
                    )}
                  </td>
                  <td className="px-4 py-4">
                    <span className="text-gray-600 capitalize text-xs bg-gray-100 px-2 py-1 rounded">
                      {rec.resourceType || '—'}
                    </span>
                  </td>
                  <td className="px-4 py-4">
                    {rec.currentSku || rec.targetSku ? (
                      <div className="flex items-center gap-1.5 text-xs">
                        <span className="font-medium text-gray-700 bg-gray-100 px-1.5 py-0.5 rounded">
                          {rec.currentSku || '—'}
                        </span>
                        <i className="fas fa-arrow-right text-gray-400 text-xs"></i>
                        <span className="font-medium text-blue-700 bg-blue-50 px-1.5 py-0.5 rounded">
                          {rec.targetSku || '—'}
                        </span>
                      </div>
                    ) : (
                      <span className="text-gray-400">—</span>
                    )}
                  </td>
                  <td className="px-4 py-4">
                    {rec.maxCpuP95 ? (
                      <span className="text-gray-600 text-xs font-medium">{rec.maxCpuP95}%</span>
                    ) : (
                      <span className="text-gray-400">—</span>
                    )}
                  </td>
                  <td className="px-4 py-4 text-right">
                    {rec.monthlySavings > 0 ? (
                      <span className="font-bold text-green-600">{formatINR(rec.monthlySavings)}</span>
                    ) : (
                      <span className="text-gray-400">—</span>
                    )}
                  </td>
                  <td className="px-4 py-4">
                    <ImpactBadge impact={rec.impact} />
                  </td>
                  <td className="px-4 py-4">
                    <p className="text-gray-600 text-xs max-w-[200px] line-clamp-2" title={rec.problem}>
                      {rec.problem
                        ? rec.problem.length > 100
                          ? rec.problem.substring(0, 97) + '...'
                          : rec.problem
                        : '—'}
                    </p>
                  </td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>

      {!isLoading && filtered.length > 0 && (
        <div className="px-6 py-3 border-t border-gray-100 text-xs text-gray-500">
          Showing {filtered.length} of {recommendations.length} recommendations
        </div>
      )}
    </div>
  );
};

export default AzureRightsizingTable;
