import React from 'react';
import { Icon } from '@iconify/react';
import type { LineageDatastore, LineageFile, LineagePolicy } from '../../../types/dspmLineage';
import { DspmObjectIcon } from '../DspmIcons';
import { countText, exposureCountText, formatNodeDay } from './lineageText';
import { CATEGORY_NODE_ICON, datastoreIcon, policyIcon, severityDot } from './lineageVisuals';

/** What one tree node shows, by kind (build 4 design 6.6). */
export type LineageNodeView =
  | { kind: 'category'; name: string; count: number | null }
  | { kind: 'policy'; policy: LineagePolicy }
  | { kind: 'datastore'; datastore: LineageDatastore }
  | { kind: 'file'; file: LineageFile };

/** The node's name as drawn and as its button's accessible name. */
export function nodeName(view: LineageNodeView): string {
  switch (view.kind) {
    case 'category': return view.name;
    case 'policy': return view.policy.name ?? '-';
    case 'datastore': return view.datastore.name ?? '-';
    case 'file': return view.file.name ?? '-';
  }
}

const Chip: React.FC<{ children: string }> = ({ children }) => (
  <span className="inline-block whitespace-nowrap rounded border border-[rgba(0,0,0,0.08)] bg-[rgba(0,0,0,0.05)] px-1.5 py-0.5 text-[10px] font-semibold text-[rgba(0,0,0,0.6)]">
    {children}
  </span>
);

const IconSquare: React.FC<{ chosen: boolean; children: React.ReactNode }> = ({ chosen, children }) => (
  <span className="relative flex h-8 w-8 flex-shrink-0 items-center justify-center rounded-lg bg-[rgba(0,0,0,0.04)] text-[rgba(0,0,0,0.6)]">
    {children}
    {chosen && (
      <span data-chosen-badge="" className="absolute -right-1 -top-1 flex rounded-full bg-white">
        <Icon icon="mdi:check-circle" width={12} className="text-sky-500" aria-hidden="true" />
      </span>
    )}
  </span>
);

/**
 * A tree node's body: the kind's icon in a rounded square (with a blue check badge when chosen), the
 * bold name and the kind's lines. The canvas wraps it in the node's button.
 */
const LineageNodeCard: React.FC<{ view: LineageNodeView; chosen: boolean }> = ({ view, chosen }) => {
  const name = nodeName(view);
  let icon: React.ReactNode;
  let title: React.ReactNode = <span className="block truncate font-semibold text-[rgba(0,0,0,0.85)]" title={name}>{name}</span>;
  let lines: React.ReactNode = null;
  switch (view.kind) {
    case 'category':
      icon = <Icon icon={CATEGORY_NODE_ICON} width={16} aria-hidden="true" />;
      lines = view.count !== null && <Chip>{exposureCountText(view.count)}</Chip>;
      break;
    case 'policy': {
      const { policy } = view;
      icon = <Icon icon={policyIcon(policy.service)} width={16} aria-hidden="true" />;
      title = <span className="line-clamp-2 break-words font-semibold text-[rgba(0,0,0,0.85)]" title={name}>{name}</span>;
      lines = (
        <span className="flex flex-wrap items-center gap-1.5">
          <span className="inline-flex items-center gap-1 text-[rgba(0,0,0,0.6)]">
            <span className={`h-1.5 w-1.5 rounded-full ${severityDot(policy.severity)}`} aria-hidden="true" />
            {policy.severityLabel ?? '-'}
          </span>
          {policy.datastores !== null && <Chip>{countText(policy.datastores, 'datastore')}</Chip>}
        </span>
      );
      break;
    }
    case 'datastore': {
      const { datastore } = view;
      icon = <Icon icon={datastoreIcon(datastore.service)} width={16} aria-hidden="true" />;
      lines = datastore.files !== null && <Chip>{countText(datastore.files, 'file')}</Chip>;
      break;
    }
    case 'file': {
      const { file } = view;
      icon = <DspmObjectIcon objectType="FILE" extension={file.extension} />;
      lines = (
        <>
          <span className="block truncate text-[rgba(0,0,0,0.6)]">Actor: {file.actor ?? '-'}</span>
          <span className="block text-[rgba(0,0,0,0.45)]">{file.modifiedAt === null ? '-' : formatNodeDay(file.modifiedAt)}</span>
        </>
      );
      break;
    }
  }
  return (
    <span className="flex h-full w-full items-start gap-2 text-left text-xs">
      <IconSquare chosen={chosen}>{icon}</IconSquare>
      <span className="flex min-w-0 flex-1 flex-col gap-1">
        {title}
        {lines}
      </span>
    </span>
  );
};

export default LineageNodeCard;
