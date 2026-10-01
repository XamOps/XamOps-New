import React, { useEffect, useMemo, useState } from 'react';
import ReactDOM from 'react-dom';
import { Icon } from '@iconify/react';
import type { DspmAssetDetail, DspmAssetRow, DspmObject } from '../../types/dspm';
import {
  categoryAccent, compactCount, facetNumber, formatBytes, formatConsoleDate, saasDriveLabel,
} from './dspmFormat';
import { DspmObjectIcon, DspmProviderIcon, DspmScanStatus } from './DspmIcons';
import { saasIconKey } from './DspmAssetTable';
import {
  CategoryChip, PagedFooter, entityCountText, entityCountTitle, filesWith, orderedEntities, usePaged,
} from './dspmShared';

/**
 * SaaS account detail, as a side drawer over the list, laid out like the console's.
 *
 * Two tabs: Sensitives (data elements, each expanding to the files that contain it; a file opens its own
 * drawer) and Information (scan summary and properties).
 *
 * Only what the All-Assets API returns is drawn. Absent because the API has no data for them: Backfill
 * and Incremental Scan state (`facet.is_completed` is false even where the console says Completed, so it
 * is not that), Document Classes, the file-type chips under the scan summary, Created On, Last Scan,
 * and on a file its Category, Document Class, Sharing, Details, File Activity, Lineage and File Copies.
 * The API also returns at most 50 files per account, so file lists and per-element counts are drawn from
 * those; counts summed from a sample carry "+".
 */

// ── small pieces ──────────────────────────────────────────────────────────────────────────────

const CloseButton: React.FC<{ onClick: () => void; label: string }> = ({ onClick, label }) => (
  <button
    type="button"
    onClick={onClick}
    aria-label={label}
    className="flex h-7 w-7 flex-shrink-0 items-center justify-center rounded-md border border-[rgba(0,0,0,0.12)] text-[rgba(0,0,0,0.5)] transition-colors hover:bg-[rgba(0,0,0,0.04)] hover:text-[rgba(0,0,0,0.85)]"
  >
    <Icon icon="mdi:close" width={14} />
  </button>
);

/** The label-over-value strip under a drawer's title. */
const InfoStrip: React.FC<{ items: Array<{ label: string; value: React.ReactNode }> }> = ({ items }) => (
  <div className="grid grid-cols-2 gap-4 rounded-xl border border-[rgba(0,0,0,0.08)] bg-[rgba(0,0,0,0.015)] px-4 py-3 sm:grid-cols-4">
    {items.map(item => (
      <div key={item.label} className="min-w-0">
        <div className="text-[10px] text-[rgba(0,0,0,0.45)]">{item.label}</div>
        <div className="mt-1 truncate text-xs font-semibold text-[rgba(0,0,0,0.8)]">{item.value}</div>
      </div>
    ))}
  </div>
);

const Tabs = <T extends string>({ tabs, active, onChange }: {
  tabs: ReadonlyArray<readonly [T, string]>; active: T; onChange: (tab: T) => void;
}) => (
  <div className="flex gap-1 border-b border-[rgba(0,0,0,0.08)]" role="tablist">
    {tabs.map(([id, label]) => (
      <button
        key={id}
        type="button"
        role="tab"
        aria-selected={active === id}
        onClick={() => onChange(id)}
        className={`-mb-px border-b-2 px-3 py-2.5 text-xs font-semibold transition-colors
          ${active === id
            ? 'border-[rgba(0,0,0,0.88)] text-[rgba(0,0,0,0.88)]'
            : 'border-transparent text-[rgba(0,0,0,0.45)] hover:text-[rgba(0,0,0,0.7)]'}`}
      >
        {label}
      </button>
    ))}
  </div>
);

const SearchBox: React.FC<{ value: string; onChange: (v: string) => void; placeholder: string }> =
  ({ value, onChange, placeholder }) => (
    <div className="relative">
      <Icon icon="mdi:magnify" width={13} className="absolute left-2.5 top-1/2 -translate-y-1/2 text-[rgba(0,0,0,0.3)]" />
      <input
        type="search"
        value={value}
        onChange={e => onChange(e.target.value)}
        placeholder={placeholder}
        aria-label={placeholder}
        className="w-60 rounded-lg border border-[rgba(0,0,0,0.08)] bg-[rgba(0,0,0,0.03)] py-1.5 pl-8 pr-3 text-xs focus:outline-none focus:ring-1 focus:ring-[rgba(0,0,0,0.2)]"
      />
    </div>
  );

const TH = 'px-3 py-2.5 text-[11px] font-semibold text-[rgba(0,0,0,0.5)]';

/** An entity name with its category bar, as the console's data-element cells show it. */
const ElementName: React.FC<{ name: string; categoryLabel: string | null }> = ({ name, categoryLabel }) => (
  <span className="flex items-center gap-2">
    <span className={`h-4 w-0.5 flex-shrink-0 rounded ${categoryAccent(categoryLabel).bar}`} />
    <span className="text-[10px] font-semibold uppercase tracking-wide text-[rgba(0,0,0,0.8)]">
      {name || 'Unclassified'}
    </span>
  </span>
);

const fileType = (object: DspmObject) => (object.extension ?? '').toUpperCase() || 'FILE';

// ── file drawer ───────────────────────────────────────────────────────────────────────────────

/** One file's data elements, summed per entity (a file can report the same detector twice). */
function elementsOf(object: DspmObject) {
  const totals = new Map<string, { entityType: string; categoryLabel: string | null; count: number }>();
  for (const finding of object.findings) {
    const current = totals.get(finding.entityType);
    if (current) current.count += finding.count;
    else totals.set(finding.entityType, {
      entityType: finding.entityType, categoryLabel: finding.categoryLabel, count: finding.count,
    });
  }
  return [...totals.values()].sort((a, b) => b.count - a.count);
}

const FileDrawer: React.FC<{
  object: DspmObject;
  owner: string | null;
  account: React.ReactNode;
  onClose: () => void;
}> = ({ object, owner, account, onClose }) => {
  const [query, setQuery] = useState('');
  const elements = useMemo(() => elementsOf(object), [object]);
  const needle = query.trim().toLowerCase();
  const visible = needle ? elements.filter(e => e.entityType.toLowerCase().includes(needle)) : elements;
  const paged = usePaged(visible, needle);

  return (
    <>
      <div className="fixed inset-0 z-[60] bg-[rgba(0,0,0,0.12)]" onClick={onClose} aria-hidden="true" />
      <aside
        role="dialog"
        aria-modal="true"
        aria-label={object.name ?? 'File'}
        className="fixed inset-y-0 right-0 z-[70] flex w-full flex-col bg-white shadow-2xl lg:w-[46vw] lg:max-w-[720px]"
      >
        <div className="flex items-start gap-3 px-5 pb-3 pt-4">
          <span className="mt-1"><DspmObjectIcon objectType={object.objectType} extension={object.extension} /></span>
          <div className="min-w-0 flex-1">
            <h3 className="truncate text-sm font-bold text-[rgba(0,0,0,0.88)]" title={object.name ?? ''}>
              {object.name ?? '(unnamed)'}
            </h3>
            <p className="mt-0.5 text-[10px] font-semibold text-[rgba(0,0,0,0.45)]">{fileType(object)}</p>
          </div>
          <CloseButton onClick={onClose} label="Close file" />
        </div>

        <div className="flex-1 space-y-4 overflow-y-auto px-5 pb-6">
          <InfoStrip items={[
            { label: 'Source/Account', value: account },
            { label: 'Owner', value: owner ?? '—' },
          ]} />

          <Tabs tabs={[['sensitives', 'Sensitives']] as const} active="sensitives" onChange={() => {}} />

          <div className="rounded-xl border border-[rgba(0,0,0,0.08)] p-3">
            <SearchBox value={query} onChange={setQuery} placeholder="Search" />
            <div className="mt-3 overflow-hidden rounded-lg border border-[rgba(0,0,0,0.06)]">
              <table className="w-full text-left">
                <thead>
                  <tr className="border-b border-[rgba(0,0,0,0.06)] bg-[rgba(0,0,0,0.015)]">
                    <th className={TH}>Data Element</th>
                    <th className={TH}>Count</th>
                    <th className={TH}>Label</th>
                  </tr>
                </thead>
                <tbody>
                  {paged.items.map(element => (
                    <tr key={element.entityType} className="border-b border-[rgba(0,0,0,0.04)] last:border-b-0">
                      <td className="px-3 py-2.5"><ElementName name={element.entityType} categoryLabel={element.categoryLabel} /></td>
                      <td className="px-3 py-2.5 text-xs font-semibold tabular-nums text-[rgba(0,0,0,0.75)]" title={element.count.toLocaleString()}>
                        {compactCount(element.count)}
                      </td>
                      <td className="px-3 py-2.5"><CategoryChip label={element.categoryLabel} /></td>
                    </tr>
                  ))}
                  {visible.length === 0 && (
                    <tr><td colSpan={3} className="px-3 py-6 text-center text-xs text-[rgba(0,0,0,0.4)]">
                      {elements.length ? 'No data elements match' : 'No sensitive data found in this file'}
                    </td></tr>
                  )}
                </tbody>
              </table>
            </div>
            <PagedFooter
              always
              className="mt-3"
              page={paged.page}
              totalPages={paged.totalPages}
              size={paged.size}
              total={paged.total}
              onPage={paged.setPage}
              onSize={paged.setSize}
            />
          </div>
        </div>
      </aside>
    </>
  );
};

// ── Sensitives tab ────────────────────────────────────────────────────────────────────────────

/** The files containing one data element, shown when its row is expanded. */
const ElementFiles: React.FC<{ detail: DspmAssetDetail; entityType: string; onOpen: (o: DspmObject) => void }> =
  ({ detail, entityType, onOpen }) => {
    const [query, setQuery] = useState('');
    const all = useMemo(() => filesWith(detail.objects, entityType), [detail.objects, entityType]);
    const needle = query.trim().toLowerCase();
    const visible = needle ? all.filter(f => (f.object.name ?? '').toLowerCase().includes(needle)) : all;
    const paged = usePaged(visible, needle);

    if (all.length === 0) {
      return (
        <p className="px-4 py-4 text-xs text-[rgba(0,0,0,0.45)]">
          Detected in this account, but none of the {detail.objects.length} files the API returns contain
          it, so its files cannot be listed here.
        </p>
      );
    }

    return (
      <div className="space-y-3 px-4 py-3">
        <SearchBox value={query} onChange={setQuery} placeholder="Search files" />
        <div className="overflow-hidden rounded-lg border border-[rgba(0,0,0,0.06)] bg-white">
          <table className="w-full text-left">
            <thead>
              <tr className="border-b border-[rgba(0,0,0,0.06)] bg-[rgba(0,0,0,0.015)]">
                <th className={TH}>File</th>
                <th className={TH}>Count</th>
              </tr>
            </thead>
            <tbody>
              {paged.items.map(({ object, count }) => (
                <tr
                  key={object.id}
                  onClick={() => onOpen(object)}
                  className="cursor-pointer border-b border-[rgba(0,0,0,0.04)] last:border-b-0 hover:bg-[rgba(0,0,0,0.02)]"
                >
                  <td className="px-3 py-2">
                    <span className="flex items-center gap-2">
                      <DspmObjectIcon objectType={object.objectType} extension={object.extension} />
                      <span className="min-w-0">
                        <span className="block max-w-[26rem] truncate text-xs font-semibold text-[rgba(0,0,0,0.85)]" title={object.name ?? ''}>
                          {object.name ?? '(unnamed)'}
                        </span>
                        <span className="text-[10px] text-[rgba(0,0,0,0.45)]">{fileType(object)}</span>
                      </span>
                    </span>
                  </td>
                  <td className="px-3 py-2 text-xs font-semibold tabular-nums text-[rgba(0,0,0,0.7)]" title={count.toLocaleString()}>
                    {compactCount(count)}
                  </td>
                </tr>
              ))}
              {visible.length === 0 && (
                <tr><td colSpan={2} className="px-3 py-6 text-center text-xs text-[rgba(0,0,0,0.4)]">No files match</td></tr>
              )}
            </tbody>
          </table>
        </div>
        <PagedFooter
          always
          className=""
          page={paged.page}
          totalPages={paged.totalPages}
          size={paged.size}
          total={paged.total}
          onPage={paged.setPage}
          onSize={paged.setSize}
        />
      </div>
    );
  };

const SensitivesTab: React.FC<{ detail: DspmAssetDetail; onOpenFile: (o: DspmObject) => void }> =
  ({ detail, onOpenFile }) => {
    const [query, setQuery] = useState('');
    const [open, setOpen] = useState<string | null>(null);
    const entities = useMemo(() => orderedEntities(detail.entityBreakdown), [detail.entityBreakdown]);
    const needle = query.trim().toLowerCase();
    const visible = needle ? entities.filter(e => e.entityType.toLowerCase().includes(needle)) : entities;
    const paged = usePaged(visible, needle);
    const partial = detail.breakdownPartial;

    if (entities.length === 0) {
      return (
        <div className="py-12 text-center">
          <Icon icon="mdi:shield-check-outline" width={30} className="mx-auto text-[rgba(0,0,0,0.15)]" />
          <p className="mt-2 text-xs font-semibold text-[rgba(0,0,0,0.6)]">
            {detail.sensitives.known ? 'No sensitive data found in this account' : 'This account has not been scanned yet'}
          </p>
          {!detail.sensitives.known && (
            <p className="mt-1 text-xs text-[rgba(0,0,0,0.4)]">Status: {detail.scan.statusLabel}.</p>
          )}
        </div>
      );
    }

    return (
      <div className="space-y-3">
        <div className="flex flex-wrap items-center gap-3">
          <span className="rounded-md bg-[rgba(0,0,0,0.88)] px-2.5 py-1 text-[11px] font-semibold text-white">
            Data Elements
          </span>
          <div className="ml-auto"><SearchBox value={query} onChange={setQuery} placeholder="Search" /></div>
        </div>

        <div className="overflow-hidden rounded-xl border border-[rgba(0,0,0,0.08)]">
          <table className="w-full text-left">
            <thead>
              <tr className="border-b border-[rgba(0,0,0,0.06)] bg-[rgba(0,0,0,0.015)]">
                <th className={`${TH} w-8`} aria-label="Expand" />
                <th className={TH}>Data Element</th>
                <th className={TH}>Count</th>
                <th className={TH}>Label</th>
              </tr>
            </thead>
            <tbody>
              {paged.items.map(entity => {
                const expanded = open === entity.entityType;
                return (
                  <React.Fragment key={entity.entityType}>
                    <tr
                      onClick={() => setOpen(expanded ? null : entity.entityType)}
                      aria-expanded={expanded}
                      className={`cursor-pointer border-b border-[rgba(0,0,0,0.04)] ${expanded ? 'bg-[rgba(0,0,0,0.03)]' : 'hover:bg-[rgba(0,0,0,0.02)]'}`}
                    >
                      <td className="px-3 py-2.5 text-[rgba(0,0,0,0.4)]">
                        <Icon icon={expanded ? 'mdi:chevron-down' : 'mdi:chevron-right'} width={14} />
                      </td>
                      <td className="px-3 py-2.5"><ElementName name={entity.entityType} categoryLabel={entity.categoryLabel} /></td>
                      <td className="px-3 py-2.5 text-xs font-semibold tabular-nums text-[rgba(0,0,0,0.75)]" title={entityCountTitle(entity, detail)}>
                        {entityCountText(entity, partial)}
                      </td>
                      <td className="px-3 py-2.5"><CategoryChip label={entity.categoryLabel} /></td>
                    </tr>
                    {expanded && (
                      <tr className="border-b border-[rgba(0,0,0,0.06)] bg-[rgba(0,0,0,0.015)]">
                        <td colSpan={4}><ElementFiles detail={detail} entityType={entity.entityType} onOpen={onOpenFile} /></td>
                      </tr>
                    )}
                  </React.Fragment>
                );
              })}
              {visible.length === 0 && (
                <tr><td colSpan={4} className="px-3 py-6 text-center text-xs text-[rgba(0,0,0,0.4)]">No data elements match</td></tr>
              )}
            </tbody>
          </table>
        </div>

        <PagedFooter
          always
          className=""
          page={paged.page}
          totalPages={paged.totalPages}
          size={paged.size}
          total={paged.total}
          onPage={paged.setPage}
          onSize={paged.setSize}
        />

        {partial && (
          <p className="text-[10px] text-[rgba(0,0,0,0.4)]">
            Counted across the {detail.objects.length} most sensitive files of{' '}
            {detail.objectTotal.toLocaleString()} — the most the API returns. “+” marks a minimum; “—” an
            element detected but not counted.
          </p>
        )}
      </div>
    );
  };

// ── Information tab ───────────────────────────────────────────────────────────────────────────

/** Supported against unsupported files, as the console's Scan Summary ring. */
const Donut: React.FC<{ supported: number; unsupported: number; children: React.ReactNode }> =
  ({ supported, unsupported, children }) => {
    const total = supported + unsupported;
    const radius = 42;
    const circumference = 2 * Math.PI * radius;
    const supportedLength = total > 0 ? (supported / total) * circumference : 0;
    return (
      <div className="relative h-32 w-32 flex-shrink-0">
        <svg viewBox="0 0 100 100" className="h-full w-full -rotate-90" aria-hidden="true">
          <circle cx="50" cy="50" r={radius} fill="none" strokeWidth="9" className="stroke-amber-400" />
          <circle
            cx="50" cy="50" r={radius} fill="none" strokeWidth="9" className="stroke-sky-400"
            strokeDasharray={`${supportedLength} ${circumference}`}
          />
        </svg>
        <div className="absolute inset-0 flex flex-col items-center justify-center text-center">{children}</div>
      </div>
    );
  };

const percent = (part: number, total: number) =>
  total > 0 ? `${((part / total) * 100).toFixed(2)}%` : '0%';

const ScanSummary: React.FC<{ detail: DspmAssetDetail }> = ({ detail }) => {
  const f = detail.facet;
  const total = facetNumber(f, 'total_inventories');
  const supported = facetNumber(f, 'supported_files_count');
  const unsupported = facetNumber(f, 'unsupported_files_count');
  const totalSize = facetNumber(f, 'total_size_bytes');
  const supportedSize = facetNumber(f, 'supported_files_size');
  const unsupportedSize = facetNumber(f, 'unsupported_files_size');

  if (total === null || supported === null || unsupported === null) {
    return <p className="text-xs text-[rgba(0,0,0,0.4)]">No scan summary is available for this account yet.</p>;
  }

  const legend = [
    { label: 'Supported Files', dot: 'bg-sky-400', count: supported, size: supportedSize },
    { label: 'Unsupported Files', dot: 'bg-amber-400', count: unsupported, size: unsupportedSize },
  ];

  return (
    <div className="flex flex-wrap items-center gap-8">
      <Donut supported={supported} unsupported={unsupported}>
        <span className="text-lg font-bold tabular-nums text-[rgba(0,0,0,0.88)]" title={total.toLocaleString()}>
          {compactCount(total)}
        </span>
        <span className="text-[9px] text-[rgba(0,0,0,0.5)]">Discovered Files</span>
        {totalSize !== null && <span className="text-[9px] text-[rgba(0,0,0,0.5)]">{formatBytes(totalSize)}</span>}
      </Donut>
      <div className="flex flex-wrap gap-8">
        {legend.map(item => (
          <div key={item.label}>
            <div className="flex items-center gap-1.5 text-[11px] text-[rgba(0,0,0,0.55)]">
              <span className={`h-2 w-2 rounded-full ${item.dot}`} />
              {item.label}
            </div>
            <div className="mt-1 text-sm font-bold tabular-nums text-[rgba(0,0,0,0.88)]">
              {item.count.toLocaleString()}{' '}
              <span className="text-[11px] font-normal text-[rgba(0,0,0,0.5)]">
                ({percent(item.count, supported + unsupported)})
              </span>
            </div>
            {item.size !== null && (
              <div className="text-[11px] text-[rgba(0,0,0,0.5)]">{formatBytes(item.size)}</div>
            )}
          </div>
        ))}
      </div>
    </div>
  );
};

const PropertyRow: React.FC<{ label: string; children: React.ReactNode }> = ({ label, children }) => (
  <div>
    <div className="text-[10px] text-[rgba(0,0,0,0.45)]">{label}</div>
    <div className="mt-1 text-xs font-semibold text-[rgba(0,0,0,0.8)]">{children}</div>
  </div>
);

const InformationTab: React.FC<{ detail: DspmAssetDetail }> = ({ detail }) => {
  const size = facetNumber(detail.facet, 'total_size_bytes');
  return (
    <div className="grid grid-cols-1 items-start gap-4 lg:grid-cols-[1fr_260px]">
      <section className="rounded-xl border border-[rgba(0,0,0,0.08)] p-4">
        <h4 className="mb-4 border-b border-[rgba(0,0,0,0.06)] pb-2 text-[11px] font-bold text-[rgba(0,0,0,0.75)]">
          Scan Summary
        </h4>
        <ScanSummary detail={detail} />
      </section>
      <section className="space-y-3 rounded-xl border border-[rgba(0,0,0,0.08)] p-4">
        <h4 className="border-b border-[rgba(0,0,0,0.06)] pb-2 text-[11px] font-bold text-[rgba(0,0,0,0.75)]">
          Properties
        </h4>
        <PropertyRow label="Owner">
          <span className="flex items-center gap-2">
            <span className="flex h-5 w-5 flex-shrink-0 items-center justify-center rounded-full bg-[rgba(0,0,0,0.85)] text-[9px] font-bold text-white">
              {(detail.name || '?').charAt(0).toUpperCase()}
            </span>
            <span className="truncate" title={detail.externalId ?? undefined}>{detail.name}</span>
          </span>
        </PropertyRow>
        {detail.externalId && <PropertyRow label="Email">{detail.externalId}</PropertyRow>}
        {size !== null && <PropertyRow label="Datastore size">{formatBytes(size)}</PropertyRow>}
        {/* The same timestamp as the list's Last Modified column. The console's Last Scan here is a
            different time that the API does not return, so it is not shown under that name. */}
        <PropertyRow label="Last Modified">
          {detail.scan.lastScannedAt ? formatConsoleDate(detail.scan.lastScannedAt) : '—'}
        </PropertyRow>
      </section>
    </div>
  );
};

// ── drawer ────────────────────────────────────────────────────────────────────────────────────

interface Props {
  detail: DspmAssetDetail | null;
  loading: boolean;
  error: string | null;
  /** The clicked row, so the header can render while detail loads. */
  row: DspmAssetRow | null;
  /** Resolved label for the account's integration, e.g. "Google Workspace". */
  accountLabel?: string;
  onClose: () => void;
}

const DspmSaasDrawer: React.FC<Props> = ({ detail, loading, error, row, accountLabel, onClose }) => {
  const [tab, setTab] = useState<'sensitives' | 'information'>('sensitives');
  const [file, setFile] = useState<DspmObject | null>(null);

  // Escape closes the topmost panel only.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== 'Escape') return;
      if (file) setFile(null);
      else onClose();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [file, onClose]);

  const driveType = detail?.facet.drive_type ?? row?.profile?.facet?.drive_type;
  const driveLabel = saasDriveLabel(driveType) ?? detail?.subtypeLabel ?? row?.subtypeLabel ?? null;
  const iconKey = saasIconKey(driveType, detail?.source.provider ?? row?.source.provider ?? null);
  const name = detail?.name ?? row?.name ?? '';
  const owner = detail?.externalId ?? row?.profile?.externalId ?? null;

  const account = (
    <span className="flex items-center gap-1.5">
      <DspmProviderIcon provider={iconKey} size={13} />
      <span className="truncate">{accountLabel ?? '—'}</span>
    </span>
  );

  return ReactDOM.createPortal(
    <>
      <div className="fixed inset-0 z-40 bg-[rgba(0,0,0,0.25)]" onClick={onClose} aria-hidden="true" />
      <aside
        role="dialog"
        aria-modal="true"
        aria-label={name || 'SaaS account'}
        className="fixed inset-y-0 right-0 z-50 flex w-full flex-col bg-white shadow-2xl lg:w-[58vw] lg:max-w-[900px]"
      >
        <div className="flex items-start gap-3 px-5 pb-3 pt-4">
          <span className="mt-0.5"><DspmProviderIcon provider={iconKey} size={22} /></span>
          <div className="min-w-0 flex-1">
            <h2 className="truncate text-base font-bold text-[rgba(0,0,0,0.88)]" title={owner ?? name}>
              {name || (loading ? 'Loading…' : '—')}
            </h2>
            <p className="mt-0.5 text-[11px] text-[rgba(0,0,0,0.45)]">{driveLabel ?? ''}</p>
          </div>
          <CloseButton onClick={onClose} label="Close" />
        </div>

        <div className="flex-1 space-y-4 overflow-y-auto px-5 pb-6">
          <InfoStrip items={[
            {
              label: 'Asset Type',
              value: (
                <span className="flex items-center gap-1.5">
                  <DspmProviderIcon provider={iconKey} size={13} />
                  {driveLabel ?? '—'}
                </span>
              ),
            },
            { label: 'Source/Account', value: account },
            {
              label: 'Scan Status',
              value: detail
                ? <DspmScanStatus
                    status={detail.scan.status}
                    label={detail.scan.status === 'CLASSIFIED' ? 'Scanning Complete' : detail.scan.statusLabel}
                  />
                : '—',
            },
          ]} />

          <Tabs
            tabs={[['sensitives', 'Sensitives'], ['information', 'Information']] as const}
            active={tab}
            onChange={setTab}
          />

          {loading && <p className="py-10 text-center text-xs text-[rgba(0,0,0,0.4)]">Loading account…</p>}
          {error && !loading && (
            <div className="flex items-start gap-2 rounded-lg border border-rose-100 bg-rose-50 p-3 text-xs text-rose-700">
              <Icon icon="mdi:alert-circle-outline" width={14} className="mt-0.5 flex-shrink-0" />
              {error}
            </div>
          )}
          {detail && !loading && tab === 'sensitives' && <SensitivesTab detail={detail} onOpenFile={setFile} />}
          {detail && !loading && tab === 'information' && <InformationTab detail={detail} />}
        </div>
      </aside>

      {file && <FileDrawer object={file} owner={owner} account={account} onClose={() => setFile(null)} />}
    </>,
    document.body,
  );
};

export default DspmSaasDrawer;
