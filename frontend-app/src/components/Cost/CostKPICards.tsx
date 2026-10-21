import React from 'react';
import { Icon } from '@iconify/react';
import type { CostDataItem, CostFilters } from '../../types/cost';
import { useAnimatedValue } from '../CloudK8s/shared/useAnimatedValue';

interface CostKPICardsProps {
  costData: CostDataItem[];
  filters: CostFilters;
  isLoading: boolean;
  labelFilterActive?: boolean;
}

const CostKPICards: React.FC<CostKPICardsProps> = ({ costData, filters, isLoading, labelFilterActive }) => {
  const totalCost = costData.reduce((sum, item) => sum + item.amount, 0);
  const sortedData = [...costData].sort((a, b) => b.amount - a.amount);
  const topSpender = sortedData.length > 0 ? sortedData[0] : null;

  const animatedTotal = useAnimatedValue(totalCost);
  const animatedTop = useAnimatedValue(topSpender?.amount ?? 0);

  if (isLoading) {
    return (
      <div className="bg-white border border-[rgba(0,0,0,0.08)] rounded-xl overflow-hidden">
        <div className="grid grid-cols-3 divide-x divide-[rgba(0,0,0,0.06)]">
          {[1, 2, 3].map(i => (
            <div key={i} className="p-5 animate-pulse">
              <div className="h-2.5 w-20 bg-[rgba(0,0,0,0.06)] rounded mb-3" />
              <div className="h-7 w-28 bg-[rgba(0,0,0,0.06)] rounded mb-2" />
              <div className="h-2 w-32 bg-[rgba(0,0,0,0.04)] rounded" />
            </div>
          ))}
        </div>
      </div>
    );
  }

  const formatMain = (val: number) => '$' + Math.floor(val).toLocaleString();
  const formatCents = (val: number) => '.' + String(Math.round((val % 1) * 100)).padStart(2, '0');

  return (
    <div className="bg-white border border-[rgba(0,0,0,0.08)] rounded-xl overflow-hidden">
      <div className="grid grid-cols-1 md:grid-cols-3 divide-x divide-[rgba(0,0,0,0.06)]">

        {/* Total Cost */}
        <div className="p-5 flex items-start gap-3">
          <div className="w-9 h-9 rounded-lg bg-gradient-to-br from-emerald-50 to-emerald-100 border border-emerald-200/50 flex items-center justify-center flex-shrink-0 mt-0.5">
            <Icon icon="mdi:currency-usd" width={20} className="text-emerald-600" />
          </div>
          <div className="min-w-0">
            <p className="text-[10px] uppercase tracking-wider font-semibold text-[rgba(0,0,0,0.45)] mb-1.5">
              TOTAL COST
            </p>
            <p className="text-[26px] font-bold text-black leading-none mb-1.5">
              {formatMain(animatedTotal)}
              <span className="text-[14px] text-[rgba(0,0,0,0.35)]">{formatCents(animatedTotal)}</span>
            </p>
            <p className="text-[11px] text-[rgba(0,0,0,0.35)] truncate">
              {filters.startDate} → {filters.endDate}{labelFilterActive ? ' · Label filtered' : ''}
            </p>
          </div>
        </div>

        {/* Categories */}
        <div className="p-5 flex items-start gap-3">
          <div className="w-9 h-9 rounded-lg bg-gradient-to-br from-violet-50 to-violet-100 border border-violet-200/50 flex items-center justify-center flex-shrink-0 mt-0.5">
            <Icon icon="mdi:layers-outline" width={20} className="text-violet-600" />
          </div>
          <div className="min-w-0">
            <p className="text-[10px] uppercase tracking-wider font-semibold text-[rgba(0,0,0,0.45)] mb-1.5">
              CATEGORIES
            </p>
            <p className="text-[26px] font-bold text-black leading-none mb-1.5">
              {costData.length}
            </p>
            <p className="text-[11px] text-[rgba(0,0,0,0.35)]">
              Grouped by {filters.groupBy.toLowerCase()}
            </p>
          </div>
        </div>

        {/* Top Spender */}
        <div className="p-5 flex items-start gap-3">
          <div className="w-9 h-9 rounded-lg bg-gradient-to-br from-amber-50 to-amber-100 border border-amber-200/50 flex items-center justify-center flex-shrink-0 mt-0.5">
            <Icon icon="mdi:fire" width={20} className="text-amber-600" />
          </div>
          <div className="min-w-0">
            <p className="text-[10px] uppercase tracking-wider font-semibold text-[rgba(0,0,0,0.45)] mb-1.5">
              TOP SPENDER
            </p>
            <p className="text-[26px] font-bold text-black leading-none mb-1.5">
              {formatMain(animatedTop)}
              <span className="text-[14px] text-[rgba(0,0,0,0.35)]">{formatCents(animatedTop)}</span>
            </p>
            <p className="text-[11px] text-[rgba(0,0,0,0.35)] truncate">
              {topSpender?.name ?? 'No data'}
            </p>
          </div>
        </div>

      </div>
    </div>
  );
};

export default CostKPICards;
