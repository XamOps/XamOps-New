import React, { useId, useMemo, useState } from 'react';
import { Icon } from '@iconify/react';
import type { EndpointFileOverview } from '../../../../types/dspmFiles';
import { useLazyResource } from '../../../../hooks/useDspmDatastoreDrawer';
import { endpointFileApi } from '../../../../hooks/useDspmFiles';
import { DspmObjectIcon } from '../../DspmIcons';
import { documentCategoryAccent } from '../../files/filesFormat';
import { extensionOf } from '../datastoreFormat';
import { NestedDrawerShell } from '../drawer/DrawerShell';
import { DrawerTabList } from '../drawer/DrawerTabList';
import { InfoStrip, type InfoStripItem } from '../drawer/InfoStrip';
import { LoadingNote, ResourceView } from '../drawer/TabParts';
import SaasFileCopiesTab from '../saas/SaasFileCopiesTab';
import SaasRelatedFilesTab from '../saas/SaasRelatedFilesTab';
import { BarChip, DotChip } from '../ui/Chips';
import EndpointFileActivityTab from './EndpointFileActivityTab';
import EndpointFileDetailsTab from './EndpointFileDetailsTab';
import EndpointFileLineageTab from './EndpointFileLineageTab';
import EndpointFileSensitivesTab from './EndpointFileSensitivesTab';
import type { EndpointFileOpenTab } from './EndpointFileTable';
import { ENDPOINT_FILE_TABS, type EndpointFileTabId } from './endpointFileTabs';
import { isRecentlyModified } from './endpointFormat';
import { NewBadge } from './EndpointParts';

export interface EndpointFileDrawerProps {
  /** The vendor's file UUID, from the list row. The drawer is keyed by it. */
  fileId: string;
  /** The row's name: the title until the overview loads, and after it failed. */
  fileName: string | null;
  /** The row's extension: the icon and the subtitle until the overview loads. */
  extension: string | null;
  /** The row's last-modified time (epoch seconds): the "New" tag. */
  modifiedAt: number | null;
  /** The row's last scan time (epoch seconds): Details' Last Scanned. */
  scannedAt: number | null;
  /** `sensitives`, or `lineage` when opened from the lineage icon. */
  initialTab: EndpointFileOpenTab;
  /** The file's row, focused again when the drawer closes. */
  returnFocus: HTMLElement | null;
  /** Must be stable (`useCallback`), as `NestedDrawerShell` requires. */
  onClose: () => void;
}

const DASH = '-';

/** The card under the title; null (the overview failed) reads "-". Category and Document Class share its colour. */
function cardItems(overview: EndpointFileOverview | null): InfoStripItem[] {
  if (!overview) {
    return ['Owner', 'Category', 'Document Class', 'Sensitive Records'].map(label => ({ label, value: DASH }));
  }
  const accent = documentCategoryAccent(overview.categoryLabel);
  return [
    { label: 'Owner', value: overview.owner ?? DASH },
    { label: 'Category', value: overview.categoryLabel ? <DotChip label={overview.categoryLabel} dot={accent.dot} /> : DASH },
    {
      label: 'Document Class',
      value: overview.documentClassLabel ? <BarChip label={overview.documentClassLabel} bar={accent.bar} /> : DASH,
    },
    { label: 'Sensitive Records', value: overview.sensitiveRecords.toLocaleString('en-US') },
  ];
}

/**
 * The Endpoint file drawer (design 6.8 as changed by plan C15 to C21): a layer over the Files page or over
 * the Endpoint drawer, opened from a file row. Escape or its X closes it and returns focus to that row.
 *
 * The overview loads on open and feeds the card, Sensitives, Details, the Lineage tab's own lane and the
 * Related Files header. File Activity and Lineage load the first time their tab is shown, File Copies and
 * Related Files share one copies request. While the overview loads the title is the row's name; when it
 * fails, the card reads "-" and Sensitives and Details show the error in the file's wording, while the
 * other tabs load on their own. Read-only: there is no Take Action.
 */
const EndpointFileDrawer: React.FC<EndpointFileDrawerProps> = ({
  fileId, fileName, extension, modifiedAt, scannedAt, initialTab, returnFocus, onClose,
}) => {
  const api = useMemo(() => endpointFileApi(fileId), [fileId]);
  const [tab, setTab] = useState<EndpointFileTabId>(initialTab);

  const overview = useLazyResource(api.overview, true);
  const activity = useLazyResource(api.activity, tab === 'activity');
  const lineage = useLazyResource(api.lineage, tab === 'lineage');
  const copies = useLazyResource(api.copies, tab === 'copies' || tab === 'related');

  const ids = useId();
  const data = overview.data;
  const title = data?.name ?? fileName ?? DASH;
  const fileExtension = data?.extension ?? extension ?? extensionOf(fileName);
  const overviewLoading = data === null && overview.error === null;

  let body: React.ReactNode;
  switch (tab) {
    case 'sensitives':
      body = (
        <ResourceView resource={overview} subject="file">
          {loaded => <EndpointFileSensitivesTab fileId={fileId} sensitives={loaded.sensitives} />}
        </ResourceView>
      );
      break;
    case 'details':
      body = (
        <ResourceView resource={overview} subject="file">
          {loaded => <EndpointFileDetailsTab overview={loaded} scannedAt={scannedAt} />}
        </ResourceView>
      );
      break;
    case 'activity':
      body = <EndpointFileActivityTab activity={activity} />;
      break;
    case 'lineage':
      body = <EndpointFileLineageTab name={title} overview={overview} lineage={lineage} />;
      break;
    case 'copies':
      body = <SaasFileCopiesTab copies={copies} />;
      break;
    case 'related':
      body = (
        <SaasRelatedFilesTab
          copies={copies}
          overview={data ? { owner: data.owner } : null}
          datastoreName={null}
          endpoint={{ person: data?.owner ?? null, device: data?.device ?? null }}
        />
      );
      break;
  }

  return (
    <NestedDrawerShell label={title} returnFocus={returnFocus} onClose={onClose}>
      {({ closeButton, close }) => (
        <>
          <div className="flex items-start gap-3 px-5 pb-3 pt-4">
            <span className="mt-1"><DspmObjectIcon objectType={null} extension={fileExtension} /></span>
            <div className="min-w-0 flex-1">
              <div className="flex min-w-0 items-center gap-2">
                <h2 className="truncate text-base font-bold text-[rgba(0,0,0,0.88)]" title={title}>{title}</h2>
                {isRecentlyModified(modifiedAt, Date.now()) && <NewBadge />}
              </div>
              {fileExtension && (
                <p className="mt-0.5 text-[11px] font-semibold text-[rgba(0,0,0,0.45)]">{fileExtension.toUpperCase()}</p>
              )}
            </div>
            <button
              ref={closeButton}
              type="button"
              onClick={close}
              aria-label="Close"
              className="flex h-7 w-7 flex-shrink-0 items-center justify-center rounded-md border border-[rgba(0,0,0,0.12)] text-[rgba(0,0,0,0.5)] transition-colors hover:bg-[rgba(0,0,0,0.04)] hover:text-[rgba(0,0,0,0.85)]"
            >
              <Icon icon="mdi:close" width={14} />
            </button>
          </div>
          <div className="flex-1 space-y-4 overflow-y-auto px-5 pb-6">
            {overviewLoading ? <LoadingNote /> : <InfoStrip items={cardItems(data)} />}

            <DrawerTabList tabs={ENDPOINT_FILE_TABS} active={tab} onSelect={setTab} label="File details" idPrefix={ids} />

            <div role="tabpanel" id={`${ids}-panel`} aria-labelledby={`${ids}-tab-${tab}`}>
              {body}
            </div>
          </div>
        </>
      )}
    </NestedDrawerShell>
  );
};

export default EndpointFileDrawer;
