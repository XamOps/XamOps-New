/**
 * XamLens datastore drawer demo data (ROLE_XAMOPS), design 7.3.
 *
 * Answers the drawer's resources and the Data Flow routes for every cloud and on-prem demo datastore, in
 * the shapes `types/dspmDatastores.ts` defines. Demo mode returns these DTOs directly, so values are
 * written as the backend sends them after its mappers (labels resolved, "Sse S3" already prettified).
 *
 * - Three showcases are written by hand: the stopped S3 bucket `xammer-prod-customer-exports`, the
 *   never-scanned Azure container `ledger-exports-weu` (every empty state) and the on-prem database
 *   `crm_production` (Table View and Data Flow).
 * - Every other row's data is derived from its spec in `xamlensMockData.ts`.
 *
 * For every row the list's record count, the sum of the drawer's entity counts and the overview total
 * are one number, and the list's type labels are the overview's group labels in order
 * (`demoRouter.test.ts` checks it). A never-scanned row is the exception: its total is null.
 *
 * Every name, path, role, e-mail and sample is invented: `example.com` mailboxes, `demo-role-NNN` roles
 * and `<<Jane Doe>>`-style samples.
 */

import type {
  DataFlowColumn, DataFlowColumnPage, DataFlowDatabase, DataFlowDatabases, DataFlowSchemas, DataFlowTable,
  DataFlowTablePage, DatastoreAccessGraph, DatastoreAccessGroup, DatastoreFilePage, DatastoreFileRow,
  DatastoreFileSummary, DatastoreMisconfiguration, DatastoreOccurrence, DatastoreOccurrencePage, DatastoreOverview,
  DatastoreProperty, DatastoreRisks, DatastoreScanHistory, DatastoreScanProgress, DatastoreScanRun,
  DatastoreSecurityConfiguration, DatastoreSensitiveEntity, DatastoreSensitiveRecords, DatastoreSensitives,
  DatastoreSensitiveTable,
} from '../types/dspmDatastores';
import type { DspmObject } from '../types/dspm';
// Circular: `xamlensMockData.ts` reads `SHOWCASE_SENSITIVES` from here. Each side reads the other only
// inside functions, never while the modules load, so either may load first.
import { demoDatastoreSource, type DemoDatastoreSource } from './xamlensMockData';

// ─── Vocabulary ──────────────────────────────────────────────────────────────

const DAY = 24 * 60 * 60;
const daysAgo = (days: number) => Math.floor(Date.now() / 1000) - Math.round(days * DAY);

const PERSONAL = 'Personal';
const IT_SECURITY = 'IT & Security';

/** The showcases' detectors and their labels. */
const ENTITY_LABEL: Record<string, string> = {
  'PERSON NAME': PERSONAL, 'EMAIL ADDRESS': PERSONAL, 'PHONE NUMBER': PERSONAL, 'ADDRESS': PERSONAL,
  'ACCESS KEY AWS': IT_SECURITY, 'SHA KEY': IT_SECURITY, 'HASHED SECRET KEY': IT_SECURITY,
};

/** The vendor's blank entity: kept last, shown as "-" with no chip, never expandable. */
const BLANK_ENTITY: DatastoreSensitiveEntity = { name: '', count: 0, categoryLabel: null, blank: true };

/** The 17 file types, in the order the backend lists zero counts: by label, case-insensitively. */
const FILE_TYPES: ReadonlyArray<[key: string, label: string]> = [
  ['autocad', 'Autocad'], ['code_files', 'Code'], ['compressed_files', 'Compressed'], ['contact_files', 'Contact'],
  ['databases_files', 'Databases'], ['documents', 'Documents'], ['email', 'Email'], ['image_files', 'Image'],
  ['log_files', 'Log'], ['mailboxes', 'Mailboxes'], ['unknown_files', 'Other'], ['parquet_files', 'Parquet'],
  ['pdf_files', 'PDF'], ['presentations', 'Presentations'], ['system_generated_files', 'System Generated'],
  ['web_markup', 'Web Markup'], ['xlsx_files', 'XLSX'],
];

/** Non-zero types by count, descending, then the zeros; ties stay in label order (design 5.6.4). */
function fileSummary(totalFiles: number, counts: Readonly<Record<string, number>>): DatastoreFileSummary {
  const types = FILE_TYPES.map(([key, label]) => ({ key, label, count: counts[key] ?? 0 }));
  return { totalFiles, types: types.sort((a, b) => b.count - a.count) };
}

const SECURITY_SETTINGS: ReadonlyArray<[key: string, label: string]> = [
  ['bucket_versioning', 'Bucket Versioning'], ['default_encryption', 'Default Encryption'],
  ['public_accessibility', 'Public Accessibility'], ['logging', 'Logging'], ['ssl_enforced', 'SSL Enforced'],
];

/** The five settings, with values as the backend sends them ("Enabled", "Disabled", "Sse S3"). */
const securityConfigurations = (values: readonly string[]): DatastoreSecurityConfiguration[] =>
  SECURITY_SETTINGS.map(([key, label], i) => ({ key, label, value: values[i] }));

const textProperty = (key: string, label: string, text: string): DatastoreProperty => ({ key, label, text, bytes: null });
const sizeProperty = (bytes: number): DatastoreProperty =>
  ({ key: 'datastore_size', label: 'Datastore Size', text: null, bytes: Math.round(bytes) });

/** Text after the last "/", trailing slashes ignored (`DatastoreDrawerMapper.basename`). */
function basename(path: string): string {
  const trimmed = path.replace(/\/+$/, '');
  return trimmed.slice(trimmed.lastIndexOf('/') + 1) || path;
}

const byLabel = (a: string, b: string) => a.toLowerCase().localeCompare(b.toLowerCase());
const sortedLabels = (labels: readonly string[]) => [...new Set(labels)].sort(byLabel);

/** Invented sample values per detector. The drawer renders them raw (decision D1). */
const SAMPLES: Record<string, readonly string[]> = {
  'PERSON NAME': [
    '<<Jane Doe>>', '<<John Doe>>', '<<Richard Roe>>', '<<Mary Major>>',
    '<<Alex Sample>>', '<<Sam Example>>', '<<Pat Placeholder>>', '<<Chris Test>>',
  ],
  'EMAIL ADDRESS': ['jane.doe@example.com', 'john.doe@example.com', 'richard.roe@example.com', 'mary.major@example.com'],
  'PHONE NUMBER': ['+1 555 0100', '+1 555 0142', '+1 555 0177'],
  'ADDRESS': ['<<12 Example Street>>', '<<48 Sample Road>>', '<<7 Placeholder Lane>>'],
  'ACCESS KEY AWS': ['<<AKIA…DEMO1>>', '<<AKIA…DEMO2>>'],
  'SHA KEY': ['<<9f2c…e41a>>', '<<5b7d…0c93>>', '<<e3a1…77f2>>', '<<0d4e…a5b8>>', '<<c81f…3e6d>>'],
  'HASHED SECRET KEY': ['<<$2b$12$…demo1>>', '<<$2b$12$…demo2>>'],
};

/** `n` samples of a detector, starting at `start` in its list; an unlisted detector gets numbered stand-ins. */
function samples(entity: string, start: number, n: number): string[] {
  const pool = SAMPLES[entity] ?? [`<<${entity.toLowerCase()} 1>>`, `<<${entity.toLowerCase()} 2>>`];
  return Array.from({ length: n }, (_, k) => pool[(start + k) % pool.length]);
}

const fileOccurrence = (url: string, values: string[]): DatastoreOccurrence => ({
  fileName: basename(url), url, databaseName: null, schemaName: null, tableName: null, columnName: null,
  falsePositive: false, samples: values,
});

const columnOccurrence = (database: string, schema: string, table: string, column: string, values: string[]): DatastoreOccurrence => ({
  fileName: null, url: null, databaseName: database, schemaName: schema, tableName: table, columnName: column,
  falsePositive: false, samples: values,
});

/** A permission set's principals of one type; the id is the backend's (`READ,WRITE|ROLE`, `ACTION_ONLY|ROLE`). */
function accessGroup(permissions: string[], principalType: string, principals: string[], actionOnly = false): DatastoreAccessGroup {
  return {
    id: `${actionOnly ? 'ACTION_ONLY' : permissions.join(',')}|${principalType}`,
    permissions,
    principalType,
    actionOnly,
    count: principals.length,
    principals: [...principals].sort(byLabel),
  };
}

/** Groups in display order (design 5.6.8): sets by size, types by count. */
const accessGraph = (rootName: string, rootType: string, groups: DatastoreAccessGroup[]): DatastoreAccessGraph => ({
  rootName, rootType, principalCount: groups.reduce((sum, group) => sum + group.count, 0), groups,
});

/** `demo-role-001`, `demo-role-002`, … */
const numbered = (prefix: string, from: number, n: number) =>
  Array.from({ length: n }, (_, i) => `${prefix}-${String(from + i).padStart(3, '0')}`);

const SENSITIVITY_LABEL: Record<string, string> = {
  CRITICAL_SENSITIVITY: 'Critical', HIGH_SENSITIVITY: 'High', MEDIUM_SENSITIVITY: 'Medium', LOW_SENSITIVITY: 'Low',
};

const OPERATOR = 'maria.lopez@example.com';

/** One scan run as the backend maps it. */
function scanRun(startTime: number | null, records: number, sensitivityLevel: string | null, status: string, statusLabel: string): DatastoreScanRun {
  return {
    executionNumber: 1, startTime, sensitiveRecords: records, sensitivityLevel,
    severityLabel: SENSITIVITY_LABEL[sensitivityLevel ?? ''] ?? null, triggeredBy: OPERATOR, status, statusLabel,
  };
}

/** Everything one datastore's drawer reads. */
interface DrawerData {
  overview: DatastoreOverview;
  progress: number | null;
  sensitives: DatastoreSensitives;
  /** The File View rows, in API key order. */
  files: DatastoreFileRow[];
  /** Every occurrence row of an entity, in API order; none for a name the datastore does not hold. */
  occurrences: (entityName: string) => DatastoreOccurrence[];
  risks: DatastoreMisconfiguration[];
  accessGraph: DatastoreAccessGraph;
  runs: DatastoreScanRun[];
  databases: DataFlowDatabase[];
}

/** A label group of an overview: the category code, its label and its entities in API order. */
type GroupSeed = [code: string, label: string, entities: Array<[name: string, count: number]>];

const recordsOf = (groups: GroupSeed[]): DatastoreSensitiveRecords => ({
  total: groups.reduce((sum, [, , entities]) => sum + entities.reduce((s, [, count]) => s + count, 0), 0),
  groups: groups.map(([code, label, entities]) => ({ code, label, entities: entities.map(([name, count]) => ({ name, count })) })),
});

/** The groups' entities as the API lists them, count descending, then the blank entity. */
const entitiesOf = (groups: GroupSeed[]): DatastoreSensitiveEntity[] => [
  ...groups
    .flatMap(([, label, entities]) => entities.map(([name, count]) => ({ name, count, categoryLabel: label, blank: false })))
    .sort((a, b) => b.count - a.count),
  BLANK_ENTITY,
];

// ─── Showcase 1: the stopped S3 bucket ───────────────────────────────────────

const S3_NAME = 'xammer-prod-customer-exports';
const S3_ROOT = `s3://${S3_NAME}`;

const S3_GROUPS: GroupSeed[] = [
  ['PII', PERSONAL, [['PERSON NAME', 1559], ['EMAIL ADDRESS', 412], ['ADDRESS', 95], ['PHONE NUMBER', 88]]],
  ['SECRETS', IT_SECURITY, [['ACCESS KEY AWS', 2]]],
];

/**
 * The bucket's File View in API key order: 15 files whose findings add up to the entity totals, and 9
 * files whose only entity is the blank one ("0 · Personal"), one of them a folder key ending in "/".
 */
const S3_FILES: ReadonlyArray<[path: string, findings: Array<[entity: string, count: number]>]> = [
  ['exports/2026/09/customers_2026_09.csv', [['PERSON NAME', 410], ['EMAIL ADDRESS', 120], ['PHONE NUMBER', 24]]],
  ['exports/2026/08/customers_2026_08.csv', [['PERSON NAME', 388], ['EMAIL ADDRESS', 104], ['PHONE NUMBER', 21]]],
  ['exports/2026/06/customers_2026_06.csv', [['PERSON NAME', 26], ['EMAIL ADDRESS', 9]]],
  ['exports/legacy/', []],
  ['exports/2026/07/customers_2026_07.csv', [['PERSON NAME', 301], ['EMAIL ADDRESS', 86], ['PHONE NUMBER', 18]]],
  ['archive/2025/statements/statement_2025_01.csv', []],
  ['exports/crm/crm_contacts.json', [['PERSON NAME', 152], ['EMAIL ADDRESS', 49], ['ADDRESS', 38]]],
  ['kyc/2026/address_proofs_batch_14.pdf', [['PERSON NAME', 96], ['ADDRESS', 41]]],
  ['archive/2025/statements/statement_2025_02.csv', []],
  ['support/support_escalations.csv', [['PERSON NAME', 64], ['EMAIL ADDRESS', 27], ['PHONE NUMBER', 12]]],
  ['marketing/webinar_signups.xlsx', [['PERSON NAME', 41], ['EMAIL ADDRESS', 13], ['PHONE NUMBER', 7]]],
  ['archive/2025/statements/statement_2025_03.csv', []],
  ['partners/reseller_contacts.docx', [['PERSON NAME', 29], ['ADDRESS', 16], ['PHONE NUMBER', 6]]],
  ['invoices/2026/q3/invoice_batch_0912.pdf', [['PERSON NAME', 22]]],
  ['archive/2025/statements/statement_2025_04.csv', []],
  ['feedback/nps_responses_sep.csv', [['PERSON NAME', 14], ['EMAIL ADDRESS', 4]]],
  ['config/deploy/.env.backup', [['ACCESS KEY AWS', 1]]],
  ['archive/2025/statements/statement_2025_05.csv', []],
  ['support/refund_requests_q3.csv', [['PERSON NAME', 9]]],
  ['archive/2025/statements/statement_2025_06.csv', []],
  ['kyc/2026/vendor_kyc_notes.txt', [['PERSON NAME', 7]]],
  ['ci/terraform/prod.tfvars.bak', [['ACCESS KEY AWS', 1]]],
  ['archive/2025/statements/statement_2025_07.csv', []],
  ['archive/2025/statements/statement_2025_08.csv', []],
];

/** PERSON NAME occurrence rows per file holding it, in file order: 72 rows, 8 pages of 10. */
const S3_PERSON_ROWS = [15, 14, 2, 11, 8, 6, 5, 3, 3, 2, 1, 1, 1];

function s3FileRows(): DatastoreFileRow[] {
  return S3_FILES.map(([relative, findings]) => {
    const path = `${S3_ROOT}/${relative}`;
    if (findings.length === 0) {
      return { path, name: basename(path), count: 0, labels: [PERSONAL], entities: [{ name: '', label: PERSONAL }] };
    }
    return {
      path,
      name: basename(path),
      count: findings.reduce((sum, [, count]) => sum + count, 0),
      labels: sortedLabels(findings.map(([entity]) => ENTITY_LABEL[entity])),
      entities: findings.map(([entity]) => ({ name: entity, label: ENTITY_LABEL[entity] })),
    };
  });
}

function s3Occurrences(entityName: string): DatastoreOccurrence[] {
  const urls = S3_FILES
    .filter(([, findings]) => findings.some(([entity]) => entity === entityName))
    .map(([relative]) => `${S3_ROOT}/${relative}`);
  if (entityName !== 'PERSON NAME') return urls.map((url, i) => fileOccurrence(url, samples(entityName, i, i % 3 === 0 ? 3 : 1)));
  // A large file's occurrences arrive in several chunks. Every fourth row holds five samples ("+4"),
  // and a few hold none.
  return urls
    .flatMap((url, i) => Array.from({ length: S3_PERSON_ROWS[i] }, () => url))
    .map((url, i) => fileOccurrence(url, samples(entityName, i, i % 4 === 0 ? 5 : i % 6 === 3 ? 0 : 1)));
}

/** An Upwind finding whose markdown uses every construct the panel renders. */
function upwindFinding(
  title: string,
  severity: 'HIGH' | 'MEDIUM',
  text: { heading: string; intro: string; impact: string[]; config: string; steps: string[]; link: [label: string, href: string]; cli: string },
): DatastoreMisconfiguration {
  return {
    title,
    source: 'UPWIND',
    severity,
    severityLabel: severity === 'HIGH' ? 'High' : 'Medium',
    description: [
      `### ${text.heading}`,
      '',
      text.intro,
      '',
      'Until this is fixed:',
      '',
      ...text.impact.map(line => `- ${line}`),
      '',
      'Current configuration:',
      '',
      '```json',
      text.config,
      '```',
      '',
      `See [${text.link[0]}](${text.link[1]}).`,
    ].join('\n'),
    remediation: text.steps.map((step, i) => `${i + 1}. ${step}`).join('\n'),
    cli: text.cli,
  };
}

const S3_RISKS: DatastoreMisconfiguration[] = [
  upwindFinding('Bucket access logging is turned off', 'MEDIUM', {
    heading: 'Server access logging is off',
    intro: 'Amazon S3 is not writing **server access logs** for `<bucket_name>`, so reads of its exports leave no audit trail.',
    impact: ['Who downloaded an export cannot be traced.', 'Unusual read volumes go unnoticed.', 'Audit evidence for this data is incomplete.'],
    config: '{\n  "LoggingEnabled": null\n}',
    steps: [
      'Pick a **separate** bucket to receive the logs.',
      'Allow the S3 logging service to write to it.',
      'Turn on server access logging for `<bucket_name>` with the command below.',
    ],
    link: ['Logging requests with server access logging', 'https://docs.aws.amazon.com/AmazonS3/latest/userguide/ServerLogs.html'],
    cli: 'aws s3api put-bucket-logging --bucket <SOURCE_BUCKET> --bucket-logging-status '
      + '\'{"LoggingEnabled":{"TargetBucket":"<LOG_BUCKET>","TargetPrefix":"s3-access/"}}\'',
  }),
  upwindFinding('S3 bucket uses S3-managed encryption keys', 'HIGH', {
    heading: 'Objects are encrypted with S3-managed keys',
    intro: '`<bucket_name>` encrypts new objects with **SSE-S3**, so access to the key cannot be limited apart from access to the bucket.',
    impact: ['Anyone who can read the bucket can read every object.', 'Key use is not recorded per request.', 'The key cannot be disabled during an incident.'],
    config: '{\n  "Rules": [{ "ApplyServerSideEncryptionByDefault": { "SSEAlgorithm": "AES256" } }]\n}',
    steps: [
      'Create a customer managed KMS key for this data.',
      'Grant **kms:Decrypt** only to the roles that read the exports.',
      'Make it the default encryption of `<bucket_name>`.',
    ],
    link: ['Using server-side encryption with AWS KMS keys', 'https://docs.aws.amazon.com/AmazonS3/latest/userguide/UsingKMSEncryption.html'],
    cli: 'aws s3api put-bucket-encryption --bucket <SOURCE_BUCKET> --server-side-encryption-configuration '
      + '\'{"Rules":[{"ApplyServerSideEncryptionByDefault":{"SSEAlgorithm":"aws:kms","KMSMasterKeyID":"<KMS_KEY_ARN>"}}]}\'',
  }),
  upwindFinding('S3 bucket policy grants access outside the organization', 'HIGH', {
    heading: 'Another account can read this bucket',
    intro: 'The bucket policy of `<bucket_name>` grants **s3:GetObject** to a principal outside your AWS organization.',
    impact: ['Customer exports can be read from that account.', 'The access is outside your own IAM reviews.', 'Only removing the grant revokes it.'],
    config: '{\n  "Effect": "Allow",\n  "Principal": { "AWS": "arn:aws:iam::<EXTERNAL_ACCOUNT_ID>:root" },\n  "Action": "s3:GetObject"\n}',
    steps: [
      'Confirm whether the external account still needs the data.',
      'Remove the statement, or limit it with the **aws:PrincipalOrgID** condition.',
      'Scan `<bucket_name>` again.',
    ],
    link: ['Examples of Amazon S3 bucket policies', 'https://docs.aws.amazon.com/AmazonS3/latest/userguide/example-bucket-policies.html'],
    cli: 'aws s3api get-bucket-policy --bucket <SOURCE_BUCKET> --query Policy --output text',
  }),
  upwindFinding('S3 bucket keeps noncurrent versions forever', 'MEDIUM', {
    heading: 'Old object versions never expire',
    intro: 'Versioning is on for `<bucket_name>`, but no **lifecycle rule** expires noncurrent versions, so deleted exports stay readable.',
    impact: ['Deleted customer data is still stored.', 'Retention promises are not met.', 'Storage cost keeps growing.'],
    config: '{\n  "Rules": []\n}',
    steps: [
      'Decide how long old versions must be kept.',
      'Add a rule that expires **noncurrent versions** after that period.',
      'Apply it to `<bucket_name>` with the command below.',
    ],
    link: ['Managing the lifecycle of objects', 'https://docs.aws.amazon.com/AmazonS3/latest/userguide/object-lifecycle-mgmt.html'],
    cli: 'aws s3api put-bucket-lifecycle-configuration --bucket <SOURCE_BUCKET> --lifecycle-configuration file://lifecycle.json',
  }),
  upwindFinding('S3 bucket has no replication rule', 'MEDIUM', {
    heading: 'There is no second copy of this data',
    intro: '`<bucket_name>` holds **customer exports** but has no replication rule, so an accidental delete has no recovery copy.',
    impact: ['A deleted export cannot be restored.', 'A regional outage makes the data unavailable.', 'Recovery objectives cannot be met.'],
    config: '{\n  "ReplicationConfiguration": null\n}',
    steps: [
      'Create a destination bucket in a second region.',
      'Add a replication rule with a dedicated **IAM role**.',
      'Enable it on `<bucket_name>` with the command below.',
    ],
    link: ['Replicating objects within and across Regions', 'https://docs.aws.amazon.com/AmazonS3/latest/userguide/replication.html'],
    cli: 'aws s3api put-bucket-replication --bucket <SOURCE_BUCKET> --replication-configuration file://replication.json',
  }),
];

function s3Showcase(source: DemoDatastoreSource): DrawerData {
  return {
    overview: {
      consoleUrl: `https://s3.console.aws.amazon.com/s3/buckets/${S3_NAME}?region=ap-south-1`,
      scanningStatus: 'SCAN_STOPPED',
      scanningStatusLabel: 'Scan Stopped',
      canDiscoverSchema: false,
      fileSummary: fileSummary(12480, {
        unknown_files: 4212, xlsx_files: 3120, pdf_files: 2045, compressed_files: 1388,
        documents: 1061, image_files: 402, log_files: 186, parquet_files: 66,
      }),
      sensitiveRecords: recordsOf(S3_GROUPS),
      properties: [
        textProperty('region', 'Region', String(source.facet.region)),
        sizeProperty(Number(source.facet.storage_size_bytes)),
        textProperty('arn', 'ARN', String(source.facet.arn)),
      ],
      securityConfigurations: securityConfigurations(['Enabled', 'Sse S3', 'Disabled', 'Disabled', 'Enabled']),
    },
    progress: null,
    sensitives: { database: false, entities: entitiesOf(S3_GROUPS), databases: [] },
    files: s3FileRows(),
    occurrences: s3Occurrences,
    risks: S3_RISKS,
    accessGraph: accessGraph(S3_NAME, 'S3', [
      accessGroup(['READ'], 'ROLE', numbered('demo-role', 1, 7)),
      accessGroup(['READ'], 'USER', numbered('demo-user', 1, 1)),
      accessGroup(['READ', 'WRITE', 'MANAGEMENT'], 'ROLE', numbered('demo-role', 8, 3)),
      accessGroup(['READ', 'WRITE', 'MANAGEMENT'], 'USER', numbered('demo-user', 2, 2)),
      accessGroup(['READ', 'WRITE', 'MANAGEMENT'], 'GROUP', numbered('demo-group', 1, 1)),
      accessGroup(['READ', 'WRITE'], 'ROLE', numbered('demo-role', 11, 1)),
    ]),
    runs: [scanRun(source.lastScannedAt ?? daysAgo(52), 2156, 'CRITICAL_SENSITIVITY', 'SCAN_STOPPED', 'Stopped')],
    databases: [],
  };
}

// ─── Showcase 2: the never-scanned Azure container ───────────────────────────

const AZURE_RISKS: DatastoreMisconfiguration[] = [
  {
    title: 'Storage account allows public network access',
    source: 'MATTERS',
    severity: 'HIGH',
    severityLabel: 'High',
    description: '### Public network access is on\n\nThe storage account of `<container_name>` accepts connections from **any network**.\n\n'
      + '- Blobs can be reached from the internet.\n- Network rules do not apply.',
    remediation: '1. Turn off public network access.\n2. Allow only your virtual networks or private endpoints.',
    cli: 'az storage account update --name <STORAGE_ACCOUNT> --resource-group <RESOURCE_GROUP> --public-network-access Disabled',
  },
  {
    title: 'Blob soft delete is not enabled',
    source: 'MATTERS',
    severity: 'MEDIUM',
    severityLabel: 'Medium',
    description: 'A deleted blob in `<container_name>` cannot be recovered, because **soft delete** is off.',
    remediation: 'Enable soft delete for blobs with a retention of at least 7 days.',
    cli: null,
  },
];

function azureShowcase(source: DemoDatastoreSource): DrawerData {
  return {
    overview: {
      consoleUrl: null,
      scanningStatus: null,
      scanningStatusLabel: null,
      canDiscoverSchema: false,
      // Never scanned: no files, every type at zero, and no total ("-", "No sensitive records found").
      fileSummary: fileSummary(0, {}),
      sensitiveRecords: { total: null, groups: [] },
      properties: [
        textProperty('region', 'Region', String(source.facet.region)),
        sizeProperty(0),
        textProperty('subscription_id', 'Subscription ID', String(source.facet.cloud_account_id)),
        textProperty('resource_group', 'Resource Group', 'xammer-ledger-rg'),
        textProperty('storage_account', 'Storage Account', 'xammerledger'),
      ],
      securityConfigurations: securityConfigurations(['Disabled', 'Sse S3', 'Disabled', 'Disabled', 'Enabled']),
    },
    progress: null,
    sensitives: { database: false, entities: [], databases: [] },
    files: [],
    occurrences: () => [],
    risks: AZURE_RISKS,
    accessGraph: accessGraph(source.name, 'AZURE_BLOB', [
      accessGroup(['READ', 'WRITE', 'DELETE', 'MANAGEMENT'], 'ROLE', numbered('demo-role', 101, 6)),
      accessGroup(['READ', 'WRITE', 'DELETE', 'MANAGEMENT'], 'USER', numbered('demo-user', 101, 1)),
      accessGroup(['READ', 'WRITE', 'DELETE'], 'ROLE', numbered('demo-role', 107, 4)),
      accessGroup(['READ'], 'ROLE', numbered('demo-role', 111, 3)),
      // Principals with actions but none of the four flags: their own box, last, with no permissions.
      accessGroup([], 'ROLE', numbered('demo-role', 114, 2), true),
    ]),
    runs: [],
    databases: [],
  };
}

// ─── Showcase 3: the on-prem database ────────────────────────────────────────

const CRM_NAME = 'crm_production';

const CRM_GROUPS: GroupSeed[] = [
  ['SECRETS', IT_SECURITY, [['SHA KEY', 3410], ['HASHED SECRET KEY', 2]]],
  ['PII', PERSONAL, [['EMAIL ADDRESS', 2451], ['PHONE NUMBER', 2310], ['PERSON NAME', 418]]],
];

/** A column, with its finding when the scan found one: entity, count and labels (the entity's by default). */
type ColumnSeed = [name: string, nativeType: string, finding?: [entity: string, count: number, labels?: string[]]];

interface TableSeed {
  name: string;
  schema: 'public' | 'audit';
  rows: number | null;
  foreignKeys: number;
  columns: ColumnSeed[];
  /** Listed in the Sensitive tab's Table View although nothing was found in it. */
  scannedEmpty?: boolean;
}

const ID: ColumnSeed = ['id', 'int8'];
const CREATED: ColumnSeed = ['created_at', 'timestamp'];

/**
 * The database's tables in API order: 23 in `public` and 3 in `audit`. Their findings add up to the
 * entity totals; 14 of them appear in the Table View (8 with findings, 6 empty).
 */
const CRM_TABLES: TableSeed[] = [
  { name: 'accounts', schema: 'public', rows: 1840, foreignKeys: 1, columns: [ID, ['name', 'varchar'], ['industry', 'varchar'], ['owner_id', 'int8'], CREATED] },
  { name: 'activities', schema: 'public', rows: 48210, foreignKeys: 2, scannedEmpty: true,
    columns: [ID, ['contact_id', 'int8'], ['kind', 'text'], ['due_at', 'timestamp'], ['completed_at', 'timestamp']] },
  { name: 'api_keys', schema: 'public', rows: 412, foreignKeys: 1, columns: [
    ID, ['owner_id', 'int8'], ['secret', 'varchar', ['SHA KEY', 3100]],
    ['request_body', 'jsonb', ['SHA KEY', 310, [IT_SECURITY, PERSONAL]]], CREATED, ['revoked_at', 'timestamp'],
  ] },
  { name: 'campaign_members', schema: 'public', rows: 9620, foreignKeys: 2, columns: [ID, ['campaign_id', 'int8'], ['contact_id', 'int8'], ['status', 'text']] },
  { name: 'campaigns', schema: 'public', rows: 214, foreignKeys: 0, columns: [ID, ['name', 'varchar'], ['channel', 'text'], ['starts_at', 'timestamp']] },
  { name: 'contacts', schema: 'public', rows: 2140, foreignKeys: 2, columns: [
    ID, ['display_name', 'varchar', ['PERSON NAME', 296]], ['email', 'varchar', ['EMAIL ADDRESS', 1820]],
    ['phone', 'varchar', ['PHONE NUMBER', 1702]], ['company', 'varchar'], ['job_title', 'varchar'], ['account_id', 'int8'],
    ['owner_id', 'int8'], ['lifecycle_stage', 'text'], ['lead_source', 'text'], ['notes', 'text'], ['metadata', 'jsonb'],
    CREATED, ['updated_at', 'timestamp'],
  ] },
  { name: 'deal_notes', schema: 'public', rows: 1306, foreignKeys: 2, columns: [
    ID, ['deal_id', 'int8'], ['author_email', 'varchar', ['EMAIL ADDRESS', 130]], ['author_name', 'varchar', ['PERSON NAME', 30]],
    ['body', 'text'], CREATED,
  ] },
  { name: 'deal_stages', schema: 'public', rows: 9, foreignKeys: 0, scannedEmpty: true, columns: [ID, ['name', 'varchar'], ['position', 'int4']] },
  { name: 'deals', schema: 'public', rows: 3120, foreignKeys: 3, scannedEmpty: true,
    columns: [ID, ['account_id', 'int8'], ['stage_id', 'int8'], ['amount', 'numeric'], ['closed_at', 'timestamp']] },
  { name: 'email_templates', schema: 'public', rows: 64, foreignKeys: 0, columns: [ID, ['name', 'varchar'], ['subject', 'varchar'], ['body', 'text']] },
  { name: 'invoices', schema: 'public', rows: 5480, foreignKeys: 2, columns: [
    ID, ['account_id', 'int8'], ['billing_email', 'varchar', ['EMAIL ADDRESS', 36]], ['billing_name', 'varchar', ['PERSON NAME', 20]],
    ['amount', 'numeric'], ['issued_at', 'timestamp'],
  ] },
  { name: 'job_queue', schema: 'public', rows: null, foreignKeys: 0, scannedEmpty: true,
    columns: [ID, ['kind', 'text'], ['payload', 'jsonb'], ['run_at', 'timestamp']] },
  { name: 'leads', schema: 'public', rows: 980, foreignKeys: 1, columns: [
    ID, ['name', 'varchar', ['PERSON NAME', 72]], ['email', 'varchar', ['EMAIL ADDRESS', 401]], ['phone', 'varchar', ['PHONE NUMBER', 388]],
    ['source', 'text'], CREATED,
  ] },
  { name: 'notes', schema: 'public', rows: 7410, foreignKeys: 2, columns: [ID, ['contact_id', 'int8'], ['body', 'text'], CREATED] },
  { name: 'opportunities', schema: 'public', rows: 690, foreignKeys: 2, columns: [ID, ['account_id', 'int8'], ['amount', 'numeric'], ['stage', 'text']] },
  { name: 'partner_contacts', schema: 'public', rows: 120, foreignKeys: 1,
    columns: [ID, ['partner_id', 'int8'], ['phone', 'varchar', ['PHONE NUMBER', 100]], ['region', 'text']] },
  { name: 'pricebooks', schema: 'public', rows: 12, foreignKeys: 0, columns: [ID, ['name', 'varchar'], ['currency', 'text']] },
  { name: 'products', schema: 'public', rows: 86, foreignKeys: 1, scannedEmpty: true,
    columns: [ID, ['sku', 'varchar'], ['name', 'varchar'], ['price', 'numeric']] },
  { name: 'quotes', schema: 'public', rows: 1530, foreignKeys: 3, columns: [ID, ['opportunity_id', 'int8'], ['total', 'numeric'], ['valid_until', 'date']] },
  { name: 'support_cases', schema: 'public', rows: 2210, foreignKeys: 2, columns: [
    ID, ['requester_email', 'varchar', ['EMAIL ADDRESS', 64]], ['callback_phone', 'varchar', ['PHONE NUMBER', 120]],
    ['subject', 'varchar'], ['status', 'text'], CREATED,
  ] },
  { name: 'tasks', schema: 'public', rows: null, foreignKeys: 2, columns: [ID, ['owner_id', 'int8'], ['title', 'varchar'], ['due_at', 'timestamp']] },
  { name: 'territories', schema: 'public', rows: 18, foreignKeys: 0, columns: [ID, ['name', 'varchar'], ['region', 'text']] },
  { name: 'user_preferences', schema: 'public', rows: 340, foreignKeys: 1, columns: [ID, ['user_id', 'int8'], ['theme', 'text'], ['locale', 'text']] },
  { name: 'audit_log', schema: 'audit', rows: 182400, foreignKeys: 0, scannedEmpty: true,
    columns: [ID, ['actor_id', 'int8'], ['action', 'text'], ['recorded_at', 'timestamp']] },
  { name: 'login_events', schema: 'audit', rows: 96012, foreignKeys: 1, columns: [ID, ['actor_id', 'int8'], ['ip_hash', 'varchar'], ['logged_in_at', 'timestamp']] },
  { name: 'service_accounts', schema: 'audit', rows: 14, foreignKeys: 0, columns: [
    ID, ['name', 'varchar'], ['password_hash', 'varchar', ['HASHED SECRET KEY', 2]], ['rotated_at', 'timestamp'],
  ] },
];

/** A table's findings, as the Table View's expanded row and the occurrences read them. */
function findingsOf(table: TableSeed) {
  return table.columns.flatMap(([column, , finding]) => (finding
    ? [{ column, entity: finding[0], count: finding[1], labels: finding[2] ?? [ENTITY_LABEL[finding[0]]] }]
    : []));
}

/** The Table View: tables with findings and the scanned empty ones, count descending (stable). */
function crmTables(): DatastoreSensitiveTable[] {
  return CRM_TABLES
    .filter(table => table.scannedEmpty || findingsOf(table).length > 0)
    .map(table => {
      const findings = findingsOf(table);
      return {
        name: table.name,
        schemaName: table.schema,
        count: findings.reduce((sum, finding) => sum + finding.count, 0),
        labels: sortedLabels(findings.flatMap(finding => finding.labels)),
        expandable: findings.length > 0,
        findings: findings.map(finding => ({
          columnName: finding.column, entityType: finding.entity, labels: finding.labels, count: finding.count, falsePositive: false,
        })),
      };
    })
    .sort((a, b) => b.count - a.count);
}

/** One row per column holding the entity, largest first; the first row holds five samples ("+4"). */
function crmOccurrences(entityName: string): DatastoreOccurrence[] {
  return CRM_TABLES
    .flatMap(table => findingsOf(table).filter(finding => finding.entity === entityName).map(finding => ({ table, finding })))
    .sort((a, b) => b.finding.count - a.finding.count)
    .map(({ table, finding }, i) => columnOccurrence(
      CRM_NAME, table.schema, table.name, finding.column, samples(entityName, i, i === 0 ? 5 : i % 2 === 0 ? 2 : 1),
    ));
}

/** Data Flow ids are UUIDs: `de3`, a kind letter (a database, b schema, c table, d column), then a number. */
const flowId = (kind: 'a' | 'b' | 'c' | 'd', n: number) => `de3${kind}0000-0000-4000-8000-${String(n).padStart(12, '0')}`;

const CRM_DATABASE: DataFlowDatabase = { id: flowId('a', 1), name: CRM_NAME, engine: 'POSTGRES', schemaless: false };
const CRM_SCHEMAS = [{ id: flowId('b', 1), name: 'public' }, { id: flowId('b', 2), name: 'audit' }];

/** Every table with its Data Flow row and columns. */
const CRM_FLOW = CRM_TABLES.map((seed, index): { schema: string; table: DataFlowTable; columns: DataFlowColumn[] } => ({
  schema: seed.schema,
  table: {
    id: flowId('c', index + 1),
    name: seed.name,
    rowCountEstimate: seed.rows,
    foreignKeyCount: seed.foreignKeys,
    sensitiveCount: findingsOf(seed).reduce((sum, finding) => sum + finding.count, 0),
  },
  columns: seed.columns.map(([name, nativeType, finding], column) => ({
    id: flowId('d', (index + 1) * 100 + column + 1),
    name,
    nativeType,
    sensitiveCount: finding?.[1] ?? 0,
    dataElements: finding ? [finding[0]] : [],
    primaryKey: name === 'id',
    // No demo foreign keys exist yet: FEAT-007 derives this flag from CRM_FOREIGN_KEYS.
    foreignKey: false,
  })),
}));

function crmShowcase(source: DemoDatastoreSource): DrawerData {
  return {
    overview: {
      consoleUrl: null,
      scanningStatus: 'SCANNING_OUTPUT_PROCESSED',
      scanningStatusLabel: 'Scanning Output Processed',
      canDiscoverSchema: true,
      fileSummary: null,
      sensitiveRecords: recordsOf(CRM_GROUPS),
      properties: [textProperty('host', 'Host', String(source.facet.host)), textProperty('port', 'Port', String(source.facet.port))],
      securityConfigurations: null,
    },
    progress: 100,
    sensitives: {
      database: true,
      entities: entitiesOf(CRM_GROUPS),
      databases: [{ id: CRM_DATABASE.id, name: CRM_NAME, tables: crmTables() }],
    },
    files: [],
    occurrences: crmOccurrences,
    risks: [],
    accessGraph: accessGraph(CRM_NAME, 'SELF_HOSTED_POSTGRES', [
      accessGroup(['READ'], 'USER', ['bi_reader', 'support_reader']),
      accessGroup(['READ', 'WRITE', 'DELETE', 'MANAGEMENT'], 'USER', ['crm_admin']),
      accessGroup(['READ', 'WRITE', 'DELETE'], 'USER', ['crm_app']),
    ]),
    runs: [scanRun(source.lastScannedAt, 8591, 'HIGH_SENSITIVITY', 'SCAN_COMPLETED', 'Scan Completed')],
    databases: [CRM_DATABASE],
  };
}

// ─── Every other cloud and on-prem row ───────────────────────────────────────

/** The overview's scanning status, its label and the scan progress, by the list status. */
const SCANNING: Record<string, [code: string, label: string, progress: number | null]> = {
  CLASSIFIED: ['SCANNING_OUTPUT_PROCESSED', 'Scanning Output Processed', 100],
  SCANNING: ['SCANNING_IN_PROGRESS', 'Scanning In Progress', 40],
  STOPPED: ['SCAN_STOPPED', 'Scan Stopped', null],
  FAILED: ['SCAN_FAILED', 'Scan Failed', null],
};

const OBJECT_STORES = new Set(['S3', 'GCS', 'AZURE_BLOB']);

/** Facet keys that describe the datastore itself, after Region and Datastore Size, with their labels. */
const PROPERTY_KEYS: ReadonlyArray<[key: string, label: string]> = [
  ['arn', 'ARN'], ['engine', 'Engine'], ['engine_version', 'Engine Version'], ['gcp_project_id', 'GCP Project ID'],
  ['host', 'Host'], ['port', 'Port'],
];

function derivedProperties(source: DemoDatastoreSource): DatastoreProperty[] {
  const { facet } = source;
  const properties: DatastoreProperty[] = [];
  // The vendor reports no region for a self-hosted database (Host and Port only).
  if (source.category === 'cloud' && typeof facet.region === 'string') properties.push(textProperty('region', 'Region', facet.region));
  if (typeof facet.storage_size_bytes === 'number') properties.push(sizeProperty(facet.storage_size_bytes));
  for (const [key, label] of PROPERTY_KEYS) {
    const value = facet[key];
    if (typeof value === 'string' || typeof value === 'number') properties.push(textProperty(key, label, String(value)));
  }
  return properties;
}

/** Where an object lives: its path, else its name, else its id. */
const locationOf = (object: DspmObject): string => object.path ?? object.name ?? object.id;

function derivedFileRow(object: DspmObject): DatastoreFileRow {
  const path = locationOf(object);
  if (object.findings.length === 0) return { path, name: basename(path), count: 0, labels: [], entities: [{ name: '', label: null }] };
  return {
    path,
    name: basename(path),
    count: object.sensitiveCount,
    labels: sortedLabels(object.findings.flatMap(finding => (finding.categoryLabel ? [finding.categoryLabel] : []))),
    entities: object.findings.map(finding => ({ name: finding.entityType, label: finding.categoryLabel })),
  };
}

/**
 * Drawer data from a row's spec (design 7.3, last row): entities, file rows and one occurrence per file
 * from its files, the overview total equal to the list's record count, groups in the row's type order,
 * no misconfigurations, no principals, and one run once classified.
 */
function derivedData(source: DemoDatastoreSource): DrawerData {
  const scanning = SCANNING[source.status] ?? null;
  const entities: DatastoreSensitiveEntity[] = source.entities.map(entity => ({
    name: entity.entityType, count: entity.count, categoryLabel: entity.categoryLabel, blank: false,
  }));
  const index = Number(source.id.replace(/\D/g, ''));
  return {
    overview: {
      consoleUrl: null,
      scanningStatus: scanning?.[0] ?? null,
      scanningStatusLabel: scanning?.[1] ?? null,
      canDiscoverSchema: source.category === 'on_prem',
      fileSummary: null,
      sensitiveRecords: {
        total: source.status === 'NOT_SCANNED' ? null : source.recordCount,
        groups: source.types.map((code, i) => ({
          code,
          label: source.typeLabels[i] ?? null,
          entities: source.entities.filter(entity => entity.category === code).map(entity => ({ name: entity.entityType, count: entity.count })),
        })),
      },
      properties: derivedProperties(source),
      securityConfigurations: OBJECT_STORES.has(source.provider)
        ? securityConfigurations([
          index % 2 === 0 ? 'Enabled' : 'Disabled', source.provider === 'S3' ? 'Sse S3' : 'Enabled', 'Disabled',
          index % 3 === 0 ? 'Enabled' : 'Disabled', 'Enabled',
        ])
        : null,
    },
    progress: scanning?.[2] ?? null,
    sensitives: { database: false, entities: source.objects.length > 0 ? [...entities, BLANK_ENTITY] : entities, databases: [] },
    files: source.objects.map(derivedFileRow),
    occurrences: entityName => source.objects
      .filter(object => object.findings.some(finding => finding.entityType === entityName))
      .map((object, i) => fileOccurrence(locationOf(object), samples(entityName, i, 2))),
    risks: [],
    accessGraph: accessGraph(source.name, source.provider, []),
    runs: source.status === 'CLASSIFIED'
      ? [scanRun(source.lastScannedAt, source.recordCount, source.sensitivityLevel, 'SCAN_COMPLETED', 'Scan Completed')]
      : [],
    databases: [],
  };
}

// ─── Answers ─────────────────────────────────────────────────────────────────

/**
 * The showcases' figures for the list (design 7.2): the sum of their drawer's entity counts, the label
 * codes of their overview groups in order, and their entity names.
 */
export const SHOWCASE_SENSITIVES: Record<string, { recordCount: number; types: string[]; elements: string[] }> = {
  'demo-ds-01': figuresOf(S3_GROUPS),
  'demo-ds-11': figuresOf(CRM_GROUPS),
  'demo-ds-20': { recordCount: 0, types: [], elements: [] },
};

function figuresOf(groups: GroupSeed[]) {
  const entities = groups.flatMap(([, , list]) => list);
  return {
    recordCount: entities.reduce((sum, [, count]) => sum + count, 0),
    types: groups.map(([code]) => code),
    elements: entities.map(([name]) => name),
  };
}

const SHOWCASES: Record<string, (source: DemoDatastoreSource) => DrawerData> = {
  'demo-ds-01': s3Showcase,
  'demo-ds-11': crmShowcase,
  'demo-ds-20': azureShowcase,
};

const intParam = (params: URLSearchParams, name: string, fallback: number, min: number, max: number) =>
  Math.min(max, Math.max(min, Number.parseInt(params.get(name) ?? '', 10) || fallback));

/** One page of `rows` by the request's `page` and `pageSize` (defaults 1 and 10). */
function pageOf<T>(rows: T[], params: URLSearchParams) {
  const page = intParam(params, 'page', 1, 1, 1_000_000);
  const pageSize = intParam(params, 'pageSize', 10, 1, 100);
  const start = (page - 1) * pageSize;
  return { rows: rows.slice(start, start + pageSize), page, pageSize, total: rows.length, totalPages: Math.ceil(rows.length / pageSize) };
}

/**
 * `GET /api/dspm/datastores/{sourceId}/{resource}` for a cloud or on-prem datastore, or null for an id
 * the demo does not know (the router answers 404). `url` carries the paging, search and entity name.
 */
export function demoDatastoreResource(id: string, resource: string, url: string): object | null {
  const source = demoDatastoreSource(id);
  if (!source) return null;
  const data = (SHOWCASES[source.id] ?? derivedData)(source);
  const params = new URL(url, 'http://localhost').searchParams;
  switch (resource) {
    case 'overview': return data.overview;
    case 'scan-status': return { progress: data.progress } satisfies DatastoreScanProgress;
    case 'sensitives': return data.sensitives;
    case 'sensitives/files': {
      // The search matches paths, case-insensitively, before paging (A15).
      const q = (params.get('q') ?? '').trim().toLowerCase();
      const rows = q ? data.files.filter(row => row.path.toLowerCase().includes(q)) : data.files;
      return { ...pageOf(rows, params), truncated: false } satisfies DatastoreFilePage;
    }
    case 'sensitives/occurrences': {
      const entityName = (params.get('entityName') ?? '').trim();
      const skip = intParam(params, 'skip', 0, 0, 10_000);
      const limit = intParam(params, 'limit', 10, 1, 100);
      const showFalsePositives = params.get('showFalsePositives') === 'true';
      const rows = data.occurrences(entityName).filter(row => showFalsePositives || !row.falsePositive);
      return { entityName, total: rows.length, skip, limit, rows: rows.slice(skip, skip + limit) } satisfies DatastoreOccurrencePage;
    }
    case 'risks': return { items: data.risks } satisfies DatastoreRisks;
    case 'access-graph': return data.accessGraph;
    case 'scan-history': return { runs: data.runs } satisfies DatastoreScanHistory;
    case 'data-flow/databases': return { items: data.databases, truncated: false } satisfies DataFlowDatabases;
    default: return null;
  }
}

/**
 * `GET /api/dspm/data-flow/{kind}/{id}/{child}`: a database's schemas, a schema's tables (paged, and
 * searched by name once `q` has 3 characters) and a table's columns. Null for an unknown id; the only
 * Data Flow database is `crm_production`'s, which has schemas, so `databases/{id}/tables` is never one.
 */
export function demoDataFlowResource(kind: string, id: string, child: string, url: string): object | null {
  const params = new URL(url, 'http://localhost').searchParams;
  if (kind === 'databases' && child === 'schemas') {
    return id === CRM_DATABASE.id ? { items: CRM_SCHEMAS, truncated: false } satisfies DataFlowSchemas : null;
  }
  if (kind === 'schemas' && child === 'tables') {
    const schema = CRM_SCHEMAS.find(s => s.id === id);
    if (!schema) return null;
    const q = (params.get('q') ?? '').trim().toLowerCase();
    const tables = CRM_FLOW.filter(entry => entry.schema === schema.name).map(entry => entry.table)
      .filter(table => q.length < 3 || (table.name ?? '').toLowerCase().includes(q));
    return { ...pageOf(tables, params), searchCapped: false } satisfies DataFlowTablePage;
  }
  if (kind === 'tables' && child === 'columns') {
    const entry = CRM_FLOW.find(e => e.table.id === id);
    return entry ? pageOf(entry.columns, params) satisfies DataFlowColumnPage : null;
  }
  return null;
}
