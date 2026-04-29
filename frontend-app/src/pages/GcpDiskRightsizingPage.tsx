import React, { useState, useEffect } from 'react';
import { useAccounts } from '../contexts/AccountContext';
import { useGcpDiskRightsizingData, GcpDisk } from '../hooks/useGcpDiskRightsizingData';
import { useTopbarActions } from '../contexts/TopbarContext';

const PAGE_SIZE = 20;

const recommendationBadge = (rec: string) => {
  const styles: Record<string, string> = {
    DELETE_UNATTACHED: 'bg-red-100 text-red-700 dark:bg-red-900/30 dark:text-red-400',
    SHRINK:            'bg-amber-100 text-amber-700 dark:bg-amber-900/30 dark:text-amber-400',
    EXPAND:            'bg-blue-100 text-blue-700 dark:bg-blue-900/30 dark:text-blue-400',
    NONE:              'bg-gray-100 text-gray-500 dark:bg-gray-700 dark:text-gray-400',
  };
  const labels: Record<string, string> = {
    DELETE_UNATTACHED: 'Delete (unattached)',
    SHRINK:            'Shrink',
    EXPAND:            'Expand',
    NONE:              'No action',
  };
  return (
    <span className={`px-2 py-0.5 rounded-full text-xs font-semibold ${styles[rec] ?? styles.NONE}`}>
      {labels[rec] ?? rec}
    </span>
  );
};

const GcpDiskRightsizingPage: React.FC = () => {
  const { selectedAccountIds } = useAccounts();
  const selectedAccountId = selectedAccountIds[0] || null;
  const { setTitle, setSubtitle, setActions, clearActions } = useTopbarActions();

  const [filterSearch, setFilterSearch]             = useState('');
  const [filterZone, setFilterZone]                 = useState('');
  const [filterType, setFilterType]                 = useState('');
  const [filterRecommendation, setFilterRecommendation] = useState('');
  const [page, setPage]                             = useState(1);
  const [activeTab, setActiveTab] = useTabState<'overview' | 'history'>('overview');
  const [actionMsg, setActionMsg]                   = useState<{ type: 'success' | 'error'; text: string } | null>(null);
  const [confirmAction, setConfirmAction]           = useState<{ disk: GcpDisk; actionType: string; targetSizeGb?: number } | null>(null);
  const [showInstallGuide, setShowInstallGuide]     = useState(false);

  const { disks, actionHistory, isLoading, isRefreshing, error, applyAction, refreshAll } =
    useGcpDiskRightsizingData(selectedAccountId);

  useEffect(() => {
    setTitle('GCP Disk Rightsizing');
    setSubtitle('Optimize GCP Persistent Disk sizes using real usage data from the XamOps disk agent');
    return () => { setTitle(null); setSubtitle(null); };
  }, []);

  useEffect(() => {
    setActions(
      <div className="flex items-center gap-2">
        <button
          onClick={() => setShowInstallGuide(v => !v)}
          className="flex items-center gap-1.5 px-3 py-2 rounded-xl border border-gray-300 dark:border-gray-600 text-gray-600 dark:text-gray-300 text-sm font-medium hover:bg-gray-100 dark:hover:bg-gray-700 transition-colors"
        >
          <i className="fas fa-terminal" />
          Install Agent
        </button>
        <button
          onClick={refreshAll}
          disabled={isRefreshing}
          className="flex items-center gap-2 px-4 py-2 rounded-xl bg-indigo-600 hover:bg-indigo-700 text-white text-sm font-medium transition-colors disabled:opacity-60"
        >
          <i className={`fas fa-sync-alt ${isRefreshing ? 'animate-spin' : ''}`} />
          {isRefreshing ? 'Refreshing...' : 'Refresh'}
        </button>
      </div>
    );
    return () => clearActions();
  }, [isRefreshing, refreshAll]);

  const uniqueZones = Array.from(new Set(disks.map(d => d.zone))).sort();
  const uniqueTypes = Array.from(new Set(disks.map(d => d.diskType))).sort();

  const filteredDisks = disks.filter(d => {
    if (filterSearch && !d.diskName.toLowerCase().includes(filterSearch.toLowerCase()) &&
        !(d.instanceName ?? '').toLowerCase().includes(filterSearch.toLowerCase())) return false;
    if (filterZone && d.zone !== filterZone) return false;
    if (filterType && d.diskType !== filterType) return false;
    if (filterRecommendation && d.recommendation !== filterRecommendation) return false;
    return true;
  });

  const totalPages = Math.ceil(filteredDisks.length / PAGE_SIZE);
  const paginated  = filteredDisks.slice((page - 1) * PAGE_SIZE, page * PAGE_SIZE);

  const totalWasted   = disks.reduce((s, d) => s + d.estimatedSavings, 0);
  const unattachedCnt = disks.filter(d => d.recommendation === 'DELETE_UNATTACHED').length;
  const shrinkCnt     = disks.filter(d => d.recommendation === 'SHRINK').length;
  const expandCnt     = disks.filter(d => d.recommendation === 'EXPAND').length;

  const handleAction = async () => {
    if (!confirmAction) return;
    try {
      await applyAction(confirmAction.disk.diskName, confirmAction.disk.zone,
        confirmAction.actionType, confirmAction.targetSizeGb);
      setActionMsg({ type: 'success', text: `${confirmAction.actionType} applied to ${confirmAction.disk.diskName}` });
    } catch (e: any) {
      setActionMsg({ type: 'error', text: e?.message ?? 'Action failed' });
    } finally {
      setConfirmAction(null);
      setTimeout(() => setActionMsg(null), 4000);
    }
  };

  if (!selectedAccountId) {
    return (
      <div className="flex items-center justify-center h-64 text-gray-500 dark:text-gray-400">
        Select a GCP account to view disk rightsizing recommendations.
      </div>
    );
  }

  return (
    <div className="p-6 space-y-6 max-w-screen-2xl mx-auto">

      {/* Action message */}
      {actionMsg && (
        <div className={`rounded-lg px-4 py-3 text-sm font-medium ${
          actionMsg.type === 'success'
            ? 'bg-green-50 text-green-700 border border-green-200 dark:bg-green-900/20 dark:text-green-400'
            : 'bg-red-50 text-red-700 border border-red-200 dark:bg-red-900/20 dark:text-red-400'
        }`}>
          {actionMsg.text}
        </div>
      )}

      {/* KPI cards */}
      <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
        {[
          { label: 'Potential Monthly Savings', value: `₹${totalWasted.toLocaleString('en-IN', { maximumFractionDigits: 0 })}`, color: 'text-green-600 dark:text-green-400' },
          { label: 'Unattached Disks', value: String(unattachedCnt), color: 'text-red-600 dark:text-red-400' },
          { label: 'Oversized (Shrink)', value: String(shrinkCnt), color: 'text-amber-600 dark:text-amber-400' },
          { label: 'Undersized (Expand)', value: String(expandCnt), color: 'text-blue-600 dark:text-blue-400' },
        ].map(card => (
          <div key={card.label} className="bg-white dark:bg-gray-800 rounded-xl border border-gray-100 dark:border-gray-700 p-4 shadow-sm">
            <p className="text-xs text-gray-500 dark:text-gray-400 font-medium uppercase tracking-wide">{card.label}</p>
            <p className={`text-2xl font-bold mt-1 ${card.color}`}>{card.value}</p>
          </div>
        ))}
      </div>

      {/* Agent install guide — shown when triggered from topbar or when disks need agent */}
      {(showInstallGuide || disks.some(d => d.attached && d.usedPercent === undefined && !d.agentDeployed)) && (
        <GcpAgentSetupGuide
          count={disks.filter(d => d.attached && d.usedPercent === undefined && !d.agentDeployed).length}
          gcpProjectId={selectedAccountId}
          defaultExpanded={showInstallGuide}
        />
      )}

      {/* Tabs */}
      <div className="border-b border-gray-200 dark:border-gray-700">
        <nav className="flex gap-6">
          {(['overview', 'history'] as const).map(tab => (
            <button
              key={tab}
              onClick={() => setActiveTab(tab)}
              className={`pb-3 text-sm font-medium border-b-2 -mb-px capitalize ${
                activeTab === tab
                  ? 'border-blue-600 text-blue-600 dark:text-blue-400'
                  : 'border-transparent text-gray-500 dark:text-gray-400 hover:text-gray-700 dark:hover:text-gray-300'
              }`}
            >
              {tab === 'overview' ? 'Disks' : 'Action History'}
            </button>
          ))}
        </nav>
      </div>

      {activeTab === 'overview' && (
        <>
          {/* Filters */}
          <div className="flex flex-wrap gap-3">
            <input
              type="text" placeholder="Search disk or instance…"
              value={filterSearch} onChange={e => { setFilterSearch(e.target.value); setPage(1); }}
              className="px-3 py-2 text-sm border border-gray-200 dark:border-gray-600 rounded-lg bg-white dark:bg-gray-800 text-gray-700 dark:text-gray-300 w-56"
            />
            <select value={filterZone} onChange={e => { setFilterZone(e.target.value); setPage(1); }}
              className="px-3 py-2 text-sm border border-gray-200 dark:border-gray-600 rounded-lg bg-white dark:bg-gray-800 text-gray-700 dark:text-gray-300">
              <option value="">All zones</option>
              {uniqueZones.map(z => <option key={z} value={z}>{z}</option>)}
            </select>
            <select value={filterType} onChange={e => { setFilterType(e.target.value); setPage(1); }}
              className="px-3 py-2 text-sm border border-gray-200 dark:border-gray-600 rounded-lg bg-white dark:bg-gray-800 text-gray-700 dark:text-gray-300">
              <option value="">All types</option>
              {uniqueTypes.map(t => <option key={t} value={t}>{t}</option>)}
            </select>
            <select value={filterRecommendation} onChange={e => { setFilterRecommendation(e.target.value); setPage(1); }}
              className="px-3 py-2 text-sm border border-gray-200 dark:border-gray-600 rounded-lg bg-white dark:bg-gray-800 text-gray-700 dark:text-gray-300">
              <option value="">All recommendations</option>
              <option value="DELETE_UNATTACHED">Delete (unattached)</option>
              <option value="SHRINK">Shrink</option>
              <option value="EXPAND">Expand</option>
              <option value="NONE">No action</option>
            </select>
          </div>

          {/* Table */}
          {isLoading ? (
            <div className="flex items-center justify-center h-48">
              <svg className="animate-spin w-8 h-8 text-blue-500" fill="none" viewBox="0 0 24 24">
                <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4"/>
                <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z"/>
              </svg>
            </div>
          ) : error ? (
            <div className="rounded-lg bg-red-50 dark:bg-red-900/20 border border-red-200 dark:border-red-700 p-4 text-sm text-red-700 dark:text-red-400">{error}</div>
          ) : (
            <div className="bg-white dark:bg-gray-800 rounded-xl border border-gray-100 dark:border-gray-700 shadow-sm overflow-hidden">
              <div className="overflow-x-auto">
                <table className="w-full text-sm">
                  <thead className="bg-gray-50 dark:bg-gray-700/50">
                    <tr>
                      {['Disk Name', 'Zone', 'Type', 'Size', 'Usage', 'Monthly Cost', 'Recommendation', 'Actions'].map(h => (
                        <th key={h} className="px-4 py-3 text-left text-xs font-semibold text-gray-500 dark:text-gray-400 uppercase tracking-wide">{h}</th>
                      ))}
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-gray-100 dark:divide-gray-700">
                    {paginated.length === 0 ? (
                      <tr>
                        <td colSpan={8} className="px-4 py-12 text-center text-gray-400 dark:text-gray-500">
                          No disks found
                        </td>
                      </tr>
                    ) : paginated.map(disk => (
                      <tr key={`${disk.diskName}-${disk.zone}`} className="hover:bg-gray-50 dark:hover:bg-gray-700/30 transition-colors">
                        <td className="px-4 py-3">
                          <div className="font-medium text-gray-900 dark:text-white">{disk.diskName}</div>
                          {disk.instanceName && (
                            <div className="text-xs text-gray-400 dark:text-gray-500 mt-0.5">
                              VM: {disk.instanceName}
                            </div>
                          )}
                        </td>
                        <td className="px-4 py-3 text-gray-600 dark:text-gray-300">{disk.zone}</td>
                        <td className="px-4 py-3 text-gray-600 dark:text-gray-300">{disk.diskType}</td>
                        <td className="px-4 py-3 text-right">
                          <div className="text-sm font-semibold text-gray-800 dark:text-gray-200">{disk.sizeGb} GB</div>
                          {disk.usedPercent !== undefined ? (
                            <div className="mt-1.5">
                              <div className="w-24 h-1.5 bg-gray-200 dark:bg-gray-600 rounded-full overflow-hidden ml-auto">
                                <div
                                  className={`h-full rounded-full ${disk.usedPercent > 85 ? 'bg-red-500' : disk.usedPercent > 65 ? 'bg-amber-500' : 'bg-green-500'}`}
                                  style={{ width: `${disk.usedPercent}%` }}
                                />
                              </div>
                              <div className="text-xs text-gray-400 dark:text-gray-500 mt-0.5 text-right">{disk.usedPercent.toFixed(0)}%</div>
                            </div>
                          ) : (
                            <div className="text-xs text-gray-400 dark:text-gray-500 mt-0.5">
                              {disk.agentDeployed ? 'Refreshing…' : '—'}
                            </div>
                          )}
                        </td>
                        <td className="px-4 py-3 text-right font-medium text-gray-800 dark:text-gray-200">
                          ₹{disk.monthlyCost.toLocaleString('en-IN', { maximumFractionDigits: 0 })}
                        </td>
                        <td className="px-4 py-3 text-right font-semibold text-green-600 dark:text-green-400">
                          {disk.estimatedSavings > 0
                            ? `₹${disk.estimatedSavings.toLocaleString('en-IN', { maximumFractionDigits: 0 })}/mo`
                            : '—'}
                        </td>
                        <td className="px-4 py-3">
                          <div className="space-y-1">
                            {recommendationBadge(disk.recommendation)}
                            {disk.recommendationReason && (
                              <div className="text-xs text-gray-400 dark:text-gray-500 leading-snug max-w-xs">
                                {disk.recommendationReason}
                              </div>
                            )}
                            {disk.recommendation === 'NONE' && disk.usedPercent === undefined && (
                              <div className={`text-xs leading-snug ${disk.agentDeployed ? 'text-blue-600 dark:text-blue-400' : 'text-amber-600 dark:text-amber-400'}`}>
                                {disk.agentDeployed
                                  ? 'Agent deployed — data refreshing shortly'
                                  : disk.attached ? 'Install disk agent for SHRINK/EXPAND analysis' : ''}
                              </div>
                            )}
                          </div>
                        </td>
                        <td className="px-4 py-3">
                          <div className="flex gap-2">
                            {disk.recommendation === 'DELETE_UNATTACHED' && (
                              <button
                                onClick={() => setConfirmAction({ disk, actionType: 'DELETE_UNATTACHED' })}
                                className="px-3 py-1.5 text-xs font-medium bg-red-600 text-white rounded-lg hover:bg-red-700 transition-colors"
                              >
                                Delete
                              </button>
                            )}
                            {(disk.recommendation === 'SHRINK' || disk.recommendation === 'EXPAND') && (
                              <button
                                onClick={() => setConfirmAction({ disk, actionType: disk.recommendation, targetSizeGb: disk.recommendedSizeGb })}
                                className="px-3 py-1.5 text-xs font-medium bg-blue-600 text-white rounded-lg hover:bg-blue-700 transition-colors"
                              >
                                {disk.recommendation === 'SHRINK' ? `Shrink to ${disk.recommendedSizeGb} GB` : `Expand to ${disk.recommendedSizeGb} GB`}
                              </button>
                            )}
                          </div>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              {totalPages > 1 && (
                <div className="px-4 py-3 border-t border-gray-100 dark:border-gray-700 flex items-center justify-between">
                  <span className="text-xs text-gray-500 dark:text-gray-400">
                    Showing {(page - 1) * PAGE_SIZE + 1}–{Math.min(page * PAGE_SIZE, filteredDisks.length)} of {filteredDisks.length}
                  </span>
                  <div className="flex gap-2">
                    <button onClick={() => setPage(p => Math.max(1, p - 1))} disabled={page === 1}
                      className="px-3 py-1 text-xs border border-gray-200 dark:border-gray-600 rounded disabled:opacity-40 hover:bg-gray-50 dark:hover:bg-gray-700">
                      Previous
                    </button>
                    <button onClick={() => setPage(p => Math.min(totalPages, p + 1))} disabled={page === totalPages}
                      className="px-3 py-1 text-xs border border-gray-200 dark:border-gray-600 rounded disabled:opacity-40 hover:bg-gray-50 dark:hover:bg-gray-700">
                      Next
                    </button>
                  </div>
                </div>
              )}
            </div>
          )}
        </>
      )}

      {activeTab === 'history' && (
        <div className="bg-white dark:bg-gray-800 rounded-xl border border-gray-100 dark:border-gray-700 shadow-sm overflow-hidden">
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead className="bg-gray-50 dark:bg-gray-700/50">
                <tr>
                  {['Disk', 'Zone', 'Action', 'Size Change', 'Savings', 'Status', 'Date'].map(h => (
                    <th key={h} className="px-4 py-3 text-left text-xs font-semibold text-gray-500 dark:text-gray-400 uppercase tracking-wide">{h}</th>
                  ))}
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-100 dark:divide-gray-700">
                {actionHistory.length === 0 ? (
                  <tr>
                    <td colSpan={7} className="px-4 py-12 text-center text-gray-400 dark:text-gray-500">No actions yet</td>
                  </tr>
                ) : actionHistory.map(a => (
                  <tr key={a.id} className="hover:bg-gray-50 dark:hover:bg-gray-700/30">
                    <td className="px-4 py-3 font-medium text-gray-900 dark:text-white">{a.volumeId}</td>
                    <td className="px-4 py-3 text-gray-600 dark:text-gray-300">{a.region}</td>
                    <td className="px-4 py-3">{recommendationBadge(a.actionType)}</td>
                    <td className="px-4 py-3 text-gray-600 dark:text-gray-300">
                      {a.oldSizeGb && a.newSizeGb ? `${a.oldSizeGb} GB → ${a.newSizeGb} GB` : '—'}
                    </td>
                    <td className="px-4 py-3 font-medium text-green-600 dark:text-green-400">
                      {a.estimatedSavings ? `₹${a.estimatedSavings.toLocaleString('en-IN', { maximumFractionDigits: 0 })}/mo` : '—'}
                    </td>
                    <td className="px-4 py-3">
                      <span className={`px-2 py-0.5 rounded-full text-xs font-semibold ${
                        a.status === 'SUCCESS'     ? 'bg-green-100 text-green-700 dark:bg-green-900/30 dark:text-green-400' :
                        a.status === 'FAILED'      ? 'bg-red-100 text-red-700 dark:bg-red-900/30 dark:text-red-400' :
                        a.status === 'IN_PROGRESS' ? 'bg-blue-100 text-blue-700 dark:bg-blue-900/30 dark:text-blue-400' :
                                                     'bg-gray-100 text-gray-500 dark:bg-gray-700 dark:text-gray-400'
                      }`}>{a.status}</span>
                    </td>
                    <td className="px-4 py-3 text-gray-500 dark:text-gray-400">
                      {new Date(a.initiatedAt).toLocaleDateString()}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {/* Confirm action modal */}
      {confirmAction && (
        <div className="fixed inset-0 bg-black/50 flex items-center justify-center z-50">
          <div className="bg-white dark:bg-gray-800 rounded-2xl p-6 w-full max-w-md shadow-2xl">
            <h3 className="text-lg font-bold text-gray-900 dark:text-white mb-2">
              Confirm {confirmAction.actionType.replace('_', ' ')}
            </h3>
            <p className="text-sm text-gray-600 dark:text-gray-400 mb-4">
              {confirmAction.actionType === 'DELETE_UNATTACHED'
                ? `Are you sure you want to permanently delete disk "${confirmAction.disk.diskName}"? This cannot be undone.`
                : `Resize disk "${confirmAction.disk.diskName}" from ${confirmAction.disk.sizeGb} GB to ${confirmAction.targetSizeGb} GB?`}
            </p>
            <div className="flex gap-3 justify-end">
              <button onClick={() => setConfirmAction(null)}
                className="px-4 py-2 text-sm text-gray-600 dark:text-gray-400 border border-gray-200 dark:border-gray-600 rounded-lg hover:bg-gray-50 dark:hover:bg-gray-700">
                Cancel
              </button>
              <button onClick={handleAction}
                className={`px-4 py-2 text-sm font-medium text-white rounded-lg ${
                  confirmAction.actionType === 'DELETE_UNATTACHED'
                    ? 'bg-red-600 hover:bg-red-700'
                    : 'bg-blue-600 hover:bg-blue-700'
                }`}>
                Confirm
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};

// ── GCP Disk Agent Setup Guide ──────────────────────────────────────────────

const GcpAgentSetupGuide: React.FC<{ count: number; gcpProjectId: string | null; defaultExpanded?: boolean }> = ({ count, gcpProjectId, defaultExpanded = false }) => {
  const [expanded, setExpanded] = React.useState(defaultExpanded);
  const [copied, setCopied] = React.useState(false);

  const detectedBase = window.location.origin.replace(':5173', ':8080');
  const isLocalhost = detectedBase.includes('localhost') || detectedBase.includes('127.0.0.1');
  const [serverUrl, setServerUrl] = React.useState(detectedBase);

  const projectArg = gcpProjectId ?? '<gcp-project-id>';
  const isNgrok = serverUrl.includes('ngrok');
  const curlHeaders = isNgrok ? ' -H "ngrok-skip-browser-warning: true"' : '';
  const AGENT_CMD = `curl -fsSL${curlHeaders} ${serverUrl}/agentinstall/gcp_disk_agent_install.sh | sudo bash -s -- -b ${serverUrl} -t ${projectArg}`;

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
            Install the XamOps disk agent on each GCP VM to enable SHRINK/EXPAND recommendations — no Cloud Monitoring Agent needed.
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

          <div className="rounded-xl border border-indigo-100 dark:border-indigo-800 bg-indigo-50 dark:bg-indigo-900/20 p-4 space-y-2">
            <p className="text-xs font-semibold text-indigo-800 dark:text-indigo-300 flex items-center gap-1.5">
              <i className="fas fa-lightbulb" />Why we need the agent
            </p>
            <p className="text-xs text-indigo-700 dark:text-indigo-400 leading-relaxed">
              GCP does not expose actual disk usage through its APIs — only disk size and type are available via the Compute API. Without knowing how much space is <strong>actually used</strong> inside the OS, we can't tell whether a disk is over-provisioned (SHRINK) or nearly full (EXPAND).
            </p>
            <p className="text-xs text-indigo-700 dark:text-indigo-400 leading-relaxed">
              The agent runs <code className="px-1 rounded bg-indigo-100 dark:bg-indigo-800 font-mono">df</code> on the VM filesystem and reports real usage — no Cloud Monitoring Agent or extra IAM permissions required.
            </p>
          </div>

          <div className="rounded-xl border border-gray-200 dark:border-gray-700 overflow-hidden">
            <div className="px-4 py-2.5 bg-gray-50 dark:bg-gray-700/50 flex items-center gap-2">
              <i className="fas fa-shield-alt text-green-500 text-sm" />
              <span className="text-xs font-semibold text-gray-700 dark:text-gray-300">What data the agent collects</span>
            </div>
            <div className="px-4 py-3 space-y-2">
              {[
                { icon: 'fa-hdd', label: 'Mount point', value: 'e.g. /, /data' },
                { icon: 'fa-weight-hanging', label: 'Disk total / used / free (GB)', value: 'from df output' },
                { icon: 'fa-percentage', label: 'Usage percent', value: 'e.g. 72%' },
                { icon: 'fa-server', label: 'GCE instance name', value: 'from GCE metadata' },
                { icon: 'fa-tag', label: 'GCP project ID', value: 'from agent config' },
              ].map(({ icon, label, value }) => (
                <div key={label} className="flex items-start gap-2.5">
                  <i className={`fas ${icon} text-gray-400 text-xs mt-0.5 w-4 flex-shrink-0`} />
                  <div className="flex-1 min-w-0">
                    <span className="text-xs font-medium text-gray-700 dark:text-gray-300">{label}</span>
                    <span className="text-xs text-gray-400 dark:text-gray-500 ml-1.5">{value}</span>
                  </div>
                  <i className="fas fa-check-circle text-green-500 text-xs flex-shrink-0 mt-0.5" />
                </div>
              ))}
              <div className="pt-2 border-t border-gray-100 dark:border-gray-700 space-y-1.5">
                {[
                  'No file contents, names, or paths',
                  'No process list, network traffic, or credentials',
                  'No persistent access — agent only makes outbound HTTPS calls to XamOps',
                ].map(text => (
                  <div key={text} className="flex items-start gap-2">
                    <i className="fas fa-times-circle text-red-400 text-xs mt-0.5 flex-shrink-0" />
                    <span className="text-xs text-gray-500 dark:text-gray-400">{text}</span>
                  </div>
                ))}
              </div>
            </div>
          </div>

          {isLocalhost && (
            <div className="rounded-lg bg-yellow-50 dark:bg-yellow-900/20 border border-yellow-200 dark:border-yellow-800 px-3 py-2.5 space-y-2">
              <p className="text-xs font-medium text-yellow-800 dark:text-yellow-300 flex items-center gap-1.5">
                <i className="fas fa-exclamation-circle" />
                GCE instances can't reach <code className="font-mono">localhost</code> — enter the public IP or hostname of this XamOps server:
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

          <div className="flex gap-3">
            <div className="w-6 h-6 rounded-full bg-indigo-100 dark:bg-indigo-900/40 text-indigo-700 dark:text-indigo-300 text-xs font-bold flex items-center justify-center flex-shrink-0 mt-0.5">1</div>
            <div className="flex-1">
              <p className="text-sm font-medium text-gray-800 dark:text-gray-200 mb-2">
                SSH into each GCE instance and run
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

export default GcpDiskRightsizingPage;
