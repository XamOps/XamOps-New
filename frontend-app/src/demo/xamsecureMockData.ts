/**
 * XamSecure Demo Mock Data  (ROLE_XAMOPS)
 *
 * Kept out of xamopsMockData.ts because XamSecure is its own API surface
 * (/api/xamsecure/*, ~20 endpoints across 11 tabs) and that file is already
 * large enough to be hard to navigate.
 *
 * Two things are deliberate here:
 *
 * 1. Timestamps are generated relative to now. Several tabs bucket by calendar
 *    day over a trailing 7-day window and drop anything outside it, so frozen
 *    dates would leave the trend charts empty a day after they were written.
 *
 * 2. The bulk tables (threats, vulnerabilities, posture, inventory, CVEs) are
 *    composed from pools rather than hand-listed, because these views only look
 *    real at a few hundred rows — enough to fill a table, drive the pager and
 *    give the category and severity filters something to cut through. The
 *    composition is index-driven, not random, so every render and every test
 *    sees the same rows.
 */

import type {
  ApiEndpoint, AttackSurfaceSummary, ComplianceFramework, ConfigurationsDashboard,
  CveGroupRow, CveResourceFinding, InventoryAsset, InventoryCategory, InventoryRisk, IssueDetail,
  IssueSummary, NetworkTopology, NetworkNode, OnboardingAccount, PageResp, PostureFinding,
  SbomComponent, Severity, ThreatDetection, ThreatPolicy, ThreatStoriesSummary, ThreatOverview,
  VulnerabilityDashboard, VulnerabilityFinding, Workflow, XamSecureSummary,
} from '../types/xamsecure';

// ─── Helpers ─────────────────────────────────────────────────────────────────

const MS_DAY = 24 * 60 * 60 * 1000;

const daysAgo = (n: number): string => new Date(Date.now() - n * MS_DAY).toISOString();
const hoursAgo = (n: number): string => new Date(Date.now() - n * 60 * 60 * 1000).toISOString();

/** Deterministic pick — same index always yields the same element. */
const at = <T>(pool: readonly T[], i: number): T => pool[i % pool.length];

/** Spread a value over a range without randomness, so runs stay reproducible. */
const spread = (i: number, min: number, max: number): number =>
  min + ((i * 37) % (max - min + 1));

/** The seven `YYYY-MM-DD` keys the trend charts bucket into, oldest first. */
const last7Keys = (): string[] => {
  const out: string[] = [];
  for (let i = 6; i >= 0; i--) {
    const d = new Date();
    d.setHours(0, 0, 0, 0);
    d.setDate(d.getDate() - i);
    out.push(d.toISOString().slice(0, 10));
  }
  return out;
};

/** Severity mix weighted the way a real estate looks: mostly medium and low. */
const SEVERITY_MIX: readonly Severity[] = [
  'CRITICAL', 'HIGH', 'HIGH', 'MEDIUM', 'MEDIUM', 'MEDIUM', 'LOW', 'HIGH',
  'MEDIUM', 'LOW', 'CRITICAL', 'MEDIUM', 'HIGH', 'LOW', 'MEDIUM', 'LOW',
];
const sevAt = (i: number): Severity => at(SEVERITY_MIX, i);

const REGIONS = [
  'ap-south-1', 'us-east-1', 'us-west-2', 'eu-west-1', 'ap-southeast-1', 'eu-central-1',
] as const;

const ACCOUNT_IDS = ['112233445566', '223344556677', '334455667788'] as const;

// ─── Query-string aware paging ───────────────────────────────────────────────
// The tables send ?page=&size= (and inventory sends &category=). Honouring them
// makes the Prev/Next controls and the category rail actually work in the demo
// instead of redrawing page one forever.

const qs = (url: string): URLSearchParams => {
  const i = url.indexOf('?');
  return new URLSearchParams(i === -1 ? '' : url.slice(i + 1));
};

const paginate = <T>(items: T[], url: string): PageResp<T> => {
  const q = qs(url);
  const size = Math.max(1, Number(q.get('size')) || 50);
  const totalPages = Math.max(1, Math.ceil(items.length / size));
  const number = Math.min(Math.max(0, Number(q.get('page')) || 0), totalPages - 1);
  return {
    content: items.slice(number * size, number * size + size),
    totalElements: items.length,
    totalPages,
    number,
  };
};

const bySeverity = <T extends { severity: Severity | null }>(items: T[], url: string): T[] => {
  const wanted = qs(url).get('severity');
  if (!wanted) return items;
  return items.filter((i) => (i.severity ?? '').toUpperCase() === wanted.toUpperCase());
};

// ─── /api/xamsecure/issues ───────────────────────────────────────────────────
// The Home tab derives its severity table, "new issues" tiles and cumulative
// findings-over-time chart from this list, so the spread of severities and
// firstSeen dates is what makes that page look populated.

const issue = (
  check: string, title: string, severity: Severity, resourceCount: number,
  frameworks: string[], firstSeenDays: number,
): IssueSummary => ({
  check, title, severity, resourceCount, frameworks,
  firstSeen: daysAgo(firstSeenDays), lastEvaluation: hoursAgo(4),
});

export const demoIssues: IssueSummary[] = [
  issue('s3_bucket_public_read_acl', 'S3 bucket allows public read access', 'CRITICAL', 3, ['CIS AWS 1.5', 'SOC 2', 'PCI DSS 3.2.1'], 21),
  issue('iam_root_access_key_present', 'Root account has an active access key', 'CRITICAL', 1, ['CIS AWS 1.5', 'SOC 2'], 14),
  issue('rds_instance_publicly_accessible', 'RDS instance is publicly accessible', 'CRITICAL', 2, ['CIS AWS 1.5', 'HIPAA'], 5),
  issue('secrets_hardcoded_in_task_definition', 'Plaintext secret in an ECS task definition', 'CRITICAL', 4, ['CIS AWS 1.5', 'SOC 2'], 3),
  issue('eks_endpoint_public', 'EKS API server endpoint is public', 'CRITICAL', 2, ['CIS EKS 1.4', 'SOC 2'], 9),
  issue('kms_key_rotation_disabled', 'KMS key rotation is disabled', 'CRITICAL', 5, ['CIS AWS 1.5', 'PCI DSS 3.2.1'], 17),
  issue('sg_ingress_ssh_from_anywhere', 'Security group allows SSH (22) from 0.0.0.0/0', 'HIGH', 9, ['CIS AWS 1.5', 'PCI DSS 3.2.1'], 30),
  issue('sg_ingress_rdp_from_anywhere', 'Security group allows RDP (3389) from 0.0.0.0/0', 'HIGH', 3, ['CIS AWS 1.5'], 26),
  issue('ebs_volume_not_encrypted', 'EBS volume is not encrypted at rest', 'HIGH', 17, ['SOC 2', 'HIPAA', 'ISO 27001'], 30),
  issue('iam_user_mfa_not_enabled', 'IAM user without MFA enabled', 'HIGH', 6, ['CIS AWS 1.5', 'SOC 2'], 3),
  issue('iam_policy_allows_full_admin', 'IAM policy grants full administrative privileges', 'HIGH', 4, ['CIS AWS 1.5', 'SOC 2'], 19),
  issue('rds_backup_retention_too_short', 'RDS backup retention is under 7 days', 'HIGH', 3, ['SOC 2', 'ISO 27001'], 11),
  issue('vpc_flow_logs_disabled', 'VPC flow logs are disabled', 'HIGH', 3, ['CIS AWS 1.5', 'PCI DSS 3.2.1'], 23),
  issue('efs_not_encrypted', 'EFS file system is not encrypted', 'HIGH', 2, ['HIPAA', 'ISO 27001'], 15),
  issue('lambda_env_contains_secret', 'Lambda environment variable looks like a secret', 'HIGH', 7, ['SOC 2'], 6),
  issue('ecr_image_scanning_disabled', 'ECR repository has image scanning disabled', 'HIGH', 8, ['SOC 2', 'ISO 27001'], 13),
  issue('cloudtrail_log_validation_disabled', 'CloudTrail log file validation is disabled', 'MEDIUM', 2, ['CIS AWS 1.5'], 12),
  issue('alb_access_logs_disabled', 'Load balancer access logging is disabled', 'MEDIUM', 4, ['SOC 2', 'ISO 27001'], 2),
  issue('lambda_runtime_deprecated', 'Lambda function uses a deprecated runtime', 'MEDIUM', 5, ['ISO 27001'], 1),
  issue('alb_listener_http_only', 'Load balancer listener accepts plaintext HTTP', 'MEDIUM', 5, ['PCI DSS 3.2.1'], 8),
  issue('cloudfront_tls_version_outdated', 'CloudFront distribution allows TLS 1.0', 'MEDIUM', 3, ['PCI DSS 3.2.1'], 16),
  issue('sqs_queue_encryption_disabled', 'SQS queue is not encrypted at rest', 'MEDIUM', 9, ['SOC 2'], 20),
  issue('sns_topic_encryption_disabled', 'SNS topic is not encrypted at rest', 'MEDIUM', 6, ['SOC 2'], 20),
  issue('dynamodb_pitr_disabled', 'DynamoDB point-in-time recovery is disabled', 'MEDIUM', 7, ['SOC 2', 'ISO 27001'], 18),
  issue('ecs_task_privileged_mode', 'ECS task definition runs in privileged mode', 'MEDIUM', 2, ['CIS AWS 1.5'], 10),
  issue('elasticache_transit_encryption_disabled', 'ElastiCache in-transit encryption is disabled', 'MEDIUM', 4, ['PCI DSS 3.2.1'], 22),
  issue('apigw_logging_disabled', 'API Gateway stage has execution logging disabled', 'MEDIUM', 5, ['SOC 2'], 7),
  issue('config_recorder_not_enabled', 'AWS Config recorder is not enabled in all regions', 'MEDIUM', 4, ['CIS AWS 1.5'], 25),
  issue('guardduty_not_enabled', 'GuardDuty is not enabled in every region', 'MEDIUM', 3, ['CIS AWS 1.5', 'SOC 2'], 27),
  issue('s3_bucket_versioning_disabled', 'S3 bucket versioning is disabled', 'LOW', 11, ['SOC 2'], 30),
  issue('resource_missing_owner_tag', 'Resource is missing a required Owner tag', 'LOW', 28, ['ISO 27001'], 30),
  issue('resource_missing_cost_centre_tag', 'Resource is missing a required CostCentre tag', 'LOW', 24, ['ISO 27001'], 30),
  issue('s3_lifecycle_policy_absent', 'S3 bucket has no lifecycle policy', 'LOW', 15, ['ISO 27001'], 29),
  issue('ec2_detailed_monitoring_disabled', 'EC2 instance has detailed monitoring disabled', 'LOW', 19, ['SOC 2'], 28),
  issue('iam_access_key_older_than_90_days', 'IAM access key is older than 90 days', 'LOW', 8, ['CIS AWS 1.5'], 24),
  issue('ec2_instance_missing_ssm_agent', 'EC2 instance is not managed by SSM', 'LOW', 12, ['SOC 2'], 26),
];

// ─── /api/xamsecure/issues/detail ────────────────────────────────────────────

const RESOURCE_KINDS = [
  'AWS_EC2_INSTANCE', 'AWS_S3_BUCKET', 'AWS_RDS_INSTANCE', 'AWS_SECURITY_GROUP',
  'AWS_EBS_VOLUME', 'AWS_LAMBDA_FUNCTION', 'AWS_IAM_USER', 'AWS_ECS_TASK',
] as const;

const RESOURCE_NAMES = [
  'prod-api-01', 'prod-api-02', 'prod-web-01', 'prod-web-02', 'prod-cache-01',
  'analytics-worker-1', 'analytics-worker-2', 'analytics-worker-3', 'etl-runner-01',
  'etl-runner-02', 'checkout-service-7d9f', 'billing-api-5c2a', 'search-indexer-2b81',
  'notification-worker-4c19', 'reporting-lambda', 'image-resizer-lambda',
  'xammer-prod-assets', 'xammer-logs-archive', 'xammer-public-exports',
  'xammer-legacy-backups', 'prod-orders-db', 'prod-billing-db', 'staging-orders-db',
  'prod-web-sg', 'bastion-sg', 'legacy-app-sg', 'svc-deploy', 'svc-backup',
] as const;

/** Builds the affected-resource table for a check, sized to its resourceCount. */
const resourcesFor = (check: string, count: number, severity: Severity) =>
  Array.from({ length: Math.min(count, 25) }, (_, i) => {
    const seed = check.length + i;
    const name = at(RESOURCE_NAMES, seed);
    return {
      resourceId: `${name}-${(seed * 7) % 97}`,
      resourceName: name,
      resourceType: at(RESOURCE_KINDS, seed),
      cloudProvider: 'AWS',
      cloudAccountId: at(ACCOUNT_IDS, seed),
      region: at(REGIONS, seed),
      severity: i % 4 === 3 ? 'MEDIUM' : severity,
      lastSeenTime: hoursAgo(spread(i, 2, 30)),
    };
  });

const REMEDIATION: Record<string, string> = {
  s3_bucket_public_read_acl:
    'Enable S3 Block Public Access at the account level, remove the AllUsers grant from the ' +
    'bucket ACL, and serve public content through CloudFront with an origin access identity.',
  sg_ingress_ssh_from_anywhere:
    'Restrict the rule to your VPN or corporate CIDR ranges, or replace direct SSH with AWS ' +
    'Systems Manager Session Manager and remove the rule entirely.',
  ebs_volume_not_encrypted:
    'Turn on EBS encryption by default for the region, then snapshot each volume, copy the ' +
    'snapshot with encryption enabled and re-attach the restored volume.',
  iam_root_access_key_present:
    'Delete the root access key and issue scoped IAM roles for programmatic access instead.',
  rds_instance_publicly_accessible:
    'Set PubliclyAccessible to false and reach the instance over a private subnet or VPN.',
  kms_key_rotation_disabled:
    'Enable automatic annual rotation on every customer-managed key.',
};

const DESCRIPTION: Record<string, string> = {
  s3_bucket_public_read_acl:
    'The bucket ACL or policy grants s3:GetObject to AllUsers, so any object in the bucket ' +
    'can be read anonymously from the internet.',
  sg_ingress_ssh_from_anywhere:
    'An inbound rule exposes TCP/22 to the entire internet, leaving the attached instances ' +
    'open to credential-stuffing and brute-force attempts.',
  ebs_volume_not_encrypted:
    'The volume was created without a KMS key, so snapshots and the underlying block storage ' +
    'hold plaintext data.',
};

export const demoIssueDetail = (check: string): IssueDetail => {
  const known = demoIssues.find((i) => i.check === check);
  const title = known?.title ?? check.replace(/_/g, ' ').replace(/^\w/, (c) => c.toUpperCase());
  const severity = known?.severity ?? 'MEDIUM';
  const resourceCount = known?.resourceCount ?? 3;
  return {
    title,
    severity,
    description: DESCRIPTION[check] ??
      `${title}. Detected by the continuous posture scan across every connected account; ` +
      'the affected resources are listed below with the account and region they live in.',
    remediation: REMEDIATION[check] ??
      'Review the affected resources and bring them in line with your organisation’s baseline ' +
      'configuration, then re-run the scan to confirm the finding clears.',
    frameworks: known?.frameworks ?? ['SOC 2'],
    firstSeen: known?.firstSeen ?? daysAgo(9),
    lastEvaluation: hoursAgo(4),
    resourceCount,
    resources: resourcesFor(check, resourceCount, severity),
  };
};

// ─── /api/xamsecure/attack-surface ───────────────────────────────────────────

const exposureFinding = (
  check: string, title: string, severity: Severity, resourceCount: number,
  frameworks: string[], firstSeenDays: number, resourceKind: string, impact: string,
): IssueSummary => ({
  check, title, severity, resourceCount, frameworks,
  firstSeen: daysAgo(firstSeenDays), lastEvaluation: hoursAgo(4),
  resourceKind, impact,
});

export const demoAttackSurface: AttackSurfaceSummary = {
  exposedResourceCount: 63,
  exposedByType: [
    { type: 'AWS_S3_BUCKET', count: 14 },
    { type: 'AWS_EC2_INSTANCE', count: 12 },
    { type: 'AWS_ALB', count: 9 },
    { type: 'AWS_ROUTE53_DNS_RECORD', count: 8 },
    { type: 'AWS_CLOUDFRONT_DISTRIBUTION', count: 6 },
    { type: 'AWS_API_GATEWAY', count: 5 },
    { type: 'AWS_CLASSIC_LOAD_BALANCER', count: 3 },
    { type: 'AWS_RDS_INSTANCE', count: 2 },
    { type: 'AWS_EFS', count: 2 },
    { type: 'AWS_ELASTICSEARCH_DOMAIN', count: 2 },
  ],
  exposedByLocation: [
    { country: 'India',          coordinates: [78.9629, 20.5937], count: 24 },
    { country: 'United States',  coordinates: [-95.7129, 37.0902], count: 18 },
    { country: 'Ireland',        coordinates: [-8.2439, 53.4129], count: 7 },
    { country: 'Germany',        coordinates: [10.4515, 51.1657], count: 5 },
    { country: 'Singapore',      coordinates: [103.8198, 1.3521], count: 4 },
    { country: 'Japan',          coordinates: [138.2529, 36.2048], count: 3 },
    { country: 'Australia',      coordinates: [133.7751, -25.2744], count: 2 },
  ],
  severityCounts: { CRITICAL: 9, HIGH: 24, MEDIUM: 21, LOW: 9 },
  findings: [
    exposureFinding('s3_bucket_public_read_acl', 'S3 bucket allows public read access', 'CRITICAL', 3, ['CIS AWS 1.5', 'SOC 2'], 21, 'AWS_S3_BUCKET', 'Data exposure'),
    exposureFinding('rds_instance_publicly_accessible', 'RDS instance is publicly accessible', 'CRITICAL', 2, ['CIS AWS 1.5', 'HIPAA'], 5, 'AWS_RDS_INSTANCE', 'Direct database access'),
    exposureFinding('elasticsearch_domain_public', 'Elasticsearch domain is reachable from the internet', 'CRITICAL', 2, ['SOC 2'], 12, 'AWS_ELASTICSEARCH_DOMAIN', 'Data exposure'),
    exposureFinding('eks_endpoint_public', 'EKS API server endpoint is public', 'CRITICAL', 2, ['CIS EKS 1.4'], 9, 'AWS_EC2_INSTANCE', 'Cluster takeover'),
    exposureFinding('sg_ingress_ssh_from_anywhere', 'Security group allows SSH (22) from 0.0.0.0/0', 'HIGH', 9, ['CIS AWS 1.5'], 30, 'AWS_EC2_INSTANCE', 'Remote access'),
    exposureFinding('sg_ingress_rdp_from_anywhere', 'Security group allows RDP (3389) from 0.0.0.0/0', 'HIGH', 3, ['CIS AWS 1.5'], 26, 'AWS_EC2_INSTANCE', 'Remote access'),
    exposureFinding('alb_listener_http_only', 'Load balancer listener accepts plaintext HTTP', 'HIGH', 5, ['PCI DSS 3.2.1'], 8, 'AWS_ALB', 'Traffic interception'),
    exposureFinding('apigw_no_authorizer', 'API Gateway route has no authoriser', 'HIGH', 5, ['SOC 2'], 7, 'AWS_API_GATEWAY', 'Unauthenticated access'),
    exposureFinding('cloudfront_tls_version_outdated', 'CloudFront distribution allows TLS 1.0', 'HIGH', 3, ['PCI DSS 3.2.1'], 16, 'AWS_CLOUDFRONT_DISTRIBUTION', 'Traffic interception'),
    exposureFinding('dns_record_points_to_unclaimed_bucket', 'DNS record points to an unclaimed origin', 'MEDIUM', 4, ['ISO 27001'], 6, 'AWS_ROUTE53_DNS_RECORD', 'Subdomain takeover'),
    exposureFinding('classic_lb_still_in_use', 'Classic load balancer still serving production traffic', 'MEDIUM', 3, ['ISO 27001'], 33, 'AWS_CLASSIC_LOAD_BALANCER', 'Unsupported component'),
    exposureFinding('s3_bucket_website_hosting_enabled', 'S3 static website hosting is enabled', 'MEDIUM', 6, ['SOC 2'], 19, 'AWS_S3_BUCKET', 'Data exposure'),
    exposureFinding('cloudfront_no_waf', 'CloudFront distribution has no WAF attached', 'MEDIUM', 6, ['SOC 2'], 14, 'AWS_CLOUDFRONT_DISTRIBUTION', 'Application attack'),
    exposureFinding('efs_mount_target_open', 'EFS mount target reachable from the public subnet', 'LOW', 2, ['SOC 2'], 16, 'AWS_EFS', 'Data exposure'),
    exposureFinding('alb_no_deletion_protection', 'Load balancer has deletion protection disabled', 'LOW', 4, ['ISO 27001'], 21, 'AWS_ALB', 'Availability'),
  ],
};

// ─── /api/xamsecure/threats ──────────────────────────────────────────────────

const THREAT_TEMPLATES: ReadonlyArray<readonly [string, string, string, string]> = [
  ['Crypto-mining binary executed in container', 'Malware', 'Execution, Impact', 'CRITICAL'],
  ['Reverse shell spawned from web process', 'Runtime', 'Execution, Command and Control', 'CRITICAL'],
  ['Known ransomware file extension written to disk', 'Malware', 'Impact', 'CRITICAL'],
  ['Container escape attempt via privileged syscall', 'Runtime', 'Privilege Escalation', 'CRITICAL'],
  ['IAM credentials read from instance metadata by unusual process', 'Credential Access', 'Credential Access', 'HIGH'],
  ['Outbound connection to known C2 endpoint', 'Network', 'Command and Control', 'HIGH'],
  ['Suspicious kubectl exec into a production pod', 'Runtime', 'Execution', 'HIGH'],
  ['Cloud API enumeration burst from a single principal', 'Discovery', 'Discovery', 'HIGH'],
  ['SSH brute-force attempt against a public host', 'Network', 'Credential Access', 'HIGH'],
  ['Package manager invoked inside a running container', 'Runtime', 'Defense Evasion', 'MEDIUM'],
  ['Sensitive file read: /etc/shadow', 'File Integrity', 'Credential Access', 'MEDIUM'],
  ['Audit log tampering detected', 'Defense Evasion', 'Defense Evasion', 'MEDIUM'],
  ['Unexpected binary written to /tmp and executed', 'Runtime', 'Execution', 'MEDIUM'],
  ['DNS query to a newly registered domain', 'Network', 'Command and Control', 'MEDIUM'],
  ['New listening port opened on a production host', 'Network', 'Persistence', 'LOW'],
  ['Scheduled task created by a non-admin user', 'Persistence', 'Persistence', 'LOW'],
  ['Shell history cleared on a production host', 'Defense Evasion', 'Defense Evasion', 'LOW'],
  ['User added to a privileged local group', 'Persistence', 'Privilege Escalation', 'MEDIUM'],
];

const WORKLOADS: ReadonlyArray<readonly [string, string]> = [
  ['prod-api-01', 'AWS_INSTANCE'], ['prod-api-02', 'AWS_INSTANCE'],
  ['prod-web-01', 'AWS_INSTANCE'], ['prod-web-02', 'AWS_INSTANCE'],
  ['prod-cache-01', 'AWS_INSTANCE'], ['analytics-worker-3', 'AWS_INSTANCE'],
  ['etl-runner-02', 'AWS_INSTANCE'], ['checkout-service-7d9f', 'AWS_ECS_TASK'],
  ['billing-api-5c2a', 'AWS_ECS_TASK'], ['search-indexer-2b81', 'AWS_ECS_TASK'],
  ['notification-worker-4c19', 'AWS_ECS_TASK'], ['reporting-lambda', 'AWS_LAMBDA_FUNCTION'],
  ['image-resizer-lambda', 'AWS_LAMBDA_FUNCTION'],
];

/** 64 detections — two pages at the table's 50-row size. */
export const demoThreatsAll: ThreatDetection[] = Array.from({ length: 64 }, (_, i) => {
  const [title, category, mitreTactics, severity] = at(THREAT_TEMPLATES, i);
  const [resourceName, resourceType] = at(WORKLOADS, i * 3);
  const lastSeen = spread(i, 1, 70);
  return {
    id: i + 1,
    upwindId: `thr-${(9000 + i * 137).toString(16)}`,
    category,
    severity,
    status: i % 11 === 0 ? 'ACKNOWLEDGED' : 'OPEN',
    title,
    cloudProvider: 'AWS',
    cloudAccountId: at(ACCOUNT_IDS, i),
    // Matches the id shape resourcesFor() builds, so the resource drawer joins
    // threats to configurations on the same key in demo mode.
    resourceId: `${resourceName}-${(i * 7) % 97}`,
    resourceName,
    resourceType,
    mitreTactics,
    occurrenceCount: spread(i, 1, 48),
    firstSeenTime: hoursAgo(lastSeen + spread(i, 4, 40)),
    lastSeenTime: hoursAgo(lastSeen),
    rawPayload: null,
  };
});

export const demoThreatsPage = (url: string): PageResp<ThreatDetection> =>
  paginate(bySeverity(demoThreatsAll, url), url);

// ─── /api/xamsecure/threat-policies ──────────────────────────────────────────

const openIssuesFor = (category: string): number =>
  demoThreatsAll.filter((t) => t.category === category && t.status === 'OPEN').length;

const threatPolicy = (
  id: string, displayName: string, category: string, scope: string,
  managedBy: string, enabled: boolean,
): ThreatPolicy => ({
  id, displayName, category, scope,
  openIssues: enabled ? openIssuesFor(category) : 0,
  managedBy, enabled,
});

export const demoThreatPolicies: ThreatPolicy[] = [
  threatPolicy('pol-001', 'Crypto-mining detection',           'Malware',           'All accounts', 'Upwind', true),
  threatPolicy('pol-002', 'Reverse shell detection',           'Runtime',           'All accounts', 'Upwind', true),
  threatPolicy('pol-003', 'Cloud credential theft',            'Credential Access', 'All accounts', 'Upwind', true),
  threatPolicy('pol-004', 'Known C2 communication',            'Network',           'Production',   'Upwind', true),
  threatPolicy('pol-005', 'Container drift',                   'Runtime',           'Production',   'Xammer', true),
  threatPolicy('pol-006', 'Sensitive file access',             'File Integrity',    'All accounts', 'Upwind', true),
  threatPolicy('pol-007', 'Anti-forensics and log tampering',  'Defense Evasion',   'All accounts', 'Upwind', true),
  threatPolicy('pol-008', 'Cloud API enumeration',             'Discovery',         'All accounts', 'Upwind', true),
  threatPolicy('pol-009', 'Persistence mechanism created',     'Persistence',       'All accounts', 'Upwind', true),
  threatPolicy('pol-010', 'Ransomware behaviour',              'Malware',           'All accounts', 'Upwind', true),
  threatPolicy('pol-011', 'Privilege escalation attempts',     'Privilege Escalation', 'All accounts', 'Upwind', true),
  threatPolicy('pol-012', 'Lateral movement detection',        'Lateral Movement',  'All accounts', 'Upwind', false),
  threatPolicy('pol-013', 'Data staging and exfiltration',     'Exfiltration',      'Production',   'Upwind', false),
  threatPolicy('pol-014', 'Suspicious package installation',   'Supply Chain',      'All accounts', 'Xammer', true),
  threatPolicy('pol-015', 'Kubernetes audit anomalies',        'Runtime',           'Production',   'Xammer', true),
  threatPolicy('pol-016', 'Unusual outbound data volume',      'Network',           'Production',   'Upwind', false),
  threatPolicy('pol-017', 'Serverless function tampering',     'Runtime',           'All accounts', 'Upwind', true),
  threatPolicy('pol-018', 'Secrets accessed outside a deploy', 'Credential Access', 'Production',   'Xammer', true),
];

// ─── /api/xamsecure/stories ──────────────────────────────────────────────────

export const demoThreatStories: ThreatStoriesSummary = {
  activeCount: 7,
  stories: [
    { id: 'story-01', title: 'Compromised container mining cryptocurrency',        severity: 'CRITICAL', status: 'ACTIVE', resourceName: 'checkout-service-7d9f' , createTime: '2026-08-02T21:08:07Z', updateTime: '2026-08-03T01:06:21Z', relatedEvents: 40, resolvedCount: 40 },
    { id: 'story-02', title: 'Credential access followed by outbound C2 traffic',  severity: 'CRITICAL', status: 'ACTIVE', resourceName: 'prod-api-01' , createTime: '2026-07-30T11:20:00Z', updateTime: '2026-08-03T09:14:02Z', relatedEvents: 22, resolvedCount: 22 },
    { id: 'story-03', title: 'Container escape attempt on the billing service',    severity: 'HIGH',     status: 'ACTIVE', resourceName: 'billing-api-5c2a' , createTime: '2026-07-29T04:41:13Z', updateTime: '2026-08-02T18:03:55Z', relatedEvents: 14, resolvedCount: 12 },
    { id: 'story-04', title: 'SSH brute force into lateral movement',              severity: 'HIGH',     status: 'ACTIVE', resourceName: 'prod-web-02' , createTime: '2026-07-28T23:12:44Z', updateTime: '2026-08-01T07:31:10Z', relatedEvents: 11, resolvedCount: 11 },
    { id: 'story-05', title: 'Cloud API enumeration from a stale access key',      severity: 'HIGH',     status: 'ACTIVE', resourceName: 'svc-deploy' , createTime: '2026-07-27T15:02:19Z', updateTime: '2026-07-31T20:48:06Z', relatedEvents: 9, resolvedCount: 7 },
    { id: 'story-06', title: 'Repeated container drift on the search indexer',     severity: 'MEDIUM',   status: 'ACTIVE', resourceName: 'search-indexer-2b81' , createTime: '2026-07-26T08:55:31Z', updateTime: '2026-07-30T12:22:40Z', relatedEvents: 6, resolvedCount: 6 },
    { id: 'story-07', title: 'Log tampering after a privileged shell session',     severity: 'MEDIUM',   status: 'ACTIVE', resourceName: 'analytics-worker-3' , createTime: '2026-07-25T19:37:02Z', updateTime: '2026-07-29T05:11:58Z', relatedEvents: 5, resolvedCount: 5 },
    { id: 'story-08', title: 'Suspicious package pulled during a build',           severity: 'MEDIUM',   status: 'RESOLVED', resourceName: 'etl-runner-02' , createTime: '2026-07-21T10:04:26Z', updateTime: '2026-07-24T16:40:12Z', relatedEvents: 4, resolvedCount: 4 },
    { id: 'story-09', title: 'Unexpected persistence via cron on a legacy host',   severity: 'LOW',      status: 'RESOLVED', resourceName: 'prod-cache-01' , createTime: '2026-07-18T02:19:47Z', updateTime: '2026-07-20T22:05:33Z', relatedEvents: 2, resolvedCount: 2 },
  ],
};

// ─── /api/xamsecure/threats/overview ────────────────────────────────────────────

/**
 * The Events stage is deliberately unavailable here too. Upwind's funnel starts from raw
 * sensor events; we sync detections, so the demo must not invent a number the real API
 * cannot return.
 */
export const demoThreatOverview: ThreatOverview = {
  detectionCount: 214,
  resourceCount: 47,
  storyCount: 7,
  eventCount: null,
  eventCountAvailable: false,
  eventCountReason:
    "Upwind's funnel counts raw sensor events. We sync detections, not events, "
    + 'so no comparable figure exists on our side.',
  bySeverity: { CRITICAL: 12, HIGH: 38, MEDIUM: 141, LOW: 23 },
  trend: [
    { date: '2026-07-30', critical: 1, high: 4, other: 18 },
    { date: '2026-07-31', critical: 0, high: 6, other: 21 },
    { date: '2026-08-01', critical: 2, high: 5, other: 17 },
    { date: '2026-08-02', critical: 4, high: 7, other: 24 },
    { date: '2026-08-03', critical: 3, high: 6, other: 26 },
    { date: '2026-08-04', critical: 1, high: 5, other: 22 },
    { date: '2026-08-05', critical: 1, high: 5, other: 19 },
  ],
  trendDays: 7,
  trendWindows: [7, 30, 90],
  detectionsInWindow: 214,
  byTactic: [
    { tactic: 'Defense Evasion', count: 86 },
    { tactic: 'Execution', count: 41 },
    { tactic: 'Credential Access', count: 33 },
    { tactic: 'Initial Access', count: 28 },
    { tactic: 'Persistence', count: 14 },
    { tactic: 'Lateral Movement', count: 9 },
  ],
  bySource: [
    { source: 'Sensor', count: 128 },
    { source: 'AWS CloudTrail', count: 74 },
    { source: 'Audit Logs', count: 12 },
  ],
};

// ─── /api/xamsecure/vulnerabilities/* ────────────────────────────────────────

const PACKAGES: ReadonlyArray<readonly [string, string, string | null]> = [
  ['openssl', 'apk', null], ['glibc', 'apk', null], ['libwebp', 'deb', null],
  ['curl', 'deb', null], ['zlib', 'apk', null], ['ncurses', 'apk', null],
  ['liblzma5', 'apk', null], ['busybox', 'apk', null], ['libxml2', 'deb', null],
  ['expat', 'deb', null], ['sqlite3', 'deb', null], ['perl', 'deb', null],
  ['python3', 'deb', 'Python'], ['urllib3', 'pip', 'Python'], ['requests', 'pip', 'Python'],
  ['setuptools', 'pip', 'Python'], ['cryptography', 'pip', 'Python'], ['pillow', 'pip', 'Python'],
  ['express', 'npm', 'Node.js'], ['lodash', 'npm', 'Node.js'], ['axios', 'npm', 'Node.js'],
  ['minimist', 'npm', 'Node.js'], ['semver', 'npm', 'Node.js'], ['ws', 'npm', 'Node.js'],
  ['log4j-core', 'maven', 'Java'], ['spring-web', 'maven', 'Spring'],
  ['jackson-databind', 'maven', 'Java'], ['commons-text', 'maven', 'Java'],
  ['netty-codec-http', 'maven', 'Java'], ['snakeyaml', 'maven', 'Java'],
  ['golang.org/x/net', 'go', 'Go'], ['github.com/gin-gonic/gin', 'go', 'Go'],
];

const VULN_CLASSES = [
  'remote code execution', 'heap buffer overflow', 'denial of service',
  'improper input validation', 'path traversal', 'integer overflow',
  'use-after-free', 'privilege escalation', 'information disclosure',
  'insecure deserialisation', 'authentication bypass', 'race condition',
  'regular-expression denial of service', 'XML external entity injection',
  'out-of-bounds read', 'improper certificate validation',
] as const;

/** Well-known CVEs lead the table; the rest are composed from the pools above. */
const NAMED_CVES: ReadonlyArray<readonly [string, string, Severity, number, number, boolean]> = [
  ['CVE-2024-3094',  'xz-utils backdoor in liblzma',                 'CRITICAL', 10.0, 0.94, true],
  ['CVE-2021-44228', 'Log4Shell — Apache Log4j2 RCE',                'CRITICAL', 10.0, 0.97, true],
  ['CVE-2024-21626', 'runc container escape via leaked file handle', 'CRITICAL', 8.6,  0.42, true],
  ['CVE-2022-42889', 'Text4Shell — Apache Commons Text RCE',         'CRITICAL', 9.8,  0.55, true],
  ['CVE-2023-4863',  'libwebp heap buffer overflow',                 'HIGH',     8.8,  0.66, true],
  ['CVE-2023-44487', 'HTTP/2 Rapid Reset denial of service',         'HIGH',     7.5,  0.81, true],
  ['CVE-2023-38545', 'curl SOCKS5 heap buffer overflow',             'HIGH',     8.8,  0.23, false],
  ['CVE-2022-22965', 'Spring4Shell — Spring Framework RCE',          'CRITICAL', 9.8,  0.89, true],
  ['CVE-2023-2650',  'OpenSSL object identifier denial of service',  'HIGH',     7.5,  0.12, false],
  ['CVE-2023-29491', 'ncurses local privilege escalation',           'MEDIUM',   7.8,  0.06, false],
  ['CVE-2024-27983', 'Node.js HTTP/2 server memory leak',            'MEDIUM',   5.3,  0.11, false],
  ['CVE-2022-40897', 'setuptools regular-expression denial of service', 'LOW',   5.9,  0.03, false],
  ['CVE-2023-45853', 'zlib MiniZip integer overflow',                'HIGH',     9.8,  0.19, false],
  ['CVE-2023-5678',  'OpenSSL DH key check denial of service',       'MEDIUM',   5.3,  0.04, false],
  ['CVE-2024-24790', 'Go net/netip unexpected IPv4-mapped behaviour', 'MEDIUM',  6.5,  0.08, false],
  ['CVE-2023-39325', 'Go HTTP/2 rapid stream reset',                 'HIGH',     7.5,  0.44, true],
];

const CVSS_BY_SEVERITY: Record<string, number> = { CRITICAL: 9.4, HIGH: 8.1, MEDIUM: 6.2, LOW: 3.9 };

/** 128 CVEs — three pages at 50 per page. */
export const demoCveRowsAll: CveGroupRow[] = [
  ...NAMED_CVES.map(([cveId, name, severity, cvssScore, epssScore, exploitable], i) => ({
    cveId, name, severity, cvssScore, epssScore, exploitable,
    // Runtime reachability thins out fast in real estates: most installed packages are never
    // loaded, and fewer still sit behind ingress. Measured shares were 93% in use, 37.9%
    // internet-exposed, 0.42% with a vulnerable function actually executing.
    packageInUse: i % 8 !== 0,
    internetExposed: i % 3 === 0,
    functionsInUse: i === 4,
    runtimeExposed: i % 8 !== 0 && i % 3 === 0,
    findingsCount: spread(i, 8, 96),
    resourceCount: spread(i, 3, 34),
    firstSeen: daysAgo(spread(i, 2, 30)),
    lastScan: hoursAgo(3),
  })),
  ...Array.from({ length: 112 }, (_, n) => {
    const i = n + NAMED_CVES.length;
    const [pkg] = at(PACKAGES, i);
    const severity = sevAt(i);
    const exploitable = i % 7 === 0;
    return {
      cveId: `CVE-${2023 + (i % 2)}-${10000 + i * 373}`,
      name: `${pkg} ${at(VULN_CLASSES, i)}`,
      severity,
      cvssScore: Math.round((CVSS_BY_SEVERITY[severity] - (i % 5) * 0.3) * 10) / 10,
      epssScore: Math.round(((i % 19) / 20) * 100) / 100,
      exploitable,
      packageInUse: i % 9 !== 0,
      internetExposed: i % 4 === 0,
      functionsInUse: false,
      runtimeExposed: i % 9 !== 0 && i % 4 === 0,
      findingsCount: spread(i, 1, 74),
      resourceCount: spread(i, 1, 28),
      firstSeen: daysAgo(spread(i, 1, 45)),
      lastScan: hoursAgo(3),
    };
  }),
];

/**
 * Honours the `view=runtime` saved view as the real endpoint does, so the demo shows the same
 * narrowing Upwind's Findings tab opens on. Both totals are reported before filtering, which is
 * what lets the toggle be labelled with its own count.
 */
export const demoVulnByCvePage = (url: string): PageResp<CveGroupRow> => {
  const runtimeOnly = qs(url).get('view') === 'runtime';
  const scoped = runtimeOnly ? demoCveRowsAll.filter((c) => c.runtimeExposed) : demoCveRowsAll;
  return {
    ...paginate(bySeverity(scoped, url), url),
    totalCveCount: demoCveRowsAll.length,
    runtimeExposedCount: demoCveRowsAll.filter((c) => c.runtimeExposed).length,
    view: runtimeOnly ? 'runtime' : 'all',
  };
};

/**
 * Per-CVE affected resources. Sized from the CVE's own resourceCount so the
 * drill-down agrees with the row that opened it, and paginated by the drawer.
 */
export const demoCveFindings = (cveId: string): CveResourceFinding[] => {
  const row = demoCveRowsAll.find((c) => c.cveId === cveId);
  const count = Math.min(row?.resourceCount ?? 6, 30);
  const seed = cveId.length;
  return Array.from({ length: count }, (_, i) => {
    const [resourceName, resourceType] = at(WORKLOADS, seed + i);
    const [pkg, , framework] = at(PACKAGES, seed + i * 3);
    const packageCount = 1 + ((seed + i) % 4);
    const fixAvailable = (seed + i) % 4 !== 0;
    return {
      resourceId: `${resourceName}-${((seed + i) * 7) % 97}`,
      resourceName,
      resourceType,
      region: at(REGIONS, seed + i),
      cloudProvider: 'AWS',
      cvssScore: String(row?.cvssScore ?? 7.5),
      exploitable: row?.exploitable ?? false,
      firstSeenTime: daysAgo(spread(i, 1, 30)),
      lastScanTime: hoursAgo(3),
      packageName: packageCount > 1 ? `${packageCount} packages` : pkg,
      packageCount,
      packageVersion: `${1 + (i % 4)}.${(i * 3) % 20}.${i % 9}`,
      inUse: (seed + i) % 3 !== 0,
      image: resourceType === 'AWS_ECS_TASK' ? `xammer/${resourceName.split('-')[0]}:1.${i % 30}.0` : null,
      fixAvailable,
      fixedInVersion: fixAvailable ? `${1 + (i % 4)}.${((i * 3) % 20) + 1}.0` : null,
      // Reachability tiers, deliberately sparse: exposure is common, executing functions rare.
      internetExposed: (seed + i) % 3 === 0,
      activeCommunication: (seed + i) % 6 === 0,
      functionsInUse: (seed + i) % 23 === 0,
      // A slice of the estate is Kubernetes, so the namespace hint has something to show.
      namespace: resourceType === 'AWS_ECS_TASK' ? at(['prod', 'staging'], seed + i) : null,
      clusterName: resourceType === 'AWS_ECS_TASK' ? 'xammer-prod-cluster' : null,
      imageTag: `1.${(seed + i) % 30}.0`,
      ...(framework ? {} : {}),
    };
  });
};

/** 240 individual findings — five pages at 50 per page. */
export const demoVulnerabilitiesAll: VulnerabilityFinding[] = Array.from({ length: 240 }, (_, i) => {
  const cve = at(demoCveRowsAll, i * 3);
  const [resourceName, resourceType] = at(WORKLOADS, i);
  return {
    id: i + 1,
    upwindId: `vln-${(i + 1).toString().padStart(5, '0')}`,
    cveId: cve.cveId,
    vulnerabilityName: cve.name,
    severity: cve.severity,
    cvssScore: String(cve.cvssScore),
    epssScore: String(cve.epssScore),
    exploitable: cve.exploitable,
    status: i % 13 === 0 ? 'ACCEPTED_RISK' : 'OPEN',
    cloudProvider: 'AWS',
    cloudAccountId: at(ACCOUNT_IDS, i),
    region: at(REGIONS, i),
    resourceName,
    resourceType,
    firstSeenTime: daysAgo(spread(i, 1, 45)),
    lastScanTime: hoursAgo(3),
    rawPayload: null,
  };
});

export const demoVulnerabilitiesPage = (url: string): PageResp<VulnerabilityFinding> =>
  paginate(bySeverity(demoVulnerabilitiesAll, url), url);

const cveSeverityCounts = demoCveRowsAll.reduce<Record<string, number>>((acc, c) => {
  const key = (c.severity ?? 'LOW').toUpperCase();
  acc[key] = (acc[key] ?? 0) + 1;
  return acc;
}, {});

const totalCveFindings = demoCveRowsAll.reduce((a, c) => a + c.findingsCount, 0);
const exploitableCves = demoCveRowsAll.filter((c) => c.exploitable).length;
const epssPrioritized = demoCveRowsAll.filter((c) => (c.epssScore ?? 0) >= 0.5).length;

/**
 * Cumulative day-over-day series. The charts stack CRITICAL/HIGH/OTHER per day
 * and the "new CVEs" bar chart looks up `overTime[YYYY-MM-DD]`, so both are
 * built from the same trailing-7-day key list.
 */
const buildVulnTrends = () => {
  const keys = last7Keys();
  const base = {
    CRITICAL: cveSeverityCounts.CRITICAL ?? 0,
    HIGH: cveSeverityCounts.HIGH ?? 0,
    OTHER: (cveSeverityCounts.MEDIUM ?? 0) + (cveSeverityCounts.LOW ?? 0),
  };
  const severityOverTime = keys.map((key, i) => ({
    date: key.slice(5),           // MM-DD reads better on a narrow axis
    CRITICAL: Math.round(base.CRITICAL * (0.82 + i * 0.03) * 3),
    HIGH: Math.round(base.HIGH * (0.85 + i * 0.025) * 4),
    OTHER: Math.round(base.OTHER * (0.88 + i * 0.02) * 5),
  }));
  const criticalWorkloadOverTime = keys.map((key, i) => ({
    date: key.slice(5),
    Hosts: 34 + i * 2,
    Containers: 21 + i * 3,
    Serverless: 9 + i,
  }));
  const newPerDay = [11, 6, 18, 9, 22, 14, 17];
  const overTime: Record<string, number> = {};
  keys.forEach((key, i) => { overTime[key] = newPerDay[i]; });
  return { severityOverTime, criticalWorkloadOverTime, overTime };
};

const vulnTrends = buildVulnTrends();

/**
 * Each funnel stage is a subset of the one before it, so the ribbon narrows left
 * to right. Clamping here rather than hand-tuning the numbers keeps the shape
 * correct even when the underlying counts move.
 */
const narrowing = (stages: ReadonlyArray<readonly [string, number]>) => {
  let ceiling = Number.POSITIVE_INFINITY;
  return stages.map(([label, value]) => {
    const clamped = Math.min(value, ceiling);
    ceiling = clamped;
    return { label, value: clamped, available: true };
  });
};

export const demoVulnDashboard: VulnerabilityDashboard = {
  totalCves: demoCveRowsAll.length,
  totalFindings: totalCveFindings,
  severityCounts: cveSeverityCounts,
  byWorkloadType: [
    { type: 'AWS_INSTANCE', count: Math.round(demoCveRowsAll.length * 0.44) },
    { type: 'AWS_ECS_TASK', count: Math.round(demoCveRowsAll.length * 0.33) },
    { type: 'AWS_LAMBDA_FUNCTION', count: Math.round(demoCveRowsAll.length * 0.15) },
    { type: 'OTHER', count: Math.round(demoCveRowsAll.length * 0.08) },
  ],
  exploitableCves,
  epssPrioritizedCves: epssPrioritized,
  newLast7Days: { CRITICAL: 9, HIGH: 24, OTHER: 31 },
  overTime: vulnTrends.overTime,
  severityOverTime: vulnTrends.severityOverTime,
  criticalWorkloadOverTime: vulnTrends.criticalWorkloadOverTime,
  workloadLabels: ['Hosts', 'Containers', 'Serverless'],
  funnel: narrowing([
    ['Total',             totalCveFindings],
    ['Package in use',    Math.round(totalCveFindings * 0.52)],
    ['Internet exposure', Math.round(totalCveFindings * 0.24)],
    ['Known exploit',     Math.round(totalCveFindings * 0.11)],
    ['Fix available',     Math.round(totalCveFindings * 0.08)],
    ['NIST critical sev', cveSeverityCounts.CRITICAL ?? 0],
    ['EPSS-prioritized',  epssPrioritized],
    ['Functions in Use',  Math.max(1, Math.round(totalCveFindings * 0.004))],
  ]),
  // Ratios mirror what was measured against the live API on 2026-08-04, so the demo
  // narrowing is as steep as the real thing: most CVEs are installed but unreachable.
  runtimeExposure: {
    packageInUse: Math.round(demoCveRowsAll.length * 0.93),
    internetExposed: Math.round(demoCveRowsAll.length * 0.379),
    exposedAndInUse: Math.round(demoCveRowsAll.length * 0.364),
    activeCommunication: Math.round(demoCveRowsAll.length * 0.162),
    functionsInUse: Math.max(1, Math.round(demoCveRowsAll.length * 0.004)),
  },
};

// ─── /api/xamsecure/posture ──────────────────────────────────────────────────

const POSTURE_RESOURCE_BY_CHECK: Record<string, readonly [string, string]> = {
  s3_bucket_public_read_acl: ['xammer-prod-assets', 'AWS_S3_BUCKET'],
  s3_bucket_versioning_disabled: ['xammer-logs-archive', 'AWS_S3_BUCKET'],
  s3_lifecycle_policy_absent: ['xammer-legacy-backups', 'AWS_S3_BUCKET'],
  iam_root_access_key_present: ['root', 'AWS_IAM_USER'],
  iam_user_mfa_not_enabled: ['svc-deploy', 'AWS_IAM_USER'],
  iam_policy_allows_full_admin: ['AdminAccessPolicy', 'AWS_IAM_POLICY'],
  iam_access_key_older_than_90_days: ['svc-backup', 'AWS_IAM_USER'],
  rds_instance_publicly_accessible: ['prod-orders-db', 'AWS_RDS_INSTANCE'],
  rds_backup_retention_too_short: ['staging-orders-db', 'AWS_RDS_INSTANCE'],
  sg_ingress_ssh_from_anywhere: ['prod-web-sg', 'AWS_SECURITY_GROUP'],
  sg_ingress_rdp_from_anywhere: ['legacy-app-sg', 'AWS_SECURITY_GROUP'],
  ebs_volume_not_encrypted: ['prod-api-root', 'AWS_EBS_VOLUME'],
  lambda_runtime_deprecated: ['reporting-lambda', 'AWS_LAMBDA_FUNCTION'],
  lambda_env_contains_secret: ['image-resizer-lambda', 'AWS_LAMBDA_FUNCTION'],
  alb_access_logs_disabled: ['prod-public-alb', 'AWS_ALB'],
  alb_listener_http_only: ['prod-public-alb', 'AWS_ALB'],
  cloudtrail_log_validation_disabled: ['xammer-org-trail', 'AWS_CLOUD_TRAIL'],
  vpc_flow_logs_disabled: ['prod-vpc', 'AWS_VPC'],
};

const FRAMEWORK_OF = (frameworks: string[]) => frameworks[0] ?? 'SOC 2';

/**
 * One posture row per affected resource, expanded from the grouped issues so the
 * Configurations table and the Issues table describe the same estate.
 * ~250 rows across five pages.
 */
export const demoPostureAll: PostureFinding[] = demoIssues.flatMap((iss, issueIndex) =>
  Array.from({ length: Math.min(iss.resourceCount, 12) }, (_, r) => {
    const seed = issueIndex * 13 + r;
    const [fallbackName, fallbackType] = [at(RESOURCE_NAMES, seed), at(RESOURCE_KINDS, seed)];
    const [name, type] = POSTURE_RESOURCE_BY_CHECK[iss.check] ?? [fallbackName, fallbackType];
    return {
      id: seed + 1,
      upwindId: `pst-${(seed + 1).toString().padStart(5, '0')}`,
      resourceId: `${name}-${(seed * 7) % 97}`,
      severity: iss.severity,
      status: 'OPEN',
      title: iss.title,
      frameworkTitle: FRAMEWORK_OF(iss.frameworks),
      checkTitle: iss.check,
      cloudProvider: 'AWS',
      cloudAccountId: at(ACCOUNT_IDS, seed),
      region: at(REGIONS, seed),
      resourceName: r === 0 ? name : `${name}-${r}`,
      resourceType: type,
      // A slice of the estate is Kubernetes, so the cluster/namespace column has something
      // to show. Cloud resources keep these null, as the real payload does.
      clusterId: seed % 9 === 0 ? '4122e4ce-1ca2-408a-8fb9-fff348a8c525' : null,
      namespace: seed % 9 === 0 ? at(['prod', 'staging', 'analytics'], seed) : null,
      firstSeenTime: iss.firstSeen,
      lastSeenTime: hoursAgo(4),
      rawPayload: null,
    };
  })
);

export const demoPosturePage = (url: string): PageResp<PostureFinding> =>
  paginate(bySeverity(demoPostureAll, url), url);

// ─── /api/xamsecure/inventory ────────────────────────────────────────────────

const ASSET_SHAPES: ReadonlyArray<readonly [string, string, string, string]> = [
  // category, sub-category, resource type, name prefix
  ['compute',    'instance',        'AWS_EC2_INSTANCE',       'ec2'],
  ['compute',    'instance',        'AWS_EC2_INSTANCE',       'worker'],
  ['compute',    'container',       'AWS_ECS_TASK',           'svc'],
  ['compute',    'container',       'AWS_EKS_POD',            'pod'],
  ['compute',    'auto-scaling',    'AWS_AUTOSCALING_GROUP',  'asg'],
  ['serverless', 'function',        'AWS_LAMBDA_FUNCTION',    'fn'],
  ['serverless', 'step-function',   'AWS_STEP_FUNCTION',      'flow'],
  ['storage',    'object-storage',  'AWS_S3_BUCKET',          'xammer-bucket'],
  ['storage',    'block-storage',   'AWS_EBS_VOLUME',         'vol'],
  ['storage',    'file-storage',    'AWS_EFS',                'efs'],
  ['storage',    'backup',          'AWS_BACKUP_VAULT',       'vault'],
  ['database',   'relational',      'AWS_RDS_INSTANCE',       'rds'],
  ['database',   'nosql',           'AWS_DYNAMODB_TABLE',     'ddb'],
  ['database',   'cache',           'AWS_ELASTICACHE',        'cache'],
  ['database',   'warehouse',       'AWS_REDSHIFT_CLUSTER',   'dwh'],
  ['networking', 'vpc',             'AWS_VPC',                'vpc'],
  ['networking', 'subnet',          'AWS_SUBNET',             'subnet'],
  ['networking', 'security-group',  'AWS_SECURITY_GROUP',     'sg'],
  ['networking', 'load-balancer',   'AWS_ALB',                'alb'],
  ['networking', 'nat-gateway',     'AWS_NAT_GATEWAY',        'nat'],
  ['networking', 'internet-gateway', 'AWS_INTERNET_GATEWAY',  'igw'],
  ['networking', 'dns',             'AWS_ROUTE53_ZONE',       'zone'],
  ['identity',   'user',            'AWS_IAM_USER',           'user'],
  ['identity',   'role',            'AWS_IAM_ROLE',           'role'],
  ['identity',   'policy',          'AWS_IAM_POLICY',         'policy'],
  ['identity',   'secret',          'AWS_SECRETS_MANAGER_SECRET', 'secret'],
  ['messaging',  'queue',           'AWS_SQS_QUEUE',          'queue'],
  ['messaging',  'topic',           'AWS_SNS_TOPIC',          'topic'],
];

const ENVIRONMENTS = ['prod', 'staging', 'dev', 'analytics'] as const;

/** 224 assets — five pages at 50 per page, spread across eight categories. */
export const demoInventoryAll: InventoryAsset[] = Array.from({ length: 224 }, (_, i) => {
  const [assetCategory, assetSubCategory, resourceType, prefix] = at(ASSET_SHAPES, i);
  const env = at(ENVIRONMENTS, Math.floor(i / 7));
  const name = `${env}-${prefix}-${String((i % 60) + 1).padStart(2, '0')}`;
  const provider = i % 11 === 0 ? 'GCP' : i % 17 === 0 ? 'Azure' : 'AWS';
  return {
    id: i + 1,
    upwindId: `inv-${(i + 1).toString().padStart(5, '0')}`,
    assetCategory,
    assetSubCategory,
    label: resourceType,
    cloudProvider: provider,
    cloudAccountId: at(ACCOUNT_IDS, i),
    cloudResourceId: `${resourceType.toLowerCase()}/${name}`,
    name,
    region: at(REGIONS, i),
    status: i % 23 === 0 ? 'STOPPED' : 'ACTIVE',
    rawPayload: null,
    // Catalog risk signals. Sparse on purpose: most assets carry none, which is what makes
    // the ones that do worth surfacing.
    hasElevatedPrivilege: assetCategory === 'identity' && i % 3 === 0,
    sensitivePiiAtRest: assetCategory === 'storage' && i % 5 === 0,
    sensitivePhiAtRest: false,
    sensitivePciAtRest: assetCategory === 'database' && i % 9 === 0,
    sensitivePiiInTransit: assetCategory === 'compute_platform' && i % 13 === 0,
    sensitivePhiInTransit: false,
    sensitivePciInTransit: false,
    vulnCriticalCount: i % 7 === 0 ? (i % 4) : 0,
    vulnHighCount: i % 5 === 0 ? (i % 9) : 0,
    vulnTotalCount: i % 3 === 0 ? (i % 24) : 0,
    detectionTotalCount: i % 11 === 0 ? (i % 5) : 0,
    internetExposureType: i % 8 === 0 ? 'direct' : null,
    lastInternetCommunication: i % 8 === 0 ? hoursAgo(spread(i, 1, 40)) : null,
    technologies: i % 6 === 0 ? `openssl 1.0.2 (EOL), nginx 1.${i % 26}` : null,
    hasEolTechnology: i % 6 === 0,
  };
});

/** Rollup derived from the assets above, so the strip agrees with the table. */
export const demoInventoryRisk: InventoryRisk = {
  assets: demoInventoryAll.length,
  pii: demoInventoryAll.filter((a) => a.sensitivePiiAtRest || a.sensitivePiiInTransit).length,
  phi: 0,
  pci: demoInventoryAll.filter((a) => a.sensitivePciAtRest).length,
  elevatedPrivilege: demoInventoryAll.filter((a) => a.hasElevatedPrivilege).length,
  eolTechnology: demoInventoryAll.filter((a) => a.hasEolTechnology).length,
  internetExposed: demoInventoryAll.filter((a) => a.internetExposureType !== null).length,
};

/** Counts come from the assets themselves, so the rail agrees with the table. */
export const demoInventoryCategories: InventoryCategory[] = Object.entries(
  demoInventoryAll.reduce<Record<string, number>>((acc, a) => {
    const key = a.assetCategory ?? 'uncategorized';
    acc[key] = (acc[key] ?? 0) + 1;
    return acc;
  }, {})
)
  .map(([category, count]) => ({ category, count }))
  .sort((a, b) => b.count - a.count);

export const demoInventoryPage = (url: string): PageResp<InventoryAsset> => {
  const category = qs(url).get('category');
  const filtered = category
    ? demoInventoryAll.filter((a) => a.assetCategory === category)
    : demoInventoryAll;
  return paginate(filtered, url);
};

const networkNodes: NetworkNode[] = Object.entries(
  demoInventoryAll
    .filter((a) => a.assetCategory === 'networking')
    .reduce<Record<string, number>>((acc, a) => {
      const key = a.assetSubCategory ?? 'other';
      acc[key] = (acc[key] ?? 0) + 1;
      return acc;
    }, {})
)
  .map(([subCategory, count]) => ({ subCategory, count }))
  .sort((a, b) => b.count - a.count);

export const demoNetworkTopology: NetworkTopology = {
  accountId: ACCOUNT_IDS[0],
  accounts: ACCOUNT_IDS.length,
  nodes: networkNodes,
};

// ─── /api/xamsecure/compliance ───────────────────────────────────────────────

const framework = (
  name: string, failCount: number, passCount: number, version: string, scanHoursAgo: number,
): ComplianceFramework => ({
  framework: name, failCount, passCount, version, status: 'ACTIVE',
  lastScanTime: hoursAgo(scanHoursAgo),
  score: Math.round((passCount * 1000) / (passCount + failCount)) / 10,
});

export const demoCompliance: ComplianceFramework[] = [
  framework('CIS AWS Foundations', 24, 168, '1.5.0', 4),
  framework('SOC 2',               18, 142, '2017',  4),
  framework('ISO 27001',           31, 205, '2022',  5),
  framework('PCI DSS',             12, 96,  '3.2.1', 5),
  framework('HIPAA',                9, 74,  '2013',  6),
  framework('NIST CSF',            27, 181, '2.0',   6),
  framework('GDPR',                14, 88,  '2016',  7),
  framework('CIS EKS',             11, 63,  '1.4.0', 7),
  framework('AWS Well-Architected', 19, 124, '2023', 8),
];

// ─── /api/xamsecure/configurations/dashboard ─────────────────────────────────

const postureSeverityCounts = demoPostureAll.reduce<Record<string, number>>((acc, p) => {
  const key = (p.severity ?? 'LOW').toUpperCase();
  acc[key] = (acc[key] ?? 0) + 1;
  return acc;
}, {});

const avgComplianceScore =
  Math.round((demoCompliance.reduce((a, f) => a + (f.score ?? 0), 0) / demoCompliance.length) * 10) / 10;

export const demoConfigDashboard: ConfigurationsDashboard = {
  severityCounts: postureSeverityCounts,
  totalFindings: demoPostureAll.length,
  newLast7Days: demoIssues.filter((i) => {
    const seen = i.firstSeen ? Date.parse(i.firstSeen) : 0;
    return Date.now() - seen <= 7 * MS_DAY;
  }).length,
  topFindings: demoIssues.slice(0, 8),
  findingsOverTime: last7Keys().map((key, i) => ({
    date: key.slice(5),
    HIGH: Math.round(((postureSeverityCounts.CRITICAL ?? 0) + (postureSeverityCounts.HIGH ?? 0)) * (0.86 + i * 0.023)),
    OTHER: Math.round(((postureSeverityCounts.MEDIUM ?? 0) + (postureSeverityCounts.LOW ?? 0)) * (0.89 + i * 0.018)),
  })),
  frameworkScores: demoCompliance,
  avgComplianceScore,
  frameworkCount: demoCompliance.length,
};

// ─── /api/xamsecure/api-security ─────────────────────────────────────────────

const API_ROUTES: ReadonlyArray<readonly [string, string, string, boolean, boolean]> = [
  // method, uri, owning resource, internet exposed, authenticated
  ['POST',   '/api/v1/checkout',              'checkout-service-7d9f',    true,  true],
  ['GET',    '/api/v1/checkout/{id}',         'checkout-service-7d9f',    true,  true],
  ['POST',   '/api/v1/checkout/refund',       'checkout-service-7d9f',    true,  true],
  ['GET',    '/api/v1/orders',                'prod-api-01',              true,  true],
  ['GET',    '/api/v1/orders/{id}',           'prod-api-01',              true,  true],
  ['PUT',    '/api/v1/orders/{id}',           'prod-api-01',              true,  true],
  ['DELETE', '/api/v1/orders/{id}',           'prod-api-01',              true,  true],
  ['POST',   '/api/v1/billing/invoices',      'billing-api-5c2a',         true,  true],
  ['GET',    '/api/v1/billing/invoices/{id}', 'billing-api-5c2a',         true,  true],
  ['GET',    '/api/v1/billing/usage',         'billing-api-5c2a',         true,  true],
  ['POST',   '/api/v1/billing/webhooks',      'billing-api-5c2a',         true,  false],
  ['GET',    '/api/v1/accounts',              'prod-api-02',              true,  true],
  ['PUT',    '/api/v1/accounts/{id}',         'prod-api-02',              true,  true],
  ['POST',   '/api/v1/accounts/invite',       'prod-api-02',              true,  true],
  ['POST',   '/api/v1/auth/login',            'prod-api-02',              true,  false],
  ['POST',   '/api/v1/auth/refresh',          'prod-api-02',              true,  true],
  ['DELETE', '/api/v1/sessions',              'prod-api-02',              true,  true],
  ['GET',    '/api/v1/search',                'search-indexer-2b81',      true,  true],
  ['POST',   '/api/v1/search/reindex',        'search-indexer-2b81',      false, false],
  ['GET',    '/api/v1/reports/export',        'reporting-lambda',         true,  true],
  ['POST',   '/api/v1/reports/schedule',      'reporting-lambda',         true,  true],
  ['POST',   '/api/v1/notifications/send',    'notification-worker-4c19', false, true],
  ['GET',    '/api/v1/notifications/status',  'notification-worker-4c19', false, true],
  ['POST',   '/api/v1/images/resize',         'image-resizer-lambda',     true,  false],
  ['GET',    '/api/v1/catalogue',             'prod-web-01',              true,  false],
  ['GET',    '/api/v1/catalogue/{sku}',       'prod-web-01',              true,  false],
  ['GET',    '/health',                       'prod-public-alb',          true,  false],
  ['GET',    '/ready',                        'prod-public-alb',          true,  false],
  ['GET',    '/metrics',                      'prod-api-01',              true,  false],
  ['GET',    '/debug/pprof',                  'prod-api-01',              true,  false],
  ['POST',   '/internal/reindex',             'analytics-worker-3',       false, false],
  ['POST',   '/internal/etl/trigger',         'etl-runner-02',            false, true],
  ['GET',    '/internal/etl/status',          'etl-runner-02',            false, true],
  ['GET',    '/api/v2/analytics/events',      'analytics-worker-3',       true,  true],
  ['POST',   '/api/v2/analytics/events',      'analytics-worker-3',       true,  true],
  ['GET',    '/api/v2/analytics/dashboards',  'analytics-worker-3',       true,  true],
  ['POST',   '/api/v1/support/tickets',       'prod-api-02',              true,  true],
  ['GET',    '/api/v1/support/tickets/{id}',  'prod-api-02',              true,  true],
  ['POST',   '/api/v1/files/upload',          'prod-api-01',              true,  true],
  ['GET',    '/api/v1/files/{id}',            'prod-api-01',              true,  true],
];

export const demoApiSecurity: ApiEndpoint[] = API_ROUTES.map(
  ([method, uri, resourceId, internetExposed, authenticated], i) => ({
    id: `api-${String(i + 1).padStart(2, '0')}`,
    method, uri, resourceId,
    firstSeenTime: daysAgo(spread(i, 3, 60)),
    lastSeenTime: hoursAgo(spread(i, 1, 12)),
    internetExposed,
    authState: authenticated ? 'AUTHENTICATED' : 'UNAUTHENTICATED',
    authenticated,
    domains: internetExposed
      ? [uri.startsWith('/api/v2') ? 'analytics.xammer.in' : 'api.xammer.in']
      : ['internal.xammer.in'],
    cloudAccountId: at(ACCOUNT_IDS, i),
    // Sensitive data is what Upwind observed in the traffic, so it clusters on the
    // routes that actually carry user records rather than being spread evenly.
    sensitiveData: uri.includes('/users') || uri.includes('/profile') || uri.includes('/support')
      ? ['PII:Email', 'PII:Name']
      : uri.includes('/payments') || uri.includes('/billing')
        ? ['PCI:CardNumber']
        : [],
    hasSensitiveData: uri.includes('/users') || uri.includes('/profile')
      || uri.includes('/support') || uri.includes('/payments') || uri.includes('/billing'),
    statusCodes: authenticated ? ['200', '401'] : ['200'],
  })
);

// ─── /api/xamsecure/components ───────────────────────────────────────────────

const LICENSES = [
  ['MIT'], ['Apache-2.0'], ['BSD-3-Clause'], ['GPL-2.0'], ['ISC'],
  ['MIT', 'Apache-2.0'], ['LGPL-2.1'], ['MPL-2.0'], ['BSD-2-Clause'],
] as const;

/** One row per package, with vulnerability counts that add up to totalVulns. */
export const demoComponents: SbomComponent[] = PACKAGES.flatMap(([name, packageManager, framework], p) =>
  // two versions of each package in the estate — an old one and a current one
  [0, 1].map((v) => {
    const i = p * 2 + v;
    const stale = v === 0;
    const critical = stale ? (i % 3 === 0 ? 1 : 0) : 0;
    const high = stale ? 1 + (i % 3) : (i % 5 === 0 ? 1 : 0);
    const medium = stale ? 1 + (i % 4) : i % 3;
    const low = stale ? i % 3 : i % 2;
    return {
      name,
      version: stale
        ? `${1 + (i % 3)}.${(i * 2) % 18}.${i % 7}`
        : `${2 + (i % 3)}.${((i * 2) % 18) + 2}.${(i % 7) + 1}`,
      packageManager,
      framework,
      licenses: [...at(LICENSES, i)],
      cloudProvider: 'AWS',
      cloudAccountId: at(ACCOUNT_IDS, i),
      critical, high, medium, low,
      totalVulns: critical + high + medium + low,
      inUse: spread(i, 0, 26),
      resourceCount: spread(i, 2, 41),
      affectedImages: spread(i, 1, 15),
    };
  })
);

// ─── /api/xamsecure/workflows ────────────────────────────────────────────────

const workflow = (
  id: string, name: string, type: string, status: string,
  lastRunHoursAgo: number, cron: string | null, actions: string[],
): Workflow => ({
  id, name, type, status,
  lastExecutionTime: hoursAgo(lastRunHoursAgo), cron, actions,
});

export const demoWorkflows: Workflow[] = [
  workflow('wf-01', 'Critical threat → PagerDuty',            'EVENT',    'ENABLED',  2,   null,          ['pagerduty', 'slack']),
  workflow('wf-02', 'New CVE digest → Slack',                 'SCHEDULE', 'ENABLED',  9,   '0 9 * * *',   ['slack']),
  workflow('wf-03', 'Public S3 bucket → Jira ticket',         'EVENT',    'ENABLED',  26,  null,          ['jira']),
  workflow('wf-04', 'Weekly compliance report → email',       'SCHEDULE', 'ENABLED',  72,  '0 8 * * MON', ['email']),
  workflow('wf-05', 'Container drift → auto-quarantine',      'EVENT',    'DISABLED', 264, null,          ['webhook']),
  workflow('wf-06', 'Exploitable CVE → Jira + Slack',         'EVENT',    'ENABLED',  5,   null,          ['jira', 'slack']),
  workflow('wf-07', 'Daily posture drift digest',             'SCHEDULE', 'ENABLED',  15,  '0 7 * * *',   ['email', 'slack']),
  workflow('wf-08', 'Failed compliance control → ServiceNow', 'EVENT',    'ENABLED',  40,  null,          ['servicenow']),
  workflow('wf-09', 'New internet-exposed asset → Slack',     'EVENT',    'ENABLED',  11,  null,          ['slack']),
  workflow('wf-10', 'Monthly executive summary → email',      'SCHEDULE', 'ENABLED',  200, '0 9 1 * *',   ['email']),
  workflow('wf-11', 'Credential theft → isolate instance',    'EVENT',    'DISABLED', 480, null,          ['webhook', 'pagerduty']),
  workflow('wf-12', 'SBOM export → S3 archive',               'SCHEDULE', 'ENABLED',  48,  '0 2 * * SUN', ['s3']),
];

// ─── /api/xamsecure/onboarding/status ────────────────────────────────────────
// Mirrors the demo cloud accounts in xamopsMockData so the two pages agree.

const HELM_INSTALL =
  'helm upgrade --install upwind-sensor upwind/sensor \\\n' +
  '  --namespace upwind --create-namespace \\\n' +
  '  --set clientId=demo-client-id --set clientSecret=<SECRET>';

export const demoOnboarding: OnboardingAccount[] = [
  { accountName: 'Xammer Production', cloudAccountId: 'demo-aws-001',   provider: 'AWS',   status: 'CONNECTED',     sensorInstallCommand: null },
  { accountName: 'Xammer Staging',    cloudAccountId: 'demo-aws-002',   provider: 'AWS',   status: 'CONNECTED',     sensorInstallCommand: null },
  { accountName: 'GCP Analytics',     cloudAccountId: 'demo-gcp-001',   provider: 'GCP',   status: 'PENDING',       sensorInstallCommand: HELM_INSTALL },
  { accountName: 'Azure Enterprise',  cloudAccountId: 'demo-azure-001', provider: 'Azure', status: 'NOT_CONNECTED', sensorInstallCommand: null },
];

// ─── /api/xamsecure/summary ──────────────────────────────────────────────────

const threatSeverityCounts = demoThreatsAll.reduce<Record<string, number>>((acc, t) => {
  const key = (t.severity ?? 'LOW').toUpperCase();
  acc[key] = (acc[key] ?? 0) + 1;
  return acc;
}, {});

export const demoXamSecureSummary: XamSecureSummary = {
  threatsBySeverity: threatSeverityCounts,
  vulnerabilitiesBySeverity: cveSeverityCounts,
  postureBySeverity: postureSeverityCounts,
  syncState: [
    { domain: 'threats',         lastSuccessAt: hoursAgo(1), lastError: null },
    { domain: 'vulnerabilities', lastSuccessAt: hoursAgo(3), lastError: null },
    { domain: 'posture',         lastSuccessAt: hoursAgo(4), lastError: null },
    { domain: 'inventory',       lastSuccessAt: hoursAgo(4), lastError: null },
    { domain: 'compliance',      lastSuccessAt: hoursAgo(6), lastError: null },
    { domain: 'api-security',    lastSuccessAt: hoursAgo(2), lastError: null },
    { domain: 'components',      lastSuccessAt: hoursAgo(5), lastError: null },
  ],
};
