/**
 * XamLens (DSPM) demo mock data  (ROLE_XAMOPS)
 *
 * Serves the four routes XamLens reads — `/api/dspm/status`, `/api/dspm/{surface}/assets`,
 * `/api/dspm/{surface}/filters` and `/api/dspm/assets/{id}` — in the same shapes the backend returns.
 *
 * Paging, sorting, search and filters are applied here rather than returned fixed, so every control on
 * the page works in a demo: the filter counts are disjunctive like the real API's (a dimension's counts
 * ignore its own selection), and a filtered list and its counts always agree.
 *
 * Each asset is written once, as the files it holds and what was found in them. Its record count,
 * sensitive types, entity breakdown and file list are all derived from those files, so the table, the
 * asset page and the Sensitive tab can never disagree with each other.
 *
 * People, emails, account ids and IPs are fictional: the names match the demo Google Workspace users
 * elsewhere in the demo data, IPs come from the documentation ranges, and MAC addresses are locally
 * administered.
 *
 * The same specs also answer the Datastores page (`/api/dspm/datastores`, `/filter-options`, `/kpis`;
 * design 7.2) in the new DTO shapes. Its drawer data lives in `xamlensDrawerMockData.ts`, and the three
 * showcase datastores take their sensitive figures from there, so the list and the drawer agree.
 */

import type {
  DspmAssetDetail, DspmAssetRow, DspmEntityCount, DspmFilterOption, DspmFilters, DspmObject, DspmPage,
  DspmSeverity, DspmStatus, DspmTrendPoint,
} from '../types/dspm';
import type {
  DatastoreFilterDimension, DatastoreFilterOptions, DatastoreKpis, DatastoreRow,
} from '../types/dspmDatastores';
import type { DspmSurface } from '../hooks/useDspmData';
// Circular: the drawer module reads `demoDatastoreSource` from here. Each side reads the other only
// inside functions, never while the modules load, so either may load first.
import { SHOWCASE_SENSITIVES } from './xamlensDrawerMockData';

// ─── Vocabulary ──────────────────────────────────────────────────────────────

const DAY = 24 * 60 * 60;
const nowSeconds = () => Math.floor(Date.now() / 1000);
const MB = 1024 ** 2;
const GB = 1024 ** 3;
const TB = 1024 ** 4;

/** Detector → category, as the vendor reports them. */
const ENTITY_CATEGORY: Record<string, string> = {
  'PERSON NAME': 'PII', 'EMAIL ADDRESS': 'PII', 'PHONE NUMBER': 'PII', 'ADDRESS': 'PII',
  'DATE OF BIRTH': 'PII', 'IP ADDRESS': 'PII', 'PAN NUMBER': 'PII', 'AADHAAR NUMBER': 'PII',
  'ACCESS KEY AWS': 'SECRETS', 'HASHED SECRET KEY': 'SECRETS', 'SHA KEY': 'SECRETS',
  'JWT TOKEN': 'SECRETS', 'PRIVATE KEY': 'SECRETS',
  'CREDIT CARD NUMBER': 'PCI', 'BANK ACCOUNT NUMBER': 'PCI', 'IFSC CODE': 'PCI',
  'MEDICAL RECORD NUMBER': 'PHI',
};

const CATEGORY_LABEL: Record<string, string> = {
  PII: 'Personal', SECRETS: 'IT & Security', PCI: 'Financial', PHI: 'Health',
};

const SCAN_LABEL: Record<string, string> = {
  CLASSIFIED: 'Scanned', NOT_SCANNED: 'Not scanned', SCANNING: 'Scanning',
  FAILED: 'Scan failed', STOPPED: 'Scan stopped',
};

const SCAN_STAGE: Record<string, string> = {
  CLASSIFIED: 'SCANNING_COMPLETED', SCANNING: 'IN_PROGRESS', FAILED: 'SCAN_FAILED', STOPPED: 'SCAN_STOPPED',
};

const PROVIDER_LABEL: Record<string, string> = {
  S3: 'S3', EBS: 'EBS', RDS: 'RDS', DYNAMODB: 'DynamoDB', BIGQUERY: 'BigQuery', GCS: 'GCS',
  AZURE_BLOB: 'Azure Blob', SELF_HOSTED_POSTGRES: 'Self-hosted Postgres',
  GOOGLE_WORKSPACE: 'Google Workspace', ENDPOINT_AGENT: 'Endpoint Agent', GMAIL: 'Gmail',
  MAC: 'Mac', WINDOWS: 'Windows', LINUX: 'Linux',
};

const SEVERITY_LABEL: Record<string, string> = {
  CRITICAL: 'Critical', HIGH: 'High', MEDIUM: 'Medium', LOW: 'Low', NONE: 'No findings',
};

const SEVERITY_RANK: Record<string, number> = { CRITICAL: 4, HIGH: 3, MEDIUM: 2, LOW: 1, NONE: 0 };

/** Integration id → the resolved Account label the backend would put on the filter option. */
const INTEGRATIONS: Record<string, string> = {
  'demo-int-aws-prod': '482193746510 (Xammer Production)',
  'demo-int-aws-stg': '715028364901 (Xammer Staging)',
  'demo-int-gcp': 'xammer-analytics (GCP Analytics)',
  'demo-int-azure': '3f9c2a14-7b6e-4d1a-9e2f-5c8b0d7a6e31 (Azure Enterprise)',
  'demo-int-onprem': 'Self-hosted Postgres',
  'demo-int-onprem-2': 'Self-hosted Postgres',
  'demo-int-gws': 'Google Workspace',
};

const CLOUD_ACCOUNT: Record<string, string> = {
  'demo-int-aws-prod': '482193746510',
  'demo-int-aws-stg': '715028364901',
  'demo-int-gcp': 'xammer-analytics',
  'demo-int-azure': '3f9c2a14-7b6e-4d1a-9e2f-5c8b0d7a6e31',
};

// ─── Asset specs ─────────────────────────────────────────────────────────────

type Status = 'CLASSIFIED' | 'NOT_SCANNED' | 'SCANNING' | 'FAILED' | 'STOPPED';

interface FileSpec {
  name: string;
  path: string | null;
  ext: string | null;
  type?: 'FILE' | 'COLUMN' | 'TABLE';
  sizeBytes?: number | null;
  findings: Array<[entity: string, count: number]>;
}

interface AssetSpec {
  id: string;
  surface: DspmSurface;
  category: 'cloud' | 'on_prem' | 'saas' | 'endpoint';
  resourceType: 'DATASTORE' | 'SAAS_PRINCIPAL' | 'ENDPOINT_HOST';
  name: string;
  subtype: string;
  provider: string;
  account: string | null;
  region: string | null;
  status: Status;
  /** Days since the last scan; null for none. */
  scanned: number | null;
  /**
   * Days since the scan status last changed (the vendor's `status_at`). The Datastores list shows it for
   * every status except Classified (design 5.6.1); absent when the vendor sends none.
   */
  statusAt?: number;
  risk: [level: DspmSeverity, score: number | null, violations: number | null];
  /** `sensitives.level`: what the Cloud table's Severity column shows. Null on SaaS and Endpoint. */
  sensLevel: string | null;
  objectTotal: number;
  externalId: string | null;
  lifecycle: string;
  facet: Record<string, unknown>;
  files: FileSpec[];
  /** A clean file for position i, used to fill the returned object list up to 50. */
  filler: (i: number) => FileSpec;
}

const s3Filler = (bucket: string, prefix: string) => (i: number): FileSpec => ({
  name: `part-${String(i).padStart(5, '0')}.parquet`,
  path: `s3://${bucket}/${prefix}/part-${String(i).padStart(5, '0')}.parquet`,
  ext: 'parquet', findings: [],
});
const columnFiller = (table: string, columns: string[]) => (i: number): FileSpec => ({
  name: columns[i % columns.length], path: `${table}.${columns[i % columns.length]}_${Math.floor(i / columns.length)}`,
  ext: null, type: 'COLUMN', findings: [],
});
const pathFiller = (base: string, ext: string) => (i: number): FileSpec => ({
  name: `file-${i}.${ext}`,
  // Windows homes join with a backslash, everything else with a slash.
  path: `${base}${base.includes('\\') ? '\\' : '/'}file-${i}.${ext}`,
  ext, findings: [],
});
const mailFiller = (i: number): FileSpec => ({
  name: ['Weekly digest', 'Team standup notes', 'Calendar invitation', 'Build succeeded', 'Newsletter'][i % 5] + ` #${i}`,
  path: null, ext: 'msg', findings: [],
});
const driveFiller = (i: number): FileSpec => ({
  name: `Meeting notes ${i}.docx`, path: null, ext: 'docx', findings: [],
});

const s3 = (bucket: string, account: string, region: string) => ({
  cloud_account_id: CLOUD_ACCOUNT[account], region, arn: `arn:aws:s3:::${bucket}`, able_to_scan: true,
});

const DATASTORES: AssetSpec[] = [
  {
    id: 'demo-ds-01', surface: 'cloud', category: 'cloud', resourceType: 'DATASTORE',
    name: 'xammer-prod-customer-exports', subtype: 'S3', provider: 'S3', account: 'demo-int-aws-prod',
    // Stopped 52 days ago, so the Datastores list shows an absolute date for it.
    region: 'ap-south-1', status: 'STOPPED', scanned: 52, statusAt: 52, risk: ['CRITICAL', 9.12, 14], sensLevel: 'HIGH_SENSITIVITY',
    objectTotal: 12480, externalId: 'arn:aws:s3:::xammer-prod-customer-exports', lifecycle: 'NOT_APPLICABLE',
    facet: { ...s3('xammer-prod-customer-exports', 'demo-int-aws-prod', 'ap-south-1'), storage_size_bytes: 1.84 * TB, number_of_records: 12531 },
    files: [
      { name: 'customers_2026_09.csv', path: 's3://xammer-prod-customer-exports/exports/2026/09/customers_2026_09.csv', ext: 'csv',
        findings: [['PERSON NAME', 48210], ['EMAIL ADDRESS', 48210], ['PHONE NUMBER', 45872], ['ADDRESS', 41206]] },
      { name: 'customers_2026_08.csv', path: 's3://xammer-prod-customer-exports/exports/2026/08/customers_2026_08.csv', ext: 'csv',
        findings: [['PERSON NAME', 47655], ['EMAIL ADDRESS', 47655], ['PHONE NUMBER', 44990]] },
      { name: 'pan_cards_batch_14.pdf', path: 's3://xammer-prod-customer-exports/kyc/2026/pan_cards_batch_14.pdf', ext: 'pdf',
        findings: [['PAN NUMBER', 1840], ['PERSON NAME', 1840], ['DATE OF BIRTH', 1840]] },
      { name: 'settlements_q3.xlsx', path: 's3://xammer-prod-customer-exports/payments/settlements_q3.xlsx', ext: 'xlsx',
        findings: [['BANK ACCOUNT NUMBER', 3120], ['IFSC CODE', 3120], ['PERSON NAME', 3120]] },
      { name: 'crm_contacts.json', path: 's3://xammer-prod-customer-exports/exports/crm/crm_contacts.json', ext: 'json',
        findings: [['EMAIL ADDRESS', 9820], ['PHONE NUMBER', 6310]] },
      { name: 'support_escalations.csv', path: 's3://xammer-prod-customer-exports/support/support_escalations.csv', ext: 'csv',
        findings: [['EMAIL ADDRESS', 412], ['PERSON NAME', 398], ['CREDIT CARD NUMBER', 6]] },
    ],
    filler: s3Filler('xammer-prod-customer-exports', 'archive/2025'),
  },
  {
    id: 'demo-ds-02', surface: 'cloud', category: 'cloud', resourceType: 'DATASTORE',
    name: 'xammer-terraform-state', subtype: 'S3', provider: 'S3', account: 'demo-int-aws-prod',
    region: 'us-east-1', status: 'CLASSIFIED', scanned: 2, risk: ['CRITICAL', 8.74, 9], sensLevel: 'HIGH_SENSITIVITY',
    objectTotal: 214, externalId: 'arn:aws:s3:::xammer-terraform-state', lifecycle: 'NOT_APPLICABLE',
    facet: { ...s3('xammer-terraform-state', 'demo-int-aws-prod', 'us-east-1'), storage_size_bytes: 186 * MB, number_of_records: 214 },
    files: [
      { name: 'terraform.tfstate', path: 's3://xammer-terraform-state/env/prod/terraform.tfstate', ext: 'json',
        findings: [['ACCESS KEY AWS', 6], ['HASHED SECRET KEY', 14], ['PRIVATE KEY', 2]] },
      { name: 'terraform.tfstate', path: 's3://xammer-terraform-state/env/staging/terraform.tfstate', ext: 'json',
        findings: [['ACCESS KEY AWS', 3], ['HASHED SECRET KEY', 9]] },
      { name: 'rds.auto.tfvars', path: 's3://xammer-terraform-state/env/prod/rds.auto.tfvars', ext: 'txt',
        findings: [['HASHED SECRET KEY', 2]] },
    ],
    filler: s3Filler('xammer-terraform-state', 'env/modules'),
  },
  {
    id: 'demo-ds-03', surface: 'cloud', category: 'cloud', resourceType: 'DATASTORE',
    name: 'xammer-prod-app-logs', subtype: 'S3', provider: 'S3', account: 'demo-int-aws-prod',
    region: 'ap-south-1', status: 'CLASSIFIED', scanned: 1, risk: ['HIGH', 7.41, 6], sensLevel: 'MEDIUM_SENSITIVITY',
    objectTotal: 86214, externalId: 'arn:aws:s3:::xammer-prod-app-logs', lifecycle: 'NOT_APPLICABLE',
    facet: { ...s3('xammer-prod-app-logs', 'demo-int-aws-prod', 'ap-south-1'), storage_size_bytes: 612 * GB, number_of_records: 86240 },
    files: [
      { name: 'access.log.gz', path: 's3://xammer-prod-app-logs/api-gateway/2026/09/28/access.log.gz', ext: 'gz',
        findings: [['IP ADDRESS', 18420], ['EMAIL ADDRESS', 312]] },
      { name: 'app.log', path: 's3://xammer-prod-app-logs/auth-service/2026/09/27/app.log', ext: 'log',
        findings: [['EMAIL ADDRESS', 1204], ['JWT TOKEN', 44]] },
      { name: 'payments-worker.log', path: 's3://xammer-prod-app-logs/payments/2026/09/26/payments-worker.log', ext: 'log',
        findings: [['CREDIT CARD NUMBER', 18], ['EMAIL ADDRESS', 96]] },
    ],
    filler: s3Filler('xammer-prod-app-logs', 'api-gateway/2026/09/25'),
  },
  {
    id: 'demo-ds-04', surface: 'cloud', category: 'cloud', resourceType: 'DATASTORE',
    name: 'xammer-staging-db-backups', subtype: 'S3', provider: 'S3', account: 'demo-int-aws-stg',
    region: 'ap-south-1', status: 'STOPPED', scanned: 6, statusAt: 6, risk: ['HIGH', 7.86, 4], sensLevel: 'HIGH_SENSITIVITY',
    objectTotal: 3402, externalId: 'arn:aws:s3:::xammer-staging-db-backups', lifecycle: 'NOT_APPLICABLE',
    facet: { ...s3('xammer-staging-db-backups', 'demo-int-aws-stg', 'ap-south-1'), storage_size_bytes: 244 * GB, number_of_records: 3402 },
    files: [
      { name: 'users_2026_09_20.sql.gz', path: 's3://xammer-staging-db-backups/dumps/users_2026_09_20.sql.gz', ext: 'gz',
        findings: [['EMAIL ADDRESS', 22840], ['HASHED SECRET KEY', 22840], ['PERSON NAME', 22840]] },
    ],
    filler: s3Filler('xammer-staging-db-backups', 'dumps/2026'),
  },
  {
    id: 'demo-ds-05', surface: 'cloud', category: 'cloud', resourceType: 'DATASTORE',
    name: 'orders-prod', subtype: 'DYNAMODB', provider: 'DYNAMODB', account: 'demo-int-aws-prod',
    region: 'ap-south-1', status: 'CLASSIFIED', scanned: 1, risk: ['HIGH', 6.92, 3], sensLevel: 'HIGH_SENSITIVITY',
    objectTotal: 18, externalId: 'arn:aws:dynamodb:ap-south-1:482193746510:table/orders-prod', lifecycle: 'NOT_APPLICABLE',
    facet: { cloud_account_id: '482193746510', region: 'ap-south-1', arn: 'arn:aws:dynamodb:ap-south-1:482193746510:table/orders-prod', storage_size_bytes: 38.4 * GB, able_to_scan: true },
    files: [
      { name: 'customer_email', path: 'orders-prod.customer_email', ext: null, type: 'COLUMN', findings: [['EMAIL ADDRESS', 1284300]] },
      { name: 'shipping_address', path: 'orders-prod.shipping_address', ext: null, type: 'COLUMN', findings: [['ADDRESS', 1284300]] },
      { name: 'phone', path: 'orders-prod.phone', ext: null, type: 'COLUMN', findings: [['PHONE NUMBER', 1190420]] },
      { name: 'customer_name', path: 'orders-prod.customer_name', ext: null, type: 'COLUMN', findings: [['PERSON NAME', 1284300]] },
    ],
    filler: columnFiller('orders-prod', ['order_id', 'status', 'created_at', 'sku', 'quantity', 'total', 'currency']),
  },
  {
    id: 'demo-ds-06', surface: 'cloud', category: 'cloud', resourceType: 'DATASTORE',
    name: 'xammer-prod-postgres', subtype: 'RDS', provider: 'RDS', account: 'demo-int-aws-prod',
    region: 'ap-south-1', status: 'CLASSIFIED', scanned: 2, risk: ['CRITICAL', 8.35, 11], sensLevel: 'HIGH_SENSITIVITY',
    objectTotal: 312, externalId: 'arn:aws:rds:ap-south-1:482193746510:db:xammer-prod-postgres', lifecycle: 'NOT_APPLICABLE',
    facet: { cloud_account_id: '482193746510', region: 'ap-south-1', arn: 'arn:aws:rds:ap-south-1:482193746510:db:xammer-prod-postgres',
      engine: 'postgres', engine_version: '15.6', storage_size_bytes: 420 * GB, able_to_scan: true },
    files: [
      { name: 'card_number', path: 'public.payments.card_number', ext: null, type: 'COLUMN', findings: [['CREDIT CARD NUMBER', 84210]] },
      { name: 'password_hash', path: 'public.users.password_hash', ext: null, type: 'COLUMN', findings: [['HASHED SECRET KEY', 120440]] },
      { name: 'email', path: 'public.users.email', ext: null, type: 'COLUMN', findings: [['EMAIL ADDRESS', 120440]] },
      { name: 'full_name', path: 'public.users.full_name', ext: null, type: 'COLUMN', findings: [['PERSON NAME', 120440]] },
      { name: 'date_of_birth', path: 'public.users.date_of_birth', ext: null, type: 'COLUMN', findings: [['DATE OF BIRTH', 96110]] },
      { name: 'last_login_ip', path: 'public.sessions.last_login_ip', ext: null, type: 'COLUMN', findings: [['IP ADDRESS', 402880]] },
    ],
    filler: columnFiller('public.orders', ['id', 'user_id', 'status', 'created_at', 'amount', 'currency', 'coupon']),
  },
  {
    id: 'demo-ds-07', surface: 'cloud', category: 'cloud', resourceType: 'DATASTORE',
    name: 'finance-archive', subtype: 'AZURE_BLOB', provider: 'AZURE_BLOB', account: 'demo-int-azure',
    region: 'centralindia', status: 'CLASSIFIED', scanned: 3, risk: ['HIGH', 7.18, 5], sensLevel: 'HIGH_SENSITIVITY',
    objectTotal: 9120, externalId: 'finance-archive', lifecycle: 'NOT_APPLICABLE',
    facet: { cloud_account_id: CLOUD_ACCOUNT['demo-int-azure'], region: 'centralindia', storage_size_bytes: 96 * GB, number_of_records: 9120, able_to_scan: true },
    files: [
      { name: 'payroll_sept_2026.xlsx', path: 'https://xammerfinance.blob.core.windows.net/finance-archive/payroll/payroll_sept_2026.xlsx', ext: 'xlsx',
        findings: [['BANK ACCOUNT NUMBER', 412], ['PAN NUMBER', 412], ['PERSON NAME', 412]] },
      { name: 'vendor_master.csv', path: 'https://xammerfinance.blob.core.windows.net/finance-archive/vendors/vendor_master.csv', ext: 'csv',
        findings: [['BANK ACCOUNT NUMBER', 186], ['IFSC CODE', 186], ['EMAIL ADDRESS', 174]] },
      { name: 'INV-2026-0912.pdf', path: 'https://xammerfinance.blob.core.windows.net/finance-archive/invoices/2026/INV-2026-0912.pdf', ext: 'pdf',
        findings: [['ADDRESS', 2], ['PERSON NAME', 2]] },
    ],
    filler: pathFiller('https://xammerfinance.blob.core.windows.net/finance-archive/reports', 'pdf'),
  },
  {
    id: 'demo-ds-08', surface: 'cloud', category: 'cloud', resourceType: 'DATASTORE',
    name: 'hr-documents', subtype: 'AZURE_BLOB', provider: 'AZURE_BLOB', account: 'demo-int-azure',
    region: 'centralindia', status: 'CLASSIFIED', scanned: 4, risk: ['MEDIUM', 5.94, 2], sensLevel: 'MEDIUM_SENSITIVITY',
    objectTotal: 2210, externalId: 'hr-documents', lifecycle: 'NOT_APPLICABLE',
    facet: { cloud_account_id: CLOUD_ACCOUNT['demo-int-azure'], region: 'centralindia', storage_size_bytes: 14.2 * GB, number_of_records: 2210, able_to_scan: true },
    files: [
      { name: 'claims_2026_q2.pdf', path: 'https://xammerhr.blob.core.windows.net/hr-documents/medical/claims_2026_q2.pdf', ext: 'pdf',
        findings: [['MEDICAL RECORD NUMBER', 86], ['PERSON NAME', 86]] },
      { name: 'offer_letters_2026.docx', path: 'https://xammerhr.blob.core.windows.net/hr-documents/offers/offer_letters_2026.docx', ext: 'docx',
        findings: [['PERSON NAME', 64], ['ADDRESS', 64], ['PHONE NUMBER', 58]] },
    ],
    filler: pathFiller('https://xammerhr.blob.core.windows.net/hr-documents/policies', 'pdf'),
  },
  {
    id: 'demo-ds-09', surface: 'cloud', category: 'cloud', resourceType: 'DATASTORE',
    name: 'analytics_events', subtype: 'BIGQUERY', provider: 'BIGQUERY', account: 'demo-int-gcp',
    region: 'asia-south1', status: 'CLASSIFIED', scanned: 2, risk: ['MEDIUM', 5.61, 2], sensLevel: 'MEDIUM_SENSITIVITY',
    objectTotal: 146, externalId: 'xammer-analytics.analytics_events', lifecycle: 'NOT_APPLICABLE',
    facet: { cloud_account_id: 'xammer-analytics', gcp_project_id: 'xammer-analytics', region: 'asia-south1', storage_size_bytes: 1.2 * TB, able_to_scan: true },
    files: [
      { name: 'user_email', path: 'analytics_events.events.user_email', ext: null, type: 'COLUMN', findings: [['EMAIL ADDRESS', 2104550]] },
      { name: 'client_ip', path: 'analytics_events.events.client_ip', ext: null, type: 'COLUMN', findings: [['IP ADDRESS', 9882100]] },
    ],
    filler: columnFiller('analytics_events.events', ['event_name', 'event_ts', 'page', 'referrer', 'device', 'country']),
  },
  {
    id: 'demo-ds-10', surface: 'cloud', category: 'cloud', resourceType: 'DATASTORE',
    name: 'xammer-analytics-raw', subtype: 'GCS', provider: 'GCS', account: 'demo-int-gcp',
    region: 'asia-south1', status: 'CLASSIFIED', scanned: 5, risk: ['LOW', 3.2, 1], sensLevel: 'LOW_SENSITIVITY',
    objectTotal: 5620, externalId: 'xammer-analytics-raw', lifecycle: 'NOT_APPLICABLE',
    facet: { cloud_account_id: 'xammer-analytics', gcp_project_id: 'xammer-analytics', region: 'asia-south1', storage_size_bytes: 78 * GB, number_of_records: 5620, able_to_scan: true },
    files: [
      { name: 'signups.csv', path: 'gs://xammer-analytics-raw/raw/2026/09/signups.csv', ext: 'csv',
        findings: [['EMAIL ADDRESS', 1820], ['PERSON NAME', 1820]] },
    ],
    filler: pathFiller('gs://xammer-analytics-raw/raw/2026/09/events', 'json'),
  },
  {
    id: 'demo-ds-11', surface: 'cloud', category: 'on_prem', resourceType: 'DATASTORE',
    name: 'crm_production', subtype: 'SELF_HOSTED_POSTGRES', provider: 'SELF_HOSTED_POSTGRES', account: 'demo-int-onprem',
    region: 'self-hosted', status: 'CLASSIFIED', scanned: 1, risk: ['HIGH', 6.44, 4], sensLevel: 'HIGH_SENSITIVITY',
    objectTotal: 228, externalId: 'crm_production', lifecycle: 'NOT_APPLICABLE',
    facet: { region: 'self-hosted', host: 'crm-db.internal', port: 5432, able_to_scan: true },
    files: [
      { name: 'email', path: 'public.contacts.email', ext: null, type: 'COLUMN', findings: [['EMAIL ADDRESS', 245120]] },
      { name: 'phone', path: 'public.contacts.phone', ext: null, type: 'COLUMN', findings: [['PHONE NUMBER', 231044]] },
      { name: 'secret', path: 'public.api_keys.secret', ext: null, type: 'COLUMN', findings: [['SHA KEY', 3410]] },
    ],
    filler: columnFiller('public.deals', ['id', 'stage', 'amount', 'owner_id', 'created_at', 'closed_at']),
  },
  {
    id: 'demo-ds-12', surface: 'cloud', category: 'cloud', resourceType: 'DATASTORE',
    name: 'vol-0a8f3c21d9e4b7f16', subtype: 'EBS', provider: 'EBS', account: 'demo-int-aws-prod',
    region: 'ap-south-1', status: 'CLASSIFIED', scanned: 3, risk: ['MEDIUM', 4.82, 2], sensLevel: 'MEDIUM_SENSITIVITY',
    objectTotal: 18904, externalId: 'vol-0a8f3c21d9e4b7f16', lifecycle: 'NOT_APPLICABLE',
    facet: { cloud_account_id: '482193746510', region: 'ap-south-1', storage_size_bytes: 200 * GB, number_of_records: 18904, able_to_scan: true },
    files: [
      { name: 'credentials', path: '/home/ubuntu/.aws/credentials', ext: null, findings: [['ACCESS KEY AWS', 2]] },
      { name: '.env', path: '/var/lib/billing-api/config/.env', ext: null, findings: [['HASHED SECRET KEY', 5], ['JWT TOKEN', 1]] },
    ],
    filler: pathFiller('/var/log/billing-api', 'log'),
  },
  {
    id: 'demo-ds-13', surface: 'cloud', category: 'cloud', resourceType: 'DATASTORE',
    name: 'xammer-staging-uploads', subtype: 'S3', provider: 'S3', account: 'demo-int-aws-stg',
    region: 'ap-south-1', status: 'CLASSIFIED', scanned: 4, risk: ['LOW', 1.84, null], sensLevel: 'LOW_SENSITIVITY',
    objectTotal: 1180, externalId: 'arn:aws:s3:::xammer-staging-uploads', lifecycle: 'NOT_APPLICABLE',
    facet: { ...s3('xammer-staging-uploads', 'demo-int-aws-stg', 'ap-south-1'), storage_size_bytes: 9.6 * GB, number_of_records: 1180 },
    files: [
      { name: 'sample_customers.csv', path: 's3://xammer-staging-uploads/uploads/test/sample_customers.csv', ext: 'csv',
        findings: [['EMAIL ADDRESS', 50], ['PERSON NAME', 50]] },
    ],
    filler: s3Filler('xammer-staging-uploads', 'uploads/images'),
  },
  {
    id: 'demo-ds-14', surface: 'cloud', category: 'cloud', resourceType: 'DATASTORE',
    name: 'xammer-marketing-assets', subtype: 'S3', provider: 'S3', account: 'demo-int-aws-prod',
    region: 'us-east-1', status: 'CLASSIFIED', scanned: 2, risk: ['NONE', null, null], sensLevel: 'NOT_SENSITIVE',
    objectTotal: 5630, externalId: 'arn:aws:s3:::xammer-marketing-assets', lifecycle: 'NOT_APPLICABLE',
    facet: { ...s3('xammer-marketing-assets', 'demo-int-aws-prod', 'us-east-1'), storage_size_bytes: 312 * GB, number_of_records: 5630 },
    files: [],
    filler: s3Filler('xammer-marketing-assets', 'campaigns/2026'),
  },
  {
    id: 'demo-ds-15', surface: 'cloud', category: 'cloud', resourceType: 'DATASTORE',
    name: 'xammer-cloudtrail-logs', subtype: 'S3', provider: 'S3', account: 'demo-int-aws-prod',
    region: 'us-east-1', status: 'CLASSIFIED', scanned: 1, risk: ['MEDIUM', 5.21, 3], sensLevel: 'MEDIUM_SENSITIVITY',
    objectTotal: 41380, externalId: 'arn:aws:s3:::xammer-cloudtrail-logs', lifecycle: 'NOT_APPLICABLE',
    facet: { ...s3('xammer-cloudtrail-logs', 'demo-int-aws-prod', 'us-east-1'), storage_size_bytes: 128 * GB, number_of_records: 41380 },
    files: [
      { name: '482193746510_CloudTrail_ap-south-1_20260928T0915Z.json.gz',
        path: 's3://xammer-cloudtrail-logs/AWSLogs/482193746510/CloudTrail/ap-south-1/2026/09/28/482193746510_CloudTrail_ap-south-1_20260928T0915Z.json.gz',
        ext: 'gz', findings: [['IP ADDRESS', 6210], ['ACCESS KEY AWS', 4]] },
    ],
    filler: s3Filler('xammer-cloudtrail-logs', 'AWSLogs/482193746510/CloudTrail/us-east-1/2026/09/27'),
  },
  {
    id: 'demo-ds-16', surface: 'cloud', category: 'cloud', resourceType: 'DATASTORE',
    name: 'vol-06d2b9e7c1a4f3508', subtype: 'EBS', provider: 'EBS', account: 'demo-int-aws-stg',
    region: 'ap-south-1', status: 'NOT_SCANNED', scanned: null, risk: ['NONE', null, null], sensLevel: null,
    objectTotal: 0, externalId: 'vol-06d2b9e7c1a4f3508', lifecycle: 'NOT_APPLICABLE',
    facet: { cloud_account_id: '715028364901', region: 'ap-south-1', storage_size_bytes: 100 * GB, able_to_scan: true },
    files: [], filler: pathFiller('/var/log', 'log'),
  },
  {
    id: 'demo-ds-17', surface: 'cloud', category: 'cloud', resourceType: 'DATASTORE',
    name: 'vol-0c7e1f4a2b9d86e33', subtype: 'EBS', provider: 'EBS', account: 'demo-int-aws-stg',
    region: 'us-east-1', status: 'FAILED', scanned: null, statusAt: 3.4, risk: ['NONE', null, null], sensLevel: null,
    objectTotal: 0, externalId: 'vol-0c7e1f4a2b9d86e33', lifecycle: 'NOT_APPLICABLE',
    facet: { cloud_account_id: '715028364901', region: 'us-east-1', storage_size_bytes: 50 * GB, able_to_scan: false },
    files: [], filler: pathFiller('/var/log', 'log'),
  },
  {
    id: 'demo-ds-18', surface: 'cloud', category: 'cloud', resourceType: 'DATASTORE',
    name: 'marketing_attribution', subtype: 'BIGQUERY', provider: 'BIGQUERY', account: 'demo-int-gcp',
    region: 'asia-south1', status: 'NOT_SCANNED', scanned: null, risk: ['NONE', null, null], sensLevel: null,
    objectTotal: 0, externalId: 'xammer-analytics.marketing_attribution', lifecycle: 'NOT_APPLICABLE',
    facet: { cloud_account_id: 'xammer-analytics', gcp_project_id: 'xammer-analytics', region: 'asia-south1', storage_size_bytes: 22 * GB, able_to_scan: true },
    files: [], filler: columnFiller('marketing_attribution.touches', ['id']),
  },
  {
    id: 'demo-ds-19', surface: 'cloud', category: 'cloud', resourceType: 'DATASTORE',
    name: 'xammer-ml-training-data', subtype: 'GCS', provider: 'GCS', account: 'demo-int-gcp',
    region: 'asia-south1', status: 'SCANNING', scanned: null, statusAt: 0.1, risk: ['NONE', null, null], sensLevel: null,
    objectTotal: 0, externalId: 'xammer-ml-training-data', lifecycle: 'NOT_APPLICABLE',
    facet: { cloud_account_id: 'xammer-analytics', gcp_project_id: 'xammer-analytics', region: 'asia-south1', storage_size_bytes: 2.6 * TB, able_to_scan: true },
    files: [], filler: pathFiller('gs://xammer-ml-training-data/datasets', 'parquet'),
  },
  {
    id: 'demo-ds-20', surface: 'cloud', category: 'cloud', resourceType: 'DATASTORE',
    name: 'ledger-exports-weu', subtype: 'AZURE_BLOB', provider: 'AZURE_BLOB', account: 'demo-int-azure',
    // Never scanned, its status set 47 hours ago: the list reads "1d ago". The drawer's empty states.
    region: 'centralindia', status: 'NOT_SCANNED', scanned: null, statusAt: 47 / 24, risk: ['NONE', null, null], sensLevel: null,
    objectTotal: 0, externalId: 'ledger-exports-weu', lifecycle: 'NOT_APPLICABLE',
    facet: { cloud_account_id: CLOUD_ACCOUNT['demo-int-azure'], region: 'centralindia', storage_size_bytes: 0, able_to_scan: true },
    files: [], filler: pathFiller('https://xammerledger.blob.core.windows.net/ledger-exports-weu', 'csv'),
  },
  {
    id: 'demo-ds-21', surface: 'cloud', category: 'cloud', resourceType: 'DATASTORE',
    name: 'xammer-prod-backups-glacier', subtype: 'S3', provider: 'S3', account: 'demo-int-aws-prod',
    region: 'ap-south-1', status: 'NOT_SCANNED', scanned: null, risk: ['NONE', null, null], sensLevel: null,
    objectTotal: 0, externalId: 'arn:aws:s3:::xammer-prod-backups-glacier', lifecycle: 'NOT_APPLICABLE',
    facet: { ...s3('xammer-prod-backups-glacier', 'demo-int-aws-prod', 'ap-south-1'), storage_size_bytes: 4.1 * TB },
    files: [], filler: s3Filler('xammer-prod-backups-glacier', 'daily'),
  },
  {
    id: 'demo-ds-22', surface: 'cloud', category: 'cloud', resourceType: 'DATASTORE',
    name: 'xammer-data-lake', subtype: 'S3', provider: 'S3', account: 'demo-int-aws-prod',
    region: 'us-east-1', status: 'FAILED', scanned: null, statusAt: 9.2, risk: ['NONE', null, null], sensLevel: null,
    objectTotal: 0, externalId: 'arn:aws:s3:::xammer-data-lake', lifecycle: 'NOT_APPLICABLE',
    facet: { ...s3('xammer-data-lake', 'demo-int-aws-prod', 'us-east-1'), storage_size_bytes: 9.8 * TB },
    files: [], filler: s3Filler('xammer-data-lake', 'curated'),
  },
  {
    // The second on-prem datastore: a failed scan 23.1 days ago, which the list reads "23d ago".
    id: 'demo-ds-23', surface: 'cloud', category: 'on_prem', resourceType: 'DATASTORE',
    name: 'ledger_replica', subtype: 'SELF_HOSTED_POSTGRES', provider: 'SELF_HOSTED_POSTGRES', account: 'demo-int-onprem-2',
    region: 'self-hosted', status: 'FAILED', scanned: null, statusAt: 23.1, risk: ['NONE', null, null], sensLevel: null,
    objectTotal: 0, externalId: 'ledger_replica', lifecycle: 'NOT_APPLICABLE',
    facet: { region: 'self-hosted', host: 'ledger-replica.internal', port: 5433, able_to_scan: true },
    files: [], filler: columnFiller('public.ledger_entries', ['id', 'account_id', 'amount', 'posted_at']),
  },
];

// ── SaaS ──

interface SaasSeed {
  id: string; person: string; email: string; drive: 'GMAIL' | 'GOOGLE_WORKSPACE'; status: Status; scanned: number | null;
  statusAt?: number;
  risk: DspmSeverity; inventories: number; sizeBytes: number; unsupported: number; files: FileSpec[];
}

/**
 * Added to every SaaS scan age. The console's Google Workspace scans were about 55 days old, so its
 * Classified SaaS rows show a date rather than "Nd ago" (design 5.6.1); so do the demo's.
 */
const SAAS_SCAN_AGE_DAYS = 54;

const mail = (subject: string, findings: FileSpec['findings']): FileSpec => ({ name: subject, path: null, ext: 'msg', findings });
const doc = (name: string, findings: FileSpec['findings']): FileSpec =>
  ({ name, path: null, ext: name.split('.').pop() ?? null, findings });

const SAAS_SEEDS: SaasSeed[] = [
  { id: 'demo-saas-01', person: 'Akshay Sharma', email: 'akshay@xammer.in', drive: 'GOOGLE_WORKSPACE', status: 'CLASSIFIED', scanned: 1,
    risk: 'CRITICAL', inventories: 845, sizeBytes: 64.2 * GB, unsupported: 61, files: [
      doc('customer_master_2026.xlsx', [['PERSON NAME', 64210], ['EMAIL ADDRESS', 64210], ['PHONE NUMBER', 58140]]),
      doc('aws_keys_backup.txt', [['ACCESS KEY AWS', 4]]),
      doc('Q3 revenue by customer.csv', [['PERSON NAME', 2480], ['EMAIL ADDRESS', 2480]]),
      doc('vendor onboarding - bank details.pdf', [['BANK ACCOUNT NUMBER', 22], ['IFSC CODE', 22], ['PERSON NAME', 22]]),
    ] },
  { id: 'demo-saas-02', person: 'Notifications', email: 'notifications@xammer.in', drive: 'GMAIL', status: 'CLASSIFIED', scanned: 1,
    risk: 'HIGH', inventories: 18420, sizeBytes: 0, unsupported: 254, files: [
      mail('Your new XamOps sign-in', [['EMAIL ADDRESS', 1], ['HASHED SECRET KEY', 1]]),
      mail('Undeliverable: weekly digest', [['EMAIL ADDRESS', 2]]),
      mail('Password reset requested', [['EMAIL ADDRESS', 1], ['JWT TOKEN', 1]]),
      mail('Invoice INV-2026-04-001 is ready', [['PERSON NAME', 1], ['ADDRESS', 1], ['EMAIL ADDRESS', 1]]),
    ] },
  { id: 'demo-saas-03', person: 'Priya Nair', email: 'priya@xammer.in', drive: 'GMAIL', status: 'CLASSIFIED', scanned: 1,
    risk: 'HIGH', inventories: 2672, sizeBytes: 0, unsupported: 96, files: [
      // The SaaS drawer's Gmail showcase (design 7.2): PERSON NAME is in six files, so its file list has a
      // second page at Rows Per Page 5; the totals per element are build 1's.
      mail('Re: Payroll inputs for September', [['BANK ACCOUNT NUMBER', 14], ['PAN NUMBER', 14], ['PERSON NAME', 8]]),
      mail('Fwd: Candidate documents', [['AADHAAR NUMBER', 1], ['DATE OF BIRTH', 1], ['ADDRESS', 1]]),
      mail('Customer escalation - refund', [['CREDIT CARD NUMBER', 1], ['PHONE NUMBER', 2], ['EMAIL ADDRESS', 3]]),
      doc('Offer Letter - Priya Nair.pdf', [['PERSON NAME', 2]]),
      mail('Re: Offer acceptance', [['PERSON NAME', 1]]),
      mail('Signed contract - vendor NDA', [['PERSON NAME', 1]]),
      mail('Incident review notes', [['PERSON NAME', 1]]),
      mail('Payment confirmation - relocation', [['PERSON NAME', 1]]),
    ] },
  { id: 'demo-saas-04', person: 'Priya Nair', email: 'priya@xammer.in', drive: 'GOOGLE_WORKSPACE', status: 'CLASSIFIED', scanned: 2,
    risk: 'MEDIUM', inventories: 312, sizeBytes: 8.6 * GB, unsupported: 18, files: [
      doc('Team Roster.xlsx', [['PERSON NAME', 84], ['PHONE NUMBER', 84], ['EMAIL ADDRESS', 84]]),
      doc('Offer Letter - Priya Nair.docx', [['PERSON NAME', 1], ['ADDRESS', 1]]),
    ] },
  { id: 'demo-saas-05', person: 'Rohan Mehta', email: 'rohan@xammer.in', drive: 'GMAIL', status: 'CLASSIFIED', scanned: 1,
    risk: 'MEDIUM', inventories: 1639, sizeBytes: 0, unsupported: 70, files: [
      mail('Campaign leads - Sept', [['EMAIL ADDRESS', 612], ['PHONE NUMBER', 540], ['PERSON NAME', 612]]),
      mail('Event registrations export', [['EMAIL ADDRESS', 188], ['PERSON NAME', 188]]),
    ] },
  { id: 'demo-saas-06', person: 'Rohan Mehta', email: 'rohan@xammer.in', drive: 'GOOGLE_WORKSPACE', status: 'CLASSIFIED', scanned: 3,
    risk: 'LOW', inventories: 128, sizeBytes: 14 * MB, unsupported: 24, files: [
      doc('Webinar attendees.csv', [['EMAIL ADDRESS', 39]]),
    ] },
  { id: 'demo-saas-07', person: 'Vijay Kumar', email: 'vijay@xammer.in', drive: 'GMAIL', status: 'CLASSIFIED', scanned: 1,
    risk: 'HIGH', inventories: 407, sizeBytes: 0, unsupported: 8, files: [
      mail('Re: Enterprise quote - PO attached', [['BANK ACCOUNT NUMBER', 1], ['ADDRESS', 2], ['PERSON NAME', 3]]),
      mail('Signed MSA - Acme', [['PERSON NAME', 4], ['EMAIL ADDRESS', 6]]),
      mail('AWS root access for demo account', [['ACCESS KEY AWS', 1]]),
    ] },
  { id: 'demo-saas-08', person: 'Vijay Kumar', email: 'vijay@xammer.in', drive: 'GOOGLE_WORKSPACE', status: 'CLASSIFIED', scanned: 2,
    risk: 'MEDIUM', inventories: 244, sizeBytes: 1.13 * GB, unsupported: 43, files: [
      doc('Pipeline Q4.xlsx', [['PERSON NAME', 96], ['EMAIL ADDRESS', 96], ['PHONE NUMBER', 18]]),
    ] },
  { id: 'demo-saas-09', person: 'Ananya Reddy', email: 'ananya@xammer.in', drive: 'GMAIL', status: 'CLASSIFIED', scanned: 1,
    risk: 'MEDIUM', inventories: 300, sizeBytes: 0, unsupported: 4, files: [
      mail('Re: Staging DB connection string', [['HASHED SECRET KEY', 1], ['IP ADDRESS', 2]]),
      mail('On-call handover', [['IP ADDRESS', 6], ['EMAIL ADDRESS', 2]]),
    ] },
  { id: 'demo-saas-10', person: 'Ananya Reddy', email: 'ananya@xammer.in', drive: 'GOOGLE_WORKSPACE', status: 'SCANNING', scanned: null,
    statusAt: 0.2, risk: 'NONE', inventories: 96, sizeBytes: 2.4 * GB, unsupported: 11, files: [] },
  { id: 'demo-saas-11', person: 'Billing', email: 'billing@xammer.in', drive: 'GMAIL', status: 'CLASSIFIED', scanned: 2,
    risk: 'HIGH', inventories: 256, sizeBytes: 0, unsupported: 8, files: [
      mail('Card payment failed - action needed', [['CREDIT CARD NUMBER', 2], ['PERSON NAME', 2], ['EMAIL ADDRESS', 2]]),
      mail('Remittance advice', [['BANK ACCOUNT NUMBER', 3], ['IFSC CODE', 3]]),
    ] },
  { id: 'demo-saas-12', person: 'Sneha Patel', email: 'sneha@xammer.in', drive: 'GMAIL', status: 'NOT_SCANNED', scanned: null,
    risk: 'NONE', inventories: 0, sizeBytes: 0, unsupported: 0, files: [] },
  { id: 'demo-saas-13', person: 'Deepak Singh', email: 'deepak@xammer.in', drive: 'GMAIL', status: 'NOT_SCANNED', scanned: null,
    risk: 'NONE', inventories: 0, sizeBytes: 0, unsupported: 0, files: [] },
  { id: 'demo-saas-14', person: 'Meera Joshi', email: 'meera@xammer.in', drive: 'GMAIL', status: 'CLASSIFIED', scanned: 9,
    risk: 'LOW', inventories: 189, sizeBytes: 0, unsupported: 0, files: [
      mail('Vendor contact list', [['PHONE NUMBER', 12], ['EMAIL ADDRESS', 12]]),
    ] },
];

const SAAS: AssetSpec[] = SAAS_SEEDS.map(seed => {
  const supported = Math.max(0, seed.inventories - seed.unsupported);
  const supportedSize = seed.sizeBytes > 0 ? Math.round(seed.sizeBytes * 0.94) : 0;
  return {
    id: seed.id, surface: 'saas', category: 'saas', resourceType: 'SAAS_PRINCIPAL',
    // The drive is the subtype: a Gmail mailbox or a Google Drive.
    name: seed.person, subtype: seed.drive, provider: 'GOOGLE_WORKSPACE', account: 'demo-int-gws', region: null,
    status: seed.status, scanned: seed.scanned === null ? null : seed.scanned + SAAS_SCAN_AGE_DAYS, statusAt: seed.statusAt,
    risk: [seed.risk, null, null], sensLevel: null,
    objectTotal: seed.status === 'CLASSIFIED' ? supported : 0, externalId: seed.email, lifecycle: 'ACTIVE',
    facet: {
      drive_type: seed.drive, entity_type: 'USER',
      total_inventories: seed.inventories, total_size_bytes: seed.sizeBytes,
      supported_files_count: supported, supported_files_size: supportedSize,
      unsupported_files_count: seed.unsupported, unsupported_files_size: Math.max(0, seed.sizeBytes - supportedSize),
      is_selected: true, is_unassigned: false,
    },
    files: seed.files,
    filler: seed.drive === 'GMAIL' ? mailFiller : driveFiller,
  };
});

// ── Endpoint ──

interface DeviceSeed {
  id: string; name: string; os: 'MAC' | 'WINDOWS' | 'LINUX'; osVersion: string; user: string; serial: string;
  status: Status; online: boolean; pingHoursAgo: number; risk: DspmSeverity; objectTotal: number; ip: string;
  mac: string; arch: string; memoryGb: number; files: FileSpec[]; home: string; agentVersion: string;
}

const DEVICE_SEEDS: DeviceSeed[] = [
  { id: 'demo-ep-01', name: 'Priya’s MacBook Pro', os: 'MAC', osVersion: '15.1', user: 'priya', serial: 'C02XD4DEMO01',
    status: 'CLASSIFIED', online: true, pingHoursAgo: 0.2, risk: 'HIGH', objectTotal: 4210, ip: '203.0.113.24',
    mac: '02:1a:4b:3c:5d:01', arch: 'arm64', memoryGb: 16, home: '/Users/priya', agentVersion: '2.1.0', files: [
      { name: 'customer_export_sept.csv', path: '/Users/priya/Downloads/customer_export_sept.csv', ext: 'csv',
        findings: [['EMAIL ADDRESS', 1840], ['PERSON NAME', 1840], ['PHONE NUMBER', 1602]] },
      { name: 'payroll_draft.xlsx', path: '/Users/priya/Desktop/payroll_draft.xlsx', ext: 'xlsx',
        findings: [['BANK ACCOUNT NUMBER', 58], ['PAN NUMBER', 58]] },
      { name: 'credentials.bak', path: '/Users/priya/Documents/aws/credentials.bak', ext: 'bak', findings: [['ACCESS KEY AWS', 2]] },
    ] },
  { id: 'demo-ep-02', name: 'FIN-LAPTOP-07', os: 'WINDOWS', osVersion: '11 Pro 23H2', user: 'vijay', serial: '8TG5DEMO07',
    status: 'CLASSIFIED', online: true, pingHoursAgo: 1, risk: 'CRITICAL', objectTotal: 6932, ip: '203.0.113.87',
    mac: '02:1a:4b:3c:5d:07', arch: 'amd64', memoryGb: 16, home: 'C:\\Users\\vijay', agentVersion: '2.2.0', files: [
      { name: 'vendor_bank_details.xlsx', path: 'C:\\Users\\vijay\\Documents\\Finance\\vendor_bank_details.xlsx', ext: 'xlsx',
        findings: [['BANK ACCOUNT NUMBER', 312], ['IFSC CODE', 312], ['PERSON NAME', 312]] },
      { name: 'card_disputes_q3.csv', path: 'C:\\Users\\vijay\\Downloads\\card_disputes_q3.csv', ext: 'csv',
        findings: [['CREDIT CARD NUMBER', 86], ['PERSON NAME', 86]] },
    ] },
  { id: 'demo-ep-03', name: 'Akshay’s MacBook Pro', os: 'MAC', osVersion: '14.6.1', user: 'akshay', serial: 'C02XD4DEMO03',
    status: 'CLASSIFIED', online: false, pingHoursAgo: 30, risk: 'MEDIUM', objectTotal: 2943, ip: '198.51.100.12',
    mac: '02:1a:4b:3c:5d:03', arch: 'arm64', memoryGb: 32, home: '/Users/akshay', agentVersion: '2.2.2', files: [
      { name: '.env', path: '/Users/akshay/code/billing-service/.env', ext: null, findings: [['HASHED SECRET KEY', 6], ['JWT TOKEN', 2]] },
      { name: 'leads.csv', path: '/Users/akshay/Downloads/leads.csv', ext: 'csv', findings: [['EMAIL ADDRESS', 420], ['PHONE NUMBER', 388]] },
      { name: 'contacts-sample.json', path: '/Users/akshay/code/crm-api/fixtures/contacts-sample.json', ext: 'json',
        findings: [['EMAIL ADDRESS', 2], ['PERSON NAME', 1]] },
    ] },
  { id: 'demo-ep-04', name: 'build-runner-01', os: 'LINUX', osVersion: 'Ubuntu 22.04', user: 'ci', serial: 'VM-DEMO-0001',
    status: 'CLASSIFIED', online: true, pingHoursAgo: 0.1, risk: 'LOW', objectTotal: 18240, ip: '198.51.100.40',
    mac: '02:1a:4b:3c:5d:04', arch: 'amd64', memoryGb: 8, home: '/home/ci', agentVersion: '2.2.2', files: [
      { name: 'config.json', path: '/home/ci/.docker/config.json', ext: 'json', findings: [['HASHED SECRET KEY', 1]] },
    ] },
  { id: 'demo-ep-05', name: 'HR-DESKTOP-02', os: 'WINDOWS', osVersion: '11 Pro 23H2', user: 'meera', serial: '5KD2DEMO02',
    status: 'SCANNING', online: true, pingHoursAgo: 0.5, risk: 'NONE', objectTotal: 0, ip: '203.0.113.141',
    mac: '02:1a:4b:3c:5d:05', arch: 'amd64', memoryGb: 8, home: 'C:\\Users\\meera', agentVersion: '2.2.0', files: [] },
  { id: 'demo-ep-06', name: 'Sneha’s MacBook Air', os: 'MAC', osVersion: '15.0.1', user: 'sneha', serial: 'C02XD4DEMO06',
    status: 'NOT_SCANNED', online: false, pingHoursAgo: 96, risk: 'NONE', objectTotal: 0, ip: '198.51.100.77',
    mac: '02:1a:4b:3c:5d:06', arch: 'arm64', memoryGb: 8, home: '/Users/sneha', agentVersion: '2.1.0', files: [] },
];

const ENDPOINTS: AssetSpec[] = DEVICE_SEEDS.map(seed => ({
  id: seed.id, surface: 'endpoint', category: 'endpoint', resourceType: 'ENDPOINT_HOST',
  name: seed.name, subtype: seed.os, provider: 'ENDPOINT_AGENT', account: null, region: null,
  status: seed.status, scanned: null, risk: [seed.risk, null, null], sensLevel: null,
  objectTotal: seed.objectTotal, externalId: seed.serial, lifecycle: 'ACTIVE',
  facet: {
    agent_status: seed.online ? 'ONLINE' : 'OFFLINE', agent_version: seed.agentVersion, architecture: seed.arch,
    cpu_usage: seed.online ? 0.14 : 0.01, device_type: seed.os === 'LINUX' ? 'server' : 'laptop',
    device_users: [seed.user], enrolled_user: seed.name, hostname: seed.name,
    last_ping_time: Math.floor(Date.now() / 1000 - seed.pingHoursAgo * 3600),
    mac_address: seed.mac, memory: `${seed.memoryGb} GB`, memory_usage: seed.online ? 0.52 : 0.21,
    os: seed.os, os_version: seed.osVersion, public_ip: seed.ip, serial_number: seed.serial,
    used_disk_space_bytes: Math.round((seed.memoryGb * 14) * GB), user: seed.name,
  },
  files: seed.files,
  filler: seed.os === 'WINDOWS'
    ? pathFiller(`${seed.home}\\AppData\\Local\\Temp`, 'tmp')
    : pathFiller(`${seed.home}/code/src`, 'ts'),
}));

const ALL: AssetSpec[] = [...DATASTORES, ...SAAS, ...ENDPOINTS];

// ─── Derivation ──────────────────────────────────────────────────────────────

const SURFACE_DIGIT: Record<DspmSurface, string> = { cloud: '0', saas: '1', endpoint: '2' };

/**
 * The 24-hex datastore id: `de3`, the surface digit (cloud and on-prem 0, SaaS 1, endpoint 2), then the
 * spec's number padded to 20 digits. `demo-ds-01` is `de3000000000000000000001`.
 */
function sourceIdOf(spec: AssetSpec): string {
  return `de3${SURFACE_DIGIT[spec.surface]}${spec.id.replace(/\D/g, '').padStart(20, '0')}`;
}

/** The returned object list: every sensitive file, then clean ones, capped at 50 like the API. */
function objectsOf(spec: AssetSpec): DspmObject[] {
  const cap = Math.min(50, spec.objectTotal);
  const specs = [...spec.files];
  for (let i = 0; specs.length < cap; i++) specs.push(spec.filler(i));
  return specs.map((file, i) => {
    const findings = file.findings.map(([entityType, count]) => {
      const category = ENTITY_CATEGORY[entityType] ?? null;
      return { entityType, category, categoryLabel: category ? CATEGORY_LABEL[category] : null, count };
    });
    const sensitiveCount = findings.reduce((sum, f) => sum + f.count, 0);
    return {
      id: `${spec.id}-obj-${i}`,
      name: file.name,
      objectType: file.type ?? 'FILE',
      source: spec.surface === 'saas' ? 'SAAS' : spec.surface === 'endpoint' ? 'ENDPOINT_AGENT' : 'CLOUD_SCAN',
      path: file.path,
      extension: file.ext,
      sizeBytes: file.sizeBytes ?? null,
      sensitive: sensitiveCount > 0,
      sensitiveCount,
      findings,
    };
  }).sort((a, b) => b.sensitiveCount - a.sensitiveCount);
}

function breakdownOf(objects: DspmObject[]): DspmEntityCount[] {
  const totals = new Map<string, DspmEntityCount>();
  for (const object of objects) {
    const seen = new Set<string>();
    for (const finding of object.findings) {
      const entry = totals.get(finding.entityType) ?? {
        entityType: finding.entityType, category: finding.category, categoryLabel: finding.categoryLabel,
        count: 0, fileCount: 0, sampled: true,
      };
      entry.count += finding.count;
      if (!seen.has(finding.entityType)) { entry.fileCount += 1; seen.add(finding.entityType); }
      totals.set(finding.entityType, entry);
    }
  }
  return [...totals.values()].sort((a, b) => b.count - a.count);
}

interface Built {
  spec: AssetSpec;
  row: DspmAssetRow;
  detail: () => DspmAssetDetail;
  /** Detectors present, for the data_element filter. */
  elements: string[];
}

function build(spec: AssetSpec): Built {
  const objects = objectsOf(spec);
  const records = objects.reduce((sum, o) => sum + o.sensitiveCount, 0);
  const types = [...new Set(objects.flatMap(o => o.findings.map(f => f.category)).filter((c): c is string => !!c))];
  const known = spec.status === 'CLASSIFIED';
  const [level, score, violations] = spec.risk;
  const scannedAt = spec.scanned === null ? null : nowSeconds() - spec.scanned * DAY - 3 * 3600;

  const row: DspmAssetRow = {
    id: spec.id,
    name: spec.name,
    subtype: spec.subtype,
    subtypeLabel: PROVIDER_LABEL[spec.subtype] ?? spec.subtype,
    category: spec.category,
    resourceType: spec.resourceType,
    sourceId: sourceIdOf(spec),
    lifecycle: spec.lifecycle,
    risk: { score, level, policyViolationCount: violations, hasFinding: level !== 'NONE' },
    sensitives: {
      recordCount: records,
      objectCount: spec.objectTotal,
      types,
      typeLabels: types.map(t => CATEGORY_LABEL[t] ?? t),
      level: spec.sensLevel,
      known,
    },
    source: {
      provider: spec.provider,
      providerLabel: PROVIDER_LABEL[spec.provider] ?? spec.provider,
      account: spec.account,
      region: spec.region,
    },
    scan: {
      status: spec.status,
      statusLabel: SCAN_LABEL[spec.status],
      stage: SCAN_STAGE[spec.status] ?? null,
      lastScannedAt: scannedAt,
    },
    // The backend attaches detail-only fields to SaaS and Endpoint rows; so does the demo.
    profile: spec.surface === 'cloud' ? null : { externalId: spec.externalId, facet: spec.facet, recordCount: null },
  };

  return {
    spec,
    row,
    elements: [...new Set(objects.flatMap(o => o.findings.map(f => f.entityType)))],
    detail: () => ({
      id: row.id, name: row.name, subtype: row.subtype, subtypeLabel: row.subtypeLabel, category: row.category,
      resourceType: row.resourceType, externalId: spec.externalId, lifecycle: row.lifecycle,
      risk: row.risk, sensitives: row.sensitives, source: row.source, scan: row.scan,
      facet: spec.facet,
      objects,
      objectTotal: spec.objectTotal,
      objectsTruncated: objects.length < spec.objectTotal,
      entityBreakdown: breakdownOf(objects),
      // Every sensitive file is in the list, so no count is a minimum.
      breakdownPartial: false,
      duplicateObjects: 0,
    }),
  };
}

const BUILT: Built[] = ALL.map(build);
const BY_ID = new Map(BUILT.map(b => [b.row.id, b]));

// ─── Querying ────────────────────────────────────────────────────────────────

const DIMENSIONS = [
  'severity', 'scan_status', 'provider', 'sensitive_type', 'data_element', 'integration_id', 'lifecycle',
] as const;
type Dimension = typeof DIMENSIONS[number];

/** A row's values for a filter dimension; some dimensions are multi-valued. */
function valuesOf(item: Built, dimension: Dimension): string[] {
  const { row } = item;
  switch (dimension) {
    case 'severity': return [row.risk.level];
    case 'scan_status': return [String(row.scan.status)];
    case 'provider': return row.source.provider ? [row.source.provider] : [];
    case 'sensitive_type': return row.sensitives.types;
    case 'data_element': return item.elements;
    case 'integration_id': return row.source.account ? [row.source.account] : [];
    case 'lifecycle': return row.lifecycle ? [row.lifecycle] : [];
    default: return [];
  }
}

function optionLabel(dimension: Dimension, value: string): string {
  switch (dimension) {
    case 'severity': return SEVERITY_LABEL[value] ?? value;
    case 'scan_status': return SCAN_LABEL[value] ?? value;
    case 'provider': return PROVIDER_LABEL[value] ?? value;
    case 'sensitive_type': return CATEGORY_LABEL[value] ?? value;
    case 'integration_id': return INTEGRATIONS[value] ?? value;
    case 'lifecycle': return value === 'NOT_APPLICABLE' ? 'Not Applicable' : value.charAt(0) + value.slice(1).toLowerCase();
    default: return value;
  }
}

interface Query {
  search: string;
  selected: Partial<Record<Dimension, string[]>>;
  sort: string;
  page: number;
  pageSize: number;
}

function parse(url: string): Query {
  const q = url.indexOf('?');
  const params = new URLSearchParams(q === -1 ? '' : url.slice(q + 1));
  const selected: Query['selected'] = {};
  for (const dimension of DIMENSIONS) {
    const raw = params.get(dimension);
    if (raw) selected[dimension] = raw.split(',').map(v => v.trim()).filter(Boolean);
  }
  return {
    search: (params.get('q') ?? '').trim().toLowerCase(),
    selected,
    sort: params.get('sort') ?? '-risk_score',
    page: Math.max(1, Number(params.get('page')) || 1),
    pageSize: Math.min(100, Math.max(1, Number(params.get('pageSize')) || 10)),
  };
}

/** Assets on a surface matching the search and every selected dimension except `skip`. */
function matching(surface: DspmSurface, query: Query, skip?: Dimension): Built[] {
  return BUILT.filter(item => {
    if (item.spec.surface !== surface) return false;
    if (query.search) {
      const haystack = [item.row.name, item.row.sourceId, item.spec.externalId].join(' ').toLowerCase();
      if (!haystack.includes(query.search)) return false;
    }
    for (const dimension of DIMENSIONS) {
      if (dimension === skip) continue;
      const wanted = query.selected[dimension];
      if (!wanted?.length) continue;
      const values = valuesOf(item, dimension);
      if (!wanted.some(v => values.includes(v))) return false;
    }
    return true;
  });
}

function sortKey(item: Built, field: string): number | string | null {
  const { row } = item;
  switch (field) {
    case 'name': return row.name.toLowerCase();
    case 'risk_score': return row.risk.score;
    case 'sensitive_record_count': return row.sensitives.recordCount;
    case 'severity': return SEVERITY_RANK[row.risk.level] ?? 0;
    case 'last_modified_at': return row.scan.lastScannedAt;
    default: return null;
  }
}

/** `GET /api/dspm/{surface}/assets`. */
export function demoDspmAssets(surface: DspmSurface, url: string): DspmPage<DspmAssetRow> {
  const query = parse(url);
  const descending = query.sort.startsWith('-');
  const field = descending ? query.sort.slice(1) : query.sort;

  const rows = matching(surface, query).sort((a, b) => {
    const x = sortKey(a, field);
    const y = sortKey(b, field);
    // Missing values last in either direction, then name for a stable order.
    if (x === null && y !== null) return 1;
    if (y === null && x !== null) return -1;
    if (x !== null && y !== null && x !== y) {
      const order = x < y ? -1 : 1;
      return descending ? -order : order;
    }
    return a.row.name.localeCompare(b.row.name);
  });

  const total = rows.length;
  const totalPages = Math.max(1, Math.ceil(total / query.pageSize));
  const start = (query.page - 1) * query.pageSize;
  return {
    data: rows.slice(start, start + query.pageSize).map(item => item.row),
    page: query.page,
    pageSize: query.pageSize,
    total,
    totalPages,
  };
}

/** `GET /api/dspm/{surface}/filters`, with disjunctive counts like the vendor's. */
export function demoDspmFilters(surface: DspmSurface, url: string): DspmFilters {
  const query = parse(url);
  const dimensions: Record<string, DspmFilterOption[]> = {};
  const hasMore: Record<string, boolean> = {};

  for (const dimension of DIMENSIONS) {
    const counts = new Map<string, number>();
    for (const item of matching(surface, query, dimension)) {
      for (const value of valuesOf(item, dimension)) counts.set(value, (counts.get(value) ?? 0) + 1);
    }
    dimensions[dimension] = [...counts.entries()]
      .sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0]))
      .map(([value, count]) => ({ value, label: optionLabel(dimension, value), count }));
    hasMore[dimension] = false;
  }
  return { dimensions, hasMore };
}

/** `GET /api/dspm/assets/{id}`, or null for an unknown id (the route answers 404). */
export function demoDspmAsset(id: string): DspmAssetDetail | null {
  return BY_ID.get(id)?.detail() ?? null;
}

/** `GET /api/dspm/status`: connected and entitled, so every tab renders data. */
export function demoDspmStatus(): DspmStatus {
  return {
    ready: true,
    entitled: true,
    configured: true,
    reason: null,
    message: null,
    projectName: 'Xammer Demo',
    lastVerifiedAt: new Date(Date.now() - 2 * 3600 * 1000).toISOString(),
  };
}

// ─── Datastores page: list, filter options and KPIs (design 7.2) ─────────────

/**
 * Integration → the label the Datastores labeler resolves (design 5.8): the XamOps account name alone
 * for a cloud, the workspace's account name for SaaS. On-prem rows carry their own name and endpoints
 * none, so neither is listed.
 */
const ACCOUNT_LABEL: Record<string, string> = {
  'demo-int-aws-prod': 'Xammer Production',
  'demo-int-aws-stg': 'Xammer Staging',
  'demo-int-gcp': 'GCP Analytics',
  'demo-int-azure': 'Azure Enterprise',
  'demo-int-gws': 'xammer-workspace',
};

/** Integration → its 24-hex id, the value the `integration_id` filter sends. */
const INTEGRATION_ID: Record<string, string> = {
  'demo-int-aws-prod': 'de3900000000000000000001',
  'demo-int-aws-stg': 'de3900000000000000000002',
  'demo-int-gcp': 'de3900000000000000000003',
  'demo-int-azure': 'de3900000000000000000004',
  'demo-int-onprem': 'de3900000000000000000005',
  'demo-int-onprem-2': 'de3900000000000000000006',
  'demo-int-gws': 'de3900000000000000000007',
};

// The backend's label maps (`DatastoreLabels`, design 5.7). An unlisted code is prettified.
const DS_PROVIDER_LABEL: Record<string, string> = {
  EBS: 'EBS', S3: 'S3', AZURE_BLOB: 'Azure Blob', BIGQUERY: 'BigQuery', DYNAMODB: 'DynamoDB', GCS: 'GCS', RDS: 'RDS',
  GOOGLE_WORKSPACE: 'Google Workspace', ENDPOINT_AGENT: 'Endpoint Agent', SELF_HOSTED_POSTGRES: 'PostgreSQL (self-hosted)',
};
const DS_SUBTYPE_LABEL: Record<string, string> = {
  S3: 'S3', GMAIL: 'Gmail', GOOGLE_WORKSPACE: 'Google Drive', SELF_HOSTED_POSTGRES: 'PostgreSQL', AZURE_BLOB: 'Azure Blob',
  MAC: 'macOS', WINDOWS: 'Windows',
};
const DS_SCAN_LABEL: Record<string, string> = {
  NOT_SCANNED: 'Not Scanned', CLASSIFIED: 'Classified', FAILED: 'Failed', STOPPED: 'Stopped', SCANNING: 'Scanning',
  SCHEDULED: 'Scheduled',
};
const LEVEL_LABEL: Record<string, string> = { CRITICAL: 'Critical', HIGH: 'High', MEDIUM: 'Medium', LOW: 'Low' };
const OS_LABEL: Record<string, string> = { MAC: 'macOS', WINDOWS: 'Windows' };
const AGENT_STATUS_LABEL: Record<string, string> = { ONLINE: 'Online', OFFLINE: 'Offline' };

/** `DspmLabels.prettify`: underscores to spaces, each word capitalised. */
const prettify = (code: string) =>
  code.toLowerCase().split('_').filter(Boolean).map(word => word[0].toUpperCase() + word.slice(1)).join(' ');

/** A row's sensitive figures as every Datastores answer reports them. */
interface SensitiveFigures {
  recordCount: number;
  /** Category codes, in the row's order. */
  types: string[];
  /** Detector names, for the `data_element` filter. */
  elements: string[];
}

/**
 * The three showcase datastores take their figures from their drawer data (`SHOWCASE_SENSITIVES`);
 * every other row from its files, as `build()` derives them. The list, the filter options and the KPIs
 * all read this, so they agree with the drawers.
 */
function datastoreSensitivesOf(item: Built): SensitiveFigures {
  return SHOWCASE_SENSITIVES[item.spec.id]
    ?? { recordCount: item.row.sensitives.recordCount, types: item.row.sensitives.types, elements: item.elements };
}

/** The labeler's row rule (design 5.8). */
function accountLabelOf(spec: AssetSpec): string | null {
  if (spec.category === 'on_prem') return spec.name;
  if (spec.category === 'endpoint' || !spec.account) return null;
  return ACCOUNT_LABEL[spec.account] ?? null;
}

/**
 * The time the Scan Status cell shows (design 5.6.1): Classified rows show `last_scanned_at`, which only
 * SaaS rows carry (cloud and endpoint rows show no time); every other status shows `status_at`.
 */
function displayAtOf(item: Built): number | null {
  const { spec } = item;
  if (spec.status === 'CLASSIFIED') return spec.surface === 'saas' ? item.row.scan.lastScannedAt : null;
  return spec.statusAt === undefined ? null : nowSeconds() - Math.round(spec.statusAt * DAY);
}

function datastoreRowOf(item: Built): DatastoreRow {
  const { spec } = item;
  const [riskLevel, score] = spec.risk;
  // The vendor sends no level for a datastore without findings; the cell then reads "-".
  const level = riskLevel === 'NONE' ? null : riskLevel;
  const { recordCount, types } = datastoreSensitivesOf(item);
  const agentStatus = String(spec.facet.agent_status);
  return {
    id: spec.id,
    sourceId: sourceIdOf(spec),
    name: spec.name,
    category: spec.category,
    subtype: spec.subtype,
    subtypeLabel: DS_SUBTYPE_LABEL[spec.subtype] ?? DS_PROVIDER_LABEL[spec.subtype] ?? prettify(spec.subtype),
    risk: { score, level, levelLabel: level ? LEVEL_LABEL[level] : null },
    sensitives: { recordCount, typeLabels: types.map(type => CATEGORY_LABEL[type] ?? type) },
    source: {
      provider: spec.provider,
      providerLabel: DS_PROVIDER_LABEL[spec.provider] ?? prettify(spec.provider),
      accountLabel: accountLabelOf(spec),
      region: spec.region,
    },
    scan: { status: spec.status, statusLabel: DS_SCAN_LABEL[spec.status], displayAt: displayAtOf(item) },
    agent: spec.category === 'endpoint'
      ? {
        serialNumber: String(spec.facet.serial_number),
        status: agentStatus,
        statusLabel: AGENT_STATUS_LABEL[agentStatus] ?? null,
        version: String(spec.facet.agent_version),
      }
      : null,
  };
}

const DS_TABS = ['all', 'cloud', 'saas', 'endpoint', 'on_prem'] as const;
type DatastoreTab = typeof DS_TABS[number];

/** The filter dimensions each tab accepts (design 5.3.2), in the order the answer lists them. */
const SHARED_DIMENSIONS = ['integration_id', 'provider', 'region', 'scan_status', 'severity', 'sensitive_type', 'data_element'];
const TAB_DIMENSIONS: Record<DatastoreTab, string[]> = {
  all: SHARED_DIMENSIONS,
  cloud: SHARED_DIMENSIONS,
  on_prem: SHARED_DIMENSIONS,
  saas: [...SHARED_DIMENSIONS, 'selected'],
  endpoint: [...SHARED_DIMENSIONS, 'os', 'agent_status', 'device_type', 'serial_number', 'hostname', 'user'],
};
/** The vendor sends these endpoint options without counts. */
const UNCOUNTED_DIMENSIONS = new Set(['hostname', 'user']);
const ENDPOINT_FACET_DIMENSIONS = new Set(['os', 'agent_status', 'device_type', 'serial_number', 'hostname', 'user']);

const scalarText = (value: unknown): string[] =>
  typeof value === 'string' || typeof value === 'number' || typeof value === 'boolean' ? [String(value)] : [];

/** A row's values for a filter dimension; `sensitive_type` and `data_element` hold several. */
function datastoreValuesOf(item: Built, dimension: string): string[] {
  const { spec } = item;
  if (ENDPOINT_FACET_DIMENSIONS.has(dimension)) return spec.category === 'endpoint' ? scalarText(spec.facet[dimension]) : [];
  switch (dimension) {
    case 'integration_id': return spec.account && INTEGRATION_ID[spec.account] ? [INTEGRATION_ID[spec.account]] : [];
    case 'provider': return [spec.provider];
    case 'region': return spec.region ? [spec.region] : [];
    case 'scan_status': return [spec.status];
    case 'severity': return [spec.risk[0]];
    case 'sensitive_type': return datastoreSensitivesOf(item).types;
    case 'data_element': return datastoreSensitivesOf(item).elements;
    case 'selected': return spec.category === 'saas' ? scalarText(spec.facet.is_selected) : [];
    default: return [];
  }
}

/** The labeler's option rule: an integration reads as the account label of its first row. */
function integrationLabel(id: string): string | null {
  const sample = BUILT.find(item => item.spec.account !== null && INTEGRATION_ID[item.spec.account] === id);
  return sample ? accountLabelOf(sample.spec) : null;
}

/** `DatastoreLabels.optionLabel` (design 5.7). */
function datastoreOptionLabel(dimension: string, value: string): string {
  switch (dimension) {
    case 'integration_id': return integrationLabel(value) ?? value;
    case 'provider': return DS_PROVIDER_LABEL[value] ?? prettify(value);
    case 'scan_status': return DS_SCAN_LABEL[value] ?? prettify(value);
    case 'severity': return value === 'NONE' ? 'No findings' : LEVEL_LABEL[value] ?? prettify(value);
    case 'sensitive_type': return CATEGORY_LABEL[value] ?? prettify(value);
    case 'os': return OS_LABEL[value] ?? prettify(value);
    case 'agent_status': return AGENT_STATUS_LABEL[value] ?? prettify(value);
    case 'device_type': return prettify(value);
    case 'data_element': return value || 'Unclassified';
    default: return value || '-';
  }
}

interface DatastoreQuery {
  tab: DatastoreTab;
  /** Lower-cased; empty for no search. */
  q: string;
  /** Dimension → its values, sent as `<dim>` (Include) or `<dim>_exclude` (Exclude). */
  filters: Map<string, { exclude: boolean; values: string[] }>;
  sort: string;
  page: number;
  pageSize: number;
}

function parseDatastoreQuery(url: string): DatastoreQuery {
  const params = new URL(url, 'http://localhost').searchParams;
  const tab = DS_TABS.find(t => t === params.get('tab')) ?? 'all';
  const filters: DatastoreQuery['filters'] = new Map();
  for (const dimension of TAB_DIMENSIONS[tab]) {
    for (const exclude of [false, true]) {
      const values = [...new Set(params.getAll(exclude ? `${dimension}_exclude` : dimension)
        .flatMap(raw => raw.split(',')).map(value => value.trim()).filter(Boolean))];
      if (values.length > 0) filters.set(dimension, { exclude, values });
    }
  }
  return {
    tab,
    q: (params.get('q') ?? '').trim().toLowerCase(),
    filters,
    sort: params.get('sort') ?? '-severity',
    page: Math.max(1, Number(params.get('page')) || 1),
    pageSize: Math.min(100, Math.max(1, Number(params.get('pageSize')) || 10)),
  };
}

const inTab = (item: Built, tab: DatastoreTab) => tab === 'all' || item.spec.category === tab;

/** Whether a row is on the tab and matches the search and every dimension's selection except `skip`'s. */
function datastoreMatches(item: Built, query: DatastoreQuery, skip?: string): boolean {
  if (!inTab(item, query.tab)) return false;
  if (query.q && !item.spec.name.toLowerCase().includes(query.q)) return false;
  for (const [dimension, selection] of query.filters) {
    if (dimension === skip) continue;
    const values = datastoreValuesOf(item, dimension);
    // Include needs a match, Exclude needs none.
    if (selection.values.some(value => values.includes(value)) === selection.exclude) return false;
  }
  return true;
}

/** `GET /api/dspm/datastores`: the tab, search, Include / Exclude on every dimension, sort and paging. */
export function demoDatastores(url: string): DspmPage<DatastoreRow> {
  const query = parseDatastoreQuery(url);
  const descending = query.sort.startsWith('-');
  const field = descending ? query.sort.slice(1) : query.sort;
  const key = (item: Built): number | string => {
    if (field === 'name') return item.spec.name.toLowerCase();
    if (field === 'sensitive_record_count') return datastoreSensitivesOf(item).recordCount;
    return SEVERITY_RANK[item.spec.risk[0]] ?? 0;
  };
  // Ties by name, A to Z in either direction.
  const items = BUILT.filter(item => datastoreMatches(item, query)).sort((a, b) => {
    const x = key(a);
    const y = key(b);
    if (x !== y) return (x < y ? -1 : 1) * (descending ? -1 : 1);
    return a.spec.name.localeCompare(b.spec.name);
  });
  const start = (query.page - 1) * query.pageSize;
  return {
    data: items.slice(start, start + query.pageSize).map(datastoreRowOf),
    page: query.page,
    pageSize: query.pageSize,
    total: items.length,
    totalPages: Math.ceil(items.length / query.pageSize),
  };
}

/**
 * `GET /api/dspm/datastores/filter-options`. Counts are disjunctive: each dimension counts the rows that
 * match every other dimension's selection, so its own selection never shrinks its own options, and in
 * Exclude mode its excluded values leave its own list, as the vendor's do. Region is listed only on a
 * single tab; endpoint `hostname` and `user` options carry no count.
 */
export function demoDatastoreFilterOptions(url: string): DatastoreFilterOptions {
  const query = parseDatastoreQuery(url);
  const dimensions = TAB_DIMENSIONS[query.tab]
    .filter(key => key !== 'region' || query.tab !== 'all')
    .map((key): DatastoreFilterDimension => {
      const counts = new Map<string, number>();
      for (const item of BUILT) {
        if (!datastoreMatches(item, query, key)) continue;
        for (const value of new Set(datastoreValuesOf(item, key))) counts.set(value, (counts.get(value) ?? 0) + 1);
      }
      const own = query.filters.get(key);
      const dropped = new Set(own?.exclude ? own.values : []);
      const options = [...counts.entries()]
        .filter(([value]) => !dropped.has(value))
        .sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0]))
        .map(([value, count]) => ({
          value,
          label: datastoreOptionLabel(key, value),
          count: UNCOUNTED_DIMENSIONS.has(key) ? null : count,
        }));
      return { key, options, hasMore: false };
    });
  return { dimensions };
}

/**
 * 14 daily readings dated the 14 local days ending today: reading i is
 * `start + round((end − start) × i / 13)`, so the first is `start` and the last exactly `end`, the card's
 * value, as in the vendor's data.
 */
export function demoTrend(start: number, end: number): DspmTrendPoint[] {
  const today = new Date();
  return Array.from({ length: 14 }, (_, i) => {
    const day = new Date(today.getFullYear(), today.getMonth(), today.getDate() - 13 + i);
    const date = `${day.getFullYear()}-${String(day.getMonth() + 1).padStart(2, '0')}-${String(day.getDate()).padStart(2, '0')}`;
    return { date, value: start + Math.round(((end - start) * i) / 13) };
  });
}

/** Where a tab's records and asset trends start (design 7.2). A tab not listed is flat, so it shows no badge. */
const TREND_STARTS: Partial<Record<DatastoreTab, (records: number, assets: number) => [number, number]>> = {
  all: (records, assets) => [records - 2, assets - 6],                   // "+1%", "+6"
  cloud: (records, assets) => [Math.ceil(records / 1.1), assets - 4],    // "+10%", "+4"
  endpoint: (records, assets) => [Math.ceil(records / 1.25), assets],    // "+25%", flat
};

/** `GET /api/dspm/datastores/kpis`: every value derived from the tab's rows; filters never apply. */
export function demoDatastoreKpis(url: string): DatastoreKpis {
  const { tab } = parseDatastoreQuery(url);
  const rows = BUILT.filter(item => inTab(item, tab));
  const records = rows.reduce((sum, item) => sum + datastoreSensitivesOf(item).recordCount, 0);
  const assets = rows.length;
  const [recordsStart, assetsStart] = TREND_STARTS[tab]?.(records, assets) ?? [records, assets];
  const withStatus = (status: Status) => rows.filter(item => item.spec.status === status).length;
  return {
    tab,
    tabCounts: Object.fromEntries(DS_TABS.map(t => [t, BUILT.filter(item => inTab(item, t)).length])),
    totalSensitiveRecords: { value: records, trend: demoTrend(recordsStart, records) },
    assetCount: { value: assets, trend: demoTrend(assetsStart, assets) },
    classified: withStatus('CLASSIFIED'),
    // No demo datastore has a scan scheduled.
    scheduled: 0,
    failed: withStatus('FAILED'),
    stopped: withStatus('STOPPED'),
    criticalHigh: rows.filter(item => item.spec.risk[0] === 'CRITICAL' || item.spec.risk[0] === 'HIGH').length,
  };
}

/** What `xamlensDrawerMockData.ts` derives a cloud or on-prem datastore's drawer data from (design 7.3). */
export interface DemoDatastoreSource {
  /** The spec id (`demo-ds-01`), which names the showcases. */
  id: string;
  name: string;
  category: 'cloud' | 'on_prem';
  provider: string;
  status: string;
  /** Epoch seconds, or null when never scanned. */
  lastScannedAt: number | null;
  /** The spec's `HIGH_SENSITIVITY`-style level, which a Scan History run reports. */
  sensitivityLevel: string | null;
  facet: Record<string, unknown>;
  /** The files, sensitive ones first, as the old asset drawer lists them. */
  objects: DspmObject[];
  /** Entities summed over the files, count descending. */
  entities: DspmEntityCount[];
  /** Category codes in the row's order, and their labels. */
  types: string[];
  typeLabels: string[];
  recordCount: number;
}

/** The cloud or on-prem datastore with this 24-hex source id; null for SaaS, endpoint and unknown ids. */
export function demoDatastoreSource(sourceId: string): DemoDatastoreSource | null {
  const item = BUILT.find(b => b.row.sourceId === sourceId);
  if (!item) return null;
  const { spec, row } = item;
  if (spec.category !== 'cloud' && spec.category !== 'on_prem') return null;
  const detail = item.detail();
  return {
    id: spec.id,
    name: spec.name,
    category: spec.category,
    provider: spec.provider,
    status: spec.status,
    lastScannedAt: row.scan.lastScannedAt,
    sensitivityLevel: spec.sensLevel,
    facet: spec.facet,
    objects: detail.objects,
    entities: detail.entityBreakdown,
    types: row.sensitives.types,
    typeLabels: row.sensitives.typeLabels,
    recordCount: row.sensitives.recordCount,
  };
}

/** A spec file with its findings labelled, as the SaaS and Endpoint drawer data reads it. */
export interface DemoSourceFile {
  name: string;
  path: string | null;
  /** Lower case, or null without one (`.env`). */
  extension: string | null;
  findings: Array<{ entityType: string; category: string | null; categoryLabel: string | null; count: number }>;
  /** The findings summed. */
  records: number;
}

const sourceFileOf = (file: FileSpec): DemoSourceFile => {
  const findings = file.findings.map(([entityType, count]) => {
    const category = ENTITY_CATEGORY[entityType] ?? null;
    return { entityType, category, categoryLabel: category ? CATEGORY_LABEL[category] : null, count };
  });
  return {
    name: file.name, path: file.path, extension: file.ext, findings,
    records: findings.reduce((sum, finding) => sum + finding.count, 0),
  };
};

/** What `xamlensSaasDrawerMockData.ts` derives a SaaS row's drawer data from (design 7.2). */
export interface DemoSaasSource {
  /** The spec id (`demo-saas-03`) and its number, which the file ids carry. */
  id: string;
  number: number;
  sourceId: string;
  /** The person or mailbox. */
  name: string;
  email: string;
  drive: 'GMAIL' | 'GOOGLE_WORKSPACE';
  status: Status;
  /** Epoch seconds, or null when never scanned. */
  lastScannedAt: number | null;
  facet: Record<string, unknown>;
  /** The spec files in spec order: every file with findings. */
  files: DemoSourceFile[];
  /** The list row's record count: the files' findings summed. */
  recordCount: number;
}

/** The SaaS row with this 24-hex source id; null for any other row and unknown ids. */
export function demoSaasSource(sourceId: string): DemoSaasSource | null {
  const item = BUILT.find(b => b.row.sourceId === sourceId);
  if (!item || item.spec.category !== 'saas') return null;
  const { spec, row } = item;
  const seed = SAAS_SEEDS.find(s => s.id === spec.id)!;
  return {
    id: spec.id,
    number: Number(spec.id.replace(/\D/g, '')),
    sourceId,
    name: spec.name,
    email: seed.email,
    drive: seed.drive,
    status: spec.status,
    lastScannedAt: row.scan.lastScannedAt,
    facet: spec.facet,
    files: spec.files.map(sourceFileOf),
    recordCount: datastoreSensitivesOf(item).recordCount,
  };
}

/** What `xamlensEndpointDrawerMockData.ts` derives an endpoint row's drawer data from (design 7.2). */
export interface DemoEndpointSource {
  id: string;
  sourceId: string;
  name: string;
  os: 'MAC' | 'WINDOWS' | 'LINUX';
  status: Status;
  online: boolean;
  /** The list row's risk level; NONE without findings. */
  riskLevel: DspmSeverity;
  user: string;
  facet: Record<string, unknown>;
  /** The spec files in spec order. */
  files: DemoSourceFile[];
  /** Every file on the device, the spec files included. */
  objectTotal: number;
  /** The clean file at position i, for the File View's pages after the spec files. */
  filler: (i: number) => DemoSourceFile;
  recordCount: number;
}

/** The endpoint row with this 24-hex source id; null for any other row and unknown ids. */
export function demoEndpointSource(sourceId: string): DemoEndpointSource | null {
  const item = BUILT.find(b => b.row.sourceId === sourceId);
  if (!item || item.spec.category !== 'endpoint') return null;
  const { spec } = item;
  const seed = DEVICE_SEEDS.find(s => s.id === spec.id)!;
  return {
    id: spec.id,
    sourceId,
    name: spec.name,
    os: seed.os,
    status: spec.status,
    online: seed.online,
    riskLevel: spec.risk[0],
    user: seed.user,
    facet: spec.facet,
    files: spec.files.map(sourceFileOf),
    objectTotal: spec.objectTotal,
    filler: i => sourceFileOf(spec.filler(i)),
    recordCount: datastoreSensitivesOf(item).recordCount,
  };
}
