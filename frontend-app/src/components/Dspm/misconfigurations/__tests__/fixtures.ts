/** Synthetic Misconfigurations data shared by the page and drawer tests. No tenant values. */
import type { DatastoreRow } from '../../../../types/dspmDatastores';
import type { MisconfigurationDetail, MisconfigurationList, MisconfigurationRow } from '../../../../types/dspmMisconfigurations';

export function policy(over: Partial<MisconfigurationRow> & Pick<MisconfigurationRow, 'policyId' | 'title'>): MisconfigurationRow {
  return {
    source: 'MATTERS', severity: 'MEDIUM', severityLabel: 'Medium', affectedResources: 1, sensitiveRecords: 0,
    labels: [], frameworks: [], cloudAccounts: [],
    ...over,
  };
}

export const PUBLIC_READ = policy({
  policyId: 'p-00000000000000a1', title: 'Bucket allows anonymous public read', source: 'UPWIND', severity: 'HIGH',
  severityLabel: 'High', affectedResources: 4, sensitiveRecords: 4_700, labels: ['Personal', 'IT & Security'],
  frameworks: ['SOC2', 'NIST', 'GDPR'], cloudAccounts: ['example-prod'],
});
export const ACCESS_LOGGING = policy({
  policyId: 'p-00000000000000b2', title: 'Server access logging is disabled', source: 'UPWIND', affectedResources: 33,
  sensitiveRecords: 432, labels: ['Personal'], frameworks: ['NIST'], cloudAccounts: ['example-prod', 'example-stg'],
});
export const BACKUPS = policy({
  policyId: 'backup_encryption_rule', title: 'Backups are not encrypted', affectedResources: 2,
});

/** Cards with figures the page must never show. */
export const LIST: MisconfigurationList = {
  totalAffectedResources: 4242,
  open: { total: 7171, critical: 1313, high: 2626, medium: 3939, low: 5252 },
  datastoresRead: 88,
  capped: false,
  rows: [PUBLIC_READ, ACCESS_LOGGING, BACKUPS],
};

export function datastore(over: Partial<DatastoreRow> & Pick<DatastoreRow, 'name'>): DatastoreRow {
  return {
    id: null, sourceId: null, category: 'cloud', subtype: 'S3', subtypeLabel: 'S3',
    risk: { score: null, level: null, levelLabel: null },
    sensitives: { recordCount: 0, typeLabels: [] },
    source: { provider: 'S3', providerLabel: 'S3', accountLabel: null, region: null },
    scan: { status: 'CLASSIFIED', statusLabel: 'Classified', displayAt: null },
    agent: null,
    ...over,
  };
}

export const ORDERS = datastore({
  id: 'asset-orders', name: 'orders-archive', sourceId: '0123456789abcdef01234567',
  sensitives: { recordCount: 1_559, typeLabels: ['Personal'] },
  source: { provider: 'S3', providerLabel: 'S3', accountLabel: 'example-prod', region: 'eu-west-1' },
});
export const EXPORTS = datastore({
  id: 'asset-exports', name: 'exports-staging', sourceId: 'abcdefabcdefabcdefabcdef',
  sensitives: { recordCount: 12, typeLabels: ['IT & Security'] },
  source: { provider: 'S3', providerLabel: 'S3', accountLabel: 'example-stg', region: 'us-east-1' },
});
export const LEDGER = datastore({
  id: 'asset-ledger', name: 'ledger_db', category: 'on_prem', subtype: 'SELF_HOSTED_POSTGRES', subtypeLabel: 'PostgreSQL',
  source: { provider: 'SELF_HOSTED_POSTGRES', providerLabel: 'PostgreSQL', accountLabel: 'ledger_db', region: null },
});

export const DETAIL: MisconfigurationDetail = {
  policyId: PUBLIC_READ.policyId,
  title: PUBLIC_READ.title,
  source: 'UPWIND',
  severity: 'HIGH',
  severityLabel: 'High',
  description: 'The bucket grants **anonymous** read to `AllUsers`.',
  remediation: '1. Open the bucket permissions.\n2. Remove the public grant.',
  cli: 'aws s3api put-public-access-block --bucket <bucket_name>',
  learnMore: 'https://docs.example.com/s3-public-access',
  frameworks: ['SOC2', 'NIST', 'GDPR'],
  affected: [
    { datastore: ORDERS, lastScannedAt: 1_790_000_000 },
    { datastore: EXPORTS, lastScannedAt: null },
    { datastore: LEDGER, lastScannedAt: null },
  ],
};
