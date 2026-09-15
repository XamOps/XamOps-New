import React, { useRef, useCallback, useEffect, useState, useMemo } from 'react';
import { Link } from 'react-router-dom';
import { Icon } from '@iconify/react';
import type { KPIData, CostHistory, OptimizationData, ResourceInventory } from '../../types/liveDashboard';
import { fmtMoney } from '../../utils/currency';
import KPIManageModal, { ALL_KPI_CARDS, DEFAULT_SELECTED, LEGACY_DEFAULT_SELECTED, MIN_SELECTED, type KPICardDef } from './KPIManageModal';

const LS_KEY = (provider: string) =>
  provider === 'GCP' ? 'xamops_kpi_cards_gcp_v1'
  : provider === 'Azure' ? 'xamops_kpi_cards_azure_v1'
  : 'xamops_kpi_cards_v1';

// Session-scoped keys so an unexpected page reload mid-selection doesn't
// discard the user's in-progress KPI picks: the modal reopens with its draft.
const MODAL_OPEN_KEY = (provider: string) => `xamops_kpi_modal_open_${provider}`;
const DRAFT_KEY = (provider: string) => `xamops_kpi_draft_${provider}`;

const sameSelection = (a: string[], b: string[]) =>
  a.length === b.length && a.every(id => b.includes(id));

const loadKpiCards = (provider: string): string[] => {
  try {
    const raw = localStorage.getItem(LS_KEY(provider));
    if (!raw) return DEFAULT_SELECTED;
    const parsed = JSON.parse(raw);
    if (!Array.isArray(parsed) || parsed.length === 0) return DEFAULT_SELECTED;
    // A stored list identical to the old default is an untouched default, so move
    // it onto the current one — otherwise the new fifth card would only ever show
    // up for users who had never opened a dashboard before. A customised
    // selection is left exactly as the user saved it.
    if (sameSelection(parsed, LEGACY_DEFAULT_SELECTED)) {
      localStorage.setItem(LS_KEY(provider), JSON.stringify(DEFAULT_SELECTED));
      return DEFAULT_SELECTED;
    }
    return parsed;
  } catch { return DEFAULT_SELECTED; }
};

// Provider-specific name/description overrides for AWS-named cards
const PROVIDER_OVERRIDES: Record<string, Record<string, Partial<KPICardDef>>> = {
  GCP: {
    // Existing AWS→GCP renames
    ec2Recs:               { title: 'GCE Rightsizing',                description: 'GCE instances over-provisioned and can be downsized.',              unit: 'instances' },
    ebsRecs:               { title: 'Persistent Disk Waste',          description: 'Unattached or underutilized persistent disks to remove.',           unit: 'disks'     },
    lambdaRecs:            { title: 'Cloud Function Optimization',    description: 'Cloud Functions with over-allocated memory to right-size.',         unit: 'functions' },
    ec2Count:              { title: 'Compute Instances',              description: 'Number of currently running GCE compute instances.',                unit: 'instances' },
    s3Count:               { title: 'Cloud Storage Buckets',          description: 'Total number of Cloud Storage buckets in this project.',            unit: 'buckets'   },
    rdsCount:              { title: 'Cloud SQL Instances',            description: 'Active Cloud SQL database instances.',                              unit: 'instances' },
    lambdaCount:           { title: 'Cloud Functions',                description: 'Total deployed Cloud Functions in this project.',                   unit: 'functions' },
    // Platform overrides for GCP
    flexComputeInstances:  { title: 'Preemptible Instances',          description: 'GCP preemptible VM instances running at reduced cost.',             unit: 'instances' },
    flexComputeSavings:    { title: 'Preemptible Savings',            description: 'Monthly savings from preemptible VM usage.',                       },
    autoSpottingSavings:   { title: 'AutoSpotting Savings',           description: 'Monthly savings from AutoSpotting spot instance management on GCP.' },
    autoSpottingInstances: { title: 'AutoSpotting Instances',         description: 'GCP instances managed and optimized by AutoSpotting.',              unit: 'instances' },
    diskRightsizing:       { title: 'Persistent Disk Rightsizing',    description: 'Persistent disk rightsizing opportunities to reduce storage costs.', unit: 'disks'    },
    diskRightsizingSavings:{ title: 'Disk Rightsizing Savings',       description: 'Potential savings from persistent disk rightsizing on GCP.'        },
    cicdPipelines:         { title: 'Cloud Build Pipelines',          description: 'Active Cloud Build pipelines monitored in this project.',           unit: 'pipelines' },
    cicdFailures:          { title: 'Cloud Build Failures',           description: 'Failed Cloud Build pipeline runs requiring attention.',              unit: 'failures'  },
    cicdDeployFrequency:   { title: 'Deploy Frequency',               description: 'Number of successful Cloud Build deployments this period.',         unit: 'deploys'   },
  },
  Azure: {
    // Existing AWS→Azure renames
    ec2Recs:               { title: 'VM Rightsizing',                 description: 'Azure VMs over-provisioned and can be downsized.',                 unit: 'VMs'       },
    ebsRecs:               { title: 'Disk Waste',                     description: 'Unattached or underutilized managed disks to clean up.',           unit: 'disks'     },
    lambdaRecs:            { title: 'Function App Optimization',      description: 'Azure Function Apps with over-allocated memory to right-size.',    unit: 'functions' },
    ec2Count:              { title: 'Virtual Machines',               description: 'Number of currently running Azure Virtual Machines.',              unit: 'VMs'       },
    s3Count:               { title: 'Storage Accounts',               description: 'Total number of Azure Storage Accounts.',                          unit: 'accounts'  },
    rdsCount:              { title: 'SQL Databases',                  description: 'Active Azure SQL Database instances.',                             unit: 'databases' },
    lambdaCount:           { title: 'Function Apps',                  description: 'Total deployed Azure Function Apps.',                              unit: 'functions' },
    // Platform overrides for Azure
    flexComputeInstances:  { title: 'Spot VM Instances',              description: 'Azure Spot VMs running workloads at reduced cost.',                unit: 'VMs'       },
    flexComputeSavings:    { title: 'Spot VM Savings',                description: 'Monthly savings achieved through Azure Spot VM usage.'            },
    autoSpottingSavings:   { title: 'Spot VM AutoSpotting Savings',   description: 'Monthly savings from AutoSpotting Spot VM management on Azure.'   },
    autoSpottingInstances: { title: 'AutoSpotting Instances',         description: 'Azure VMs managed and optimized by AutoSpotting.',                 unit: 'VMs'       },
    diskRightsizing:       { title: 'Managed Disk Rightsizing',       description: 'Azure managed disk rightsizing opportunities to reduce costs.',    unit: 'disks'     },
    diskRightsizingSavings:{ title: 'Disk Rightsizing Savings',       description: 'Potential savings from managed disk rightsizing on Azure.'        },
    cicdPipelines:         { title: 'Azure DevOps Pipelines',         description: 'Active Azure DevOps pipelines monitored in this subscription.',   unit: 'pipelines' },
    cicdFailures:          { title: 'Pipeline Failures',              description: 'Failed Azure DevOps pipeline runs requiring attention.',            unit: 'failures'  },
    cicdDeployFrequency:   { title: 'Deploy Frequency',               description: 'Number of successful Azure DevOps deployments this period.',       unit: 'deploys'   },
  },
};

// "View more" targets — swap the AWS route for the provider's variant when one exists
const PROVIDER_LINKS: Record<string, Record<string, string>> = {
  GCP: {
    '/cost':            '/gcp-cost',
    '/finops':          '/gcp-finops',
    '/waste':           '/gcp-waste',
    '/rightsizing':     '/gcp-rightsizing',
    '/alerts':          '/gcp-alerts',
    '/dataops':         '/gcp-dataops',
    '/ebs-rightsizing': '/gcp-disk-rightsizing',
  },
  Azure: {
    '/cost':            '/azure-cost',
    '/finops':          '/azure-finops',
    '/waste':           '/azure-waste',
    '/rightsizing':     '/azure-rightsizing',
    '/alerts':          '/azure-alerts',
    '/dataops':         '/azure-dataops',
    '/ebs-rightsizing': '/azure-disk-rightsizing',
  },
};

interface KPISectionProps {
  kpiData: KPIData;
  costHistory?: CostHistory;
  optimization?: OptimizationData;
  resourceInventory?: ResourceInventory;
  /**
   * Pre-computed resource count, for providers whose inventory does not use the AWS
   * field names below. Without it the Azure and GCP dashboards showed
   * "Total Resources 0" beside an inventory panel listing real counts, because the
   * sum reads `ec2Count`, `lambdaCount` and friends, none of which those providers set.
   */
  totalResourcesOverride?: number;
  isLoading: boolean;
  provider?: 'AWS' | 'GCP' | 'Azure';
  accountId?: string | null;
  headerAction?: React.ReactNode;
}

// IDs that need live API fetching beyond the main dashboard data
const EXTENDED_CARD_IDS = new Set([
  'aiopsIncidents',
  'dbopsIssues', 'dbopsSavings', 'dbopsSlowQueries',
  'autoSpottingSavings', 'autoSpottingInstances',
  'flexComputeInstances', 'flexComputeSavings',
  'cicdPipelines', 'cicdFailures', 'cicdDeployFrequency',
  'diskRightsizing', 'diskRightsizingSavings',
]);

// ── Helpers ──────────────────────────────────────────────────────────────────
const hexToRgba = (hex: string, alpha: number): string => {
  const r = parseInt(hex.slice(1, 3), 16);
  const g = parseInt(hex.slice(3, 5), 16);
  const b = parseInt(hex.slice(5, 7), 16);
  return `rgba(${r}, ${g}, ${b}, ${alpha})`;
};

const fmtCount = (n: number) =>
  Math.round(n).toLocaleString('en-US');

// ── KPI Card ─────────────────────────────────────────────────────────────────
interface KPICardProps {
  def: KPICardDef;
  rawValue: number;
  change: number;
  changeLabel: string;
  trendData: number[];
  index: number;
  provider?: 'AWS' | 'GCP' | 'Azure';
}

const KPICard: React.FC<KPICardProps> = ({ def, rawValue, change, changeLabel, trendData, index, provider }) => {
  const chartInstanceRef = useRef<any>(null);
  const animRef          = useRef<number | undefined>(undefined);
  const prevRawValue     = useRef(0);
  const [animCount, setAnimCount] = useState(rawValue);

  useEffect(() => {
    if (rawValue === prevRawValue.current) return;
    const prev = prevRawValue.current;
    prevRawValue.current = rawValue;
    if (animRef.current) cancelAnimationFrame(animRef.current);
    if (rawValue === 0) { setAnimCount(0); return; }
    const duration = 1050;
    const startTime = performance.now();
    const step = (now: number) => {
      const t = Math.min((now - startTime) / duration, 1);
      const eased = 1 - Math.pow(1 - t, 3);
      setAnimCount(prev + eased * (rawValue - prev));
      if (t < 1) { animRef.current = requestAnimationFrame(step); }
      else { setAnimCount(rawValue); }
    };
    animRef.current = requestAnimationFrame(step);
    return () => { if (animRef.current) cancelAnimationFrame(animRef.current); };
  }, [rawValue]);

  const sparklineId = `sparkline-${def.id}`;

  const handleMouseEnter = useCallback(() => {
    if (!window.Chart || !trendData || trendData.length < 2) return;
    const canvas = document.getElementById(sparklineId) as HTMLCanvasElement;
    if (!canvas) return;
    if (chartInstanceRef.current) { chartInstanceRef.current.destroy(); chartInstanceRef.current = null; }
    const ctx = canvas.getContext('2d');
    if (!ctx) return;
    chartInstanceRef.current = new window.Chart(ctx, {
      type: 'line',
      data: {
        labels: trendData.map((_, i) => i.toString()),
        datasets: [{
          data: trendData,
          borderColor: def.iconColor,
          backgroundColor: hexToRgba(def.iconColor, 0.08),
          borderWidth: 1.5,
          pointRadius: 0,
          tension: 0.4,
          fill: true,
        }],
      },
      options: {
        responsive: true, maintainAspectRatio: false,
        scales: { x: { display: false }, y: { display: false } },
        plugins: { legend: { display: false }, tooltip: { enabled: false } },
      },
    });
  }, [sparklineId, trendData, def.iconColor]);

  const handleMouseLeave = useCallback(() => {
    if (chartInstanceRef.current) { chartInstanceRef.current.destroy(); chartInstanceRef.current = null; }
  }, []);

  const isCurrency = def.displayType === 'currency';

  // For currency: show change badge; for count alerts, red=bad, for count recs, yellow=info
  const showBadge = isCurrency && change !== 0;
  const isGood = change < 0;
  const safeChange = isFinite(change) && !isNaN(change) ? change : null;
  const badgeText  = safeChange === null ? 'N/A' : `${safeChange >= 0 ? '+' : ''}${Math.abs(safeChange).toFixed(1)}%`;

  // Display value
  let display = '';
  let mainPart = '';
  let decPart  = '';
  if (isCurrency) {
    display  = fmtMoney(animCount, provider);
    mainPart = display.includes('.') ? display.slice(0, display.lastIndexOf('.')) : display;
    decPart  = display.includes('.') ? display.slice(display.lastIndexOf('.'))    : '';
  } else {
    mainPart = fmtCount(animCount);
  }

  // Alert count — urgent coloring
  const isAlert = def.category === 'Alerts' && rawValue > 0;

  const delay = 0.05 + index * 0.07;

  return (
    <div
      className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-5 relative overflow-hidden group card-hover-lift"
      style={{ animation: `fadeInUp 0.5s cubic-bezier(0.22, 1, 0.36, 1) ${delay}s both` }}
      onMouseEnter={handleMouseEnter}
      onMouseLeave={handleMouseLeave}
    >
      {/* Decorative cloud */}
      <div className="absolute top-0 right-0 w-full h-full pointer-events-none opacity-[0.07]">
        <svg className="absolute -top-8 -right-12 w-32 h-32" viewBox="0 0 200 120" fill="currentColor" style={{ color: '#6b7280' }}>
          <path d="M140 70c0-15-12-27-27-27-3 0-6 1-9 2-6-14-20-24-36-24-17 0-31 10-36 24-3-1-6-2-9-2-15 0-27 12-27 27s12 27 27 27h117c15 0 27-12 27-27z"/>
        </svg>
      </div>

      {/* Sparkline on hover */}
      <div className="absolute inset-x-0 bottom-0 h-14 opacity-0 group-hover:opacity-100 transition-opacity duration-200 pointer-events-none">
        <canvas id={sparklineId} className="w-full h-full"></canvas>
      </div>

      {/* Category icon dot */}
      <div className="absolute top-4 right-4 w-7 h-7 rounded-lg flex items-center justify-center"
        style={{ backgroundColor: def.iconBg }}>
        <Icon icon={def.icon} width={14} color={isAlert ? '#dc2626' : def.iconColor} />
      </div>

      <div className="relative z-10">
        <p className="text-[11px] font-semibold uppercase tracking-[0.09em] leading-[15px] text-[rgba(0,0,0,0.55)] mb-3 pr-8">
          {def.title}
        </p>

        {/* Value */}
        {isCurrency ? (
          <p className="leading-8 mb-2 flex items-baseline gap-0.5">
            <span style={{ fontFamily: "'Bebas Neue', 'Barlow Condensed', sans-serif", fontSize: '30px', fontWeight: 400, letterSpacing: '0px', lineHeight: '30px', color: 'rgb(10,10,10)', fontVariantNumeric: 'tabular-nums' }}>
              {mainPart}
            </span>
            {decPart && (
              <span style={{ fontFamily: "'Inter', sans-serif", fontSize: '13px', fontWeight: 400, lineHeight: '20px', color: 'rgb(10,10,10)' }}>
                {decPart}
              </span>
            )}
          </p>
        ) : (
          <div className="mb-2">
            <span style={{ fontFamily: "'Bebas Neue', 'Barlow Condensed', sans-serif", fontSize: '30px', fontWeight: 400, letterSpacing: '0px', lineHeight: '30px', color: isAlert ? '#dc2626' : 'rgb(10,10,10)', fontVariantNumeric: 'tabular-nums' }}>
              {mainPart}
            </span>
            {def.unit && (
              <span className="ml-1.5 text-[11px] font-medium" style={{ color: isAlert ? '#dc2626' : def.iconColor }}>
                {def.unit}
              </span>
            )}
          </div>
        )}

        {/* Badge row */}
        <div className="flex items-center justify-between gap-2">
          <div className="flex items-center gap-2 min-w-0">
            {showBadge && (
              <span className={`inline-flex items-center gap-0.5 text-[11px] font-bold flex-shrink-0 ${isGood ? 'text-[#158045]' : 'text-[#DC2626]'}`}>
                <svg className="w-2.5 h-2.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={3}
                    d={isGood ? "M13 17h8m0 0v-8m0 8l-8-8-4 4-6-6" : "M13 7h8m0 0v8m0-8l-8 8-4-4-6 6"} />
                </svg>
                {badgeText}
              </span>
            )}
            <span className="text-[11px] text-[rgba(0,0,0,0.35)] truncate">{changeLabel}</span>
          </div>
          {def.link && (
            <Link
              to={def.link}
              className="inline-flex items-center gap-0.5 text-[10.5px] font-semibold whitespace-nowrap flex-shrink-0 hover:opacity-70 transition-opacity"
              style={{ color: def.iconColor }}
            >
              View more <Icon icon="mdi:arrow-right" width={10} />
            </Link>
          )}
        </div>
      </div>
    </div>
  );
};

// ── KPI Section ───────────────────────────────────────────────────────────────
const KPISection: React.FC<KPISectionProps> = ({ kpiData, costHistory, optimization, resourceInventory, totalResourcesOverride, isLoading, provider = 'AWS', accountId, headerAction }) => {
  const [selectedIds, setSelectedIds] = useState<string[]>(() => loadKpiCards(provider));
  const [manageOpen, setManageOpen]   = useState(() => {
    try { return sessionStorage.getItem(MODAL_OPEN_KEY(provider)) === '1'; } catch { return false; }
  });
  const [extValues, setExtValues]     = useState<Record<string, number>>({});

  const openManage = (open: boolean) => {
    setManageOpen(open);
    try {
      if (open) sessionStorage.setItem(MODAL_OPEN_KEY(provider), '1');
      else sessionStorage.removeItem(MODAL_OPEN_KEY(provider));
    } catch { /* sessionStorage unavailable */ }
  };

  const providerOverrides = PROVIDER_OVERRIDES[provider] || {};
  const providerLinks = PROVIDER_LINKS[provider] || {};
  const activeCards: KPICardDef[] = useMemo(() =>
    ALL_KPI_CARDS.map(card => ({
      ...card,
      ...(providerOverrides[card.id] || {}),
      link: card.link ? (providerLinks[card.link] || card.link) : undefined,
    })),
  [provider]); // eslint-disable-line react-hooks/exhaustive-deps

  // Fetch data for extended KPI cards that are currently selected
  useEffect(() => {
    const active = selectedIds.filter(id => EXTENDED_CARD_IDS.has(id));
    if (active.length === 0) return;

    const has = (...ids: string[]) => ids.some(id => active.includes(id));
    const dbProvider = provider === 'GCP' ? 'gcp' : provider === 'Azure' ? 'azure' : 'aws';
    const fetches: Promise<void>[] = [];
    const upd: Record<string, number> = {};

    // AIOps anomalies (global — no accountId needed)
    if (has('aiopsIncidents')) {
      fetches.push(
        fetch('/api/xamops/aiops/anomalies?period=30d', { credentials: 'include' })
          .then(r => r.ok ? r.json() : [])
          .then((d: any) => { upd.aiopsIncidents = Array.isArray(d) ? d.length : 0; })
          .catch(() => {})
      );
    }

    // DBOps summary (account-specific; also drives diskRightsizing)
    if (accountId && has('dbopsIssues', 'dbopsSavings', 'diskRightsizing', 'diskRightsizingSavings')) {
      fetches.push(
        fetch(`/api/dbops/${dbProvider}/${accountId}/summary`, { credentials: 'include' })
          .then(r => r.ok ? r.json() : null)
          .then((s: any) => {
            if (!s) return;
            upd.dbopsIssues           = (s.idleInstances || 0) + (s.rightsizingCandidates || 0);
            upd.dbopsSavings          = s.totalPotentialSavings || 0;
            upd.diskRightsizing       = s.rightsizingCandidates || 0;
            upd.diskRightsizingSavings = s.rightsizingSavingsPotential || s.rightsizingSavings || 0;
          })
          .catch(() => {})
      );
    }

    // DBOps slow queries
    if (accountId && has('dbopsSlowQueries')) {
      fetches.push(
        fetch(`/api/dbops/${dbProvider}/${accountId}/slow-queries`, { credentials: 'include' })
          .then(r => r.ok ? r.json() : null)
          .then((s: any) => {
            if (!s) return;
            upd.dbopsSlowQueries = (s.queries || (Array.isArray(s) ? s : [])).length;
          })
          .catch(() => {})
      );
    }

    // AutoSpotting (AWS only, account-specific)
    if (accountId && provider === 'AWS' && has('autoSpottingSavings', 'autoSpottingInstances')) {
      fetches.push(
        fetch(`/api/autospotting/costs/${accountId}`, { credentials: 'include' })
          .then(r => r.ok ? r.json() : null)
          .then((d: any) => {
            if (!d) return;
            upd.autoSpottingSavings    = (d.summary?.total_actual_savings || 0) * 730;
            upd.autoSpottingInstances  = (d.asgs || []).length;
          })
          .catch(() => {})
      );
    }

    // FlexCompute (global)
    if (has('flexComputeInstances', 'flexComputeSavings')) {
      const p = new URLSearchParams({ page: '1', pageSize: '500', sort: 'desc' });
      p.append('statuses', 'ACTIVE');
      fetches.push(
        fetch(`/api/flexcompute/capacity?${p}`, { credentials: 'include' })
          .then(r => r.ok ? r.json() : null)
          .then((d: any) => {
            if (!d) return;
            const items: any[] = Array.isArray(d) ? d : (d.data || []);
            upd.flexComputeInstances = items.filter((c: any) => c.status === 'ACTIVE').length;
            upd.flexComputeSavings   = items.reduce((s: number, c: any) => s + (c.totalSavings || 0), 0);
          })
          .catch(() => {})
      );
    }

    // CI/CD — fetch github + jenkins metrics in parallel
    if (has('cicdPipelines', 'cicdFailures', 'cicdDeployFrequency')) {
      fetches.push(
        Promise.all([
          fetch('/api/cicd/github/metrics?days=30',  { credentials: 'include' }).then(r => r.ok ? r.json() : null).catch(() => null),
          fetch('/api/cicd/jenkins/metrics?days=30', { credentials: 'include' }).then(r => r.ok ? r.json() : null).catch(() => null),
        ]).then(([gh, jk]) => {
          const total    = (gh?.totalRuns || 0) + (jk?.totalRuns || 0);
          const failures = (gh?.failureCount || 0) + (jk?.failureCount || 0);
          upd.cicdPipelines       = total;
          upd.cicdFailures        = failures;
          upd.cicdDeployFrequency = Math.max(0, total - failures);
        })
      );
    }

    Promise.all(fetches).then(() => setExtValues(prev => ({ ...prev, ...upd })));
  }, [accountId, provider, selectedIds.join(',')]); // eslint-disable-line react-hooks/exhaustive-deps

  const costs = costHistory?.costs || [];
  const mtdSpend        = kpiData.mtdSpend        || 0;
  const lastMonthSpend  = kpiData.lastMonthSpend  || 0;
  const forecastedSpend = kpiData.forecastedSpend || 0;
  const potentialSavings = kpiData.potentialSavings || 0;

  const mtdChange      = lastMonthSpend > 0 ? ((mtdSpend - lastMonthSpend) / lastMonthSpend * 100) : 0;
  const forecastChange = mtdSpend > 0 ? ((forecastedSpend - mtdSpend) / mtdSpend * 100) : 0;
  const lastMonthChange = costs.length > 2 && costs[costs.length - 3] > 0
    ? ((lastMonthSpend - costs[costs.length - 3]) / costs[costs.length - 3] * 100) : 0;

  // The field sum below is AWS-shaped. Azure and GCP pass their own count instead.
  const totalResources = totalResourcesOverride !== undefined
    ? totalResourcesOverride
    : resourceInventory
      ? (resourceInventory.ec2Count + resourceInventory.s3Count + resourceInventory.rdsCount +
         resourceInventory.vpcCount + resourceInventory.lambdaCount + resourceInventory.ebsCount +
         resourceInventory.route53Count + resourceInventory.loadBalancerCount +
         resourceInventory.ecsCount + resourceInventory.eksCount +
         resourceInventory.lightsailCount + resourceInventory.amplifyCount)
      : 0;

  // Map card ID → runtime value + metadata
  const getCardData = useCallback((id: string) => {
    switch (id) {
      case 'mtdSpend':
        return { value: mtdSpend, change: mtdChange, changeLabel: 'vs last period', trendData: costs.length ? costs : [0] };
      case 'forecast':
        return { value: forecastedSpend, change: forecastChange, changeLabel: 'vs current MTD', trendData: [...costs, forecastedSpend] };
      case 'lastMonth':
        return { value: lastMonthSpend, change: lastMonthChange, changeLabel: 'vs prior month', trendData: costs.length > 1 ? costs.slice(0, -1) : [lastMonthSpend] };
      case 'potentialSavings':
        return { value: potentialSavings, change: 0, changeLabel: 'available to reclaim', trendData: [0, potentialSavings] };
      case 'totalRecs':
        return { value: optimization?.summary.totalRecommendations || 0, change: 0, changeLabel: 'active recommendations', trendData: [] };
      case 'ec2Recs':
        return { value: optimization?.summary.ec2RecommendationCount || 0, change: 0, changeLabel: 'instances to resize', trendData: [] };
      case 'ebsRecs':
        return { value: optimization?.summary.ebsRecommendationCount || 0, change: 0, changeLabel: 'volumes to clean up', trendData: [] };
      case 'lambdaRecs':
        return { value: optimization?.summary.lambdaRecommendationCount || 0, change: 0, changeLabel: 'functions to optimize', trendData: [] };
      case 'totalSavingsPotential':
        return { value: optimization?.summary.totalPotentialSavings || 0, change: 0, changeLabel: 'savings identified', trendData: [] };
      case 'criticalAlerts':
        return { value: optimization?.summary.criticalAlerts || 0, change: 0, changeLabel: 'require immediate action', trendData: [] };
      case 'anomalies':
        return { value: optimization?.anomalies?.length || 0, change: 0, changeLabel: 'cost anomalies detected', trendData: [] };
      case 'totalResources':
        return { value: totalResources, change: 0, changeLabel: 'active cloud resources', trendData: [] };
      case 'ec2Count':
        return { value: resourceInventory?.ec2Count || 0, change: 0, changeLabel: 'running instances', trendData: [] };
      case 's3Count':
        return { value: resourceInventory?.s3Count || 0, change: 0, changeLabel: 'active buckets', trendData: [] };
      case 'rdsCount':
        return { value: resourceInventory?.rdsCount || 0, change: 0, changeLabel: 'database instances', trendData: [] };
      case 'lambdaCount':
        return { value: resourceInventory?.lambdaCount || 0, change: 0, changeLabel: 'deployed functions', trendData: [] };

      // ── AIOps ──────────────────────────────────────────────────────────
      case 'aiopsIncidents':
        return { value: extValues.aiopsIncidents ?? 0, change: 0, changeLabel: 'detected incidents', trendData: [] };

      // ── DBOps ──────────────────────────────────────────────────────────
      case 'dbopsIssues':
        return { value: extValues.dbopsIssues ?? 0, change: 0, changeLabel: 'health issues found', trendData: [] };
      case 'dbopsSavings':
        return { value: extValues.dbopsSavings ?? 0, change: 0, changeLabel: 'db savings potential', trendData: [] };
      case 'dbopsSlowQueries':
        return { value: extValues.dbopsSlowQueries ?? 0, change: 0, changeLabel: 'slow queries detected', trendData: [] };

      // ── Platform ───────────────────────────────────────────────────────
      case 'flexComputeInstances':
        return { value: extValues.flexComputeInstances ?? 0, change: 0, changeLabel: 'flex instances active', trendData: [] };
      case 'flexComputeSavings':
        return { value: extValues.flexComputeSavings ?? 0, change: 0, changeLabel: 'flex compute savings', trendData: [] };
      case 'autoSpottingSavings':
        return { value: extValues.autoSpottingSavings ?? 0, change: 0, changeLabel: 'monthly spot savings', trendData: [] };
      case 'autoSpottingInstances':
        return { value: extValues.autoSpottingInstances ?? 0, change: 0, changeLabel: 'instances managed', trendData: [] };
      case 'diskRightsizing':
        return { value: extValues.diskRightsizing ?? 0, change: 0, changeLabel: 'volumes to rightsize', trendData: [] };
      case 'diskRightsizingSavings':
        return { value: extValues.diskRightsizingSavings ?? 0, change: 0, changeLabel: 'disk savings potential', trendData: [] };
      case 'cicdPipelines':
        return { value: extValues.cicdPipelines ?? 0, change: 0, changeLabel: 'active pipelines', trendData: [] };
      case 'cicdFailures':
        return { value: extValues.cicdFailures ?? 0, change: 0, changeLabel: 'require attention', trendData: [] };
      case 'cicdDeployFrequency':
        return { value: extValues.cicdDeployFrequency ?? 0, change: 0, changeLabel: 'successful deploys', trendData: [] };

      default:
        return { value: 0, change: 0, changeLabel: '', trendData: [] };
    }
  }, [mtdSpend, lastMonthSpend, forecastedSpend, potentialSavings, mtdChange, forecastChange, lastMonthChange, costs, optimization, resourceInventory, totalResources, extValues]);

  const selectedCards: KPICardDef[] = useMemo(
    () => selectedIds
      .map(id => activeCards.find(c => c.id === id))
      .filter((c): c is KPICardDef => c !== undefined),
    [selectedIds, activeCards]
  );

  const handleSave = (ids: string[]) => {
    setSelectedIds(ids);
    localStorage.setItem(LS_KEY(provider), JSON.stringify(ids));
  };

  // Rendered in both branches below so a loading flicker (e.g. dashboard
  // auto-refresh) never unmounts an open modal mid-selection.
  const manageModal = (
    <KPIManageModal
      open={manageOpen}
      selectedIds={selectedIds}
      onSave={handleSave}
      onClose={() => openManage(false)}
      cards={activeCards}
      draftKey={DRAFT_KEY(provider)}
    />
  );

  if (isLoading) {
    return (
      <div>
        <div className="flex items-center justify-between mb-3">
          <div className="h-3 w-24 bg-[rgba(0,0,0,0.06)] rounded animate-pulse" />
          <div className="h-7 w-28 bg-[rgba(0,0,0,0.04)] rounded-lg animate-pulse" />
        </div>
        {/* Same column count and placeholder count as the loaded grid, so the row
            does not reflow when the real cards arrive. */}
        <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-4 xl:grid-cols-5 gap-4">
          {Array.from({ length: Math.max(selectedIds.length, MIN_SELECTED) }, (_, i) => i).map(i => (
            <div key={i} className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-5 h-[110px] animate-pulse">
              <div className="h-2.5 w-24 bg-[rgba(0,0,0,0.06)] rounded mb-3" />
              <div className="h-7 w-28 bg-[rgba(0,0,0,0.06)] rounded mb-3" />
              <div className="h-2 w-16 bg-[rgba(0,0,0,0.04)] rounded" />
            </div>
          ))}
        </div>
        {manageModal}
      </div>
    );
  }

  return (
    <>
      {/* Row header */}
      <div className="flex items-center justify-between mb-3">
        <div className="flex items-center gap-2">
          <span className="text-[11px] font-semibold uppercase tracking-wide text-[rgba(0,0,0,0.35)]">KPI Overview</span>
          <span className="text-[10px] text-[rgba(0,0,0,0.25)] bg-[rgba(0,0,0,0.05)] px-1.5 py-0.5 rounded-full font-medium">
            {selectedCards.length} cards
          </span>
        </div>
        <div className="flex items-center gap-2">
          {headerAction}
          <button
            onClick={() => openManage(true)}
            className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-[12px] font-medium border border-[rgba(0,0,0,0.1)] text-[rgba(0,0,0,0.55)] hover:bg-[rgba(0,0,0,0.04)] hover:text-[rgba(0,0,0,0.8)] transition-colors"
          >
            <Icon icon="mdi:view-dashboard-edit-outline" width={13} />
            Manage KPIs
          </button>
        </div>
      </div>

      {/* Card grid */}
      <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-4 xl:grid-cols-5 gap-4">
        {selectedCards.map((def, i) => {
          const data = getCardData(def.id);
          return (
            <KPICard
              key={def.id}
              def={def}
              rawValue={data.value}
              change={data.change}
              changeLabel={data.changeLabel}
              trendData={data.trendData}
              index={i}
              provider={provider}
            />
          );
        })}
      </div>

      {/* Manage modal */}
      {manageModal}
    </>
  );
};

export default KPISection;
