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
 */

import type {
  DspmAssetDetail, DspmAssetRow, DspmEntityCount, DspmFilterOption, DspmFilters, DspmObject, DspmPage,
  DspmSeverity, DspmStatus,
} from '../types/dspm';
import type { DspmSurface } from '../hooks/useDspmData';

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
  GOOGLE_WORKSPACE: 'Google Workspace', ENDPOINT_AGENT: 'Endpoint Agent',
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
    region: 'ap-south-1', status: 'CLASSIFIED', scanned: 1, risk: ['CRITICAL', 9.12, 14], sensLevel: 'HIGH_SENSITIVITY',
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
    region: 'ap-south-1', status: 'STOPPED', scanned: 6, risk: ['HIGH', 7.86, 4], sensLevel: 'HIGH_SENSITIVITY',
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
    region: 'us-east-1', status: 'FAILED', scanned: null, risk: ['NONE', null, null], sensLevel: null,
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
    region: 'asia-south1', status: 'SCANNING', scanned: null, risk: ['NONE', null, null], sensLevel: null,
    objectTotal: 0, externalId: 'xammer-ml-training-data', lifecycle: 'NOT_APPLICABLE',
    facet: { cloud_account_id: 'xammer-analytics', gcp_project_id: 'xammer-analytics', region: 'asia-south1', storage_size_bytes: 2.6 * TB, able_to_scan: true },
    files: [], filler: pathFiller('gs://xammer-ml-training-data/datasets', 'parquet'),
  },
  {
    id: 'demo-ds-20', surface: 'cloud', category: 'cloud', resourceType: 'DATASTORE',
    name: 'costexports', subtype: 'AZURE_BLOB', provider: 'AZURE_BLOB', account: 'demo-int-azure',
    region: 'centralindia', status: 'NOT_SCANNED', scanned: null, risk: ['NONE', null, null], sensLevel: null,
    objectTotal: 0, externalId: 'costexports', lifecycle: 'NOT_APPLICABLE',
    facet: { cloud_account_id: CLOUD_ACCOUNT['demo-int-azure'], region: 'centralindia', storage_size_bytes: 3.4 * GB, able_to_scan: true },
    files: [], filler: pathFiller('https://xammerfinance.blob.core.windows.net/costexports', 'csv'),
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
    region: 'us-east-1', status: 'FAILED', scanned: null, risk: ['NONE', null, null], sensLevel: null,
    objectTotal: 0, externalId: 'arn:aws:s3:::xammer-data-lake', lifecycle: 'NOT_APPLICABLE',
    facet: { ...s3('xammer-data-lake', 'demo-int-aws-prod', 'us-east-1'), storage_size_bytes: 9.8 * TB },
    files: [], filler: s3Filler('xammer-data-lake', 'curated'),
  },
];

// ── SaaS ──

interface SaasSeed {
  id: string; person: string; email: string; drive: 'GMAIL' | 'GOOGLE_WORKSPACE'; status: Status; scanned: number | null;
  risk: DspmSeverity; inventories: number; sizeBytes: number; unsupported: number; files: FileSpec[];
}

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
      mail('Welcome to XamOps - your account credentials', [['EMAIL ADDRESS', 1], ['HASHED SECRET KEY', 1]]),
      mail('Delivery Status Notification (Failure)', [['EMAIL ADDRESS', 2]]),
      mail('Password reset requested', [['EMAIL ADDRESS', 1], ['JWT TOKEN', 1]]),
      mail('Invoice INV-2026-04-001 is ready', [['PERSON NAME', 1], ['ADDRESS', 1], ['EMAIL ADDRESS', 1]]),
    ] },
  { id: 'demo-saas-03', person: 'Priya Nair', email: 'priya@xammer.in', drive: 'GMAIL', status: 'CLASSIFIED', scanned: 1,
    risk: 'HIGH', inventories: 2672, sizeBytes: 0, unsupported: 96, files: [
      mail('Re: Payroll inputs for September', [['BANK ACCOUNT NUMBER', 14], ['PAN NUMBER', 14], ['PERSON NAME', 14]]),
      mail('Fwd: Candidate documents', [['AADHAAR NUMBER', 1], ['DATE OF BIRTH', 1], ['ADDRESS', 1]]),
      mail('Customer escalation - refund', [['CREDIT CARD NUMBER', 1], ['PHONE NUMBER', 2], ['EMAIL ADDRESS', 3]]),
    ] },
  { id: 'demo-saas-04', person: 'Priya Nair', email: 'priya@xammer.in', drive: 'GOOGLE_WORKSPACE', status: 'CLASSIFIED', scanned: 2,
    risk: 'MEDIUM', inventories: 312, sizeBytes: 8.6 * GB, unsupported: 18, files: [
      doc('Team contacts.xlsx', [['PERSON NAME', 84], ['PHONE NUMBER', 84], ['EMAIL ADDRESS', 84]]),
      doc('Offer letter template.docx', [['PERSON NAME', 1], ['ADDRESS', 1]]),
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
    risk: 'NONE', inventories: 96, sizeBytes: 2.4 * GB, unsupported: 11, files: [] },
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
    name: seed.person, subtype: 'GOOGLE_WORKSPACE', provider: 'GOOGLE_WORKSPACE', account: 'demo-int-gws', region: null,
    status: seed.status, scanned: seed.scanned, risk: [seed.risk, null, null], sensLevel: null,
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
  mac: string; arch: string; memoryGb: number; files: FileSpec[]; home: string;
}

const DEVICE_SEEDS: DeviceSeed[] = [
  { id: 'demo-ep-01', name: 'Priya’s MacBook Pro', os: 'MAC', osVersion: '15.1', user: 'priya', serial: 'C02XD4DEMO01',
    status: 'CLASSIFIED', online: true, pingHoursAgo: 0.2, risk: 'HIGH', objectTotal: 4210, ip: '203.0.113.24',
    mac: '02:1a:4b:3c:5d:01', arch: 'arm64', memoryGb: 16, home: '/Users/priya', files: [
      { name: 'customer_export_sept.csv', path: '/Users/priya/Downloads/customer_export_sept.csv', ext: 'csv',
        findings: [['EMAIL ADDRESS', 1840], ['PERSON NAME', 1840], ['PHONE NUMBER', 1602]] },
      { name: 'payroll_draft.xlsx', path: '/Users/priya/Desktop/payroll_draft.xlsx', ext: 'xlsx',
        findings: [['BANK ACCOUNT NUMBER', 58], ['PAN NUMBER', 58]] },
      { name: 'credentials.bak', path: '/Users/priya/Documents/aws/credentials.bak', ext: 'bak', findings: [['ACCESS KEY AWS', 2]] },
    ] },
  { id: 'demo-ep-02', name: 'FIN-LAPTOP-07', os: 'WINDOWS', osVersion: '11 Pro 23H2', user: 'vijay', serial: '8TG5DEMO07',
    status: 'CLASSIFIED', online: true, pingHoursAgo: 1, risk: 'CRITICAL', objectTotal: 6932, ip: '203.0.113.87',
    mac: '02:1a:4b:3c:5d:07', arch: 'amd64', memoryGb: 16, home: 'C:\\Users\\vijay', files: [
      { name: 'vendor_bank_details.xlsx', path: 'C:\\Users\\vijay\\Documents\\Finance\\vendor_bank_details.xlsx', ext: 'xlsx',
        findings: [['BANK ACCOUNT NUMBER', 312], ['IFSC CODE', 312], ['PERSON NAME', 312]] },
      { name: 'card_disputes_q3.csv', path: 'C:\\Users\\vijay\\Downloads\\card_disputes_q3.csv', ext: 'csv',
        findings: [['CREDIT CARD NUMBER', 86], ['PERSON NAME', 86]] },
    ] },
  { id: 'demo-ep-03', name: 'Akshay’s MacBook Pro', os: 'MAC', osVersion: '14.6.1', user: 'akshay', serial: 'C02XD4DEMO03',
    status: 'CLASSIFIED', online: false, pingHoursAgo: 30, risk: 'MEDIUM', objectTotal: 2943, ip: '198.51.100.12',
    mac: '02:1a:4b:3c:5d:03', arch: 'arm64', memoryGb: 32, home: '/Users/akshay', files: [
      { name: '.env', path: '/Users/akshay/code/billing-service/.env', ext: null, findings: [['HASHED SECRET KEY', 6], ['JWT TOKEN', 2]] },
      { name: 'leads.csv', path: '/Users/akshay/Downloads/leads.csv', ext: 'csv', findings: [['EMAIL ADDRESS', 420], ['PHONE NUMBER', 388]] },
      { name: 'people-list.json', path: '/Users/akshay/code/xamops-service/src/test/resources/people-list.json', ext: 'json',
        findings: [['EMAIL ADDRESS', 2], ['PERSON NAME', 1]] },
    ] },
  { id: 'demo-ep-04', name: 'build-runner-01', os: 'LINUX', osVersion: 'Ubuntu 22.04', user: 'ci', serial: 'VM-DEMO-0001',
    status: 'CLASSIFIED', online: true, pingHoursAgo: 0.1, risk: 'LOW', objectTotal: 18240, ip: '198.51.100.40',
    mac: '02:1a:4b:3c:5d:04', arch: 'amd64', memoryGb: 8, home: '/home/ci', files: [
      { name: 'config.json', path: '/home/ci/.docker/config.json', ext: 'json', findings: [['HASHED SECRET KEY', 1]] },
    ] },
  { id: 'demo-ep-05', name: 'HR-DESKTOP-02', os: 'WINDOWS', osVersion: '11 Pro 23H2', user: 'meera', serial: '5KD2DEMO02',
    status: 'SCANNING', online: true, pingHoursAgo: 0.5, risk: 'NONE', objectTotal: 0, ip: '203.0.113.141',
    mac: '02:1a:4b:3c:5d:05', arch: 'amd64', memoryGb: 8, home: 'C:\\Users\\meera', files: [] },
  { id: 'demo-ep-06', name: 'Sneha’s MacBook Air', os: 'MAC', osVersion: '15.0.1', user: 'sneha', serial: 'C02XD4DEMO06',
    status: 'NOT_SCANNED', online: false, pingHoursAgo: 96, risk: 'NONE', objectTotal: 0, ip: '198.51.100.77',
    mac: '02:1a:4b:3c:5d:06', arch: 'arm64', memoryGb: 8, home: '/Users/sneha', files: [] },
];

const ENDPOINTS: AssetSpec[] = DEVICE_SEEDS.map(seed => ({
  id: seed.id, surface: 'endpoint', category: 'endpoint', resourceType: 'ENDPOINT_HOST',
  name: seed.name, subtype: seed.os, provider: 'ENDPOINT_AGENT', account: null, region: null,
  status: seed.status, scanned: null, risk: [seed.risk, null, null], sensLevel: null,
  objectTotal: seed.objectTotal, externalId: seed.serial, lifecycle: 'ACTIVE',
  facet: {
    agent_status: seed.online ? 'ONLINE' : 'OFFLINE', agent_version: '2.1.0', architecture: seed.arch,
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
    sourceId: `66a1f0${spec.id.replace(/\D/g, '').padStart(6, '0')}c9e2`,
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
