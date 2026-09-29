import React, { useEffect, useMemo, useState } from 'react';
import ReactDOM from 'react-dom';
import { Icon } from '@iconify/react';
import type { DspmAssetDetail, DspmEntityCount } from '../../types/dspm';
import {
  categoryAccent, compactCount, formatBytes, formatEpochSeconds,
  sensitivityClasses, sensitivityLabel,
} from './dspmFormat';
import { DspmObjectIcon, DspmProviderIcon, DspmScanStatus } from './DspmIcons';

/** Severity glyphs, mirroring DspmIcons' badge so the drawer header matches the table. */
const SEVERITY_GLYPH: Record<string, string> = {
  Critical: 'mdi:alert-octagon',
  High: 'mdi:alert',
  Medium: 'mdi:alert-outline',
  Low: 'mdi:information-outline',
};

/**
 * Asset detail, as a panel filling the content area.
 *
 * <h3>Why it stops at the sidebar</h3>
 * It spans from the right edge to `lg:left-[220px]`, the sidebar's width, rather than covering the
 * viewport. The vendor shows this as a full-page view with a Back link, and a narrow overlay drawer
 * could not hold their two-column layout — Properties on the left, Files/Sensitive Records on the
 * right — without everything wrapping. Leaving the sidebar exposed also keeps the rest of the product
 * navigable, which a full-screen takeover does not.
 *
 * On small screens (`< lg`) the sidebar is translated off-canvas and the content area is full width, so
 * the panel goes edge to edge and `left-0` is correct there.
 *
 * <h3>Two tabs, not five</h3>
 * Overview and Sensitive data are what the All-Assets API can feed. The console also offers
 * Misconfigurations, Access Graph and Scan History; none is exposed by this API, so no placeholder tabs
 * are shown for them.
 */

/** Human labels for the facet keys seen across datastore types. Unknown keys fall back to prettifying. */
const FACET_LABELS: Record<string, string> = {
  cloud_account_id: 'Cloud Account',
  region: 'Region',
  storage_size_bytes: 'Datastore Size',
  arn: 'ARN',
  engine: 'Engine',
  engine_version: 'Engine version',
  gcp_project_id: 'GCP project',
  number_of_records: 'Records',
  is_protected: 'Protected',
  able_to_scan: 'Scannable',
  snapshot_available: 'Snapshot available',
  next_scheduled_time: 'Next scan',
  rds_type: 'RDS type',
  host: 'Host',
  port: 'Port',
};

/**
 * Facet keys shown in the Properties card, in the console's order.
 *
 * Ordered rather than rendered as-received so the card reads the same way theirs does — Cloud Account,
 * Region, Datastore Size, ARN. Anything not listed still renders, after these.
 */
const FACET_ORDER = ['cloud_account_id', 'region', 'storage_size_bytes', 'arn'];

const prettifyKey = (key: string) =>
  key.replace(/_/g, ' ').replace(/\b\w/g, c => c.toUpperCase());

function formatFacetValue(key: string, value: unknown): string {
  if (value === null || value === undefined) return '—';
  if (typeof value === 'boolean') return value ? 'Enabled' : 'Disabled';
  if (Array.isArray(value)) return value.length ? value.join(', ') : '—';
  if (typeof value === 'number') {
    if (key.endsWith('_bytes')) return formatBytes(value);
    // Anything named like a timestamp is epoch seconds. Guarded by magnitude so a genuine count in the
    // same range is not mangled into a date.
    if ((key.endsWith('_time') || key.endsWith('_at')) && value > 1_000_000_000) {
      return formatEpochSeconds(value);
    }
    return value.toLocaleString();
  }
  return String(value);
}

/** A boxed read-only value, matching the console's Properties fields. */
const PropertyField: React.FC<{ label: string; value: React.ReactNode; mono?: boolean }> =
  ({ label, value, mono }) => (
    <div>
      <div className="mb-1 text-[10px] text-[rgba(0,0,0,0.4)]">{label}</div>
      <div
        className={`rounded-lg border border-[rgba(0,0,0,0.08)] bg-[rgba(0,0,0,0.02)] px-3 py-2 text-xs text-[rgba(0,0,0,0.8)] break-all ${mono ? 'font-mono text-[11px]' : ''}`}
      >
        {value}
      </div>
    </div>
  );

const CopyButton: React.FC<{ text: string }> = ({ text }) => {
  const [copied, setCopied] = useState(false);
  return (
    <button
      onClick={() => {
        void navigator.clipboard.writeText(text).then(() => {
          setCopied(true);
          setTimeout(() => setCopied(false), 1500);
        });
      }}
      className="flex-shrink-0 rounded p-1 text-[rgba(0,0,0,0.3)] transition-colors hover:bg-[rgba(0,0,0,0.05)] hover:text-[rgba(0,0,0,0.6)]"
      title="Copy path"
    >
      <Icon icon={copied ? 'mdi:check' : 'mdi:content-copy'} width={12} />
    </button>
  );
};

/**
 * Sensitive records, grouped by category.
 *
 * Mirrors the console's panel: a total on the left, then one banded block per category (Personal, IT &
 * Security, Financial) with its entity chips inside. Grouping matters because a flat list of fifteen
 * detectors does not tell you whether the exposure is personal data or credentials.
 */
const SensitiveRecordsPanel: React.FC<{ detail: DspmAssetDetail }> = ({ detail }) => {
  const grouped = useMemo(() => {
    const groups = new Map<string, DspmEntityCount[]>();
    for (const entity of detail.entityBreakdown) {
      const key = entity.categoryLabel ?? 'Other';
      const list = groups.get(key) ?? [];
      list.push(entity);
      groups.set(key, list);
    }
    return [...groups.entries()];
  }, [detail.entityBreakdown]);

  const known = detail.sensitives.known;

  return (
    <section>
      <div className="mb-2 flex items-baseline gap-2">
        <h3 className="text-[11px] font-semibold text-[rgba(0,0,0,0.5)]">Sensitive Records</h3>
        {detail.breakdownPartial && (
          <span
            className="rounded border border-amber-200 bg-amber-50 px-1.5 py-0.5 text-[10px] font-semibold text-amber-700"
            title={`Counted across the ${detail.objects.length} objects returned, of ${detail.objectTotal.toLocaleString()} in total. The API caps objects at 50, so these are minimums.`}
          >
            partial
          </span>
        )}
      </div>

      <div className="flex items-start gap-5">
        <div className="flex-shrink-0">
          {known ? (
            <div
              className="text-2xl font-bold text-rose-600"
              title={detail.sensitives.recordCount.toLocaleString()}
            >
              {compactCount(detail.sensitives.recordCount)}
            </div>
          ) : (
            // Never a bare zero. Unscanned, failed and stopped assets all report zero, and printing it
            // would assert a clean result nobody produced.
            <div className="text-sm italic text-[rgba(0,0,0,0.4)]">
              Not available
              <div className="mt-0.5 text-[10px] not-italic">{detail.scan.statusLabel}</div>
            </div>
          )}
        </div>

        <div className="min-w-0 flex-1 space-y-2">
          {grouped.length === 0 && (
            <p className="text-xs text-[rgba(0,0,0,0.4)]">
              {known ? 'No sensitive data found.' : 'Results appear once a scan completes.'}
            </p>
          )}
          {grouped.map(([category, entities]) => {
            const accent = categoryAccent(category);
            return (
              <div key={category} className="rounded-lg border border-[rgba(0,0,0,0.06)] p-2.5">
                <span className={`inline-flex items-center gap-1.5 rounded border px-1.5 py-0.5 text-[10px] font-semibold ${accent.chip}`}>
                  {category}
                </span>
                <div className="mt-2 flex flex-wrap gap-x-4 gap-y-1.5">
                  {entities.map(entity => (
                    <span key={entity.entityType} className="inline-flex items-center gap-1.5">
                      <span className={`h-3 w-0.5 flex-shrink-0 rounded ${accent.bar}`} />
                      <span className="text-[11px] text-[rgba(0,0,0,0.7)]">{entity.entityType}</span>
                      <span
                        className="text-[11px] font-semibold tabular-nums text-[rgba(0,0,0,0.5)]"
                        title={entity.count.toLocaleString()}
                      >
                        {compactCount(entity.count)}
                      </span>
                    </span>
                  ))}
                </div>
              </div>
            );
          })}
        </div>
      </div>
    </section>
  );
};

type DrawerTab = 'overview' | 'sensitive';

interface Props {
  assetId: string;
  detail: DspmAssetDetail | null;
  loading: boolean;
  error: string | null;
  onClose: () => void;
}

const DspmAssetDrawer: React.FC<Props> = ({ detail, loading, error, onClose }) => {
  const [tab, setTab] = useState<DrawerTab>('overview');

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);

  const severity = sensitivityLabel(detail?.sensitives.level);

  // Facet keys in the console's order, with anything unlisted appended.
  const facetKeys = detail
    ? [
        ...FACET_ORDER.filter(k => k in detail.facet),
        ...Object.keys(detail.facet).filter(k => !FACET_ORDER.includes(k)),
      ]
    : [];

  return ReactDOM.createPortal(
    // Stops at the sidebar's 220px rather than covering it, so the rest of the product stays reachable.
    // z-30 keeps it below the sidebar's z-40 — they do not overlap, but if a future layout change makes
    // them, the nav should win.
    <div className="fixed inset-y-0 right-0 left-0 z-30 flex lg:left-[220px]">
      <div className="flex h-full w-full flex-col bg-white">
        {/* ── Back bar ── */}
        <div className="flex items-center gap-3 border-b border-[rgba(0,0,0,0.06)] px-5 py-2.5">
          <button
            onClick={onClose}
            className="inline-flex items-center gap-1 text-xs font-semibold text-[rgba(0,0,0,0.6)] transition-colors hover:text-[rgba(0,0,0,0.9)]"
          >
            <Icon icon="mdi:arrow-left" width={14} />
            Back
          </button>
        </div>

        {/* ── Asset header ── */}
        <div className="flex items-start gap-3 px-5 py-3">
          {/* The service's own mark, not a generic database tile — same icon this asset shows in the
              list and anywhere else in the product. */}
          <span className="mt-0.5 flex h-8 w-8 flex-shrink-0 items-center justify-center rounded-lg border border-[rgba(0,0,0,0.08)] bg-[rgba(0,0,0,0.02)]">
            <DspmProviderIcon provider={detail?.source.provider ?? detail?.subtype ?? null} size={18} />
          </span>
          <div className="min-w-0 flex-1">
            <h2 className="truncate text-sm font-bold text-[rgba(0,0,0,0.88)]" title={detail?.name}>
              {detail?.name ?? (loading ? 'Loading…' : '—')}
            </h2>
            <p className="mt-0.5 text-[10px] font-semibold uppercase tracking-wide text-[rgba(0,0,0,0.4)]">
              {detail?.subtype ?? ''}
            </p>
          </div>
          <div className="flex flex-shrink-0 items-center gap-2">
            {severity && (
              <span className={`inline-flex items-center gap-1 rounded-full border px-2 py-0.5 text-[11px] font-semibold ${sensitivityClasses(severity)}`}>
                <Icon icon={SEVERITY_GLYPH[severity] ?? 'mdi:information-outline'} width={11} />
                {severity}
              </span>
            )}
            {detail && (
              <span className="inline-flex items-center rounded-full border border-[rgba(0,0,0,0.1)] px-2.5 py-0.5">
                <DspmScanStatus status={detail.scan.status} label={detail.scan.statusLabel} />
              </span>
            )}
          </div>
        </div>

        {/* ── Tabs ── */}
        <div className="flex gap-1 border-b border-[rgba(0,0,0,0.06)] px-5">
          {([['overview', 'Overview'], ['sensitive', 'Sensitive']] as const).map(([id, label]) => (
            <button
              key={id}
              onClick={() => setTab(id)}
              className={`-mb-px border-b-2 px-3 py-2.5 text-xs font-semibold transition-colors
                ${tab === id
                  ? 'border-[rgba(0,0,0,0.88)] text-[rgba(0,0,0,0.88)]'
                  : 'border-transparent text-[rgba(0,0,0,0.45)] hover:text-[rgba(0,0,0,0.7)]'}`}
            >
              {label}
            </button>
          ))}
        </div>

        <div className="flex-1 overflow-y-auto p-5">
          {loading && (
            <p className="py-10 text-center text-xs text-[rgba(0,0,0,0.4)]">Loading asset…</p>
          )}

          {error && !loading && (
            <div className="flex items-start gap-2 rounded-lg border border-rose-100 bg-rose-50 p-3 text-xs text-rose-700">
              <Icon icon="mdi:alert-circle-outline" width={14} className="mt-0.5 flex-shrink-0" />
              {error}
            </div>
          )}

          {/* Two columns, as the console lays it out: Properties left, rollups right. */}
          {detail && !loading && tab === 'overview' && (
            <div className="grid grid-cols-1 gap-5 lg:grid-cols-[minmax(240px,300px)_1fr]">
              <section className="space-y-3 rounded-xl border border-[rgba(0,0,0,0.08)] p-4">
                <h3 className="text-[11px] font-semibold text-[rgba(0,0,0,0.5)]">Properties</h3>
                {facetKeys.length === 0 && (
                  <p className="text-xs text-[rgba(0,0,0,0.4)]">
                    No properties reported for this asset type.
                  </p>
                )}
                {facetKeys.map(key => (
                  <PropertyField
                    key={key}
                    label={FACET_LABELS[key] ?? prettifyKey(key)}
                    value={formatFacetValue(key, detail.facet[key])}
                    mono={key === 'arn' || key === 'cloud_account_id'}
                  />
                ))}
                {detail.externalId && !('arn' in detail.facet) && (
                  <PropertyField label="Identifier" value={detail.externalId} mono />
                )}
              </section>

              <div className="space-y-5">
                <section>
                  <h3 className="mb-2 text-[11px] font-semibold text-[rgba(0,0,0,0.5)]">
                    Objects discovered
                  </h3>
                  <div className="flex items-baseline gap-2">
                    <span
                      className="text-2xl font-bold text-[rgba(0,0,0,0.88)]"
                      title={detail.objectTotal.toLocaleString()}
                    >
                      {compactCount(detail.objectTotal)}
                    </span>
                    <span className="text-[11px] text-[rgba(0,0,0,0.45)]">
                      files, tables and columns
                    </span>
                  </div>
                  {/* The console shows a per-file-type breakdown here. That is not in the API, so the
                      total stands alone rather than being padded with invented categories. */}
                  <div className="mt-2 flex flex-wrap gap-3 text-[11px] text-[rgba(0,0,0,0.5)]">
                    {detail.risk.score !== null && (
                      <span>Risk score <strong className="text-[rgba(0,0,0,0.75)]">{detail.risk.score.toFixed(2)}</strong></span>
                    )}
                    {detail.risk.policyViolationCount !== null && (
                      <span>Policy violations <strong className="text-[rgba(0,0,0,0.75)]">{detail.risk.policyViolationCount}</strong></span>
                    )}
                    {detail.scan.lastScannedAt && (
                      <span>Last scanned {formatEpochSeconds(detail.scan.lastScannedAt)}</span>
                    )}
                  </div>
                </section>

                <SensitiveRecordsPanel detail={detail} />
              </div>
            </div>
          )}

          {detail && !loading && tab === 'sensitive' && (
            <div className="space-y-5">
              <SensitiveRecordsPanel detail={detail} />

              {detail.objects.length > 0 && (
                <section>
                  <h3 className="mb-2 text-[11px] font-semibold text-[rgba(0,0,0,0.5)]">
                    Files and objects
                  </h3>
                  <div className="overflow-hidden rounded-xl border border-[rgba(0,0,0,0.08)]">
                    {detail.objects.map(object => (
                      <div
                        key={object.id}
                        className="border-b border-[rgba(0,0,0,0.04)] px-4 py-3 last:border-b-0"
                      >
                        <div className="flex items-start gap-2">
                          <span className="mt-0.5">
                            {/* Extension-aware, so a spreadsheet and a PDF are distinguishable at a
                                glance the way they are in the console's file lists. */}
                            <DspmObjectIcon
                              objectType={object.objectType}
                              extension={object.extension}
                            />
                          </span>
                          <div className="min-w-0 flex-1">
                            <div className="truncate text-xs font-semibold text-[rgba(0,0,0,0.85)]" title={object.name ?? ''}>
                              {object.name ?? '(unnamed)'}
                            </div>
                            {object.path && (
                              <div className="mt-0.5 flex items-start gap-1">
                                <span
                                  className="min-w-0 flex-1 break-all font-mono text-[10px] text-[rgba(0,0,0,0.4)]"
                                  title={object.path}
                                >
                                  {object.path}
                                </span>
                                {/* Copy rather than link: reaching the object needs the customer's own
                                    cloud credentials, which is how the console presents it too. */}
                                <CopyButton text={object.path} />
                              </div>
                            )}
                          </div>
                          {object.sensitiveCount > 0 && (
                            <span
                              className="flex-shrink-0 text-xs font-semibold tabular-nums text-[rgba(0,0,0,0.7)]"
                              title={object.sensitiveCount.toLocaleString()}
                            >
                              {compactCount(object.sensitiveCount)}
                            </span>
                          )}
                        </div>

                        {object.findings.length > 0 && (
                          <div className="mt-2 flex flex-wrap gap-1 border-t border-[rgba(0,0,0,0.04)] pt-2">
                            {object.findings.map((finding, i) => {
                              const accent = categoryAccent(finding.categoryLabel);
                              return (
                                <span
                                  key={`${finding.entityType}-${i}`}
                                  className={`inline-flex items-center gap-1 rounded border px-1.5 py-0.5 text-[10px] ${accent.chip}`}
                                >
                                  <span className="font-semibold">{finding.entityType}</span>
                                  <span className="tabular-nums opacity-70">
                                    {compactCount(finding.count)}
                                  </span>
                                </span>
                              );
                            })}
                          </div>
                        )}
                      </div>
                    ))}
                  </div>

                  {detail.objectsTruncated && (
                    // The cap is invisible from the array alone, so it has to be stated. 50 of 4,272 is
                    // not "the contents of this bucket".
                    <p className="mt-3 flex items-center gap-1.5 text-[11px] text-[rgba(0,0,0,0.45)]">
                      <Icon icon="mdi:information-outline" width={12} />
                      Showing the {detail.objects.length} most sensitive objects of{' '}
                      {detail.objectTotal.toLocaleString()}.
                    </p>
                  )}
                </section>
              )}

              {detail.objects.length === 0 && (
                <div className="py-10 text-center">
                  <Icon icon="mdi:shield-check-outline" width={30} className="mx-auto text-[rgba(0,0,0,0.15)]" />
                  <p className="mt-2 text-xs font-semibold text-[rgba(0,0,0,0.6)]">
                    {detail.sensitives.known
                      ? 'No sensitive data found in this asset'
                      : 'This asset has not been scanned yet'}
                  </p>
                  {!detail.sensitives.known && (
                    <p className="mt-1 text-xs text-[rgba(0,0,0,0.4)]">
                      Status: {detail.scan.statusLabel}. Results appear once a scan completes.
                    </p>
                  )}
                </div>
              )}
            </div>
          )}
        </div>
      </div>
    </div>,
    document.body,
  );
};

export default DspmAssetDrawer;
