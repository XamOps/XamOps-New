import type { DatastoreRow } from '../../../../types/dspmDatastores';
import type { DatastoreScanParameters, DatastoreScanSettings } from '../../../../types/dspmScans';

/** The AWS services the console's scan settings cover (the set `DspmIcons.tsx` draws as AWS). */
export const AWS_SCAN_SERVICES = ['S3', 'EBS', 'RDS', 'RDS_ORACLE', 'DYNAMODB', 'DOCUMENTDB', 'MSK_KAFKA'] as const;

const OTHER_SCAN_SERVICES = new Set(['CLOUD_SQL', 'GCS', 'SNOWFLAKE']);
const AWS = new Set<string>(AWS_SCAN_SERVICES);
const OBJECT_STORES = new Set(['S3', 'GCS', 'EBS']);

type RowShape = Pick<DatastoreRow, 'category' | 'source'>;

const providerOf = (row: RowShape) => row.source.provider ?? '';

/**
 * Whether the Actions menu lists Scan Settings (design 4.8, the bundle's `serviceRules`): every on-prem
 * datastore, the AWS services above, any Databricks service, Cloud SQL, GCS and Snowflake. Azure Blob and
 * BigQuery are left out, as in the console.
 */
export function scanSettingsEligible(row: RowShape): boolean {
  if (row.category === 'on_prem') return true;
  const provider = providerOf(row);
  return AWS.has(provider) || provider.startsWith('DATABRICKS') || OTHER_SCAN_SERVICES.has(provider);
}

/** Snowflake and Databricks datastores choose the databases a scan covers. */
export function showDatabases(row: RowShape): boolean {
  const provider = providerOf(row);
  return provider === 'SNOWFLAKE' || provider.startsWith('DATABRICKS');
}

/** S3, GCS and EBS hold files, so their scans take path conditions. */
export function isObjectStore(row: RowShape): boolean {
  return OBJECT_STORES.has(providerOf(row));
}

export type ScanSettingsSection =
  | 'parameters' | 'conditions' | 'databases' | 'assist' | 'connection' | 'credentials' | 'cost';

/**
 * The Configuration Parameters in the console's order, with its labels and descriptions (copied from the
 * public bundle chunk `CloudScanSettingsDrawer-0d32ff28.js`, read as data on 2026-11-19).
 */
export const SCAN_PARAMETERS: ReadonlyArray<{
  key: keyof DatastoreScanParameters;
  label: string;
  description: string;
  percent?: boolean;
}> = [
  {
    key: 'numFoldersLeafNode',
    label: 'Number of Files per Leaf Folder',
    description: 'This parameter focuses on the file count within leaf folders - final storage nodes without subdirectories. We recommend a setting of 50 for optimal scan efficiency.',
  },
  {
    key: 'parallelism',
    label: 'Maximum Parallelism',
    description: 'This parameter defines the level of parallel execution during scans. We recommend a value of 100 for optimal performance and balanced resource utilization.',
  },
  {
    key: 'timeOutHours',
    label: 'Timeout for the Classification Scan (hrs)',
    description: "This parameter sets the maximum duration for a scan, aligning with the desired turnaround time for the client. While our recommended value is 2 hours, it's important to consider the size and number of files to ensure the scan completes within the expected timeframe.",
  },
  {
    key: 'rowSamplingLimit',
    label: 'Row-Wise Sampling Limit (rows)',
    description: 'This parameter controls the number of rows per file sampled during the scan, with our recommended value set at 5,000. If your data has a consistent structure, we recommend a reduced row-wise sampling limit which may still provide an accurate assessment.',
  },
  {
    key: 'samplingPercentageLimit',
    label: 'Sampling Percentage',
    description: 'This parameter defines the portion of your data that will be sampled for the scan, with our recommended value set at 10%.',
    percent: true,
  },
];

/** One shown parameter: its label, description and the value as the field draws it ("5,000", "10%"). */
export interface ParameterRow {
  key: keyof DatastoreScanParameters;
  label: string;
  description: string;
  value: string;
}

/** The parameters the response fills, in the console's order. */
export function parameterRows(settings: DatastoreScanSettings): ParameterRow[] {
  const parameters = settings.parameters;
  if (parameters === null) return [];
  return SCAN_PARAMETERS.flatMap(({ key, label, description, percent }) => {
    const value = parameters[key];
    if (value === null || value === undefined) return [];
    const text = value.toLocaleString('en-US');
    return [{ key, label, description, value: percent ? `${text}%` : text }];
  });
}

/**
 * The sections the drawer draws, in order (design 6.9 table). A section the response does not fill is
 * left out, so the drawer never shows an empty form the data cannot back.
 */
export function scanSettingsSections(row: RowShape, settings: DatastoreScanSettings): ScanSettingsSection[] {
  const sections: ScanSettingsSection[] = [];
  if (parameterRows(settings).length > 0) sections.push('parameters');
  if (isObjectStore(row) || settings.inclusionPaths.length > 0 || settings.exclusionPaths.length > 0) {
    sections.push('conditions');
  }
  if (showDatabases(row)) sections.push('databases');
  if (settings.accessible !== null || settings.exportAssistName !== null || settings.discoveryAssistName !== null) {
    sections.push('assist');
  }
  if (settings.host !== null || settings.port !== null || settings.serviceName !== null) sections.push('connection');
  if (settings.host !== null) sections.push('credentials');
  if ((settings.parameters?.timeOutHours ?? null) !== null) sections.push('cost');
  return sections;
}
