import React from 'react';
import { Icon } from '@iconify/react';
import type { DspmAssetRow } from '../../types/dspm';
import { sensitivityClasses, severityFor } from './dspmFormat';

/**
 * Icons for the DSPM tables.
 *
 * <h3>Where the images come from</h3>
 * The repo already ships the official AWS, GCP and Azure service marks under `/icons`, and the house
 * rule is that the same service uses the same icon everywhere — an S3 bucket in CloudList and an S3
 * bucket here must not look like different things. So every cloud datastore resolves to one of those
 * existing assets rather than to a drawn-on substitute.
 *
 * <h3>Where they do not</h3>
 * Self-hosted engines, SaaS suites and managed endpoints have no official asset in this repo, so those
 * fall back to Iconify `mdi:` glyphs. That split is deliberate: inventing a lookalike for S3 would
 * breach the same-service rule, while a generic database glyph for self-hosted Postgres claims nothing
 * it should not.
 */

const BASE = '/icons';
/** Icons authored for this repo live in a subfolder, kept apart from the vendor assets. */
const OWN = '/icons/aws';

/**
 * Matters provider token to an official service icon.
 *
 * Keyed on the provider token the API returns ({@code S3}, {@code AZURE_BLOB}), not on a display label.
 * The existing {@code awsServiceIcons} resolver is label-keyed and AWS-only, so it cannot answer for
 * {@code GCS} or {@code AZURE_BLOB}; the asset paths are reused directly instead of routing a
 * synthesised label through it.
 */
const PROVIDER_IMAGE: Record<string, string> = {
  // ── AWS ──
  S3: `${BASE}/s3-bucket.png`,
  EBS: `${BASE}/aws-ebs.png`,
  RDS: `${BASE}/rds-instance.png`,
  RDS_ORACLE: `${BASE}/rds-instance.png`,
  DYNAMODB: `${BASE}/dynamodb-table.png`,
  DOCUMENTDB: `${OWN}/documentdb.svg`,
  MSK_KAFKA: `${OWN}/msk.svg`,

  // ── GCP ──
  GCS: `${BASE}/gcp-cloud-storage.png`,
  BIGQUERY: `${BASE}/gcp-bigquery.png`,
  CLOUD_SQL: `${BASE}/gcp-cloud-sql.png`,

  // ── Azure ──
  AZURE_BLOB: `${BASE}/azure-storage-account.png`,
  AZURE_SQL: `${BASE}/sql-database.png`,
  AZURE_SQL_SERVER: `${BASE}/sql-database.png`,
};

/**
 * Providers with no official asset here, mapped to an mdi glyph.
 *
 * Self-hosted engines share one database glyph rather than each getting a vendor logo: the point of the
 * column is to say "a datastore you run yourself", and the engine name is already printed beneath the
 * asset name.
 */
const PROVIDER_GLYPH: Record<string, string> = {
  SNOWFLAKE: 'mdi:snowflake',
  DATABRICKS_CATALOG: 'mdi:table-large',
  MONGODB_ATLAS: 'mdi:leaf',
  SMB: 'mdi:folder-network-outline',

  SELF_HOSTED_MYSQL: 'mdi:database-outline',
  SELF_HOSTED_POSTGRES: 'mdi:database-outline',
  SELF_HOSTED_MSSQL: 'mdi:database-outline',
  SELF_HOSTED_ORACLE: 'mdi:database-outline',
  SELF_HOSTED_MONGO_DB: 'mdi:database-outline',
  SELF_HOSTED_COUCHDB: 'mdi:database-outline',
  SELF_HOSTED_COUCHBASE: 'mdi:database-outline',
  SELF_HOSTED_COCKROACHDB: 'mdi:database-outline',
  SELF_HOSTED_MINIO_LOGS: 'mdi:database-outline',

  GOOGLE_WORKSPACE: 'mdi:google',
  OFFICE_365: 'mdi:microsoft-office',
  GITLAB: 'mdi:gitlab',
  JIRA: 'mdi:jira',
  CONFLUENCE: 'mdi:confluence',

  ENDPOINT_AGENT: 'mdi:laptop',
  // Endpoint rows report the OS as their `subtype` while every one of them shares the provider
  // `ENDPOINT_AGENT`, so the Endpoint table passes the subtype first — a fleet of identical laptop
  // glyphs says nothing, and Mac against Windows is the one thing that column can usefully show.
  MAC: 'mdi:apple',
  WINDOWS: 'mdi:microsoft-windows',
  LINUX: 'mdi:linux',
};

/** Which cloud a provider belongs to, for the account column's mark. */
const AWS_PROVIDERS = new Set([
  'S3', 'EBS', 'RDS', 'RDS_ORACLE', 'DYNAMODB', 'DOCUMENTDB', 'MSK_KAFKA',
]);
const GCP_PROVIDERS = new Set(['GCS', 'BIGQUERY', 'CLOUD_SQL']);
const AZURE_PROVIDERS = new Set(['AZURE_BLOB', 'AZURE_SQL', 'AZURE_SQL_SERVER']);

/**
 * The datastore icon for a row.
 *
 * Falls back to a neutral database glyph rather than the generic AWS mark: the providers reaching this
 * path are as likely to be SaaS or self-hosted as AWS, and stamping an AWS logo on a self-hosted
 * Postgres row would be actively wrong. The docs also state these enums are open sets, so an unmapped
 * provider is expected rather than exceptional.
 */
export const DspmProviderIcon: React.FC<{ provider: string | null; size?: number }> =
  ({ provider, size = 16 }) => {
    const key = (provider ?? '').toUpperCase();
    const image = PROVIDER_IMAGE[key];

    if (image) {
      return (
        <img
          src={image}
          alt=""
          aria-hidden="true"
          width={size}
          height={size}
          className="flex-shrink-0 object-contain"
          // A missing asset should degrade to nothing rather than to a broken-image glyph in every row.
          onError={e => { (e.currentTarget as HTMLImageElement).style.visibility = 'hidden'; }}
        />
      );
    }

    return (
      <Icon
        icon={PROVIDER_GLYPH[key] ?? 'mdi:database-outline'}
        width={size}
        className="flex-shrink-0 text-[rgba(0,0,0,0.45)]"
      />
    );
  };

/** The cloud mark shown beside a region, matching the console's account column. */
export const DspmCloudMark: React.FC<{ provider: string | null; size?: number }> =
  ({ provider, size = 12 }) => {
    const key = (provider ?? '').toUpperCase();

    let src: string | null = null;
    if (AWS_PROVIDERS.has(key)) src = `${BASE}/aws-icon.png`;
    else if (GCP_PROVIDERS.has(key)) src = `${BASE}/gcp-icon.png`;
    else if (AZURE_PROVIDERS.has(key)) src = `${BASE}/azure-icon.png`;

    // Self-hosted and SaaS assets have no cloud to mark. Rendering nothing is correct — a placeholder
    // would imply a provider that is not there.
    if (!src) return null;

    return (
      <img
        src={src}
        alt=""
        aria-hidden="true"
        width={size}
        height={size}
        className="flex-shrink-0 object-contain"
        onError={e => { (e.currentTarget as HTMLImageElement).style.visibility = 'hidden'; }}
      />
    );
  };

/** Severity icon per band, weighted so Critical reads loudest. */
const SEVERITY_GLYPH: Record<string, string> = {
  Critical: 'mdi:alert-octagon',
  High: 'mdi:alert',
  Medium: 'mdi:alert-outline',
  Low: 'mdi:information-outline',
};

/**
 * Severity badge with its icon.
 *
 * The band comes from {@link severityFor}, which reads a different vendor field per asset type —
 * {@code sensitives.level} for datastores, {@code risk.level} for SaaS and endpoints, because the vendor
 * populates only one of the two on each. A dash when neither is set, as their console has it.
 */
export const DspmSeverityBadge: React.FC<{ row: DspmAssetRow }> = ({ row }) => {
  const label = severityFor(row);
  if (!label) return <span className="text-xs text-[rgba(0,0,0,0.3)]">—</span>;

  return (
    <span className={`inline-flex items-center gap-1 rounded-full border px-2 py-0.5 text-[11px] font-semibold ${sensitivityClasses(label)}`}>
      <Icon icon={SEVERITY_GLYPH[label] ?? 'mdi:information-outline'} width={11} />
      {label}
    </span>
  );
};

/**
 * Scan status with its indicator.
 *
 * Icons rather than bare dots so the states are distinguishable without relying on colour — which
 * matters here because "Scan stopped" and "Scanned" are the difference between a trustworthy record
 * count and a meaningless one.
 */
const SCAN_GLYPH: Record<string, { icon: string; cls: string }> = {
  CLASSIFIED: { icon: 'mdi:check-circle', cls: 'text-emerald-500' },
  SCANNING: { icon: 'mdi:progress-clock', cls: 'text-sky-500' },
  STOPPED: { icon: 'mdi:pause-circle', cls: 'text-slate-400' },
  FAILED: { icon: 'mdi:alert-circle', cls: 'text-rose-500' },
  NOT_SCANNED: { icon: 'mdi:circle-outline', cls: 'text-[rgba(0,0,0,0.25)]' },
  ACTIVE: { icon: 'mdi:check-circle', cls: 'text-emerald-500' },
  SUSPENDED: { icon: 'mdi:pause-circle', cls: 'text-slate-400' },
};

export const DspmScanStatus: React.FC<{ status: string; label: string }> = ({ status, label }) => {
  const meta = SCAN_GLYPH[status?.toUpperCase()]
    ?? { icon: 'mdi:help-circle-outline', cls: 'text-[rgba(0,0,0,0.25)]' };
  return (
    <span className="inline-flex items-center gap-1.5 whitespace-nowrap text-xs text-[rgba(0,0,0,0.65)]">
      <Icon icon={meta.icon} width={13} className={`flex-shrink-0 ${meta.cls}`} />
      {label}
    </span>
  );
};

/** Object-type glyph for the detail panel's file and column rows. */
export const DspmObjectIcon: React.FC<{ objectType: string | null; extension?: string | null }> =
  ({ objectType, extension }) => {
    if (objectType === 'COLUMN') {
      return <Icon icon="mdi:table-column" width={13} className="flex-shrink-0 text-[rgba(0,0,0,0.3)]" />;
    }
    if (objectType === 'TABLE') {
      return <Icon icon="mdi:table" width={13} className="flex-shrink-0 text-[rgba(0,0,0,0.3)]" />;
    }

    // Extension-aware so a spreadsheet and a PDF are distinguishable at a glance, as they are in the
    // console's file lists.
    const ext = (extension ?? '').toLowerCase();
    const byExt: Record<string, string> = {
      csv: 'mdi:file-delimited-outline',
      xls: 'mdi:file-excel-outline', xlsx: 'mdi:file-excel-outline',
      pdf: 'mdi:file-pdf-box',
      doc: 'mdi:file-word-outline', docx: 'mdi:file-word-outline',
      json: 'mdi:code-json',
      txt: 'mdi:file-document-outline',
      zip: 'mdi:folder-zip-outline', gz: 'mdi:folder-zip-outline',
      log: 'mdi:text-box-outline',
      parquet: 'mdi:file-table-outline',
    };
    return (
      <Icon
        icon={byExt[ext] ?? 'mdi:file-outline'}
        width={13}
        className="flex-shrink-0 text-[rgba(0,0,0,0.3)]"
      />
    );
  };
