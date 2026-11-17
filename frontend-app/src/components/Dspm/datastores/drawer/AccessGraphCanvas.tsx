import React from 'react';
import { Icon } from '@iconify/react';
import type { DatastoreAccessGroup } from '../../../../types/dspmDatastores';
import { DspmProviderIcon } from '../../DspmIcons';
import { edgePath, type Box, type GraphLayout, type GraphNode } from './accessGraphLayout';

export interface PrincipalKind {
  /** The type box's label and the panel's subtitle: "Roles". */
  label: string;
  /** For the toggle link: "Show roles". */
  noun: string;
  icon: string;
}

const KINDS: Readonly<Record<string, PrincipalKind>> = {
  ROLE: { label: 'Roles', noun: 'roles', icon: 'mdi:shield-account-outline' },
  USER: { label: 'Users', noun: 'users', icon: 'mdi:account-outline' },
  GROUP: { label: 'Groups', noun: 'groups', icon: 'mdi:account-group-outline' },
};
const OTHER_KIND: PrincipalKind = { label: 'Principals', noun: 'principals', icon: 'mdi:account-question-outline' };

/** How a group's principal type reads: ROLE → Roles, USER → Users, GROUP → Groups. */
export const principalKind = (type: string | null): PrincipalKind => KINDS[type ?? ''] ?? OTHER_KIND;

/** A group's permissions as chips (READ, WRITE, …), or "-" for a group that holds only actions. */
export const PermissionChips: React.FC<{ group: DatastoreAccessGroup }> = ({ group }) => {
  if (group.actionOnly || group.permissions.length === 0) {
    // TODO(G2): placement of action-only principals is unseen; see datastores-page-mapping.md, third
    // batch. They keep their own box with an empty permission list, shown as "-".
    return <span className="text-xs text-[rgba(0,0,0,0.4)]">-</span>;
  }
  // The spaces between chips keep accessible names readable ("Permissions READ WRITE"); flex layout drops them.
  return (
    <span className="flex flex-wrap gap-1">
      {group.permissions.map((permission, index) => (
        <React.Fragment key={permission}>
          {index > 0 && ' '}
          <span className="rounded bg-[rgba(0,0,0,0.06)] px-1.5 py-0.5 text-[9px] font-semibold tracking-wide text-[rgba(0,0,0,0.6)]">
            {permission}
          </span>
        </React.Fragment>
      ))}
    </span>
  );
};

const place = (box: Box): React.CSSProperties => ({ left: box.x, top: box.y, width: box.w, height: box.h });

const BOX = 'absolute rounded-xl border border-[rgba(0,0,0,0.1)] bg-white shadow-sm';

interface Props {
  layout: GraphLayout;
  zoom: number;
  /** The root's label: the graph's root name, else the row's name. */
  rootName: string;
  provider: string | null;
  principalCount: number;
  /** A Permissions box or a type box body was clicked; `opener` gets focus back when the panel closes. */
  onOpen: (group: DatastoreAccessGroup, opener: HTMLElement) => void;
  onToggle: (groupId: string) => void;
}

/**
 * The Access Graph drawing: a scrollable viewport (pan by scrolling) holding a sizer of the zoomed size,
 * which holds the drawing at its natural size scaled from its top-left corner. Boxes are absolutely
 * positioned HTML, so they hold chips and buttons and are reachable by keyboard; the connectors are one
 * SVG layer in the same coordinates.
 *
 * A Permissions box is one button. A type box holds two buttons side by side, as a button cannot hold
 * another: its body opens the panel, its link shows or hides the principals. Principals are plain boxes
 * (clicking one does nothing, FR-ACG-4), highlighted when they match the search.
 */
const AccessGraphCanvas: React.FC<Props> = ({ layout, zoom, rootName, provider, principalCount, onOpen, onToggle }) => {
  const renderNode = (node: GraphNode) => {
    switch (node.kind) {
      case 'root':
        return (
          <div key={node.id} style={place(node)} className={`${BOX} flex flex-col items-center justify-center gap-1 px-3 text-center`}>
            <span className="flex h-8 w-8 items-center justify-center rounded-full border border-[rgba(0,0,0,0.1)] bg-[rgba(0,0,0,0.02)]">
              <DspmProviderIcon provider={provider} size={16} />
            </span>
            <span className="w-full truncate text-xs font-semibold text-[rgba(0,0,0,0.85)]" title={rootName}>{rootName}</span>
            <span className="text-[10px] text-[rgba(0,0,0,0.45)]">{principalCount} principals</span>
          </div>
        );
      case 'permissions':
        return (
          <button
            key={node.id}
            type="button"
            style={place(node)}
            onClick={e => onOpen(node.group, e.currentTarget)}
            className={`${BOX} flex flex-col items-start gap-1.5 overflow-hidden px-3 py-2 text-left transition-colors hover:border-[rgba(0,0,0,0.25)]`}
          >
            <span className="text-[11px] font-semibold text-[rgba(0,0,0,0.75)]">Permissions</span>
            {' '}
            <PermissionChips group={node.group} />
          </button>
        );
      case 'type': {
        const kind = principalKind(node.group.principalType);
        return (
          <div key={node.id} style={place(node)} className={`${BOX} flex items-stretch overflow-hidden`}>
            <button
              type="button"
              onClick={e => onOpen(node.group, e.currentTarget)}
              // Tight spacing, so "Groups" with a two-digit count and "Show groups" fit the 200 px box.
              className="flex min-w-0 flex-1 items-center gap-1.5 pl-3 pr-1 text-left transition-colors hover:bg-[rgba(0,0,0,0.02)]"
            >
              <Icon icon={kind.icon} width={16} className="flex-shrink-0 text-[rgba(0,0,0,0.5)]" />
              <span className="truncate text-xs font-semibold text-[rgba(0,0,0,0.8)]">{kind.label}</span>
              {' '}
              <span className="rounded-full bg-[rgba(0,0,0,0.06)] px-1.5 text-[10px] font-semibold tabular-nums text-[rgba(0,0,0,0.6)]">
                {node.group.count}
              </span>
            </button>
            <button
              type="button"
              aria-expanded={node.expanded}
              onClick={() => onToggle(node.group.id)}
              className="flex-shrink-0 pl-1 pr-2 text-[11px] font-semibold text-sky-700 hover:underline"
            >
              {node.expanded ? 'Hide' : `Show ${kind.noun}`}
            </button>
          </div>
        );
      }
      case 'principal':
        return (
          <div
            key={node.id}
            style={place(node)}
            title={node.name}
            data-match={node.match ? 'true' : undefined}
            className={`absolute truncate rounded-md border px-2 text-[11px] leading-[26px] text-[rgba(0,0,0,0.75)] ${
              node.match ? 'border-yellow-300 bg-yellow-200' : 'border-[rgba(0,0,0,0.1)] bg-white'
            }`}
          >
            {node.name}
          </div>
        );
    }
  };

  return (
    <div role="group" aria-label="Access graph" className="h-[460px] overflow-auto rounded-xl border border-[rgba(0,0,0,0.08)] bg-[rgba(0,0,0,0.015)]">
      <div className="relative" style={{ width: layout.width * zoom, height: layout.height * zoom }}>
        <div
          className="absolute left-0 top-0"
          style={{ width: layout.width, height: layout.height, transform: `scale(${zoom})`, transformOrigin: '0 0' }}
        >
          <svg
            aria-hidden="true"
            width={layout.width}
            height={layout.height}
            className="pointer-events-none absolute left-0 top-0"
          >
            {layout.edges.map(edge => (
              <path key={edge.id} d={edgePath(edge)} fill="none" stroke="rgba(0,0,0,0.2)" strokeWidth={1.5} />
            ))}
          </svg>
          {layout.nodes.map(renderNode)}
        </div>
      </div>
    </div>
  );
};

export default AccessGraphCanvas;
