import React, { useEffect, useMemo, useState } from 'react';
import { Icon } from '@iconify/react';
import {
  AreaChart, Area, XAxis, YAxis, CartesianGrid, Tooltip,
  ResponsiveContainer, ReferenceLine, ReferenceDot,
} from 'recharts';
import type { CostHistory } from '../../types/liveDashboard';
import { fmtMoney, fmtMoneyShort } from '../../utils/currency';
import apiClient from '../../utils/apiClient';

interface CostTrendSectionProps {
  costHistory: CostHistory;
  isLoading: boolean;
  provider?: 'AWS' | 'GCP' | 'Azure';
  /**
   * Projected month-end spend for the current month, i.e. the same month as the
   * final point in `costHistory`. Marked on the chart at that month's position.
   */
  forecastedSpend?: number;
  /** Used to look up account budgets for the threshold line. AWS only. */
  accountId?: string | null;
}

/** Subset of DashboardData.BudgetDetails */
interface BudgetDetails {
  budgetName: string;
  budgetLimit: number | string | null;
  actualSpend: number | string | null;
  serviceName?: string | null;
}

type Slice = '3m' | '6m' | '12m' | 'all';

const SLICES: { id: Slice; label: string; months: number }[] = [
  { id: '3m',  label: '3M',  months: 3   },
  { id: '6m',  label: '6M',  months: 6   },
  { id: '12m', label: '12M', months: 12  },
  { id: 'all', label: 'All', months: 999 },
];

const ChartTooltip = ({ active, payload, label, provider, forecastLabel, forecastValue }: any) => {
  if (!active || !payload?.length) return null;
  const value: number = payload[0]?.value ?? 0;
  // The final category is the current, still-open month, so say so rather than
  // letting a part-month total read as a finished one.
  const isOpenMonth = forecastLabel != null && label === forecastLabel;
  return (
    <div className="bg-white border border-[rgba(0,0,0,0.1)] rounded-xl shadow-lg px-3.5 py-2.5 min-w-[130px]">
      <p className="text-[11px] font-semibold text-[rgba(0,0,0,0.45)] mb-1">{label}</p>
      <p className="text-[16px] font-bold text-[rgba(0,0,0,0.88)] tabular-nums">
        {fmtMoney(value, provider)}
      </p>
      {isOpenMonth && (
        <>
          <p className="text-[10px] text-[rgba(0,0,0,0.35)] mt-0.5">Month to date</p>
          <p className="text-[10.5px] font-semibold text-[#FF9900] tabular-nums mt-1 pt-1 border-t border-[rgba(0,0,0,0.06)]">
            {fmtMoney(forecastValue, provider)} projected
          </p>
        </>
      )}
    </div>
  );
};

const toNumber = (v: number | string | null | undefined): number => {
  if (v == null) return 0;
  const n = typeof v === 'number' ? v : Number.parseFloat(v);
  return Number.isFinite(n) ? n : 0;
};

const CostTrendSection: React.FC<CostTrendSectionProps> = ({
  costHistory, isLoading, provider, forecastedSpend, accountId,
}) => {
  const [slice, setSlice] = useState<Slice>('6m');
  const [budget, setBudget] = useState<{ name: string; limit: number } | null>(null);
  const fmt = (v: number) => fmtMoneyShort(v, provider);

  /**
   * Budget threshold. Account-wide budgets (no serviceName) win over per-service
   * ones; among equals the largest limit is used. Silent on failure — a missing
   * budget just means no reference line.
   *
   * AWS only, deliberately. This component is shared with the GCP and Azure
   * dashboards, and /api/xamops/finops/budgets is the AWS FinOps controller;
   * GCP budgets live under a different base path and Azure has no equivalent
   * verified here. Guarding on provider keeps a future caller from silently
   * pointing a GCP account id at the AWS endpoint.
   */
  useEffect(() => {
    if (!accountId || (provider ?? 'AWS') !== 'AWS') { setBudget(null); return; }
    let cancelled = false;
    apiClient
      .get<BudgetDetails[]>(`/api/xamops/finops/budgets?accountId=${encodeURIComponent(accountId)}`)
      .then(data => {
        if (cancelled) return;
        const list = Array.isArray(data) ? data : [];
        const candidates = list.filter(b => toNumber(b.budgetLimit) > 0);
        if (candidates.length === 0) { setBudget(null); return; }
        const accountWide = candidates.filter(b => !b.serviceName);
        const pool = accountWide.length > 0 ? accountWide : candidates;
        const best = pool.reduce((a, b) => (toNumber(b.budgetLimit) > toNumber(a.budgetLimit) ? b : a));
        setBudget({ name: best.budgetName || 'Budget', limit: toNumber(best.budgetLimit) });
      })
      .catch(() => { if (!cancelled) setBudget(null); });
    return () => { cancelled = true; };
  }, [accountId, provider]);

  const actuals = useMemo(() => {
    if (!costHistory?.labels?.length) return [];
    const maxN = SLICES.find(s => s.id === slice)!.months;
    const labels = costHistory.labels.slice(-maxN);
    const costs  = (costHistory.costs  || []).slice(-maxN);
    return labels.map((label, i) => ({ label, cost: costs[i] ?? 0 }));
  }, [costHistory, slice]);

  const hasForecast = !!forecastedSpend && forecastedSpend > 0 && actuals.length > 0;

  const chartData = actuals;

  /**
   * The forecast belongs at the last actual point, not after it.
   * FinOpsService.getCostHistory ends the series on the current, still-open month
   * (`labels.add(currentYm.atDay(1)...)`) with a month-to-date total, and
   * `forecastedSpend` is where that same month is projected to land. Plotting it
   * as an extra category would invent a month that isn't in the data.
   */
  const forecastAnchor = hasForecast ? actuals[actuals.length - 1].label : null;

  const total   = useMemo(() => actuals.reduce((s, d) => s + d.cost, 0), [actuals]);
  const maxCost = useMemo(() => Math.max(...actuals.map(d => d.cost), 0), [actuals]);
  const avgCost = actuals.length ? total / actuals.length : 0;

  /**
   * Month over month. When a forecast is available the final point is the current
   * open month, so comparing its month-to-date total against a full previous month
   * would report a fake drop that shrinks as the month progresses. In that case the
   * projected month-end is compared instead, which is like for like.
   */
  const trend = useMemo(() => {
    if (actuals.length < 2) return null;
    const prev = actuals[actuals.length - 2].cost;
    if (!prev) return null;
    const last = hasForecast ? (forecastedSpend as number) : actuals[actuals.length - 1].cost;
    return {
      diff: last - prev,
      pct: ((last - prev) / prev) * 100,
      projected: hasForecast,
    };
  }, [actuals, hasForecast, forecastedSpend]);

  // The actionable comparison: is the projection heading past the budget?
  const budgetStatus = useMemo(() => {
    if (!budget || !hasForecast) return null;
    const pct = (forecastedSpend as number) / budget.limit * 100;
    return { pct, over: pct > 100 };
  }, [budget, hasForecast, forecastedSpend]);

  // Recharts clips reference lines that fall outside the domain, so the axis has
  // to stretch to whichever of spend / forecast / budget is highest.
  const yMax = useMemo(() => {
    const ceiling = Math.max(maxCost, hasForecast ? (forecastedSpend as number) : 0, budget?.limit ?? 0);
    return ceiling > 0 ? Math.ceil(ceiling * 1.1) : 'auto';
  }, [maxCost, hasForecast, forecastedSpend, budget]);

  if (isLoading) {
    return (
      <div className="lg:col-span-3 bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-5 h-full animate-pulse">
        <div className="h-4 bg-[rgba(0,0,0,0.05)] rounded w-1/4 mb-4" />
        <div className="h-52 bg-[rgba(0,0,0,0.04)] rounded-lg" />
      </div>
    );
  }

  return (
    <div className="lg:col-span-3 bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-5 h-full flex flex-col widget-panel-hover">
      {/* Header */}
      <div className="flex items-center justify-between mb-3 flex-shrink-0">
        <div className="flex items-center gap-2">
          <div className="w-7 h-7 rounded-lg flex items-center justify-center"
            style={{ background: 'linear-gradient(135deg,#FF990020,#FF6B3514)' }}>
            <Icon icon="mdi:chart-areaspline" width={15} color="#FF9900" />
          </div>
          <div>
            <p className="text-[15px] font-semibold text-[rgba(0,0,0,0.88)]">Cost Trend</p>
            <p className="text-[11px] text-[rgba(0,0,0,0.4)]">Monthly spend history</p>
          </div>
        </div>

        <div className="flex items-center bg-[rgba(0,0,0,0.04)] rounded-lg p-0.5 gap-0.5">
          {SLICES.map(s => (
            <button key={s.id} onClick={() => setSlice(s.id)}
              className={`px-2.5 py-1 rounded-md text-[11px] font-medium transition-all ${
                slice === s.id
                  ? 'bg-white text-[rgba(0,0,0,0.88)] shadow-sm'
                  : 'text-[rgba(0,0,0,0.4)] hover:text-[rgba(0,0,0,0.7)]'
              }`}>{s.label}
            </button>
          ))}
        </div>
      </div>

      {/* KPIs */}
      <div className="flex items-end gap-5 mb-3 flex-shrink-0">
        <div>
          <p className="text-[10px] uppercase tracking-wider font-semibold text-[rgba(0,0,0,0.3)] mb-0.5">Total spend</p>
          <p className="text-[26px] font-bold text-[rgba(0,0,0,0.88)] leading-none tabular-nums">{fmt(total)}</p>
        </div>
        <div className="flex items-end gap-4 pb-0.5">
          <div>
            <p className="text-[10px] uppercase tracking-wider font-semibold text-[rgba(0,0,0,0.28)] mb-0.5">Avg / Mo</p>
            <p className="text-[14px] font-semibold text-[rgba(0,0,0,0.55)] tabular-nums">{fmt(avgCost)}</p>
          </div>
          <div>
            <p className="text-[10px] uppercase tracking-wider font-semibold text-[rgba(0,0,0,0.28)] mb-0.5">Peak</p>
            <p className="text-[14px] font-semibold text-[rgba(0,0,0,0.55)] tabular-nums">{fmt(maxCost)}</p>
          </div>
          {hasForecast && (
            <div>
              <p className="text-[10px] uppercase tracking-wider font-semibold text-[rgba(0,0,0,0.28)] mb-0.5">Projected</p>
              <p className="text-[14px] font-semibold text-[#FF9900] tabular-nums">{fmt(forecastedSpend as number)}</p>
            </div>
          )}
          {trend && (
            <div className={`flex items-center gap-1 px-2 py-0.5 rounded-full text-[11px] font-bold border ${
              trend.diff >= 0
                ? 'bg-red-50 text-red-500 border-red-100'
                : 'bg-green-50 text-green-600 border-green-100'
            }`}>
              <Icon icon={trend.diff >= 0 ? 'mdi:trending-up' : 'mdi:trending-down'} width={12} />
              {trend.diff >= 0 ? '+' : ''}{trend.pct.toFixed(1)}% MoM{trend.projected ? ' (proj)' : ''}
            </div>
          )}
          {budgetStatus && (
            <div className={`flex items-center gap-1 px-2 py-0.5 rounded-full text-[11px] font-bold border ${
              budgetStatus.over
                ? 'bg-red-50 text-red-500 border-red-100'
                : 'bg-green-50 text-green-600 border-green-100'
            }`}
              title={`Forecast ${fmt(forecastedSpend as number)} vs "${budget!.name}" budget ${fmt(budget!.limit)}`}>
              <Icon icon={budgetStatus.over ? 'mdi:alert-outline' : 'mdi:check-circle-outline'} width={12} />
              {budgetStatus.pct.toFixed(0)}% of budget
            </div>
          )}
        </div>
      </div>

      {/* Chart */}
      <div className="flex-1" style={{ minHeight: 160 }}>
        {chartData.length === 0 ? (
          <div className="h-full flex flex-col items-center justify-center gap-2">
            <Icon icon="mdi:chart-line" width={36} color="rgba(0,0,0,0.12)" />
            <p className="text-[12px] text-[rgba(0,0,0,0.35)]">No cost history available</p>
          </div>
        ) : (
          <ResponsiveContainer width="100%" height="100%">
            <AreaChart data={chartData} margin={{ top: 4, right: 4, left: -8, bottom: 0 }}>
              <defs>
                <linearGradient id="trendGrad" x1="0" y1="0" x2="0" y2="1">
                  <stop offset="5%"  stopColor="#FF9900" stopOpacity={0.18} />
                  <stop offset="95%" stopColor="#FF9900" stopOpacity={0.01} />
                </linearGradient>
              </defs>
              <CartesianGrid strokeDasharray="3 3" stroke="rgba(0,0,0,0.05)" vertical={false} />
              <XAxis dataKey="label" tick={{ fontSize: 11, fill: 'rgba(0,0,0,0.4)' }}
                axisLine={false} tickLine={false} />
              <YAxis tickFormatter={fmt} tick={{ fontSize: 10, fill: 'rgba(0,0,0,0.35)' }}
                axisLine={false} tickLine={false} width={50} domain={[0, yMax]} />
              <Tooltip content={
                <ChartTooltip provider={provider} forecastLabel={forecastAnchor} forecastValue={forecastedSpend} />
              } />
              {avgCost > 0 && (
                <ReferenceLine y={avgCost} stroke="rgba(0,0,0,0.15)" strokeDasharray="4 3"
                  label={{ value: 'avg', position: 'insideTopRight', fontSize: 9, fill: 'rgba(0,0,0,0.3)' }} />
              )}
              {budget && (
                <ReferenceLine y={budget.limit} stroke="#dc2626" strokeDasharray="5 4" strokeOpacity={0.7}
                  label={{ value: 'budget', position: 'insideBottomRight', fontSize: 9, fill: '#dc2626' }} />
              )}
              <Area type="monotone" dataKey="cost" stroke="#FF9900" strokeWidth={2.5}
                fill="url(#trendGrad)"
                dot={{ r: 3, fill: '#FF9900', stroke: '#fff', strokeWidth: 2 }}
                activeDot={{ r: 5, fill: '#FF9900', stroke: '#fff', strokeWidth: 2 }} />
              {forecastAnchor && (
                <ReferenceDot x={forecastAnchor} y={forecastedSpend as number} r={4}
                  fill="#fff" stroke="#FF9900" strokeWidth={2}
                  label={{ value: 'proj', position: 'right', fontSize: 9, fill: '#FF9900' }} />
              )}
            </AreaChart>
          </ResponsiveContainer>
        )}
      </div>
    </div>
  );
};

export default CostTrendSection;
