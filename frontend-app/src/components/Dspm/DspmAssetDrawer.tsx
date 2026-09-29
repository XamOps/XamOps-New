import React, { useEffect, useMemo, useState } from 'react';
import ReactDOM from 'react-dom';
import { Icon } from '@iconify/react';
import type { DspmAssetDetail, DspmEntityCount, DspmObject } from '../../types/dspm';
import { categoryAccent, compactCount, formatBytes, formatEpochSeconds } from './dspmFormat';
import { DspmCloudMark, DspmObjectIcon, DspmProviderIcon, DspmScanStatus } from './DspmIcons';

/**
 * Asset detail, laid out like the vendor console's datastore page: Overview and Sensitive tabs.
 *
 * Fills the content area up to the sidebar (`lg:left-[220px]`) rather than the whole viewport, so the
 * rest of the product stays reachable. Below `lg` the sidebar is off-canvas and the panel is full width.
 *
 * Only what the All-Assets API returns is drawn. Not shown because the API has no data for them:
 * Misconfigurations / Access Graph / Scan History tabs, Security Configurations, the file-type
 * breakdown, Related Alerts, sample values, false-positive marking and scan actions.
 *
 * The API returns at most 50 objects per asset, most sensitive first. On a larger asset, per-entity
 * counts and file counts built from them are minimums and carry a "+"; an entity the asset reports but
 * none of those 50 objects contain shows "—" (detected, not counted), never 0.
 */

const FACET_LABELS: Record<string, string> = {
  cloud_account_id: 'Cloud Account',
  region: 'Region',
  storage_size_bytes: 'Datastore Size',
  arn: 'ARN',
  engine: 'Engine',
  engine_version: 'Engine version',
  gcp_project_id: 'GCP project',
  is_protected: 'Protected',
  snapshot_available: 'Snapshot available',
  next_scheduled_time: 'Next scan',
  rds_type: 'RDS type',
  host: 'Host',
  port: 'Port',
  // SaaS principals
  drive_type: 'Drive type',
  entity_type: 'Principal type',
  total_inventories: 'Items',
  total_size_bytes: 'Total size',
  supported_files_count: 'Supported files',
  supported_files_size: 'Supported size',
  unsupported_files_count: 'Unsupported files',
  unsupported_files_size: 'Unsupported size',
  is_completed: 'Scan complete',
  is_selected: 'Selected for scan',
  is_unassigned: 'Unassigned',
  // Endpoint hosts
  hostname: 'Hostname',
  serial_number: 'Serial number',
  os: 'OS',
  os_version: 'OS version',
  device_type: 'Device type',
  enrolled_user: 'Enrolled user',
  device_users: 'Device users',
  user: 'User',
  agent_status: 'Agent status',
  agent_version: 'Agent version',
  last_ping_time: 'Last seen',
  public_ip: 'Public IP',
  mac_address: 'MAC address',
  architecture: 'Architecture',
  memory: 'Memory',
  cpu_usage: 'CPU usage',
  memory_usage: 'Memory usage',
  used_disk_space_bytes: 'Disk used',
};

/** Facet keys shown first, per asset type, in the console's order. Unlisted keys follow. */
const FACET_ORDER: Record<string, string[]> = {
  DATASTORE: ['cloud_account_id', 'region', 'storage_size_bytes', 'arn'],
  SAAS_PRINCIPAL: [
    'drive_type', 'entity_type', 'total_inventories', 'total_size_bytes',
    'supported_files_count', 'supported_files_size', 'unsupported_files_count', 'unsupported_files_size',
  ],
  ENDPOINT_HOST: [
    'hostname', 'serial_number', 'os', 'os_version', 'device_type', 'enrolled_user', 'device_users',
    'agent_status', 'agent_version', 'last_ping_time', 'public_ip', 'mac_address',
  ],
};

/** `number_of_records` is the Files Summary total; `able_to_scan` is not shown by the console. */
const HIDDEN_FACET_KEYS = new Set(['number_of_records', 'able_to_scan']);

const MONO_KEYS = new Set(['arn', 'cloud_account_id', 'serial_number', 'mac_address', 'public_ip']);

const prettifyKey = (key: string) =>
  key.replace(/_/g, ' ').replace(/\b\w/g, c => c.toUpperCase());

function formatFacetValue(key: string, value: unknown): string {
  if (value === null || value === undefined) return '—';
  // `is_*` flags are yes/no facts; the rest (encryption, versioning) are settings.
  if (typeof value === 'boolean') {
    if (key.startsWith('is_')) return value ? 'Yes' : 'No';
    return value ? 'Enabled' : 'Disabled';
  }
  if (Array.isArray(value)) return value.length ? value.join(', ') : '—';
  if (typeof value === 'number') {
    if (key.endsWith('_bytes') || key.endsWith('_size')) return formatBytes(value);
    // Endpoint usage figures arrive as fractions (0.25 = 25%).
    if (key.endsWith('_usage') && value >= 0 && value <= 1) return `${(value * 100).toFixed(1)}%`;
    // Epoch seconds. Guarded by magnitude so a genuine count is not mangled into a date.
    if ((key.endsWith('_time') || key.endsWith('_at')) && value > 1_000_000_000) {
      return formatEpochSeconds(value);
    }
    return value.toLocaleString();
  }
  const text = String(value);
  // Enum values (GOOGLE_WORKSPACE, OFFLINE) read as words; identifiers and regions are left alone.
  return /^[A-Z][A-Z0-9_]*$/.test(text) ? prettifyKey(text.toLowerCase()) : text;
}

/**
 * The asset in its own cloud console, derived from its name and region. The customer signs in with
 * their own credentials; nothing is granted by the link. Null where no stable URL can be built.
 */
function consoleLink(detail: DspmAssetDetail): { label: string; href: string } | null {
  const name = encodeURIComponent(detail.name);
  const region = encodeURIComponent(String(detail.facet.region ?? detail.source.region ?? ''));
  const project = encodeURIComponent(String(detail.facet.gcp_project_id ?? ''));
  switch ((detail.source.provider ?? '').toUpperCase()) {
    case 'S3':
      return {
        label: 'Go To AWS',
        href: `https://s3.console.aws.amazon.com/s3/buckets/${name}${region ? `?region=${region}` : ''}`,
      };
    case 'EBS':
      return region ? {
        label: 'Go To AWS',
        href: `https://${region}.console.aws.amazon.com/ec2/home?region=${region}#VolumeDetails:volumeId=${name}`,
      } : null;
    case 'DYNAMODB':
      return region ? {
        label: 'Go To AWS',
        href: `https://${region}.console.aws.amazon.com/dynamodbv2/home?region=${region}#table?name=${name}`,
      } : null;
    case 'GCS':
      return {
        label: 'Go To GCP',
        href: `https://console.cloud.google.com/storage/browser/${name}${project ? `?project=${project}` : ''}`,
      };
    case 'BIGQUERY':
      return project
        ? { label: 'Go To GCP', href: `https://console.cloud.google.com/bigquery?project=${project}` }
        : null;
    default:
      return null;
  }
}

/** Entities grouped by category, in first-seen order. Flattened, this is also the table order. */
function groupEntities(entities: DspmEntityCount[]): Array<[string, DspmEntityCount[]]> {
  const groups = new Map<string, DspmEntityCount[]>();
  for (const entity of entities) {
    const key = entity.categoryLabel ?? 'Other';
    groups.set(key, [...(groups.get(key) ?? []), entity]);
  }
  return [...groups.entries()];
}

function entityCountText(entity: DspmEntityCount, partial: boolean): string {
  if (!entity.sampled) return '—';
  return `${compactCount(entity.count)}${partial ? '+' : ''}`;
}

function entityCountTitle(entity: DspmEntityCount, detail: DspmAssetDetail): string {
  if (!entity.sampled) {
    return `Detected in this asset, but not in the ${detail.objects.length} files the API returns, so it cannot be counted`;
  }
  return detail.breakdownPartial
    ? `At least ${entity.count.toLocaleString()} — counted across ${detail.objects.length} of ${detail.objectTotal.toLocaleString()} files`
    : entity.count.toLocaleString();
}

/** Every entity finding in one object, summed — an object can report the same detector twice. */
const countIn = (object: DspmObject, entityType: string) =>
  object.findings.filter(f => f.entityType === entityType).reduce((sum, f) => sum + f.count, 0);

// ── small pieces ──────────────────────────────────────────────────────────────────────────────

const Card: React.FC<{ title?: string; children: React.ReactNode; className?: string }> =
  ({ title, children, className }) => (
    <section className={`rounded-xl border border-[rgba(0,0,0,0.08)] bg-white p-4 ${className ?? ''}`}>
      {title && (
        <h3 className="mb-3 border-b border-[rgba(0,0,0,0.06)] pb-2 text-[11px] font-bold text-[rgba(0,0,0,0.75)]">
          {title}
        </h3>
      )}
      {children}
    </section>
  );

const PropertyField: React.FC<{ label: string; value: React.ReactNode; mono?: boolean }> =
  ({ label, value, mono }) => (
    <div>
      <div className="mb-1 text-[10px] text-[rgba(0,0,0,0.45)]">{label}</div>
      <div
        className={`rounded-lg bg-[rgba(0,0,0,0.035)] px-3 py-2 text-xs font-semibold text-[rgba(0,0,0,0.8)] break-all ${mono ? 'font-mono text-[11px]' : ''}`}
      >
        {value}
      </div>
    </div>
  );

const CategoryChip: React.FC<{ label: string | null }> = ({ label }) => {
  if (!label) return null;
  const accent = categoryAccent(label);
  return (
    <span className={`inline-flex items-center gap-1.5 whitespace-nowrap rounded border px-1.5 py-0.5 text-[10px] font-semibold ${accent.chip}`}>
      <span className={`h-1.5 w-1.5 rounded-sm ${accent.bar}`} />
      {label}
    </span>
  );
};

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
      className="flex-shrink-0 rounded p-1 text-[rgba(0,0,0,0.35)] transition-colors hover:bg-[rgba(0,0,0,0.06)] hover:text-[rgba(0,0,0,0.7)]"
      title="Copy path"
      aria-label="Copy path"
    >
      <Icon icon={copied ? 'mdi:check' : 'mdi:content-copy'} width={12} />
    </button>
  );
};

/**
 * The asset's record total. Shown whenever it is non-zero, including for stopped or failed scans — the
 * console does too, and 909 found before a scan stopped is a real minimum. Only a zero from an
 * unfinished scan is withheld, since it would read as "clean" when nobody looked.
 */
const RecordsTotal: React.FC<{ detail: DspmAssetDetail }> = ({ detail }) => {
  const { recordCount, known } = detail.sensitives;
  if (recordCount > 0 || known) {
    return (
      <div
        className="text-2xl font-bold tabular-nums text-rose-600"
        title={known ? recordCount.toLocaleString() : `${recordCount.toLocaleString()} found — ${detail.scan.statusLabel.toLowerCase()}`}
      >
        {compactCount(recordCount)}
      </div>
    );
  }
  return (
    <div className="text-sm italic text-[rgba(0,0,0,0.4)]">
      Not available
      <div className="mt-0.5 text-[10px] not-italic">{detail.scan.statusLabel}</div>
    </div>
  );
};

// ── Overview ──────────────────────────────────────────────────────────────────────────────────

const PropertiesCard: React.FC<{ detail: DspmAssetDetail }> = ({ detail }) => {
  const order = FACET_ORDER[detail.resourceType ?? ''] ?? [];
  const keys = [
    ...order.filter(k => k in detail.facet),
    ...Object.keys(detail.facet).filter(k => !order.includes(k) && !HIDDEN_FACET_KEYS.has(k)),
  ];

  // The external id is worth showing only where nothing else already says it: an ARN is its own field,
  // and a Postgres database's id is just its name.
  const identifier = detail.externalId
    && !('arn' in detail.facet)
    && detail.externalId !== detail.name
    && !Object.values(detail.facet).includes(detail.externalId)
    ? detail.externalId : null;
  const identifierField = identifier && (
    <PropertyField
      label={detail.resourceType === 'SAAS_PRINCIPAL' ? 'Email' : 'Identifier'}
      value={identifier}
      mono={detail.resourceType !== 'SAAS_PRINCIPAL'}
    />
  );

  return (
    <Card title="Properties">
      <div className="space-y-3">
        {/* A SaaS principal is best identified by its email, so it leads there. */}
        {detail.resourceType === 'SAAS_PRINCIPAL' && identifierField}
        {keys.map(key => (
          <PropertyField
            key={key}
            label={FACET_LABELS[key] ?? prettifyKey(key)}
            value={formatFacetValue(key, detail.facet[key])}
            mono={MONO_KEYS.has(key)}
          />
        ))}
        {detail.resourceType !== 'SAAS_PRINCIPAL' && identifierField}
        {keys.length === 0 && !identifier && (
          <p className="text-xs text-[rgba(0,0,0,0.4)]">No properties reported for this asset type.</p>
        )}
      </div>
    </Card>
  );
};

/**
 * Total files. The console's figure is the datastore's `number_of_records` (4.36K on the bucket it was
 * checked against), which is not the same as the scanned object total (4,272). Assets without it fall
 * back to the object total.
 */
const FilesSummaryCard: React.FC<{ detail: DspmAssetDetail }> = ({ detail }) => {
  const raw = detail.facet['number_of_records'];
  const reported = typeof raw === 'number' ? raw : null;
  const total = reported ?? detail.objectTotal;
  const label = reported !== null || detail.resourceType !== 'DATASTORE' ? 'Total Files' : 'Total Objects';

  return (
    <Card title="Files Summary">
      <div className="text-[10px] text-[rgba(0,0,0,0.45)]">{label}</div>
      {total === 0 && !detail.sensitives.known ? (
        <div className="mt-1 text-sm italic text-[rgba(0,0,0,0.4)]">Not available</div>
      ) : (
        <div className="mt-0.5 text-xl font-bold tabular-nums text-[rgba(0,0,0,0.88)]" title={total.toLocaleString()}>
          {compactCount(total)}
        </div>
      )}
    </Card>
  );
};

const SensitiveRecordsCard: React.FC<{ detail: DspmAssetDetail }> = ({ detail }) => {
  const groups = useMemo(() => groupEntities(detail.entityBreakdown), [detail.entityBreakdown]);
  const partial = detail.breakdownPartial;

  return (
    <Card>
      <div className="flex flex-col gap-4 sm:flex-row sm:gap-6">
        <div className="w-28 flex-shrink-0">
          <h3 className="text-[11px] font-bold text-[rgba(0,0,0,0.75)]">Sensitive Records</h3>
          <div className="mt-2"><RecordsTotal detail={detail} /></div>
        </div>

        <div className="min-w-0 flex-1 space-y-2.5">
          {groups.length === 0 && (
            <p className="py-2 text-xs text-[rgba(0,0,0,0.4)]">
              {detail.sensitives.known ? 'No sensitive data found.' : 'Results appear once a scan completes.'}
            </p>
          )}
          {groups.map(([category, entities]) => {
            const accent = categoryAccent(category);
            return (
              <div key={category} className={`rounded-lg border p-3 ${accent.panel}`}>
                <CategoryChip label={category} />
                <div className="mt-2.5 flex flex-wrap gap-x-5 gap-y-2">
                  {entities.map(entity => (
                    <span key={entity.entityType} className="inline-flex items-center gap-1.5">
                      <span className={`h-3.5 w-0.5 flex-shrink-0 rounded ${accent.bar}`} />
                      <span className="text-[10px] font-semibold uppercase tracking-wide text-[rgba(0,0,0,0.75)]">
                        {entity.entityType}
                      </span>
                      <span className="h-1 w-1 rounded-full bg-[rgba(0,0,0,0.3)]" />
                      <span
                        className="text-[11px] font-semibold tabular-nums text-[rgba(0,0,0,0.6)]"
                        title={entityCountTitle(entity, detail)}
                      >
                        {entityCountText(entity, partial)}
                      </span>
                    </span>
                  ))}
                </div>
              </div>
            );
          })}
          {partial && groups.length > 0 && (
            <p className="text-[10px] text-[rgba(0,0,0,0.4)]">
              Counted across the {detail.objects.length} most sensitive files of{' '}
              {detail.objectTotal.toLocaleString()} — the most the API returns. “+” marks a minimum; “—”
              an entity detected but not counted.
            </p>
          )}
        </div>
      </div>
    </Card>
  );
};

// ── Sensitive ─────────────────────────────────────────────────────────────────────────────────

const OBJECT_KIND: Record<string, string> = { COLUMN: 'Column', TABLE: 'Table' };

const FileRecordCard: React.FC<{ object: DspmObject; count?: number; showFindings?: boolean }> =
  ({ object, count, showFindings }) => (
    <div className="rounded-lg border border-[rgba(0,0,0,0.08)] bg-white p-3">
      <div className="flex items-center gap-2">
        <DspmObjectIcon objectType={object.objectType} extension={object.extension} />
        <span className="flex-shrink-0 text-[11px] text-[rgba(0,0,0,0.45)]">
          {OBJECT_KIND[object.objectType ?? ''] ?? 'File'} Name:
        </span>
        <span className="min-w-0 truncate text-xs font-semibold text-[rgba(0,0,0,0.85)]" title={object.name ?? ''}>
          {object.name ?? '(unnamed)'}
        </span>
        {count !== undefined && count > 0 && (
          <span
            className="ml-auto flex-shrink-0 rounded border border-[rgba(0,0,0,0.1)] px-1.5 py-0.5 text-[10px] font-semibold tabular-nums text-[rgba(0,0,0,0.6)]"
            title={`${count.toLocaleString()} records in this file`}
          >
            {compactCount(count)}
          </span>
        )}
      </div>

      {object.path && (
        <>
          <div className="mt-2.5 text-[10px] font-semibold text-[rgba(0,0,0,0.5)]">File Paths</div>
          {/* Copy rather than link: reaching the file needs the customer's own cloud credentials. */}
          <div className="mt-1 flex items-start gap-2 rounded-lg bg-[rgba(0,0,0,0.035)] px-2.5 py-2">
            <span className="min-w-0 flex-1 break-all font-mono text-[10px] text-[rgba(0,0,0,0.6)]">
              {object.path}
            </span>
            <CopyButton text={object.path} />
          </div>
        </>
      )}

      {showFindings && object.findings.length > 0 && (
        <div className="mt-2.5 flex flex-wrap gap-1">
          {object.findings.map((finding, i) => {
            const accent = categoryAccent(finding.categoryLabel);
            return (
              <span
                key={`${finding.entityType}-${i}`}
                className={`inline-flex items-center gap-1 rounded border px-1.5 py-0.5 text-[10px] ${accent.chip}`}
              >
                <span className="font-semibold">{finding.entityType || 'Unclassified'}</span>
                <span className="tabular-nums opacity-70">{compactCount(finding.count)}</span>
              </span>
            );
          })}
        </div>
      )}
    </div>
  );

const EmptySensitive: React.FC<{ detail: DspmAssetDetail }> = ({ detail }) => (
  <div className="py-12 text-center">
    <Icon icon="mdi:shield-check-outline" width={30} className="mx-auto text-[rgba(0,0,0,0.15)]" />
    <p className="mt-2 text-xs font-semibold text-[rgba(0,0,0,0.6)]">
      {detail.sensitives.known ? 'No sensitive data found in this asset' : 'This asset has not been scanned yet'}
    </p>
    {!detail.sensitives.known && (
      <p className="mt-1 text-xs text-[rgba(0,0,0,0.4)]">
        Status: {detail.scan.statusLabel}. Results appear once a scan completes.
      </p>
    )}
  </div>
);

const TH = 'px-3 py-2.5 text-[10px] font-semibold uppercase tracking-wider text-[rgba(0,0,0,0.45)]';

/** Entities on the left; the selected entity's files on the right. */
const EntityView: React.FC<{ detail: DspmAssetDetail; query: string }> = ({ detail, query }) => {
  const ordered = useMemo(
    () => groupEntities(detail.entityBreakdown).flatMap(([, list]) => list),
    [detail.entityBreakdown],
  );
  const [picked, setPicked] = useState<string | null>(null);

  const needle = query.trim().toLowerCase();
  const visible = needle ? ordered.filter(e => e.entityType.toLowerCase().includes(needle)) : ordered;
  const active = visible.find(e => e.entityType === picked) ?? visible[0] ?? null;

  const files = useMemo(() => {
    if (!active) return [];
    return detail.objects
      .filter(object => object.findings.some(f => f.entityType === active.entityType))
      .map(object => ({ object, count: countIn(object, active.entityType) }))
      .sort((a, b) => b.count - a.count);
  }, [active, detail.objects]);

  if (ordered.length === 0) return <EmptySensitive detail={detail} />;

  const partial = detail.breakdownPartial;

  return (
    <div className="grid grid-cols-1 overflow-hidden rounded-xl border border-[rgba(0,0,0,0.08)] lg:grid-cols-[minmax(300px,2fr)_3fr]">
      <div className="border-b border-[rgba(0,0,0,0.06)] lg:border-b-0 lg:border-r">
        <table className="w-full text-left">
          <thead>
            <tr className="border-b border-[rgba(0,0,0,0.06)] bg-[rgba(0,0,0,0.015)]">
              <th className={TH}>Entities</th>
              <th className={`${TH} text-right`}>Count</th>
              <th className={TH}>Sensitives</th>
            </tr>
          </thead>
          <tbody>
            {visible.map(entity => {
              const accent = categoryAccent(entity.categoryLabel);
              const isActive = entity.entityType === active?.entityType;
              return (
                <tr
                  key={entity.entityType}
                  onClick={() => setPicked(entity.entityType)}
                  className={`cursor-pointer border-b border-[rgba(0,0,0,0.04)] ${isActive ? 'bg-[rgba(0,0,0,0.04)]' : 'hover:bg-[rgba(0,0,0,0.02)]'}`}
                >
                  <td className="px-3 py-2.5">
                    <span className="flex items-center gap-2">
                      <span className={`h-4 w-0.5 flex-shrink-0 rounded ${accent.bar}`} />
                      <span className="text-[10px] font-semibold uppercase tracking-wide text-[rgba(0,0,0,0.8)]">
                        {entity.entityType}
                      </span>
                    </span>
                  </td>
                  <td
                    className="px-3 py-2.5 text-right text-xs font-semibold tabular-nums text-[rgba(0,0,0,0.75)]"
                    title={entityCountTitle(entity, detail)}
                  >
                    {entityCountText(entity, partial)}
                  </td>
                  <td className="px-3 py-2.5"><CategoryChip label={entity.categoryLabel} /></td>
                </tr>
              );
            })}
            {visible.length === 0 && (
              <tr><td colSpan={3} className="px-3 py-8 text-center text-xs text-[rgba(0,0,0,0.4)]">
                No entities match
              </td></tr>
            )}
          </tbody>
        </table>
      </div>

      <div className="min-w-0 bg-[rgba(0,0,0,0.015)] p-4">
        {active && (
          <>
            <div className="flex flex-wrap items-center gap-2">
              <span className="text-xs font-bold uppercase tracking-wide text-[rgba(0,0,0,0.85)]">
                {active.entityType}
              </span>
              <CategoryChip label={active.categoryLabel} />
              <span className="ml-auto rounded-lg border border-[rgba(0,0,0,0.1)] bg-white px-2.5 py-1 text-[11px] text-[rgba(0,0,0,0.55)]">
                Total Files:{' '}
                <strong className="tabular-nums text-[rgba(0,0,0,0.85)]">
                  {active.sampled ? `${active.fileCount}${partial ? '+' : ''}` : '—'}
                </strong>
              </span>
            </div>

            <h4 className="mt-4 text-[11px] font-bold text-[rgba(0,0,0,0.75)]">File Records</h4>
            {files.some(f => f.object.path) && (
              <p className="mt-0.5 text-[10px] text-[rgba(0,0,0,0.45)]">
                To access the file, copy the path and run in cloud console
              </p>
            )}

            <div className="mt-3 space-y-2.5">
              {files.map(({ object, count }) => (
                <FileRecordCard key={object.id} object={object} count={count} />
              ))}
            </div>

            {files.length === 0 && (
              <p className="mt-3 rounded-lg border border-dashed border-[rgba(0,0,0,0.12)] bg-white p-4 text-center text-xs text-[rgba(0,0,0,0.45)]">
                Detected in this asset, but none of the {detail.objects.length} files the API returns
                contain it, so its files cannot be listed here.
              </p>
            )}
            {partial && files.length > 0 && (
              <p className="mt-3 text-[10px] text-[rgba(0,0,0,0.4)]">
                Showing matches among the {detail.objects.length} most sensitive files of{' '}
                {detail.objectTotal.toLocaleString()}.
              </p>
            )}
          </>
        )}
      </div>
    </div>
  );
};

/** Every returned object with its findings. */
const FileView: React.FC<{ detail: DspmAssetDetail; query: string }> = ({ detail, query }) => {
  if (detail.objects.length === 0) return <EmptySensitive detail={detail} />;

  const needle = query.trim().toLowerCase();
  const visible = needle
    ? detail.objects.filter(o => (o.name ?? '').toLowerCase().includes(needle)
      || (o.path ?? '').toLowerCase().includes(needle))
    : detail.objects;

  return (
    <div className="space-y-2.5">
      {visible.map(object => (
        <FileRecordCard key={object.id} object={object} count={object.sensitiveCount} showFindings />
      ))}
      {visible.length === 0 && (
        <p className="py-8 text-center text-xs text-[rgba(0,0,0,0.4)]">No files match</p>
      )}
      {detail.objectsTruncated && (
        <p className="flex items-center gap-1.5 pt-1 text-[11px] text-[rgba(0,0,0,0.45)]">
          <Icon icon="mdi:information-outline" width={12} />
          Showing the {detail.objects.length} most sensitive objects of {detail.objectTotal.toLocaleString()}.
        </p>
      )}
    </div>
  );
};

const SensitiveTab: React.FC<{ detail: DspmAssetDetail }> = ({ detail }) => {
  const [view, setView] = useState<'entity' | 'file'>('entity');
  const [query, setQuery] = useState('');

  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-center gap-3">
        <div className="relative">
          <Icon
            icon="mdi:magnify" width={13}
            className="absolute left-2.5 top-1/2 -translate-y-1/2 text-[rgba(0,0,0,0.3)]"
          />
          <input
            type="search"
            value={query}
            onChange={e => setQuery(e.target.value)}
            placeholder="Search"
            aria-label={view === 'entity' ? 'Search entities' : 'Search files'}
            className="w-56 rounded-lg border border-[rgba(0,0,0,0.08)] bg-[rgba(0,0,0,0.03)] py-1.5 pl-8 pr-3 text-xs focus:outline-none focus:ring-1 focus:ring-[rgba(0,0,0,0.2)]"
          />
        </div>

        <div className="ml-auto inline-flex overflow-hidden rounded-lg border border-[rgba(0,0,0,0.12)]" role="group">
          {([['entity', 'Entity View', 'mdi:shape-outline'], ['file', 'File View', 'mdi:file-outline']] as const)
            .map(([id, label, icon]) => (
              <button
                key={id}
                onClick={() => setView(id)}
                aria-pressed={view === id}
                className={`inline-flex items-center gap-1.5 px-3 py-1.5 text-[11px] font-semibold transition-colors
                  ${view === id
                    ? 'bg-[rgba(0,0,0,0.88)] text-white'
                    : 'bg-white text-[rgba(0,0,0,0.55)] hover:bg-[rgba(0,0,0,0.03)]'}`}
              >
                <Icon icon={icon} width={12} />
                {label}
              </button>
            ))}
        </div>
      </div>

      {view === 'entity'
        ? <EntityView detail={detail} query={query} />
        : <FileView detail={detail} query={query} />}
    </div>
  );
};

// ── panel ─────────────────────────────────────────────────────────────────────────────────────

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

  const link = detail ? consoleLink(detail) : null;
  // Every endpoint shares the ENDPOINT_AGENT provider, so its OS subtype picks the icon.
  const iconKey = detail?.resourceType === 'ENDPOINT_HOST'
    ? (detail.subtype ?? detail.source.provider)
    : (detail?.source.provider ?? detail?.subtype ?? null);

  return ReactDOM.createPortal(
    // Stops at the sidebar's 220px; z-30 keeps it under the sidebar's z-40 if they ever overlap.
    <div className="fixed inset-y-0 right-0 left-0 z-30 flex lg:left-[220px]">
      <div className="flex h-full w-full flex-col bg-white">
        <div className="px-5 pt-3">
          <button
            onClick={onClose}
            className="inline-flex items-center gap-1 text-xs font-semibold text-[rgba(0,0,0,0.6)] transition-colors hover:text-[rgba(0,0,0,0.9)]"
          >
            <Icon icon="mdi:arrow-left" width={14} />
            Back
          </button>
        </div>

        <div className="flex items-start gap-3 px-5 pb-2 pt-3">
          <span className="mt-0.5 flex h-8 w-8 flex-shrink-0 items-center justify-center rounded-lg border border-[rgba(0,0,0,0.08)] bg-[rgba(0,0,0,0.02)]">
            <DspmProviderIcon provider={iconKey} size={18} />
          </span>
          <div className="min-w-0">
            <h2 className="truncate text-sm font-bold text-[rgba(0,0,0,0.88)]" title={detail?.name}>
              {detail?.name ?? (loading ? 'Loading…' : '—')}
            </h2>
            <p className="mt-0.5 text-[10px] font-semibold uppercase tracking-wide text-[rgba(0,0,0,0.4)]">
              {detail?.subtype ?? ''}
            </p>
          </div>
          {link && (
            <a
              href={link.href}
              target="_blank"
              rel="noopener noreferrer"
              className="mt-1 inline-flex flex-shrink-0 items-center gap-1 rounded-lg border border-[rgba(0,0,0,0.12)] px-2 py-1 text-[11px] font-semibold text-[rgba(0,0,0,0.65)] transition-colors hover:bg-[rgba(0,0,0,0.03)]"
            >
              <DspmCloudMark provider={detail?.source.provider ?? null} size={12} />
              {link.label}
            </a>
          )}
          {detail && (
            <span className="ml-auto inline-flex flex-shrink-0 items-center rounded-full border border-[rgba(0,0,0,0.12)] px-2.5 py-1">
              <DspmScanStatus status={detail.scan.status} label={detail.scan.statusLabel} />
            </span>
          )}
        </div>

        <div className="flex gap-1 border-b border-[rgba(0,0,0,0.08)] px-5" role="tablist">
          {([['overview', 'Overview'], ['sensitive', 'Sensitive']] as const).map(([id, label]) => (
            <button
              key={id}
              role="tab"
              aria-selected={tab === id}
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

        <div className="flex-1 overflow-y-auto bg-[rgba(0,0,0,0.015)] p-5">
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
            <div className="grid grid-cols-1 items-start gap-4 lg:grid-cols-[minmax(260px,340px)_1fr]">
              <PropertiesCard detail={detail} />
              <div className="space-y-4">
                <FilesSummaryCard detail={detail} />
                <SensitiveRecordsCard detail={detail} />
              </div>
            </div>
          )}

          {detail && !loading && tab === 'sensitive' && <SensitiveTab detail={detail} />}
        </div>
      </div>
    </div>,
    document.body,
  );
};

export default DspmAssetDrawer;
