import React, { useId, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { Icon } from '@iconify/react';
import type { DashboardRegion } from '../../../types/dspmPages';
import { DrawerTabList } from '../datastores/drawer/DrawerTabList';
import { CategoryChip } from '../datastores/ui/Chips';
import { InfoIcon } from './DashboardParts';
import RegionMap from './RegionMap';
import { formatCompactCount } from './dashboardModel';
import { groupRegions, type RegionGroup } from './regionCatalog';

/** The widget's info tooltip, copied verbatim from the console's `tooltips-a0def156.js` (`sensitiveRegions`). */
export const SENSITIVE_REGIONS_TOOLTIP = 'Provides visibility into regions hosting active cloud datastores with '
  + 'detected sensitive or classified data across supported providers.';

/** The console's zoom control: 200% at first, 100% to 400% in steps of 50%. */
const ZOOM_START = 2;
const ZOOM_MIN = 1;
const ZOOM_MAX = 4;
const ZOOM_STEP = 0.5;

/** Where "View All" leads: the Datastores Cloud tab filtered by the tab's region codes. */
export function regionDatastoresLink(codes: string[]): string {
  return `/xamlens/datastores/cloud?region=${encodeURIComponent(codes.join(','))}`;
}

const Stat: React.FC<{ label: string; value: string; sub?: string; title: string }> = ({ label, value, sub, title }) => (
  <div className="flex min-w-0 flex-1 flex-col gap-1 rounded-lg bg-[rgba(0,0,0,0.03)] p-2">
    <span className="text-[11px] text-[rgba(0,0,0,0.5)]">{label}</span>
    <span className="text-sm font-semibold tabular-nums text-[rgba(0,0,0,0.88)]" title={title}>
      {value}
      {sub && <span className="text-[11px] font-normal text-[rgba(0,0,0,0.5)]"> ({sub})</span>}
    </span>
  </div>
);

/** The selected region's card: name, the two counts, then its sensitive categories. */
const RegionCard: React.FC<{ region: RegionGroup }> = ({ region }) => {
  const { name } = region;
  const share = region.datastores > 0 ? Math.round((region.scannedDatastores / region.datastores) * 100) : 0;
  return (
    <div
      aria-label={`${name} region`}
      role="group"
      className="flex flex-col gap-3 rounded-lg border border-[rgba(0,0,0,0.08)] bg-white p-3 shadow-[10px_10px_27px_0_rgba(33,27,233,0.12)]"
    >
      <p className="text-sm font-medium text-[rgba(0,0,0,0.88)]">{name}</p>
      <div className="flex gap-2">
        <Stat label="Total DataStores" value={formatCompactCount(region.datastores)} title={region.datastores.toLocaleString('en-US')} />
        <Stat
          label="Scanned DataStores"
          value={formatCompactCount(region.scannedDatastores)}
          sub={`${share}%`}
          title={region.scannedDatastores.toLocaleString('en-US')}
        />
      </div>
      {region.sensitiveLabels.length > 0 && (
        <div className="flex flex-col gap-1">
          <div className="flex items-center justify-between">
            <span className="text-[11px] font-medium text-[rgba(0,0,0,0.5)]">Sensitives</span>
            <Link
              to={regionDatastoresLink(region.codes)}
              aria-label={`View All datastores in ${name}`}
              className="inline-flex items-center gap-1 text-[11px] font-semibold text-sky-700 hover:underline"
            >
              View All
              <Icon icon="mdi:arrow-right" width={12} />
            </Link>
          </div>
          <div className="flex flex-wrap gap-1">
            {region.sensitiveLabels.map(label => <CategoryChip key={label} label={label} />)}
          </div>
        </div>
      )}
    </div>
  );
};

interface Props {
  regions: DashboardRegion[];
  /** The cloud rows were capped, so the regions cover only the first 1,000 cloud datastores. */
  partial: boolean;
}

/**
 * "Active Sensitive Regions" (brief 11), built in part from the collected cloud rows: a tab per region name
 * (codes the console shows under one name are merged), the world map with a dot per tab the catalogue can
 * place, and the selected tab's card (Total DataStores, Scanned DataStores, the Sensitives categories and
 * "View All" into the Cloud tab filtered by its codes). The console's Total File Size, Total Records and
 * data-element row are left out: the rows carry none of them (GAP-19; the rows' sensitive record count is
 * a different number, build 8).
 */
const ActiveSensitiveRegions: React.FC<Props> = ({ regions, partial }) => {
  const [selected, setSelected] = useState<string | null>(null);
  const [zoom, setZoom] = useState(ZOOM_START);
  const ids = useId();
  const groups = useMemo(() => groupRegions(regions), [regions]);
  const active = groups.find(group => group.id === selected) ?? groups[0];
  return (
    <div role="region" aria-labelledby={`${ids}-title`} className="flex flex-col gap-3 rounded-2xl border border-[rgba(0,0,0,0.08)] bg-white p-4">
      <header className="flex items-center gap-1.5">
        <h3 id={`${ids}-title`} className="text-xs font-semibold text-[rgba(0,0,0,0.7)]">Active Sensitive Regions</h3>
        <span className="text-[11px] text-[rgba(0,0,0,0.45)]">(Showing {groups.length} Regions)</span>
        <InfoIcon text={SENSITIVE_REGIONS_TOOLTIP} />
      </header>
      {!active ? (
        <p className="flex min-h-[200px] items-center justify-center text-xs text-[rgba(0,0,0,0.45)]">
          No active sensitive regions found
        </p>
      ) : (
        <>
          <div className="overflow-x-auto">
            <DrawerTabList
              tabs={groups.map(group => ({ id: group.id, label: group.name }))}
              active={active.id}
              onSelect={setSelected}
              label="Region"
              idPrefix={ids}
            />
          </div>
          <div
            role="tabpanel"
            id={`${ids}-panel`}
            aria-labelledby={`${ids}-tab-${active.id}`}
            className="relative h-[321px] overflow-hidden rounded-lg bg-[rgba(0,0,0,0.03)]"
          >
            <div className="absolute inset-y-0 left-[40%] right-0">
              <RegionMap groups={groups} active={active.id} onSelect={setSelected} zoom={zoom} />
            </div>
            <div className="absolute left-3 top-3 z-[2] w-[40%]">
              <RegionCard key={active.id} region={active} />
            </div>
            <div className="absolute right-2 top-2 z-[2] flex items-center gap-2">
              <button
                type="button"
                aria-label="Zoom in"
                disabled={zoom >= ZOOM_MAX}
                onClick={() => setZoom(z => Math.min(ZOOM_MAX, z + ZOOM_STEP))}
                className="inline-flex h-7 w-7 items-center justify-center rounded-md border border-[rgba(0,0,0,0.1)] bg-white text-[rgba(0,0,0,0.6)] disabled:opacity-40"
              >
                <Icon icon="mdi:plus-circle-outline" width={12} />
              </button>
              <span className="text-[11px] tabular-nums text-[rgba(0,0,0,0.55)]" aria-live="polite">{Math.round(zoom * 100)}%</span>
              <button
                type="button"
                aria-label="Zoom out"
                disabled={zoom <= ZOOM_MIN}
                onClick={() => setZoom(z => Math.max(ZOOM_MIN, z - ZOOM_STEP))}
                className="inline-flex h-7 w-7 items-center justify-center rounded-md border border-[rgba(0,0,0,0.1)] bg-white text-sm font-medium text-[rgba(0,0,0,0.6)] disabled:opacity-40"
              >
                −
              </button>
            </div>
          </div>
          {partial && (
            <p className="text-[11px] text-[rgba(0,0,0,0.45)]">Based on the first 1,000 cloud datastores.</p>
          )}
        </>
      )}
    </div>
  );
};

export default ActiveSensitiveRegions;
