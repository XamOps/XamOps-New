import React, { useState, useMemo } from 'react';
import { Icon } from '@iconify/react';
import type { GcpRightsizingRecommendation, ServiceFilter } from '../../types/gcpRightsizing';

interface GcpRightsizingTableProps {
  recommendations: GcpRightsizingRecommendation[];
  isLoading: boolean;
  onRefresh: () => void;
}

const SERVICES: ServiceFilter[] = ['All', 'Compute Engine', 'Cloud SQL', 'Commitment'];

const SERVICE_ICONS: Record<string, { icon: string; color: string; bg: string }> = {
  'Compute Engine': { icon: 'mdi:server',     color: '#4285F4', bg: '#4285F414' },
  'Cloud SQL':      { icon: 'mdi:database',   color: '#34A853', bg: '#34A85314' },
  'Commitment':     { icon: 'mdi:handshake',  color: '#FBBC05', bg: '#FBBC0514' },
};

const cleanValue = (value: string | undefined | null, fallback = 'N/A'): string => {
  if (!value || value.trim() === '' || value.toLowerCase() === 'unknown') return fallback;
  return value;
};

export const GcpRightsizingTable: React.FC<GcpRightsizingTableProps> = ({
  recommendations,
  isLoading,
  onRefresh,
}) => {
  const [filter, setFilter] = useState<ServiceFilter>('All');
  const [searchTerm, setSearchTerm] = useState('');

  const filteredRecommendations = useMemo(() => {
    const term = searchTerm.toLowerCase();
    return recommendations.filter(rec => {
      const serviceMatch = filter === 'All' || (rec.service && rec.service === filter);
      const searchMatch = term === '' || (rec.resourceName && rec.resourceName.toLowerCase().includes(term));
      return serviceMatch && searchMatch;
    });
  }, [recommendations, filter, searchTerm]);

  const isUnderprovisioned = (rec: GcpRightsizingRecommendation) =>
    rec.recommendationType === 'PERFORMANCE_IMPROVEMENT';

  const formatSavings = (rec: GcpRightsizingRecommendation): string => {
    const value = Math.abs(rec.monthlySavings || 0);
    return '$' + value.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  };

  const getReasonText = (rec: GcpRightsizingRecommendation): string => {
    if (rec.reason && rec.reason !== 'No details available') return rec.reason;
    if (rec.recommendationType === 'PERFORMANCE_IMPROVEMENT')
      return 'Resource is underprovisioned. Upgrade recommended for better performance.';
    if (rec.recommendationType === 'COST_SAVINGS')
      return 'Resource is overprovisioned. Downgrade recommended to reduce costs.';
    return rec.description || 'Optimization recommended';
  };

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
    <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm overflow-hidden">
      {/* Toolbar */}
      <div className="flex flex-wrap items-center gap-3 px-5 py-3.5 border-b border-[rgba(0,0,0,0.06)] bg-[rgba(0,0,0,0.015)]">
        {/* Service filters */}
        <div className="flex items-center gap-1.5 flex-wrap">
          {SERVICES.map(service => (
            <button
              key={service}
              onClick={() => setFilter(service)}
              className={`px-3 py-1 rounded-full text-[11px] font-semibold transition-all ${
                filter === service
                  ? 'bg-[rgba(0,0,0,0.88)] text-white'
                  : 'bg-[rgba(0,0,0,0.04)] text-[rgba(0,0,0,0.55)] hover:bg-[rgba(0,0,0,0.07)]'
              }`}
            >
              {service}
            </button>
          ))}
        </div>

        {/* Search */}
        <div className="relative ml-auto">
          <Icon icon="mdi:magnify" width={13} className="absolute left-2.5 top-1/2 -translate-y-1/2 text-[rgba(0,0,0,0.3)]" />
          <input
            type="text"
            value={searchTerm}
            onChange={e => setSearchTerm(e.target.value)}
            placeholder="Search resource name…"
            className="pl-7 pr-3 py-1.5 text-[12px] border border-[rgba(0,0,0,0.1)] rounded-lg focus:outline-none focus:ring-2 focus:ring-indigo-300 bg-white w-44 text-[rgba(0,0,0,0.88)] placeholder:text-[rgba(0,0,0,0.3)]"
          />
        </div>

        <span className="text-[11px] text-[rgba(0,0,0,0.35)]">{filteredRecommendations.length} of {recommendations.length}</span>
      </div>

      {/* Column headers */}
      <div className="grid grid-cols-12 px-5 py-2 bg-[rgba(0,0,0,0.02)] text-[11px] font-semibold uppercase tracking-wide text-[rgba(0,0,0,0.4)] border-b border-[rgba(0,0,0,0.06)]">
        <div className="col-span-3">Resource Name</div>
        <div className="col-span-2">Service</div>
        <div className="col-span-2">Location</div>
        <div className="col-span-3">Machine Type</div>
        <div className="col-span-1">Impact</div>
        <div className="col-span-1 text-right">Savings/mo</div>
      </div>

      {/* Empty state */}
      {filteredRecommendations.length === 0 && (
        <div className="p-10 text-center">
          <div className="w-12 h-12 rounded-full flex items-center justify-center mx-auto mb-3" style={{ backgroundColor: '#16A34A14' }}>
            <Icon icon="mdi:check-circle-outline" width={24} color="#16A34A" />
          </div>
          <p className="text-[15px] font-semibold text-[rgba(0,0,0,0.88)] mb-1">All Optimized!</p>
          <p className="text-[13px] text-[rgba(0,0,0,0.45)] mb-4">No rightsizing recommendations found for the current filter.</p>
          <button
            onClick={onRefresh}
            className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-[12px] font-semibold border border-indigo-200 text-indigo-600 hover:bg-indigo-50 transition-colors"
          >
            <Icon icon="mdi:refresh" width={13} />
            Refresh Analysis
          </button>
        </div>
      )}

      {/* Rows */}
      {filteredRecommendations.map((rec, idx) => {
        const resourceName = cleanValue(rec.resourceName);
        const service = cleanValue(rec.service);
        const location = cleanValue(rec.location, 'global');
        const currentMachineType = cleanValue(rec.currentMachineType);
        const recommendedMachineType = cleanValue(rec.recommendedMachineType);
        const under = isUnderprovisioned(rec);
        const meta = SERVICE_ICONS[rec.service ?? ''] ?? { icon: 'mdi:cloud-outline', color: '#6B7280', bg: '#6B728014' };

        return (
          <div
            key={rec.recommendationId || `${rec.resourceName}-${idx}`}
            className="grid grid-cols-12 items-center px-5 py-3.5 border-t border-[rgba(0,0,0,0.04)] hover:bg-[rgba(0,0,0,0.01)] transition-colors text-[13px]"
          >
            {/* Resource name */}
            <div className="col-span-3 flex items-center gap-2.5 min-w-0">
              <div className="w-7 h-7 rounded-lg flex items-center justify-center flex-shrink-0 border border-[rgba(0,0,0,0.08)]" style={{ backgroundColor: meta.bg }}>
                <Icon icon={meta.icon} width={14} color={meta.color} />
              </div>
              <span className="font-semibold text-[rgba(0,0,0,0.88)] truncate">{resourceName}</span>
            </div>

            {/* Service + badge */}
            <div className="col-span-2 flex items-center gap-1.5 flex-wrap">
              <span className="text-[rgba(0,0,0,0.55)]">{service}</span>
              {under && (
                <span className="inline-flex items-center gap-0.5 px-1.5 py-0.5 rounded-full text-[10px] font-semibold bg-amber-50 text-amber-600 border border-amber-100">
                  <Icon icon="mdi:lightning-bolt" width={9} />
                  Perf
                </span>
              )}
            </div>

            {/* Location */}
            <div className="col-span-2 text-[rgba(0,0,0,0.55)]">{location}</div>

            {/* Machine type comparison */}
            <div className="col-span-3 flex items-center gap-2">
              <div>
                <p className="font-mono text-[12px] font-semibold text-rose-600">{currentMachineType}</p>
                <p className="text-[10px] text-[rgba(0,0,0,0.35)]">Current</p>
              </div>
              <svg className="w-3 h-3 text-[rgba(0,0,0,0.25)] flex-shrink-0" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 5l7 7-7 7" />
              </svg>
              <div>
                <p className="font-mono text-[12px] font-semibold text-emerald-600">{recommendedMachineType}</p>
                <p className="text-[10px] text-[rgba(0,0,0,0.35)]">Recommended</p>
              </div>
            </div>

            {/* Impact label */}
            <div className="col-span-1">
              <span className={`text-[10px] font-semibold px-2 py-0.5 rounded-full border ${
                under
                  ? 'bg-amber-50 text-amber-600 border-amber-100'
                  : 'bg-emerald-50 text-emerald-600 border-emerald-100'
              }`}>
                {under ? 'Upgrade' : 'Downsize'}
              </span>
            </div>

            {/* Savings */}
            <div className="col-span-1 text-right">
              <span className={`font-semibold text-[13px] ${under ? 'text-amber-600' : 'text-emerald-600'}`}>
                {formatSavings(rec)}
              </span>
              <p className="text-[10px] text-[rgba(0,0,0,0.35)]">{under ? '30-day cost' : 'savings'}</p>
            </div>
          </div>
        );
      })}
    </div>
  );
};

export default GcpRightsizingTable;
