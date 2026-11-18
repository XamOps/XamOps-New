import React from 'react';
import { Icon } from '@iconify/react';
import type { DataFlowColumnPage } from '../../../../../types/dspmDatastores';
import type { DataFlowErdExternalTable, DataFlowErdNode } from '../../../../../types/dspmDrawers';
import { useLazyResource, type DatastoreDrawerApi } from '../../../../../hooks/useDspmDatastoreDrawer';
import { ResourceView } from '../TabParts';
import { CARD_FOOTER, CARD_HEADER, CHIP_ROW, MAX_CHIPS, MORE_ROW, type ErdBox } from './erdLayout';
import { KeyBadge, SensitiveDot } from './ErdParts';

/** The columns endpoint's id rule: a canonical UUID, in any case. */
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** How many columns an expanded card lists: the first page of 100. */
const CARD_COLUMN_LIMIT = 100;

/** An expanded card's columns: the key badge (PK first), the name and the native type. */
const ColumnList: React.FC<{ page: DataFlowColumnPage }> = ({ page }) => (
  <ul>
    {page.rows.map((column, index) => (
      <li key={column.id ?? `column-${index}`} className="flex items-center gap-1.5 px-3" style={{ height: CHIP_ROW }}>
        <span className="flex w-6 flex-shrink-0 justify-center">
          {column.primaryKey ? <KeyBadge kind="PK" /> : column.foreignKey ? <KeyBadge kind="FK" /> : null}
        </span>
        <span className="min-w-0 flex-1 truncate text-[11px] text-[rgba(0,0,0,0.75)]" title={column.name ?? undefined}>
          {column.name ?? '-'}
        </span>
        <span className="max-w-[45%] flex-shrink-0 truncate text-right text-[10px] text-[rgba(0,0,0,0.45)]" title={column.nativeType ?? undefined}>
          {column.nativeType ?? '-'}
        </span>
      </li>
    ))}
    {page.total > page.rows.length && (
      <li className="px-3 py-1 text-[10px] text-[rgba(0,0,0,0.45)]">
        +{(page.total - page.rows.length).toLocaleString('en-US')} more columns
      </li>
    )}
  </ul>
);

interface ErdTableCardProps {
  node: DataFlowErdNode;
  box: ErdBox;
  /** Shares no connector with the hovered or focused card, or does not match the search. */
  dimmed: boolean;
  expanded: boolean;
  onToggleExpanded: (id: string) => void;
  /** The body was clicked: open the table panel; `opener` gets focus back when the panel closes. */
  onOpen: (opener: HTMLElement) => void;
  /** The pointer entered the card (its id) or left it (null). */
  onHover: (id: string | null) => void;
  /** Focus entered the card (its id) or left it (null). */
  onFocusChange: (id: string | null) => void;
  api: DatastoreDrawerApi;
}

/**
 * One table of the diagram (design 6.8): the table icon, the name, the red sensitives count (0 too), then
 * the key columns as "FK" chips, up to six and "+N more", and the "Show all columns" link.
 *
 * Review fix R9: the card holds the body button (which opens the table panel), then, when expanded, the
 * column list in a sibling `div` with its own loading and error states, then the footer's link button,
 * so no button sits inside another. Expanded, the card lists the table's columns instead of the chips,
 * gets the purple border and is raised over its neighbours; it grows downward and nothing else moves.
 * The columns load on the first expansion and are kept while the card is mounted.
 *
 * Only a canonical UUID can be sent to the columns and relations endpoints, so a card with any other
 * id is display only: no panel and no "Show all columns".
 */
const ErdTableCard: React.FC<ErdTableCardProps> = ({
  node, box, dimmed, expanded, onToggleExpanded, onOpen, onHover, onFocusChange, api,
}) => {
  const interactive = UUID.test(node.id);
  const columns = useLazyResource(() => api.columns(node.id, 1, CARD_COLUMN_LIMIT), expanded && interactive);

  const name = node.name ?? '-';
  const chips = node.keyColumns.slice(0, MAX_CHIPS);
  const more = node.keyColumns.length - chips.length;
  const body = (
    <>
      <span className="flex items-center gap-2 px-3" style={{ height: CARD_HEADER }}>
        <Icon icon="mdi:table" width={16} className="flex-shrink-0 text-[rgba(0,0,0,0.5)]" />
        <span className="min-w-0 flex-1 truncate text-xs font-bold text-[rgba(0,0,0,0.85)]" title={name}>{name}</span>
        <span data-sensitives="" className="inline-flex flex-shrink-0 items-center gap-1 text-[11px] font-semibold tabular-nums text-rose-600">
          <SensitiveDot />
          {node.sensitiveCount ?? 0}
        </span>
      </span>
      {!expanded && chips.map((column, index) => (
        <span key={`${index}:${column}`} data-chip="" className="flex items-center gap-1.5 px-3" style={{ height: CHIP_ROW }}>
          <KeyBadge kind="FK" />
          <span className="min-w-0 truncate text-[11px] text-[rgba(0,0,0,0.7)]" title={column}>{column}</span>
        </span>
      ))}
      {!expanded && more > 0 && (
        <span className="flex items-center px-3 text-[10px] text-[rgba(0,0,0,0.45)]" style={{ height: MORE_ROW }}>+{more} more</span>
      )}
    </>
  );

  return (
    <div
      data-erd-card={node.id}
      onMouseEnter={() => onHover(node.id)}
      onMouseLeave={() => onHover(null)}
      onFocus={() => onFocusChange(node.id)}
      onBlur={e => {
        if (!e.currentTarget.contains(e.relatedTarget as Node | null)) onFocusChange(null);
      }}
      style={{ left: box.x, top: box.y, width: box.w, ...(expanded ? { minHeight: box.h } : { height: box.h }) }}
      className={`absolute flex flex-col rounded-lg bg-white shadow-sm transition-opacity ${
        expanded ? 'z-10 border-2 border-violet-500' : 'overflow-hidden border border-[rgba(0,0,0,0.12)]'
      } ${dimmed ? 'opacity-35' : ''}`}
    >
      {interactive ? (
        <button
          type="button"
          aria-label={name}
          onClick={e => onOpen(e.currentTarget)}
          className="flex w-full min-w-0 flex-col text-left transition-colors hover:bg-[rgba(0,0,0,0.02)]"
        >
          {body}
        </button>
      ) : (
        <div className="flex w-full min-w-0 flex-col">{body}</div>
      )}
      {expanded && (
        <div data-erd-columns="" className="border-t border-[rgba(0,0,0,0.06)] py-1">
          <ResourceView resource={columns}>{page => <ColumnList page={page} />}</ResourceView>
        </div>
      )}
      <div className="mt-auto flex items-center border-t border-[rgba(0,0,0,0.06)] px-3" style={{ height: CARD_FOOTER }}>
        {interactive && (
          <button
            type="button"
            aria-expanded={expanded}
            onClick={() => onToggleExpanded(node.id)}
            className="text-[11px] font-semibold text-sky-700 hover:underline"
          >
            {expanded ? '^ Show less' : 'Show all columns'}
          </button>
        )}
      </div>
    </div>
  );
};

export default ErdTableCard;

/** A table of another schema that a key points at: a dashed box with "schema.table" and no buttons. */
export const ErdExternalCard: React.FC<{ box: ErdBox; table: DataFlowErdExternalTable | undefined; dimmed: boolean }> = ({
  box, table, dimmed,
}) => {
  const label = table ? `${table.schemaName}.${table.tableName}` : box.key;
  return (
    <div
      data-erd-external={box.key}
      title={label}
      style={{ left: box.x, top: box.y, width: box.w, height: box.h }}
      className={`absolute flex items-center gap-2 rounded-lg border border-dashed border-[rgba(0,0,0,0.3)] bg-white px-3 transition-opacity ${
        dimmed ? 'opacity-35' : ''
      }`}
    >
      <Icon icon="mdi:table" width={14} className="flex-shrink-0 text-[rgba(0,0,0,0.4)]" />
      <span className="min-w-0 truncate text-[11px] text-[rgba(0,0,0,0.6)]">{label}</span>
    </div>
  );
};
