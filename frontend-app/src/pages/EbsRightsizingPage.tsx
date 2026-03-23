import React, { useState, useEffect } from 'react';
import { useAccounts } from '../contexts/AccountContext';
import { useEbsRightsizingData, EbsVolume, DiskPolicy, RoiResult, PendingDeletionVolume } from '../hooks/useEbsRightsizingData';
import { AriaTabGroup } from '../components/ui';
import { useTopbarActions } from '../contexts/TopbarContext';
import { EbsVolumeMonitoringDrawer } from '../components/EbsRightsizing/EbsVolumeMonitoringDrawer';

interface ShrinkTarget {
  volumeId: string;
  region: string;
  instanceId: string;
  currentSizeGb: number;
  isRootVolume?: boolean;
}

type Tab = 'overview' | 'recommendations' | 'policies' | 'actions' | 'roi';

const EbsRightsizingPage: React.FC = () => {
  const { selectedAccountIds } = useAccounts();
  const selectedAccountId = selectedAccountIds[0] || null;
  const [activeTab, setActiveTab] = useState<Tab>('overview');
  const [applyingId, setApplyingId] = useState<string | null>(null);
  const [actionMsg, setActionMsg] = useState<{ type: 'success' | 'error'; text: string } | null>(null);
  const [shrinkTarget, setShrinkTarget] = useState<ShrinkTarget | null>(null);
  const [monitoringVolume, setMonitoringVolume] = useState<EbsVolume | null>(null);
  const [fixingSsm, setFixingSsm] = useState(false);
  const { setTitle, setActions, clearActions } = useTopbarActions();

  const {
    volumes, recommendations, policies, actionHistory, pendingDeletion,
    isLoading, isDiskUsageLoading, isRefreshing, ssmUnavailableInstances, totalWastedMonthly,
    unattachedCount, shrinkCount, expandCount,
    applyAction, initiateDownsize, createPolicy, updatePolicy, deletePolicy,
    calculateRoi, refreshAll, fixSsmPermissions, deletePendingVolume, keepVolume,
  } = useEbsRightsizingData({ selectedAccountId });

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
    { id: 'policies',        label: 'Policies',         icon: 'fas fa-shield-alt' },
    { id: 'actions',         label: 'Actions History',  icon: 'fas fa-history' },
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

                {/* Breakdown by recommendation type */}
                <div className="rounded-2xl border border-gray-200 dark:border-gray-700 bg-white dark:bg-gray-800 shadow-sm p-6">
                  <h3 className="text-base font-semibold text-gray-800 dark:text-gray-200 mb-4">Savings Breakdown</h3>
                  {isLoading ? (
                    <div className="space-y-3">
                      {[1,2,3].map(i => <div key={i} className="h-8 bg-gray-100 dark:bg-gray-700 rounded animate-pulse" />)}
                    </div>
                  ) : recommendations.length === 0 ? (
                    <p className="text-gray-500 dark:text-gray-400 text-sm">No recommendations available. Try refreshing.</p>
                  ) : (
                    <div className="space-y-3">
                      {[
                        { label: 'Delete Unattached Volumes', type: 'DELETE_UNATTACHED', color: 'bg-red-500' },
                        { label: 'Volume Type Upgrades (gp2→gp3)', type: 'UPGRADE_TYPE', color: 'bg-blue-500' },
                        { label: 'Underutilized — Shrink Candidates', type: 'SHRINK', color: 'bg-orange-500' },
                        { label: 'Near Full — Expand Needed', type: 'EXPAND', color: 'bg-amber-500' },
                      ].map(({ label, type, color }) => {
                        const items = recommendations.filter(r => r.recommendation === type);
                        const savings = items.reduce((s, r) => s + r.estimatedSavings, 0);
                        if (items.length === 0) return null;
                        return (
                          <div key={type} className="flex items-center gap-4">
                            <div className={`w-2 h-8 rounded-full ${color}`} />
                            <div className="flex-1">
                              <div className="text-sm font-medium text-gray-800 dark:text-gray-200">{label}</div>
                              <div className="text-xs text-gray-500 dark:text-gray-400">{items.length} volumes</div>
                            </div>
                            <div className="text-sm font-semibold text-green-600 dark:text-green-400">
                              ${savings.toFixed(2)}/mo
                            </div>
                          </div>
                        );
                      })}
                    </div>
                  )}
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
                  <div className="rounded-xl border border-amber-200 dark:border-amber-800 bg-amber-50 dark:bg-amber-900/20 px-4 py-3 flex items-start gap-3">
                    <i className="fas fa-exclamation-triangle text-amber-500 mt-0.5 flex-shrink-0" />
                    <div className="flex-1 min-w-0">
                      <p className="text-sm font-semibold text-amber-800 dark:text-amber-300">
                        Disk usage unavailable for {ssmUnavailableInstances.length} instance{ssmUnavailableInstances.length > 1 ? 's' : ''}
                      </p>
                      <p className="text-xs text-amber-700 dark:text-amber-400 mt-0.5">
                        SSM agent is not registered. XamOps can automatically attach
                        <code className="mx-1 px-1 py-0.5 rounded bg-amber-100 dark:bg-amber-900/50 font-mono">AmazonSSMManagedInstanceCore</code>
                        to each instance's IAM role — SSM agent will register within ~1 minute.
                      </p>
                    </div>
                    <button
                      disabled={fixingSsm}
                      onClick={async () => {
                        setFixingSsm(true);
                        // Group unavailable instances by region
                        const byRegion: Record<string, string[]> = {};
                        volumes.forEach(v => {
                          if (v.instanceId && ssmUnavailableInstances.includes(v.instanceId)) {
                            (byRegion[v.region] ??= []).push(v.instanceId);
                          }
                        });
                        const results = await Promise.all(
                          Object.entries(byRegion).map(([region, ids]) =>
                            fixSsmPermissions(region, [...new Set(ids)])
                          )
                        );
                        const totalFixed = results.reduce((s, r) => s + (r?.fixed?.length ?? 0), 0);
                        setFixingSsm(false);
                        if (totalFixed > 0) {
                          setActionMsg({ type: 'success', text: `SSM policy attached to ${totalFixed} instance role(s). Refresh in ~1 minute to see disk usage.` });
                        } else {
                          setActionMsg({ type: 'error', text: 'Could not attach SSM policy — instances may have no IAM role attached, or XamOps role lacks iam:AttachRolePolicy permission.' });
                        }
                      }}
                      className="flex-shrink-0 flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-amber-600 hover:bg-amber-700 text-white text-xs font-semibold transition-colors disabled:opacity-60"
                    >
                      {fixingSsm
                        ? <><i className="fas fa-circle-notch fa-spin" /> Fixing…</>
                        : <><i className="fas fa-magic" /> Fix SSM</>}
                    </button>
                  </div>
                )}

                {/* All Volumes Table */}
                <div className="rounded-2xl border border-gray-200 dark:border-gray-700 bg-white dark:bg-gray-800 shadow-sm overflow-hidden">
                  <div className="px-6 py-4 border-b border-gray-200 dark:border-gray-700 flex items-center justify-between">
                    <h3 className="text-base font-semibold text-gray-800 dark:text-gray-200">All Volumes</h3>
                    <span className="text-xs text-gray-500 dark:text-gray-400 bg-gray-100 dark:bg-gray-700 px-2 py-1 rounded-full">
                      {volumes.length} total · {volumes.reduce((s, v) => s + v.sizeGb, 0)} GB provisioned
                    </span>
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
                          {volumes.map((vol) => (
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
                                {vol.attached && vol.instanceId ? (
                                  <button
                                    onClick={() => setShrinkTarget({
                                      volumeId: vol.volumeId,
                                      region: vol.region,
                                      instanceId: vol.instanceId!,
                                      currentSizeGb: vol.sizeGb,
                                      isRootVolume: vol.isRootVolume,
                                    })}
                                    className="px-2.5 py-1 rounded-lg bg-orange-500 hover:bg-orange-600 text-white text-xs font-medium transition-colors"
                                    title={vol.isRootVolume ? 'Migrate root volume to a smaller EBS (requires instance stop)' : 'Shrink volume size'}
                                  >
                                    {vol.isRootVolume ? <><i className="fas fa-exchange-alt mr-1" />Migrate</> : 'Shrink'}
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
                              {volumes.reduce((s, v) => s + v.sizeGb, 0)} GB
                              {volumes.some(v => v.usedGb !== undefined) && (
                                <div className="text-xs font-normal text-gray-400 mt-0.5">
                                  {volumes.reduce((s, v) => s + (v.usedGb ?? 0), 0).toFixed(1)} GB used
                                </div>
                              )}
                            </td>
                            <td colSpan={2} className="px-5 py-3 text-xs text-gray-500 dark:text-gray-400">
                              {volumes.filter(v => v.attached).length} attached · {volumes.filter(v => !v.attached).length} unattached
                            </td>
                            <td className="px-5 py-3 text-right text-sm font-bold text-gray-800 dark:text-gray-200">
                              ${volumes.reduce((s, v) => s + v.monthlyCost, 0).toFixed(2)}/mo
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
                              {vol.recommendation === 'SHRINK' ? (
                                vol.instanceId ? (
                                  <button
                                    onClick={() => setShrinkTarget({
                                      volumeId: vol.volumeId,
                                      region: vol.region,
                                      instanceId: vol.instanceId!,
                                      currentSizeGb: vol.sizeGb,
                                      isRootVolume: vol.isRootVolume,
                                    })}
                                    className="px-3 py-1.5 rounded-lg bg-orange-500 hover:bg-orange-600 text-white text-xs font-medium transition-colors"
                                  >
                                    <i className={`fas ${vol.isRootVolume ? 'fa-exchange-alt' : 'fa-compress-arrows-alt'} mr-1`} />
                                    {vol.isRootVolume ? 'Migrate' : 'Shrink'}
                                  </button>
                                ) : <span className="text-xs text-gray-400">No instance</span>
                              ) : (
                                <button
                                  onClick={() => handleApply(vol)}
                                  disabled={applyingId === vol.volumeId}
                                  className={`px-3 py-1.5 rounded-lg text-white text-xs font-medium transition-colors disabled:opacity-50 ${
                                    vol.recommendation === 'DELETE_UNATTACHED' ? 'bg-red-600 hover:bg-red-700' :
                                    vol.recommendation === 'EXPAND'            ? 'bg-amber-600 hover:bg-amber-700' :
                                    'bg-indigo-600 hover:bg-indigo-700'
                                  }`}
                                >
                                  {applyingId === vol.volumeId
                                    ? <><i className="fas fa-spinner fa-spin mr-1" />Applying…</>
                                    : vol.recommendation === 'DELETE_UNATTACHED' ? <><i className="fas fa-trash-alt mr-1" />Delete</>
                                    : vol.recommendation === 'EXPAND'            ? <><i className="fas fa-expand-arrows-alt mr-1" />Expand</>
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

      {/* Shrink Modal */}
      {shrinkTarget && (
        <ShrinkModal
          target={shrinkTarget}
          onClose={() => setShrinkTarget(null)}
          onSubmit={async (mountPoint, targetSizeGb) => {
            const result = await initiateDownsize(
              shrinkTarget.volumeId, shrinkTarget.region,
              shrinkTarget.instanceId, mountPoint, targetSizeGb
            );
            setShrinkTarget(null);
            if (result.success) {
              setActionMsg({ type: 'success', text: result.message });
              setActiveTab('actions');
            } else {
              setActionMsg({ type: 'error', text: result.error });
            }
          }}
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
            Data Growth Rate: <span className="font-bold text-indigo-600">{growthRatePct}%</span>
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

            <div className="rounded-xl bg-indigo-50 dark:bg-indigo-900/20 p-4 text-center">
              <div className="text-xs text-indigo-600 dark:text-indigo-400 font-medium">Monthly Savings</div>
              <div className="text-3xl font-bold text-indigo-700 dark:text-indigo-300 mt-1">
                ${result.monthlySavings.toFixed(2)}
              </div>
              <div className="text-sm text-indigo-600 dark:text-indigo-400 mt-0.5">{result.savingsPct.toFixed(1)}% reduction</div>
            </div>

            <div className="grid grid-cols-2 gap-3">
              <div className="rounded-xl border border-gray-200 dark:border-gray-700 p-3 text-center">
                <div className="text-xs text-gray-500 dark:text-gray-400">1 Year Savings</div>
                <div className="text-xl font-bold text-gray-800 dark:text-gray-200">${result.firstYearSavings.toFixed(2)}</div>
              </div>
              <div className="rounded-xl border border-gray-200 dark:border-gray-700 p-3 text-center">
                <div className="text-xs text-gray-500 dark:text-gray-400">5 Year Savings</div>
                <div className="text-xl font-bold text-gray-800 dark:text-gray-200">${result.fiveYearSavings.toFixed(2)}</div>
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

const ShrinkModal: React.FC<{
  target: ShrinkTarget;
  onClose: () => void;
  onSubmit: (mountPoint: string, targetSizeGb: number) => Promise<void>;
}> = ({ target, onClose, onSubmit }) => {
  const [mountPoint, setMountPoint] = useState(target.isRootVolume ? '/' : '/data');
  const [targetSizeGb, setTargetSizeGb] = useState(Math.ceil(target.currentSizeGb * 0.4));
  const [submitting, setSubmitting] = useState(false);

  const handleSubmit = async () => {
    setSubmitting(true);
    await onSubmit(mountPoint, targetSizeGb);
    setSubmitting(false);
  };

  return (
    <div className="fixed inset-0 bg-black/60 backdrop-blur-sm z-50 flex items-center justify-center p-4">
      <div className="bg-white dark:bg-gray-800 rounded-2xl shadow-2xl w-full max-w-lg p-6">
        <div className="flex items-center justify-between mb-5">
          <div>
            <h3 className="text-lg font-bold text-gray-900 dark:text-white">
              {target.isRootVolume ? 'Migrate Root Volume' : 'Shrink Data Volume'}
            </h3>
            <p className="text-xs text-gray-500 dark:text-gray-400 mt-0.5 font-mono">{target.volumeId}</p>
          </div>
          <button onClick={onClose} className="text-gray-400 hover:text-gray-600 dark:hover:text-gray-200">
            <i className="fas fa-times text-lg" />
          </button>
        </div>

        {/* Root volume extra warning */}
        {target.isRootVolume && (
          <div className="bg-red-50 dark:bg-red-900/20 border border-red-200 dark:border-red-800 rounded-xl p-3 mb-3 flex gap-3">
            <i className="fas fa-server text-red-500 mt-0.5 flex-shrink-0" />
            <div className="text-xs text-red-700 dark:text-red-300 space-y-1">
              <p><strong>Root Volume Migration:</strong> A new smaller EBS volume will be created, data will be copied via rsync, then the instance will be stopped and root device swapped. The instance <strong>must be stoppable</strong>.</p>
              <p>Set mount point to <code className="bg-red-100 dark:bg-red-900/40 px-1 rounded">/</code> for the root filesystem.</p>
            </div>
          </div>
        )}

        {/* Warning */}
        <div className="bg-orange-50 dark:bg-orange-900/20 border border-orange-200 dark:border-orange-800 rounded-xl p-3 mb-5 flex gap-3">
          <i className="fas fa-exclamation-triangle text-orange-500 mt-0.5 flex-shrink-0" />
          <div className="text-xs text-orange-700 dark:text-orange-300 space-y-1">
            <p><strong>Prerequisites:</strong></p>
            <ul className="list-disc list-inside space-y-0.5">
              <li>SSM Agent must be running on the instance</li>
              <li>Target size must exceed actual used disk space</li>
              <li>{target.isRootVolume ? 'Instance will be stopped during root device swap' : '~5 seconds of app downtime during final swap'}</li>
              <li>A snapshot will be created for rollback before any changes</li>
            </ul>
          </div>
        </div>

        <div className="space-y-4">
          <div className="grid grid-cols-2 gap-4 p-3 bg-gray-50 dark:bg-gray-700/50 rounded-xl text-sm">
            <div>
              <div className="text-xs text-gray-500 dark:text-gray-400">Current Size</div>
              <div className="font-bold text-gray-800 dark:text-gray-200">{target.currentSizeGb} GB</div>
            </div>
            <div>
              <div className="text-xs text-gray-500 dark:text-gray-400">Instance</div>
              <div className="font-mono text-xs text-gray-800 dark:text-gray-200">{target.instanceId}</div>
            </div>
          </div>

          <div>
            <label className="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">
              Mount Point
            </label>
            <input
              type="text"
              value={mountPoint}
              onChange={e => setMountPoint(e.target.value)}
              placeholder="/data"
              className="w-full px-3 py-2 rounded-xl border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-700 text-gray-900 dark:text-white text-sm font-mono focus:outline-none focus:ring-2 focus:ring-orange-500"
            />
            <p className="text-xs text-gray-400 mt-1">Where is this volume mounted on the instance?</p>
          </div>

          <div>
            <label className="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">
              Target Size: <span className="font-bold text-orange-600">{targetSizeGb} GB</span>
              <span className="text-xs text-gray-400 ml-2">
                (saving {target.currentSizeGb - targetSizeGb} GB)
              </span>
            </label>
            <input
              type="range"
              min={10}
              max={target.currentSizeGb - 1}
              value={targetSizeGb}
              onChange={e => setTargetSizeGb(+e.target.value)}
              className="w-full accent-orange-500"
            />
            <div className="flex justify-between text-xs text-gray-400 mt-1">
              <span>10 GB (min)</span>
              <span>{target.currentSizeGb - 1} GB (max)</span>
            </div>
          </div>

          <div className="bg-green-50 dark:bg-green-900/20 rounded-xl p-3 text-sm">
            <div className="flex justify-between">
              <span className="text-gray-600 dark:text-gray-400">Estimated monthly savings:</span>
              <span className="font-bold text-green-600 dark:text-green-400">
                ${((target.currentSizeGb - targetSizeGb) * 0.08).toFixed(2)}/mo
              </span>
            </div>
            <div className="flex justify-between mt-1">
              <span className="text-gray-600 dark:text-gray-400">Expected downtime:</span>
              <span className="font-medium text-gray-700 dark:text-gray-300">~5 seconds</span>
            </div>
          </div>
        </div>

        <div className="flex gap-3 mt-6">
          <button onClick={onClose}
            className="flex-1 px-4 py-2.5 rounded-xl border border-gray-300 dark:border-gray-600 text-gray-700 dark:text-gray-300 text-sm font-medium hover:bg-gray-50 dark:hover:bg-gray-700 transition-colors">
            Cancel
          </button>
          <button onClick={handleSubmit} disabled={submitting}
            className="flex-1 px-4 py-2.5 rounded-xl bg-orange-500 hover:bg-orange-600 text-white text-sm font-medium transition-colors disabled:opacity-60">
            {submitting
              ? <><i className="fas fa-spinner fa-spin mr-2" />Initiating...</>
              : <>Confirm Shrink ({target.currentSizeGb}GB → {targetSizeGb}GB)</>}
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

export default EbsRightsizingPage;
