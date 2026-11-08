import React from 'react';
import { Icon } from '@iconify/react';
import { InfoTooltip } from '../../ui/Tooltip';
import type { DatastoreKpis } from '../../../types/dspmDatastores';
import { kpiCardTrend, kpiCardValue, type KpiCardDef } from './datastoreTabConfig';
import { trendBadge, type TrendBadge } from './datastoreFormat';

/**
 * Badge colours follow the console: rising sensitive records are bad (red ▲), falling good (green ▼);
 * the asset-count delta is always blue, with a diamond when rising.
 */
const Badge: React.FC<{ badge: TrendBadge; kind: 'percent' | 'delta' }> = ({ badge, kind }) => {
  const up = badge.direction === 'up';
  const tone = kind === 'delta'
    ? 'bg-sky-50 text-sky-700'
    : up ? 'bg-rose-50 text-rose-700' : 'bg-emerald-50 text-emerald-700';
  const icon = kind === 'delta'
    ? (up ? 'mdi:rhombus' : 'mdi:triangle-down')
    : (up ? 'mdi:triangle' : 'mdi:triangle-down');
  return (
    <span className={`inline-flex items-center gap-1 rounded-full px-1.5 py-0.5 text-[10px] font-semibold tabular-nums ${tone}`}>
      <Icon icon={icon} width={9} />
      {badge.text}
    </span>
  );
};

interface Props {
  cards: KpiCardDef[];
  /** The active tab's KPIs, or null while they load or after they failed. */
  kpis: DatastoreKpis | null;
}

/** The tab's KPI cards. Values are per tab only: search and filters never change them. */
const DatastoreKpiStrip: React.FC<Props> = ({ cards, kpis }) => (
  <div className="grid grid-cols-2 gap-3 md:grid-cols-3 xl:grid-cols-6">
    {cards.map(card => {
      const value = kpis ? kpiCardValue(card.id, kpis) : null;
      const trend = kpis && card.badge ? kpiCardTrend(card.id, kpis) : null;
      const badge = trend && card.badge ? trendBadge(trend, card.badge) : null;
      return (
        <div key={card.id} data-kpi={card.id} className="rounded-xl border border-[rgba(0,0,0,0.08)] bg-white px-4 py-3">
          <div className="flex items-center gap-1.5 text-[11px] font-semibold text-[rgba(0,0,0,0.5)]">
            <span>{card.label}</span>
            <InfoTooltip>{card.tooltip}</InfoTooltip>
          </div>
          <div className="mt-1.5 flex flex-wrap items-center gap-2">
            <span className="text-xl font-bold tabular-nums text-[rgba(0,0,0,0.88)]">
              {value === null ? '–' : value.toLocaleString('en-US')}
            </span>
            {badge && card.badge && <Badge badge={badge} kind={card.badge} />}
          </div>
        </div>
      );
    })}
  </div>
);

export default DatastoreKpiStrip;
