/**
 * The accessible table that sits with every chart (design-delta §3.2 "Labels", §2.5). A chart may
 * leave a thin layer unlabelled, but this table always carries every layer and every plotted point,
 * so the data is reachable without reading colour. It is plain greyscale: no series colour appears
 * here, only the numbers.
 *
 * Columns are the series/layers; rows are the x points. Each cell is pre-formatted by the caller
 * (the chart kit never guesses units).
 */
import type { ReactNode } from 'react';

export interface ChartDataColumn {
  /** A stable key. */
  key: string;
  /** The visible column header (the layer / series name). */
  header: string;
}

export interface ChartDataRow {
  /** A stable key. */
  key: string;
  /** The x label (a time, a day, a namespace, a node…). */
  label: string;
  /** Pre-formatted cell values, keyed by column key; "—" for a missing point. */
  values: Record<string, ReactNode>;
}

export interface ChartDataTableProps {
  /** A caption naming what the table lists (for assistive tech). */
  caption: string;
  /** The x-axis column header, e.g. "Time", "Namespace". */
  rowHeader: string;
  columns: ChartDataColumn[];
  rows: ChartDataRow[];
  className?: string;
}

export function ChartDataTable({ caption, rowHeader, columns, rows, className = '' }: ChartDataTableProps) {
  return (
    <details className={`group text-[12px] ${className}`} data-chart-table="">
      <summary className="cursor-pointer select-none text-[12px] text-[rgba(0,0,0,0.6)] marker:text-[rgba(0,0,0,0.4)] dark:text-[rgba(255,255,255,0.6)]">
        Data table
      </summary>
      <div className="mt-1 overflow-x-auto">
        <table className="w-full border-collapse text-left tabular-nums">
          <caption className="sr-only">{caption}</caption>
          <thead>
            <tr className="border-b border-[rgba(0,0,0,0.1)] dark:border-[rgba(255,255,255,0.12)]">
              <th scope="col" className="py-1 pr-3 font-semibold text-[rgba(0,0,0,0.72)] dark:text-[rgba(255,255,255,0.8)]">
                {rowHeader}
              </th>
              {columns.map((c) => (
                <th key={c.key} scope="col" className="py-1 pr-3 text-right font-semibold text-[rgba(0,0,0,0.72)] dark:text-[rgba(255,255,255,0.8)]">
                  {c.header}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {rows.map((row) => (
              <tr key={row.key} className="border-b border-[rgba(0,0,0,0.06)] last:border-b-0 dark:border-[rgba(255,255,255,0.08)]">
                <th scope="row" className="py-1 pr-3 font-normal text-[rgba(0,0,0,0.72)] dark:text-[rgba(255,255,255,0.8)]">
                  {row.label}
                </th>
                {columns.map((c) => (
                  <td key={c.key} className="py-1 pr-3 text-right text-[rgba(0,0,0,0.72)] dark:text-[rgba(255,255,255,0.8)]">
                    {row.values[c.key] ?? '—'}
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </details>
  );
}

export default ChartDataTable;
