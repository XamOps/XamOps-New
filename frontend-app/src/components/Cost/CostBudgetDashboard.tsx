/**
 * Cost Budget Dashboard
 *
 * AWS-style budget management UI matching the provided screenshots.
 * Features:
 * - Monthly bar chart (green = within budget, red = over budget)
 * - Summary panel (total budget, actual, remaining)
 * - Searchable budget table with progress bars
 * - Account/Service filter toggles
 * - Trigger % column with tooltip
 * - Actions menu (delete)
 */

import React, { useState, useMemo, useRef, useEffect } from 'react';
import { Icon } from '@iconify/react';
import type { Budget } from '../../types/finops';
import type { CostDataItem } from '../../types/cost';

interface CostBudgetDashboardProps {
  budgets: Budget[];
  costData: CostDataItem[];
  /**
   * Real month-by-month spend, aligned as `labels[i]` → `costs[i]`.
   *
   * Past months used to be generated with `Math.random()` scaled around the budget, so the
   * chart showed four plausible-looking bars that were pure invention. When this is absent
   * those months now render as "no data" instead.
   */
  costHistory?: { labels: string[]; costs: number[] };
  /** ISO-4217 code for both budget limits and spend. Defaults to USD. */
  currency?: string;
  onCreateBudget: () => void;
  onDeleteBudget: (budgetName: string) => void;
}

/** Renders an amount in the dashboard's currency. */
const currencySymbol = (currency: string): string => {
  switch (currency.toUpperCase()) {
    case 'INR': return '₹';
    case 'EUR': return '€';
    case 'GBP': return '£';
    default: return '$';
  }
};

type FilterMode = 'all' | 'account' | 'service';

interface MonthData {
  label: string;
  budget: number;
  spent: number;
  withinBudget: number;
  overBudget: number;
  isCurrent: boolean;
  /** False when no spend was recorded for this past month, so the bar must not be drawn. */
  hasData: boolean;
}

const CostBudgetDashboard: React.FC<CostBudgetDashboardProps> = ({
  budgets,
  costData,
  costHistory,
  currency = 'USD',
  onCreateBudget,
  onDeleteBudget,
}) => {
  const symbol = currencySymbol(currency);
  const [searchQuery, setSearchQuery] = useState('');
  const [filterMode, setFilterMode] = useState<FilterMode>('all');
  const [serviceFilter, setServiceFilter] = useState<string | null>(null);
  const [showServiceDropdown, setShowServiceDropdown] = useState(false);
  const [hoveredBar, setHoveredBar] = useState<number | null>(null);
  const [hoveredRow, setHoveredRow] = useState<number | null>(null);
  const [activeMenu, setActiveMenu] = useState<string | null>(null);
  const serviceDropdownRef = useRef<HTMLDivElement>(null);
  const menuRef = useRef<HTMLDivElement>(null);

  // Close dropdowns on outside click
  useEffect(() => {
    const handleClickOutside = (e: MouseEvent) => {
      if (serviceDropdownRef.current && !serviceDropdownRef.current.contains(e.target as Node)) {
        setShowServiceDropdown(false);
      }
      if (menuRef.current && !menuRef.current.contains(e.target as Node)) {
        setActiveMenu(null);
      }
    };
    document.addEventListener('mousedown', handleClickOutside);
    return () => document.removeEventListener('mousedown', handleClickOutside);
  }, []);

  // Compute total budget, actual spend, remaining
  // Use costData (already fetched for Cost Breakdown) instead of budget.actualSpend to avoid extra API calls
  const totalCostFromData = useMemo(() => costData.reduce((sum, item) => sum + item.amount, 0), [costData]);
  const totalBudget = useMemo(() => budgets.reduce((sum, b) => sum + b.budgetLimit, 0), [budgets]);
  const totalActual = totalCostFromData;
  const totalRemaining = totalBudget - totalActual;

  // Month label → real spend, for the chart below.
  const historyByLabel = useMemo(() => {
    const map = new Map<string, number>();
    const labels = costHistory?.labels ?? [];
    const costs = costHistory?.costs ?? [];
    labels.forEach((label, i) => {
      if (typeof costs[i] === 'number') {
        map.set(label, costs[i]);
      }
    });
    return map;
  }, [costHistory]);

  // Helper: get actual spend for a budget using costData
  const getActualSpend = (budget: Budget): number => {
    if (budget.serviceName) {
      // Find the matching service in costData
      const match = costData.find(item => item.name === budget.serviceName);
      return match ? match.amount : 0;
    }
    // Account-level budget: use total cost
    return totalCostFromData;
  };

  // Current month label
  const currentMonthLabel = useMemo(() => {
    const now = new Date();
    return now.toLocaleDateString('en-US', { month: 'short', year: 'numeric' });
  }, []);

  // Generate monthly chart data (past 4 months + current + future 5 months)
  const monthlyData = useMemo((): MonthData[] => {
    const now = new Date();
    const months: MonthData[] = [];
    for (let i = -4; i <= 5; i++) {
      const d = new Date(now.getFullYear(), now.getMonth() + i, 1);
      const label = d.toLocaleDateString('en-US', { month: 'short', year: 'numeric' });
      const isCurrent = i === 0;
      const isPast = i < 0;

      if (isCurrent) {
        const withinBudget = Math.min(totalActual, totalBudget);
        const overBudget = Math.max(0, totalActual - totalBudget);
        months.push({
          label, budget: totalBudget, spent: totalActual,
          withinBudget, overBudget, isCurrent: true, hasData: true,
        });
      } else if (isPast) {
        // Real history only. A month with no recorded spend is reported as having no data
        // rather than being filled in with a plausible-looking invention.
        const spent = historyByLabel.get(label);
        if (spent === undefined) {
          months.push({
            label, budget: totalBudget, spent: 0,
            withinBudget: 0, overBudget: 0, isCurrent: false, hasData: false,
          });
        } else {
          months.push({
            label, budget: totalBudget, spent,
            withinBudget: Math.min(spent, totalBudget),
            overBudget: Math.max(0, spent - totalBudget),
            isCurrent: false, hasData: true,
          });
        }
      } else {
        // Future months carry no spend yet, which is a fact rather than missing data.
        months.push({
          label, budget: totalBudget, spent: 0,
          withinBudget: 0, overBudget: 0, isCurrent: false, hasData: true,
        });
      }
    }
    return months;
  }, [totalBudget, totalActual, historyByLabel]);

  // Available services from cost data
  const availableServices = useMemo(() => {
    const serviceMap = new Map<string, number>();
    costData.forEach(item => {
      serviceMap.set(item.name, (serviceMap.get(item.name) || 0) + 1);
    });
    return Array.from(serviceMap.entries()).sort((a, b) => a[0].localeCompare(b[0]));
  }, [costData]);

  // Filter budgets
  const filteredBudgets = useMemo(() => {
    let result = [...budgets];
    if (searchQuery) {
      const q = searchQuery.toLowerCase();
      result = result.filter(b => b.budgetName.toLowerCase().includes(q));
    }
    if (serviceFilter) {
      result = result.filter(b => b.budgetName.toLowerCase().includes(serviceFilter.toLowerCase()));
    }
    // Sort by percentage spent descending
    result.sort((a, b) => {
      const pctA = a.budgetLimit > 0 ? (getActualSpend(a) / a.budgetLimit) : 0;
      const pctB = b.budgetLimit > 0 ? (getActualSpend(b) / b.budgetLimit) : 0;
      return pctB - pctA;
    });
    return result;
  }, [budgets, searchQuery, serviceFilter]);

  // Format currency. The symbol follows the account's currency: hardcoding "$" put a dollar
  // sign on INR budget limits and compared them against INR spend as if both were USD.
  const fmt = (value: number): string => {
    if (value >= 1000) {
      return symbol + value.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
    }
    return symbol + value.toFixed(2);
  };

  const fmtShort = (value: number): string => {
    if (value >= 1000) return symbol + (value / 1000).toFixed(1) + 'K';
    return symbol + value.toFixed(0);
  };

  // Chart dimensions
  const chartMaxValue = useMemo(() => {
    const maxSpent = Math.max(...monthlyData.map(m => m.spent), totalBudget);
    return Math.ceil(maxSpent / 1000) * 1000 + 2000;
  }, [monthlyData, totalBudget]);

  // If no budgets, show empty state
  if (!budgets || budgets.length === 0) {
    return (
      <div className="bg-white border border-[rgba(0,0,0,0.08)] rounded-xl">
        <div className="text-center py-16">
          <div className="w-14 h-14 bg-[rgba(0,0,0,0.03)] rounded-full flex items-center justify-center mx-auto mb-4">
            <Icon icon="mdi:wallet-outline" width={28} color="rgba(0,0,0,0.25)" />
          </div>
          <p className="text-[15px] font-semibold text-[rgba(0,0,0,0.85)] mb-1">No Budgets Found</p>
          <p className="text-[13px] text-[rgba(0,0,0,0.45)] mb-5">
            Set up budgets to track and control your cloud spending
          </p>
          <button
            onClick={onCreateBudget}
            className="px-5 py-2.5 rounded-lg text-[13px] font-semibold transition-colors inline-flex items-center gap-2"
            style={{ backgroundColor: '#1BE68B', color: '#000' }}
          >
            <Icon icon="mdi:plus" width={16} />
            Create Your First Budget
          </button>
        </div>
      </div>
    );
  }

  return (
    <div className="bg-white border border-[rgba(0,0,0,0.08)] rounded-xl overflow-hidden">
      {/* ─── Top: Chart + Summary Panel ─── */}
      <div className="flex border-b border-[rgba(0,0,0,0.06)]">
        {/* Bar Chart */}
        <div className="flex-1 p-5 pb-4">
          {/* Y-axis labels + bars */}
          <div className="flex">
            {/* Y-axis */}
            <div className="flex flex-col justify-between text-[10px] text-[rgba(0,0,0,0.4)] font-medium pr-2 h-[130px]">
              <span>{fmtShort(chartMaxValue)}</span>
              <span>{fmtShort(chartMaxValue * 0.66)}</span>
              <span>{fmtShort(chartMaxValue * 0.33)}</span>
              <span>{symbol}0</span>
            </div>

            {/* Bars */}
            <div className="flex-1 flex items-end gap-1 h-[130px] border-b border-l border-[rgba(0,0,0,0.06)] pl-1 relative">
              {monthlyData.map((month, idx) => {
                const CHART_H = 130; // matches the h-[130px] plot area
                const totalPx = month.spent > 0 ? (month.spent / chartMaxValue) * CHART_H : 0;
                const greenPx = month.withinBudget > 0 ? (month.withinBudget / chartMaxValue) * CHART_H : 0;
                const redPx = month.overBudget > 0 ? (month.overBudget / chartMaxValue) * CHART_H : 0;
                // A past month with no recorded spend is unknown, not zero. Rendering it the
                // same way as a future month keeps the chart honest instead of implying spend.
                const isFuture = month.spent === 0 && !month.isCurrent;
                const isUnknown = !month.hasData;

                return (
                  <div
                    key={idx}
                    className="flex-1 h-full flex flex-col justify-end items-center relative"
                    onMouseEnter={() => setHoveredBar(idx)}
                    onMouseLeave={() => setHoveredBar(null)}
                  >
                    {/* Value label on top */}
                    {month.spent > 0 && !isUnknown && (
                      <span className="text-[9px] font-semibold text-[rgba(0,0,0,0.5)] mb-0.5 whitespace-nowrap">
                        {fmtShort(month.spent)}
                      </span>
                    )}
                    {/* Bar */}
                    <div className="w-full flex flex-col justify-end" style={{ height: (isFuture || isUnknown) ? '100%' : `${Math.max(totalPx, 4)}px` }}>
                      {(isFuture || isUnknown) ? (
                        <div className="w-full h-full rounded-t-sm bg-[rgba(0,0,0,0.03)] border border-dashed border-[rgba(0,0,0,0.08)]" />
                      ) : (
                        <>
                          {redPx > 0 && (
                            <div className="w-full rounded-t-sm" style={{ height: `${Math.max(redPx, 3)}px`, backgroundColor: '#F87171' }} />
                          )}
                          {greenPx > 0 && (
                            <div className="w-full" style={{ height: `${Math.max(greenPx, 3)}px`, backgroundColor: '#1BE68B', borderRadius: redPx > 0 ? '0' : '3px 3px 0 0' }} />
                          )}
                        </>
                      )}
                    </div>

                    {/* Month label */}
                    <span className={`absolute top-full mt-1.5 text-[9px] whitespace-nowrap ${month.isCurrent ? 'font-bold text-black' : 'text-[rgba(0,0,0,0.4)]'}`}>
                      {month.label}
                    </span>

                    {/* Tooltip on hover */}
                    {hoveredBar === idx && isUnknown && (
                      <div className="absolute bottom-full mb-2 left-1/2 -translate-x-1/2 bg-white border border-[rgba(0,0,0,0.12)] rounded-lg shadow-lg p-3 z-50 min-w-[160px]">
                        <p className="text-[11px] font-bold text-black mb-1">{month.label}</p>
                        <p className="text-[11px] text-[rgba(0,0,0,0.5)]">No spend recorded for this month.</p>
                      </div>
                    )}
                    {hoveredBar === idx && month.spent > 0 && !isUnknown && (
                      <div className="absolute bottom-full mb-2 left-1/2 -translate-x-1/2 bg-white border border-[rgba(0,0,0,0.12)] rounded-lg shadow-lg p-3 z-50 min-w-[180px]">
                        <p className="text-[11px] font-bold text-black mb-2">{month.label}</p>
                        <div className="space-y-1.5 text-[11px]">
                          <div className="flex justify-between"><span className="text-[rgba(0,0,0,0.5)]">Budget</span><span className="font-semibold">{fmt(month.budget)}</span></div>
                          <div className="flex justify-between"><span className="text-[rgba(0,0,0,0.5)]">Spent</span><span className="font-semibold">{fmt(month.spent)}</span></div>
                          <div className="flex justify-between items-center">
                            <span className="flex items-center gap-1"><span className="w-2 h-2 rounded-sm bg-[#1BE68B] inline-block" />Within budget</span>
                            <span className="font-semibold">{fmt(month.withinBudget)}</span>
                          </div>
                          {month.overBudget > 0 && (
                            <div className="flex justify-between items-center">
                              <span className="flex items-center gap-1"><span className="w-2 h-2 rounded-sm bg-[#F87171] inline-block" />Over budget</span>
                              <span className="font-semibold">{fmt(month.overBudget)}</span>
                            </div>
                          )}
                        </div>
                      </div>
                    )}
                  </div>
                );
              })}
            </div>
          </div>
        </div>

        {/* Summary Panel (right side) */}
        <div className="w-[240px] border-l border-[rgba(0,0,0,0.06)] p-5 flex flex-col justify-center">
          <p className="text-[12px] font-semibold text-[rgba(0,0,0,0.6)] mb-0.5">{currentMonthLabel}</p>
          <div className="mb-4">
            <p className="text-[10px] uppercase tracking-wider text-[rgba(0,0,0,0.4)] flex items-center gap-1.5 mb-1">
              <span className="w-2 h-2 rounded-full bg-[rgba(0,0,0,0.15)] inline-block" />
              TOTAL BUDGET
            </p>
            <p className="text-[28px] font-bold text-black leading-none">
              {symbol}{Math.floor(totalBudget).toLocaleString()}<span className="text-[16px] text-[rgba(0,0,0,0.4)]">.{String(Math.round((totalBudget % 1) * 100)).padStart(2, '0')}</span>
            </p>
          </div>
          <div className="flex gap-6">
            <div>
              <p className="text-[10px] uppercase tracking-wider text-[rgba(0,0,0,0.4)] flex items-center gap-1.5 mb-1">
                <span className="w-2 h-2 rounded-full bg-[#1BE68B] inline-block" />
                ACTUAL
              </p>
              <p className="text-[18px] font-bold text-black leading-none">
                ${Math.floor(totalActual).toLocaleString()}<span className="text-[12px] text-[rgba(0,0,0,0.4)]">.{String(Math.round((totalActual % 1) * 100)).padStart(2, '0')}</span>
              </p>
            </div>
            <div>
              <p className="text-[10px] uppercase tracking-wider text-[rgba(0,0,0,0.4)] mb-1">REMAINING</p>
              <p className="text-[18px] font-bold text-black leading-none">
                ${Math.floor(Math.max(0, totalRemaining)).toLocaleString()}<span className="text-[12px] text-[rgba(0,0,0,0.4)]">.{String(Math.round((Math.abs(totalRemaining) % 1) * 100)).padStart(2, '0')}</span>
              </p>
            </div>
          </div>
        </div>
      </div>

      {/* ─── Filter Bar ─── */}
      <div className="flex items-center gap-3 px-5 py-3 border-b border-[rgba(0,0,0,0.06)]">
        {/* Search */}
        <div className="relative">
          <Icon icon="mdi:magnify" width={14} className="absolute left-2.5 top-1/2 -translate-y-1/2 text-[rgba(0,0,0,0.3)]" />
          <input
            type="text"
            placeholder="Search budgets..."
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            className="pl-8 pr-3 py-1.5 text-[12px] border border-[rgba(0,0,0,0.1)] rounded-md w-[140px] focus:outline-none focus:border-[rgba(0,0,0,0.25)] bg-transparent"
          />
        </div>

        {/* Account filter toggle */}
        <button
          onClick={() => setFilterMode(filterMode === 'account' ? 'all' : 'account')}
          className={`flex items-center gap-1.5 px-3 py-1.5 rounded-full text-[11px] font-medium border transition-colors ${
            filterMode === 'account' ? 'border-black bg-[rgba(0,0,0,0.04)]' : 'border-[rgba(0,0,0,0.1)] hover:border-[rgba(0,0,0,0.2)]'
          }`}
        >
          <Icon icon="mdi:circle-outline" width={12} />
          Account
        </button>

        {/* Service filter toggle */}
        <div className="relative" ref={serviceDropdownRef}>
          <button
            onClick={() => setShowServiceDropdown(!showServiceDropdown)}
            className={`flex items-center gap-1.5 px-3 py-1.5 rounded-full text-[11px] font-medium border transition-colors ${
              filterMode === 'service' || serviceFilter ? 'border-black bg-[rgba(0,0,0,0.04)]' : 'border-[rgba(0,0,0,0.1)] hover:border-[rgba(0,0,0,0.2)]'
            }`}
          >
            <Icon icon="mdi:circle-outline" width={12} />
            Service
          </button>

          {/* Service dropdown */}
          {showServiceDropdown && (
            <div className="absolute top-full left-0 mt-1 w-[280px] bg-white border border-[rgba(0,0,0,0.12)] rounded-lg shadow-xl z-50 max-h-[300px] overflow-y-auto">
              <div className="p-2 border-b border-[rgba(0,0,0,0.06)]">
                <input
                  type="text"
                  placeholder="Service"
                  className="w-full px-2.5 py-1.5 text-[12px] border border-[rgba(0,0,0,0.1)] rounded-md focus:outline-none"
                  autoFocus
                />
              </div>
              <div className="py-1">
                {availableServices.map(([name, count]) => (
                  <button
                    key={name}
                    onClick={() => {
                      setServiceFilter(serviceFilter === name ? null : name);
                      setFilterMode('service');
                      setShowServiceDropdown(false);
                    }}
                    className={`w-full text-left px-3 py-2 text-[12px] hover:bg-[rgba(0,0,0,0.03)] flex items-center justify-between ${
                      serviceFilter === name ? 'text-[#1BE68B] font-semibold' : 'text-[rgba(0,0,0,0.8)]'
                    }`}
                  >
                    <span className="truncate">{name}</span>
                    <span className="text-[10px] text-[rgba(0,0,0,0.35)] ml-2 flex-shrink-0">{count}</span>
                  </button>
                ))}
              </div>
            </div>
          )}
        </div>

        <div className="flex-1" />

        {/* Columns button */}
        <button className="flex items-center gap-1.5 px-3 py-1.5 text-[11px] font-medium text-[rgba(0,0,0,0.5)] hover:text-black transition-colors">
          <Icon icon="mdi:view-column-outline" width={14} />
          Columns
        </button>

        {/* Create Budget button */}
        <button
          onClick={onCreateBudget}
          className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-[11px] font-semibold transition-colors"
          style={{ backgroundColor: '#1BE68B', color: '#000' }}
        >
          <Icon icon="mdi:plus" width={14} />
          Create Budget
        </button>
      </div>

      {/* ─── Table Header ─── */}
      <div className="grid grid-cols-[1fr_120px_1fr_80px_40px] items-center px-5 py-2.5 border-b border-[rgba(0,0,0,0.06)] text-[11px] uppercase tracking-wider font-semibold text-[rgba(0,0,0,0.45)]">
        <div className="flex items-center gap-1">
          Account & Service
          <Icon icon="mdi:unfold-more-horizontal" width={12} className="text-[rgba(0,0,0,0.3)]" />
        </div>
        <div className="flex items-center gap-1">
          Budget
          <Icon icon="mdi:unfold-more-horizontal" width={12} className="text-[rgba(0,0,0,0.3)]" />
        </div>
        <div className="flex items-center gap-1">
          Progress
          <Icon icon="mdi:arrow-down" width={12} className="text-[rgba(0,0,0,0.3)]" />
        </div>
        <div className="flex items-center gap-1">
          Trigger
          <Icon icon="mdi:unfold-more-horizontal" width={12} className="text-[rgba(0,0,0,0.3)]" />
        </div>
        <div />
      </div>

      {/* ─── Table Rows ─── */}
      <div className="divide-y divide-[rgba(0,0,0,0.04)]">
        {filteredBudgets.map((budget, idx) => {
          const actual = getActualSpend(budget);
          const percentage = budget.budgetLimit > 0 ? Math.min((actual / budget.budgetLimit) * 100, 100) : 0;
          const remaining = Math.max(0, budget.budgetLimit - actual);
          const triggerPct = 85; // Default trigger threshold
          const triggerAmount = budget.budgetLimit * (triggerPct / 100);

          return (
            <div
              key={budget.budgetName}
              className={`grid grid-cols-[1fr_120px_1fr_80px_40px] items-center px-5 py-4 transition-colors ${
                hoveredRow === idx ? 'bg-[rgba(0,0,0,0.015)]' : ''
              }`}
              onMouseEnter={() => setHoveredRow(idx)}
              onMouseLeave={() => setHoveredRow(null)}
            >
              {/* Account & Service */}
              <div>
                <p className="text-[10px] text-[rgba(0,0,0,0.4)] flex items-center gap-1 mb-0.5">
                  <Icon icon="mdi:cloud-outline" width={10} />
                  Sample Account
                </p>
                <p className="text-[13px] font-medium text-black">
                  {budget.serviceName || budget.budgetName}
                </p>
              </div>

              {/* Budget */}
              <div className="text-[13px] font-medium text-[rgba(0,0,0,0.7)]">
                {fmt(budget.budgetLimit)}
              </div>

              {/* Progress */}
              <div className="pr-4">
                <div className="flex justify-between text-[10px] mb-1.5">
                  <div>
                    <span className="uppercase tracking-wider text-[rgba(0,0,0,0.4)]">ACTUAL</span>
                    <span className="ml-1.5 font-bold text-black">{fmt(actual)}</span>
                  </div>
                  <div>
                    <span className="uppercase tracking-wider text-[rgba(0,0,0,0.4)]">REMAINING</span>
                    <span className="ml-1.5 font-bold text-black">{fmt(remaining)}</span>
                  </div>
                </div>
                {/* Progress bar with trigger marker */}
                <div className="relative group">
                  <div className="h-[6px] w-full rounded-full bg-[rgba(0,0,0,0.06)] overflow-hidden">
                    <div
                      className="h-full rounded-full transition-all duration-500"
                      style={{
                        width: `${percentage}%`,
                        backgroundColor: percentage >= 85 ? '#F87171' : '#1BE68B',
                      }}
                    />
                  </div>
                  {/* Trigger marker */}
                  <div
                    className="absolute top-0 h-[6px] w-[2px] bg-[rgba(0,0,0,0.3)]"
                    style={{ left: `${triggerPct}%` }}
                  />
                  {/* Trigger tooltip */}
                  <div className="absolute -top-8 opacity-0 group-hover:opacity-100 transition-opacity bg-[rgba(0,0,0,0.85)] text-white text-[10px] font-medium px-2 py-1 rounded whitespace-nowrap pointer-events-none"
                    style={{ left: `${triggerPct}%`, transform: 'translateX(-50%)' }}
                  >
                    TRIGGER {fmt(triggerAmount)} ({triggerPct}%)
                  </div>
                </div>
              </div>

              {/* Trigger */}
              <div className="text-[12px] font-medium text-[rgba(0,0,0,0.6)]">
                {triggerPct}%
              </div>

              {/* Actions menu */}
              <div className="relative flex justify-end" ref={activeMenu === budget.budgetName ? menuRef : undefined}>
                <button
                  onClick={() => setActiveMenu(activeMenu === budget.budgetName ? null : budget.budgetName)}
                  className="w-6 h-6 flex items-center justify-center rounded hover:bg-[rgba(0,0,0,0.05)] text-[rgba(0,0,0,0.4)]"
                >
                  <Icon icon="mdi:dots-vertical" width={16} />
                </button>
                {activeMenu === budget.budgetName && (
                  <div className="absolute right-0 top-full mt-1 w-[120px] bg-white border border-[rgba(0,0,0,0.1)] rounded-lg shadow-lg z-50 py-1">
                    <button
                      onClick={() => {
                        onDeleteBudget(budget.budgetName);
                        setActiveMenu(null);
                      }}
                      className="w-full text-left px-3 py-2 text-[12px] text-red-600 hover:bg-red-50 flex items-center gap-2"
                    >
                      <Icon icon="mdi:trash-can-outline" width={14} />
                      Delete
                    </button>
                  </div>
                )}
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
};

export default CostBudgetDashboard;
