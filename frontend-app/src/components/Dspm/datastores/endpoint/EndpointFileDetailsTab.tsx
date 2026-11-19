import React from 'react';
import type { EndpointFileOverview } from '../../../../types/dspmFiles';
import { CopyButton } from '../ui/CopyButton';
import { endpointFileDetails } from './endpointFileTabs';

/** The Endpoint file's Details tab (plan C17): a two-column list of `endpointFileDetails`. */
const EndpointFileDetailsTab: React.FC<{ overview: EndpointFileOverview; scannedAt: number | null }> = ({
  overview, scannedAt,
}) => (
  <dl className="grid grid-cols-[minmax(8rem,14rem)_1fr] gap-x-6 gap-y-3 rounded-xl border border-[rgba(0,0,0,0.08)] p-4">
    {endpointFileDetails(overview, scannedAt).map(row => (
      <React.Fragment key={row.label}>
        <dt className="text-[11px] text-[rgba(0,0,0,0.45)]">{row.label}</dt>
        <dd className="flex min-w-0 items-start gap-1 text-xs font-semibold text-[rgba(0,0,0,0.8)]">
          <span className="min-w-0 break-all">{row.value}</span>
          {row.copyText && <CopyButton text={row.copyText} label="Copy path" />}
        </dd>
      </React.Fragment>
    ))}
  </dl>
);

export default EndpointFileDetailsTab;
