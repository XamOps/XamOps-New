import React, { useState, useMemo } from 'react';
import { Icon } from '@iconify/react';
import type { AzureRightsizingRecommendation, RightsizingStatus } from '../../types/azureRightsizing';

interface AzureRightsizingTableProps {
  recommendations: AzureRightsizingRecommendation[];
  status: RightsizingStatus | null;
  isLoading: boolean;
}

type ImpactFilter = 'All' | 'High' | 'Medium' | 'Low';

const RESOURCE_ICONS: Record<string, { icon: string; color: string; bg: string }> = {
  virtualmachine: { icon: 'mdi:monitor',          color: '#3B82F6', bg: '#3B82F614' },
  managedcluster: { icon: 'mdi:kubernetes',        color: '#3B82F6', bg: '#3B82F614' },
  storageaccount: { icon: 'mdi:database',          color: '#6366F1', bg: '#6366F114' },
  disk:           { icon: 'mdi:harddisk',          color: '#8B5CF6', bg: '#8B5CF614' },
  container:      { icon: 'mdi:docker',            color: '#0EA5E9', bg: '#0EA5E914' },
};

const getResourceMeta = (resourceType: string) => {
  const t = (resourceType || '').toLowerCase();
  for (const [key, meta] of Object.entries(RESOURCE_ICONS)) {
    if (t.includes(key)) return meta;
  }
  return { icon: 'mdi:cloud-outline', color: '#6B7280', bg: '#6B728014' };
};

const IMPACT_COLORS: Record<string, string> = {
  High:   'bg-rose-50 text-rose-600 border-rose-100',
  Medium: 'bg-amber-50 text-amber-600 border-amber-100',
  Low:    'bg-emerald-50 text-emerald-600 border-emerald-100',
};

const EmptyState: React.FC<{ status: RightsizingStatus | null; hasFilter: boolean }> = ({ status, hasFilter }) => {
  if (hasFilter) {
    return (
      <>
        <div className="w-12 h-12 bg-[rgba(0,0,0,0.04)] rounded-full flex items-center justify-center mx-auto mb-3">
          <Icon icon="mdi:filter-outline" width={22} color="rgba(0,0,0,0.3)" />
        </div>
        <p className="text-[14px] font-semibold text-[rgba(0,0,0,0.88)] mb-1">No Matching Results</p>
        <p className="text-[13px] text-[rgba(0,0,0,0.45)]">Try adjusting the impact filter or search term.</p>
      </>
    );
  }
  if (status === 'PERMISSION_ERROR') {
    return (
      <>
        <div className="w-12 h-12 bg-rose-50 rounded-full flex items-center justify-center mx-auto mb-3">
          <Icon icon="mdi:lock-outline" width={22} color="#DC2626" />
        </div>
        <p className="text-[14px] font-semibold text-[rgba(0,0,0,0.88)] mb-1">Permission Required</p>
        <p className="text-[13px] text-[rgba(0,0,0,0.45)] max-w-sm mx-auto">
          Assign the <span className="font-semibold text-blue-600">Advisor Reviews Reader</span> or{' '}
          <span className="font-semibold text-blue-600">Reader</span> role on the subscription.
        </p>
      </>
    );
  }
  if (status === 'API_ERROR') {
    return (
      <>
        <div className="w-12 h-12 bg-amber-50 rounded-full flex items-center justify-center mx-auto mb-3">
          <Icon icon="mdi:alert-outline" width={22} color="#D97706" />
        </div>
        <p className="text-[14px] font-semibold text-[rgba(0,0,0,0.88)] mb-1">Unable to Reach Azure Advisor</p>
        <p className="text-[13px] text-[rgba(0,0,0,0.45)]">Check the backend logs, then try refreshing.</p>
      </>
    );
  }
  return (
    <>
      <div className="w-12 h-12 rounded-full flex items-center justify-center mx-auto mb-3" style={{ backgroundColor: '#16A34A14' }}>
        <Icon icon="mdi:check-circle-outline" width={24} color="#16A34A" />
      </div>
      <p className="text-[14px] font-semibold text-[rgba(0,0,0,0.88)] mb-1">No Cost Recommendations</p>
      <p className="text-[13px] text-[rgba(0,0,0,0.45)]">Azure Advisor has no cost recommendations — your resources appear well-optimized.</p>
    </>
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
      const matchSearch = !search || r.resourceName.toLowerCase().includes(search.toLowerCase());
      const matchImpact = impactFilter === 'All' || r.impact === impactFilter;
      return matchSearch && matchImpact;
    });
  }, [recommendations, search, impactFilter]);

  const hasFilter = !!(search || impactFilter !== 'All');

  if (isLoading) {
    return (
      <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm overflow-hidden">
        {Array.from({ length: 5 }).map((_, i) => (
          <div key={i} className="px-5 py-4 border-t border-[rgba(0,0,0,0.04)] animate-pulse flex items-center gap-4">
            <div className="w-8 h-8 rounded-lg bg-[rgba(0,0,0,0.06)] flex-shrink-0" />
            <div className="flex-1 space-y-2">
              <div className="h-2.5 w-40 bg-[rgba(0,0,0,0.06)] rounded" />
              <div className="h-2 w-24 bg-[rgba(0,0,0,0.04)] rounded" />
            </div>
          </div>
        ))}
      </div>
    );
  }

  return (
    <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm overflow-hidden widget-panel-hover">
      {/* Toolbar */}
      <div className="flex flex-wrap items-center gap-3 px-5 py-3.5 border-b border-[rgba(0,0,0,0.06)] bg-[rgba(0,0,0,0.015)]">
        {/* Impact filter pills */}
        <div className="flex items-center gap-1.5 flex-wrap">
          {(['All', 'High', 'Medium', 'Low'] as ImpactFilter[]).map(opt => (
            <button
              key={opt}
              onClick={() => setImpactFilter(opt)}
              className={`px-3 py-1 rounded-full text-[11px] font-semibold transition-all ${
                impactFilter === opt
                  ? opt === 'High'   ? 'bg-rose-500 text-white'
                  : opt === 'Medium' ? 'bg-amber-500 text-white'
                  : opt === 'Low'    ? 'bg-emerald-600 text-white'
                  : 'bg-[rgba(0,0,0,0.88)] text-white'
                  : 'bg-[rgba(0,0,0,0.04)] text-[rgba(0,0,0,0.55)] hover:bg-[rgba(0,0,0,0.07)]'
              }`}
            >
              {opt}
              {opt !== 'All' && (
                <span className="ml-1 opacity-75">({recommendations.filter(r => r.impact === opt).length})</span>
              )}
            </button>
          ))}
        </div>

        {/* Search */}
        <div className="relative ml-auto">
          <Icon icon="mdi:magnify" width={13} className="absolute left-2.5 top-1/2 -translate-y-1/2 text-[rgba(0,0,0,0.3)]" />
          <input
            type="text"
            placeholder="Search resources…"
            value={search}
            onChange={e => setSearch(e.target.value)}
            className="pl-7 pr-3 py-1.5 text-[12px] border border-[rgba(0,0,0,0.1)] rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-300 bg-white w-44 text-[rgba(0,0,0,0.88)] placeholder:text-[rgba(0,0,0,0.3)]"
          />
        </div>

        <span className="text-[11px] text-[rgba(0,0,0,0.35)]">{filtered.length} of {recommendations.length}</span>
      </div>

      {/* Subheader note */}
      <div className="flex items-center gap-2 px-5 py-2.5 border-b border-[rgba(0,0,0,0.06)] bg-[rgba(0,0,0,0.01)]">
        <Icon icon="mdi:microsoft-azure" width={13} color="#0078D4" />
        <span className="text-[11px] text-[rgba(0,0,0,0.4)] italic">Recommendations sourced directly from Azure Advisor</span>
      </div>

      {/* Empty / error state */}
      {filtered.length === 0 ? (
        <div className="p-10 text-center">
          <EmptyState status={status} hasFilter={hasFilter && recommendations.length > 0} />
        </div>
      ) : (
        <>
          {/* Column headers */}
          <div className="grid grid-cols-12 px-5 py-2 bg-[rgba(0,0,0,0.02)] text-[11px] font-semibold uppercase tracking-wide text-[rgba(0,0,0,0.4)] border-b border-[rgba(0,0,0,0.06)]">
            <div className="col-span-3">Resource</div>
            <div className="col-span-3">Current → Recommended</div>
            <div className="col-span-2">Impact</div>
            <div className="col-span-2">Savings / mo</div>
            <div className="col-span-2">Recommendation</div>
          </div>

          {filtered.map((rec, idx) => {
            const meta = getResourceMeta(rec.resourceType);
            return (
              <div
                key={rec.recommendationId || idx}
                className="grid grid-cols-12 items-center px-5 py-3.5 border-t border-[rgba(0,0,0,0.04)] hover:bg-[rgba(0,0,0,0.01)] transition-colors text-[13px]"
                style={{ animation: `fadeInUp 0.35s ease ${Math.min(idx * 0.035, 0.4)}s both` }}
              >
                {/* Resource */}
                <div className="col-span-3 flex items-center gap-2.5 min-w-0">
                  <div className="w-7 h-7 rounded-lg flex items-center justify-center flex-shrink-0 border border-[rgba(0,0,0,0.08)]" style={{ backgroundColor: meta.bg }}>
                    <Icon icon={meta.icon} width={14} color={meta.color} />
                  </div>
                  <div className="min-w-0">
                    <p className="font-semibold text-[rgba(0,0,0,0.88)] truncate" title={rec.resourceName}>{rec.resourceName || '—'}</p>
                    {(rec.location || rec.resourceType) && (
                      <p className="text-[11px] text-[rgba(0,0,0,0.35)] truncate">
                        {[rec.resourceType, rec.location].filter(Boolean).join(' · ')}
                      </p>
                    )}
                  </div>
                </div>

                {/* Current → Recommended */}
                <div className="col-span-3">
                  {rec.currentSku || rec.targetSku ? (
                    <div className="flex items-center gap-2">
                      <p className="font-mono text-[12px] font-semibold text-rose-600 truncate">{rec.currentSku || '—'}</p>
                      <svg className="w-3 h-3 text-[rgba(0,0,0,0.25)] flex-shrink-0" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 5l7 7-7 7" />
                      </svg>
                      <p className="font-mono text-[12px] font-semibold text-emerald-600 truncate">{rec.targetSku || '—'}</p>
                    </div>
                  ) : (
                    <span className="text-[rgba(0,0,0,0.3)]">—</span>
                  )}
                </div>

                {/* Impact */}
                <div className="col-span-2">
                  {rec.impact && (
                    <span className={`text-[11px] font-semibold px-2 py-0.5 rounded-full border ${IMPACT_COLORS[rec.impact] ?? 'bg-[rgba(0,0,0,0.04)] text-[rgba(0,0,0,0.55)] border-[rgba(0,0,0,0.08)]'}`}>
                      {rec.impact}
                    </span>
                  )}
                </div>

                {/* Savings */}
                <div className="col-span-2">
                  {rec.monthlySavings > 0 ? (
                    <>
                      <p className="font-semibold text-emerald-600">₹{rec.monthlySavings.toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}</p>
                      <p className="text-[11px] text-[rgba(0,0,0,0.35)]">/month</p>
                    </>
                  ) : (
                    <span className="text-[rgba(0,0,0,0.3)]">—</span>
                  )}
                </div>

                {/* Recommendation */}
                <div className="col-span-2 text-[12px] text-[rgba(0,0,0,0.55)] leading-relaxed">
                  {rec.problem || '—'}
                </div>
              </div>
            );
          })}
        </>
      )}
    </div>
  );
};

export default AzureRightsizingTable;
