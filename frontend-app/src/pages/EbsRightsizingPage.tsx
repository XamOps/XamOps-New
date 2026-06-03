import React, { useState, useEffect } from 'react';
import ReactDOM from 'react-dom';
import { AnimatePresence, motion } from 'framer-motion';
import { Icon } from '@iconify/react';
import { useAccounts } from '../contexts/AccountContext';
import { useEbsRightsizingData, EbsVolume, RoiResult, PendingDeletionVolume } from '../hooks/useEbsRightsizingData';
import { useTopbarActions } from '../contexts/TopbarContext';
import { EbsVolumeMonitoringDrawer } from '../components/EbsRightsizing/EbsVolumeMonitoringDrawer';
import { useTabState } from '../hooks/useTabState';

interface GuideTarget {
  volumeId: string;
  region: string;
  instanceId?: string;
  currentSizeGb: number;
  recommendedSizeGb?: number;
  recommendation: 'SHRINK' | 'EXPAND';
  isRootVolume?: boolean;
  availabilityZone?: string;
  volumeType?: string;
}

type Tab = 'overview' | 'roi';

const CLOUD_SVG = (
  <div className="absolute top-0 right-0 w-full h-full pointer-events-none opacity-[0.07]">
    <svg className="absolute -top-8 -right-12 w-32 h-32" viewBox="0 0 200 120" fill="currentColor" style={{ color: '#6b7280' }}>
      <path d="M140 70c0-15-12-27-27-27-3 0-6 1-9 2-6-14-20-24-36-24-17 0-31 10-36 24-3-1-6-2-9-2-15 0-27 12-27 27s12 27 27 27h117c15 0 27-12 27-27z"/>
    </svg>
  </div>
);

const NUM_STYLE: React.CSSProperties = {
  fontFamily: "'Bebas Neue', 'Barlow Condensed', sans-serif",
  fontSize: '28px',
  fontWeight: 400,
  lineHeight: '28px',
  color: 'rgb(10,10,10)',
  fontVariantNumeric: 'tabular-nums',
};

const REC_BADGE: Record<string, { label: string; cls: string; icon: string }> = {
  DELETE_UNATTACHED: { label: 'Delete',        cls: 'bg-rose-50 text-rose-600 border-rose-100',   icon: 'mdi:trash-can-outline' },
  UPGRADE_TYPE:      { label: 'Upgrade Type',  cls: 'bg-blue-50 text-blue-600 border-blue-100',   icon: 'mdi:arrow-up-circle-outline' },
  EXPAND:            { label: 'Expand',        cls: 'bg-amber-50 text-amber-600 border-amber-100', icon: 'mdi:arrow-expand' },
  SHRINK:            { label: 'Shrink',        cls: 'bg-orange-50 text-orange-600 border-orange-100', icon: 'mdi:arrow-collapse' },
};

const recommendationBadge = (r: string) => {
  const cfg = REC_BADGE[r];
  if (!cfg) return <span className="px-2 py-0.5 rounded-full text-[11px] font-semibold bg-[rgba(0,0,0,0.04)] text-[rgba(0,0,0,0.45)] border border-[rgba(0,0,0,0.08)]">None</span>;
  return (
    <span className={`inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-[11px] font-semibold border ${cfg.cls}`}>
      <Icon icon={cfg.icon} width={10} />
      {cfg.label}
    </span>
  );
};

const DISK_TYPES = ['gp2', 'gp3', 'io1', 'io2', 'sc1', 'st1'];

const EbsRightsizingPage: React.FC = () => {
  const { selectedAccountIds } = useAccounts();
  const selectedAccountId = selectedAccountIds[0] || null;
  const [activeTab, setActiveTab] = useTabState<Tab>('overview');
  const [actionMsg, setActionMsg] = useState<{ type: 'success' | 'error'; text: string } | null>(null);
  const [guideTarget, setGuideTarget] = useState<GuideTarget | null>(null);
  const [monitoringVolume, setMonitoringVolume] = useState<EbsVolume | null>(null);
  const [filterSearch, setFilterSearch] = useState('');
  const [filterRegion, setFilterRegion] = useState('');
  const [filterType, setFilterType] = useState('');
  const [filterRecommendation, setFilterRecommendation] = useState('');
  const [showHelpModal, setShowHelpModal] = useState(false);
  const { setTitle, setActions, clearActions } = useTopbarActions();

  const {
    volumes, pendingDeletion,
    isLoading, isDiskUsageLoading, isRefreshing, ssmUnavailableInstances, totalWastedMonthly,
    unattachedCount, shrinkCount, expandCount,
    calculateRoi, refreshAll, deletePendingVolume, keepVolume,
  } = useEbsRightsizingData({ selectedAccountId });

  const uniqueRegions = Array.from(new Set(volumes.map(v => v.region))).sort();
  const uniqueTypes = Array.from(new Set(volumes.map(v => v.volumeType))).sort();

  const instancesNeedingAgent = ssmUnavailableInstances.filter(instanceId =>
    !volumes.some(v => v.instanceId === instanceId && (v.usedPercent !== undefined || v.agentDeployed))
  );

  const filteredVolumes = volumes.filter(v => {
    if (filterSearch && !v.volumeId.includes(filterSearch) && !(v.volumeName ?? '').toLowerCase().includes(filterSearch.toLowerCase())) return false;
    if (filterRegion && v.region !== filterRegion) return false;
    if (filterType && v.volumeType !== filterType) return false;
    if (filterRecommendation && v.recommendation !== filterRecommendation) return false;
    return true;
  });

  useEffect(() => { setPage(1); }, [filterSearch, filterRegion, filterType, filterRecommendation]);

  useEffect(() => {
    setTitle('Disk Rightsizing');
    setActions(
      <div className="flex items-center gap-2">
        <span className="w-1.5 h-1.5 rounded-full bg-indigo-500" />
        <span className="text-[11px] font-medium uppercase tracking-wide text-[rgba(0,0,0,0.4)]">EBS</span>
        <span className="text-[rgba(0,0,0,0.15)] text-[12px] select-none">|</span>
        <button
          onClick={() => setShowHelpModal(true)}
          className="flex items-center gap-1.5 text-[11px] font-semibold text-[rgba(0,0,0,0.5)] hover:text-[rgba(0,0,0,0.88)] transition-colors"
        >
          <Icon icon="mdi:book-open-outline" width={13} />
          Guide
        </button>
        <span className="text-[rgba(0,0,0,0.15)] text-[12px] select-none">|</span>
        <button
          onClick={refreshAll}
          disabled={isRefreshing}
          className="flex items-center gap-1.5 text-[11px] font-semibold text-[rgba(0,0,0,0.5)] hover:text-[rgba(0,0,0,0.88)] transition-colors disabled:opacity-40"
        >
          <svg className={`w-3 h-3 ${isRefreshing ? 'animate-spin' : ''}`} fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2.5} d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15" />
          </svg>
          {isRefreshing ? 'Refreshing…' : 'Refresh'}
        </button>
      </div>
    );
    return () => { clearActions(); setTitle(null); };
  }, [isRefreshing, refreshAll]);

  const PAGE_SIZE = 15;
  const [page, setPage] = useState(1);
  const totalPages = Math.ceil(filteredVolumes.length / PAGE_SIZE);
  const pagedVolumes = filteredVolumes.slice((page - 1) * PAGE_SIZE, page * PAGE_SIZE);

  return (
    <main className="flex-1 px-6 pt-4 pb-6 space-y-5">

      {/* Action feedback */}
      {actionMsg && (
        <div className={`flex items-center gap-2 px-4 py-3 rounded-xl text-[13px] font-medium border ${
          actionMsg.type === 'success'
            ? 'bg-emerald-50 text-emerald-700 border-emerald-200'
            : 'bg-rose-50 text-rose-700 border-rose-200'
        }`}>
          <Icon icon={actionMsg.type === 'success' ? 'mdi:check-circle-outline' : 'mdi:alert-circle-outline'} width={16} />
          {actionMsg.text}
          <button className="ml-auto opacity-50 hover:opacity-100" onClick={() => setActionMsg(null)}>
            <Icon icon="mdi:close" width={14} />
          </button>
        </div>
      )}

      {!selectedAccountId ? (
        <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-10 text-center">
          <div className="w-12 h-12 bg-[rgba(0,0,0,0.04)] rounded-full flex items-center justify-center mx-auto mb-3">
            <Icon icon="mdi:harddisk" width={22} color="rgba(0,0,0,0.3)" />
          </div>
          <p className="text-[14px] font-semibold text-[rgba(0,0,0,0.88)] mb-1">No Account Selected</p>
          <p className="text-[13px] text-[rgba(0,0,0,0.45)]">Select an AWS account from the sidebar.</p>
        </div>
      ) : (
        <>
          {/* Tab switcher */}
          <div className="flex gap-1 bg-[rgba(0,0,0,0.03)] border border-[rgba(0,0,0,0.07)] rounded-xl p-1 w-fit">
            {([
              { id: 'overview', label: 'Overview',       icon: 'mdi:view-dashboard-outline' },
              { id: 'roi',      label: 'ROI Calculator', icon: 'mdi:calculator-variant-outline' },
            ] as const).map(tab => (
              <button
                key={tab.id}
                onClick={() => setActiveTab(tab.id)}
                className={`flex items-center gap-2 px-4 py-2 rounded-lg text-[12px] font-semibold transition-all ${
                  activeTab === tab.id
                    ? 'bg-white shadow-sm text-[rgba(0,0,0,0.88)] border border-[rgba(0,0,0,0.08)]'
                    : 'text-[rgba(0,0,0,0.45)] hover:text-[rgba(0,0,0,0.65)]'
                }`}
              >
                <Icon icon={tab.icon} width={14} />
                {tab.label}
              </button>
            ))}
          </div>

          <AnimatePresence mode="wait">
          {/* OVERVIEW TAB */}
          {activeTab === 'overview' && (
            <motion.div
              key="overview"
              className="space-y-5"
              initial={{ opacity: 0, y: 8 }}
              animate={{ opacity: 1, y: 0 }}
              exit={{ opacity: 0, y: -4 }}
              transition={{ duration: 0.18, ease: 'easeOut' }}
            >
              {/* KPI cards */}
              <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-6 gap-4">
                {[
                  { icon: 'mdi:harddisk',              label: 'Total Volumes',      value: String(volumes.length),                                  iconColor: '#6366F1', iconBg: '#6366F114' },
                  { icon: 'mdi:database-outline',      label: 'Total Storage',      value: `${volumes.reduce((s,v)=>s+v.sizeGb,0)} GB`,            iconColor: '#3B82F6', iconBg: '#3B82F614' },
                  { icon: 'mdi:currency-usd',          label: 'Monthly Cost',       value: `$${volumes.reduce((s,v)=>s+v.monthlyCost,0).toFixed(2)}`, iconColor: '#16A34A', iconBg: '#16A34A14' },
                  { icon: 'mdi:link-off',              label: 'Unattached',         value: String(unattachedCount),                                 iconColor: '#DC2626', iconBg: '#DC262614' },
                  { icon: 'mdi:arrow-collapse',        label: 'Shrink',             value: isDiskUsageLoading ? '…' : String(shrinkCount),          iconColor: '#F59E0B', iconBg: '#F59E0B14' },
                  { icon: 'mdi:piggy-bank-outline',    label: 'Savings/mo',         value: `$${totalWastedMonthly.toFixed(2)}`,                     iconColor: '#16A34A', iconBg: '#16A34A14' },
                ].map((card, i) => (
                  <div key={card.label} className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-4 relative overflow-hidden card-hover-lift" style={{ animation: `fadeInUp 0.4s cubic-bezier(0.22,1,0.36,1) ${i * 0.06}s both` }}>
                    {CLOUD_SVG}
                    <div className="relative z-10 flex items-center justify-between mb-2">
                      <p className="text-[11px] font-semibold uppercase tracking-[0.07em] text-[rgba(0,0,0,0.55)]">{card.label}</p>
                      <div className="w-7 h-7 rounded-lg flex items-center justify-center flex-shrink-0" style={{ backgroundColor: card.iconBg }}>
                        <Icon icon={card.icon} width={14} color={card.iconColor} />
                      </div>
                    </div>
                    {isLoading ? (
                      <div className="h-7 w-16 bg-[rgba(0,0,0,0.06)] rounded animate-pulse" />
                    ) : (
                      <p style={NUM_STYLE}>{card.value}</p>
                    )}
                  </div>
                ))}
              </div>

              {/* Pending deletion */}
              {pendingDeletion.length > 0 && (
                <PendingDeletionPanel
                  volumes={pendingDeletion}
                  onDelete={async (v) => {
                    const r = await deletePendingVolume(v.volumeId, v.region);
                    if (r.success) setActionMsg({ type: 'success', text: r.message });
                    else setActionMsg({ type: 'error', text: r.error });
                  }}
                  onKeep={async (v) => {
                    const r = await keepVolume(v.volumeId, v.region);
                    if (r.success) setActionMsg({ type: 'success', text: r.message });
                    else setActionMsg({ type: 'error', text: r.error });
                  }}
                />
              )}

              {/* SSM agent banner */}
              {!isDiskUsageLoading && instancesNeedingAgent.length > 0 && (
                <SsmSetupGuide count={instancesNeedingAgent.length} awsAccountId={selectedAccountId} />
              )}

              {/* Volumes table */}
              <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm overflow-hidden widget-panel-hover">
                {/* Table toolbar */}
                <div className="flex flex-wrap items-center gap-2 px-5 py-3.5 border-b border-[rgba(0,0,0,0.06)] bg-[rgba(0,0,0,0.015)]">
                  <div className="relative flex-1 min-w-[160px]">
                    <Icon icon="mdi:magnify" width={13} className="absolute left-2.5 top-1/2 -translate-y-1/2 text-[rgba(0,0,0,0.3)]" />
                    <input
                      type="text"
                      placeholder="Search volume ID or name…"
                      value={filterSearch}
                      onChange={e => setFilterSearch(e.target.value)}
                      className="w-full pl-7 pr-3 py-1.5 rounded-lg border border-[rgba(0,0,0,0.1)] bg-white text-[12px] text-[rgba(0,0,0,0.88)] placeholder:text-[rgba(0,0,0,0.3)] focus:outline-none focus:ring-2 focus:ring-indigo-300"
                    />
                  </div>
                  <select value={filterRegion} onChange={e => setFilterRegion(e.target.value)}
                    className="px-2.5 py-1.5 rounded-lg border border-[rgba(0,0,0,0.1)] bg-white text-[12px] text-[rgba(0,0,0,0.65)] focus:outline-none focus:ring-2 focus:ring-indigo-300">
                    <option value="">All Regions</option>
                    {uniqueRegions.map(r => <option key={r} value={r}>{r}</option>)}
                  </select>
                  <select value={filterType} onChange={e => setFilterType(e.target.value)}
                    className="px-2.5 py-1.5 rounded-lg border border-[rgba(0,0,0,0.1)] bg-white text-[12px] text-[rgba(0,0,0,0.65)] focus:outline-none focus:ring-2 focus:ring-indigo-300">
                    <option value="">All Types</option>
                    {uniqueTypes.map(t => <option key={t} value={t}>{t}</option>)}
                  </select>
                  <select value={filterRecommendation} onChange={e => setFilterRecommendation(e.target.value)}
                    className="px-2.5 py-1.5 rounded-lg border border-[rgba(0,0,0,0.1)] bg-white text-[12px] text-[rgba(0,0,0,0.65)] focus:outline-none focus:ring-2 focus:ring-indigo-300">
                    <option value="">All Recommendations</option>
                    <option value="SHRINK">Shrink</option>
                    <option value="EXPAND">Expand</option>
                    <option value="DELETE_UNATTACHED">Delete Unattached</option>
                    <option value="UPGRADE_TYPE">Upgrade Type</option>
                    <option value="NONE">None</option>
                  </select>
                  {(filterSearch || filterRegion || filterType || filterRecommendation) && (
                    <button onClick={() => { setFilterSearch(''); setFilterRegion(''); setFilterType(''); setFilterRecommendation(''); }}
                      className="px-2.5 py-1.5 rounded-lg text-[11px] font-semibold text-rose-500 hover:bg-rose-50 transition-colors">
                      <Icon icon="mdi:close" width={12} className="inline mr-1" />Clear
                    </button>
                  )}
                  <span className="ml-auto text-[11px] text-[rgba(0,0,0,0.35)]">
                    {filteredVolumes.length}{filteredVolumes.length !== volumes.length ? ` of ${volumes.length}` : ''} volumes
                  </span>
                </div>

                {/* Column headers */}
                <div className="grid grid-cols-12 px-5 py-2 bg-[rgba(0,0,0,0.02)] text-[11px] font-semibold uppercase tracking-wide text-[rgba(0,0,0,0.4)] border-b border-[rgba(0,0,0,0.06)]">
                  <div className="col-span-3">Volume</div>
                  <div className="col-span-1">Region</div>
                  <div className="col-span-1">Type</div>
                  <div className="col-span-2 text-right">Size / Usage</div>
                  <div className="col-span-1">State</div>
                  <div className="col-span-2">Attached To</div>
                  <div className="col-span-1 text-right">Cost/mo</div>
                  <div className="col-span-1">Action</div>
                </div>

                {isLoading ? (
                  <div className="space-y-0">
                    {[1,2,3,4,5].map(i => (
                      <div key={i} className="px-5 py-4 border-t border-[rgba(0,0,0,0.04)] animate-pulse flex items-center gap-4">
                        <div className="w-8 h-8 rounded-lg bg-[rgba(0,0,0,0.06)] flex-shrink-0" />
                        <div className="flex-1 space-y-2">
                          <div className="h-2.5 w-32 bg-[rgba(0,0,0,0.06)] rounded" />
                          <div className="h-2 w-20 bg-[rgba(0,0,0,0.04)] rounded" />
                        </div>
                      </div>
                    ))}
                  </div>
                ) : volumes.length === 0 ? (
                  <div className="p-10 text-center">
                    <div className="w-12 h-12 bg-[rgba(0,0,0,0.04)] rounded-full flex items-center justify-center mx-auto mb-3">
                      <Icon icon="mdi:harddisk" width={22} color="rgba(0,0,0,0.3)" />
                    </div>
                    <p className="text-[14px] font-semibold text-[rgba(0,0,0,0.88)] mb-1">No Volumes Found</p>
                    <p className="text-[13px] text-[rgba(0,0,0,0.45)]">Click Refresh to scan for EBS volumes.</p>
                  </div>
                ) : (
                  <>
                    {pagedVolumes.map((vol, idx) => (
                      <div
                        key={vol.volumeId}
                        onClick={(e) => {
                          if ((e.target as HTMLElement).closest('button')) return;
                          setMonitoringVolume(vol);
                        }}
                        className="grid grid-cols-12 items-center px-5 py-3.5 border-t border-[rgba(0,0,0,0.04)] hover:bg-[rgba(0,0,0,0.01)] transition-colors cursor-pointer text-[13px]"
                        style={{ animation: `fadeInUp 0.3s ease ${Math.min(idx * 0.03, 0.45)}s both` }}
                      >
                        {/* Volume */}
                        <div className="col-span-3 min-w-0">
                          <div className="flex items-center gap-1.5">
                            <p className="font-mono text-[12px] text-[rgba(0,0,0,0.88)] font-semibold truncate">{vol.volumeId}</p>
                            {vol.encrypted && <Icon icon="mdi:lock" width={10} color="#6366F1" title="Encrypted" />}
                            {vol.isRootVolume && <Icon icon="mdi:server" width={10} color="#8B5CF6" title="Root Volume" />}
                          </div>
                          {vol.volumeName && <p className="text-[11px] text-[rgba(0,0,0,0.4)] truncate">{vol.volumeName}</p>}
                        </div>

                        {/* Region */}
                        <div className="col-span-1 text-[rgba(0,0,0,0.55)] text-[12px]">{vol.region}</div>

                        {/* Type */}
                        <div className="col-span-1">
                          <span className="px-1.5 py-0.5 rounded text-[11px] font-medium bg-blue-50 text-blue-600 border border-blue-100">{vol.volumeType}</span>
                        </div>

                        {/* Size / Usage */}
                        <div className="col-span-2 text-right">
                          <p className="font-semibold text-[rgba(0,0,0,0.88)]">{vol.sizeGb} GB</p>
                          {vol.usedPercent !== undefined ? (
                            <div className="mt-1">
                              <div className="w-20 h-1.5 bg-[rgba(0,0,0,0.06)] rounded-full overflow-hidden ml-auto">
                                <div
                                  className={`h-full rounded-full ${vol.usedPercent > 85 ? 'bg-rose-500' : vol.usedPercent > 65 ? 'bg-amber-400' : 'bg-emerald-500'}`}
                                  style={{ width: `${vol.usedPercent}%` }}
                                />
                              </div>
                              <p className="text-[10px] text-[rgba(0,0,0,0.35)] mt-0.5">{vol.usedGb}GB / {vol.freeGb}GB free</p>
                            </div>
                          ) : isDiskUsageLoading && vol.instanceId ? (
                            <div className="mt-1 flex items-center justify-end gap-1">
                              <div className="w-14 h-1.5 bg-[rgba(0,0,0,0.06)] rounded-full animate-pulse" />
                              <span className="text-[10px] text-[rgba(0,0,0,0.35)]">scanning…</span>
                            </div>
                          ) : vol.instanceId && ssmUnavailableInstances.includes(vol.instanceId) ? (
                            <div className="mt-1 flex items-center justify-end gap-1">
                              <Icon icon="mdi:alert-circle-outline" width={10} color="#F59E0B" />
                              <span className="text-[10px] text-amber-500">SSM unavail.</span>
                            </div>
                          ) : (
                            <p className="text-[11px] text-[rgba(0,0,0,0.3)]">—</p>
                          )}
                        </div>

                        {/* State */}
                        <div className="col-span-1">
                          {vol.attached ? (
                            <span className="flex items-center gap-1 text-[11px] font-medium text-emerald-600">
                              <span className="w-1.5 h-1.5 rounded-full bg-emerald-500 inline-block" />In Use
                            </span>
                          ) : (
                            <span className="flex items-center gap-1 text-[11px] font-medium text-amber-600">
                              <span className="w-1.5 h-1.5 rounded-full bg-amber-400 inline-block" />Unattached
                            </span>
                          )}
                        </div>

                        {/* Attached to */}
                        <div className="col-span-2">
                          {vol.instanceId ? (
                            <div>
                              <p className="font-mono text-[11px] text-[rgba(0,0,0,0.7)] truncate">{vol.instanceId}</p>
                              {vol.device && <p className="text-[10px] text-[rgba(0,0,0,0.35)]">{vol.device}{vol.isRootVolume ? ' · root' : ''}</p>}
                            </div>
                          ) : (
                            <span className="text-[rgba(0,0,0,0.3)]">—</span>
                          )}
                        </div>

                        {/* Cost */}
                        <div className="col-span-1 text-right font-semibold text-[rgba(0,0,0,0.88)]">
                          ${vol.monthlyCost.toFixed(2)}
                        </div>

                        {/* Recommendation */}
                        <div className="col-span-1">
                          {vol.recommendation === 'SHRINK' || vol.recommendation === 'EXPAND' ? (
                            <div className="space-y-1">
                              {recommendationBadge(vol.recommendation)}
                              <button
                                onClick={() => setGuideTarget({ volumeId: vol.volumeId, region: vol.region, instanceId: vol.instanceId ?? undefined, currentSizeGb: vol.sizeGb, recommendedSizeGb: vol.recommendedSizeGb, recommendation: vol.recommendation as 'SHRINK' | 'EXPAND', isRootVolume: vol.isRootVolume, volumeType: vol.volumeType })}
                                className="flex items-center gap-1 px-2 py-1 rounded-lg text-[10px] font-semibold border border-indigo-200 text-indigo-600 hover:bg-indigo-50 transition-all active:scale-95"
                              >
                                <Icon icon="mdi:book-open-outline" width={10} />How to fix
                              </button>
                            </div>
                          ) : vol.recommendation === 'DELETE_UNATTACHED' || vol.recommendation === 'UPGRADE_TYPE' ? (
                            <div className="space-y-1">
                              {recommendationBadge(vol.recommendation)}
                              {vol.recommendationReason && <p className="text-[10px] text-[rgba(0,0,0,0.4)] leading-snug">{vol.recommendationReason}</p>}
                            </div>
                          ) : (
                            <div>
                              <span className={`px-2 py-0.5 rounded-full text-[11px] font-semibold border ${
                                vol.usedPercent !== undefined ? 'bg-emerald-50 text-emerald-600 border-emerald-100'
                                : vol.agentDeployed ? 'bg-blue-50 text-blue-600 border-blue-100'
                                : vol.instanceId && ssmUnavailableInstances.includes(vol.instanceId) ? 'bg-amber-50 text-amber-600 border-amber-100'
                                : 'bg-[rgba(0,0,0,0.03)] text-[rgba(0,0,0,0.45)] border-[rgba(0,0,0,0.08)]'
                              }`}>
                                {vol.usedPercent !== undefined ? 'Optimal'
                                  : vol.agentDeployed ? 'Refreshing…'
                                  : vol.instanceId && ssmUnavailableInstances.includes(vol.instanceId) ? 'No agent'
                                  : 'No action'}
                              </span>
                            </div>
                          )}
                        </div>
                      </div>
                    ))}

                    {/* Pagination */}
                    {totalPages > 1 && (
                      <div className="px-5 py-3 border-t border-[rgba(0,0,0,0.06)] flex items-center justify-between">
                        <span className="text-[11px] text-[rgba(0,0,0,0.4)]">
                          {(page - 1) * PAGE_SIZE + 1}–{Math.min(page * PAGE_SIZE, filteredVolumes.length)} of {filteredVolumes.length}
                        </span>
                        <div className="flex items-center gap-1">
                          <button
                            onClick={() => setPage(p => Math.max(1, p - 1))}
                            disabled={page === 1}
                            className="px-2.5 py-1.5 rounded-lg text-[11px] font-medium border border-[rgba(0,0,0,0.1)] text-[rgba(0,0,0,0.55)] hover:bg-[rgba(0,0,0,0.03)] disabled:opacity-40 transition-colors"
                          >
                            <Icon icon="mdi:chevron-left" width={14} />
                          </button>
                          {Array.from({ length: totalPages }, (_, i) => i + 1)
                            .filter(p => p === 1 || p === totalPages || Math.abs(p - page) <= 1)
                            .reduce<(number | '...')[]>((acc, p, idx, arr) => {
                              if (idx > 0 && (p as number) - (arr[idx - 1] as number) > 1) acc.push('...');
                              acc.push(p);
                              return acc;
                            }, [])
                            .map((p, i) => p === '...' ? (
                              <span key={`e-${i}`} className="px-1 text-[11px] text-[rgba(0,0,0,0.3)]">…</span>
                            ) : (
                              <button
                                key={p}
                                onClick={() => setPage(p as number)}
                                className={`px-2.5 py-1.5 rounded-lg text-[11px] font-medium transition-colors ${page === p ? 'bg-indigo-600 text-white' : 'border border-[rgba(0,0,0,0.1)] text-[rgba(0,0,0,0.55)] hover:bg-[rgba(0,0,0,0.03)]'}`}
                              >
                                {p}
                              </button>
                            ))}
                          <button
                            onClick={() => setPage(p => Math.min(totalPages, p + 1))}
                            disabled={page === totalPages}
                            className="px-2.5 py-1.5 rounded-lg text-[11px] font-medium border border-[rgba(0,0,0,0.1)] text-[rgba(0,0,0,0.55)] hover:bg-[rgba(0,0,0,0.03)] disabled:opacity-40 transition-colors"
                          >
                            <Icon icon="mdi:chevron-right" width={14} />
                          </button>
                        </div>
                      </div>
                    )}
                  </>
                )}
              </div>
            </motion.div>
          )}

          {/* ROI CALCULATOR TAB */}
          {activeTab === 'roi' && (
            <motion.div
              key="roi"
              initial={{ opacity: 0, y: 8 }}
              animate={{ opacity: 1, y: 0 }}
              exit={{ opacity: 0, y: -4 }}
              transition={{ duration: 0.18, ease: 'easeOut' }}
            >
              <RoiCalculator calculateRoi={calculateRoi} />
            </motion.div>
          )}
          </AnimatePresence>
        </>
      )}

      {showHelpModal && <EbsHelpModal onClose={() => setShowHelpModal(false)} />}
      {guideTarget && <GuideModal target={guideTarget} onClose={() => setGuideTarget(null)} />}
      {selectedAccountId && (
        <EbsVolumeMonitoringDrawer
          volume={monitoringVolume}
          accountId={selectedAccountId}
          onClose={() => setMonitoringVolume(null)}
        />
      )}
    </main>
  );
};

// ─── Sub-components ─────────────────────────────────────────────────────────────

const RoiCalculator: React.FC<{
  calculateRoi: (gb: number, util: number, growth: number, type: string) => Promise<RoiResult | null>;
}> = ({ calculateRoi }) => {
  const [provisionedGb, setProvisionedGb] = useState(1000);
  const [utilizationPct, setUtilizationPct] = useState(25);
  const [growthRatePct, setGrowthRatePct] = useState(25);
  const [diskType, setDiskType] = useState('gp2');
  const [result, setResult] = useState<RoiResult | null>(null);
  const [loading, setLoading] = useState(false);

  const handleCalculate = async () => {
    setLoading(true);
    const r = await calculateRoi(provisionedGb, utilizationPct, growthRatePct, diskType);
    setResult(r);
    setLoading(false);
  };

  return (
    <div className="grid grid-cols-1 lg:grid-cols-2 gap-5">
      <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-6 space-y-5">
        <p className="text-[13px] font-semibold text-[rgba(0,0,0,0.88)]">Input</p>

        <div>
          <label className="block text-[12px] font-medium text-[rgba(0,0,0,0.65)] mb-1.5">Cloud Provider</label>
          <div className="flex gap-2">
            {['AWS', 'GCP', 'Azure'].map(p => (
              <button key={p}
                className={`flex-1 py-2 rounded-lg text-[12px] font-semibold border transition-colors ${
                  p === 'AWS' ? 'bg-indigo-600 text-white border-indigo-600' : 'border-[rgba(0,0,0,0.1)] text-[rgba(0,0,0,0.3)] cursor-not-allowed'
                }`}
                disabled={p !== 'AWS'}
              >{p}</button>
            ))}
          </div>
        </div>

        {[
          { label: `Disk Provisioned: ${provisionedGb} GB`, min: 10, max: 16000, step: 10, value: provisionedGb, onChange: setProvisionedGb },
          { label: `Disk Utilization: ${utilizationPct}%`, min: 1, max: 95, step: 1, value: utilizationPct, onChange: setUtilizationPct },
          { label: `Data Growth Rate (per year): ${growthRatePct}%`, min: 0, max: 100, step: 1, value: growthRatePct, onChange: setGrowthRatePct },
        ].map(s => (
          <div key={s.label}>
            <label className="block text-[12px] font-medium text-[rgba(0,0,0,0.65)] mb-1.5">{s.label}</label>
            <input type="range" min={s.min} max={s.max} step={s.step} value={s.value}
              onChange={e => s.onChange(+e.target.value)} className="w-full accent-indigo-600" />
          </div>
        ))}

        <div>
          <label className="block text-[12px] font-medium text-[rgba(0,0,0,0.65)] mb-1.5">Current Disk Type</label>
          <select value={diskType} onChange={e => setDiskType(e.target.value)}
            className="w-full px-3 py-2 rounded-lg border border-[rgba(0,0,0,0.1)] bg-white text-[12px] text-[rgba(0,0,0,0.88)] focus:outline-none focus:ring-2 focus:ring-indigo-300">
            {DISK_TYPES.map(t => <option key={t} value={t}>{t}</option>)}
          </select>
        </div>

        <div className="flex gap-3">
          <button onClick={handleCalculate} disabled={loading}
            className="flex-1 py-2.5 rounded-lg bg-indigo-600 hover:bg-indigo-700 text-white text-[12px] font-semibold transition-colors disabled:opacity-60">
            {loading ? 'Calculating…' : 'Calculate'}
          </button>
          <button onClick={() => setResult(null)}
            className="px-4 py-2.5 rounded-lg border border-[rgba(0,0,0,0.1)] text-[rgba(0,0,0,0.55)] text-[12px] font-semibold hover:bg-[rgba(0,0,0,0.03)] transition-colors">
            Reset
          </button>
        </div>
      </div>

      <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-6">
        <p className="text-[13px] font-semibold text-[rgba(0,0,0,0.88)] mb-5">Results</p>
        {!result ? (
          <div className="flex flex-col items-center justify-center h-56 text-[rgba(0,0,0,0.3)]">
            <Icon icon="mdi:calculator-variant-outline" width={40} />
            <p className="text-[13px] mt-3">Configure inputs and click Calculate</p>
          </div>
        ) : (
          <div className="space-y-4">
            <div className="grid grid-cols-2 gap-3">
              <div className="bg-[rgba(0,0,0,0.02)] rounded-xl p-3">
                <p className="text-[11px] text-[rgba(0,0,0,0.45)]">Before</p>
                <p className="text-[18px] font-bold text-[rgba(0,0,0,0.88)]">${result.currentCostPerMonth}/mo</p>
                <p className="text-[11px] text-[rgba(0,0,0,0.4)] mt-0.5">{result.provisionedGb}GB {result.currentType}</p>
              </div>
              <div className="bg-emerald-50 rounded-xl p-3">
                <p className="text-[11px] text-emerald-600">After</p>
                <p className="text-[18px] font-bold text-emerald-700">${result.newCostPerMonth}/mo</p>
                <p className="text-[11px] text-emerald-600 mt-0.5">{result.targetGb}GB {result.recommendedType}</p>
              </div>
            </div>

            {result.monthlySavings >= 0 ? (
              <div className="rounded-xl bg-indigo-50 p-4 text-center border border-indigo-100">
                <p className="text-[11px] text-indigo-500 font-medium">Monthly Savings</p>
                <p className="text-[32px] font-bold text-indigo-700 mt-1" style={{ fontFamily: "'Bebas Neue', sans-serif" }}>${result.monthlySavings.toFixed(2)}</p>
                <p className="text-[12px] text-indigo-500">{result.savingsPct.toFixed(1)}% reduction</p>
              </div>
            ) : (
              <div className="rounded-xl bg-amber-50 p-4 text-center border border-amber-100">
                <p className="text-[11px] text-amber-600 font-medium">Monthly Cost Increase</p>
                <p className="text-[32px] font-bold text-amber-700 mt-1" style={{ fontFamily: "'Bebas Neue', sans-serif" }}>+${Math.abs(result.monthlySavings).toFixed(2)}</p>
                <p className="text-[12px] text-amber-500">Volume needs to grow due to high growth rate</p>
              </div>
            )}

            <div className="grid grid-cols-2 gap-3">
              {[
                { label: result.monthlySavings >= 0 ? '1 Year Savings' : '1 Year Cost Increase', value: result.firstYearSavings },
                { label: result.monthlySavings >= 0 ? '5 Year Savings' : '5 Year Cost Increase', value: result.fiveYearSavings },
              ].map(card => (
                <div key={card.label} className="rounded-xl border border-[rgba(0,0,0,0.08)] p-3 text-center">
                  <p className="text-[11px] text-[rgba(0,0,0,0.45)]">{card.label}</p>
                  <p className={`text-[18px] font-bold ${card.value >= 0 ? 'text-[rgba(0,0,0,0.88)]' : 'text-amber-600'}`}>
                    {card.value >= 0 ? '$' : '+$'}{Math.abs(card.value).toFixed(2)}
                  </p>
                </div>
              ))}
            </div>
            <p className="text-[11px] text-[rgba(0,0,0,0.3)] text-center">Based on gp3 pricing ($0.08/GB/mo). Actual savings may vary by region.</p>
          </div>
        )}
      </div>
    </div>
  );
};

const EbsHelpModal: React.FC<{ onClose: () => void }> = ({ onClose }) => {
  const [activeSection, setActiveSection] = React.useState<'shrink' | 'expand'>('shrink');
  const [activeMethod, setActiveMethod] = React.useState<'cli' | 'console'>('cli');
  const [copiedStep, setCopiedStep] = React.useState<number | null>(null);

  const copy = (text: string, step: number) => {
    navigator.clipboard.writeText(text).then(() => { setCopiedStep(step); setTimeout(() => setCopiedStep(null), 2000); });
  };

  const shrinkCliSteps = [
    { label: 'Create a snapshot (safety backup)', cmd: 'aws ec2 create-snapshot --volume-id <volume-id> --description "pre-shrink-backup" --region <region>' },
    { label: 'Create a new smaller volume in the same Availability Zone', cmd: 'aws ec2 create-volume --size <new-size-gb> --availability-zone <az> --volume-type gp3 --region <region>' },
    { label: 'Attach the new volume to your instance', cmd: 'aws ec2 attach-volume --volume-id <new-vol-id> --instance-id <instance-id> --device /dev/xvdz --region <region>' },
    { label: 'SSH into the instance — format, mount, and copy data', cmd: 'sudo mkfs.ext4 /dev/xvdz\nsudo mkdir -p /mnt/new_vol\nsudo mount /dev/xvdz /mnt/new_vol\nsudo rsync -axHAX <mount-point>/ /mnt/new_vol/' },
    { label: 'Swap mounts and update /etc/fstab', cmd: 'NEW_UUID=$(sudo blkid -s UUID -o value /dev/xvdz)\nsudo umount <mount-point>\nsudo umount /mnt/new_vol\nsudo mount /dev/xvdz <mount-point>\nsudo sed -i \'/<mount-point>/d\' /etc/fstab\necho "UUID=$NEW_UUID <mount-point> ext4 defaults,nofail 0 2" | sudo tee -a /etc/fstab' },
    { label: 'Detach the old volume', cmd: 'aws ec2 detach-volume --volume-id <old-vol-id> --region <region>' },
  ];
  const expandCliSteps = [
    { label: 'Modify the volume size', cmd: 'aws ec2 modify-volume --volume-id <volume-id> --size <new-size-gb> --region <region>' },
    { label: 'Wait for modification to complete (~1–2 min)', cmd: "aws ec2 describe-volumes-modifications --volume-ids <volume-id> --region <region> --query 'VolumesModifications[0].ModificationState'" },
    { label: 'SSH into the instance — grow partition and resize filesystem', cmd: '# Xen-based instances (xvda):\nsudo growpart /dev/xvda 1\nsudo resize2fs /dev/xvda1    # ext4\n# OR: sudo xfs_growfs /      # xfs\n\n# Nitro/NVMe instances:\nsudo growpart /dev/nvme0n1 1\nsudo resize2fs /dev/nvme0n1p1' },
  ];
  const isShrink = activeSection === 'shrink';
  const isCli = activeMethod === 'cli';
  const steps = isCli ? (isShrink ? shrinkCliSteps : expandCliSteps) : [];

  return ReactDOM.createPortal(
    <div className="fixed inset-0 bg-black/50 backdrop-blur-sm z-50 flex items-center justify-center p-4" onClick={onClose}>
      <div className="bg-white rounded-2xl shadow-2xl w-full max-w-2xl max-h-[90vh] overflow-y-auto" onClick={e => e.stopPropagation()}>
        <div className="flex items-center justify-between p-6 border-b border-[rgba(0,0,0,0.08)]">
          <div className="flex items-center gap-2">
            <Icon icon="mdi:book-open-outline" width={18} color="#6366F1" />
            <h3 className="text-[15px] font-bold text-[rgba(0,0,0,0.88)]">EBS Rightsizing Guide</h3>
          </div>
          <button onClick={onClose} className="text-[rgba(0,0,0,0.3)] hover:text-[rgba(0,0,0,0.65)] p-1">
            <Icon icon="mdi:close" width={18} />
          </button>
        </div>

        <div className="flex border-b border-[rgba(0,0,0,0.08)] px-6">
          {[{ id: 'shrink', label: 'How to Shrink', icon: 'mdi:arrow-collapse' }, { id: 'expand', label: 'How to Expand', icon: 'mdi:arrow-expand' }].map(s => (
            <button key={s.id} onClick={() => { setActiveSection(s.id as any); setCopiedStep(null); }}
              className={`py-3 px-4 text-[12px] font-medium border-b-2 flex items-center gap-1.5 transition-colors ${activeSection === s.id ? 'border-indigo-500 text-indigo-600' : 'border-transparent text-[rgba(0,0,0,0.45)] hover:text-[rgba(0,0,0,0.65)]'}`}>
              <Icon icon={s.icon} width={12} />{s.label}
            </button>
          ))}
        </div>

        <div className="p-6 space-y-4">
          {isShrink ? (
            <div className="bg-amber-50 border border-amber-200 rounded-xl p-3 flex gap-2 text-[12px] text-amber-800">
              <Icon icon="mdi:alert-outline" width={14} className="flex-shrink-0 mt-0.5" />
              <span>AWS does not support shrinking volumes in-place. Always snapshot first — data loss is possible if interrupted.</span>
            </div>
          ) : (
            <div className="bg-blue-50 border border-blue-200 rounded-xl p-3 flex gap-2 text-[12px] text-blue-800">
              <Icon icon="mdi:information-outline" width={14} className="flex-shrink-0 mt-0.5" />
              <span>Expanding is safe and non-disruptive. You only need to grow the partition and filesystem inside the instance after.</span>
            </div>
          )}

          <div className="flex items-center gap-1 bg-[rgba(0,0,0,0.04)] rounded-xl p-1 w-fit">
            {[{ id: 'cli', label: 'AWS CLI', icon: 'mdi:console' }, { id: 'console', label: 'AWS Console', icon: 'mdi:monitor' }].map(m => (
              <button key={m.id} onClick={() => { setActiveMethod(m.id as any); setCopiedStep(null); }}
                className={`flex items-center gap-1.5 px-4 py-1.5 rounded-lg text-[11px] font-semibold transition-colors ${activeMethod === m.id ? 'bg-white text-[rgba(0,0,0,0.88)] shadow-sm' : 'text-[rgba(0,0,0,0.45)] hover:text-[rgba(0,0,0,0.65)]'}`}>
                <Icon icon={m.icon} width={12} />{m.label}
              </button>
            ))}
          </div>

          {isCli && (
            <div className="space-y-3">
              {steps.map((step, i) => (
                <div key={i} className="rounded-xl border border-[rgba(0,0,0,0.08)] overflow-hidden">
                  <div className="flex items-center gap-2 px-4 py-2 bg-[rgba(0,0,0,0.02)]">
                    <span className="w-5 h-5 rounded-full bg-indigo-600 text-white text-[11px] font-bold flex items-center justify-center flex-shrink-0">{i+1}</span>
                    <span className="text-[12px] font-medium text-[rgba(0,0,0,0.7)]">{step.label}</span>
                  </div>
                  <div className="relative bg-gray-900 px-4 py-3">
                    <pre className="text-[11px] text-emerald-300 font-mono whitespace-pre-wrap break-all pr-16">{step.cmd}</pre>
                    <button onClick={() => copy(step.cmd, i)}
                      className="absolute top-2 right-2 px-2 py-1 rounded bg-gray-700 hover:bg-gray-600 text-gray-300 text-[11px] transition-colors">
                      {copiedStep === i ? <>✓ Copied</> : <>Copy</>}
                    </button>
                  </div>
                </div>
              ))}
              <p className="text-[11px] text-[rgba(0,0,0,0.35)]">Replace placeholders with your actual values. Click <strong>How to fix</strong> on a row for a pre-filled version.</p>
            </div>
          )}
          {!isCli && (
            <div className="p-4 text-center text-[13px] text-[rgba(0,0,0,0.45)]">Open the <strong>AWS Console</strong> → EC2 → Elastic Block Store → Volumes to get started.</div>
          )}
        </div>

        <div className="px-6 pb-6">
          <button onClick={onClose} className="w-full px-4 py-2.5 rounded-xl border border-[rgba(0,0,0,0.1)] text-[rgba(0,0,0,0.65)] text-[12px] font-semibold hover:bg-[rgba(0,0,0,0.03)] transition-colors">Close</button>
        </div>
      </div>
    </div>,
    document.body
  );
};

const GuideModal: React.FC<{ target: GuideTarget; onClose: () => void }> = ({ target, onClose }) => {
  const [copiedStep, setCopiedStep] = React.useState<number | null>(null);
  const targetSize = target.recommendedSizeGb ?? Math.ceil(target.currentSizeGb * 0.6);
  const copy = (text: string, step: number) => {
    navigator.clipboard.writeText(text).then(() => { setCopiedStep(step); setTimeout(() => setCopiedStep(null), 2000); });
  };
  const isShrink = target.recommendation === 'SHRINK';

  const shrinkSteps = [
    { label: 'Create a snapshot (safety backup)', cmd: `aws ec2 create-snapshot --volume-id ${target.volumeId} --description "pre-shrink-backup" --region ${target.region}` },
    { label: `Create a new ${targetSize}GB volume in the same Availability Zone`, cmd: `aws ec2 create-volume --size ${targetSize} --availability-zone <your-az> --volume-type ${target.volumeType ?? 'gp3'} --region ${target.region}` },
    { label: 'Attach the new volume to your instance', cmd: `aws ec2 attach-volume --volume-id <new-vol-id> --instance-id ${target.instanceId ?? '<instance-id>'} --device /dev/xvdz --region ${target.region}` },
    { label: 'SSH into the instance — format, mount, and copy data', cmd: 'sudo mkfs.ext4 /dev/xvdz\nsudo mkdir -p /mnt/new_vol\nsudo mount /dev/xvdz /mnt/new_vol\nsudo rsync -axHAX <mount-point>/ /mnt/new_vol/' },
    { label: 'Swap mounts and update /etc/fstab', cmd: 'NEW_UUID=$(sudo blkid -s UUID -o value /dev/xvdz)\nsudo umount <mount-point>\nsudo umount /mnt/new_vol\nsudo mount /dev/xvdz <mount-point>\nsudo sed -i \'/<mount-point>/d\' /etc/fstab\necho "UUID=$NEW_UUID <mount-point> ext4 defaults,nofail 0 2" | sudo tee -a /etc/fstab' },
    { label: 'Detach the old volume', cmd: `aws ec2 detach-volume --volume-id ${target.volumeId} --region ${target.region}` },
  ];
  const expandSteps = [
    { label: `Modify volume size to ${targetSize}GB`, cmd: `aws ec2 modify-volume --volume-id ${target.volumeId} --size ${targetSize} --region ${target.region}` },
    { label: 'Wait for modification to complete (~1–2 min)', cmd: `aws ec2 describe-volumes-modifications --volume-ids ${target.volumeId} --region ${target.region} --query 'VolumesModifications[0].ModificationState'` },
    { label: 'SSH into the instance — grow partition and resize filesystem', cmd: '# For xvda root volume:\nsudo growpart /dev/xvda 1\nsudo resize2fs /dev/xvda1    # ext4\n# OR: sudo xfs_growfs /      # xfs (Amazon Linux 2)' },
  ];
  const steps = isShrink ? shrinkSteps : expandSteps;

  return ReactDOM.createPortal(
    <div className="fixed inset-0 bg-black/50 backdrop-blur-sm z-50 flex items-center justify-center p-4" onClick={onClose}>
      <div className="bg-white rounded-2xl shadow-2xl w-full max-w-2xl max-h-[90vh] overflow-y-auto" onClick={e => e.stopPropagation()}>
        <div className="flex items-center justify-between p-6 border-b border-[rgba(0,0,0,0.08)]">
          <div>
            <h3 className="text-[15px] font-bold text-[rgba(0,0,0,0.88)] flex items-center gap-2">
              <Icon icon={isShrink ? 'mdi:arrow-collapse' : 'mdi:arrow-expand'} width={16} color={isShrink ? '#F59E0B' : '#3B82F6'} />
              {isShrink ? 'How to Shrink This Volume' : 'How to Expand This Volume'}
            </h3>
            <p className="text-[11px] text-[rgba(0,0,0,0.4)] mt-0.5 font-mono">{target.volumeId} · {target.currentSizeGb}GB → {targetSize}GB · {target.region}</p>
          </div>
          <button onClick={onClose} className="text-[rgba(0,0,0,0.3)] hover:text-[rgba(0,0,0,0.65)] p-1">
            <Icon icon="mdi:close" width={18} />
          </button>
        </div>

        <div className="p-6 space-y-4">
          {isShrink && (
            <div className="bg-amber-50 border border-amber-200 rounded-xl p-3 flex gap-2 text-[12px] text-amber-800">
              <Icon icon="mdi:alert-outline" width={14} className="flex-shrink-0 mt-0.5" />
              <span>AWS does not support shrinking volumes in-place. Take a snapshot first — data loss is possible if interrupted.</span>
            </div>
          )}
          <div className="space-y-3">
            {steps.map((step, i) => (
              <div key={i} className="rounded-xl border border-[rgba(0,0,0,0.08)] overflow-hidden">
                <div className="flex items-center gap-2 px-4 py-2 bg-[rgba(0,0,0,0.02)]">
                  <span className="w-5 h-5 rounded-full bg-blue-600 text-white text-[11px] font-bold flex items-center justify-center flex-shrink-0">{i+1}</span>
                  <span className="text-[12px] font-medium text-[rgba(0,0,0,0.7)]">{step.label}</span>
                </div>
                <div className="relative bg-gray-900 px-4 py-3">
                  <pre className="text-[11px] text-emerald-300 font-mono whitespace-pre-wrap break-all pr-16">{step.cmd}</pre>
                  <button onClick={() => copy(step.cmd, i)}
                    className="absolute top-2 right-2 px-2 py-1 rounded bg-gray-700 hover:bg-gray-600 text-gray-300 text-[11px] transition-colors">
                    {copiedStep === i ? '✓ Copied' : 'Copy'}
                  </button>
                </div>
              </div>
            ))}
          </div>
          {isShrink && (
            <div className="flex items-center justify-between bg-emerald-50 rounded-xl px-4 py-3 border border-emerald-100">
              <span className="text-[12px] text-[rgba(0,0,0,0.55)]">Estimated savings after shrink:</span>
              <span className="font-bold text-emerald-600 text-[13px]">~${((target.currentSizeGb - targetSize) * 0.08).toFixed(2)}/mo</span>
            </div>
          )}
        </div>

        <div className="px-6 pb-6">
          <button onClick={onClose} className="w-full px-4 py-2.5 rounded-xl border border-[rgba(0,0,0,0.1)] text-[rgba(0,0,0,0.65)] text-[12px] font-semibold hover:bg-[rgba(0,0,0,0.03)] transition-colors">Close</button>
        </div>
      </div>
    </div>,
    document.body
  );
};

const PendingDeletionPanel: React.FC<{
  volumes: PendingDeletionVolume[];
  onDelete: (v: PendingDeletionVolume) => Promise<void>;
  onKeep: (v: PendingDeletionVolume) => Promise<void>;
}> = ({ volumes, onDelete, onKeep }) => {
  const [actingId, setActingId] = useState<string | null>(null);

  const daysLeft = (deleteAfter?: string) => {
    if (!deleteAfter) return null;
    const diff = new Date(deleteAfter).getTime() - Date.now();
    return Math.max(0, Math.ceil(diff / 86_400_000));
  };

  const handle = async (v: PendingDeletionVolume, action: 'delete' | 'keep') => {
    setActingId(v.volumeId);
    if (action === 'delete') await onDelete(v);
    else await onKeep(v);
    setActingId(null);
  };

  return (
    <div className="bg-amber-50 border border-amber-200 rounded-xl shadow-sm overflow-hidden">
      <div className="px-5 py-3.5 border-b border-amber-200 flex items-center gap-2">
        <Icon icon="mdi:clock-outline" width={15} color="#D97706" />
        <span className="text-[13px] font-semibold text-amber-800">Pending Deletion</span>
        <span className="text-[11px] px-2 py-0.5 rounded-full bg-amber-100 text-amber-700 border border-amber-200">{volumes.length} volume{volumes.length !== 1 ? 's' : ''}</span>
        <span className="ml-auto text-[11px] text-amber-600">Kept 7 days as rollback</span>
      </div>
      <div className="overflow-x-auto">
        <table className="w-full text-[12px]">
          <thead>
            <tr className="border-b border-amber-200 bg-amber-100/50">
              {['Volume ID', 'Region', 'Type', 'Size', 'Source', 'Auto-Delete In', 'Cost/mo', 'Actions'].map(h => (
                <th key={h} className="px-4 py-2.5 text-left text-[11px] font-semibold uppercase tracking-wide text-amber-700">{h}</th>
              ))}
            </tr>
          </thead>
          <tbody>
            {volumes.map((v) => {
              const days = daysLeft(v.deleteAfter);
              return (
                <tr key={v.volumeId} className="border-b border-amber-100 hover:bg-amber-100/40 transition-colors">
                  <td className="px-4 py-3 font-mono text-[rgba(0,0,0,0.88)]">{v.volumeId}</td>
                  <td className="px-4 py-3 text-[rgba(0,0,0,0.55)]">{v.region}</td>
                  <td className="px-4 py-3"><span className="px-1.5 py-0.5 rounded text-[11px] font-medium bg-blue-50 text-blue-600 border border-blue-100">{v.volumeType}</span></td>
                  <td className="px-4 py-3 font-semibold text-[rgba(0,0,0,0.88)]">{v.sizeGb} GB</td>
                  <td className="px-4 py-3 font-mono text-[rgba(0,0,0,0.45)]">{v.sourceVolumeId ?? '—'}</td>
                  <td className="px-4 py-3">
                    {days !== null ? (
                      <span className={`px-2 py-0.5 rounded-full text-[11px] font-semibold border ${
                        days <= 1 ? 'bg-rose-50 text-rose-600 border-rose-100'
                        : days <= 3 ? 'bg-amber-50 text-amber-600 border-amber-100'
                        : 'bg-[rgba(0,0,0,0.03)] text-[rgba(0,0,0,0.45)] border-[rgba(0,0,0,0.08)]'
                      }`}>{days === 0 ? 'Today' : `${days}d`}</span>
                    ) : '—'}
                  </td>
                  <td className="px-4 py-3 font-semibold text-[rgba(0,0,0,0.88)]">${v.monthlyCost.toFixed(2)}</td>
                  <td className="px-4 py-3">
                    <div className="flex items-center gap-2">
                      <button onClick={() => handle(v, 'delete')} disabled={actingId === v.volumeId}
                        className="px-2.5 py-1 rounded-lg text-[11px] font-semibold border border-rose-200 text-rose-600 hover:bg-rose-50 disabled:opacity-50 transition-colors">
                        {actingId === v.volumeId ? '…' : 'Delete Now'}
                      </button>
                      <button onClick={() => handle(v, 'keep')} disabled={actingId === v.volumeId}
                        className="px-2.5 py-1 rounded-lg text-[11px] font-semibold border border-emerald-200 text-emerald-600 hover:bg-emerald-50 disabled:opacity-50 transition-colors">
                        Keep
                      </button>
                    </div>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    </div>
  );
};

const SsmSetupGuide: React.FC<{ count: number; awsAccountId: string | null }> = ({ count, awsAccountId }) => {
  const [expanded, setExpanded] = React.useState(false);
  const [copied, setCopied] = React.useState(false);
  const detectedBase = window.location.origin.replace(':5173', ':8080');
  const isLocalhost = detectedBase.includes('localhost') || detectedBase.includes('127.0.0.1');
  const [serverUrl, setServerUrl] = React.useState(detectedBase);
  const accountArg = awsAccountId ?? '<aws-account-id>';
  const isNgrok = serverUrl.includes('ngrok');
  const curlHeaders = isNgrok ? ' -H "ngrok-skip-browser-warning: true"' : '';
  const AGENT_CMD = `curl -fsSL${curlHeaders} ${serverUrl}/agentinstall/agent_install.sh | sudo bash -s -- -b ${serverUrl} -t ${accountArg}`;
  const copy = () => { navigator.clipboard.writeText(AGENT_CMD).then(() => { setCopied(true); setTimeout(() => setCopied(false), 2000); }); };

  return (
    <div className="rounded-xl border border-amber-200 bg-amber-50 overflow-hidden">
      <div className="px-4 py-3 flex items-start gap-3">
        <Icon icon="mdi:alert-outline" width={16} color="#D97706" className="mt-0.5 flex-shrink-0" />
        <div className="flex-1 min-w-0">
          <p className="text-[13px] font-semibold text-amber-800">Disk usage unavailable for {count} instance{count > 1 ? 's' : ''}</p>
          <p className="text-[12px] text-amber-700 mt-0.5">Install the XamOps disk agent to enable SHRINK/EXPAND recommendations — no IAM changes needed.</p>
        </div>
        <button onClick={() => setExpanded(e => !e)}
          className="flex-shrink-0 flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-amber-600 hover:bg-amber-700 text-white text-[11px] font-semibold transition-colors">
          <Icon icon={expanded ? 'mdi:chevron-up' : 'mdi:robot-outline'} width={12} />
          {expanded ? 'Hide' : 'Setup Agent'}
        </button>
      </div>

      {expanded && (
        <div className="border-t border-amber-200 px-4 py-4 space-y-4 bg-white">
          <div className="rounded-xl border border-indigo-100 bg-indigo-50 p-4 space-y-2">
            <p className="text-[12px] font-semibold text-indigo-700 flex items-center gap-1.5"><Icon icon="mdi:lightbulb-on-outline" width={13} />Why we need the agent</p>
            <p className="text-[12px] text-indigo-600 leading-relaxed">AWS does not expose actual disk usage through its APIs. The agent runs <code className="px-1 rounded bg-indigo-100 font-mono">df</code> and reports real usage — the only reliable way to generate SHRINK/EXPAND recommendations.</p>
          </div>

          {isLocalhost && (
            <div className="rounded-lg bg-yellow-50 border border-yellow-200 px-3 py-2.5 space-y-2">
              <p className="text-[12px] font-medium text-yellow-800 flex items-center gap-1.5"><Icon icon="mdi:alert-circle-outline" width={13} />EC2 instances can't reach <code className="font-mono">localhost</code> — enter the public IP or hostname:</p>
              <input type="text" value={serverUrl} onChange={e => setServerUrl(e.target.value.replace(/\/$/, ''))}
                placeholder="http://1.2.3.4:8080"
                className="w-full px-3 py-1.5 rounded-lg border border-yellow-300 bg-white text-[12px] font-mono text-[rgba(0,0,0,0.88)] focus:outline-none focus:ring-2 focus:ring-yellow-400" />
            </div>
          )}

          <div className="flex gap-3">
            <div className="w-6 h-6 rounded-full bg-indigo-100 text-indigo-700 text-[11px] font-bold flex items-center justify-center flex-shrink-0 mt-0.5">1</div>
            <div className="flex-1">
              <p className="text-[13px] font-medium text-[rgba(0,0,0,0.88)] mb-2">SSH into each EC2 instance and run</p>
              <div className="relative rounded-lg bg-gray-900 p-3 font-mono text-[11px] text-emerald-300 overflow-x-auto">
                <pre className="whitespace-pre-wrap break-all">{AGENT_CMD}</pre>
                <button onClick={copy} className="absolute top-2 right-2 px-2 py-1 rounded bg-gray-700 hover:bg-gray-600 text-gray-300 text-[11px] transition-colors">
                  {copied ? '✓ Copied' : 'Copy'}
                </button>
              </div>
            </div>
          </div>

          <div className="rounded-lg bg-emerald-50 border border-emerald-200 px-3 py-2 text-[12px] text-emerald-700 flex items-start gap-2">
            <Icon icon="mdi:check-circle-outline" width={13} className="mt-0.5 flex-shrink-0" />
            <span>Disk usage appears within 1–2 minutes. Click <strong>Refresh</strong> to see SHRINK/EXPAND recommendations.</span>
          </div>
        </div>
      )}
    </div>
  );
};

export default EbsRightsizingPage;
