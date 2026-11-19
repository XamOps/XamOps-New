import React, { useId, useMemo, useState } from 'react';
import { Icon } from '@iconify/react';
import type { SaasFileOverview, SaasFileSharing } from '../../../../types/dspmDrawers';
import { useLazyResource } from '../../../../hooks/useDspmDatastoreDrawer';
import { saasFileApi } from '../../../../hooks/useDspmSaasDrawer';
import { DspmObjectIcon, DspmProviderIcon } from '../../DspmIcons';
import { extensionOf } from '../datastoreFormat';
import { driveIconKey } from '../datastoreVisuals';
import { CategoryChip } from '../ui/Chips';
import { ConsoleOnlyButton } from '../ui/ConsoleOnly';
import { NestedDrawerShell } from '../drawer/DrawerShell';
import { DrawerTabList } from '../drawer/DrawerTabList';
import { InfoStrip, type InfoStripItem } from '../drawer/InfoStrip';
import { LoadingNote, ResourceView } from '../drawer/TabParts';
import { saasFileTabs, type SaasFileTabId } from './saasFileTabs';
import { GreyTag, SaasCard } from './SaasParts';
import SaasFileSensitivesTab from './SaasFileSensitivesTab';
import SaasFileDetailsTab from './SaasFileDetailsTab';
import SaasFileActivityTab from './SaasFileActivityTab';
import SaasFileLineageTab from './SaasFileLineageTab';
import SaasFileCopiesTab from './SaasFileCopiesTab';
import SaasRelatedFilesTab from './SaasRelatedFilesTab';

export interface SaasFileDrawerProps {
  /** The file's 24-hex id, from its list row. The drawer is keyed by it. */
  fileId: string;
  /** The list row's name: the title until the overview loads, and after it failed. */
  fileName: string | null;
  /** The SaaS datastore's name, which heads Related Files; null when opened from the Files page. */
  datastoreName: string | null;
  /** The SaaS datastore's drive type (the row's subtype): decides the tabs before the overview loads. */
  driveType: string | null;
  /** `sensitives`, or `lineage` when opened from the lineage icon. */
  initialTab: SaasFileTabId;
  /** The file's row in the list, focused again when the drawer closes. */
  returnFocus: HTMLElement | null;
  /** Must be stable (`useCallback`), as `NestedDrawerShell` requires. */
  onClose: () => void;
}

const DASH = '-';

/** The header icon: an envelope for an Outlook message, else the file type's glyph. */
const FileIcon: React.FC<{ extension: string | null }> = ({ extension }) =>
  extension === 'msg'
    ? <Icon icon="mdi:email-outline" width={20} className="flex-shrink-0 text-[rgba(0,0,0,0.45)]" />
    : <DspmObjectIcon objectType={null} extension={extension} />;

/** The card under the title. Null (loading is handled by the caller; null here means the overview failed) reads "-". */
function cardItems(overview: SaasFileOverview | null): InfoStripItem[] {
  if (!overview) {
    return ['Source/Account', 'Owner', 'Category', 'Document Class'].map(label => ({ label, value: DASH }));
  }
  const icon = driveIconKey(overview.driveType);
  return [
    {
      label: 'Source/Account',
      value: (
        <>
          {icon && <DspmProviderIcon provider={icon} size={14} />}
          <span className="truncate" title={overview.accountName ?? undefined}>{overview.accountName ?? DASH}</span>
        </>
      ),
    },
    { label: 'Owner', value: overview.owner ?? DASH },
    { label: 'Category', value: overview.categoryLabel ? <CategoryChip label={overview.categoryLabel} neutral /> : DASH },
    {
      label: 'Document Class',
      value: overview.documentClassLabel ? <GreyTag truncate>{overview.documentClassLabel}</GreyTag> : DASH,
    },
  ];
}

/** Each list with its title's colour class: External Emails first, in red. */
const SHARING_LISTS: ReadonlyArray<[keyof SaasFileSharing, string, string]> = [
  ['externalEmails', 'External Emails', 'text-rose-600'],
  ['internalEmails', 'Internal Emails', 'text-[rgba(0,0,0,0.45)]'],
  ['internalGroups', 'Internal Groups', 'text-[rgba(0,0,0,0.45)]'],
  ['externalGroups', 'External Groups', 'text-[rgba(0,0,0,0.45)]'],
];

const SHARING_FLAGS: ReadonlyArray<[keyof SaasFileSharing, string]> = [
  ['privateShare', 'Private'],
  ['publicShare', 'Public'],
  ['orgWide', 'Org-wide'],
];

/**
 * The Sharing card (FR-SFILE-4): each non-empty list as its chips, External Emails first with a red title,
 * then a grey tag per true flag; "-" when there is neither, or when the overview failed (`sharing` null).
 * `externalShare` draws no tag: the captures show External and Internal Emails for that file, not a tag.
 */
const SharingCard: React.FC<{ sharing: SaasFileSharing | null }> = ({ sharing }) => {
  const lists = sharing ? SHARING_LISTS.filter(([key]) => (sharing[key] as string[]).length > 0) : [];
  const flags = sharing ? SHARING_FLAGS.filter(([key]) => sharing[key] === true) : [];
  return (
    <SaasCard title="Sharing">
      {lists.length === 0 && flags.length === 0 ? (
        <p className="text-xs text-[rgba(0,0,0,0.45)]">{DASH}</p>
      ) : (
        <div className="space-y-3">
          {lists.map(([key, title, titleClass]) => (
            <div key={key}>
              <div className={`mb-1.5 text-[10px] ${titleClass}`}>{title}</div>
              <div className="flex flex-wrap gap-1.5">
                {(sharing![key] as string[]).map((value, index) => <GreyTag key={`${index}:${value}`}>{value}</GreyTag>)}
              </div>
            </div>
          ))}
          {flags.length > 0 && (
            <div className="flex flex-wrap gap-1.5">
              {flags.map(([key, label]) => <GreyTag key={key}>{label}</GreyTag>)}
            </div>
          )}
        </div>
      )}
    </SaasCard>
  );
};

/**
 * The SaaS file drawer (design 6.6, FR-SFILE): a second drawer over the SaaS drawer, opened from a file
 * row of an expanded Sensitives row. Escape or its X closes it first and returns focus to that row.
 *
 * The overview loads on open and feeds the subtitle, the card, Sharing, Sensitives, Details and the Gmail
 * lineage. File Activity, Lineage, and File Copies with Related Files (one shared copies request) load
 * the first time their tab is shown. While the overview loads the title is the list row's name; when it
 * fails, the card and Sharing read "-" and the tabs that need it show the error in the file's wording (a
 * removed file reads "This file could not be found." with no Retry), while the other tabs load on their
 * own. Take Action is a console action and stays greyed.
 */
const SaasFileDrawer: React.FC<SaasFileDrawerProps> = ({
  fileId, fileName, datastoreName, driveType, initialTab, returnFocus, onClose,
}) => {
  const api = useMemo(() => saasFileApi(fileId), [fileId]);
  const tabs = useMemo(() => saasFileTabs(driveType), [driveType]);
  const [tab, setTab] = useState<SaasFileTabId>(initialTab);
  const active = tabs.some(t => t.id === tab) ? tab : 'sensitives';

  const overview = useLazyResource(api.overview, true);
  const activity = useLazyResource(api.activity, active === 'activity');
  const lineage = useLazyResource(api.lineage, active === 'lineage');
  const copies = useLazyResource(api.copies, active === 'copies' || active === 'related');

  const ids = useId();
  const data = overview.data;
  const title = data?.name ?? fileName ?? DASH;
  const overviewLoading = data === null && overview.error === null;

  let body: React.ReactNode;
  switch (active) {
    case 'sensitives':
      body = (
        <ResourceView resource={overview} subject="file">
          {loaded => <SaasFileSensitivesTab sensitives={loaded.sensitives} />}
        </ResourceView>
      );
      break;
    case 'details':
      body = (
        <ResourceView resource={overview} subject="file">
          {loaded => <SaasFileDetailsTab overview={loaded} />}
        </ResourceView>
      );
      break;
    case 'activity':
      body = <SaasFileActivityTab activity={activity} />;
      break;
    case 'lineage':
      // Without the overview the tab cannot tell a Gmail file from another one, so it shows its error only.
      body = (
        <ResourceView resource={overview} subject="file">
          {loaded => <SaasFileLineageTab overview={loaded} name={title} lineage={lineage} />}
        </ResourceView>
      );
      break;
    case 'copies':
      body = <SaasFileCopiesTab copies={copies} />;
      break;
    case 'related':
      body = <SaasRelatedFilesTab copies={copies} overview={data} datastoreName={datastoreName} />;
      break;
  }

  return (
    <NestedDrawerShell label={title} returnFocus={returnFocus} onClose={onClose}>
      {({ closeButton, close }) => (
        <>
          <div className="flex items-start gap-3 px-5 pb-3 pt-4">
            <span className="mt-1"><FileIcon extension={data?.fileExtension ?? extensionOf(fileName)} /></span>
            <div className="min-w-0 flex-1">
              <h2 className="truncate text-base font-bold text-[rgba(0,0,0,0.88)]" title={title}>{title}</h2>
              {data?.fileTypeLabel && (
                <p className="mt-0.5 text-[11px] font-semibold text-[rgba(0,0,0,0.45)]">{data.fileTypeLabel}</p>
              )}
            </div>
            <ConsoleOnlyButton variant="secondary" placement="left">
              Take Action
              <Icon icon="mdi:chevron-down" width={14} />
            </ConsoleOnlyButton>
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
            {overviewLoading ? <LoadingNote /> : (
              <>
                <InfoStrip items={cardItems(data)} />
                <SharingCard sharing={data?.sharing ?? null} />
              </>
            )}

            <DrawerTabList tabs={tabs} active={active} onSelect={setTab} label="File details" idPrefix={ids} />

            <div role="tabpanel" id={`${ids}-panel`} aria-labelledby={`${ids}-tab-${active}`}>
              {body}
            </div>
          </div>
        </>
      )}
    </NestedDrawerShell>
  );
};

export default SaasFileDrawer;
