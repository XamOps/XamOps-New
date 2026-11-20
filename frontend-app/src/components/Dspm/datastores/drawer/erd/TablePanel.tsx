import React, { useId, useState } from 'react';
import { Icon } from '@iconify/react';
import { useLazyResource, type DatastoreDrawerApi } from '../../../../../hooks/useDspmDatastoreDrawer';
import { DataFlowColumnsTable } from '../DataFlowColumns';
import { NestedDrawerShell } from '../DrawerShell';
import { DrawerTabList, type DrawerTabDef } from '../DrawerTabList';
import { ResourceView } from '../TabParts';
import TableRelations from './TableRelations';

type TablePanelTab = 'columns' | 'relationships';

const TABS: ReadonlyArray<DrawerTabDef<TablePanelTab>> = [
  { id: 'columns', label: 'Columns' },
  { id: 'relationships', label: 'Relationships' },
];

export interface TablePanelProps {
  api: DatastoreDrawerApi;
  table: { id: string; name: string };
  /** The card's body button, focused again when the panel closes. */
  returnFocus: HTMLElement | null;
  /** Must be stable (`useCallback`), as `NestedDrawerShell` requires. */
  onClose: () => void;
  /** Opened from the full-screen Graph View: the panel stacks above the full-screen layer (review fix R1). */
  elevated: boolean;
}

/**
 * The panel a diagram card opens (design 4.6, 6.8, FR-ERD-9): a nested drawer over the datastore drawer,
 * which dims behind it, 40% of the screen wide from `lg` up with a 28rem floor (DA14; below `lg` it takes
 * the full width, so the floor never overflows a narrow screen). It joins the drawer's Escape stack as
 * its newest layer: Escape or the X closes it first and focus returns to the card.
 *
 * Columns shows build 1's columns table with all four columns and no chooser (review fix R2).
 * Relationships loads the table's keys on its first showing. Both stay mounted once shown, so switching
 * tabs fetches nothing again.
 */
const TablePanel: React.FC<TablePanelProps> = ({ api, table, returnFocus, onClose, elevated }) => {
  const [active, setActive] = useState<TablePanelTab>('columns');
  const relations = useLazyResource(() => api.tableRelations(table.id), active === 'relationships');
  const ids = useId();

  return (
    <NestedDrawerShell
      label={table.name}
      returnFocus={returnFocus}
      onClose={onClose}
      widthClass="lg:w-[40vw] lg:min-w-[28rem]"
      elevated={elevated}
    >
      {({ closeButton, close }) => (
        <>
          <div className="flex items-start gap-3 px-5 pb-3 pt-4">
            <Icon icon="mdi:table" width={20} className="mt-0.5 flex-shrink-0 text-[rgba(0,0,0,0.5)]" />
            <div className="min-w-0 flex-1">
              <h2 className="truncate text-base font-bold text-[rgba(0,0,0,0.88)]" title={table.name}>{table.name}</h2>
              <p className="mt-0.5 text-[11px] font-semibold text-[rgba(0,0,0,0.45)]">Table</p>
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
            <DrawerTabList tabs={TABS} active={active} onSelect={setActive} label="Table details" idPrefix={ids} />
            <div role="tabpanel" id={`${ids}-panel`} aria-labelledby={`${ids}-tab-${active}`}>
              <div hidden={active !== 'columns'}>
                <DataFlowColumnsTable api={api} tableId={table.id} />
              </div>
              {active === 'relationships' && (
                <ResourceView resource={relations}>{loaded => <TableRelations relations={loaded} />}</ResourceView>
              )}
            </div>
          </div>
        </>
      )}
    </NestedDrawerShell>
  );
};

export default TablePanel;
