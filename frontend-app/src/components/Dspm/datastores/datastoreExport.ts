/**
 * The Datastores export format (FR-EXP-6, design 6.4): the one place to change when the user's Matters
 * format arrives.
 *
 * Each visible table column contributes the parts it displays as separate CSV columns (Datastore and
 * Type, Risk Score and Risk Level, …), with exact numbers and absolute times, never "1.56K" or "2d ago".
 * A missing value is an empty cell. Quoting, the formula guard and line ends are `utils/csvExport.ts`'s.
 */

import type { DatastoreTabSlug } from '../../../constants/xamlensTabs';
import type { DatastoreRow } from '../../../types/dspmDatastores';
import { csvFileName, toCsv, type CsvCell } from '../../../utils/csvExport';
import type { ColumnDef } from './datastoreTabConfig';
import { formatListDate, formatRiskScore } from './datastoreFormat';

/** Rows per list request while exporting. */
export const EXPORT_PAGE_SIZE = 100;
/** The export stops here: 100 pages of 100, the vendor's documented page limit. */
export const EXPORT_MAX_ROWS = 10_000;

export interface ExportField {
  header: string;
  value: (row: DatastoreRow) => CsvCell;
}

/** The CSV columns one visible table column contributes (FR-EXP-6). */
export function exportFields(column: ColumnDef, slug: DatastoreTabSlug): ExportField[] {
  switch (column.id) {
    case 'name':
      return [
        { header: column.label, value: row => row.name },
        { header: slug === 'endpoint' ? 'OS' : 'Type', value: row => row.subtypeLabel },
      ];
    case 'risk':
      return [
        { header: 'Risk Score', value: row => (row.risk.score === null ? '' : formatRiskScore(row.risk.score)) },
        { header: 'Risk Level', value: row => row.risk.levelLabel },
      ];
    case 'sensitiveData':
      return [
        { header: 'Sensitive Records', value: row => (row.scan.status === 'NOT_SCANNED' ? '' : row.sensitives.recordCount) },
        { header: 'Sensitive Types', value: row => row.sensitives.typeLabels.join(', ') },
      ];
    case 'sourceAccount':
      return [
        { header: 'Source / Account', value: row => row.source.accountLabel },
        { header: 'Region', value: row => row.source.region },
      ];
    case 'scanStatus':
      return [
        { header: 'Scan Status', value: row => row.scan.statusLabel },
        { header: 'Scan Time', value: row => (row.scan.displayAt === null ? '' : formatListDate(row.scan.displayAt)) },
      ];
    case 'serialNumber':
      return [{ header: column.label, value: row => row.agent?.serialNumber }];
    case 'agentStatus':
      return [{ header: column.label, value: row => row.agent?.statusLabel ?? row.agent?.status }];
    case 'agentVersion':
      return [{ header: column.label, value: row => row.agent?.version }];
  }
}

/** The CSV of `rows` over the visible `columns`, in table order: one header row, one row per datastore. */
export function buildDatastoreCsv(
  rows: readonly DatastoreRow[],
  columns: readonly ColumnDef[],
  slug: DatastoreTabSlug,
): string {
  const fields = columns.flatMap(column => exportFields(column, slug));
  return toCsv(fields.map(field => field.header), rows.map(row => fields.map(field => field.value(row))));
}

/** "datastores-cloud-2026-11-05.csv", by the viewer's local date. */
export function exportFileName(slug: DatastoreTabSlug, now: Date): string {
  const month = String(now.getMonth() + 1).padStart(2, '0');
  const day = String(now.getDate()).padStart(2, '0');
  return csvFileName(`datastores-${slug}-${now.getFullYear()}-${month}-${day}`);
}
