import React, { useEffect, useState } from 'react';
import ReactDOM from 'react-dom';
import { Icon } from '@iconify/react';
import type { DspmAssetDetail } from '../../types/dspm';

/**
 * Asset detail panel.
 *
 * Two tabs, which is all the All-Assets API can feed: **Overview** from `facet` plus the rollups, and
 * **Sensitive data** from `objects[].findings[]`. The vendor console also offers Misconfigurations, an
 * Access Graph and Scan History; none of those is exposed by this API, so no placeholder tabs are shown
 * for them.
 */

/** Human labels for the facet keys seen across datastore types. Unknown keys fall back to prettifying. */
const FACET_LABELS: Record<string, string> = {
  arn: 'ARN',
  region: 'Region',
  engine: 'Engine',
  engine_version: 'Engine version',
  cloud_account_id: 'Cloud account',
  gcp_project_id: 'GCP project',
  storage_size_bytes: 'Size',
  number_of_records: 'Records',
  is_protected: 'Protected',
  able_to_scan: 'Scannable',
  snapshot_available: 'Snapshot available',
  next_scheduled_time: 'Next scan',
  rds_type: 'RDS type',
  host: 'Host',
  port: 'Port',
};

const prettifyKey = (key: string) =>
  key.replace(/_/g, ' ').replace(/\b\w/g, c => c.toUpperCase());

const formatBytes = (bytes: number): string => {
  if (bytes <= 0) return '0 B';
  const units = ['B', 'KB', 'MB', 'GB', 'TB', 'PB'];
  const i = Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), units.length - 1);
  return `${(bytes / 1024 ** i).toFixed(i === 0 ? 0 : 2)} ${units[i]}`;
};

/** Epoch **seconds**, not milliseconds — the vendor sends seconds throughout. */
const formatEpochSeconds = (seconds: number) =>
  new Date(seconds * 1000).toLocaleString(undefined, {
    year: 'numeric', month: 'short', day: '2-digit', hour: '2-digit', minute: '2-digit',
  });

function formatFacetValue(key: string, value: unknown): string {
  if (value === null || value === undefined) return '—';
  if (typeof value === 'boolean') return value ? 'Yes' : 'No';
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

const Row: React.FC<{ label: string; value: React.ReactNode; mono?: boolean }> = ({ label, value, mono }) => (
  <div className="flex items-start gap-3 border-b border-[rgba(0,0,0,0.04)] py-2 last:border-b-0">
    <span className="w-32 flex-shrink-0 text-[11px] text-[rgba(0,0,0,0.45)]">{label}</span>
    <span className={`min-w-0 flex-1 break-all text-xs text-[rgba(0,0,0,0.8)] ${mono ? 'font-mono text-[11px]' : ''}`}>
      {value}
    </span>
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

  // Escape closes, which is expected of a panel opened from a table row.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);

  return ReactDOM.createPortal(
    <div className="fixed inset-0 z-50 flex justify-end">
      <div className="fixed inset-0 bg-black/30" onClick={onClose} />
      <div
        className="relative z-10 flex h-full w-full max-w-2xl flex-col border-l border-[rgba(0,0,0,0.12)] bg-white shadow-xl"
        onClick={e => e.stopPropagation()}
      >
        {/* ── Header ── */}
        <div className="flex items-start gap-3 border-b border-[rgba(0,0,0,0.06)] px-5 py-4">
          <div className="mt-0.5 flex h-9 w-9 flex-shrink-0 items-center justify-center rounded-xl bg-[rgba(0,0,0,0.88)]">
            <Icon icon="mdi:database-outline" width={18} color="white" />
          </div>
          <div className="min-w-0 flex-1">
            <h2 className="truncate text-sm font-bold text-[rgba(0,0,0,0.88)]" title={detail?.name}>
              {detail?.name ?? 'Loading…'}
            </h2>
            <p className="mt-0.5 text-[11px] text-[rgba(0,0,0,0.45)]">
              {detail?.subtypeLabel ?? detail?.subtype ?? ''}
              {detail?.source.region ? ` · ${detail.source.region}` : ''}
              {detail?.scan.statusLabel ? ` · ${detail.scan.statusLabel}` : ''}
            </p>
          </div>
          <button
            onClick={onClose}
            className="rounded-lg p-2 text-[rgba(0,0,0,0.4)] transition-colors hover:bg-[rgba(0,0,0,0.05)]"
            aria-label="Close"
          >
            <Icon icon="mdi:close" width={16} />
          </button>
        </div>

        {/* ── Tabs ── */}
        <div className="flex gap-1 border-b border-[rgba(0,0,0,0.06)] px-5">
          {([['overview', 'Overview'], ['sensitive', 'Sensitive data']] as const).map(([id, label]) => (
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

          {detail && !loading && tab === 'overview' && (
            <div className="space-y-5">
              {/* Rollups */}
              <section>
                <h3 className="mb-2 text-[10px] font-semibold uppercase tracking-wider text-[rgba(0,0,0,0.45)]">
                  Summary
                </h3>
                <div className="rounded-xl border border-[rgba(0,0,0,0.08)] px-4 py-2">
                  <Row
                    label="Risk score"
                    value={detail.risk.score === null ? 'Not evaluated' : detail.risk.score.toFixed(2)}
                  />
                  <Row
                    label="Severity"
                    value={detail.risk.hasFinding
                      ? detail.risk.level.charAt(0) + detail.risk.level.slice(1).toLowerCase()
                      : 'No policy evaluated'}
                  />
                  {/* Shown even when severity is absent: the two are independent upstream, and one live
                      asset carries 35 violations with no severity at all. */}
                  {detail.risk.policyViolationCount !== null && (
                    <Row label="Policy violations" value={detail.risk.policyViolationCount.toLocaleString()} />
                  )}
                  <Row
                    label="Sensitive records"
                    value={detail.sensitives.known
                      ? detail.sensitives.recordCount.toLocaleString()
                      // Never a bare zero: unscanned, failed and stopped assets all report zero, and
                      // presenting that as a finding would assert a clean result nobody produced.
                      : <span className="italic text-[rgba(0,0,0,0.4)]">
                          Not available — {detail.scan.statusLabel.toLowerCase()}
                        </span>}
                  />
                  <Row label="Objects discovered" value={detail.objectTotal.toLocaleString()} />
                  {detail.sensitives.typeLabels.length > 0 && (
                    <Row label="Sensitive data" value={detail.sensitives.typeLabels.join(', ')} />
                  )}
                  {detail.scan.lastScannedAt && (
                    <Row label="Last scanned" value={formatEpochSeconds(detail.scan.lastScannedAt)} />
                  )}
                  {detail.externalId && <Row label="Identifier" value={detail.externalId} mono />}
                </div>
              </section>

              {/* Facet — rendered from whatever keys are present, since they differ per asset type and
                  empty ones are omitted upstream. */}
              {Object.keys(detail.facet).length > 0 && (
                <section>
                  <h3 className="mb-2 text-[10px] font-semibold uppercase tracking-wider text-[rgba(0,0,0,0.45)]">
                    Properties
                  </h3>
                  <div className="rounded-xl border border-[rgba(0,0,0,0.08)] px-4 py-2">
                    {Object.entries(detail.facet).map(([key, value]) => (
                      <Row
                        key={key}
                        label={FACET_LABELS[key] ?? prettifyKey(key)}
                        value={formatFacetValue(key, value)}
                        mono={key === 'arn' || key === 'cloud_account_id'}
                      />
                    ))}
                  </div>
                </section>
              )}
            </div>
          )}

          {detail && !loading && tab === 'sensitive' && (
            <div className="space-y-5">
              {detail.entityBreakdown.length === 0 && detail.objects.length === 0 && (
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

              {detail.entityBreakdown.length > 0 && (
                <section>
                  <div className="mb-2 flex items-center gap-2">
                    <h3 className="text-[10px] font-semibold uppercase tracking-wider text-[rgba(0,0,0,0.45)]">
                      Detected entities
                    </h3>
                    {/* The breakdown is aggregated from the returned objects, and the vendor caps those at
                        50 with no way to page further. Presenting a partial sum as a total would
                        understate the asset, sometimes by an order of magnitude. */}
                    {detail.breakdownPartial && (
                      <span className="rounded border border-amber-200 bg-amber-50 px-1.5 py-0.5 text-[10px] font-semibold text-amber-700">
                        partial
                      </span>
                    )}
                  </div>
                  {detail.breakdownPartial && (
                    <p className="mb-2 text-[11px] text-[rgba(0,0,0,0.45)]">
                      Counted across the {detail.objects.length} objects below, out of{' '}
                      {detail.objectTotal.toLocaleString()} in total — treat these as minimums.
                    </p>
                  )}
                  <div className="rounded-xl border border-[rgba(0,0,0,0.08)] overflow-hidden">
                    {detail.entityBreakdown.map(entity => (
                      <div
                        key={entity.entityType}
                        className="flex items-center gap-3 border-b border-[rgba(0,0,0,0.04)] px-4 py-2 last:border-b-0"
                      >
                        <span className="min-w-0 flex-1 truncate text-xs font-semibold text-[rgba(0,0,0,0.8)]">
                          {entity.entityType}
                        </span>
                        {entity.categoryLabel && (
                          <span className="flex-shrink-0 rounded border border-[rgba(0,0,0,0.1)] bg-[rgba(0,0,0,0.03)] px-1.5 py-0.5 text-[10px] font-semibold text-[rgba(0,0,0,0.55)]">
                            {entity.categoryLabel}
                          </span>
                        )}
                        <span className="w-20 flex-shrink-0 text-right text-xs tabular-nums text-[rgba(0,0,0,0.7)]">
                          {entity.count.toLocaleString()}
                        </span>
                      </div>
                    ))}
                  </div>
                </section>
              )}

              {detail.objects.length > 0 && (
                <section>
                  <h3 className="mb-2 text-[10px] font-semibold uppercase tracking-wider text-[rgba(0,0,0,0.45)]">
                    Objects
                  </h3>
                  <div className="space-y-2">
                    {detail.objects.map(object => (
                      <div key={object.id} className="rounded-xl border border-[rgba(0,0,0,0.08)] px-4 py-3">
                        <div className="flex items-start gap-2">
                          <Icon
                            icon={object.objectType === 'COLUMN' ? 'mdi:table-column' : 'mdi:file-outline'}
                            width={13}
                            className="mt-0.5 flex-shrink-0 text-[rgba(0,0,0,0.3)]"
                          />
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
                                    cloud credentials, which is also how the vendor console presents it. */}
                                <CopyButton text={object.path} />
                              </div>
                            )}
                          </div>
                          {object.sensitiveCount > 0 && (
                            <span className="flex-shrink-0 text-xs font-semibold tabular-nums text-[rgba(0,0,0,0.7)]">
                              {object.sensitiveCount.toLocaleString()}
                            </span>
                          )}
                        </div>

                        {object.findings.length > 0 && (
                          <div className="mt-2 flex flex-wrap gap-1 border-t border-[rgba(0,0,0,0.04)] pt-2">
                            {object.findings.map((finding, i) => (
                              <span
                                key={`${finding.entityType}-${i}`}
                                className="inline-flex items-center gap-1 rounded border border-[rgba(0,0,0,0.08)] bg-[rgba(0,0,0,0.02)] px-1.5 py-0.5 text-[10px] text-[rgba(0,0,0,0.6)]"
                              >
                                <span className="font-semibold">{finding.entityType}</span>
                                <span className="tabular-nums text-[rgba(0,0,0,0.4)]">
                                  {finding.count.toLocaleString()}
                                </span>
                              </span>
                            ))}
                          </div>
                        )}
                      </div>
                    ))}
                  </div>

                  {detail.objectsTruncated && (
                    // The cap is invisible from the array alone, so it has to be stated. 50 of 5,211 is
                    // not "the contents of this bucket".
                    <p className="mt-3 flex items-center gap-1.5 text-[11px] text-[rgba(0,0,0,0.45)]">
                      <Icon icon="mdi:information-outline" width={12} />
                      Showing the {detail.objects.length} most sensitive objects of{' '}
                      {detail.objectTotal.toLocaleString()}.
                    </p>
                  )}
                </section>
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
