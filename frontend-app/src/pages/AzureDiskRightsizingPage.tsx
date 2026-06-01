import React, { useState, useEffect } from 'react';
import { Icon } from '@iconify/react';
import { useAccounts } from '../contexts/AccountContext';
import { useAzureDiskRightsizingData, AzureDisk } from '../hooks/useAzureDiskRightsizingData';
import { useTopbarActions } from '../contexts/TopbarContext';
import { useTabState } from '../hooks/useTabState';

const PAGE_SIZE = 20;

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
    <svg className="absolute top-16 right-2 w-24 h-24" viewBox="0 0 200 120" fill="currentColor" style={{ color: '#6b7280' }}>
      <path d="M120 60c0-10-8-18-18-18-2 0-4 0-5 1-4-8-13-14-23-14s-19 6-23 14c-1-1-3-1-5-1-10 0-18 8-18 18s8 18 18 18h74c10 0 18-8 18-18z"/>
    </svg>
  </div>
);

const diskTypeLabel = (diskType: string): string => {
  switch (diskType) {
    case 'Premium_LRS':     return 'Premium SSD';
    case 'Standard_LRS':    return 'Standard HDD';
    case 'StandardSSD_LRS': return 'Standard SSD';
    case 'UltraSSD_LRS':   return 'Ultra SSD';
    default:                return diskType;
  }
};

const REC_BADGE: Record<string, { label: string; cls: string; icon: string }> = {
  DELETE_UNATTACHED: { label: 'Delete (unattached)', cls: 'bg-rose-50 text-rose-600 border-rose-100',   icon: 'mdi:delete-outline' },
  SHRINK:            { label: 'Shrink',               cls: 'bg-amber-50 text-amber-600 border-amber-100', icon: 'mdi:arrow-collapse-down' },
  EXPAND:            { label: 'Expand',               cls: 'bg-blue-50 text-blue-600 border-blue-100',   icon: 'mdi:arrow-expand-up' },
  NONE:              { label: 'No action',            cls: 'bg-[rgba(0,0,0,0.04)] text-[rgba(0,0,0,0.45)] border-[rgba(0,0,0,0.08)]', icon: 'mdi:check-circle-outline' },
};

const RecommendationBadge: React.FC<{ rec: string }> = ({ rec }) => {
  const meta = REC_BADGE[rec] ?? REC_BADGE.NONE;
  return (
    <span className={`inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-[11px] font-semibold border ${meta.cls}`}>
      <Icon icon={meta.icon} width={11} />
      {meta.label}
    </span>
  );
};

// ── Azure Disk Agent Setup Guide ────────────────────────────────────────────

const AzureAgentSetupGuide: React.FC<{ count: number; accountId: string | null; defaultExpanded?: boolean }> = ({ count, accountId, defaultExpanded = false }) => {
  const [expanded, setExpanded] = React.useState(defaultExpanded);
  const [copied, setCopied]     = React.useState(false);

  const AGENT_CMD =
`export XAMOPS_API_URL="https://your-xamops-api.com"
export XAMOPS_ACCOUNT_ID="${accountId ?? 'your-account-id'}"
curl -sSL \${XAMOPS_API_URL}/scripts/azure_disk_agent_install.sh | bash`;

  const copy = () => {
    navigator.clipboard.writeText(AGENT_CMD).then(() => {
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    });
  };

  return (
    <div className="rounded-xl border border-amber-200 bg-amber-50 overflow-hidden">
      <div className="px-4 py-3 flex items-start gap-3">
        <Icon icon="mdi:alert-outline" width={16} color="#D97706" className="mt-0.5 flex-shrink-0" />
        <div className="flex-1 min-w-0">
          <p className="text-[13px] font-semibold text-amber-800">
            Disk usage unavailable for {count} VM{count > 1 ? 's' : ''}
          </p>
          <p className="text-[12px] text-amber-700 mt-0.5">
            Install the XamOps disk agent on each Azure VM to enable SHRINK/EXPAND recommendations.
          </p>
        </div>
        <button
          onClick={() => setExpanded(e => !e)}
          className="flex-shrink-0 flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-amber-600 hover:bg-amber-700 text-white text-[12px] font-semibold transition-colors"
        >
          <Icon icon={expanded ? 'mdi:chevron-up' : 'mdi:robot-outline'} width={13} />
          {expanded ? 'Hide' : 'Setup Agent'}
        </button>
      </div>

      {expanded && (
        <div className="border-t border-amber-200 px-4 py-4 space-y-4 bg-white">
          <div className="rounded-xl border border-[rgba(0,0,0,0.08)] bg-[rgba(0,0,0,0.02)] p-4 space-y-2">
            <p className="text-[12px] font-semibold text-[rgba(0,0,0,0.88)] flex items-center gap-1.5">
              <Icon icon="mdi:console" width={13} color="#6366F1" />
              Install XamOps Disk Agent on Azure VMs
            </p>
            <p className="text-[12px] text-[rgba(0,0,0,0.55)] leading-relaxed">
              Azure does not expose actual disk usage through its APIs. The XamOps disk agent runs on each VM and reports real filesystem usage — no Azure Monitor or extra IAM permissions required.
            </p>
          </div>

          <div>
            <p className="text-[13px] font-medium text-[rgba(0,0,0,0.88)] mb-2 flex items-center gap-2">
              <span className="w-5 h-5 rounded-full bg-indigo-100 text-indigo-600 text-[11px] font-bold flex items-center justify-center flex-shrink-0">1</span>
              SSH into each Azure VM and run
            </p>
            <div className="relative rounded-xl bg-[#181715] p-4 font-mono text-[12px] text-green-300 overflow-x-auto">
              <pre className="whitespace-pre-wrap break-all leading-relaxed">{AGENT_CMD}</pre>
              <button
                onClick={copy}
                className="absolute top-2 right-2 flex items-center gap-1 px-2 py-1 rounded bg-gray-700 hover:bg-gray-600 text-gray-300 text-[11px] transition-colors"
              >
                <Icon icon={copied ? 'mdi:check' : 'mdi:content-copy'} width={11} />
                {copied ? 'Copied' : 'Copy'}
              </button>
            </div>
          </div>

          <div className="rounded-lg bg-emerald-50 border border-emerald-100 px-3 py-2 text-[12px] text-emerald-700 flex items-start gap-2">
            <Icon icon="mdi:check-circle-outline" width={13} className="mt-0.5 flex-shrink-0" />
            <span>Disk usage appears within 1–2 minutes. Click <strong>Refresh</strong> to see SHRINK/EXPAND recommendations.</span>
          </div>
        </div>
      )}
    </div>
  );
};

// ── Main Page ────────────────────────────────────────────────────────────────

const AzureDiskRightsizingPage: React.FC = () => {
  const { selectedAccountIds } = useAccounts();
  const selectedAccountId = selectedAccountIds[0] || null;
  const { setTitle, setSubtitle, setActions, clearActions } = useTopbarActions();

  const [filterSearch, setFilterSearch]                 = useState('');
  const [filterResourceGroup, setFilterResourceGroup]   = useState('');
  const [filterType, setFilterType]                     = useState('');
  const [filterRecommendation, setFilterRecommendation] = useState('');
  const [page, setPage]                                 = useState(1);
  const [activeTab, setActiveTab] = useTabState<'overview' | 'history'>('overview');
  const [actionMsg, setActionMsg]                       = useState<{ type: 'success' | 'error'; text: string } | null>(null);
  const [confirmAction, setConfirmAction]               = useState<{ disk: AzureDisk; actionType: string; targetSizeGb?: number } | null>(null);
  const [showInstallGuide, setShowInstallGuide]         = useState(false);

  const { disks, actionHistory, isLoading, isRefreshing, error, applyAction, refreshAll } =
    useAzureDiskRightsizingData(selectedAccountId);

  useEffect(() => {
    setTitle('Azure Disk Rightsizing');
    setSubtitle('Optimize Azure Managed Disk sizes using real usage data from the XamOps disk agent');
    return () => { setTitle(null); setSubtitle(null); };
  }, []);

  useEffect(() => {
    setActions(
      <div className="flex items-center gap-2">
        <span className="w-1.5 h-1.5 rounded-full bg-blue-500" />
        <span className="text-[11px] font-medium uppercase tracking-wide text-[rgba(0,0,0,0.4)]">Azure Disk</span>
        <span className="text-[rgba(0,0,0,0.15)] text-[12px] select-none">|</span>
        <button
          onClick={() => setShowInstallGuide(v => !v)}
          className="flex items-center gap-1 text-[11px] font-semibold text-[rgba(0,0,0,0.5)] hover:text-[rgba(0,0,0,0.88)] transition-colors"
        >
          <Icon icon="mdi:console" width={11} />
          Install Agent
        </button>
        <span className="text-[rgba(0,0,0,0.15)] text-[12px] select-none">|</span>
        {selectedAccountId && (
          <button
            onClick={refreshAll}
            disabled={isRefreshing}
            className="flex items-center gap-1.5 text-[11px] font-semibold text-[rgba(0,0,0,0.5)] hover:text-[rgba(0,0,0,0.88)] transition-colors disabled:opacity-40"
          >
            <svg className={`w-3 h-3 ${isRefreshing ? 'animate-spin' : ''}`} fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2.5}
                d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15" />
            </svg>
            {isRefreshing ? 'Refreshing…' : 'Refresh'}
          </button>
        )}
      </div>
    );
    return () => clearActions();
  }, [isRefreshing, refreshAll, selectedAccountId, showInstallGuide]);

  const uniqueResourceGroups = Array.from(new Set(disks.map(d => d.resourceGroup))).sort();
  const uniqueTypes          = Array.from(new Set(disks.map(d => d.diskType))).sort();

  const filteredDisks = disks.filter(d => {
    if (filterSearch && !d.diskName.toLowerCase().includes(filterSearch.toLowerCase()) &&
        !(d.instanceName ?? '').toLowerCase().includes(filterSearch.toLowerCase())) return false;
    if (filterResourceGroup && d.resourceGroup !== filterResourceGroup) return false;
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
      await applyAction(
        confirmAction.disk.diskName,
        confirmAction.disk.resourceGroup,
        confirmAction.actionType,
        confirmAction.targetSizeGb
      );
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
      <main className="flex-1 px-6 pt-4 pb-6 space-y-5">
        <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-10 text-center">
          <div className="w-12 h-12 bg-[rgba(0,0,0,0.04)] rounded-full flex items-center justify-center mx-auto mb-3">
            <Icon icon="mdi:harddisk" width={22} color="rgba(0,0,0,0.3)" />
          </div>
          <p className="text-[14px] font-semibold text-[rgba(0,0,0,0.88)] mb-1">No Account Selected</p>
          <p className="text-[13px] text-[rgba(0,0,0,0.45)]">Select an Azure account to view disk rightsizing recommendations.</p>
        </div>
      </main>
    );
  }

  return (
    <main className="flex-1 px-6 pt-4 pb-6 space-y-5">

      {/* Action message */}
      {actionMsg && (
        <div className={`rounded-lg px-4 py-3 text-[13px] font-medium border ${
          actionMsg.type === 'success'
            ? 'bg-emerald-50 text-emerald-700 border-emerald-100'
            : 'bg-rose-50 text-rose-700 border-rose-100'
        }`}>
          {actionMsg.text}
        </div>
      )}

      {/* KPI cards */}
      <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
        {[
          { label: 'Potential Savings',   value: `₹${totalWasted.toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`, icon: 'mdi:piggy-bank',          iconColor: '#16A34A', iconBg: '#16A34A14' },
          { label: 'Unattached Disks',    value: String(unattachedCnt), icon: 'mdi:harddisk-remove',     iconColor: '#DC2626', iconBg: '#DC262614' },
          { label: 'Oversized (Shrink)',  value: String(shrinkCnt),     icon: 'mdi:arrow-collapse-down', iconColor: '#D97706', iconBg: '#D9770614' },
          { label: 'Undersized (Expand)', value: String(expandCnt),     icon: 'mdi:arrow-expand-up',     iconColor: '#3B82F6', iconBg: '#3B82F614' },
        ].map(card => (
          <div key={card.label} className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-5 relative overflow-hidden">
            {CLOUD_SVG}
            <div className="relative z-10">
              <div className="flex items-start justify-between mb-2">
                <p className="text-[12px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.88)]">{card.label}</p>
                <div className="w-8 h-8 rounded-lg flex items-center justify-center flex-shrink-0" style={{ backgroundColor: card.iconBg }}>
                  <Icon icon={card.icon} width={18} color={card.iconColor} />
                </div>
              </div>
              <p style={NUM_STYLE}>{card.value}</p>
            </div>
          </div>
        ))}
      </div>

      {/* Agent install guide */}
      {(showInstallGuide || disks.some(d => d.attached && d.usedPercent === undefined && !d.agentDeployed)) && (
        <AzureAgentSetupGuide
          count={disks.filter(d => d.attached && d.usedPercent === undefined && !d.agentDeployed).length}
          accountId={selectedAccountId}
          defaultExpanded={showInstallGuide}
        />
      )}

      {/* Tabs */}
      <div className="inline-flex bg-[rgba(0,0,0,0.03)] border border-[rgba(0,0,0,0.07)] rounded-xl p-1 gap-1">
        {([
          { key: 'overview', label: 'Disks',          icon: 'mdi:harddisk' },
          { key: 'history',  label: 'Action History',  icon: 'mdi:history' },
        ] as const).map(tab => (
          <button
            key={tab.key}
            onClick={() => setActiveTab(tab.key)}
            className={`flex items-center gap-1.5 px-4 py-2 rounded-lg text-[12px] font-semibold transition-all ${
              activeTab === tab.key
                ? 'bg-white shadow-sm border border-[rgba(0,0,0,0.08)] text-[rgba(0,0,0,0.88)]'
                : 'text-[rgba(0,0,0,0.45)] hover:text-[rgba(0,0,0,0.65)]'
            }`}
          >
            <Icon icon={tab.icon} width={13} />
            {tab.label}
          </button>
        ))}
      </div>

      {activeTab === 'overview' && (
        <>
          {/* Filters */}
          <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm px-4 py-3 flex flex-wrap gap-3 items-center">
            <div className="relative">
              <Icon icon="mdi:magnify" width={13} className="absolute left-2.5 top-1/2 -translate-y-1/2 text-[rgba(0,0,0,0.3)]" />
              <input
                type="text" placeholder="Search disk or VM…"
                value={filterSearch} onChange={e => { setFilterSearch(e.target.value); setPage(1); }}
                className="pl-7 pr-3 py-1.5 text-[12px] border border-[rgba(0,0,0,0.1)] rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-300 bg-white w-48 text-[rgba(0,0,0,0.88)] placeholder:text-[rgba(0,0,0,0.3)]"
              />
            </div>
            <select value={filterResourceGroup} onChange={e => { setFilterResourceGroup(e.target.value); setPage(1); }}
              className="px-3 py-1.5 text-[12px] border border-[rgba(0,0,0,0.1)] rounded-lg bg-white text-[rgba(0,0,0,0.88)] focus:outline-none focus:ring-2 focus:ring-blue-300">
              <option value="">All resource groups</option>
              {uniqueResourceGroups.map(rg => <option key={rg} value={rg}>{rg}</option>)}
            </select>
            <select value={filterType} onChange={e => { setFilterType(e.target.value); setPage(1); }}
              className="px-3 py-1.5 text-[12px] border border-[rgba(0,0,0,0.1)] rounded-lg bg-white text-[rgba(0,0,0,0.88)] focus:outline-none focus:ring-2 focus:ring-blue-300">
              <option value="">All types</option>
              {uniqueTypes.map(t => <option key={t} value={t}>{diskTypeLabel(t)}</option>)}
            </select>
            <select value={filterRecommendation} onChange={e => { setFilterRecommendation(e.target.value); setPage(1); }}
              className="px-3 py-1.5 text-[12px] border border-[rgba(0,0,0,0.1)] rounded-lg bg-white text-[rgba(0,0,0,0.88)] focus:outline-none focus:ring-2 focus:ring-blue-300">
              <option value="">All recommendations</option>
              <option value="DELETE_UNATTACHED">Delete (unattached)</option>
              <option value="SHRINK">Shrink</option>
              <option value="EXPAND">Expand</option>
              <option value="NONE">No action</option>
            </select>
            <span className="ml-auto text-[11px] text-[rgba(0,0,0,0.35)]">{filteredDisks.length} of {disks.length}</span>
          </div>

          {/* Table */}
          {isLoading ? (
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
          ) : error ? (
            <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-6">
              <div className="flex items-center gap-3 text-rose-600">
                <Icon icon="mdi:alert-circle-outline" width={20} />
                <p className="text-[13px] font-medium">{error}</p>
              </div>
            </div>
          ) : (
            <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm overflow-hidden">
              {/* Column headers */}
              <div className="grid grid-cols-12 px-5 py-2 bg-[rgba(0,0,0,0.02)] text-[11px] font-semibold uppercase tracking-wide text-[rgba(0,0,0,0.4)] border-b border-[rgba(0,0,0,0.06)]">
                <div className="col-span-3">Disk</div>
                <div className="col-span-2">Resource Group</div>
                <div className="col-span-2">Type / Size</div>
                <div className="col-span-1">Usage</div>
                <div className="col-span-1">Cost/mo</div>
                <div className="col-span-1">Savings</div>
                <div className="col-span-1">Recommendation</div>
                <div className="col-span-1">Actions</div>
              </div>

              {paginated.length === 0 ? (
                <div className="p-10 text-center">
                  <div className="w-12 h-12 bg-[rgba(0,0,0,0.04)] rounded-full flex items-center justify-center mx-auto mb-3">
                    <Icon icon="mdi:filter-outline" width={22} color="rgba(0,0,0,0.3)" />
                  </div>
                  <p className="text-[14px] font-semibold text-[rgba(0,0,0,0.88)] mb-1">No Disks Found</p>
                  <p className="text-[13px] text-[rgba(0,0,0,0.45)]">Try adjusting your filters or search term.</p>
                </div>
              ) : paginated.map(disk => (
                <div
                  key={`${disk.diskName}-${disk.resourceGroup}`}
                  className="grid grid-cols-12 items-center px-5 py-3.5 border-t border-[rgba(0,0,0,0.04)] hover:bg-[rgba(0,0,0,0.01)] transition-colors text-[13px]"
                >
                  {/* Disk */}
                  <div className="col-span-3 flex items-center gap-2.5 min-w-0">
                    <div className="w-7 h-7 rounded-lg flex items-center justify-center flex-shrink-0 border border-[rgba(0,0,0,0.08)]" style={{ backgroundColor: '#8B5CF614' }}>
                      <Icon icon="mdi:harddisk" width={14} color="#8B5CF6" />
                    </div>
                    <div className="min-w-0">
                      <p className="font-semibold text-[rgba(0,0,0,0.88)] truncate" title={disk.diskName}>{disk.diskName}</p>
                      {disk.instanceName && (
                        <p className="text-[11px] text-[rgba(0,0,0,0.35)] truncate">VM: {disk.instanceName}</p>
                      )}
                    </div>
                  </div>

                  {/* Resource Group */}
                  <div className="col-span-2 text-[rgba(0,0,0,0.55)] truncate" title={disk.resourceGroup}>{disk.resourceGroup}</div>

                  {/* Type / Size */}
                  <div className="col-span-2">
                    <p className="text-[rgba(0,0,0,0.88)] text-[12px]">{diskTypeLabel(disk.diskType)}</p>
                    <p className="text-[11px] text-[rgba(0,0,0,0.35)]">{disk.sizeGb} GB</p>
                  </div>

                  {/* Usage */}
                  <div className="col-span-1">
                    {disk.usedPercent !== undefined ? (
                      <div>
                        <div className="w-16 h-1.5 bg-[rgba(0,0,0,0.06)] rounded-full overflow-hidden mb-0.5">
                          <div
                            className={`h-full rounded-full ${disk.usedPercent > 85 ? 'bg-rose-500' : disk.usedPercent > 65 ? 'bg-amber-500' : 'bg-emerald-500'}`}
                            style={{ width: `${disk.usedPercent}%` }}
                          />
                        </div>
                        <p className="text-[11px] text-[rgba(0,0,0,0.45)]">{disk.usedPercent.toFixed(0)}%</p>
                      </div>
                    ) : (
                      <span className="text-[11px] text-[rgba(0,0,0,0.3)]">{disk.agentDeployed ? 'Refreshing…' : '—'}</span>
                    )}
                  </div>

                  {/* Monthly Cost */}
                  <div className="col-span-1">
                    <p className="font-semibold text-[rgba(0,0,0,0.88)]">₹{disk.monthlyCost.toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}</p>
                  </div>

                  {/* Savings */}
                  <div className="col-span-1">
                    {disk.estimatedSavings > 0 ? (
                      <p className="font-semibold text-emerald-600">₹{disk.estimatedSavings.toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}/mo</p>
                    ) : (
                      <span className="text-[rgba(0,0,0,0.3)]">—</span>
                    )}
                  </div>

                  {/* Recommendation */}
                  <div className="col-span-1 space-y-1">
                    <RecommendationBadge rec={disk.recommendation} />
                    {disk.recommendationReason && (
                      <p className="text-[11px] text-[rgba(0,0,0,0.4)] leading-snug">{disk.recommendationReason}</p>
                    )}
                    {disk.recommendation === 'NONE' && disk.usedPercent === undefined && disk.attached && !disk.agentDeployed && (
                      <p className="text-[11px] text-amber-600 leading-snug">Install disk agent for analysis</p>
                    )}
                    {disk.recommendation === 'NONE' && disk.usedPercent === undefined && disk.agentDeployed && (
                      <p className="text-[11px] text-blue-600 leading-snug">Agent deployed — data refreshing</p>
                    )}
                  </div>

                  {/* Actions */}
                  <div className="col-span-1 flex gap-1.5">
                    {disk.recommendation === 'DELETE_UNATTACHED' && (
                      <button
                        onClick={() => setConfirmAction({ disk, actionType: 'DELETE_UNATTACHED' })}
                        className="px-2.5 py-1 text-[11px] font-semibold rounded-lg border border-rose-200 text-rose-600 hover:bg-rose-50 transition-colors"
                      >
                        Delete
                      </button>
                    )}
                    {(disk.recommendation === 'SHRINK' || disk.recommendation === 'EXPAND') && (
                      <button
                        onClick={() => setConfirmAction({ disk, actionType: disk.recommendation, targetSizeGb: disk.recommendedSizeGb })}
                        className="px-2.5 py-1 text-[11px] font-semibold rounded-lg border border-indigo-200 text-indigo-600 hover:bg-indigo-50 transition-colors"
                      >
                        {disk.recommendation === 'SHRINK' ? `→ ${disk.recommendedSizeGb} GB` : `→ ${disk.recommendedSizeGb} GB`}
                      </button>
                    )}
                  </div>
                </div>
              ))}

              {totalPages > 1 && (
                <div className="px-5 py-3 border-t border-[rgba(0,0,0,0.06)] flex items-center justify-between bg-[rgba(0,0,0,0.01)]">
                  <span className="text-[11px] text-[rgba(0,0,0,0.35)]">
                    Showing {(page - 1) * PAGE_SIZE + 1}–{Math.min(page * PAGE_SIZE, filteredDisks.length)} of {filteredDisks.length}
                  </span>
                  <div className="flex gap-2">
                    <button onClick={() => setPage(p => Math.max(1, p - 1))} disabled={page === 1}
                      className="px-3 py-1 text-[11px] border border-[rgba(0,0,0,0.1)] rounded-lg disabled:opacity-40 hover:bg-[rgba(0,0,0,0.02)] text-[rgba(0,0,0,0.55)]">
                      Previous
                    </button>
                    <button onClick={() => setPage(p => Math.min(totalPages, p + 1))} disabled={page === totalPages}
                      className="px-3 py-1 text-[11px] border border-[rgba(0,0,0,0.1)] rounded-lg disabled:opacity-40 hover:bg-[rgba(0,0,0,0.02)] text-[rgba(0,0,0,0.55)]">
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
        <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm overflow-hidden">
          {/* Column headers */}
          <div className="grid grid-cols-12 px-5 py-2 bg-[rgba(0,0,0,0.02)] text-[11px] font-semibold uppercase tracking-wide text-[rgba(0,0,0,0.4)] border-b border-[rgba(0,0,0,0.06)]">
            <div className="col-span-3">Disk</div>
            <div className="col-span-2">Resource Group</div>
            <div className="col-span-2">Action</div>
            <div className="col-span-2">Size Change</div>
            <div className="col-span-1">Savings</div>
            <div className="col-span-1">Status</div>
            <div className="col-span-1">Date</div>
          </div>

          {actionHistory.length === 0 ? (
            <div className="p-10 text-center">
              <div className="w-12 h-12 bg-[rgba(0,0,0,0.04)] rounded-full flex items-center justify-center mx-auto mb-3">
                <Icon icon="mdi:history" width={22} color="rgba(0,0,0,0.3)" />
              </div>
              <p className="text-[14px] font-semibold text-[rgba(0,0,0,0.88)] mb-1">No Actions Yet</p>
              <p className="text-[13px] text-[rgba(0,0,0,0.45)]">Actions you apply will appear here.</p>
            </div>
          ) : actionHistory.map(a => (
            <div key={a.id} className="grid grid-cols-12 items-center px-5 py-3.5 border-t border-[rgba(0,0,0,0.04)] hover:bg-[rgba(0,0,0,0.01)] transition-colors text-[13px]">
              <div className="col-span-3 font-medium text-[rgba(0,0,0,0.88)] truncate">{a.volumeId}</div>
              <div className="col-span-2 text-[rgba(0,0,0,0.55)] truncate">{a.region}</div>
              <div className="col-span-2"><RecommendationBadge rec={a.actionType} /></div>
              <div className="col-span-2 text-[rgba(0,0,0,0.55)]">
                {a.oldSizeGb && a.newSizeGb ? `${a.oldSizeGb} GB → ${a.newSizeGb} GB` : '—'}
              </div>
              <div className="col-span-1 font-medium text-emerald-600">
                {a.estimatedSavings ? `₹${a.estimatedSavings.toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}/mo` : '—'}
              </div>
              <div className="col-span-1">
                <span className={`px-2 py-0.5 rounded-full text-[11px] font-semibold border ${
                  a.status === 'SUCCESS'     ? 'bg-emerald-50 text-emerald-600 border-emerald-100' :
                  a.status === 'FAILED'      ? 'bg-rose-50 text-rose-600 border-rose-100' :
                  a.status === 'IN_PROGRESS' ? 'bg-blue-50 text-blue-600 border-blue-100' :
                                               'bg-[rgba(0,0,0,0.04)] text-[rgba(0,0,0,0.45)] border-[rgba(0,0,0,0.08)]'
                }`}>{a.status}</span>
              </div>
              <div className="col-span-1 text-[11px] text-[rgba(0,0,0,0.35)]">
                {new Date(a.initiatedAt).toLocaleDateString()}
              </div>
            </div>
          ))}
        </div>
      )}

      {/* Confirm action modal */}
      {confirmAction && (
        <div className="fixed inset-0 bg-black/40 flex items-center justify-center z-50">
          <div className="bg-white rounded-2xl p-6 w-full max-w-md shadow-2xl border border-[rgba(0,0,0,0.08)]">
            <h3 className="text-[16px] font-bold text-[rgba(0,0,0,0.88)] mb-2">
              Confirm {confirmAction.actionType.replace('_', ' ')}
            </h3>
            <p className="text-[13px] text-[rgba(0,0,0,0.55)] mb-5">
              {confirmAction.actionType === 'DELETE_UNATTACHED'
                ? `Are you sure you want to permanently delete disk "${confirmAction.disk.diskName}"? This cannot be undone.`
                : `Resize disk "${confirmAction.disk.diskName}" from ${confirmAction.disk.sizeGb} GB to ${confirmAction.targetSizeGb} GB?`}
            </p>
            <div className="flex gap-3 justify-end">
              <button onClick={() => setConfirmAction(null)}
                className="px-4 py-2 text-[13px] text-[rgba(0,0,0,0.55)] border border-[rgba(0,0,0,0.1)] rounded-lg hover:bg-[rgba(0,0,0,0.02)] transition-colors">
                Cancel
              </button>
              <button onClick={handleAction}
                className={`px-4 py-2 text-[13px] font-semibold text-white rounded-lg transition-colors ${
                  confirmAction.actionType === 'DELETE_UNATTACHED'
                    ? 'bg-rose-600 hover:bg-rose-700'
                    : 'bg-indigo-600 hover:bg-indigo-700'
                }`}>
                Confirm
              </button>
            </div>
          </div>
        </div>
      )}
    </main>
  );
};

export default AzureDiskRightsizingPage;
