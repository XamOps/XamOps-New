/**
 * DataTable (FEAT-001): the v3 table the flagship slices use — a thin wrapper over K8sDataTable with
 * `stickyHeader` and `numericAlignRight` on by default, and the row→drawer wiring owned here. A row
 * click (or Enter/Space) opens a DetailDrawer built from `rowToSections(row)`; the drawer's open /
 * close state lives in this component, so a caller just passes the columns and a row→sections map.
 *
 * It forwards every other K8sDataTable prop unchanged (search, filters, sort, pagination, CSV,
 * loading / error / empty), so a caller keeps all of that. `expandRow` is intentionally not
 * forwarded: a DataTable opens a drawer, it does not expand rows.
 */
import { useCallback, useState } from 'react';
import { K8sDataTable, type K8sDataTableProps } from './K8sDataTable';
import { DetailDrawer, type DrawerSection } from './DetailDrawer';

type ForwardedProps<T> = Omit<K8sDataTableProps<T>, 'onRowActivate' | 'expandRow' | 'stickyHeader' | 'numericAlignRight'>;

export interface DataTableProps<T> extends ForwardedProps<T> {
  /** The drawer heading for a row, e.g. the pod name. */
  rowTitle: (row: T) => string;
  /** Optional sub-heading for the drawer. */
  rowSubtitle?: (row: T) => string;
  /** The drawer sections for a row; all-empty sections are dropped by DetailDrawer. */
  rowToSections: (row: T) => readonly DrawerSection[];
  /** Keep the header sticky and numeric columns right-aligned (both default on). */
  stickyHeader?: boolean;
  numericAlignRight?: boolean;
}

export function DataTable<T>({
  rowTitle, rowSubtitle, rowToSections, stickyHeader = true, numericAlignRight = true, rowLabel, ...rest
}: DataTableProps<T>) {
  const [active, setActive] = useState<T | null>(null);

  const open = useCallback((row: T) => setActive(row), []);
  const close = useCallback(() => setActive(null), []);

  return (
    <>
      <K8sDataTable<T>
        {...rest}
        rowLabel={rowLabel}
        stickyHeader={stickyHeader}
        numericAlignRight={numericAlignRight}
        onRowActivate={open}
      />
      <DetailDrawer
        open={active !== null}
        onClose={close}
        title={active ? rowTitle(active) : ''}
        subtitle={active && rowSubtitle ? rowSubtitle(active) : undefined}
        sections={active ? rowToSections(active) : []}
      />
    </>
  );
}

export default DataTable;
