import React from 'react';
import { Icon } from '@iconify/react';
import type { SaasFileListRow, SharingMode } from '../../../types/dspmFiles';
import { DspmObjectIcon, DspmProviderIcon } from '../DspmIcons';
import { formatConsoleDate } from '../datastores/datastoreFormat';
import { driveIconKey } from '../datastores/datastoreVisuals';
import { BarChip, DotChip } from '../datastores/ui/Chips';
import { documentCategoryAccent, SHARING_ICON } from './filesFormat';
import HoverBubble from './HoverBubble';

export type SaasFileColumnId = 'name' | 'category' | 'documentClass' | 'sharing' | 'owner' | 'modified';

/** "Columns 6": File Name is always shown. The lineage column is not one of them. */
export const SAAS_FILE_COLUMNS: ReadonlyArray<{ id: SaasFileColumnId; label: string }> = [
  { id: 'name', label: 'File Name' },
  { id: 'category', label: 'Category' },
  { id: 'documentClass', label: 'Document Class' },
  { id: 'sharing', label: 'Sharing Mode' },
  { id: 'owner', label: 'Owner' },
  { id: 'modified', label: 'Last Modified' },
];

const DASH = <span className="text-[rgba(0,0,0,0.4)]">-</span>;

/** A file extension as the console reads it; a Gmail row's `extension` holds the subject, which is not one. */
const SHORT_EXTENSION = /^[A-Za-z0-9]{1,8}$/;

/**
 * The file-type icon: a short extension draws its icon, an Outlook row without one the mail icon, and
 * anything else (a Gmail subject included) the generic file icon.
 */
export function fileIcon(row: Pick<SaasFileListRow, 'extension' | 'source'>): React.ReactNode {
  const extension = row.extension?.trim() ?? '';
  if (SHORT_EXTENSION.test(extension)) return <DspmObjectIcon objectType={null} extension={extension} />;
  if (extension === '' && row.source === 'OUTLOOK') {
    return <Icon icon="mdi:email-outline" width={13} className="flex-shrink-0 text-[rgba(0,0,0,0.3)]" />;
  }
  return <DspmObjectIcon objectType={null} extension={null} />;
}

const MODE_CHIP = 'inline-flex items-center whitespace-nowrap rounded border border-[rgba(0,0,0,0.08)] bg-[rgba(0,0,0,0.05)] px-1.5 py-0.5 text-[10px] font-semibold text-[rgba(0,0,0,0.6)]';

/**
 * The Sharing Mode cell: the grey share icon, whose bubble lists every mode; the first mode's chip, whose
 * bubble names it; and "+ N" for the rest, focusable, whose bubble names them. No click action.
 */
export const SharingModeCell: React.FC<{ modes: SharingMode[] }> = ({ modes }) => {
  if (modes.length === 0) return DASH;
  const labels = modes.map(mode => mode.label);
  const rest = labels.slice(1);
  return (
    <span className="inline-flex items-center gap-1.5">
      <HoverBubble text={labels.join(', ')}>
        <span className="inline-flex text-[rgba(0,0,0,0.45)]">
          <Icon icon={SHARING_ICON} width={14} aria-hidden="true" />
        </span>
      </HoverBubble>
      <HoverBubble text={labels[0]}>
        <span className={MODE_CHIP}>{labels[0]}</span>
      </HoverBubble>
      {rest.length > 0 && (
        <HoverBubble text={rest.join(', ')}>
          <span
            tabIndex={0}
            className="whitespace-nowrap rounded-full bg-[rgba(0,0,0,0.06)] px-1.5 py-0.5 text-[10px] font-semibold tabular-nums text-[rgba(0,0,0,0.55)] focus:outline-none focus-visible:ring-2 focus-visible:ring-[rgba(0,0,0,0.25)]"
          >
            + {rest.length}
          </span>
        </HoverBubble>
      )}
    </span>
  );
};

export function renderSaasFileCell(id: SaasFileColumnId, row: SaasFileListRow): React.ReactNode {
  switch (id) {
    case 'name': {
      const icon = driveIconKey(row.source);
      return (
        <span className="flex min-w-0 max-w-[16.5rem] items-center gap-2">
          {fileIcon(row)}
          <span className="min-w-0">
            <span className="block max-w-[15rem] truncate font-semibold text-[rgba(0,0,0,0.85)]" title={row.name ?? undefined}>
              {row.name ?? '-'}
            </span>
            {(icon || row.sourceLabel) && (
              <span className="mt-0.5 flex items-center gap-1 text-[10px] text-[rgba(0,0,0,0.45)]">
                {icon && <DspmProviderIcon provider={icon} size={12} />}
                {row.sourceLabel}
              </span>
            )}
          </span>
        </span>
      );
    }
    case 'category':
      return row.categoryLabel
        ? <DotChip label={row.categoryLabel} dot={documentCategoryAccent(row.categoryLabel).dot} />
        : DASH;
    case 'documentClass':
      return row.documentClassLabel
        ? <BarChip label={row.documentClassLabel} bar={documentCategoryAccent(row.categoryLabel).bar} />
        : DASH;
    case 'sharing':
      return <SharingModeCell modes={row.sharingModes} />;
    case 'owner':
      return row.owner
        ? <span className="block max-w-[14rem] truncate" title={row.owner}>{row.owner}</span>
        : DASH;
    case 'modified':
      return row.lastModifiedAt === null
        ? DASH
        : <span className="whitespace-nowrap">{formatConsoleDate(row.lastModifiedAt)}</span>;
  }
}
