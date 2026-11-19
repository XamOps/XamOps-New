import React, { useId, useRef, useState } from 'react';
import { Icon } from '@iconify/react';
import type { DatastoreRow } from '../../../types/dspmDatastores';
import type { MisconfigurationRow } from '../../../types/dspmMisconfigurations';
import { misconfigurationApi } from '../../../hooks/useDspmMisconfigurations';
import { useLazyResource } from '../../../hooks/useDspmDatastoreDrawer';
import PoweredByProvider from '../../XamSecure/PoweredByProvider';
import { DspmProviderIcon } from '../DspmIcons';
import { datastoreIconKey } from '../datastores/datastoreVisuals';
import { DrawerShell } from '../datastores/drawer/DrawerShell';
import { DrawerTabList, type DrawerTabDef } from '../datastores/drawer/DrawerTabList';
import { ResourceView } from '../datastores/drawer/TabParts';
import { LevelLabel } from '../datastores/ui/Chips';
import { ConsoleOnlyButton } from '../datastores/ui/ConsoleOnly';
import AffectedDatastores from './AffectedDatastores';
import MisconfigurationDetails from './MisconfigurationDetails';

export type MisconfigurationSection = 'details' | 'affected';

const SECTIONS: ReadonlyArray<DrawerTabDef<MisconfigurationSection>> = [
  { id: 'details', label: 'Details' },
  { id: 'affected', label: 'Affected Datastores' },
];

interface Props {
  row: MisconfigurationRow;
  /** The section shown on open: Details, or Affected Datastores when coming back from a datastore. */
  initialSection?: MisconfigurationSection;
  /** Should be stable: the drawer puts it in a keydown effect's dependencies. */
  onClose: () => void;
  /** A datastore name clicked in Affected Datastores. */
  onOpenDatastore: (row: DatastoreRow) => void;
}

/**
 * One policy over the right 55% of the page: the header (the first affected datastore's type icon once
 * loaded, the full title, the Upwind badge, the severity chip, the greyed "Push to SIEM" and the close X),
 * then a vertical nav with Details and Affected Datastores beside the section.
 *
 * The detail loads once on open; both sections read it. The portal, overlay, focus and Escape rules are
 * `DrawerShell`'s.
 */
const MisconfigurationDrawer: React.FC<Props> = ({ row, initialSection = 'details', onClose, onOpenDatastore }) => {
  const [section, setSection] = useState<MisconfigurationSection>(initialSection);
  const detail = useLazyResource(() => misconfigurationApi.detail(row.policyId), true);
  const closeButton = useRef<HTMLButtonElement>(null);
  const ids = useId();
  const first = detail.data?.affected[0]?.datastore ?? null;

  return (
    <DrawerShell label={row.title ?? row.policyId} onClose={onClose} initialFocus={closeButton}>
      <div className="flex items-start gap-3 border-b border-[rgba(0,0,0,0.06)] px-5 pb-3 pt-4">
        {first && <span className="mt-0.5"><DspmProviderIcon provider={datastoreIconKey(first)} size={22} /></span>}
        <div className="min-w-0 flex-1 space-y-2">
          <h2 className="text-base font-bold text-[rgba(0,0,0,0.88)]">
            {row.title ?? '-'}
            {row.source === 'UPWIND' && <PoweredByProvider provider="upwind" className="ml-2 align-middle" />}
          </h2>
          <div className="flex flex-wrap items-center gap-2">
            <span className="inline-flex rounded-full border border-[rgba(0,0,0,0.1)] bg-[rgba(0,0,0,0.02)] px-2 py-0.5">
              <LevelLabel level={row.severity} label={row.severityLabel} />
            </span>
            <ConsoleOnlyButton variant="secondary">
              Push to SIEM <Icon icon="mdi:chevron-down" width={14} />
            </ConsoleOnlyButton>
          </div>
        </div>
        <button
          ref={closeButton}
          type="button"
          onClick={onClose}
          aria-label="Close"
          className="flex h-7 w-7 flex-shrink-0 items-center justify-center rounded-md border border-[rgba(0,0,0,0.12)] text-[rgba(0,0,0,0.5)] transition-colors hover:bg-[rgba(0,0,0,0.04)] hover:text-[rgba(0,0,0,0.85)]"
        >
          <Icon icon="mdi:close" width={14} />
        </button>
      </div>
      <div className="flex min-h-0 flex-1">
        <nav className="w-44 flex-shrink-0 px-3 py-4">
          <DrawerTabList
            tabs={SECTIONS}
            active={section}
            onSelect={setSection}
            label="Misconfiguration details"
            idPrefix={ids}
            orientation="vertical"
          />
        </nav>
        <div
          role="tabpanel"
          id={`${ids}-panel`}
          aria-labelledby={`${ids}-tab-${section}`}
          className="min-w-0 flex-1 overflow-y-auto px-5 py-4"
        >
          <ResourceView resource={detail} subject="misconfiguration">
            {data => (section === 'details'
              ? <MisconfigurationDetails detail={data} />
              : <AffectedDatastores policyId={data.policyId} affected={data.affected} onOpenDatastore={onOpenDatastore} />)}
          </ResourceView>
        </div>
      </div>
    </DrawerShell>
  );
};

export default MisconfigurationDrawer;
