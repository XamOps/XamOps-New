import React, { useId, useState } from 'react';
import { Link } from 'react-router-dom';
import { Icon } from '@iconify/react';
import type { DatastoreRow } from '../../../types/dspmDatastores';
import type { TopRiskyTab } from '../../../types/dspmPages';
import { DspmProviderIcon } from '../DspmIcons';
import { datastoreIconKey } from '../datastores/datastoreVisuals';
import { canOpenDrawer } from '../datastores/DatastoreDrawerHost';
import { DrawerTabList } from '../datastores/drawer/DrawerTabList';
import { TH } from '../datastores/drawer/TabParts';
import { LabelChips } from '../datastores/ui/Chips';
import { InfoIcon } from './DashboardParts';
import { TOP_RISKY_TABS, TOP_RISKY_TOOLTIP, datastoresSlug } from './dashboardModel';

interface Props {
  topRisky: Record<TopRiskyTab, DatastoreRow[]>;
  /** Opens the row's drawer; called only for rows `canOpenDrawer` admits. */
  onOpen: (row: DatastoreRow) => void;
}

/**
 * "Top Risky Datastores": per tab (All and the four environments) the riskiest datastores with sensitive
 * records, as the backend picked them. "View All" opens the tab's Datastores page (not on All). A row
 * with a valid source id is focusable and opens its drawer on click or Enter.
 */
const TopRiskyDatastores: React.FC<Props> = ({ topRisky, onOpen }) => {
  const [tab, setTab] = useState<TopRiskyTab>('all');
  const ids = useId();
  const rows = topRisky[tab] ?? [];
  return (
    <div className="rounded-2xl border border-[rgba(0,0,0,0.08)] bg-white">
      <div className="flex items-center justify-between gap-3 px-4 pt-4">
        <div className="flex items-center gap-1.5">
          <h3 className="text-xs font-semibold text-[rgba(0,0,0,0.7)]">Top Risky Datastores</h3>
          <InfoIcon text={TOP_RISKY_TOOLTIP} />
        </div>
        {tab !== 'all' && (
          <Link
            to={`/xamlens/datastores/${datastoresSlug(tab)}`}
            className="inline-flex items-center gap-1 text-xs font-semibold text-sky-700 hover:underline"
          >
            View All
            <Icon icon="mdi:arrow-right" width={12} />
          </Link>
        )}
      </div>
      <div className="px-4">
        <DrawerTabList tabs={TOP_RISKY_TABS} active={tab} onSelect={setTab} label="Environment" idPrefix={ids} />
      </div>
      <div role="tabpanel" id={`${ids}-panel`} aria-labelledby={`${ids}-tab-${tab}`} className="overflow-x-auto px-1 pb-2">
        <table className="w-full text-xs">
          <thead>
            <tr>
              <th className={TH}>Datastore</th>
              <th className={TH}>Sensitive Records</th>
              <th className={TH}>Label</th>
            </tr>
          </thead>
          <tbody>
            {rows.length === 0 && (
              <tr>
                <td colSpan={3} className="px-3 py-8 text-center text-[rgba(0,0,0,0.45)]">No risky datastores found</td>
              </tr>
            )}
            {rows.map((row, i) => {
              const openable = canOpenDrawer(row);
              return (
                <tr
                  key={row.id ?? row.sourceId ?? `row-${i}`}
                  tabIndex={openable ? 0 : undefined}
                  onClick={openable ? () => onOpen(row) : undefined}
                  onKeyDown={openable ? e => {
                    if (e.key === 'Enter') {
                      e.preventDefault();
                      onOpen(row);
                    }
                  } : undefined}
                  className={`border-t border-[rgba(0,0,0,0.06)] ${
                    openable
                      ? 'cursor-pointer hover:bg-[rgba(0,0,0,0.02)] focus:outline-none focus-visible:bg-[rgba(0,0,0,0.04)]'
                      : ''
                  }`}
                >
                  <td className="px-3 py-2.5">
                    <span className="flex min-w-0 items-center gap-2">
                      <DspmProviderIcon provider={datastoreIconKey(row)} size={16} />
                      <span className="truncate font-semibold text-[rgba(0,0,0,0.85)]">{row.name ?? '-'}</span>
                    </span>
                  </td>
                  <td className="px-3 py-2.5 tabular-nums text-[rgba(0,0,0,0.75)]">
                    {row.sensitives.recordCount.toLocaleString('en-US')}
                  </td>
                  <td className="px-3 py-2.5">
                    {row.sensitives.typeLabels.length > 0 ? <LabelChips labels={row.sensitives.typeLabels} /> : '-'}
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

export default TopRiskyDatastores;
