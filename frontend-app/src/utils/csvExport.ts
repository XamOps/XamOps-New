/**
 * CSV export for CloudK8s tables (docs/cloudk8s/ui-revamp-design.md §11.1, §13).
 *
 * Cells are quoted per RFC 4180. String cells that a spreadsheet would read as a formula (starting
 * with =, +, -, @, a tab or a carriage return) get a leading `'` (OWASP CSV injection guidance).
 * Numbers are written as numbers, so a negative value stays numeric.
 */

export type CsvCell = string | number | boolean | null | undefined;

const FORMULA_START = /^[=+\-@\t\r]/;

/** One cell as CSV text. */
export function csvCell(value: CsvCell): string {
  if (value === null || value === undefined) return '';
  if (typeof value === 'number') return Number.isFinite(value) ? String(value) : '';
  if (typeof value === 'boolean') return value ? 'true' : 'false';
  const text = FORMULA_START.test(value) ? `'${value}` : value;
  return /[",\r\n]/.test(text) ? `"${text.replace(/"/g, '""')}"` : text;
}

/** A header row and data rows as CSV, lines joined with CRLF. */
export function toCsv(headers: readonly string[], rows: ReadonlyArray<readonly CsvCell[]>): string {
  return [headers, ...rows].map((row) => row.map(csvCell).join(',')).join('\r\n');
}

/** "pods-prod-eks-cluster.csv" from any label: lower-case, safe characters only. */
export function csvFileName(name: string): string {
  const base = name.trim().toLowerCase().replace(/[^a-z0-9._-]+/g, '-').replace(/^-+|-+$/g, '') || 'export';
  return base.endsWith('.csv') ? base : `${base}.csv`;
}

/** Saves `csv` as a file in the browser. A byte-order mark lets spreadsheets read it as UTF-8. */
export function downloadCsv(fileName: string, csv: string): void {
  const blob = new Blob(['\ufeff', csv], { type: 'text/csv;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = csvFileName(fileName);
  link.style.display = 'none';
  document.body.appendChild(link);
  link.click();
  link.remove();
  // Revoked on the next tick so the download has started.
  setTimeout(() => URL.revokeObjectURL(url), 0);
}
