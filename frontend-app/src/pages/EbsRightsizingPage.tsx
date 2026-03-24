import React, { useState, useEffect } from 'react';
import { useAccounts } from '../contexts/AccountContext';
import { useEbsRightsizingData, EbsVolume, DiskPolicy, RoiResult, PendingDeletionVolume } from '../hooks/useEbsRightsizingData';
import { AriaTabGroup } from '../components/ui';
import { useTopbarActions } from '../contexts/TopbarContext';
import { EbsVolumeMonitoringDrawer } from '../components/EbsRightsizing/EbsVolumeMonitoringDrawer';

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

type Tab = 'overview' | 'recommendations' | 'policies' | 'actions' | 'roi';

const EbsRightsizingPage: React.FC = () => {
  const { selectedAccountIds } = useAccounts();
  const selectedAccountId = selectedAccountIds[0] || null;
  const [activeTab, setActiveTab] = useState<Tab>('overview');
  const [applyingId, setApplyingId] = useState<string | null>(null);
  const [actionMsg, setActionMsg] = useState<{ type: 'success' | 'error'; text: string } | null>(null);
  const [guideTarget, setGuideTarget] = useState<GuideTarget | null>(null);
  const [monitoringVolume, setMonitoringVolume] = useState<EbsVolume | null>(null);
  const [filterSearch, setFilterSearch] = useState('');
  const [filterRegion, setFilterRegion] = useState('');
  const [filterType, setFilterType] = useState('');
  const [filterRecommendation, setFilterRecommendation] = useState('');
  const { setTitle, setActions, clearActions } = useTopbarActions();

  const {
    volumes, recommendations, policies, actionHistory, pendingDeletion,
    isLoading, isDiskUsageLoading, isRefreshing, ssmUnavailableInstances, totalWastedMonthly,
    unattachedCount, shrinkCount, expandCount,
    applyAction, createPolicy, updatePolicy, deletePolicy,
    calculateRoi, refreshAll, deletePendingVolume, keepVolume,
  } = useEbsRightsizingData({ selectedAccountId });

  const uniqueRegions = Array.from(new Set(volumes.map(v => v.region))).sort();
  const uniqueTypes = Array.from(new Set(volumes.map(v => v.volumeType))).sort();

  const filteredVolumes = volumes.filter(v => {
    if (filterSearch && !v.volumeId.includes(filterSearch) && !(v.volumeName ?? '').toLowerCase().includes(filterSearch.toLowerCase())) return false;
    if (filterRegion && v.region !== filterRegion) return false;
    if (filterType && v.volumeType !== filterType) return false;
    if (filterRecommendation && v.recommendation !== filterRecommendation) return false;
    return true;
  });

  useEffect(() => {
    setTitle('Disk Rightsizing');
    setActions(
      <button
        onClick={refreshAll}
        disabled={isRefreshing}
        className="flex items-center gap-2 px-4 py-2 rounded-xl bg-indigo-600 hover:bg-indigo-700 text-white text-sm font-medium transition-colors disabled:opacity-60"
      >
        <i className={`fas fa-sync-alt ${isRefreshing ? 'animate-spin' : ''}`} />
        {isRefreshing ? 'Refreshing...' : 'Refresh'}
      </button>
    );
    return () => { clearActions(); setTitle(null); };
  }, [isRefreshing, refreshAll]);

  const handleApply = async (vol: EbsVolume) => {
    setApplyingId(vol.volumeId);
    setActionMsg(null);
    const result = await applyAction(vol.volumeId, vol.region, vol.recommendation, vol.recommendedType, vol.recommendedSizeGb);
    setApplyingId(null);
    if (result.success) {
      setActionMsg({ type: 'success', text: result.message || 'Action applied successfully' });
    } else {
      setActionMsg({ type: 'error', text: result.error || 'Action failed' });
    }
  };

  const tabs = [
    { id: 'overview',        label: 'Overview',        icon: 'fas fa-chart-pie' },
    { id: 'recommendations', label: 'Recommendations',  icon: 'fas fa-lightbulb' },
    { id: 'roi',             label: 'ROI Calculator',   icon: 'fas fa-calculator' },
  ];

  const recommendationBadge = (r: string) => {
    switch (r) {
      case 'DELETE_UNATTACHED': return <span className="px-2 py-0.5 rounded-full text-xs font-semibold bg-red-100 text-red-700 dark:bg-red-900/30 dark:text-red-400"><i className="fas fa-trash-alt mr-1" />Delete</span>;
      case 'UPGRADE_TYPE':      return <span className="px-2 py-0.5 rounded-full text-xs font-semibold bg-blue-100 text-blue-700 dark:bg-blue-900/30 dark:text-blue-400"><i className="fas fa-arrow-up mr-1" />Upgrade Type</span>;
      case 'EXPAND':            return <span className="px-2 py-0.5 rounded-full text-xs font-semibold bg-amber-100 text-amber-700 dark:bg-amber-900/30 dark:text-amber-400"><i className="fas fa-expand-arrows-alt mr-1" />Expand</span>;
      case 'SHRINK':            return <span className="px-2 py-0.5 rounded-full text-xs font-semibold bg-orange-100 text-orange-700 dark:bg-orange-900/30 dark:text-orange-400"><i className="fas fa-compress-arrows-alt mr-1" />Shrink</span>;
      default:                  return <span className="px-2 py-0.5 rounded-full text-xs font-semibold bg-gray-100 text-gray-500">None</span>;
    }
  };

  return (
    <main className="flex-1 flex flex-col min-h-screen bg-transparent min-w-0">
      {/* Action feedback */}
      {actionMsg && (
        <div className={`mx-8 mb-4 px-4 py-3 rounded-xl text-sm font-medium flex items-center gap-2 ${
          actionMsg.type === 'success'
            ? 'bg-green-50 text-green-700 border border-green-200 dark:bg-green-900/20 dark:text-green-400 dark:border-green-800'
            : 'bg-red-50 text-red-700 border border-red-200 dark:bg-red-900/20 dark:text-red-400 dark:border-red-800'
        }`}>
          <i className={`fas ${actionMsg.type === 'success' ? 'fa-check-circle' : 'fa-exclamation-circle'}`} />
          {actionMsg.text}
          <button className="ml-auto opacity-60 hover:opacity-100" onClick={() => setActionMsg(null)}>
            <i className="fas fa-times" />
          </button>
        </div>
      )}

      <div className="flex-1 px-8 pb-8 space-y-6 min-w-0">
        {!selectedAccountId ? (
          <div className="text-center py-16 rounded-2xl border-2 border-dashed border-gray-300 dark:border-gray-700 bg-gray-50/50 dark:bg-gray-800/20">
            <div className="w-16 h-16 bg-gray-200 dark:bg-gray-700 rounded-full flex items-center justify-center mx-auto mb-4">
              <i className="fas fa-hdd text-gray-400 text-2xl" />
            </div>
            <h4 className="text-lg font-bold text-gray-800 dark:text-gray-200 mb-2">No Account Selected</h4>
            <p className="text-gray-500 dark:text-gray-400">Select an AWS account from the sidebar.</p>
          </div>
        ) : (
          <>
            <AriaTabGroup
              tabs={tabs}
              selectedKey={activeTab}
              onSelectionChange={(key) => setActiveTab(key as Tab)}
            />

            {/* OVERVIEW TAB */}
            {activeTab === 'overview' && (
              <div className="space-y-6">
                <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
                  <KpiCard icon="fas fa-hdd" label="Total Volumes" value={String(volumes.length)} color="indigo" loading={isLoading} />
                  <KpiCard icon="fas fa-trash-alt" label="Unattached" value={String(unattachedCount)} color="red" loading={isLoading} />
                  <KpiCard
                    icon="fas fa-compress-arrows-alt"
                    label="Underutilized (Shrink)"
                    value={isDiskUsageLoading ? '…' : String(shrinkCount)}
                    color="orange"
                    loading={isLoading}
                  />
                  <KpiCard
                    icon="fas fa-exclamation-triangle"
                    label={expandCount > 0 ? `Near Full (${expandCount}) · Savings` : 'Potential Savings/mo'}
                    value={`$${totalWastedMonthly.toFixed(2)}`}
                    color={expandCount > 0 ? 'red' : 'green'}
                    loading={isLoading}
                  />
                </div>


                {/* Pending Deletion Section */}
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

                {/* SSM unavailable banner */}
                {!isDiskUsageLoading && ssmUnavailableInstances.length > 0 && (
                  <SsmSetupGuide count={ssmUnavailableInstances.length} awsAccountId={selectedAccountId} />
                )}

                {/* All Volumes Table */}
                <div className="rounded-2xl border border-gray-200 dark:border-gray-700 bg-white dark:bg-gray-800 shadow-sm overflow-hidden">
                  <div className="px-6 py-4 border-b border-gray-200 dark:border-gray-700 flex items-center justify-between">
                    <h3 className="text-base font-semibold text-gray-800 dark:text-gray-200">All Volumes</h3>
                    <span className="text-xs text-gray-500 dark:text-gray-400 bg-gray-100 dark:bg-gray-700 px-2 py-1 rounded-full">
                      {filteredVolumes.length}{filteredVolumes.length !== volumes.length ? ` of ${volumes.length}` : ''} total · {filteredVolumes.reduce((s, v) => s + v.sizeGb, 0)} GB provisioned
                    </span>
                  </div>
                  {/* Filters */}
                  <div className="px-6 py-3 border-b border-gray-100 dark:border-gray-700/60 flex flex-wrap gap-2">
                    <div className="relative flex-1 min-w-[160px]">
                      <i className="fas fa-search absolute left-3 top-1/2 -translate-y-1/2 text-gray-400 text-xs" />
                      <input
                        type="text"
                        placeholder="Search volume ID or name..."
                        value={filterSearch}
                        onChange={e => setFilterSearch(e.target.value)}
                        className="w-full pl-8 pr-3 py-1.5 rounded-lg border border-gray-200 dark:border-gray-600 bg-gray-50 dark:bg-gray-700/50 text-xs text-gray-800 dark:text-gray-200 focus:outline-none focus:ring-1 focus:ring-indigo-400"
                      />
                    </div>
                    <select value={filterRegion} onChange={e => setFilterRegion(e.target.value)}
                      className="px-3 py-1.5 rounded-lg border border-gray-200 dark:border-gray-600 bg-gray-50 dark:bg-gray-700/50 text-xs text-gray-700 dark:text-gray-300 focus:outline-none focus:ring-1 focus:ring-indigo-400">
                      <option value="">All Regions</option>
                      {uniqueRegions.map(r => <option key={r} value={r}>{r}</option>)}
                    </select>
                    <select value={filterType} onChange={e => setFilterType(e.target.value)}
                      className="px-3 py-1.5 rounded-lg border border-gray-200 dark:border-gray-600 bg-gray-50 dark:bg-gray-700/50 text-xs text-gray-700 dark:text-gray-300 focus:outline-none focus:ring-1 focus:ring-indigo-400">
                      <option value="">All Types</option>
                      {uniqueTypes.map(t => <option key={t} value={t}>{t}</option>)}
                    </select>
                    <select value={filterRecommendation} onChange={e => setFilterRecommendation(e.target.value)}
                      className="px-3 py-1.5 rounded-lg border border-gray-200 dark:border-gray-600 bg-gray-50 dark:bg-gray-700/50 text-xs text-gray-700 dark:text-gray-300 focus:outline-none focus:ring-1 focus:ring-indigo-400">
                      <option value="">All Recommendations</option>
                      <option value="SHRINK">Shrink</option>
                      <option value="EXPAND">Expand</option>
                      <option value="DELETE_UNATTACHED">Delete Unattached</option>
                      <option value="UPGRADE_TYPE">Upgrade Type</option>
                      <option value="NONE">None</option>
                    </select>
                    {(filterSearch || filterRegion || filterType || filterRecommendation) && (
                      <button onClick={() => { setFilterSearch(''); setFilterRegion(''); setFilterType(''); setFilterRecommendation(''); }}
                        className="px-3 py-1.5 rounded-lg text-xs text-gray-500 dark:text-gray-400 hover:text-red-500 dark:hover:text-red-400 transition-colors">
                        <i className="fas fa-times mr-1" />Clear
                      </button>
                    )}
                  </div>
                  {isLoading ? (
                    <div className="p-6 space-y-3">
                      {[1,2,3,4,5].map(i => <div key={i} className="h-10 bg-gray-100 dark:bg-gray-700 rounded animate-pulse" />)}
                    </div>
                  ) : volumes.length === 0 ? (
                    <div className="p-12 text-center text-gray-500 dark:text-gray-400 text-sm">
                      No volumes found. Click Refresh to scan.
                    </div>
                  ) : (
                    <div className="overflow-x-auto">
                      <table className="w-full text-sm">
                        <thead>
                          <tr className="border-b border-gray-200 dark:border-gray-700 bg-gray-50 dark:bg-gray-800/80">
                            <th className="text-left px-5 py-3 font-semibold text-gray-600 dark:text-gray-400">Volume ID</th>
                            <th className="text-left px-5 py-3 font-semibold text-gray-600 dark:text-gray-400">Region</th>
                            <th className="text-left px-5 py-3 font-semibold text-gray-600 dark:text-gray-400">Type</th>
                            <th className="text-right px-5 py-3 font-semibold text-gray-600 dark:text-gray-400">
                              Size / Usage
                              {isDiskUsageLoading && <i className="fas fa-circle-notch fa-spin ml-1.5 text-indigo-400 text-xs" />}
                            </th>
                            <th className="text-left px-5 py-3 font-semibold text-gray-600 dark:text-gray-400">State</th>
                            <th className="text-left px-5 py-3 font-semibold text-gray-600 dark:text-gray-400">Attached To</th>
                            <th className="text-right px-5 py-3 font-semibold text-gray-600 dark:text-gray-400">Cost/mo</th>
                            <th className="text-center px-5 py-3 font-semibold text-gray-600 dark:text-gray-400">Status</th>
                            <th className="text-center px-5 py-3 font-semibold text-gray-600 dark:text-gray-400">Actions</th>
                          </tr>
                        </thead>
                        <tbody>
                          {filteredVolumes.map((vol) => (
                            <tr
                              key={vol.volumeId}
                              className="border-b border-gray-100 dark:border-gray-700/50 hover:bg-indigo-50/40 dark:hover:bg-indigo-900/10 transition-colors cursor-pointer"
                              onClick={(e) => {
                                // Don't open monitor if clicking action buttons
                                if ((e.target as HTMLElement).closest('button')) return;
                                setMonitoringVolume(vol);
                              }}
                            >
                              <td className="px-5 py-3">
                                <div className="flex items-center gap-1.5">
                                  <div className="font-mono text-xs text-gray-800 dark:text-gray-200">{vol.volumeId}</div>
                                  <i className="fas fa-chart-area text-indigo-300 dark:text-indigo-600 text-[9px]" title="Click row to view CloudWatch metrics" />
                                </div>
                                {vol.volumeName && <div className="text-xs text-gray-500 dark:text-gray-400 mt-0.5">{vol.volumeName}</div>}
                              </td>
                              <td className="px-5 py-3 text-gray-600 dark:text-gray-400 text-xs">{vol.region}</td>
                              <td className="px-5 py-3">
                                <span className="px-2 py-0.5 rounded text-xs font-medium bg-blue-50 text-blue-700 dark:bg-blue-900/30 dark:text-blue-300">
                                  {vol.volumeType}
                                </span>
                              </td>
                              <td className="px-5 py-3 text-right">
                                <div className="text-sm font-semibold text-gray-800 dark:text-gray-200">{vol.sizeGb} GB</div>
                                {vol.usedPercent !== undefined ? (
                                  <div className="mt-1.5">
                                    <div className="w-24 h-1.5 bg-gray-200 dark:bg-gray-600 rounded-full overflow-hidden ml-auto">
                                      <div
                                        className={`h-full rounded-full ${vol.usedPercent > 85 ? 'bg-red-500' : vol.usedPercent > 65 ? 'bg-amber-500' : 'bg-green-500'}`}
                                        style={{ width: `${vol.usedPercent}%` }}
                                      />
                                    </div>
                                    <div className="text-xs text-gray-400 mt-0.5">{vol.usedGb}GB used · {vol.freeGb}GB free</div>
                                  </div>
                                ) : isDiskUsageLoading && vol.instanceId ? (
                                  <div className="mt-1.5 flex items-center justify-end gap-1.5">
                                    <div className="w-16 h-1.5 bg-gray-200 dark:bg-gray-700 rounded-full animate-pulse" />
                                    <span className="text-xs text-gray-400">scanning…</span>
                                  </div>
                                ) : vol.instanceId && ssmUnavailableInstances.includes(vol.instanceId) ? (
                                  <div className="mt-1.5 flex items-center justify-end gap-1">
                                    <i className="fas fa-exclamation-circle text-amber-400 text-[10px]" title="SSM agent not registered — attach AmazonSSMManagedInstanceCore to enable disk scanning" />
                                    <span className="text-[10px] text-amber-500">SSM unavailable</span>
                                  </div>
                                ) : (
                                  <div className="text-xs text-gray-400 mt-0.5">—</div>
                                )}
                              </td>
                              <td className="px-5 py-3">
                                {vol.attached ? (
                                  <span className="flex items-center gap-1.5 text-xs font-medium text-green-600 dark:text-green-400">
                                    <span className="w-1.5 h-1.5 rounded-full bg-green-500 inline-block" />
                                    In Use
                                  </span>
                                ) : (
                                  <span className="flex items-center gap-1.5 text-xs font-medium text-amber-600 dark:text-amber-400">
                                    <span className="w-1.5 h-1.5 rounded-full bg-amber-500 inline-block" />
                                    Unattached
                                  </span>
                                )}
                              </td>
                              <td className="px-5 py-3">
                                {vol.instanceId ? (
                                  <div>
                                    <div className="font-mono text-xs text-gray-700 dark:text-gray-300">{vol.instanceId}</div>
                                    {vol.device && <div className="text-xs text-gray-400 mt-0.5">{vol.device}{vol.isRootVolume ? ' · root' : ''}</div>}
                                  </div>
                                ) : (
                                  <span className="text-xs text-gray-400">—</span>
                                )}
                              </td>
                              <td className="px-5 py-3 text-right text-sm font-medium text-gray-700 dark:text-gray-300">
                                ${vol.monthlyCost.toFixed(2)}
                              </td>
                              <td className="px-5 py-3 text-center">
                                <div className="flex items-center justify-center gap-1.5">
                                  {vol.encrypted && (
                                    <span title="Encrypted" className="text-xs px-1.5 py-0.5 rounded bg-indigo-50 text-indigo-600 dark:bg-indigo-900/30 dark:text-indigo-300">
                                      <i className="fas fa-lock" />
                                    </span>
                                  )}
                                  {vol.isRootVolume && (
                                    <span title="Root Volume" className="text-xs px-1.5 py-0.5 rounded bg-purple-50 text-purple-600 dark:bg-purple-900/30 dark:text-purple-300">
                                      <i className="fas fa-server" />
                                    </span>
                                  )}
                                  {vol.recommendation === 'SHRINK' && (
                                    <span title={vol.recommendationReason} className="text-xs px-1.5 py-0.5 rounded bg-orange-50 text-orange-600 dark:bg-orange-900/30 dark:text-orange-300">
                                      <i className="fas fa-compress-arrows-alt" />
                                    </span>
                                  )}
                                  {vol.recommendation === 'EXPAND' && (
                                    <span title={vol.recommendationReason} className="text-xs px-1.5 py-0.5 rounded bg-red-50 text-red-600 dark:bg-red-900/30 dark:text-red-300">
                                      <i className="fas fa-expand-arrows-alt" />
                                    </span>
                                  )}
                                  {vol.recommendation !== 'NONE' && vol.recommendation !== 'SHRINK' && vol.recommendation !== 'EXPAND' && (
                                    <span title={vol.recommendationReason} className="text-xs px-1.5 py-0.5 rounded bg-yellow-50 text-yellow-600 dark:bg-yellow-900/30 dark:text-yellow-300">
                                      <i className="fas fa-exclamation-triangle" />
                                    </span>
                                  )}
                                </div>
                              </td>
                              <td className="px-5 py-3 text-center">
                                {vol.recommendation !== 'NONE' && vol.recommendation !== 'DELETE_UNATTACHED' ? (
                                  <button
                                    onClick={() => setGuideTarget({
                                      volumeId: vol.volumeId,
                                      region: vol.region,
                                      instanceId: vol.instanceId ?? undefined,
                                      currentSizeGb: vol.sizeGb,
                                      recommendedSizeGb: vol.recommendedSizeGb,
                                      recommendation: vol.recommendation as 'SHRINK' | 'EXPAND',
                                      isRootVolume: vol.isRootVolume,
                                      volumeType: vol.volumeType,
                                    })}
                                    className="px-2.5 py-1 rounded-lg bg-blue-600 hover:bg-blue-700 text-white text-xs font-medium transition-colors"
                                  >
                                    <i className="fas fa-book-open mr-1" />How to fix
                                  </button>
                                ) : (
                                  <span className="text-xs text-gray-300 dark:text-gray-600">—</span>
                                )}
                              </td>
                            </tr>
                          ))}
                        </tbody>
                        <tfoot>
                          <tr className="bg-gray-50 dark:bg-gray-800/80 border-t border-gray-200 dark:border-gray-700">
                            <td colSpan={3} className="px-5 py-3 text-sm font-semibold text-gray-600 dark:text-gray-400">Total</td>
                            <td className="px-5 py-3 text-right text-sm font-bold text-gray-800 dark:text-gray-200">
                              {filteredVolumes.reduce((s, v) => s + v.sizeGb, 0)} GB
                              {filteredVolumes.some(v => v.usedGb !== undefined) && (
                                <div className="text-xs font-normal text-gray-400 mt-0.5">
                                  {filteredVolumes.reduce((s, v) => s + (v.usedGb ?? 0), 0).toFixed(1)} GB used
                                </div>
                              )}
                            </td>
                            <td colSpan={2} className="px-5 py-3 text-xs text-gray-500 dark:text-gray-400">
                              {filteredVolumes.filter(v => v.attached).length} attached · {filteredVolumes.filter(v => !v.attached).length} unattached
                            </td>
                            <td className="px-5 py-3 text-right text-sm font-bold text-gray-800 dark:text-gray-200">
                              ${filteredVolumes.reduce((s, v) => s + v.monthlyCost, 0).toFixed(2)}/mo
                            </td>
                            <td /><td />
                          </tr>
                        </tfoot>
                      </table>
                    </div>
                  )}
                </div>
              </div>
            )}

            {/* RECOMMENDATIONS TAB */}
            {activeTab === 'recommendations' && (
              <div className="rounded-2xl border border-gray-200 dark:border-gray-700 bg-white dark:bg-gray-800 shadow-sm overflow-hidden">
                {isLoading ? (
                  <div className="p-6 space-y-3">
                    {[1,2,3,4,5].map(i => <div key={i} className="h-12 bg-gray-100 dark:bg-gray-700 rounded animate-pulse" />)}
                  </div>
                ) : recommendations.length === 0 ? (
                  <div className="p-12 text-center">
                    <i className="fas fa-check-circle text-green-400 text-4xl mb-3" />
                    <h3 className="text-lg font-bold text-gray-800 dark:text-gray-200">All Optimized</h3>
                    <p className="text-gray-500 dark:text-gray-400 text-sm mt-1">No rightsizing recommendations at this time.</p>
                  </div>
                ) : (
                  <div className="overflow-x-auto">
                    <table className="w-full text-sm">
                      <thead>
                        <tr className="border-b border-gray-200 dark:border-gray-700 bg-gray-50 dark:bg-gray-800/80">
                          <th className="text-left px-5 py-3 font-semibold text-gray-600 dark:text-gray-400">Volume</th>
                          <th className="text-left px-5 py-3 font-semibold text-gray-600 dark:text-gray-400">Region</th>
                          <th className="text-left px-5 py-3 font-semibold text-gray-600 dark:text-gray-400">Type / Size</th>
                          <th className="text-left px-5 py-3 font-semibold text-gray-600 dark:text-gray-400">Recommendation</th>
                          <th className="text-left px-5 py-3 font-semibold text-gray-600 dark:text-gray-400">Reason</th>
                          <th className="text-right px-5 py-3 font-semibold text-gray-600 dark:text-gray-400">Savings/mo</th>
                          <th className="text-center px-5 py-3 font-semibold text-gray-600 dark:text-gray-400">Action</th>
                        </tr>
                      </thead>
                      <tbody>
                        {recommendations.map((vol) => (
                          <tr key={vol.volumeId} className="border-b border-gray-100 dark:border-gray-700/50 hover:bg-gray-50 dark:hover:bg-gray-700/30 transition-colors">
                            <td className="px-5 py-3">
                              <div className="font-mono text-xs text-gray-800 dark:text-gray-200">{vol.volumeId}</div>
                              {vol.volumeName && <div className="text-xs text-gray-500 dark:text-gray-400 mt-0.5">{vol.volumeName}</div>}
                            </td>
                            <td className="px-5 py-3 text-gray-600 dark:text-gray-400">{vol.region}</td>
                            <td className="px-5 py-3">
                              <div className="font-medium text-gray-800 dark:text-gray-200">{vol.volumeType} · {vol.sizeGb}GB</div>
                              {vol.usedPercent !== undefined ? (
                                <div className="mt-1">
                                  <div className="w-20 h-1.5 bg-gray-200 dark:bg-gray-600 rounded-full overflow-hidden">
                                    <div
                                      className={`h-full rounded-full ${vol.usedPercent > 80 ? 'bg-red-500' : vol.usedPercent > 60 ? 'bg-amber-500' : vol.usedPercent < 25 ? 'bg-blue-400' : 'bg-green-500'}`}
                                      style={{ width: `${vol.usedPercent}%` }}
                                    />
                                  </div>
                                  <div className="text-[10px] text-gray-400 mt-0.5">{vol.usedPercent}% used</div>
                                </div>
                              ) : (
                                <div className="text-xs text-gray-400 mt-0.5">→ {vol.recommendedSizeGb}GB</div>
                              )}
                            </td>
                            <td className="px-5 py-3">{recommendationBadge(vol.recommendation)}</td>
                            <td className="px-5 py-3 text-xs text-gray-500 dark:text-gray-400 max-w-xs">{vol.recommendationReason}</td>
                            <td className="px-5 py-3 text-right font-semibold">
                              {vol.recommendation === 'EXPAND' ? (
                                <span className="text-red-500 dark:text-red-400">+${((vol.recommendedSizeGb - vol.sizeGb) * 0.08).toFixed(2)}/mo</span>
                              ) : (
                                <span className="text-green-600 dark:text-green-400">${vol.estimatedSavings.toFixed(2)}/mo</span>
                              )}
                            </td>
                            <td className="px-5 py-3 text-center">
                              {vol.recommendation === 'DELETE_UNATTACHED' ? (
                                <button
                                  onClick={() => handleApply(vol)}
                                  disabled={applyingId === vol.volumeId}
                                  className="px-3 py-1.5 rounded-lg bg-red-600 hover:bg-red-700 text-white text-xs font-medium transition-colors disabled:opacity-50"
                                >
                                  {applyingId === vol.volumeId
                                    ? <><i className="fas fa-spinner fa-spin mr-1" />Deleting…</>
                                    : <><i className="fas fa-trash-alt mr-1" />Delete</>}
                                </button>
                              ) : (vol.recommendation === 'SHRINK' || vol.recommendation === 'EXPAND') ? (
                                <button
                                  onClick={() => setGuideTarget({
                                    volumeId: vol.volumeId,
                                    region: vol.region,
                                    instanceId: vol.instanceId ?? undefined,
                                    currentSizeGb: vol.sizeGb,
                                    recommendedSizeGb: vol.recommendedSizeGb,
                                    recommendation: vol.recommendation as 'SHRINK' | 'EXPAND',
                                    isRootVolume: vol.isRootVolume,
                                    volumeType: vol.volumeType,
                                  })}
                                  className="px-3 py-1.5 rounded-lg bg-blue-600 hover:bg-blue-700 text-white text-xs font-medium transition-colors"
                                >
                                  <i className="fas fa-book-open mr-1" />How to fix
                                </button>
                              ) : (
                                <button
                                  onClick={() => handleApply(vol)}
                                  disabled={applyingId === vol.volumeId}
                                  className="px-3 py-1.5 rounded-lg bg-indigo-600 hover:bg-indigo-700 text-white text-xs font-medium transition-colors disabled:opacity-50"
                                >
                                  {applyingId === vol.volumeId
                                    ? <><i className="fas fa-spinner fa-spin mr-1" />Applying…</>
                                    : <><i className="fas fa-arrow-up mr-1" />Apply</>}
                                </button>
                              )}
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                )}
              </div>
            )}

            {/* POLICIES TAB */}
            {activeTab === 'policies' && (
              <PoliciesPanel
                policies={policies}
                accountId={selectedAccountId}
                onCreate={createPolicy}
                onUpdate={updatePolicy}
                onDelete={deletePolicy}
              />
            )}

            {/* ACTIONS HISTORY TAB */}
            {activeTab === 'actions' && (
              <div className="rounded-2xl border border-gray-200 dark:border-gray-700 bg-white dark:bg-gray-800 shadow-sm overflow-hidden">
                {actionHistory.length === 0 ? (
                  <div className="p-12 text-center">
                    <i className="fas fa-history text-gray-300 text-4xl mb-3" />
                    <h3 className="text-lg font-bold text-gray-800 dark:text-gray-200">No Actions Yet</h3>
                    <p className="text-gray-500 dark:text-gray-400 text-sm mt-1">Actions applied from Recommendations will appear here.</p>
                  </div>
                ) : (
                  <div className="overflow-x-auto">
                    <table className="w-full text-sm">
                      <thead>
                        <tr className="border-b border-gray-200 dark:border-gray-700 bg-gray-50 dark:bg-gray-800/80">
                          <th className="text-left px-5 py-3 font-semibold text-gray-600 dark:text-gray-400">Volume</th>
                          <th className="text-left px-5 py-3 font-semibold text-gray-600 dark:text-gray-400">Region</th>
                          <th className="text-left px-5 py-3 font-semibold text-gray-600 dark:text-gray-400">Action</th>
                          <th className="text-left px-5 py-3 font-semibold text-gray-600 dark:text-gray-400">Change</th>
                          <th className="text-left px-5 py-3 font-semibold text-gray-600 dark:text-gray-400">Status</th>
                          <th className="text-right px-5 py-3 font-semibold text-gray-600 dark:text-gray-400">Savings/mo</th>
                          <th className="text-left px-5 py-3 font-semibold text-gray-600 dark:text-gray-400">Date</th>
                        </tr>
                      </thead>
                      <tbody>
                        {actionHistory.map((a) => (
                          <tr key={a.id} className="border-b border-gray-100 dark:border-gray-700/50 hover:bg-gray-50 dark:hover:bg-gray-700/30 transition-colors">
                            <td className="px-5 py-3 font-mono text-xs text-gray-800 dark:text-gray-200">{a.volumeId}</td>
                            <td className="px-5 py-3 text-gray-600 dark:text-gray-400">{a.region}</td>
                            <td className="px-5 py-3">
                              <span className="text-xs font-medium text-gray-700 dark:text-gray-300">
                                {a.actionType.replace(/_/g, ' ')}
                              </span>
                            </td>
                            <td className="px-5 py-3 text-xs text-gray-500 dark:text-gray-400">
                              {a.oldType && a.newType && a.oldType !== a.newType && `${a.oldType} → ${a.newType}`}
                              {a.oldSizeGb && a.newSizeGb && a.oldSizeGb !== a.newSizeGb && ` ${a.oldSizeGb}GB → ${a.newSizeGb}GB`}
                            </td>
                            <td className="px-5 py-3">
                              <StatusBadge status={a.status} />
                            </td>
                            <td className="px-5 py-3 text-right font-semibold text-green-600 dark:text-green-400">
                              {a.estimatedSavings != null ? `$${a.estimatedSavings.toFixed(2)}` : '-'}
                            </td>
                            <td className="px-5 py-3 text-xs text-gray-500 dark:text-gray-400">
                              {new Date(a.initiatedAt).toLocaleDateString()}
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                )}
              </div>
            )}

            {/* ROI CALCULATOR TAB */}
            {activeTab === 'roi' && (
              <RoiCalculator calculateRoi={calculateRoi} />
            )}
          </>
        )}
      </div>

      {/* Guide Modal */}
      {guideTarget && (
        <GuideModal
          target={guideTarget}
          onClose={() => setGuideTarget(null)}
        />
      )}

      {/* Volume Monitoring Drawer */}
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

// ─── Sub-components ────────────────────────────────────────────────────────────

const KpiCard: React.FC<{ icon: string; label: string; value: string; color: string; loading?: boolean }> = ({
  icon, label, value, color, loading
}) => {
  const colorMap: Record<string, string> = {
    indigo:  'bg-indigo-100 text-indigo-600 dark:bg-indigo-900/30 dark:text-indigo-400',
    yellow:  'bg-yellow-100 text-yellow-600 dark:bg-yellow-900/30 dark:text-yellow-400',
    red:     'bg-red-100 text-red-600 dark:bg-red-900/30 dark:text-red-400',
    green:   'bg-green-100 text-green-600 dark:bg-green-900/30 dark:text-green-400',
    orange:  'bg-orange-100 text-orange-600 dark:bg-orange-900/30 dark:text-orange-400',
  };
  return (
    <div className="rounded-2xl border border-gray-200 dark:border-gray-700 bg-white dark:bg-gray-800 shadow-sm p-5 flex items-center gap-4">
      <div className={`w-12 h-12 rounded-xl flex items-center justify-center flex-shrink-0 ${colorMap[color]}`}>
        <i className={`${icon} text-lg`} />
      </div>
      <div>
        <div className="text-xs text-gray-500 dark:text-gray-400 font-medium">{label}</div>
        {loading ? (
          <div className="h-7 w-16 bg-gray-200 dark:bg-gray-700 rounded animate-pulse mt-1" />
        ) : (
          <div className="text-2xl font-bold text-gray-900 dark:text-white">{value}</div>
        )}
      </div>
    </div>
  );
};

const StatusBadge: React.FC<{ status: string }> = ({ status }) => {
  const styles: Record<string, string> = {
    SUCCESS:     'bg-green-100 text-green-700 dark:bg-green-900/30 dark:text-green-400',
    FAILED:      'bg-red-100 text-red-700 dark:bg-red-900/30 dark:text-red-400',
    IN_PROGRESS: 'bg-blue-100 text-blue-700 dark:bg-blue-900/30 dark:text-blue-400',
    PENDING:     'bg-gray-100 text-gray-600 dark:bg-gray-700 dark:text-gray-400',
  };
  return (
    <span className={`px-2 py-0.5 rounded-full text-xs font-semibold ${styles[status] || styles.PENDING}`}>
      {status.replace(/_/g, ' ')}
    </span>
  );
};

const DISK_TYPES = ['gp2', 'gp3', 'io1', 'io2', 'sc1', 'st1'];

const PoliciesPanel: React.FC<{
  policies: DiskPolicy[];
  accountId: string;
  onCreate: (p: DiskPolicy) => Promise<any>;
  onUpdate: (id: number, p: DiskPolicy) => Promise<any>;
  onDelete: (id: number) => Promise<void>;
}> = ({ policies, onCreate, onUpdate, onDelete }) => {
  const [showForm, setShowForm] = useState(false);
  const [editing, setEditing] = useState<DiskPolicy | null>(null);
  const [form, setForm] = useState<DiskPolicy>({
    policyName: '', desiredUtilization: 75, minDiskSizeGb: 10,
    maxDiskCount: 10, policyType: 'STANDARD', cloudProvider: 'AWS', isDefault: false,
  });

  const openCreate = () => { setEditing(null); setForm({ policyName: '', desiredUtilization: 75, minDiskSizeGb: 10, maxDiskCount: 10, policyType: 'STANDARD', cloudProvider: 'AWS', isDefault: false }); setShowForm(true); };
  const openEdit = (p: DiskPolicy) => { setEditing(p); setForm({ ...p }); setShowForm(true); };

  const handleSubmit = async () => {
    if (editing?.id) {
      await onUpdate(editing.id, form);
    } else {
      await onCreate(form);
    }
    setShowForm(false);
  };

  return (
    <div className="space-y-4">
      <div className="flex justify-between items-center">
        <p className="text-sm text-gray-500 dark:text-gray-400">Define policies to auto-manage disk sizing thresholds.</p>
        <button
          onClick={openCreate}
          className="flex items-center gap-2 px-4 py-2 rounded-xl bg-indigo-600 hover:bg-indigo-700 text-white text-sm font-medium transition-colors"
        >
          <i className="fas fa-plus" /> Create Policy
        </button>
      </div>

      {policies.length === 0 ? (
        <div className="text-center py-12 rounded-2xl border-2 border-dashed border-gray-300 dark:border-gray-700">
          <i className="fas fa-shield-alt text-gray-300 text-4xl mb-3" />
          <h3 className="text-lg font-bold text-gray-700 dark:text-gray-300">No Policies</h3>
          <p className="text-sm text-gray-500 dark:text-gray-400 mt-1">Create a policy to define auto-scaling rules.</p>
        </div>
      ) : (
        <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-4">
          {policies.map((p) => (
            <div key={p.id} className="rounded-2xl border border-gray-200 dark:border-gray-700 bg-white dark:bg-gray-800 shadow-sm p-5">
              <div className="flex items-start justify-between mb-3">
                <div>
                  <div className="font-semibold text-gray-900 dark:text-white flex items-center gap-2">
                    {p.policyName}
                    {p.isDefault && <span className="px-2 py-0.5 rounded-full text-xs bg-indigo-100 text-indigo-700 dark:bg-indigo-900/30 dark:text-indigo-400">Default</span>}
                  </div>
                  <div className="text-xs text-gray-500 dark:text-gray-400 mt-0.5">{p.cloudProvider} · {p.policyType}</div>
                </div>
                <div className="flex gap-2">
                  <button onClick={() => openEdit(p)} className="text-gray-400 hover:text-indigo-500 transition-colors"><i className="fas fa-pencil-alt text-sm" /></button>
                  <button onClick={() => p.id && onDelete(p.id)} className="text-gray-400 hover:text-red-500 transition-colors"><i className="fas fa-trash text-sm" /></button>
                </div>
              </div>
              <div className="grid grid-cols-2 gap-2 text-sm">
                <div className="bg-gray-50 dark:bg-gray-700/50 rounded-lg p-2">
                  <div className="text-xs text-gray-500 dark:text-gray-400">Utilization</div>
                  <div className="font-semibold text-gray-800 dark:text-gray-200">{p.desiredUtilization}%</div>
                </div>
                <div className="bg-gray-50 dark:bg-gray-700/50 rounded-lg p-2">
                  <div className="text-xs text-gray-500 dark:text-gray-400">Min Disk</div>
                  <div className="font-semibold text-gray-800 dark:text-gray-200">{p.minDiskSizeGb} GB</div>
                </div>
                <div className="bg-gray-50 dark:bg-gray-700/50 rounded-lg p-2">
                  <div className="text-xs text-gray-500 dark:text-gray-400">Max Disks</div>
                  <div className="font-semibold text-gray-800 dark:text-gray-200">{p.maxDiskCount}</div>
                </div>
                <div className="bg-gray-50 dark:bg-gray-700/50 rounded-lg p-2">
                  <div className="text-xs text-gray-500 dark:text-gray-400">Type</div>
                  <div className="font-semibold text-gray-800 dark:text-gray-200">{p.targetDiskType || p.policyType}</div>
                </div>
              </div>
            </div>
          ))}
        </div>
      )}

      {/* Policy Form Modal */}
      {showForm && (
        <div className="fixed inset-0 bg-black/50 backdrop-blur-sm z-50 flex items-center justify-center p-4">
          <div className="bg-white dark:bg-gray-800 rounded-2xl shadow-2xl w-full max-w-lg p-6">
            <div className="flex items-center justify-between mb-5">
              <h3 className="text-lg font-bold text-gray-900 dark:text-white">
                {editing ? 'Edit Policy' : 'Create Policy'}
              </h3>
              <button onClick={() => setShowForm(false)} className="text-gray-400 hover:text-gray-600 dark:hover:text-gray-200">
                <i className="fas fa-times text-lg" />
              </button>
            </div>

            <div className="space-y-4">
              <div>
                <label className="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">Policy Name</label>
                <input
                  type="text"
                  value={form.policyName}
                  onChange={e => setForm(f => ({ ...f, policyName: e.target.value }))}
                  placeholder="e.g. Production Default"
                  className="w-full px-3 py-2 rounded-xl border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-700 text-gray-900 dark:text-white text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500"
                />
              </div>

              <div className="grid grid-cols-2 gap-4">
                <div>
                  <label className="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">Desired Utilization (%)</label>
                  <input type="number" min={10} max={95} value={form.desiredUtilization}
                    onChange={e => setForm(f => ({ ...f, desiredUtilization: +e.target.value }))}
                    className="w-full px-3 py-2 rounded-xl border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-700 text-gray-900 dark:text-white text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500"
                  />
                </div>
                <div>
                  <label className="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">Min Disk Size (GB)</label>
                  <input type="number" min={1} value={form.minDiskSizeGb}
                    onChange={e => setForm(f => ({ ...f, minDiskSizeGb: +e.target.value }))}
                    className="w-full px-3 py-2 rounded-xl border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-700 text-gray-900 dark:text-white text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500"
                  />
                </div>
              </div>

              <div className="grid grid-cols-2 gap-4">
                <div>
                  <label className="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">Max Disk Count</label>
                  <input type="number" min={1} value={form.maxDiskCount}
                    onChange={e => setForm(f => ({ ...f, maxDiskCount: +e.target.value }))}
                    className="w-full px-3 py-2 rounded-xl border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-700 text-gray-900 dark:text-white text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500"
                  />
                </div>
                <div>
                  <label className="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">Policy Type</label>
                  <select value={form.policyType}
                    onChange={e => setForm(f => ({ ...f, policyType: e.target.value as any }))}
                    className="w-full px-3 py-2 rounded-xl border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-700 text-gray-900 dark:text-white text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500"
                  >
                    <option value="STANDARD">Standard</option>
                    <option value="IOPS_OPTIMIZED">IOPS Optimized</option>
                    <option value="CUSTOM">Custom</option>
                  </select>
                </div>
              </div>

              {form.policyType === 'CUSTOM' && (
                <div>
                  <label className="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">Target Disk Type</label>
                  <select value={form.targetDiskType || ''}
                    onChange={e => setForm(f => ({ ...f, targetDiskType: e.target.value }))}
                    className="w-full px-3 py-2 rounded-xl border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-700 text-gray-900 dark:text-white text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500"
                  >
                    <option value="">Select disk type</option>
                    {DISK_TYPES.map(t => <option key={t} value={t}>{t}</option>)}
                  </select>
                </div>
              )}

              <label className="flex items-center gap-3 cursor-pointer">
                <input type="checkbox" checked={form.isDefault}
                  onChange={e => setForm(f => ({ ...f, isDefault: e.target.checked }))}
                  className="w-4 h-4 rounded text-indigo-600"
                />
                <span className="text-sm text-gray-700 dark:text-gray-300">Set as default policy</span>
              </label>
            </div>

            <div className="flex gap-3 mt-6">
              <button onClick={() => setShowForm(false)}
                className="flex-1 px-4 py-2 rounded-xl border border-gray-300 dark:border-gray-600 text-gray-700 dark:text-gray-300 text-sm font-medium hover:bg-gray-50 dark:hover:bg-gray-700 transition-colors">
                Cancel
              </button>
              <button onClick={handleSubmit}
                className="flex-1 px-4 py-2 rounded-xl bg-indigo-600 hover:bg-indigo-700 text-white text-sm font-medium transition-colors">
                {editing ? 'Update' : 'Create'} Policy
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};

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
    <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
      {/* Input Panel */}
      <div className="rounded-2xl border border-gray-200 dark:border-gray-700 bg-white dark:bg-gray-800 shadow-sm p-6 space-y-5">
        <h3 className="text-base font-semibold text-gray-800 dark:text-gray-200">Input</h3>

        <div>
          <label className="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">Cloud Provider</label>
          <div className="flex gap-2">
            {['AWS', 'GCP', 'Azure'].map(p => (
              <button key={p}
                className={`flex-1 py-2 rounded-xl text-sm font-medium border transition-colors ${
                  p === 'AWS'
                    ? 'bg-indigo-600 text-white border-indigo-600'
                    : 'border-gray-300 dark:border-gray-600 text-gray-500 dark:text-gray-400 opacity-50 cursor-not-allowed'
                }`}
                disabled={p !== 'AWS'}
              >
                {p}
              </button>
            ))}
          </div>
        </div>

        <div>
          <label className="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">
            Disk Provisioned: <span className="font-bold text-indigo-600">{provisionedGb} GB</span>
          </label>
          <input type="range" min={10} max={16000} step={10} value={provisionedGb}
            onChange={e => setProvisionedGb(+e.target.value)}
            className="w-full accent-indigo-600"
          />
        </div>

        <div>
          <label className="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">
            Disk Utilization: <span className="font-bold text-indigo-600">{utilizationPct}%</span>
          </label>
          <input type="range" min={1} max={95} value={utilizationPct}
            onChange={e => setUtilizationPct(+e.target.value)}
            className="w-full accent-indigo-600"
          />
        </div>

        <div>
          <label className="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">
            Data Growth Rate (per year): <span className="font-bold text-indigo-600">{growthRatePct}%</span>
          </label>
          <input type="range" min={0} max={100} value={growthRatePct}
            onChange={e => setGrowthRatePct(+e.target.value)}
            className="w-full accent-indigo-600"
          />
        </div>

        <div>
          <label className="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">Current Disk Type</label>
          <select value={diskType} onChange={e => setDiskType(e.target.value)}
            className="w-full px-3 py-2 rounded-xl border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-700 text-gray-900 dark:text-white text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500">
            {DISK_TYPES.map(t => <option key={t} value={t}>{t}</option>)}
          </select>
        </div>

        <div className="flex gap-3">
          <button onClick={handleCalculate} disabled={loading}
            className="flex-1 py-2.5 rounded-xl bg-indigo-600 hover:bg-indigo-700 text-white text-sm font-medium transition-colors disabled:opacity-60">
            {loading ? <><i className="fas fa-spinner fa-spin mr-2" />Calculating...</> : 'Calculate'}
          </button>
          <button onClick={() => setResult(null)}
            className="px-4 py-2.5 rounded-xl border border-gray-300 dark:border-gray-600 text-gray-600 dark:text-gray-400 text-sm font-medium hover:bg-gray-50 dark:hover:bg-gray-700 transition-colors">
            Reset
          </button>
        </div>
      </div>

      {/* Results Panel */}
      <div className="rounded-2xl border border-gray-200 dark:border-gray-700 bg-white dark:bg-gray-800 shadow-sm p-6">
        <h3 className="text-base font-semibold text-gray-800 dark:text-gray-200 mb-5">Results</h3>
        {!result ? (
          <div className="flex flex-col items-center justify-center h-64 text-gray-400">
            <i className="fas fa-calculator text-4xl mb-3" />
            <p className="text-sm">Configure inputs and click Calculate</p>
          </div>
        ) : (
          <div className="space-y-4">
            <div className="grid grid-cols-2 gap-3 text-sm">
              <div className="bg-gray-50 dark:bg-gray-700/50 rounded-xl p-3">
                <div className="text-xs text-gray-500 dark:text-gray-400">Before</div>
                <div className="text-lg font-bold text-gray-800 dark:text-gray-200">${result.currentCostPerMonth}/mo</div>
                <div className="text-xs text-gray-500 dark:text-gray-400 mt-0.5">{result.provisionedGb}GB {result.currentType}</div>
              </div>
              <div className="bg-green-50 dark:bg-green-900/20 rounded-xl p-3">
                <div className="text-xs text-green-600 dark:text-green-400">After</div>
                <div className="text-lg font-bold text-green-700 dark:text-green-300">${result.newCostPerMonth}/mo</div>
                <div className="text-xs text-green-600 dark:text-green-400 mt-0.5">{result.targetGb}GB {result.recommendedType}</div>
              </div>
            </div>

            {result.monthlySavings >= 0 ? (
              <div className="rounded-xl bg-indigo-50 dark:bg-indigo-900/20 p-4 text-center">
                <div className="text-xs text-indigo-600 dark:text-indigo-400 font-medium">Monthly Savings</div>
                <div className="text-3xl font-bold text-indigo-700 dark:text-indigo-300 mt-1">
                  ${result.monthlySavings.toFixed(2)}
                </div>
                <div className="text-sm text-indigo-600 dark:text-indigo-400 mt-0.5">{result.savingsPct.toFixed(1)}% reduction</div>
              </div>
            ) : (
              <div className="rounded-xl bg-amber-50 dark:bg-amber-900/20 p-4 text-center">
                <div className="text-xs text-amber-600 dark:text-amber-400 font-medium">Monthly Cost Increase</div>
                <div className="text-3xl font-bold text-amber-700 dark:text-amber-300 mt-1">
                  +${Math.abs(result.monthlySavings).toFixed(2)}
                </div>
                <div className="text-sm text-amber-600 dark:text-amber-400 mt-0.5">Volume needs to grow due to high growth rate</div>
              </div>
            )}

            <div className="grid grid-cols-2 gap-3">
              <div className="rounded-xl border border-gray-200 dark:border-gray-700 p-3 text-center">
                <div className="text-xs text-gray-500 dark:text-gray-400">{result.monthlySavings >= 0 ? '1 Year Savings' : '1 Year Cost Increase'}</div>
                <div className={`text-xl font-bold ${result.firstYearSavings >= 0 ? 'text-gray-800 dark:text-gray-200' : 'text-amber-600 dark:text-amber-400'}`}>
                  {result.firstYearSavings >= 0 ? '$' : '+$'}{Math.abs(result.firstYearSavings).toFixed(2)}
                </div>
              </div>
              <div className="rounded-xl border border-gray-200 dark:border-gray-700 p-3 text-center">
                <div className="text-xs text-gray-500 dark:text-gray-400">{result.monthlySavings >= 0 ? '5 Year Savings' : '5 Year Cost Increase'}</div>
                <div className={`text-xl font-bold ${result.fiveYearSavings >= 0 ? 'text-gray-800 dark:text-gray-200' : 'text-amber-600 dark:text-amber-400'}`}>
                  {result.fiveYearSavings >= 0 ? '$' : '+$'}{Math.abs(result.fiveYearSavings).toFixed(2)}
                </div>
              </div>
            </div>

            <div className="text-xs text-gray-400 dark:text-gray-500 text-center">
              Based on gp3 pricing ($0.08/GB/mo). Actual savings may vary by region.
            </div>
          </div>
        )}
      </div>
    </div>
  );
};

const GuideModal: React.FC<{
  target: GuideTarget;
  onClose: () => void;
}> = ({ target, onClose }) => {
  const [copiedStep, setCopiedStep] = React.useState<number | null>(null);
  const targetSize = target.recommendedSizeGb ?? Math.ceil(target.currentSizeGb * 0.6);

  const copy = (text: string, step: number) => {
    navigator.clipboard.writeText(text).then(() => {
      setCopiedStep(step);
      setTimeout(() => setCopiedStep(null), 2000);
    });
  };

  const isShrink = target.recommendation === 'SHRINK';

  const shrinkSteps = [
    {
      label: 'Create a snapshot (safety backup)',
      cmd: `aws ec2 create-snapshot --volume-id ${target.volumeId} --description "pre-shrink-backup" --region ${target.region}`,
    },
    {
      label: `Create a new ${targetSize}GB volume in the same Availability Zone`,
      cmd: `aws ec2 create-volume --size ${targetSize} --availability-zone <your-az> --volume-type ${target.volumeType ?? 'gp3'} --region ${target.region}`,
    },
    {
      label: 'Attach the new volume to your instance',
      cmd: `aws ec2 attach-volume --volume-id <new-vol-id> --instance-id ${target.instanceId ?? '<instance-id>'} --device /dev/xvdz --region ${target.region}`,
    },
    {
      label: 'SSH into the instance — format, mount, and copy data',
      cmd: [
        'sudo mkfs.ext4 /dev/xvdz',
        'sudo mkdir -p /mnt/new_vol',
        'sudo mount /dev/xvdz /mnt/new_vol',
        'sudo rsync -axHAX <mount-point>/ /mnt/new_vol/',
      ].join('\n'),
    },
    {
      label: 'Swap mounts and update /etc/fstab',
      cmd: [
        'NEW_UUID=$(sudo blkid -s UUID -o value /dev/xvdz)',
        'sudo umount <mount-point>',
        'sudo umount /mnt/new_vol',
        'sudo mount /dev/xvdz <mount-point>',
        `sudo sed -i '/<mount-point>/d' /etc/fstab`,
        'echo "UUID=$NEW_UUID <mount-point> ext4 defaults,nofail 0 2" | sudo tee -a /etc/fstab',
      ].join('\n'),
    },
    {
      label: 'Detach the old volume',
      cmd: `aws ec2 detach-volume --volume-id ${target.volumeId} --region ${target.region}`,
    },
  ];

  const expandSteps = [
    {
      label: `Modify volume size to ${targetSize}GB`,
      cmd: `aws ec2 modify-volume --volume-id ${target.volumeId} --size ${targetSize} --region ${target.region}`,
    },
    {
      label: 'Wait for modification to complete (~1–2 min)',
      cmd: `aws ec2 describe-volumes-modifications --volume-ids ${target.volumeId} --region ${target.region} --query 'VolumesModifications[0].ModificationState'`,
    },
    {
      label: 'SSH into the instance — grow partition and resize filesystem',
      cmd: [
        '# For xvda root volume:',
        'sudo growpart /dev/xvda 1',
        'sudo resize2fs /dev/xvda1    # ext4',
        '# OR: sudo xfs_growfs /      # xfs (Amazon Linux 2)',
      ].join('\n'),
    },
  ];

  const steps = isShrink ? shrinkSteps : expandSteps;

  return (
    <div className="fixed inset-0 bg-black/60 backdrop-blur-sm z-50 flex items-center justify-center p-4" onClick={onClose}>
      <div className="bg-white dark:bg-gray-800 rounded-2xl shadow-2xl w-full max-w-2xl max-h-[90vh] overflow-y-auto" onClick={e => e.stopPropagation()}>
        {/* Header */}
        <div className="flex items-center justify-between p-6 border-b border-gray-200 dark:border-gray-700">
          <div>
            <h3 className="text-lg font-bold text-gray-900 dark:text-white flex items-center gap-2">
              <i className={`fas ${isShrink ? 'fa-compress-arrows-alt text-orange-500' : 'fa-expand-arrows-alt text-amber-500'}`} />
              {isShrink ? 'How to Shrink This Volume' : 'How to Expand This Volume'}
            </h3>
            <p className="text-xs text-gray-500 dark:text-gray-400 mt-0.5 font-mono">{target.volumeId} · {target.currentSizeGb}GB → {targetSize}GB · {target.region}</p>
          </div>
          <button onClick={onClose} className="text-gray-400 hover:text-gray-600 dark:hover:text-gray-200 p-1">
            <i className="fas fa-times text-lg" />
          </button>
        </div>

        <div className="p-6 space-y-4">
          {/* Warning banner */}
          {isShrink && (
            <div className="bg-amber-50 dark:bg-amber-900/20 border border-amber-200 dark:border-amber-800 rounded-xl p-3 flex gap-2 text-xs text-amber-800 dark:text-amber-300">
              <i className="fas fa-exclamation-triangle mt-0.5 flex-shrink-0" />
              <span>AWS does not support shrinking volumes in-place. You must create a new volume, copy data, then swap. Take a snapshot first — data loss is possible if interrupted.</span>
            </div>
          )}

          {/* Steps */}
          <div className="space-y-3">
            {steps.map((step, i) => (
              <div key={i} className="rounded-xl border border-gray-200 dark:border-gray-700 overflow-hidden">
                <div className="flex items-center gap-2 px-4 py-2 bg-gray-50 dark:bg-gray-700/50">
                  <span className="w-5 h-5 rounded-full bg-blue-600 text-white text-xs font-bold flex items-center justify-center flex-shrink-0">{i + 1}</span>
                  <span className="text-xs font-medium text-gray-700 dark:text-gray-300">{step.label}</span>
                </div>
                <div className="relative bg-gray-900 px-4 py-3">
                  <pre className="text-xs text-green-300 font-mono whitespace-pre-wrap break-all pr-16">{step.cmd}</pre>
                  <button
                    onClick={() => copy(step.cmd, i)}
                    className="absolute top-2 right-2 px-2 py-1 rounded bg-gray-700 hover:bg-gray-600 text-gray-300 text-xs transition-colors"
                  >
                    {copiedStep === i ? <><i className="fas fa-check mr-1 text-green-400" />Copied</> : <><i className="fas fa-copy mr-1" />Copy</>}
                  </button>
                </div>
              </div>
            ))}
          </div>

          {/* Savings note */}
          {isShrink && (
            <div className="flex items-center justify-between bg-green-50 dark:bg-green-900/20 rounded-xl px-4 py-3 text-sm">
              <span className="text-gray-600 dark:text-gray-400">Estimated savings after shrink:</span>
              <span className="font-bold text-green-600 dark:text-green-400">
                ~${((target.currentSizeGb - targetSize) * 0.08).toFixed(2)}/mo
              </span>
            </div>
          )}
        </div>

        <div className="px-6 pb-6">
          <button onClick={onClose} className="w-full px-4 py-2.5 rounded-xl border border-gray-300 dark:border-gray-600 text-gray-700 dark:text-gray-300 text-sm font-medium hover:bg-gray-50 dark:hover:bg-gray-700 transition-colors">
            Close
          </button>
        </div>
      </div>
    </div>
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
    <div className="rounded-2xl border border-amber-200 dark:border-amber-800 bg-amber-50 dark:bg-amber-900/10 shadow-sm overflow-hidden">
      <div className="px-6 py-4 border-b border-amber-200 dark:border-amber-800 flex items-center justify-between">
        <div className="flex items-center gap-2">
          <i className="fas fa-clock text-amber-500" />
          <h3 className="text-base font-semibold text-amber-800 dark:text-amber-300">Pending Deletion</h3>
          <span className="text-xs bg-amber-100 dark:bg-amber-900/40 text-amber-700 dark:text-amber-400 px-2 py-0.5 rounded-full">
            {volumes.length} volume{volumes.length !== 1 ? 's' : ''}
          </span>
        </div>
        <span className="text-xs text-amber-600 dark:text-amber-400">
          Old volumes from shrink operations — kept for 7 days as rollback
        </span>
      </div>
      <div className="overflow-x-auto">
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b border-amber-200 dark:border-amber-800 bg-amber-100/50 dark:bg-amber-900/20">
              <th className="text-left px-5 py-3 font-semibold text-amber-700 dark:text-amber-400">Volume ID</th>
              <th className="text-left px-5 py-3 font-semibold text-amber-700 dark:text-amber-400">Region</th>
              <th className="text-left px-5 py-3 font-semibold text-amber-700 dark:text-amber-400">Type</th>
              <th className="text-right px-5 py-3 font-semibold text-amber-700 dark:text-amber-400">Size</th>
              <th className="text-left px-5 py-3 font-semibold text-amber-700 dark:text-amber-400">Source Volume</th>
              <th className="text-center px-5 py-3 font-semibold text-amber-700 dark:text-amber-400">Auto-Delete In</th>
              <th className="text-right px-5 py-3 font-semibold text-amber-700 dark:text-amber-400">Cost/mo</th>
              <th className="text-center px-5 py-3 font-semibold text-amber-700 dark:text-amber-400">Actions</th>
            </tr>
          </thead>
          <tbody>
            {volumes.map((v) => {
              const days = daysLeft(v.deleteAfter);
              return (
                <tr key={v.volumeId} className="border-b border-amber-100 dark:border-amber-800/40 hover:bg-amber-50 dark:hover:bg-amber-900/20 transition-colors">
                  <td className="px-5 py-3 font-mono text-xs text-gray-800 dark:text-gray-200">{v.volumeId}</td>
                  <td className="px-5 py-3 text-xs text-gray-600 dark:text-gray-400">{v.region}</td>
                  <td className="px-5 py-3">
                    <span className="px-2 py-0.5 rounded text-xs font-medium bg-blue-50 text-blue-700 dark:bg-blue-900/30 dark:text-blue-300">
                      {v.volumeType}
                    </span>
                  </td>
                  <td className="px-5 py-3 text-right text-sm font-semibold text-gray-800 dark:text-gray-200">{v.sizeGb} GB</td>
                  <td className="px-5 py-3 font-mono text-xs text-gray-500 dark:text-gray-400">
                    {v.sourceVolumeId ?? '—'}
                  </td>
                  <td className="px-5 py-3 text-center">
                    {days !== null ? (
                      <span className={`px-2 py-1 rounded-full text-xs font-semibold ${
                        days <= 1 ? 'bg-red-100 text-red-700 dark:bg-red-900/30 dark:text-red-400'
                        : days <= 3 ? 'bg-amber-100 text-amber-700 dark:bg-amber-900/30 dark:text-amber-400'
                        : 'bg-gray-100 text-gray-600 dark:bg-gray-700 dark:text-gray-400'
                      }`}>
                        {days === 0 ? 'Today' : `${days}d`}
                      </span>
                    ) : '—'}
                  </td>
                  <td className="px-5 py-3 text-right text-sm font-medium text-gray-700 dark:text-gray-300">
                    ${v.monthlyCost.toFixed(2)}
                  </td>
                  <td className="px-5 py-3 text-center">
                    <div className="flex items-center justify-center gap-2">
                      <button
                        onClick={() => handle(v, 'delete')}
                        disabled={actingId === v.volumeId}
                        className="px-2.5 py-1 rounded-lg bg-red-500 hover:bg-red-600 text-white text-xs font-medium transition-colors disabled:opacity-50"
                        title="Permanently delete this volume now"
                      >
                        {actingId === v.volumeId ? <i className="fas fa-spinner fa-spin" /> : 'Delete Now'}
                      </button>
                      <button
                        onClick={() => handle(v, 'keep')}
                        disabled={actingId === v.volumeId}
                        className="px-2.5 py-1 rounded-lg bg-green-500 hover:bg-green-600 text-white text-xs font-medium transition-colors disabled:opacity-50"
                        title="Remove deletion tag and keep this volume"
                      >
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

// ── Disk Agent Setup Guide ─────────────────────────────────────────────────────

const SsmSetupGuide: React.FC<{ count: number; awsAccountId: string | null }> = ({ count, awsAccountId }) => {
  const [expanded, setExpanded] = React.useState(false);
  const [copied, setCopied] = React.useState(false);

  // In dev the frontend runs on :5173 but the backend is on :8080.
  // In production both are served from the same host/port so no replacement needed.
  const detectedBase = window.location.origin.replace(':5173', ':8080');
  const isLocalhost = detectedBase.includes('localhost') || detectedBase.includes('127.0.0.1');

  const [serverUrl, setServerUrl] = React.useState(detectedBase);

  const accountArg = awsAccountId ?? '<aws-account-id>';
  const isNgrok = serverUrl.includes('ngrok');
  const curlHeaders = isNgrok ? ' -H "ngrok-skip-browser-warning: true"' : '';
  const AGENT_CMD = `curl -fsSL${curlHeaders} ${serverUrl}/agentinstall/agent_install.sh | sudo bash -s -- -b ${serverUrl} -t ${accountArg}`;

  const copy = () => {
    navigator.clipboard.writeText(AGENT_CMD).then(() => {
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    });
  };

  return (
    <div className="rounded-xl border border-amber-200 dark:border-amber-800 bg-amber-50 dark:bg-amber-900/20 overflow-hidden">
      <div className="px-4 py-3 flex items-start gap-3">
        <i className="fas fa-exclamation-triangle text-amber-500 mt-0.5 flex-shrink-0" />
        <div className="flex-1 min-w-0">
          <p className="text-sm font-semibold text-amber-800 dark:text-amber-300">
            Disk usage unavailable for {count} instance{count > 1 ? 's' : ''}
          </p>
          <p className="text-xs text-amber-700 dark:text-amber-400 mt-0.5">
            Install the XamOps disk agent on each instance to enable SHRINK/EXPAND recommendations — no IAM changes needed.
          </p>
        </div>
        <button
          onClick={() => setExpanded(e => !e)}
          className="flex-shrink-0 flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-amber-600 hover:bg-amber-700 text-white text-xs font-semibold transition-colors"
        >
          <i className={`fas ${expanded ? 'fa-chevron-up' : 'fa-robot'}`} />
          {expanded ? 'Hide' : 'Setup Agent'}
        </button>
      </div>

      {expanded && (
        <div className="border-t border-amber-200 dark:border-amber-800 px-4 py-4 space-y-4 bg-white dark:bg-gray-800/60">
          <p className="text-xs text-gray-500 dark:text-gray-400">
            A lightweight systemd service that runs <code className="px-1 rounded bg-gray-100 dark:bg-gray-700 font-mono">df</code> every 5 minutes and pushes disk usage to XamOps — no IAM changes needed.
          </p>

          {/* Server URL override — shown when localhost is detected */}
          {isLocalhost && (
            <div className="rounded-lg bg-yellow-50 dark:bg-yellow-900/20 border border-yellow-200 dark:border-yellow-800 px-3 py-2.5 space-y-2">
              <p className="text-xs font-medium text-yellow-800 dark:text-yellow-300 flex items-center gap-1.5">
                <i className="fas fa-exclamation-circle" />
                EC2 instances can't reach <code className="font-mono">localhost</code> — enter the public IP or hostname of this XamOps server:
              </p>
              <input
                type="text"
                value={serverUrl}
                onChange={e => setServerUrl(e.target.value.replace(/\/$/, ''))}
                placeholder="http://1.2.3.4:8080"
                className="w-full px-3 py-1.5 rounded-lg border border-yellow-300 dark:border-yellow-700 bg-white dark:bg-gray-800 text-sm font-mono text-gray-800 dark:text-gray-200 focus:outline-none focus:ring-2 focus:ring-yellow-400"
              />
            </div>
          )}

          {/* Install command */}
          <div className="flex gap-3">
            <div className="w-6 h-6 rounded-full bg-indigo-100 dark:bg-indigo-900/40 text-indigo-700 dark:text-indigo-300 text-xs font-bold flex items-center justify-center flex-shrink-0 mt-0.5">1</div>
            <div className="flex-1">
              <p className="text-sm font-medium text-gray-800 dark:text-gray-200 mb-2">
                SSH into each EC2 instance and run
              </p>
              <div className="relative rounded-lg bg-gray-900 p-3 font-mono text-xs text-green-300 overflow-x-auto">
                <pre className="whitespace-pre-wrap break-all">{AGENT_CMD}</pre>
                <button
                  onClick={copy}
                  className="absolute top-2 right-2 px-2 py-1 rounded bg-gray-700 hover:bg-gray-600 text-gray-300 text-xs transition-colors"
                >
                  {copied ? <><i className="fas fa-check mr-1" />Copied</> : <><i className="fas fa-copy mr-1" />Copy</>}
                </button>
              </div>
            </div>
          </div>

          <div className="rounded-lg bg-green-50 dark:bg-green-900/20 border border-green-200 dark:border-green-800 px-3 py-2 text-xs text-green-700 dark:text-green-300 flex items-start gap-2">
            <i className="fas fa-check-circle mt-0.5 flex-shrink-0" />
            <span>Disk usage appears within 1–2 minutes. Click <strong>Refresh</strong> to see SHRINK/EXPAND recommendations.</span>
          </div>
        </div>
      )}
    </div>
  );
};

export default EbsRightsizingPage;
