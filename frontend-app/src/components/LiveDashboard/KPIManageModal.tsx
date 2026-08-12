import React, { useState, useMemo } from 'react';
import ReactDOM from 'react-dom';
import { Icon } from '@iconify/react';

export interface KPICardDef {
  id: string;
  title: string;
  description: string;
  icon: string;
  iconBg: string;
  iconColor: string;
  category: 'Cost' | 'Optimization' | 'Alerts' | 'Resources' | 'AIOps' | 'DBOps' | 'Platform';
  displayType: 'currency' | 'count' | 'percent';
  unit?: string;
  recommended?: boolean;
  /** Route of the page with the card's full data ("View more" target). */
  link?: string;
}

export const ALL_KPI_CARDS: KPICardDef[] = [
  // ── Cost ──────────────────────────────────────────────────────────────
  {
    id: 'mtdSpend',
    title: 'Month-to-Date Spend',
    description: 'Total cloud spend from the 1st of the current month to today.',
    icon: 'mdi:currency-usd',
    iconBg: '#6b728014', iconColor: '#6b7280',
    category: 'Cost', displayType: 'currency', recommended: true, link: '/cost',
  },
  {
    id: 'forecast',
    title: 'Forecasted Spend',
    description: 'Projected end-of-month spend based on current usage trajectory.',
    icon: 'mdi:chart-timeline-variant',
    iconBg: '#6b728014', iconColor: '#6b7280',
    category: 'Cost', displayType: 'currency', recommended: true, link: '/cost',
  },
  {
    id: 'lastMonth',
    title: 'Last Month Spend',
    description: 'Total spend for the previous calendar month.',
    icon: 'mdi:calendar-month-outline',
    iconBg: '#6b728014', iconColor: '#6b7280',
    category: 'Cost', displayType: 'currency', recommended: true, link: '/cost',
  },
  {
    id: 'potentialSavings',
    title: 'Potential Savings',
    description: 'Combined savings available from rightsizing, waste, and idle resources.',
    icon: 'mdi:piggy-bank-outline',
    iconBg: '#1BE68B14', iconColor: '#158045',
    category: 'Cost', displayType: 'currency', recommended: true, link: '/rightsizing',
  },

  // ── Optimization ──────────────────────────────────────────────────────
  {
    id: 'totalRecs',
    title: 'Total Recommendations',
    description: 'Number of active optimization recommendations across all resource types.',
    icon: 'mdi:lightbulb-outline',
    iconBg: '#f59e0b14', iconColor: '#d97706',
    category: 'Optimization', displayType: 'count', unit: 'items', link: '/rightsizing',
  },
  {
    id: 'ec2Recs',
    title: 'EC2 Rightsizing',
    description: 'EC2 instances that are over-provisioned and can be downsized.',
    icon: 'mdi:server-outline',
    iconBg: '#FF990014', iconColor: '#FF9900',
    category: 'Optimization', displayType: 'count', unit: 'instances', link: '/rightsizing',
  },
  {
    id: 'ebsRecs',
    title: 'EBS Waste Items',
    description: 'Unattached or underutilized EBS volumes that can be cleaned up.',
    icon: 'mdi:harddisk',
    iconBg: '#8b5cf614', iconColor: '#7c3aed',
    category: 'Optimization', displayType: 'count', unit: 'volumes', link: '/waste',
  },
  {
    id: 'lambdaRecs',
    title: 'Lambda Optimization',
    description: 'Lambda functions with over-allocated memory that can be right-sized.',
    icon: 'mdi:lightning-bolt-outline',
    iconBg: '#3b82f614', iconColor: '#2563eb',
    category: 'Optimization', displayType: 'count', unit: 'functions', link: '/rightsizing',
  },
  {
    id: 'totalSavingsPotential',
    title: 'Total Savings Potential',
    description: 'Cumulative dollar value of all detected savings opportunities.',
    icon: 'mdi:cash-multiple',
    iconBg: '#10b98114', iconColor: '#059669',
    category: 'Optimization', displayType: 'currency', link: '/rightsizing',
  },

  // ── Alerts ────────────────────────────────────────────────────────────
  {
    id: 'criticalAlerts',
    title: 'Critical Alerts',
    description: 'High-severity alerts that require immediate attention.',
    icon: 'mdi:alert-circle-outline',
    iconBg: '#ef444414', iconColor: '#dc2626',
    category: 'Alerts', displayType: 'count', unit: 'alerts', link: '/alerts',
  },
  {
    id: 'anomalies',
    title: 'Cost Anomalies',
    description: 'Unusual cost spikes detected by anomaly detection across services.',
    icon: 'mdi:pulse',
    iconBg: '#f9731614', iconColor: '#ea580c',
    category: 'Alerts', displayType: 'count', unit: 'anomalies', link: '/finops',
  },

  // ── Resources ─────────────────────────────────────────────────────────
  {
    id: 'totalResources',
    title: 'Total Resources',
    description: 'Sum of all active cloud resources in this account.',
    icon: 'mdi:view-grid-outline',
    iconBg: '#3b82f614', iconColor: '#2563eb',
    category: 'Resources', displayType: 'count', unit: 'resources', link: '/cloudlist',
  },
  {
    id: 'ec2Count',
    title: 'EC2 Instances',
    description: 'Number of currently running EC2 compute instances.',
    icon: 'mdi:server',
    iconBg: '#FF990014', iconColor: '#FF9900',
    category: 'Resources', displayType: 'count', unit: 'instances', link: '/cloudlist',
  },
  {
    id: 's3Count',
    title: 'S3 Buckets',
    description: 'Total number of S3 buckets in this account.',
    icon: 'mdi:bucket-outline',
    iconBg: '#22d3ee14', iconColor: '#0891b2',
    category: 'Resources', displayType: 'count', unit: 'buckets', link: '/cloudlist',
  },
  {
    id: 'rdsCount',
    title: 'RDS Databases',
    description: 'Active RDS database instances across all engines.',
    icon: 'mdi:database-outline',
    iconBg: '#527FFF14', iconColor: '#3f60ff',
    category: 'Resources', displayType: 'count', unit: 'databases', link: '/cloudlist',
  },
  {
    id: 'lambdaCount',
    title: 'Lambda Functions',
    description: 'Total deployed Lambda functions in this account.',
    icon: 'mdi:function-variant',
    iconBg: '#8b5cf614', iconColor: '#7c3aed',
    category: 'Resources', displayType: 'count', unit: 'functions', link: '/cloudlist',
  },

  // ── AIOps ─────────────────────────────────────────────────────────────
  {
    id: 'aiopsIncidents',
    title: 'AIOps Incidents',
    description: 'AI-detected incidents and anomalies across your infrastructure.',
    icon: 'mdi:robot-outline',
    iconBg: '#a855f714', iconColor: '#9333ea',
    category: 'AIOps', displayType: 'count', unit: 'incidents', link: '/aiops',
  },

  // ── DBOps ─────────────────────────────────────────────────────────────
  {
    id: 'dbopsIssues',
    title: 'DB Health Issues',
    description: 'Database performance problems and optimization opportunities detected.',
    icon: 'mdi:database-alert-outline',
    iconBg: '#0891b214', iconColor: '#0891b2',
    category: 'DBOps', displayType: 'count', unit: 'issues', link: '/dataops',
  },
  {
    id: 'dbopsSavings',
    title: 'DB Optimization Savings',
    description: 'Potential monthly savings from database rightsizing and query tuning.',
    icon: 'mdi:database-arrow-down-outline',
    iconBg: '#0891b214', iconColor: '#0891b2',
    category: 'DBOps', displayType: 'currency', link: '/dataops',
  },
  {
    id: 'dbopsSlowQueries',
    title: 'Slow Queries',
    description: 'Database queries exceeding performance thresholds requiring tuning.',
    icon: 'mdi:database-clock-outline',
    iconBg: '#0891b214', iconColor: '#0891b2',
    category: 'DBOps', displayType: 'count', unit: 'queries', link: '/dataops',
  },

  // ── Platform ──────────────────────────────────────────────────────────
  {
    id: 'flexComputeInstances',
    title: 'Flex Compute Instances',
    description: 'Flexible spot/preemptible compute instances running at reduced cost.',
    icon: 'mdi:server-network',
    iconBg: '#f9731614', iconColor: '#ea580c',
    category: 'Platform', displayType: 'count', unit: 'instances', link: '/flex-compute',
  },
  {
    id: 'flexComputeSavings',
    title: 'Flex Compute Savings',
    description: 'Monthly savings achieved through flexible compute instance usage.',
    icon: 'mdi:server-minus',
    iconBg: '#f9731614', iconColor: '#ea580c',
    category: 'Platform', displayType: 'currency', link: '/flex-compute',
  },
  {
    id: 'autoSpottingSavings',
    title: 'AutoSpotting Savings',
    description: 'Monthly savings from automatic spot/preemptible instance management.',
    icon: 'mdi:lightning-bolt-outline',
    iconBg: '#22c55e14', iconColor: '#16a34a',
    category: 'Platform', displayType: 'currency', link: '/spot-automation',
  },
  {
    id: 'autoSpottingInstances',
    title: 'AutoSpotting Instances',
    description: 'Number of instances managed and optimized by AutoSpotting.',
    icon: 'mdi:lightning-bolt',
    iconBg: '#22c55e14', iconColor: '#16a34a',
    category: 'Platform', displayType: 'count', unit: 'instances', link: '/spot-automation',
  },
  {
    id: 'diskRightsizing',
    title: 'Disk Rightsizing',
    description: 'Disk and volume rightsizing opportunities to reduce storage costs.',
    icon: 'mdi:harddisk-plus',
    iconBg: '#6366f114', iconColor: '#4f46e5',
    category: 'Platform', displayType: 'count', unit: 'volumes', link: '/ebs-rightsizing',
  },
  {
    id: 'diskRightsizingSavings',
    title: 'Disk Rightsizing Savings',
    description: 'Potential monthly savings from disk and volume rightsizing.',
    icon: 'mdi:harddisk-remove',
    iconBg: '#6366f114', iconColor: '#4f46e5',
    category: 'Platform', displayType: 'currency', link: '/ebs-rightsizing',
  },
  {
    id: 'cicdPipelines',
    title: 'CI/CD Pipelines',
    description: 'Total active CI/CD pipelines monitored across your infrastructure.',
    icon: 'mdi:pipe',
    iconBg: '#f4336614', iconColor: '#e11d48',
    category: 'Platform', displayType: 'count', unit: 'pipelines', link: '/cicd-pipelines',
  },
  {
    id: 'cicdFailures',
    title: 'Pipeline Failures',
    description: 'Failed CI/CD pipeline runs that require immediate attention.',
    icon: 'mdi:pipe-disconnected',
    iconBg: '#f4336614', iconColor: '#e11d48',
    category: 'Platform', displayType: 'count', unit: 'failures', link: '/cicd-pipelines',
  },
  {
    id: 'cicdDeployFrequency',
    title: 'Deploy Frequency',
    description: 'Number of successful deployments in the current period.',
    icon: 'mdi:rocket-launch-outline',
    iconBg: '#f4336614', iconColor: '#e11d48',
    category: 'Platform', displayType: 'count', unit: 'deploys', link: '/cicd-pipelines',
  },
];

/**
 * Defaults span more than one category on purpose, and each one is a roll-up of
 * something another widget breaks down rather than a repeat of it:
 *   mtdSpend / forecast   → headline numbers behind the cost trend chart
 *   potentialSavings      → total of the Waste + Rightsizing summary cards
 *   totalResources        → total of the Resource Inventory rows
 *
 * `lastMonth` was dropped because the cost trend already reports the MoM delta.
 *
 * `criticalAlerts` is deliberately NOT a default: the backend populates
 * OptimizationSummary.criticalAlertsCount with
 * `anomalies + ec2Recs + ebsRecs + lambdaRecs` (DashboardDataService, and GCP
 * uses `rightsizing.size()` outright), so it is a recommendation count wearing
 * an alert label. Shipping it beside the CloudGuard-backed Alerts summary card
 * would put two disagreeing "critical" numbers on one screen.
 */
export const DEFAULT_SELECTED = ['mtdSpend', 'forecast', 'potentialSavings', 'totalResources'];
export const MIN_SELECTED = 3;
const CATEGORIES = ['Cost', 'Optimization', 'Alerts', 'Resources', 'AIOps', 'DBOps', 'Platform'] as const;
const CATEGORY_COLORS: Record<string, string> = {
  Cost:         'bg-slate-100 text-slate-600',
  Optimization: 'bg-amber-50 text-amber-700',
  Alerts:       'bg-red-50 text-red-600',
  Resources:    'bg-blue-50 text-blue-600',
  AIOps:        'bg-purple-50 text-purple-700',
  DBOps:        'bg-cyan-50 text-cyan-700',
  Platform:     'bg-rose-50 text-rose-700',
};

interface KPIManageModalProps {
  open: boolean;
  selectedIds: string[];
  onSave: (ids: string[]) => void;
  onClose: () => void;
  cards?: KPICardDef[];
  /** sessionStorage key for the in-progress selection. When set, an
   *  unexpected page reload mid-selection restores the draft on reopen. */
  draftKey?: string;
}

const KPIManageModal: React.FC<KPIManageModalProps> = ({ open, selectedIds, onSave, onClose, cards, draftKey }) => {
  const [local, setLocal]     = useState<string[]>(selectedIds);
  const [search, setSearch]   = useState('');
  const [tab, setTab]         = useState<'all' | 'selected'>('all');
  const [catFilter, setCatFilter] = useState<string>('All');

  const cardList = cards || ALL_KPI_CARDS;

  const clearDraft = () => {
    if (!draftKey) return;
    try { sessionStorage.removeItem(draftKey); } catch { /* ignore */ }
  };

  // Sync when modal reopens — restore an unsaved draft if one survived a reload
  React.useEffect(() => {
    if (!open) return;
    let draft: string[] | null = null;
    if (draftKey) {
      try {
        const raw = sessionStorage.getItem(draftKey);
        const parsed = raw ? JSON.parse(raw) : null;
        if (Array.isArray(parsed)) {
          const valid = parsed.filter((id: unknown): id is string =>
            typeof id === 'string' && cardList.some(c => c.id === id));
          if (valid.length > 0) draft = valid;
        }
      } catch { /* corrupt draft — fall back to saved selection */ }
    }
    setLocal(draft ?? selectedIds);
    setSearch(''); setTab('all'); setCatFilter('All');
  }, [open, selectedIds]); // eslint-disable-line react-hooks/exhaustive-deps

  // Keep the draft in sync while the modal is open
  React.useEffect(() => {
    if (!open || !draftKey) return;
    try { sessionStorage.setItem(draftKey, JSON.stringify(local)); } catch { /* ignore */ }
  }, [open, draftKey, local]);

  const handleClose = () => { clearDraft(); onClose(); };

  const filtered = useMemo(() => {
    let cards = cardList;
    if (tab === 'selected') cards = cards.filter(c => local.includes(c.id));
    if (catFilter !== 'All') cards = cards.filter(c => c.category === catFilter);
    if (search.trim()) {
      const q = search.toLowerCase();
      cards = cards.filter(c => c.title.toLowerCase().includes(q) || c.description.toLowerCase().includes(q) || c.category.toLowerCase().includes(q));
    }
    return cards;
  }, [local, tab, catFilter, search]);

  const toggle = (id: string) => {
    setLocal(prev =>
      prev.includes(id)
        ? (prev.length > MIN_SELECTED ? prev.filter(x => x !== id) : prev)
        : [...prev, id]
    );
  };

  const belowMin = local.length < MIN_SELECTED;
  const atMinAndSelected = (id: string) => local.includes(id) && local.length <= MIN_SELECTED;

  if (!open) return null;

  return ReactDOM.createPortal(
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
      {/* Backdrop */}
      <div className="absolute inset-0 bg-black/30 backdrop-blur-[2px]" onClick={handleClose} />

      {/* Modal */}
      <div className="relative bg-white rounded-2xl shadow-2xl w-full max-w-3xl flex flex-col overflow-hidden"
        style={{ maxHeight: '88vh' }}>

        {/* Header */}
        <div className="px-6 pt-6 pb-4 border-b border-[rgba(0,0,0,0.07)]">
          <div className="flex items-start justify-between mb-1">
            <div>
              <h2 className="text-[17px] font-bold text-[rgba(0,0,0,0.88)]">Manage KPI Cards</h2>
              <p className="text-[12px] text-[rgba(0,0,0,0.45)] mt-0.5">
                Select the metrics you want to see on your dashboard
              </p>
            </div>
            <button onClick={handleClose}
              className="w-8 h-8 rounded-lg flex items-center justify-center hover:bg-[rgba(0,0,0,0.06)] transition-colors text-[rgba(0,0,0,0.4)]">
              <Icon icon="mdi:close" width={18} />
            </button>
          </div>

          {/* Row 1: Tabs + search */}
          <div className="flex items-center justify-between gap-3 mt-3">
            <div className="flex items-center gap-1">
              <button onClick={() => setTab('all')}
                className={`px-3 py-1.5 rounded-lg text-[12px] font-semibold transition-colors ${
                  tab === 'all'
                    ? 'bg-[rgba(0,0,0,0.88)] text-white'
                    : 'text-[rgba(0,0,0,0.5)] hover:bg-[rgba(0,0,0,0.05)]'
                }`}>
                All ({cardList.length})
              </button>
              <button onClick={() => setTab('selected')}
                className={`px-3 py-1.5 rounded-lg text-[12px] font-semibold transition-colors ${
                  tab === 'selected'
                    ? 'bg-[rgba(0,0,0,0.88)] text-white'
                    : 'text-[rgba(0,0,0,0.5)] hover:bg-[rgba(0,0,0,0.05)]'
                }`}>
                Selected ({local.length})
              </button>
            </div>

            <div className="relative flex-shrink-0">
              <Icon icon="mdi:magnify" width={14} className="absolute left-2.5 top-1/2 -translate-y-1/2 text-[rgba(0,0,0,0.35)]" />
              <input
                type="text" value={search} onChange={e => setSearch(e.target.value)}
                placeholder="Search KPIs…"
                className="pl-8 pr-3 py-1.5 text-[12px] border border-[rgba(0,0,0,0.12)] rounded-lg outline-none focus:border-[rgba(0,0,0,0.35)] w-[180px] placeholder:text-[rgba(0,0,0,0.3)]"
              />
            </div>
          </div>

          {/* Row 2: Category filter chips */}
          <div className="flex items-center gap-1 mt-2 overflow-x-auto pb-0.5 scrollbar-none">
            {['All', ...CATEGORIES].map(cat => (
              <button key={cat} onClick={() => setCatFilter(cat)}
                className={`flex-shrink-0 px-2.5 py-1 rounded-lg text-[11px] font-medium transition-colors ${
                  catFilter === cat
                    ? 'bg-[rgba(0,0,0,0.07)] text-[rgba(0,0,0,0.88)]'
                    : 'text-[rgba(0,0,0,0.4)] hover:bg-[rgba(0,0,0,0.04)]'
                }`}>{cat}
              </button>
            ))}
          </div>
        </div>

        {/* Card grid */}
        <div className="flex-1 overflow-y-auto px-6 py-4">
          {filtered.length === 0 ? (
            <div className="flex flex-col items-center justify-center py-16 gap-2">
              <Icon icon="mdi:magnify" width={32} color="rgba(0,0,0,0.15)" />
              <p className="text-[13px] text-[rgba(0,0,0,0.35)]">No KPI cards match your search</p>
            </div>
          ) : (
            <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-3">
              {filtered.map(card => {
                const isSelected = local.includes(card.id);
                const locked = atMinAndSelected(card.id);
                return (
                  <button
                    key={card.id}
                    onClick={() => toggle(card.id)}
                    disabled={locked}
                    className={`relative text-left p-4 rounded-xl border-2 transition-all duration-150 group ${
                      isSelected
                        ? 'border-[rgba(0,0,0,0.88)] bg-[rgba(0,0,0,0.02)]'
                        : 'border-[rgba(0,0,0,0.1)] hover:border-[rgba(0,0,0,0.25)] bg-white'
                    } ${locked ? 'opacity-60 cursor-not-allowed' : 'cursor-pointer'}`}>

                    {/* Selected check */}
                    {isSelected && (
                      <div className="absolute top-3 right-3 w-5 h-5 rounded-full bg-[rgba(0,0,0,0.88)] flex items-center justify-center">
                        <Icon icon="mdi:check" width={11} color="white" />
                      </div>
                    )}

                    {/* Recommended badge */}
                    {card.recommended && !isSelected && (
                      <span className="absolute top-2.5 right-3 text-[10px] font-semibold text-emerald-600 bg-emerald-50 px-1.5 py-0.5 rounded-full border border-emerald-100">
                        Recommended
                      </span>
                    )}

                    <div className="flex items-start gap-3">
                      <div className="w-9 h-9 rounded-xl flex items-center justify-center flex-shrink-0"
                        style={{ backgroundColor: card.iconBg }}>
                        <Icon icon={card.icon} width={18} color={card.iconColor} />
                      </div>
                      <div className="flex-1 min-w-0 mt-0.5">
                        <p className="text-[13px] font-semibold text-[rgba(0,0,0,0.88)] leading-tight mb-1">{card.title}</p>
                        <p className="text-[11px] text-[rgba(0,0,0,0.45)] leading-relaxed">{card.description}</p>
                        <span className={`inline-block mt-2 text-[10px] font-semibold px-2 py-0.5 rounded-full ${CATEGORY_COLORS[card.category]}`}>
                          {card.category}
                        </span>
                      </div>
                    </div>
                  </button>
                );
              })}
            </div>
          )}
        </div>

        {/* Footer */}
        <div className="px-6 py-4 border-t border-[rgba(0,0,0,0.07)] flex items-center justify-between gap-4">
          <div className="flex items-center gap-2">
            {belowMin ? (
              <div className="flex items-center gap-1.5 text-amber-600">
                <Icon icon="mdi:alert-outline" width={14} />
                <span className="text-[12px] font-medium">Select at least {MIN_SELECTED} KPI cards</span>
              </div>
            ) : (
              <p className="text-[11px] text-[rgba(0,0,0,0.4)]">
                {local.length} card{local.length !== 1 ? 's' : ''} selected · min {MIN_SELECTED} required
              </p>
            )}
          </div>

          <div className="flex items-center gap-2">
            <button onClick={handleClose}
              className="px-4 py-2 rounded-lg text-[13px] font-medium bg-[rgba(0,0,0,0.06)] text-[rgba(0,0,0,0.6)] hover:bg-[rgba(0,0,0,0.09)] transition-colors">
              Cancel
            </button>
            <button
              onClick={() => { onSave(local); handleClose(); }}
              disabled={belowMin}
              className={`px-5 py-2 rounded-lg text-[13px] font-semibold transition-colors ${
                belowMin
                  ? 'bg-[rgba(0,0,0,0.15)] text-[rgba(0,0,0,0.35)] cursor-not-allowed'
                  : 'bg-[rgba(0,0,0,0.88)] text-white hover:bg-black'
              }`}>
              Save
            </button>
          </div>
        </div>
      </div>
    </div>,
    document.body
  );
};

export default KPIManageModal;
