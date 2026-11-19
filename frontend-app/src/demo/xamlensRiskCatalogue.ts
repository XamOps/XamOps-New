/**
 * XamLens demo misconfiguration catalogue (ROLE_XAMOPS): the synthetic policies that derived cloud and
 * on-prem demo datastores report on their drawer's Risk tab, and that the Misconfigurations page groups.
 *
 * Every id, title, framework and text is invented for the demo. Imports types only, so both the drawer
 * data and the page data can read it without a load-order cycle.
 */
import type { DatastoreMisconfiguration } from '../types/dspmDatastores';

/** One synthetic policy and the datastores it applies to. */
interface CataloguePolicy {
  policyId: string;
  title: string;
  source: 'UPWIND' | 'MATTERS';
  severity: 'HIGH' | 'MEDIUM';
  /** Markdown. */
  description: string;
  /** Markdown. */
  remediation: string;
  cli: string | null;
  learnMore: string | null;
  frameworks: string[];
  /** The providers whose datastores report it. */
  providers: readonly string[];
  /** Further narrows it by the spec's number (`demo-ds-07` is 7), so counts differ between policies. */
  applies?: (index: number) => boolean;
}

const CATALOGUE: readonly CataloguePolicy[] = [
  {
    policyId: 'p-de30000000000001',
    title: 'S3 bucket does not block public access',
    source: 'UPWIND',
    severity: 'HIGH',
    description: [
      'The bucket has **Block Public Access** turned off, so a bucket policy or ACL can expose objects to anyone.',
      '',
      '- Objects with personal data can be listed or read anonymously.',
      '- A later policy change can make the exposure silent.',
    ].join('\n'),
    remediation: [
      '1. Open the bucket in the **AWS Management Console** and go to *Permissions*.',
      '2. Turn on all four `Block Public Access` settings.',
      '3. Review the bucket policy for `"Principal": "*"` statements.',
    ].join('\n'),
    cli: 'aws s3api put-public-access-block --bucket <bucket_name> --public-access-block-configuration BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true',
    learnMore: 'https://docs.aws.amazon.com/AmazonS3/latest/userguide/access-control-block-public-access.html',
    frameworks: ['SOC2', 'NIST 800-53', 'GDPR'],
    providers: ['S3'],
    applies: index => index % 2 === 0,
  },
  {
    policyId: 'p-de30000000000002',
    title: 'S3 bucket does not write server access logs',
    source: 'UPWIND',
    severity: 'MEDIUM',
    description: 'Amazon S3 is not writing **server access logs** for the bucket, so reads of its objects leave no audit trail.',
    remediation: [
      '1. Pick a separate bucket to receive the logs.',
      '2. Allow the S3 logging service to write to it.',
      '3. Turn on server access logging with the command below.',
    ].join('\n'),
    cli: 'aws s3api put-bucket-logging --bucket <bucket_name> --bucket-logging-status file://logging.json',
    learnMore: null,
    frameworks: ['NIST 800-53'],
    providers: ['S3'],
  },
  {
    policyId: 'p-de30000000000003',
    title: 'Storage account allows shared key access',
    source: 'MATTERS',
    severity: 'MEDIUM',
    description: 'The storage account accepts **shared key** requests, which bypass Microsoft Entra ID and its audit trail.',
    remediation: '1. Move clients to Entra ID authorisation.\n2. Turn off shared key access on the account.',
    cli: 'az storage account update --name <STORAGE_ACCOUNT> --resource-group <RESOURCE_GROUP> --allow-shared-key-access false',
    learnMore: null,
    frameworks: ['SOC2'],
    providers: ['AZURE_BLOB'],
  },
  {
    policyId: 'p-de30000000000004',
    title: 'EBS volume is not encrypted at rest',
    source: 'UPWIND',
    severity: 'HIGH',
    description: 'The volume was created **without encryption**; its snapshots are unencrypted as well.',
    remediation: [
      '1. Create an encrypted snapshot copy of the volume.',
      '2. Restore a new volume from it and swap it in.',
      '3. Turn on `EBS encryption by default` for the region.',
    ].join('\n'),
    cli: 'aws ec2 enable-ebs-encryption-by-default --region <region>',
    learnMore: null,
    frameworks: ['SOC2', 'GDPR'],
    providers: ['EBS'],
  },
  {
    policyId: 'bigquery_dataset_cmk_disabled',
    title: 'BigQuery dataset is not encrypted with a customer-managed key',
    source: 'MATTERS',
    severity: 'MEDIUM',
    description: 'The dataset uses Google-managed encryption keys, so key rotation and revocation are out of your control.',
    remediation: '1. Create a Cloud KMS key in the dataset\'s region.\n2. Set it as the dataset\'s default `kmsKeyName`.',
    cli: null,
    learnMore: null,
    frameworks: ['SOC2'],
    providers: ['BIGQUERY'],
  },
  {
    policyId: 'gcs_uniform_access_disabled',
    title: 'Cloud Storage bucket does not use uniform bucket-level access',
    source: 'MATTERS',
    severity: 'MEDIUM',
    description: 'Object ACLs are still honoured on the bucket, so access can differ from object to object.',
    remediation: '1. Review object ACLs that grant extra access.\n2. Turn on uniform bucket-level access.',
    cli: 'gcloud storage buckets update gs://<bucket_name> --uniform-bucket-level-access',
    learnMore: null,
    frameworks: [],
    providers: ['GCS'],
  },
  {
    policyId: 'p-de30000000000007',
    title: 'DynamoDB table has point-in-time recovery disabled',
    source: 'UPWIND',
    severity: 'MEDIUM',
    description: 'Without **point-in-time recovery** an accidental write or delete cannot be rolled back.',
    remediation: '1. Turn on point-in-time recovery for the table.',
    cli: 'aws dynamodb update-continuous-backups --table-name <table_name> --point-in-time-recovery-specification PointInTimeRecoveryEnabled=true',
    learnMore: null,
    frameworks: ['NIST 800-53'],
    providers: ['DYNAMODB'],
  },
  {
    policyId: 'p-de30000000000008',
    title: 'Database accepts connections without TLS',
    source: 'MATTERS',
    severity: 'HIGH',
    description: 'The database accepts **unencrypted** client connections, so credentials and rows can cross the network in clear text.',
    remediation: '1. Require TLS on the server (`require_secure_transport` or `ssl = on` with `hostssl` rules).\n2. Update clients to connect with TLS.',
    cli: null,
    learnMore: null,
    frameworks: ['GDPR', 'SOC2'],
    providers: ['RDS', 'SELF_HOSTED_POSTGRES'],
  },
];

/** Scan states whose datastores report findings. */
const SCANNED = new Set(['CLASSIFIED', 'STOPPED']);

const asFinding = (policy: CataloguePolicy): DatastoreMisconfiguration => ({
  title: policy.title,
  source: policy.source,
  severity: policy.severity,
  severityLabel: policy.severity === 'HIGH' ? 'High' : 'Medium',
  description: policy.description,
  remediation: policy.remediation,
  cli: policy.cli,
});

/**
 * The findings of a derived cloud or on-prem demo datastore: every catalogue policy for its provider
 * that applies to its number, in catalogue order. None until it is scanned (CLASSIFIED or STOPPED).
 */
export function demoCatalogueRisks(source: { id: string; provider: string; status: string }): DatastoreMisconfiguration[] {
  if (!SCANNED.has(source.status)) return [];
  const index = Number(source.id.replace(/\D/g, ''));
  return CATALOGUE
    .filter(policy => policy.providers.includes(source.provider) && (policy.applies?.(index) ?? true))
    .map(asFinding);
}

/** The catalogue policy with this title: its id, frameworks and Learn More link. */
export function demoCataloguePolicy(title: string): Pick<CataloguePolicy, 'policyId' | 'frameworks' | 'learnMore'> | null {
  const policy = CATALOGUE.find(p => p.title === title);
  return policy ? { policyId: policy.policyId, frameworks: policy.frameworks, learnMore: policy.learnMore } : null;
}
