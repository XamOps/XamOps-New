import React from 'react';
import type { SaasFileOverview } from '../../../../types/dspmDrawers';
import { formatBytes, formatConsoleDate } from '../datastoreFormat';
import { formatRelativeLong } from './saasFormat';

const yesNo = (value: boolean | null) => (value === null ? '-' : value ? 'Yes' : 'No');

/**
 * The file's Details tab (FR-SFILE-7): a two-column list of Last Modified (with "2 months ago by <who>"),
 * File Size, Sensitive Records, Policy Violations and Structured, then the text-valued metadata keys in
 * API order. The server keeps only text metadata, so headers, label ids and numeric sizes never arrive.
 */
const SaasFileDetailsTab: React.FC<{ overview: SaasFileOverview }> = ({ overview }) => {
  const at = overview.lastModifiedAt;
  const by = overview.lastModifiedBy;
  const relative = [at === null ? null : formatRelativeLong(at, Date.now()), by ? `by ${by}` : null]
    .filter(Boolean)
    .join(' ');

  const rows: Array<{ key: string; label: string; value: React.ReactNode }> = [
    {
      key: 'lastModified',
      label: 'Last Modified',
      value: (
        <>
          {at === null ? '-' : formatConsoleDate(at)}
          {relative && <span className="mt-0.5 block font-normal text-[rgba(0,0,0,0.45)]">{relative}</span>}
        </>
      ),
    },
    { key: 'size', label: 'File Size', value: formatBytes(overview.sizeBytes ?? 0) },
    { key: 'sensitiveRecords', label: 'Sensitive Records', value: overview.sensitiveRecords.toLocaleString('en-US') },
    { key: 'policyViolations', label: 'Policy Violations', value: overview.policyViolations ?? '-' },
    { key: 'structured', label: 'Structured', value: yesNo(overview.structured) },
    ...overview.metadata.map(entry => ({ key: `metadata:${entry.key}`, label: entry.label ?? entry.key, value: entry.value })),
  ];

  return (
    <dl className="grid grid-cols-[minmax(8rem,14rem)_1fr] gap-x-6 gap-y-3 rounded-xl border border-[rgba(0,0,0,0.08)] p-4">
      {rows.map(row => (
        <React.Fragment key={row.key}>
          <dt className="text-[11px] text-[rgba(0,0,0,0.45)]">{row.label}</dt>
          <dd className="min-w-0 break-all text-xs font-semibold text-[rgba(0,0,0,0.8)]">{row.value}</dd>
        </React.Fragment>
      ))}
    </dl>
  );
};

export default SaasFileDetailsTab;
