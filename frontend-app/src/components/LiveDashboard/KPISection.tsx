import React, { useRef, useCallback, useEffect, useState, useMemo } from 'react';
import { Icon } from '@iconify/react';
import type { KPIData, CostHistory, OptimizationData, ResourceInventory } from '../../types/liveDashboard';
import KPIManageModal, { ALL_KPI_CARDS, DEFAULT_SELECTED, type KPICardDef } from './KPIManageModal';

const LS_KPI_CARDS = 'xamops_kpi_cards_v1';

const loadKpiCards = (): string[] => {
  try {
    const raw = localStorage.getItem(LS_KPI_CARDS);
    return raw ? JSON.parse(raw) : DEFAULT_SELECTED;
  } catch { return DEFAULT_SELECTED; }
};

interface KPISectionProps {
  kpiData: KPIData;
  costHistory?: CostHistory;
  optimization?: OptimizationData;
  resourceInventory?: ResourceInventory;
  isLoading: boolean;
}

// ── Helpers ──────────────────────────────────────────────────────────────────
const hexToRgba = (hex: string, alpha: number): string => {
  const r = parseInt(hex.slice(1, 3), 16);
  const g = parseInt(hex.slice(3, 5), 16);
  const b = parseInt(hex.slice(5, 7), 16);
  return `rgba(${r}, ${g}, ${b}, ${alpha})`;
};

const fmtCurrency = (n: number) =>
  '$' + n.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });

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
}

const KPICard: React.FC<KPICardProps> = ({ def, rawValue, change, changeLabel, trendData, index }) => {
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
    display  = fmtCurrency(animCount);
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
        <div className="flex items-center gap-2">
          {showBadge && (
            <span className={`inline-flex items-center gap-0.5 text-[11px] font-bold ${isGood ? 'text-[#158045]' : 'text-[#DC2626]'}`}>
              <svg className="w-2.5 h-2.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={3}
                  d={isGood ? "M13 17h8m0 0v-8m0 8l-8-8-4 4-6-6" : "M13 7h8m0 0v8m0-8l-8 8-4-4-6 6"} />
              </svg>
              {badgeText}
            </span>
          )}
          <span className="text-[11px] text-[rgba(0,0,0,0.35)]">{changeLabel}</span>
        </div>
      </div>
    </div>
  );
};

// ── KPI Section ───────────────────────────────────────────────────────────────
const KPISection: React.FC<KPISectionProps> = ({ kpiData, costHistory, optimization, resourceInventory, isLoading }) => {
  const [selectedIds, setSelectedIds] = useState<string[]>(loadKpiCards);
  const [manageOpen, setManageOpen]   = useState(false);

  const costs = costHistory?.costs || [];
  const mtdSpend        = kpiData.mtdSpend        || 0;
  const lastMonthSpend  = kpiData.lastMonthSpend  || 0;
  const forecastedSpend = kpiData.forecastedSpend || 0;
  const potentialSavings = kpiData.potentialSavings || 0;

  const mtdChange      = lastMonthSpend > 0 ? ((mtdSpend - lastMonthSpend) / lastMonthSpend * 100) : 0;
  const forecastChange = mtdSpend > 0 ? ((forecastedSpend - mtdSpend) / mtdSpend * 100) : 0;
  const lastMonthChange = costs.length > 2 && costs[costs.length - 3] > 0
    ? ((lastMonthSpend - costs[costs.length - 3]) / costs[costs.length - 3] * 100) : 0;

  const totalResources = resourceInventory
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
      default:
        return { value: 0, change: 0, changeLabel: '', trendData: [] };
    }
  }, [mtdSpend, lastMonthSpend, forecastedSpend, potentialSavings, mtdChange, forecastChange, lastMonthChange, costs, optimization, resourceInventory, totalResources]);

  const selectedCards: KPICardDef[] = useMemo(
    () => selectedIds
      .map(id => ALL_KPI_CARDS.find(c => c.id === id))
      .filter((c): c is KPICardDef => c !== undefined),
    [selectedIds]
  );

  const handleSave = (ids: string[]) => {
    setSelectedIds(ids);
    localStorage.setItem(LS_KPI_CARDS, JSON.stringify(ids));
  };

  if (isLoading) {
    return (
      <div>
        <div className="flex items-center justify-between mb-3">
          <div className="h-3 w-24 bg-[rgba(0,0,0,0.06)] rounded animate-pulse" />
          <div className="h-7 w-28 bg-[rgba(0,0,0,0.04)] rounded-lg animate-pulse" />
        </div>
        <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-4 gap-4">
          {[1, 2, 3, 4].map(i => (
            <div key={i} className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-5 h-[110px] animate-pulse">
              <div className="h-2.5 w-24 bg-[rgba(0,0,0,0.06)] rounded mb-3" />
              <div className="h-7 w-28 bg-[rgba(0,0,0,0.06)] rounded mb-3" />
              <div className="h-2 w-16 bg-[rgba(0,0,0,0.04)] rounded" />
            </div>
          ))}
        </div>
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
        <button
          onClick={() => setManageOpen(true)}
          className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-[12px] font-medium border border-[rgba(0,0,0,0.1)] text-[rgba(0,0,0,0.55)] hover:bg-[rgba(0,0,0,0.04)] hover:text-[rgba(0,0,0,0.8)] transition-colors"
        >
          <Icon icon="mdi:view-dashboard-edit-outline" width={13} />
          Manage KPIs
        </button>
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
            />
          );
        })}
      </div>

      {/* Manage modal */}
      <KPIManageModal
        open={manageOpen}
        selectedIds={selectedIds}
        onSave={handleSave}
        onClose={() => setManageOpen(false)}
      />
    </>
  );
};

export default KPISection;
