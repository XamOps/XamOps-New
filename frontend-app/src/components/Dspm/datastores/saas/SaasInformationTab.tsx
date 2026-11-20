import React from 'react';
import type { SaasScanSummary, SaasScanSummaryPart } from '../../../../types/dspmDrawers';
import { InfoTooltip } from '../../../ui/Tooltip';
import { abbreviate, formatBytes, formatConsoleDate } from '../datastoreFormat';
import ScanSummaryDonut from './ScanSummaryDonut';
import { SaasCard } from './SaasParts';
import { formatConsoleDay, initialOf, percentShare } from './saasFormat';

/** DA9 [O3]: the console's ⓘ is recorded, not its text. */
const UNSUPPORTED_HINT = 'Files that were discovered but cannot be scanned for sensitive data.';

const LegendRow: React.FC<{ swatch: string; label: string; hint?: string; part: SaasScanSummaryPart; total: number }> = ({
  swatch, label, hint, part, total,
}) => (
  <div data-legend={label} className="flex flex-wrap items-center gap-x-2 gap-y-0.5 text-xs">
    <span aria-hidden="true" className={`h-2.5 w-2.5 flex-shrink-0 rounded-sm ${swatch}`} />
    <span className="text-[rgba(0,0,0,0.65)]">{label}</span>
    {hint && <InfoTooltip>{hint}</InfoTooltip>}
    <span className="font-semibold tabular-nums text-[rgba(0,0,0,0.85)]" title={part.count.toLocaleString('en-US')}>
      {abbreviate(part.count)}
    </span>
    <span className="tabular-nums text-[rgba(0,0,0,0.45)]">({percentShare(part.count, total)})</span>
    <span className="tabular-nums text-[rgba(0,0,0,0.65)]">{formatBytes(part.sizeBytes)}</span>
  </div>
);

const Property: React.FC<{ label: string; children: React.ReactNode }> = ({ label, children }) => (
  <div className="flex items-start justify-between gap-3">
    <dt className="flex-shrink-0 text-[11px] text-[rgba(0,0,0,0.45)]">{label}</dt>
    <dd className="min-w-0 break-all text-right text-xs font-semibold text-[rgba(0,0,0,0.8)]">{children}</dd>
  </div>
);

/**
 * The SaaS drawer's Information tab (FR-SAAS-7), in two columns like the datastore drawer's: the Scan
 * Summary card (the ring, the supported and unsupported legend, one chip per file type in the backend's
 * order) and the Properties card. The datastore's own size reads "-" when Matters reports 0.
 */
const SaasInformationTab: React.FC<{ summary: SaasScanSummary }> = ({ summary }) => {
  const { supported, unsupported, fileTypes, properties } = summary;
  const total = supported.count + unsupported.count;
  const size = properties.datastoreSizeBytes;
  return (
    <div className="grid grid-cols-1 items-start gap-4 md:grid-cols-[1fr_minmax(260px,340px)]">
      <SaasCard title="Scan Summary">
        <div className="flex flex-wrap items-center gap-5">
          <ScanSummaryDonut supported={supported} unsupported={unsupported} />
          <div className="space-y-2">
            <LegendRow swatch="bg-sky-500" label="Supported Files" part={supported} total={total} />
            <LegendRow swatch="bg-[rgba(0,0,0,0.2)]" label="Unsupported Files" hint={UNSUPPORTED_HINT} part={unsupported} total={total} />
          </div>
        </div>
        {fileTypes.length > 0 && (
          <div className="mt-4 flex flex-wrap gap-1.5">
            {fileTypes.map(type => (
              <span
                key={type.extension}
                data-file-type={type.extension}
                className="inline-flex items-center gap-1.5 rounded-lg border border-[rgba(0,0,0,0.1)] bg-white px-2 py-1 text-[11px] text-[rgba(0,0,0,0.7)]"
              >
                {type.label ?? type.extension}{' '}
                <span className="font-semibold tabular-nums">{type.count.toLocaleString('en-US')}</span>
              </span>
            ))}
          </div>
        )}
      </SaasCard>
      <SaasCard title="Properties">
        <dl className="space-y-2.5">
          <Property label="Owner">
            {properties.owner ? (
              <span className="inline-flex min-w-0 items-center gap-1.5">
                <span
                  aria-hidden="true"
                  className="inline-flex h-5 w-5 flex-shrink-0 items-center justify-center rounded-full bg-sky-100 text-[10px] font-bold text-sky-700"
                >
                  {initialOf(properties.owner)}
                </span>
                {properties.owner}
              </span>
            ) : '-'}
          </Property>
          <Property label="Datastore size">{size !== null && size > 0 ? formatBytes(size) : '-'}</Property>
          <Property label="Created On">{properties.createdOn === null ? '-' : formatConsoleDay(properties.createdOn)}</Property>
          <Property label="Last Scan">{properties.lastScan === null ? '-' : formatConsoleDate(properties.lastScan)}</Property>
        </dl>
      </SaasCard>
    </div>
  );
};

export default SaasInformationTab;
