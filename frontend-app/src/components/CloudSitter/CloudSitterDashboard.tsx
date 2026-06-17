import React from 'react';
import { AnimatePresence, motion } from 'framer-motion';
import { Icon } from '@iconify/react';
import type { EC2Resource } from '../../types/cloudsitter';

const NUM_STYLE: React.CSSProperties = {
  fontFamily: "'Bebas Neue', 'Barlow Condensed', sans-serif",
  fontSize: '32px',
  fontWeight: 400,
  lineHeight: '32px',
  color: 'rgb(10,10,10)',
  fontVariantNumeric: 'tabular-nums',
};

const CLOUD_SVG = (
  <div className="absolute top-0 right-0 w-full h-full pointer-events-none opacity-[0.07]">
    <svg className="absolute -top-8 -right-12 w-32 h-32" viewBox="0 0 200 120" fill="currentColor" style={{ color: '#6b7280' }}>
      <path d="M140 70c0-15-12-27-27-27-3 0-6 1-9 2-6-14-20-24-36-24-17 0-31 10-36 24-3-1-6-2-9-2-15 0-27 12-27 27s12 27 27 27h117c15 0 27-12 27-27z"/>
    </svg>
  </div>
);

// Hourly on-demand prices (us-east-1 baseline)
const HOURLY_PRICES: Record<string, number> = {
  't2.nano': 0.0058, 't2.micro': 0.0116, 't2.small': 0.023, 't2.medium': 0.0464,
  't2.large': 0.0928, 't2.xlarge': 0.1856, 't2.2xlarge': 0.3712,
  't3.nano': 0.0052, 't3.micro': 0.0104, 't3.small': 0.0208, 't3.medium': 0.0416,
  't3.large': 0.0832, 't3.xlarge': 0.1664, 't3.2xlarge': 0.3328,
  'm5.large': 0.096, 'm5.xlarge': 0.192, 'm5.2xlarge': 0.384, 'm5.4xlarge': 0.768,
  'm5.8xlarge': 1.536, 'm5.12xlarge': 2.304, 'm5.24xlarge': 4.608,
  'c5.large': 0.085, 'c5.xlarge': 0.17, 'c5.2xlarge': 0.34, 'c5.4xlarge': 0.68,
  'r5.large': 0.126, 'r5.xlarge': 0.252, 'r5.2xlarge': 0.504, 'r5.4xlarge': 1.008,
};

function getHourlyPrice(instanceType: string): number {
  return HOURLY_PRICES[instanceType.toLowerCase()] ?? 0.05;
}

function getMonthlyRunCost(resource: EC2Resource): number {
  return Math.round(getHourlyPrice(resource.type) * 730 * 100) / 100;
}

function getPotentialSavings(resource: EC2Resource): number | null {
  if (resource.savings > 0) return resource.savings;
  if (resource.recommendedSavings === undefined) return null;
  return resource.recommendedSavings ?? 0;
}

function isRecommendationLoading(resource: EC2Resource): boolean {
  return !resource.policyName && resource.state === 'running' && resource.recommendedSavings === undefined;
}

interface KpiData {
  totalInstances: number;
  managedCount: number;
  totalMonthlyCost: number;
  potentialSavings: number;
}

function isActive(resource: EC2Resource): boolean {
  return resource.state !== 'terminated' && resource.state !== 'stopped';
}

function computeKpis(allResources: EC2Resource[]): KpiData {
  const activeResources = allResources.filter(isActive);
  const managedCount = activeResources.filter((r) => r.policyName).length;
  const totalMonthlyCost = activeResources.reduce((sum, r) => sum + getMonthlyRunCost(r), 0);
  return {
    totalInstances: allResources.length,
    managedCount,
    totalMonthlyCost: Math.round(totalMonthlyCost * 100) / 100,
    potentialSavings: 0, // Not used anymore but kept for compatibility
  };
}

interface CloudSitterDashboardProps {
  accountId: string | null;
  isLoading: boolean;
  searchQuery: string;
  resources: EC2Resource[];
  allResources: EC2Resource[];
  onSearchChange: (query: string) => void;
  onRefresh: () => void;
  onCreatePolicy: () => void;
  onManagePolicies: () => void;
  onAssignPolicy: (resource: EC2Resource) => void;
  provider?: 'AWS' | 'GCP' | 'AZURE';
  page?: number;
  pageSize?: number;
  totalPages?: number;
  filteredCount?: number;
  onPageChange?: (page: number) => void;
  onPageSizeChange?: (pageSize: number) => void;
}

const PROVIDER_META: Record<string, { icon: string; label: string; color: string }> = {
  AWS:   { icon: 'mdi:aws',              label: 'EC2 Instances',    color: '#FF9900' },
  GCP:   { icon: 'mdi:google-cloud',     label: 'VM Instances',     color: '#4285F4' },
  AZURE: { icon: 'mdi:microsoft-azure',  label: 'Virtual Machines', color: '#0078D4' },
};

const CloudSitterDashboard: React.FC<CloudSitterDashboardProps> = ({
  accountId,
  isLoading,
  searchQuery,
  resources,
  allResources,
  onSearchChange,
  onRefresh,
  onCreatePolicy,
  onManagePolicies,
  onAssignPolicy,
  provider = 'AWS',
  page = 0,
  pageSize = 10,
  totalPages = 0,
  filteredCount = 0,
  onPageChange,
  onPageSizeChange,
}) => {
  const kpi = computeKpis(allResources);
  const coveragePct = kpi.totalInstances > 0
    ? Math.round((kpi.managedCount / kpi.totalInstances) * 100)
    : 0;
  const provMeta = PROVIDER_META[provider];

  return (
    <>
      {/* KPI Cards */}
      {(allResources.length > 0 || isLoading) && (
        <div className="grid grid-cols-3 gap-4 mb-5">
          {/* Total Instances */}
          <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-5 relative overflow-hidden card-hover-lift" style={{ animation: 'fadeInUp 0.4s cubic-bezier(0.22,1,0.36,1) 0s both' }}>
            {CLOUD_SVG}
            <div className="relative z-10">
              <div className="flex items-start justify-between mb-2">
                <p className="text-[12px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.88)]">Total Instances</p>
                <div className="w-8 h-8 rounded-lg flex items-center justify-center flex-shrink-0" style={{ backgroundColor: '#6366F114' }}>
                  <Icon icon="mdi:server" width={18} color="#6366F1" />
                </div>
              </div>
              {isLoading
                ? <div className="h-8 w-16 bg-[rgba(0,0,0,0.06)] rounded animate-pulse" />
                : <p style={NUM_STYLE}>{kpi.totalInstances}</p>}
              <p className="text-[11px] text-[rgba(0,0,0,0.35)] mt-1">resources tracked</p>
            </div>
          </div>

          {/* Managed */}
          <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-5 relative overflow-hidden card-hover-lift" style={{ animation: 'fadeInUp 0.4s cubic-bezier(0.22,1,0.36,1) 0.06s both' }}>
            {CLOUD_SVG}
            <div className="relative z-10">
              <div className="flex items-start justify-between mb-2">
                <p className="text-[12px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.88)]">Managed</p>
                <div className="w-8 h-8 rounded-lg flex items-center justify-center flex-shrink-0" style={{ backgroundColor: '#16A34A14' }}>
                  <Icon icon="mdi:shield-check-outline" width={18} color="#16A34A" />
                </div>
              </div>
              {isLoading
                ? <div className="h-8 w-20 bg-[rgba(0,0,0,0.06)] rounded animate-pulse" />
                : <p style={NUM_STYLE}>{kpi.managedCount}<span className="text-[16px] font-normal text-[rgba(0,0,0,0.35)] ml-1">/ {kpi.totalInstances}</span></p>}
              <p className="text-[11px] text-[rgba(0,0,0,0.35)] mt-1">{coveragePct}% coverage</p>
            </div>
          </div>

          {/* Total Monthly Cost */}
          <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-5 relative overflow-hidden card-hover-lift" style={{ animation: 'fadeInUp 0.4s cubic-bezier(0.22,1,0.36,1) 0.12s both' }}>
            {CLOUD_SVG}
            <div className="relative z-10">
              <div className="flex items-start justify-between mb-2">
                <p className="text-[12px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.88)]">Monthly Cost</p>
                <div className="w-8 h-8 rounded-lg flex items-center justify-center flex-shrink-0" style={{ backgroundColor: '#3B82F614' }}>
                  <Icon icon="mdi:currency-usd" width={18} color="#3B82F6" />
                </div>
              </div>
              {isLoading
                ? <div className="h-8 w-24 bg-[rgba(0,0,0,0.06)] rounded animate-pulse" />
                : <p style={NUM_STYLE}>${kpi.totalMonthlyCost.toLocaleString('en-US', { minimumFractionDigits: 0, maximumFractionDigits: 0 })}</p>}
              <p className="text-[11px] text-[rgba(0,0,0,0.35)] mt-1">24/7 run cost est.</p>
            </div>
          </div>
        </div>
      )}

      {/* Filter / Toolbar */}
      <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm px-4 py-3 mb-5 flex items-center gap-3 flex-wrap">
        <div className="flex items-center gap-2">
          <Icon icon={provMeta.icon} width={14} color={provMeta.color} />
          <span className="text-[12px] font-semibold text-[rgba(0,0,0,0.55)]">{provMeta.label}</span>
        </div>

        <div className="flex items-center gap-2 ml-auto">
          <button
            onClick={onRefresh}
            disabled={isLoading}
            className="flex items-center gap-1.5 text-[11px] font-semibold text-[rgba(0,0,0,0.5)] hover:text-[rgba(0,0,0,0.88)] transition-colors disabled:opacity-40 border border-[rgba(0,0,0,0.1)] rounded-lg px-3 py-1.5"
          >
            <svg className={`w-3 h-3 ${isLoading ? 'animate-spin' : ''}`} fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2.5}
                d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15" />
            </svg>
            Refresh
          </button>

          <button
            onClick={onManagePolicies}
            className="flex items-center gap-1.5 text-[11px] font-semibold text-[rgba(0,0,0,0.5)] hover:text-[rgba(0,0,0,0.88)] transition-colors border border-[rgba(0,0,0,0.1)] rounded-lg px-3 py-1.5"
          >
            <Icon icon="mdi:cog-outline" width={12} />
            Manage Policies
          </button>

          <div className="relative">
            <Icon icon="mdi:magnify" width={13} className="absolute left-2.5 top-1/2 -translate-y-1/2 text-[rgba(0,0,0,0.3)]" />
            <input
              type="text"
              value={searchQuery}
              onChange={(e) => onSearchChange(e.target.value)}
              placeholder="Search name or ID…"
              className="pl-7 pr-3 py-1.5 text-[12px] border border-[rgba(0,0,0,0.1)] rounded-lg focus:outline-none focus:ring-2 focus:ring-indigo-300 bg-white w-48 text-[rgba(0,0,0,0.88)] placeholder:text-[rgba(0,0,0,0.3)]"
            />
          </div>

          <button
            onClick={onCreatePolicy}
            className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-indigo-600 hover:bg-indigo-700 text-white text-[12px] font-semibold transition-colors"
          >
            <Icon icon="mdi:plus" width={13} />
            Create Policy
          </button>
        </div>
      </div>

      <AnimatePresence mode="wait">
        {/* Loading skeletons */}
        {isLoading && (
          <motion.div
            key="loading"
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            transition={{ duration: 0.15 }}
            className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm overflow-hidden"
          >
            {Array.from({ length: 5 }).map((_, i) => (
              <div key={i} className="px-5 py-4 border-t border-[rgba(0,0,0,0.04)] animate-pulse flex items-center gap-4">
                <div className="w-8 h-8 rounded-lg bg-[rgba(0,0,0,0.06)] flex-shrink-0" />
                <div className="flex-1 space-y-2">
                  <div className="h-2.5 w-40 bg-[rgba(0,0,0,0.06)] rounded" />
                  <div className="h-2 w-24 bg-[rgba(0,0,0,0.04)] rounded" />
                </div>
                <div className="h-6 w-24 bg-[rgba(0,0,0,0.04)] rounded-full" />
              </div>
            ))}
          </motion.div>
        )}

        {/* Empty State */}
        {!isLoading && (!accountId || resources.length === 0) && (
          <motion.div
            key="empty"
            initial={{ opacity: 0, y: 8 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ duration: 0.22, ease: 'easeOut' }}
            className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-10 text-center"
          >
            <div className="w-12 h-12 bg-[rgba(0,0,0,0.04)] rounded-full flex items-center justify-center mx-auto mb-3">
              <Icon icon="mdi:server" width={22} color="rgba(0,0,0,0.3)" />
            </div>
            <p className="text-[14px] font-semibold text-[rgba(0,0,0,0.88)] mb-1">No Resources Found</p>
            <p className="text-[13px] text-[rgba(0,0,0,0.45)]">
              {accountId
                ? 'No instances matched your search criteria.'
                : 'Select an account from the sidebar to view resources.'}
            </p>
          </motion.div>
        )}

        {/* Resources Table */}
        {!isLoading && resources.length > 0 && (
          <motion.div
            key="content"
            initial={{ opacity: 0, y: 10 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ duration: 0.28, ease: [0.22, 1, 0.36, 1] }}
            className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm overflow-hidden widget-panel-hover"
          >
          {/* Column headers */}
          <div className="grid grid-cols-11 px-5 py-2 bg-[rgba(0,0,0,0.02)] text-[11px] font-semibold uppercase tracking-wide text-[rgba(0,0,0,0.4)] border-b border-[rgba(0,0,0,0.06)]">
            <div className="col-span-3">Name / ID</div>
            <div className="col-span-2">Region</div>
            <div className="col-span-2">Type</div>
            <div className="col-span-1">State</div>
            <div className="col-span-2">CloudSitter Policy</div>
            <div className="col-span-1 text-center">Actions</div>
          </div>

          {resources.map((resource, idx) => {
            const isTerminated = resource.state === 'terminated' || resource.state === 'stopped';
            const hasPolicy    = !!resource.policyName;

            return (
              <div
                key={resource.id}
                className="grid grid-cols-11 items-center px-5 py-3.5 border-t border-[rgba(0,0,0,0.04)] hover:bg-[rgba(0,0,0,0.01)] transition-colors text-[13px]"
                style={{ animation: `fadeInUp 0.3s ease ${Math.min(idx * 0.03, 0.45)}s both` }}
              >
                {/* Name / ID */}
                <div className="col-span-3 flex items-center gap-2.5 min-w-0">
                  <div className="w-7 h-7 rounded-lg flex items-center justify-center flex-shrink-0 border border-[rgba(0,0,0,0.08)]" style={{ backgroundColor: '#6366F114' }}>
                    <Icon icon="mdi:server" width={14} color="#6366F1" />
                  </div>
                  <div className="min-w-0">
                    <p className="font-semibold text-[rgba(0,0,0,0.88)] truncate">{resource.name || '—'}</p>
                    <div className="flex items-center gap-1 text-[11px] text-[rgba(0,0,0,0.35)] font-mono">
                      <span className="truncate">{resource.id}</span>
                      <button
                        onClick={(e) => { e.stopPropagation(); navigator.clipboard.writeText(resource.id); }}
                        className="flex-shrink-0 hover:text-indigo-500 transition-colors"
                        title="Copy ID"
                      >
                        <Icon icon="mdi:content-copy" width={10} />
                      </button>
                    </div>
                  </div>
                </div>

                {/* Region */}
                <div className="col-span-2 flex items-center gap-1.5 text-[rgba(0,0,0,0.55)]">
                  <Icon icon={provMeta.icon} width={13} color={provMeta.color} />
                  <span>{resource.region}</span>
                </div>

                {/* Type */}
                <div className="col-span-2">
                  <span className="px-2 py-0.5 rounded bg-[rgba(0,0,0,0.04)] text-[11px] font-mono text-[rgba(0,0,0,0.65)] border border-[rgba(0,0,0,0.06)]">
                    {resource.type}
                  </span>
                </div>

                {/* State */}
                <div className="col-span-1 flex items-center gap-1.5">
                  <div className={`w-2 h-2 rounded-full flex-shrink-0 ${resource.state === 'running' ? 'bg-emerald-500' : 'bg-[rgba(0,0,0,0.2)]'}`} />
                  <span className="capitalize text-[rgba(0,0,0,0.65)]">{resource.state}</span>
                </div>

                {/* Policy */}
                <div className="col-span-2">
                  {resource.policyName ? (
                    <span className="inline-flex items-center gap-1 bg-emerald-50 text-emerald-700 border border-emerald-100 px-2.5 py-0.5 rounded-full text-[11px] font-semibold">
                      <Icon icon="mdi:clock-outline" width={11} />
                      {resource.policyName}
                    </span>
                  ) : (
                    <span className="text-[11px] text-[rgba(0,0,0,0.3)] italic">No Policy</span>
                  )}
                </div>

                {/* Actions */}
                <div className="col-span-1 flex justify-center">
                  <button
                    onClick={() => onAssignPolicy(resource)}
                    className="flex items-center gap-1 px-2.5 py-1 rounded-lg text-[11px] font-semibold text-indigo-600 border border-indigo-200 hover:bg-indigo-50 transition-all active:scale-95"
                  >
                    <Icon icon="mdi:calendar-check-outline" width={11} />
                    Assign
                  </button>
                </div>
              </div>
            );
          })}
          
          {/* Pagination Controls */}
          {totalPages > 1 && onPageChange && (
            <div className="flex items-center justify-between px-5 py-3 border-t border-[rgba(0,0,0,0.08)] bg-[rgba(0,0,0,0.01)]">
              <div className="flex items-center gap-3">
                <span className="text-[12px] text-[rgba(0,0,0,0.5)]">
                  Showing {page * pageSize + 1}–{Math.min((page + 1) * pageSize, filteredCount)} of {filteredCount}
                </span>
                {onPageSizeChange && (
                  <div className="flex items-center gap-1.5">
                    <span className="text-[11px] text-[rgba(0,0,0,0.45)]">Per page:</span>
                    <select
                      value={pageSize}
                      onChange={(e) => {
                        onPageSizeChange(Number(e.target.value));
                        onPageChange(0);
                      }}
                      className="text-[11px] border border-[rgba(0,0,0,0.1)] rounded px-2 py-1 bg-white text-[rgba(0,0,0,0.75)] focus:outline-none focus:ring-2 focus:ring-indigo-300"
                    >
                      <option value={5}>5</option>
                      <option value={10}>10</option>
                      <option value={25}>25</option>
                      <option value={50}>50</option>
                      <option value={100}>100</option>
                    </select>
                  </div>
                )}
              </div>
              
              <div className="flex items-center gap-1">
                <button
                  onClick={() => onPageChange(0)}
                  disabled={page === 0}
                  className="w-7 h-7 flex items-center justify-center rounded border border-[rgba(0,0,0,0.1)] text-[rgba(0,0,0,0.5)] hover:bg-[rgba(0,0,0,0.03)] disabled:opacity-30 disabled:cursor-not-allowed transition-colors"
                  title="First page"
                >
                  <Icon icon="mdi:chevron-double-left" width={14} />
                </button>
                <button
                  onClick={() => onPageChange(page - 1)}
                  disabled={page === 0}
                  className="w-7 h-7 flex items-center justify-center rounded border border-[rgba(0,0,0,0.1)] text-[rgba(0,0,0,0.5)] hover:bg-[rgba(0,0,0,0.03)] disabled:opacity-30 disabled:cursor-not-allowed transition-colors"
                  title="Previous page"
                >
                  <Icon icon="mdi:chevron-left" width={14} />
                </button>
                
                {/* Page numbers */}
                <div className="flex items-center gap-0.5 mx-1">
                  {Array.from({ length: Math.min(5, totalPages) }, (_, i) => {
                    let pageNum: number;
                    if (totalPages <= 5) {
                      pageNum = i;
                    } else if (page < 3) {
                      pageNum = i;
                    } else if (page > totalPages - 4) {
                      pageNum = totalPages - 5 + i;
                    } else {
                      pageNum = page - 2 + i;
                    }
                    
                    return (
                      <button
                        key={pageNum}
                        onClick={() => onPageChange(pageNum)}
                        className={`min-w-[28px] h-7 px-2 flex items-center justify-center rounded text-[11px] font-semibold transition-colors ${
                          page === pageNum
                            ? 'bg-indigo-600 text-white'
                            : 'border border-[rgba(0,0,0,0.1)] text-[rgba(0,0,0,0.5)] hover:bg-[rgba(0,0,0,0.03)]'
                        }`}
                      >
                        {pageNum + 1}
                      </button>
                    );
                  })}
                </div>
                
                <button
                  onClick={() => onPageChange(page + 1)}
                  disabled={page >= totalPages - 1}
                  className="w-7 h-7 flex items-center justify-center rounded border border-[rgba(0,0,0,0.1)] text-[rgba(0,0,0,0.5)] hover:bg-[rgba(0,0,0,0.03)] disabled:opacity-30 disabled:cursor-not-allowed transition-colors"
                  title="Next page"
                >
                  <Icon icon="mdi:chevron-right" width={14} />
                </button>
                <button
                  onClick={() => onPageChange(totalPages - 1)}
                  disabled={page >= totalPages - 1}
                  className="w-7 h-7 flex items-center justify-center rounded border border-[rgba(0,0,0,0.1)] text-[rgba(0,0,0,0.5)] hover:bg-[rgba(0,0,0,0.03)] disabled:opacity-30 disabled:cursor-not-allowed transition-colors"
                  title="Last page"
                >
                  <Icon icon="mdi:chevron-double-right" width={14} />
                </button>
              </div>
            </div>
          )}
          </motion.div>
        )}
      </AnimatePresence>
    </>
  );
};

export default CloudSitterDashboard;
