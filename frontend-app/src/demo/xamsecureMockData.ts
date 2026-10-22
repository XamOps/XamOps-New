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

import { SEVERITY_ORDER } from '../types/xamsecure';
import type {
  ApiEndpoint, AttackSurfaceSummary, ComplianceFramework, ConfigurationsDashboard,
  CveGroupRow, CveResourceFinding, InventoryAsset, InventoryCategory, InventoryRisk, IssueDetail,
  ConfigGraph, TopologyNode, TopologyEdge, ThreatStoryDetail, StoryDetection, SensorCoverage,
  IssueSummary, NetworkTopology, NetworkNode, OnboardingAccount, PageResp, PostureFinding,
  IdentitiesSummary, IdentityRow, PrivilegedIdentity,
  SbomComponent, Severity, ThreatDetection, ThreatPolicy, ThreatStoriesSummary, ThreatOverview,
  CodeScan, CodeScansResponse, CodeScanFindings, CodeCve,
  VulnGroupPage, VulnGroupRow, VulnGroupDimension, ThreatGroups, ThreatGroupRow, ThreatGroupDimension,
  ResourceDetail,
  VulnerabilityDashboard, VulnerabilityFinding, Workflow, XamSecureSummary,
  FrameworkControl, FrameworkControls, AssetRelationships,
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

// ─── Provider scoping ────────────────────────────────────────────────────────
// XamSecure's real endpoints take `accountId` and nothing else: XamSecureService.resolveAccountIds
// turns it into "the AWS account / GCP project / Azure subscription this client owns", and every
// synced row carries a cloud_provider that Upwind reported. So selecting the GCP project returns
// GCP assets — a GCP account showing EC2 instances is a demo-only artefact, not the product.
//
// The demo previously ignored accountId altogether and served one all-AWS estate to every
// account, with ~33 of the 224 inventory rows randomly stamped 'GCP'/'Azure' while keeping an
// AWS label, AWS region, AWS account id and an `aws_ec2_instance/...` resource id. That is what
// produced rows reading "provider: GCP" next to "aws_ec2_instance/prod-ec2-01".

export type EstateProvider = 'AWS' | 'GCP' | 'Azure';

/** Demo account id → provider, matching demoAccounts in xamopsMockData. */
const PROVIDER_BY_DEMO_ACCOUNT: Record<string, EstateProvider> = {
  'demo-aws-001': 'AWS',
  'demo-aws-002': 'AWS',
  'demo-gcp-001': 'GCP',
  'demo-azure-001': 'Azure',
};

/**
 * Provider for the selected account. `accountId` may be a comma-separated list (the page joins
 * multi-select), so the first recognised id wins; an unknown or absent id falls back to AWS,
 * which keeps every existing caller and the AWS demo account on exactly the data they had.
 */
export const providerForUrl = (url: string): EstateProvider => {
  const raw = qs(url).get('accountId');
  if (!raw) return 'AWS';
  for (const id of raw.split(',')) {
    const hit = PROVIDER_BY_DEMO_ACCOUNT[id.trim()];
    if (hit) return hit;
  }
  return 'AWS';
};

const GCP_REGIONS = [
  'us-central1', 'europe-west1', 'asia-south1', 'us-east1', 'europe-west4', 'us-west1',
] as const;
const GCP_PROJECT_IDS = ['demo-gcp-001', 'xammer-analytics-prod', 'xammer-data-lake'] as const;

const AZURE_REGIONS = [
  'eastus', 'westeurope', 'centralindia', 'southeastasia', 'uksouth', 'westus2',
] as const;
const AZURE_SUBSCRIPTION_IDS = [
  'demo-azure-001', '8f4a2b1c-7d3e-4a91-b6c2-1e5f9a0d3b47', 'c2d7e019-4b6a-4f8d-9e13-72a5b8c4f601',
] as const;

const REGIONS_BY_PROVIDER: Record<EstateProvider, readonly string[]> = {
  AWS: REGIONS, GCP: GCP_REGIONS, Azure: AZURE_REGIONS,
};
const ACCOUNTS_BY_PROVIDER: Record<EstateProvider, readonly string[]> = {
  AWS: ACCOUNT_IDS, GCP: GCP_PROJECT_IDS, Azure: AZURE_SUBSCRIPTION_IDS,
};

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

/**
 * The same estate expressed in GCP's own terms. Checks, resource kinds and frameworks are
 * GCP-native — a GCP project reporting `s3_bucket_public_read_acl` against `CIS AWS 1.5` was the
 * same inaccuracy as the inventory rows, one layer down.
 */
export const demoIssuesGcp: IssueSummary[] = [
  issue('storage_bucket_public_access', 'Cloud Storage bucket is publicly accessible', 'CRITICAL', 3, ['CIS GCP 2.0', 'SOC 2', 'PCI DSS 3.2.1'], 21),
  issue('service_account_user_managed_key', 'Service account has a user-managed key', 'CRITICAL', 2, ['CIS GCP 2.0', 'SOC 2'], 14),
  issue('cloudsql_instance_public_ip', 'Cloud SQL instance is reachable on a public IP', 'CRITICAL', 2, ['CIS GCP 2.0', 'HIPAA'], 5),
  issue('secret_in_cloud_run_env', 'Plaintext secret in a Cloud Run environment variable', 'CRITICAL', 4, ['CIS GCP 2.0', 'SOC 2'], 3),
  issue('gke_control_plane_public', 'GKE control plane endpoint is public', 'CRITICAL', 2, ['CIS GKE 1.4', 'SOC 2'], 9),
  issue('kms_key_rotation_over_90_days', 'KMS key rotation period exceeds 90 days', 'CRITICAL', 5, ['CIS GCP 2.0', 'PCI DSS 3.2.1'], 17),
  issue('firewall_allows_ssh_from_anywhere', 'Firewall rule allows SSH (22) from 0.0.0.0/0', 'HIGH', 9, ['CIS GCP 2.0', 'PCI DSS 3.2.1'], 30),
  issue('firewall_allows_rdp_from_anywhere', 'Firewall rule allows RDP (3389) from 0.0.0.0/0', 'HIGH', 3, ['CIS GCP 2.0'], 26),
  issue('persistent_disk_no_cmek', 'Persistent disk is not encrypted with a customer-managed key', 'HIGH', 17, ['SOC 2', 'HIPAA', 'ISO 27001'], 30),
  issue('iam_primitive_role_at_project', 'Primitive role roles/editor granted at project level', 'HIGH', 6, ['CIS GCP 2.0', 'SOC 2'], 3),
  issue('iam_service_account_token_creator', 'Service account granted serviceAccountTokenCreator broadly', 'HIGH', 4, ['CIS GCP 2.0', 'SOC 2'], 19),
  issue('cloudsql_backup_retention_too_short', 'Cloud SQL backup retention is under 7 days', 'HIGH', 3, ['SOC 2', 'ISO 27001'], 11),
  issue('subnetwork_flow_logs_disabled', 'VPC flow logs are disabled on the subnetwork', 'HIGH', 3, ['CIS GCP 2.0', 'PCI DSS 3.2.1'], 23),
  issue('filestore_no_cmek', 'Filestore instance is not encrypted with a CMEK', 'HIGH', 2, ['HIPAA', 'ISO 27001'], 15),
  issue('cloud_function_env_contains_secret', 'Cloud Function environment variable looks like a secret', 'HIGH', 7, ['SOC 2'], 6),
  issue('artifact_registry_scanning_disabled', 'Artifact Registry vulnerability scanning is disabled', 'HIGH', 8, ['SOC 2', 'ISO 27001'], 13),
  issue('audit_log_sink_missing', 'No aggregated log sink for admin activity audit logs', 'MEDIUM', 2, ['CIS GCP 2.0'], 12),
  issue('lb_access_logs_disabled', 'Load balancer access logging is disabled', 'MEDIUM', 4, ['SOC 2', 'ISO 27001'], 2),
  issue('cloud_function_runtime_deprecated', 'Cloud Function uses a deprecated runtime', 'MEDIUM', 5, ['ISO 27001'], 1),
  issue('lb_forwarding_rule_http_only', 'Load balancer forwarding rule accepts plaintext HTTP', 'MEDIUM', 5, ['PCI DSS 3.2.1'], 8),
  issue('ssl_policy_tls_version_outdated', 'SSL policy permits TLS 1.0', 'MEDIUM', 3, ['PCI DSS 3.2.1'], 16),
  issue('pubsub_topic_no_cmek', 'Pub/Sub topic is not encrypted with a CMEK', 'MEDIUM', 9, ['SOC 2'], 20),
  issue('bigquery_dataset_publicly_readable', 'BigQuery dataset grants allAuthenticatedUsers', 'MEDIUM', 6, ['SOC 2'], 20),
  issue('firestore_pitr_disabled', 'Firestore point-in-time recovery is disabled', 'MEDIUM', 7, ['SOC 2', 'ISO 27001'], 18),
  issue('gke_workload_runs_privileged', 'GKE workload runs as a privileged container', 'MEDIUM', 2, ['CIS GKE 1.4'], 10),
  issue('memorystore_transit_encryption_disabled', 'Memorystore in-transit encryption is disabled', 'MEDIUM', 4, ['PCI DSS 3.2.1'], 22),
  issue('api_gateway_logging_disabled', 'API Gateway config has request logging disabled', 'MEDIUM', 5, ['SOC 2'], 7),
  issue('security_command_centre_not_premium', 'Security Command Center is not on the Premium tier', 'MEDIUM', 4, ['CIS GCP 2.0'], 25),
  issue('os_login_not_enforced', 'OS Login is not enforced at the project level', 'MEDIUM', 3, ['CIS GCP 2.0', 'SOC 2'], 27),
  issue('storage_bucket_versioning_disabled', 'Cloud Storage bucket versioning is disabled', 'LOW', 11, ['SOC 2'], 30),
  issue('resource_missing_owner_label', 'Resource is missing a required owner label', 'LOW', 28, ['ISO 27001'], 30),
  issue('resource_missing_cost_centre_label', 'Resource is missing a required cost-centre label', 'LOW', 24, ['ISO 27001'], 30),
  issue('storage_lifecycle_policy_absent', 'Cloud Storage bucket has no lifecycle policy', 'LOW', 15, ['ISO 27001'], 29),
  issue('compute_instance_no_ops_agent', 'Compute Engine instance has no Ops Agent installed', 'LOW', 19, ['SOC 2'], 28),
  issue('service_account_key_older_than_90_days', 'Service account key is older than 90 days', 'LOW', 8, ['CIS GCP 2.0'], 24),
  issue('compute_instance_default_service_account', 'Instance runs as the default Compute service account', 'LOW', 12, ['CIS GCP 2.0'], 26),
];

/** The same estate in Azure's terms. */
export const demoIssuesAzure: IssueSummary[] = [
  issue('storage_container_public_access', 'Blob container allows anonymous public read', 'CRITICAL', 3, ['CIS Azure 2.0', 'SOC 2', 'PCI DSS 3.2.1'], 21),
  issue('key_vault_no_purge_protection', 'Key Vault has purge protection disabled', 'CRITICAL', 2, ['CIS Azure 2.0', 'SOC 2'], 14),
  issue('sql_server_public_network_access', 'SQL server allows public network access', 'CRITICAL', 2, ['CIS Azure 2.0', 'HIPAA'], 5),
  issue('secret_in_app_settings', 'Plaintext secret in App Service application settings', 'CRITICAL', 4, ['CIS Azure 2.0', 'SOC 2'], 3),
  issue('aks_api_server_public', 'AKS API server is reachable from any network', 'CRITICAL', 2, ['CIS AKS 1.4', 'SOC 2'], 9),
  issue('key_vault_key_no_expiry', 'Key Vault key has no expiry date set', 'CRITICAL', 5, ['CIS Azure 2.0', 'PCI DSS 3.2.1'], 17),
  issue('nsg_allows_ssh_from_internet', 'NSG rule allows SSH (22) from Internet', 'HIGH', 9, ['CIS Azure 2.0', 'PCI DSS 3.2.1'], 30),
  issue('nsg_allows_rdp_from_internet', 'NSG rule allows RDP (3389) from Internet', 'HIGH', 3, ['CIS Azure 2.0'], 26),
  issue('managed_disk_no_cmk', 'Managed disk is not encrypted with a customer-managed key', 'HIGH', 17, ['SOC 2', 'HIPAA', 'ISO 27001'], 30),
  issue('aad_user_mfa_not_enforced', 'Entra ID user without MFA enforced', 'HIGH', 6, ['CIS Azure 2.0', 'SOC 2'], 3),
  issue('role_assignment_owner_at_subscription', 'Owner role assigned at subscription scope', 'HIGH', 4, ['CIS Azure 2.0', 'SOC 2'], 19),
  issue('sql_backup_retention_too_short', 'SQL database backup retention is under 7 days', 'HIGH', 3, ['SOC 2', 'ISO 27001'], 11),
  issue('nsg_flow_logs_disabled', 'NSG flow logs are disabled', 'HIGH', 3, ['CIS Azure 2.0', 'PCI DSS 3.2.1'], 23),
  issue('file_share_no_cmk', 'Azure Files share is not encrypted with a CMK', 'HIGH', 2, ['HIPAA', 'ISO 27001'], 15),
  issue('function_app_env_contains_secret', 'Function App setting looks like a secret', 'HIGH', 7, ['SOC 2'], 6),
  issue('acr_image_scanning_disabled', 'Container Registry has image scanning disabled', 'HIGH', 8, ['SOC 2', 'ISO 27001'], 13),
  issue('activity_log_no_diagnostic_setting', 'Activity log has no diagnostic setting exporting it', 'MEDIUM', 2, ['CIS Azure 2.0'], 12),
  issue('load_balancer_diagnostics_disabled', 'Load balancer diagnostic logging is disabled', 'MEDIUM', 4, ['SOC 2', 'ISO 27001'], 2),
  issue('function_runtime_deprecated', 'Function App uses a deprecated runtime', 'MEDIUM', 5, ['ISO 27001'], 1),
  issue('app_gateway_listener_http_only', 'Application Gateway listener accepts plaintext HTTP', 'MEDIUM', 5, ['PCI DSS 3.2.1'], 8),
  issue('app_service_min_tls_outdated', 'App Service permits TLS 1.0', 'MEDIUM', 3, ['PCI DSS 3.2.1'], 16),
  issue('service_bus_queue_no_cmk', 'Service Bus queue is not encrypted with a CMK', 'MEDIUM', 9, ['SOC 2'], 20),
  issue('event_grid_topic_public', 'Event Grid topic allows public network access', 'MEDIUM', 6, ['SOC 2'], 20),
  issue('cosmos_continuous_backup_disabled', 'Cosmos DB continuous backup is disabled', 'MEDIUM', 7, ['SOC 2', 'ISO 27001'], 18),
  issue('aks_workload_runs_privileged', 'AKS workload runs as a privileged container', 'MEDIUM', 2, ['CIS AKS 1.4'], 10),
  issue('redis_non_ssl_port_enabled', 'Redis Cache has the non-SSL port enabled', 'MEDIUM', 4, ['PCI DSS 3.2.1'], 22),
  issue('api_management_logging_disabled', 'API Management has request logging disabled', 'MEDIUM', 5, ['SOC 2'], 7),
  issue('defender_for_cloud_not_standard', 'Defender for Cloud is not on the Standard plan', 'MEDIUM', 4, ['CIS Azure 2.0'], 25),
  issue('storage_secure_transfer_disabled', 'Storage account does not require secure transfer', 'MEDIUM', 3, ['CIS Azure 2.0', 'SOC 2'], 27),
  issue('blob_versioning_disabled', 'Blob versioning is disabled on the storage account', 'LOW', 11, ['SOC 2'], 30),
  issue('resource_missing_owner_tag', 'Resource is missing a required Owner tag', 'LOW', 28, ['ISO 27001'], 30),
  issue('resource_missing_cost_centre_tag', 'Resource is missing a required CostCentre tag', 'LOW', 24, ['ISO 27001'], 30),
  issue('blob_lifecycle_policy_absent', 'Storage account has no blob lifecycle policy', 'LOW', 15, ['ISO 27001'], 29),
  issue('vm_no_monitoring_agent', 'Virtual machine has no Azure Monitor agent', 'LOW', 19, ['SOC 2'], 28),
  issue('aad_app_credential_older_than_90_days', 'App registration credential is older than 90 days', 'LOW', 8, ['CIS Azure 2.0'], 24),
  issue('vm_no_managed_identity', 'Virtual machine has no managed identity assigned', 'LOW', 12, ['SOC 2'], 26),
];

const ISSUES_BY_PROVIDER: Record<EstateProvider, IssueSummary[]> = {
  AWS: demoIssues, GCP: demoIssuesGcp, Azure: demoIssuesAzure,
};

export const demoIssuesFor = (url: string): IssueSummary[] => ISSUES_BY_PROVIDER[providerForUrl(url)];

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

const GCP_RESOURCE_KINDS = [
  'GCP_COMPUTE_INSTANCE', 'GCP_STORAGE_BUCKET', 'GCP_CLOUD_SQL_INSTANCE', 'GCP_FIREWALL_RULE',
  'GCP_PERSISTENT_DISK', 'GCP_CLOUD_FUNCTION', 'GCP_SERVICE_ACCOUNT', 'GCP_GKE_POD',
] as const;

const AZURE_RESOURCE_KINDS = [
  'AZURE_VIRTUAL_MACHINE', 'AZURE_STORAGE_ACCOUNT', 'AZURE_SQL_DATABASE', 'AZURE_NETWORK_SECURITY_GROUP',
  'AZURE_MANAGED_DISK', 'AZURE_FUNCTION_APP', 'AZURE_AD_USER', 'AZURE_AKS_POD',
] as const;

const GCP_RESOURCE_NAMES = [
  'gce-prod-api-01', 'gce-prod-api-02', 'gce-prod-web-01', 'gce-prod-web-02', 'gce-prod-cache-01',
  'gce-analytics-01', 'gce-analytics-02', 'gce-analytics-03', 'gce-etl-runner-01',
  'gce-etl-runner-02', 'checkout-run-7d9f', 'billing-run-5c2a', 'search-indexer-2b81',
  'notification-worker-4c19', 'reporting-function', 'image-resizer-function',
  'xammer-gcs-assets', 'xammer-gcs-logs-archive', 'xammer-gcs-public-exports',
  'xammer-gcs-legacy-backups', 'cloudsql-prod-orders', 'cloudsql-prod-billing', 'cloudsql-staging-orders',
  'prod-web-allow-ssh', 'bastion-allow-ssh', 'legacy-app-allow-all', 'svc-deploy', 'svc-backup',
] as const;

const AZURE_RESOURCE_NAMES = [
  'vm-prod-api-01', 'vm-prod-api-02', 'vm-prod-web-01', 'vm-prod-web-02', 'vm-prod-cache-01',
  'vm-analytics-01', 'vm-analytics-02', 'vm-analytics-03', 'vm-etl-runner-01',
  'vm-etl-runner-02', 'checkout-aci-7d9f', 'billing-aci-5c2a', 'search-indexer-2b81',
  'notification-worker-4c19', 'reporting-func', 'image-resizer-func',
  'xammerprodassets', 'xammerlogsarchive', 'xammerpublicexports',
  'xammerlegacybackups', 'sqldb-prod-orders', 'sqldb-prod-billing', 'sqldb-staging-orders',
  'nsg-prod-web', 'nsg-bastion', 'nsg-legacy-app', 'sp-deploy', 'sp-backup',
] as const;

const RESOURCE_KINDS_BY_PROVIDER: Record<EstateProvider, readonly string[]> = {
  AWS: RESOURCE_KINDS, GCP: GCP_RESOURCE_KINDS, Azure: AZURE_RESOURCE_KINDS,
};
const RESOURCE_NAMES_BY_PROVIDER: Record<EstateProvider, readonly string[]> = {
  AWS: RESOURCE_NAMES, GCP: GCP_RESOURCE_NAMES, Azure: AZURE_RESOURCE_NAMES,
};

/** Builds the affected-resource table for a check, sized to its resourceCount. */
const resourcesFor = (check: string, count: number, severity: Severity, provider: EstateProvider = 'AWS') =>
  Array.from({ length: Math.min(count, 25) }, (_, i) => {
    const seed = check.length + i;
    const name = at(RESOURCE_NAMES_BY_PROVIDER[provider], seed);
    return {
      resourceId: `${name}-${(seed * 7) % 97}`,
      resourceName: name,
      resourceType: at(RESOURCE_KINDS_BY_PROVIDER[provider], seed),
      cloudProvider: provider,
      cloudAccountId: at(ACCOUNTS_BY_PROVIDER[provider], seed),
      region: at(REGIONS_BY_PROVIDER[provider], seed),
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
  // Resolved across all three estates, and the provider is taken from whichever one owns the
  // check — so a GCP finding's affected-resource table lists GCP resources in GCP regions.
  const provider: EstateProvider =
    demoIssuesGcp.some((i) => i.check === check) ? 'GCP'
    : demoIssuesAzure.some((i) => i.check === check) ? 'Azure'
    : 'AWS';
  const known = ISSUES_BY_PROVIDER[provider].find((i) => i.check === check);
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
    resources: resourcesFor(check, resourceCount, severity, provider),
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

/**
 * Internet-exposed GCP surface. Same totals and severity mix as the AWS estate so the demo reads
 * consistently whichever account is selected, but every resource type, check and framework is
 * GCP-native. The AWS version was being served for GCP projects, which is why the dashboard
 * listed CloudFront distributions and Elasticsearch domains against a GCP account.
 */
export const demoAttackSurfaceGcp: AttackSurfaceSummary = {
  exposedResourceCount: 63,
  exposedByType: [
    { type: 'GCP_STORAGE_BUCKET', count: 14 },
    { type: 'GCP_COMPUTE_INSTANCE', count: 12 },
    { type: 'GCP_LOAD_BALANCER', count: 9 },
    { type: 'GCP_DNS_RECORD_SET', count: 8 },
    { type: 'GCP_CLOUD_CDN', count: 6 },
    { type: 'GCP_API_GATEWAY', count: 5 },
    { type: 'GCP_CLOUD_RUN_SERVICE', count: 3 },
    { type: 'GCP_CLOUD_SQL_INSTANCE', count: 2 },
    { type: 'GCP_FILESTORE', count: 2 },
    { type: 'GCP_BIGQUERY_DATASET', count: 2 },
  ],
  exposedByLocation: [
    { country: 'India',          coordinates: [78.9629, 20.5937], count: 24 },
    { country: 'United States',  coordinates: [-95.7129, 37.0902], count: 18 },
    { country: 'Belgium',        coordinates: [4.4699, 50.5039], count: 7 },
    { country: 'Netherlands',    coordinates: [5.2913, 52.1326], count: 5 },
    { country: 'Singapore',      coordinates: [103.8198, 1.3521], count: 4 },
    { country: 'Japan',          coordinates: [138.2529, 36.2048], count: 3 },
    { country: 'Australia',      coordinates: [133.7751, -25.2744], count: 2 },
  ],
  severityCounts: { CRITICAL: 9, HIGH: 24, MEDIUM: 21, LOW: 9 },
  findings: [
    exposureFinding('storage_bucket_public_access', 'Cloud Storage bucket is publicly accessible', 'CRITICAL', 3, ['CIS GCP 2.0', 'SOC 2'], 21, 'GCP_STORAGE_BUCKET', 'Data exposure'),
    exposureFinding('cloudsql_instance_public_ip', 'Cloud SQL instance is reachable on a public IP', 'CRITICAL', 2, ['CIS GCP 2.0', 'HIPAA'], 5, 'GCP_CLOUD_SQL_INSTANCE', 'Direct database access'),
    exposureFinding('bigquery_dataset_publicly_readable', 'BigQuery dataset grants allAuthenticatedUsers', 'CRITICAL', 2, ['SOC 2'], 12, 'GCP_BIGQUERY_DATASET', 'Data exposure'),
    exposureFinding('gke_control_plane_public', 'GKE control plane endpoint is public', 'CRITICAL', 2, ['CIS GKE 1.4'], 9, 'GCP_COMPUTE_INSTANCE', 'Cluster takeover'),
    exposureFinding('firewall_allows_ssh_from_anywhere', 'Firewall rule allows SSH (22) from 0.0.0.0/0', 'HIGH', 9, ['CIS GCP 2.0'], 30, 'GCP_COMPUTE_INSTANCE', 'Remote access'),
    exposureFinding('firewall_allows_rdp_from_anywhere', 'Firewall rule allows RDP (3389) from 0.0.0.0/0', 'HIGH', 3, ['CIS GCP 2.0'], 26, 'GCP_COMPUTE_INSTANCE', 'Remote access'),
    exposureFinding('lb_forwarding_rule_http_only', 'Load balancer forwarding rule accepts plaintext HTTP', 'HIGH', 5, ['PCI DSS 3.2.1'], 8, 'GCP_LOAD_BALANCER', 'Traffic interception'),
    exposureFinding('api_gateway_no_authenticator', 'API Gateway route has no authenticator', 'HIGH', 5, ['SOC 2'], 7, 'GCP_API_GATEWAY', 'Unauthenticated access'),
    exposureFinding('ssl_policy_tls_version_outdated', 'SSL policy permits TLS 1.0', 'HIGH', 3, ['PCI DSS 3.2.1'], 16, 'GCP_CLOUD_CDN', 'Traffic interception'),
    exposureFinding('dns_record_points_to_unclaimed_bucket', 'DNS record points to an unclaimed origin', 'MEDIUM', 4, ['ISO 27001'], 6, 'GCP_DNS_RECORD_SET', 'Subdomain takeover'),
    exposureFinding('cloud_run_allows_unauthenticated', 'Cloud Run service allows allUsers invocation', 'MEDIUM', 3, ['SOC 2'], 33, 'GCP_CLOUD_RUN_SERVICE', 'Unauthenticated access'),
    exposureFinding('storage_bucket_website_hosting_enabled', 'Cloud Storage static website hosting is enabled', 'MEDIUM', 6, ['SOC 2'], 19, 'GCP_STORAGE_BUCKET', 'Data exposure'),
    exposureFinding('cloud_armor_policy_absent', 'Load balancer has no Cloud Armor policy attached', 'MEDIUM', 6, ['SOC 2'], 14, 'GCP_CLOUD_CDN', 'Application attack'),
    exposureFinding('filestore_reachable_from_public_subnet', 'Filestore instance reachable from the public subnetwork', 'LOW', 2, ['SOC 2'], 16, 'GCP_FILESTORE', 'Data exposure'),
    exposureFinding('lb_no_deletion_protection', 'Load balancer has deletion protection disabled', 'LOW', 4, ['ISO 27001'], 21, 'GCP_LOAD_BALANCER', 'Availability'),
  ],
};

/** Internet-exposed Azure surface, same construction as the GCP one above. */
export const demoAttackSurfaceAzure: AttackSurfaceSummary = {
  exposedResourceCount: 63,
  exposedByType: [
    { type: 'AZURE_STORAGE_ACCOUNT', count: 14 },
    { type: 'AZURE_VIRTUAL_MACHINE', count: 12 },
    { type: 'AZURE_LOAD_BALANCER', count: 9 },
    { type: 'AZURE_DNS_RECORD_SET', count: 8 },
    { type: 'AZURE_CDN_ENDPOINT', count: 6 },
    { type: 'AZURE_API_MANAGEMENT', count: 5 },
    { type: 'AZURE_APP_SERVICE', count: 3 },
    { type: 'AZURE_SQL_DATABASE', count: 2 },
    { type: 'AZURE_FILE_SHARE', count: 2 },
    { type: 'AZURE_SYNAPSE_WORKSPACE', count: 2 },
  ],
  exposedByLocation: [
    { country: 'India',          coordinates: [78.9629, 20.5937], count: 24 },
    { country: 'United States',  coordinates: [-95.7129, 37.0902], count: 18 },
    { country: 'Netherlands',    coordinates: [5.2913, 52.1326], count: 7 },
    { country: 'United Kingdom', coordinates: [-3.4360, 55.3781], count: 5 },
    { country: 'Singapore',      coordinates: [103.8198, 1.3521], count: 4 },
    { country: 'Japan',          coordinates: [138.2529, 36.2048], count: 3 },
    { country: 'Australia',      coordinates: [133.7751, -25.2744], count: 2 },
  ],
  severityCounts: { CRITICAL: 9, HIGH: 24, MEDIUM: 21, LOW: 9 },
  findings: [
    exposureFinding('storage_container_public_access', 'Blob container allows anonymous public read', 'CRITICAL', 3, ['CIS Azure 2.0', 'SOC 2'], 21, 'AZURE_STORAGE_ACCOUNT', 'Data exposure'),
    exposureFinding('sql_server_public_network_access', 'SQL server allows public network access', 'CRITICAL', 2, ['CIS Azure 2.0', 'HIPAA'], 5, 'AZURE_SQL_DATABASE', 'Direct database access'),
    exposureFinding('synapse_workspace_public', 'Synapse workspace is reachable from the internet', 'CRITICAL', 2, ['SOC 2'], 12, 'AZURE_SYNAPSE_WORKSPACE', 'Data exposure'),
    exposureFinding('aks_api_server_public', 'AKS API server is reachable from any network', 'CRITICAL', 2, ['CIS AKS 1.4'], 9, 'AZURE_VIRTUAL_MACHINE', 'Cluster takeover'),
    exposureFinding('nsg_allows_ssh_from_internet', 'NSG rule allows SSH (22) from Internet', 'HIGH', 9, ['CIS Azure 2.0'], 30, 'AZURE_VIRTUAL_MACHINE', 'Remote access'),
    exposureFinding('nsg_allows_rdp_from_internet', 'NSG rule allows RDP (3389) from Internet', 'HIGH', 3, ['CIS Azure 2.0'], 26, 'AZURE_VIRTUAL_MACHINE', 'Remote access'),
    exposureFinding('app_gateway_listener_http_only', 'Application Gateway listener accepts plaintext HTTP', 'HIGH', 5, ['PCI DSS 3.2.1'], 8, 'AZURE_LOAD_BALANCER', 'Traffic interception'),
    exposureFinding('api_management_no_subscription_key', 'API Management operation requires no subscription key', 'HIGH', 5, ['SOC 2'], 7, 'AZURE_API_MANAGEMENT', 'Unauthenticated access'),
    exposureFinding('app_service_min_tls_outdated', 'App Service permits TLS 1.0', 'HIGH', 3, ['PCI DSS 3.2.1'], 16, 'AZURE_CDN_ENDPOINT', 'Traffic interception'),
    exposureFinding('dns_record_points_to_unclaimed_origin', 'DNS record points to an unclaimed origin', 'MEDIUM', 4, ['ISO 27001'], 6, 'AZURE_DNS_RECORD_SET', 'Subdomain takeover'),
    exposureFinding('app_service_allows_anonymous', 'App Service allows anonymous authentication', 'MEDIUM', 3, ['SOC 2'], 33, 'AZURE_APP_SERVICE', 'Unauthenticated access'),
    exposureFinding('static_website_hosting_enabled', 'Storage account static website hosting is enabled', 'MEDIUM', 6, ['SOC 2'], 19, 'AZURE_STORAGE_ACCOUNT', 'Data exposure'),
    exposureFinding('front_door_waf_absent', 'CDN endpoint has no WAF policy attached', 'MEDIUM', 6, ['SOC 2'], 14, 'AZURE_CDN_ENDPOINT', 'Application attack'),
    exposureFinding('file_share_public_endpoint', 'Azure Files share reachable on its public endpoint', 'LOW', 2, ['SOC 2'], 16, 'AZURE_FILE_SHARE', 'Data exposure'),
    exposureFinding('load_balancer_no_delete_lock', 'Load balancer has no delete lock', 'LOW', 4, ['ISO 27001'], 21, 'AZURE_LOAD_BALANCER', 'Availability'),
  ],
};

const ATTACK_SURFACE_BY_PROVIDER: Record<EstateProvider, AttackSurfaceSummary> = {
  AWS: demoAttackSurface, GCP: demoAttackSurfaceGcp, Azure: demoAttackSurfaceAzure,
};

export const demoAttackSurfaceFor = (url: string): AttackSurfaceSummary =>
  ATTACK_SURFACE_BY_PROVIDER[providerForUrl(url)];

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
  kind: 'CLASSIC' | 'POLICY' = 'CLASSIC',
  severity: Severity | null = null,
  description: string | null = null,
): ThreatPolicy => ({
  id, displayName, category, scope,
  openIssues: enabled ? openIssuesFor(category) : 0,
  managedBy, enabled, kind, severity, description,
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
  // Upwind's second, newer policy set. Kept in the same list because both appear on the same
  // tab; `kind` is what lets the UI report the split (49 classic + 32 policies = 81 in their UI).
  threatPolicy('pol-v2-01', 'Cloudtrail Bedrock Violations', 'Cloud Logs', 'All accounts', 'Upwind', true,
    'POLICY', 'MEDIUM', 'Detects modifications to Amazon Bedrock guardrails, prompts and agent settings.'),
  threatPolicy('pol-v2-02', 'Anomalous IAM Role Assumption', 'Cloud Logs', 'All accounts', 'Upwind', true,
    'POLICY', 'HIGH', 'Flags role assumptions that deviate from the observed baseline for a principal.'),
  threatPolicy('pol-v2-03', 'Unusual S3 Bulk Download', 'Cloud Logs', 'All accounts', 'Upwind', false,
    'POLICY', 'HIGH', 'Detects large-volume object reads inconsistent with prior access patterns.'),
  threatPolicy('pol-v2-04', 'Sensor Tamper Attempt', 'Sensor', 'All accounts', 'Upwind', true,
    'POLICY', 'CRITICAL', 'Raised when a workload attempts to stop or unload the runtime sensor.'),
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

// ─── /api/xamsecure/stories/{id} ─────────────────────────────────────────────

/**
 * One story in full, derived from the list above so the panel always agrees with the row that
 * opened it.
 *
 * This exists because the demo router matched the list route for detail URLs too — every story
 * opened as "Story not available", since the list payload has no `id` and the loader treats a
 * missing id as "story withheld for tenancy". Detail must be routed before the list.
 */
export const demoStoryDetail = (storyId: string): ThreatStoryDetail | null => {
  const s = demoThreatStories.stories.find((x) => x.id === storyId);
  if (!s) return null;

  const seed = Number((s.id ?? 'story-01').replace(/\D/g, '')) || 1;
  const total = s.relatedEvents ?? 1;
  // Cap the rendered detections: a 40-detection story does not need 40 mock rows to read well.
  const shown = Math.max(1, Math.min(s.resolvedCount ?? total, 6));
  const tactics = at(
    [['Execution', 'Persistence'], ['Credential Access', 'Command and Control'],
     ['Privilege Escalation'], ['Lateral Movement', 'Discovery'], ['Discovery'],
     ['Defense Evasion']], seed);
  const category = at(['PROCESS', 'CLOUD_TRAIL', 'FILE_EVENTS', 'CLOUD_LOGS'], seed);

  const detections: StoryDetection[] = Array.from({ length: shown }, (_, i) => ({
    id: `${s.id}-det-${i + 1}`,
    title: at([
      'Cryptominer process started in a container',
      'Outbound connection to a known C2 endpoint',
      'Shell spawned inside a running container',
      'Repeated failed SSH authentication',
      'Enumeration of IAM permissions',
      'Binary written to a read-only path',
    ], seed + i),
    severity: i === 0 ? s.severity : at(['HIGH', 'MEDIUM', 'LOW'] as const, seed + i),
    status: s.status,
    category,
    resourceId: `arn:aws:ec2:${at(REGIONS, seed)}:${ACCOUNT_IDS[0]}:instance/i-0demo${seed}${i}`,
    resourceName: s.resourceName,
    resourceType: 'AWS_EC2_INSTANCE',
    cloudAccountId: ACCOUNT_IDS[0],
    mitreTactics: tactics,
    occurrenceCount: 1 + ((seed + i) % 5),
    firstSeenTime: s.createTime,
    lastSeenTime: s.updateTime,
  }));

  return {
    id: s.id,
    title: s.title,
    severity: s.severity,
    status: s.status,
    createTime: s.createTime,
    updateTime: s.updateTime,
    description:
      `A sequence of ${total} correlated detections on **${s.resourceName}**, beginning with `
      + `${detections[0].title?.toLowerCase()}. Activity was observed between `
      + `${s.createTime?.slice(0, 10)} and ${s.updateTime?.slice(0, 10)}.\n\n`
      + 'Review the affected resource, confirm whether the activity was expected, and rotate any '
      + 'credentials the workload can reach.',
    detectionCount: total,
    resolvedCount: s.resolvedCount ?? total,
    detections,
    worstSeverity: s.severity,
    firstSeenTime: s.createTime,
    lastSeenTime: s.updateTime,
    mitreTactics: tactics.map((t) => ({ tactic: t, count: 1 + (seed % 3) })),
    categories: [{ category, count: shown }],
    resources: [{
      resourceId: detections[0].resourceId,
      resourceName: s.resourceName,
      resourceType: 'AWS_EC2_INSTANCE',
      cloudProvider: 'AWS',
      cloudAccountId: ACCOUNT_IDS[0],
      detectionCount: shown,
    }],
  };
};

// ─── /api/xamsecure/threats/overview ────────────────────────────────────────────

/**
 * The Events stage is deliberately unavailable here too: the funnel starts from raw event data
 * we do not hold, so the demo must not invent a number the real backend cannot return.
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
    imageCount: 1 + (i % 4),
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
      imageCount: i % 5 === 0 ? 0 : 1 + (i % 3),
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
 *
 * Stages also carry the CVSS bands the ribbon is segmented by, split on the demo's own severity
 * mix. OTHER absorbs the rounding so the bands sum to the stage total exactly, which is the
 * invariant the real backend guarantees and the chart relies on.
 */
const narrowing = (stages: ReadonlyArray<readonly [string, number]>) => {
  const sevTotal = Object.values(cveSeverityCounts).reduce((a, b) => a + b, 0) || 1;
  const pCritical = (cveSeverityCounts.CRITICAL ?? 0) / sevTotal;
  const pHigh = (cveSeverityCounts.HIGH ?? 0) / sevTotal;
  let ceiling = Number.POSITIVE_INFINITY;
  return stages.map(([label, value]) => {
    const clamped = Math.min(value, ceiling);
    ceiling = clamped;
    const CRITICAL = Math.min(clamped, Math.round(clamped * pCritical));
    const HIGH = Math.min(clamped - CRITICAL, Math.round(clamped * pHigh));
    return {
      label,
      value: clamped,
      available: true,
      bands: { CRITICAL, HIGH, OTHER: clamped - CRITICAL - HIGH },
    };
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

const GCP_POSTURE_RESOURCE_BY_CHECK: Record<string, readonly [string, string]> = {
  storage_bucket_public_access: ['xammer-gcs-assets', 'GCP_STORAGE_BUCKET'],
  storage_bucket_versioning_disabled: ['xammer-gcs-logs-archive', 'GCP_STORAGE_BUCKET'],
  storage_lifecycle_policy_absent: ['xammer-gcs-legacy-backups', 'GCP_STORAGE_BUCKET'],
  service_account_user_managed_key: ['svc-deploy', 'GCP_SERVICE_ACCOUNT'],
  service_account_key_older_than_90_days: ['svc-backup', 'GCP_SERVICE_ACCOUNT'],
  iam_primitive_role_at_project: ['roles/editor', 'GCP_IAM_ROLE'],
  iam_service_account_token_creator: ['svc-deploy', 'GCP_SERVICE_ACCOUNT'],
  cloudsql_instance_public_ip: ['cloudsql-prod-orders', 'GCP_CLOUD_SQL_INSTANCE'],
  cloudsql_backup_retention_too_short: ['cloudsql-staging-orders', 'GCP_CLOUD_SQL_INSTANCE'],
  firewall_allows_ssh_from_anywhere: ['prod-web-allow-ssh', 'GCP_FIREWALL_RULE'],
  firewall_allows_rdp_from_anywhere: ['legacy-app-allow-all', 'GCP_FIREWALL_RULE'],
  persistent_disk_no_cmek: ['gce-prod-api-01-boot', 'GCP_PERSISTENT_DISK'],
  cloud_function_runtime_deprecated: ['reporting-function', 'GCP_CLOUD_FUNCTION'],
  cloud_function_env_contains_secret: ['image-resizer-function', 'GCP_CLOUD_FUNCTION'],
  lb_access_logs_disabled: ['prod-public-lb', 'GCP_LOAD_BALANCER'],
  lb_forwarding_rule_http_only: ['prod-public-lb', 'GCP_LOAD_BALANCER'],
  audit_log_sink_missing: ['xammer-org-sink', 'GCP_LOG_SINK'],
  subnetwork_flow_logs_disabled: ['prod-subnet', 'GCP_SUBNETWORK'],
};

const AZURE_POSTURE_RESOURCE_BY_CHECK: Record<string, readonly [string, string]> = {
  storage_container_public_access: ['xammerprodassets', 'AZURE_STORAGE_ACCOUNT'],
  blob_versioning_disabled: ['xammerlogsarchive', 'AZURE_STORAGE_ACCOUNT'],
  blob_lifecycle_policy_absent: ['xammerlegacybackups', 'AZURE_STORAGE_ACCOUNT'],
  aad_user_mfa_not_enforced: ['sp-deploy', 'AZURE_AD_USER'],
  aad_app_credential_older_than_90_days: ['sp-backup', 'AZURE_AD_USER'],
  role_assignment_owner_at_subscription: ['Owner', 'AZURE_ROLE_ASSIGNMENT'],
  key_vault_no_purge_protection: ['kv-prod-xammer', 'AZURE_KEY_VAULT'],
  key_vault_key_no_expiry: ['kv-prod-xammer', 'AZURE_KEY_VAULT'],
  sql_server_public_network_access: ['sqldb-prod-orders', 'AZURE_SQL_DATABASE'],
  sql_backup_retention_too_short: ['sqldb-staging-orders', 'AZURE_SQL_DATABASE'],
  nsg_allows_ssh_from_internet: ['nsg-prod-web', 'AZURE_NETWORK_SECURITY_GROUP'],
  nsg_allows_rdp_from_internet: ['nsg-legacy-app', 'AZURE_NETWORK_SECURITY_GROUP'],
  managed_disk_no_cmk: ['vm-prod-api-01-osdisk', 'AZURE_MANAGED_DISK'],
  function_runtime_deprecated: ['reporting-func', 'AZURE_FUNCTION_APP'],
  function_app_env_contains_secret: ['image-resizer-func', 'AZURE_FUNCTION_APP'],
  load_balancer_diagnostics_disabled: ['lb-prod-public', 'AZURE_LOAD_BALANCER'],
  app_gateway_listener_http_only: ['appgw-prod-public', 'AZURE_APPLICATION_GATEWAY'],
  activity_log_no_diagnostic_setting: ['xammer-activity-log', 'AZURE_DIAGNOSTIC_SETTING'],
  nsg_flow_logs_disabled: ['nsg-prod-web', 'AZURE_NETWORK_SECURITY_GROUP'],
};

const POSTURE_RESOURCE_BY_PROVIDER: Record<EstateProvider, Record<string, readonly [string, string]>> = {
  AWS: POSTURE_RESOURCE_BY_CHECK,
  GCP: GCP_POSTURE_RESOURCE_BY_CHECK,
  Azure: AZURE_POSTURE_RESOURCE_BY_CHECK,
};

const FRAMEWORK_OF = (frameworks: string[]) => frameworks[0] ?? 'SOC 2';

/**
 * One posture row per affected resource, expanded from the grouped issues so the
 * Configurations table and the Issues table describe the same estate.
 * ~250 rows across five pages.
 */
const buildPosture = (provider: EstateProvider): PostureFinding[] => {
  const names = RESOURCE_NAMES_BY_PROVIDER[provider];
  const kinds = RESOURCE_KINDS_BY_PROVIDER[provider];
  const byCheck = POSTURE_RESOURCE_BY_PROVIDER[provider];
  const accounts = ACCOUNTS_BY_PROVIDER[provider];
  const regions = REGIONS_BY_PROVIDER[provider];

  return ISSUES_BY_PROVIDER[provider].flatMap((iss, issueIndex) =>
    Array.from({ length: Math.min(iss.resourceCount, 12) }, (_, r) => {
      const seed = issueIndex * 13 + r;
      const [fallbackName, fallbackType] = [at(names, seed), at(kinds, seed)];
      const [name, type] = byCheck[iss.check] ?? [fallbackName, fallbackType];
      return {
        id: seed + 1,
        upwindId: `pst-${(seed + 1).toString().padStart(5, '0')}`,
        resourceId: `${name}-${(seed * 7) % 97}`,
        severity: iss.severity,
        status: 'OPEN',
        title: iss.title,
        frameworkTitle: FRAMEWORK_OF(iss.frameworks),
        checkTitle: iss.check,
        cloudProvider: provider,
        cloudAccountId: at(accounts, seed),
        region: at(regions, seed),
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
};

/** The AWS estate, kept as a named export because the Overview rollups derive from it. */
export const demoPostureAll: PostureFinding[] = buildPosture('AWS');

const POSTURE_BY_PROVIDER: Record<EstateProvider, PostureFinding[]> = {
  AWS: demoPostureAll, GCP: buildPosture('GCP'), Azure: buildPosture('Azure'),
};

export const demoPosturePage = (url: string): PageResp<PostureFinding> =>
  paginate(bySeverity(POSTURE_BY_PROVIDER[providerForUrl(url)], url), url);

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

const GCP_ASSET_SHAPES: ReadonlyArray<readonly [string, string, string, string]> = [
  // category, sub-category, resource type, name prefix
  ['compute',    'instance',        'GCP_COMPUTE_INSTANCE',      'gce'],
  ['compute',    'instance',        'GCP_COMPUTE_INSTANCE',      'worker'],
  ['compute',    'container',       'GCP_CLOUD_RUN_SERVICE',     'run'],
  ['compute',    'container',       'GCP_GKE_POD',               'pod'],
  ['compute',    'auto-scaling',    'GCP_INSTANCE_GROUP',        'mig'],
  ['serverless', 'function',        'GCP_CLOUD_FUNCTION',        'fn'],
  ['serverless', 'step-function',   'GCP_WORKFLOW',              'flow'],
  ['storage',    'object-storage',  'GCP_STORAGE_BUCKET',        'xammer-bucket'],
  ['storage',    'block-storage',   'GCP_PERSISTENT_DISK',       'disk'],
  ['storage',    'file-storage',    'GCP_FILESTORE',             'filestore'],
  ['storage',    'backup',          'GCP_BACKUP_VAULT',          'vault'],
  ['database',   'relational',      'GCP_CLOUD_SQL_INSTANCE',    'cloudsql'],
  ['database',   'nosql',           'GCP_FIRESTORE_DATABASE',    'firestore'],
  ['database',   'cache',           'GCP_MEMORYSTORE_INSTANCE',  'memstore'],
  ['database',   'warehouse',       'GCP_BIGQUERY_DATASET',      'bq'],
  ['networking', 'vpc',             'GCP_VPC_NETWORK',           'vpc'],
  ['networking', 'subnet',          'GCP_SUBNETWORK',            'subnet'],
  ['networking', 'security-group',  'GCP_FIREWALL_RULE',         'fw'],
  ['networking', 'load-balancer',   'GCP_LOAD_BALANCER',         'lb'],
  ['networking', 'nat-gateway',     'GCP_CLOUD_NAT',             'nat'],
  ['networking', 'internet-gateway', 'GCP_CLOUD_ROUTER',         'router'],
  ['networking', 'dns',             'GCP_DNS_MANAGED_ZONE',      'zone'],
  ['identity',   'user',            'GCP_SERVICE_ACCOUNT',       'sa'],
  ['identity',   'role',            'GCP_IAM_ROLE',              'role'],
  ['identity',   'policy',          'GCP_IAM_POLICY_BINDING',    'binding'],
  ['identity',   'secret',          'GCP_SECRET_MANAGER_SECRET', 'secret'],
  ['messaging',  'queue',           'GCP_PUBSUB_SUBSCRIPTION',   'sub'],
  ['messaging',  'topic',           'GCP_PUBSUB_TOPIC',          'topic'],
];

const AZURE_ASSET_SHAPES: ReadonlyArray<readonly [string, string, string, string]> = [
  ['compute',    'instance',        'AZURE_VIRTUAL_MACHINE',        'vm'],
  ['compute',    'instance',        'AZURE_VIRTUAL_MACHINE',        'worker'],
  ['compute',    'container',       'AZURE_CONTAINER_INSTANCE',     'aci'],
  ['compute',    'container',       'AZURE_AKS_POD',                'pod'],
  ['compute',    'auto-scaling',    'AZURE_VM_SCALE_SET',           'vmss'],
  ['serverless', 'function',        'AZURE_FUNCTION_APP',           'fn'],
  ['serverless', 'step-function',   'AZURE_LOGIC_APP',              'flow'],
  ['storage',    'object-storage',  'AZURE_STORAGE_ACCOUNT',        'xammerbucket'],
  ['storage',    'block-storage',   'AZURE_MANAGED_DISK',           'disk'],
  ['storage',    'file-storage',    'AZURE_FILE_SHARE',             'share'],
  ['storage',    'backup',          'AZURE_RECOVERY_SERVICES_VAULT', 'vault'],
  ['database',   'relational',      'AZURE_SQL_DATABASE',           'sqldb'],
  ['database',   'nosql',           'AZURE_COSMOS_DB_ACCOUNT',      'cosmos'],
  ['database',   'cache',           'AZURE_REDIS_CACHE',            'redis'],
  ['database',   'warehouse',       'AZURE_SYNAPSE_WORKSPACE',      'synapse'],
  ['networking', 'vpc',             'AZURE_VIRTUAL_NETWORK',        'vnet'],
  ['networking', 'subnet',          'AZURE_SUBNET',                 'subnet'],
  ['networking', 'security-group',  'AZURE_NETWORK_SECURITY_GROUP', 'nsg'],
  ['networking', 'load-balancer',   'AZURE_LOAD_BALANCER',          'lb'],
  ['networking', 'nat-gateway',     'AZURE_NAT_GATEWAY',            'nat'],
  ['networking', 'internet-gateway', 'AZURE_APPLICATION_GATEWAY',   'appgw'],
  ['networking', 'dns',             'AZURE_DNS_ZONE',               'zone'],
  ['identity',   'user',            'AZURE_AD_USER',                'user'],
  ['identity',   'role',            'AZURE_ROLE_ASSIGNMENT',        'role'],
  ['identity',   'policy',          'AZURE_POLICY_ASSIGNMENT',      'policy'],
  ['identity',   'secret',          'AZURE_KEY_VAULT_SECRET',       'secret'],
  ['messaging',  'queue',           'AZURE_SERVICE_BUS_QUEUE',      'queue'],
  ['messaging',  'topic',           'AZURE_EVENT_GRID_TOPIC',       'topic'],
];

const ASSET_SHAPES_BY_PROVIDER: Record<EstateProvider, ReadonlyArray<readonly [string, string, string, string]>> = {
  AWS: ASSET_SHAPES, GCP: GCP_ASSET_SHAPES, Azure: AZURE_ASSET_SHAPES,
};

const ENVIRONMENTS = ['prod', 'staging', 'dev', 'analytics'] as const;

/** 224 assets — five pages at 50 per page, spread across eight categories. */
const buildInventory = (provider: EstateProvider): InventoryAsset[] =>
  Array.from({ length: 224 }, (_, i) => {
  const [assetCategory, assetSubCategory, resourceType, prefix] = at(ASSET_SHAPES_BY_PROVIDER[provider], i);
  const env = at(ENVIRONMENTS, Math.floor(i / 7));
  const name = `${env}-${prefix}-${String((i % 60) + 1).padStart(2, '0')}`;
  // The three vulnerability counts were independent expressions, so an asset could report
  // "3 critical" inside a total of 0 — visible in the asset panel's Risk section, and
  // impossible for the real rollup, where the total is the sum over the bands.
  const vulnCritical = i % 7 === 0 ? (i % 4) : 0;
  const vulnHigh = i % 5 === 0 ? (i % 9) : 0;
  return {
    id: i + 1,
    upwindId: `inv-${(i + 1).toString().padStart(5, '0')}`,
    assetCategory,
    assetSubCategory,
    label: resourceType,
    cloudProvider: provider,
    cloudAccountId: at(ACCOUNTS_BY_PROVIDER[provider], i),
    cloudResourceId: `${resourceType.toLowerCase()}/${name}`,
    name,
    region: at(REGIONS_BY_PROVIDER[provider], i),
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
    vulnCriticalCount: vulnCritical,
    vulnHighCount: vulnHigh,
    vulnTotalCount: Math.max(i % 3 === 0 ? (i % 24) : 0, vulnCritical + vulnHigh),
    detectionTotalCount: i % 11 === 0 ? (i % 5) : 0,
    internetExposureType: i % 8 === 0 ? 'wide' : null,
    // Empty on every real asset measured, so the demo keeps it mostly null rather than
    // implying a signal the API does not deliver.
    lastInternetCommunication: null,
    // Public IPs are much rarer than private ones, matching the measured 50 vs 404 split.
    privateIpAddresses: `10.0.${i % 250}.${(i * 7) % 250}`,
    publicIpAddresses: i % 28 === 0 ? `52.${i % 250}.${(i * 3) % 250}.${i % 200}` : null,
    protectedBy: i % 4 === 0 ? 'sensor' : (i % 7 === 0 ? 'cloud_scanner' : null),
    cloudTags: i % 5 === 0 ? `env=prod,team=platform` : null,
    technologies: i % 6 === 0 ? `openssl 1.0.2 (EOL), nginx 1.${i % 26}` : null,
    hasEolTechnology: i % 6 === 0,
  };
});

/** The AWS estate, kept as a named export because other fixtures index into it. */
export const demoInventoryAll: InventoryAsset[] = buildInventory('AWS');

const INVENTORY_BY_PROVIDER: Record<EstateProvider, InventoryAsset[]> = {
  AWS: demoInventoryAll, GCP: buildInventory('GCP'), Azure: buildInventory('Azure'),
};

const riskOf = (assets: InventoryAsset[]): InventoryRisk => ({
  assets: assets.length,
  pii: assets.filter((a) => a.sensitivePiiAtRest || a.sensitivePiiInTransit).length,
  phi: 0,
  pci: assets.filter((a) => a.sensitivePciAtRest).length,
  elevatedPrivilege: assets.filter((a) => a.hasElevatedPrivilege).length,
  eolTechnology: assets.filter((a) => a.hasEolTechnology).length,
  internetExposed: assets.filter((a) => a.internetExposureType !== null).length,
});

const categoriesOf = (assets: InventoryAsset[]): InventoryCategory[] => Object.entries(
  assets.reduce<Record<string, number>>((acc, a) => {
    const key = a.assetCategory ?? 'uncategorized';
    acc[key] = (acc[key] ?? 0) + 1;
    return acc;
  }, {})
)
  .map(([category, count]) => ({ category, count }))
  .sort((a, b) => b.count - a.count);

/** Rollup derived from the assets above, so the strip agrees with the table. */
export const demoInventoryRisk: InventoryRisk = riskOf(demoInventoryAll);

/** Counts come from the assets themselves, so the rail agrees with the table. */
export const demoInventoryCategories: InventoryCategory[] = categoriesOf(demoInventoryAll);

export const demoInventoryRiskFor = (url: string): InventoryRisk =>
  riskOf(INVENTORY_BY_PROVIDER[providerForUrl(url)]);

export const demoInventoryCategoriesFor = (url: string): InventoryCategory[] =>
  categoriesOf(INVENTORY_BY_PROVIDER[providerForUrl(url)]);

export const demoInventoryPage = (url: string): PageResp<InventoryAsset> => {
  const assets = INVENTORY_BY_PROVIDER[providerForUrl(url)];
  const category = qs(url).get('category');
  const filtered = category
    ? assets.filter((a) => a.assetCategory === category)
    : assets;
  return paginate(filtered, url);
};

const networkNodesOf = (assets: InventoryAsset[]): NetworkNode[] => Object.entries(
  assets
    .filter((a) => a.assetCategory === 'networking')
    .reduce<Record<string, number>>((acc, a) => {
      const key = a.assetSubCategory ?? 'other';
      acc[key] = (acc[key] ?? 0) + 1;
      return acc;
    }, {})
)
  .map(([subCategory, count]) => ({ subCategory, count }))
  .sort((a, b) => b.count - a.count);

const networkNodes: NetworkNode[] = networkNodesOf(demoInventoryAll);

/**
 * Demo configuration graph, shaped like the real one: VPCs holding subnets, subnets holding
 * network interfaces, instances attached to interfaces, security groups sitting in the VPC with
 * no subnet, and a couple of load balancers that cannot be placed.
 *
 * Proportions follow the live measurement (97% of nodes connected, load balancers and NAT
 * gateways the honest orphans) so demo mode does not imply a cleaner topology than production.
 */
// Node type strings per provider. The structure is identical across clouds — a network holding
// subnets, subnets holding interfaces, instances attached to interfaces — only the labels differ.
// Without this the Inventory tab's Network map drew aws_vpc / aws_ec2_instance nodes for a GCP
// project, same as the Catalog table did.
interface GraphLabels {
  vpc: string; securityGroup: string; subnet: string; nic: string; instance: string;
  loadBalancer: string; natGateway: string; sgToken: string; nicToken: string;
}

const GRAPH_LABELS: Record<EstateProvider, GraphLabels> = {
  AWS: {
    vpc: 'aws_vpc', securityGroup: 'aws_security_group', subnet: 'aws_subnet',
    nic: 'aws_elastic_network_interface', instance: 'aws_ec2_instance',
    loadBalancer: 'aws_application_load_balancer', natGateway: 'aws_nat_gateway',
    sgToken: 'sg', nicToken: 'eni',
  },
  GCP: {
    vpc: 'gcp_vpc_network', securityGroup: 'gcp_firewall_rule', subnet: 'gcp_subnetwork',
    nic: 'gcp_network_interface', instance: 'gcp_compute_instance',
    loadBalancer: 'gcp_load_balancer', natGateway: 'gcp_cloud_nat',
    sgToken: 'allow', nicToken: 'nic',
  },
  Azure: {
    vpc: 'azure_virtual_network', securityGroup: 'azure_network_security_group',
    subnet: 'azure_subnet', nic: 'azure_network_interface', instance: 'azure_virtual_machine',
    loadBalancer: 'azure_load_balancer', natGateway: 'azure_nat_gateway',
    sgToken: 'nsg', nicToken: 'nic',
  },
};

const buildDemoGraph = (provider: EstateProvider = 'AWS'): ConfigGraph => {
  const nodes: TopologyNode[] = [];
  const edges: TopologyEdge[] = [];
  const vpcCount = 3;
  const L = GRAPH_LABELS[provider];
  const regions = REGIONS_BY_PROVIDER[provider];
  const account = ACCOUNTS_BY_PROVIDER[provider][0];

  for (let v = 0; v < vpcCount; v++) {
    const vpcId = `uwr-demo-vpc-${v}`;
    nodes.push({
      id: vpcId, type: L.vpc, name: `${['prod', 'staging', 'sandbox'][v]}-vpc`,
      category: 'networking', subCategory: 'virtual_private_cloud',
      region: at(regions, v), cloudAccountId: account,
      resourceId: `vpc-0demo${v}`, cidrBlock: `10.${v}.0.0/16`,
      exposure: null, synthetic: false,
    });

    // Security groups: in the VPC, never in a subnet — matches the real payload.
    for (let s = 0; s < 3 - v; s++) {
      const sgId = `uwr-demo-sg-${v}-${s}`;
      nodes.push({
        id: sgId, type: L.securityGroup, name: `${['web', 'app', 'data'][s]}-${L.sgToken}-${v}`,
        category: 'networking', subCategory: 'security_group', region: at(regions, v),
        cloudAccountId: account, resourceId: `${L.sgToken}-0demo${v}${s}`,
        exposure: v === 0 && s === 0 ? 'wide' : null, synthetic: false,
      });
      edges.push({ source: sgId, target: vpcId, kind: 'in-vpc' });
    }

    for (let sn = 0; sn < 2; sn++) {
      // Synthetic: Upwind does not model subnets as assets.
      const subnetId = `subnet:subnet-0demo${v}${sn}`;
      nodes.push({
        id: subnetId, type: L.subnet, name: `subnet-0demo${v}${sn}`,
        category: 'networking', subCategory: 'subnet',
        resourceId: `subnet-0demo${v}${sn}`, exposure: null, synthetic: true,
      });
      edges.push({ source: subnetId, target: vpcId, kind: 'in-vpc' });

      for (let i = 0; i < 3; i++) {
        const eniId = `uwr-demo-eni-${v}-${sn}-${i}`;
        const instId = `uwr-demo-i-${v}-${sn}-${i}`;
        nodes.push({
          id: eniId, type: L.nic,
          name: `${L.nicToken}-0demo${v}${sn}${i}`, category: 'networking',
          subCategory: 'virtual_private_cloud', region: at(regions, v),
          cloudAccountId: account, resourceId: `${L.nicToken}-0demo${v}${sn}${i}`,
          exposure: null, synthetic: false,
        });
        edges.push({ source: eniId, target: subnetId, kind: 'in-subnet' });
        edges.push({ source: eniId, target: vpcId, kind: 'in-vpc' });

        nodes.push({
          id: instId, type: L.instance,
          name: `${['api', 'worker', 'db'][i]}-${v}${sn}`, category: 'compute_platform',
          subCategory: 'virtual_machine', region: at(regions, v),
          cloudAccountId: account, resourceId: `i-0demo${v}${sn}${i}`,
          exposure: v === 0 && i === 0 ? 'wide' : null, synthetic: false,
        });
        edges.push({ source: instId, target: eniId, kind: 'attached-to' });
      }
    }
  }

  // Unplaceable: no VPC, subnet or address is returned for these upstream.
  nodes.push({
    id: 'uwr-demo-alb-0', type: L.loadBalancer, name: 'public-lb',
    category: 'networking', subCategory: 'load_balancer', region: at(regions, 0),
    cloudAccountId: account, resourceId: 'a1b2c3d4', exposure: 'wide',
    synthetic: false,
  });
  nodes.push({
    id: 'uwr-demo-nat-0', type: L.natGateway, name: 'prod-nat',
    category: 'networking', subCategory: 'internet_gateway', region: at(regions, 0),
    cloudAccountId: account, resourceId: 'nat-0demo', exposure: null,
    synthetic: false,
  });

  return { nodes, edges, truncated: false, source: 'configuration' };
};

// ─── /api/xamsecure/sensor-coverage ──────────────────────────────────────────

/**
 * Runtime coverage per account.
 *
 * Deliberately not all-green. One account is fully covered, one is partial, and one is connected
 * with no sensor at all — that last state is the whole reason the panel exists, and it is real:
 * measured on production, an account connected for weeks reported 1,796 assets and zero sensors.
 * A demo that shows only healthy accounts would hide the case a customer most needs to see.
 */
export const demoSensorCoverage: SensorCoverage = {
  accounts: [
    {
      cloudAccountId: ACCOUNT_IDS[2],
      accountName: 'sandbox',
      workloads: 24,
      sensorProtected: 0,
      cloudScannerOnly: 4,
      unprotected: 18,
      unknown: 2,
      totalAssets: 915,
      nonWorkloadAssets: 891,
      coveragePercent: 0,
      status: 'NO_SENSOR',
      workloadTypes: [
        { subCategory: 'virtual_machine', total: 14, sensor: 0, cloudScanner: 2, unprotected: 11, unknown: 1 },
        { subCategory: 'serverless_function', total: 10, sensor: 0, cloudScanner: 2, unprotected: 7, unknown: 1 },
      ],
    },
    {
      cloudAccountId: ACCOUNT_IDS[1],
      accountName: 'staging',
      workloads: 96,
      sensorProtected: 61,
      cloudScannerOnly: 9,
      unprotected: 22,
      unknown: 4,
      totalAssets: 1_204,
      nonWorkloadAssets: 1_108,
      coveragePercent: 63.5,
      status: 'PARTIAL',
      workloadTypes: [
        { subCategory: 'kubernetes_workload', total: 52, sensor: 27, cloudScanner: 5, unprotected: 18, unknown: 2 },
        { subCategory: 'virtual_machine', total: 26, sensor: 20, cloudScanner: 3, unprotected: 3, unknown: 0 },
        { subCategory: 'ecs_workload', total: 12, sensor: 10, cloudScanner: 1, unprotected: 1, unknown: 0 },
        { subCategory: 'serverless_function', total: 6, sensor: 4, cloudScanner: 0, unprotected: 0, unknown: 2 },
      ],
    },
    {
      cloudAccountId: ACCOUNT_IDS[0],
      accountName: 'production',
      workloads: 218,
      sensorProtected: 211,
      cloudScannerOnly: 5,
      unprotected: 2,
      unknown: 0,
      totalAssets: 3_486,
      nonWorkloadAssets: 3_268,
      coveragePercent: 96.8,
      status: 'FULL',
      workloadTypes: [
        { subCategory: 'kubernetes_workload', total: 138, sensor: 136, cloudScanner: 2, unprotected: 0, unknown: 0 },
        { subCategory: 'virtual_machine', total: 44, sensor: 43, cloudScanner: 1, unprotected: 0, unknown: 0 },
        { subCategory: 'ecs_workload', total: 22, sensor: 21, cloudScanner: 1, unprotected: 0, unknown: 0 },
        { subCategory: 'kubernetes_node', total: 14, sensor: 11, cloudScanner: 1, unprotected: 2, unknown: 0 },
      ],
    },
  ],
};

export const demoNetworkTopology: NetworkTopology = {
  accountId: ACCOUNT_IDS[0],
  accounts: ACCOUNT_IDS.length,
  nodes: networkNodes,
  graph: buildDemoGraph(),
};

export const demoNetworkTopologyFor = (url: string): NetworkTopology => {
  const provider = providerForUrl(url);
  const accounts = ACCOUNTS_BY_PROVIDER[provider];
  return {
    accountId: accounts[0],
    accounts: accounts.length,
    nodes: networkNodesOf(INVENTORY_BY_PROVIDER[provider]),
    graph: buildDemoGraph(provider),
  };
};

// ─── /api/xamsecure/compliance ───────────────────────────────────────────────

const framework = (
  name: string, failCount: number, passCount: number, version: string, scanHoursAgo: number,
): ComplianceFramework => ({
  framework: name, failCount, version, status: 'ACTIVE',
  // Null, matching the API: pass findings are no longer synced, so the grid shows Upwind's
  // score plus the failing count. `passCount` here only feeds that score, so the demo does
  // not advertise a column the product stopped populating.
  passCount: null,
  // Upwind's framework id. Required for the per-control drill-down, which is keyed on it.
  frameworkId: `fw-${name.toLowerCase().replace(/[^a-z0-9]+/g, '-')}`,
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

// ─── /api/xamsecure/compliance/frameworks/{id}/controls ──────────────────────

const CONTROL_TITLES = [
  'Ensure S3 buckets block public access',
  'Ensure EBS volumes are encrypted at rest',
  'Ensure IAM users have MFA enabled',
  'Ensure security groups do not allow 0.0.0.0/0 to port 22',
  'Ensure CloudTrail is enabled in all regions',
  'Ensure RDS instances are not publicly accessible',
  'Ensure KMS key rotation is enabled',
  'Ensure VPC flow logging is enabled',
  'Ensure root account access keys do not exist',
  'Ensure EKS control-plane logging is enabled',
  'Ensure Lambda functions are not publicly invokable',
  'Ensure secrets are rotated within 90 days',
];

const REMEDIATIONS = [
  'Enable **Block all public access** on the bucket, then confirm no bucket policy grants `*`.',
  'Create an encrypted snapshot, restore it to a new volume, and detach the unencrypted one.',
  'Assign a virtual MFA device to every console user, then deny `sts:GetSessionToken` without MFA.',
];

/**
 * Per-control breakdown for one framework, grouped from v2 configuration findings.
 *
 * Every control here is scored, because that is what the real endpoint returns: a control only
 * reaches us via its findings, and live findings carry only `pass` or `fail`. The panel also
 * handles a null compliance percentage (a control whose findings are all unscored), but demo
 * data does not fabricate that state — it is covered directly in the component test instead.
 *
 * Sorted worst-first, matching the server's ordering, so the demo reads like production.
 */
export const demoFrameworkControls = (frameworkId: string): FrameworkControls | null => {
  const f = demoCompliance.find((x) => x.frameworkId === frameworkId);
  if (!f) return null;
  const seed = frameworkId.length;
  const controls: FrameworkControl[] = CONTROL_TITLES.map((title, i) => {
    const failCount = (i * 3 + seed) % 11;
    const passCount = 8 + ((i * 5 + seed) % 40);
    const assetCount = failCount + passCount;
    return {
      ruleId: `${frameworkId}-rule-${(i + 1).toString().padStart(3, '0')}`,
      title,
      severity: at(['CRITICAL', 'HIGH', 'MEDIUM', 'LOW'] as Severity[], i + seed),
      passCount,
      failCount,
      assetCount,
      compliancePercent: Math.round((passCount / assetCount) * 100),
      unscoredCount: 0,
      resourceCount: assetCount,
      remediation: at(REMEDIATIONS, i),
      lastEvaluated: hoursAgo(4 + (i % 9)),
    };
  }).sort((a, b) => (a.compliancePercent ?? 0) - (b.compliancePercent ?? 0));
  return {
    frameworkId,
    controls,
    controlCount: controls.length,
    // Non-zero on half the frameworks so the "excluded from other accounts" note is exercised.
    skippedOtherAccounts: seed % 2 === 0 ? 0 : 37,
  };
};

// ─── /api/xamsecure/inventory/assets/{id}/relationships ──────────────────────

/**
 * Provider-native references for one asset. Read per asset upstream, so the demo keys off the
 * asset id the same way and returns an empty list for the types that legitimately stand alone.
 */
export const demoAssetRelationships = (assetId: string): AssetRelationships => {
  const asset = demoInventoryAll.find((a) => a.upwindId === assetId);
  const idx = asset ? asset.id : 0;
  // IAM policies, log groups and Bedrock profiles reference nothing in their raw payload.
  const standalone = !asset
    || asset.assetCategory === 'identity'
    || asset.assetCategory === 'logging';
  if (standalone) {
    return { assetId, resourceType: asset?.label ?? null, relationships: [] };
  }
  const sg = [`sg-0${(idx * 7919).toString(16).padStart(11, '0')}`,
              `sg-0${(idx * 104729).toString(16).padStart(11, '0')}`];
  const subnets = [`subnet-0${(idx * 6151).toString(16).padStart(11, '0')}`,
                   `subnet-0${(idx * 3571).toString(16).padStart(11, '0')}`];
  return {
    assetId,
    resourceType: asset.label,
    relationships: [
      { field: 'SecurityGroups', label: 'Security Groups', references: sg, count: sg.length },
      { field: 'SubnetIds', label: 'Subnet Ids', references: subnets, count: subnets.length },
      {
        field: 'VpcId', label: 'Vpc Id',
        references: [`vpc-0${(idx * 1543).toString(16).padStart(11, '0')}`], count: 1,
      },
      {
        field: 'TargetGroupARNs', label: 'Target Group ARNs',
        references: [
          `arn:aws:elasticloadbalancing:${asset.region}:${asset.cloudAccountId}:targetgroup/tg-${idx}/73e2d6bc24d8a067`,
        ],
        count: 1,
      },
      {
        field: 'IamInstanceProfile', label: 'Iam Instance Profile',
        references: [`arn:aws:iam::${asset.cloudAccountId}:instance-profile/app-${idx}`], count: 1,
      },
    ],
  };
};

// ─── /api/xamsecure/resource ─────────────────────────────────────────────────
//
// The cross-domain join behind every ResourceLink in this section — the dotted-underline
// resource name with the ↗ mark. It had no demo route, so it fell to the `/api/xamsecure/`
// `{}` catch-all. ResourceDrawer builds its nav from `detail.counts`, so `{}` produced a panel
// with an empty nav and a body reading "Nothing to show."
//
// On the Inventory table that looked like randomness, because an asset row has two click
// targets: the row opens the asset catalog panel, which renders from data the table already
// holds and so always worked, while the asset NAME opens this cross-domain panel, which never
// had data. Same asset, two outcomes depending on where the pointer landed.
//
// Mirrors XamSecureService.getResourceDetail: identity prefers the inventory asset and falls
// back to the first finding that names the resource (posture, then vulnerabilities, then
// detections), counts are the list sizes, and topSeverity is the worst band across all three.

type DetailConfig = ResourceDetail['configurations'][number];
type DetailVuln = ResourceDetail['vulnerabilities'][number];
type DetailDetection = ResourceDetail['detections'][number];

/** Worst band across every domain touching the resource, for the drawer header. */
const topSeverityOf = (...groups: { severity: Severity | null }[][]): Severity | null => {
  let best: Severity | null = null;
  for (const group of groups) {
    for (const row of group) {
      if (!row.severity) continue;
      const i = SEVERITY_ORDER.indexOf(row.severity);
      if (i < 0) continue;
      if (best === null || i < SEVERITY_ORDER.indexOf(best)) best = row.severity;
    }
  }
  return best;
};

/** The CVE pool split by band, so an asset's rollup is filled with CVEs that really carry it. */
const CVES_BY_SEVERITY: Record<Severity, CveGroupRow[]> = {
  CRITICAL: demoCveRowsAll.filter((c) => c.severity === 'CRITICAL'),
  HIGH: demoCveRowsAll.filter((c) => c.severity === 'HIGH'),
  MEDIUM: demoCveRowsAll.filter((c) => c.severity === 'MEDIUM'),
  LOW: demoCveRowsAll.filter((c) => c.severity === 'LOW'),
};

const asDetailVuln = (c: CveGroupRow): DetailVuln => ({
  cveId: c.cveId,
  name: c.name,
  severity: c.severity,
  cvssScore: c.cvssScore == null ? null : String(c.cvssScore),
  epssScore: c.epssScore == null ? null : String(c.epssScore),
  exploitable: c.exploitable,
  lastScanTime: hoursAgo(3),
});

/**
 * The asset's own vulnerability rollup, expanded into rows.
 *
 * Read from the catalog counts rather than the findings table on purpose: this panel and the
 * asset panel's Risk section then report the same numbers for the same asset, instead of one
 * saying 15 CVEs and the other none.
 */
const assetVulns = (a: InventoryAsset): DetailVuln[] => {
  const total = a.vulnTotalCount ?? 0;
  if (total <= 0) return [];
  const crit = Math.min(a.vulnCriticalCount ?? 0, total);
  const high = Math.min(a.vulnHighCount ?? 0, total - crit);
  const band = (sev: Severity, n: number, offset: number): CveGroupRow[] =>
    Array.from({ length: n }, (_, k) => at(CVES_BY_SEVERITY[sev], a.id * 3 + offset + k));
  return [
    ...band('CRITICAL', crit, 0),
    ...band('HIGH', high, 11),
    // The tail of a real estate is medium and low, so the remainder comes from those bands.
    ...Array.from({ length: total - crit - high }, (_, k) =>
      at(CVES_BY_SEVERITY[k % 3 === 0 ? 'LOW' : 'MEDIUM'], a.id * 5 + k)),
  ].map(asDetailVuln);
};

/** Same idea for the asset's detection rollup. */
const assetDetections = (a: InventoryAsset): DetailDetection[] =>
  Array.from({ length: a.detectionTotalCount ?? 0 }, (_, k) => {
    const seed = a.id * 5 + k;
    const [title, , mitreTactics, severity] = at(THREAT_TEMPLATES, seed);
    return {
      upwindId: `thr-${a.upwindId}-${k + 1}`,
      title,
      severity: severity as Severity,
      status: 'OPEN',
      mitreTactics,
      lastSeenTime: hoursAgo(spread(seed, 1, 36)),
    };
  });

/**
 * Failing checks for an asset the posture table does not name.
 *
 * An asset carrying no risk signal at all legitimately passes everything — a clean resource
 * showing zero failing checks is the honest answer, not an empty panel — so only the assets
 * the catalog already flags get rows here.
 */
const assetConfigs = (a: InventoryAsset, provider: EstateProvider): DetailConfig[] => {
  const flagged = a.hasElevatedPrivilege || a.hasEolTechnology
    || a.internetExposureType != null || a.publicIpAddresses != null
    || a.sensitivePiiAtRest || a.sensitivePciAtRest;
  const pool = ISSUES_BY_PROVIDER[provider];
  return Array.from({ length: flagged ? 1 + (a.id % 3) : a.id % 2 }, (_, k) => {
    const iss = at(pool, a.id * 5 + k * 11);
    return {
      check: iss.check,
      severity: iss.severity,
      framework: FRAMEWORK_OF(iss.frameworks),
      lastSeenTime: hoursAgo(spread(a.id + k, 1, 20)),
    };
  });
};

/**
 * Types for ids that are not catalog resource ids. The Relationships section links straight to
 * provider references (`sg-…`, a target-group ARN), and those resolve to nothing in any table,
 * so the id itself is the only thing left to read a type off.
 */
const ID_PREFIX_TYPES: ReadonlyArray<readonly [RegExp, string]> = [
  [/^sg-/, 'AWS_SECURITY_GROUP'],
  [/^subnet-/, 'AWS_SUBNET'],
  [/^vpc-/, 'AWS_VPC'],
  [/^i-/, 'AWS_EC2_INSTANCE'],
  [/^vol-/, 'AWS_EBS_VOLUME'],
  [/:targetgroup\//, 'AWS_TARGET_GROUP'],
  [/:instance-profile\//, 'AWS_IAM_INSTANCE_PROFILE'],
  [/^arn:aws:iam:/, 'AWS_IAM_ROLE'],
];

const derivedIdentity = (
  resourceId: string, baseName: string, provider: EstateProvider,
): ResourceDetail['identity'] => {
  const seed = resourceId.length;
  const fromPrefix = ID_PREFIX_TYPES.find(([re]) => re.test(resourceId))?.[1];
  // A catalog-shaped id carries its own type in the first segment: `aws_eks_pod/prod-pod-04`.
  const fromPath = resourceId.includes('/')
    ? resourceId.slice(0, resourceId.indexOf('/')).toUpperCase()
    : null;
  return {
    resourceId,
    name: baseName || resourceId,
    resourceType: fromPrefix ?? fromPath ?? at(RESOURCE_KINDS_BY_PROVIDER[provider], seed),
    assetCategory: null,
    cloudProvider: provider,
    cloudAccountId: at(ACCOUNTS_BY_PROVIDER[provider], seed),
    region: at(REGIONS_BY_PROVIDER[provider], seed),
  };
};

export const demoResourceDetail = (url: string): ResourceDetail | Record<string, never> => {
  const resourceId = (qs(url).get('resourceId') ?? '').trim();
  // What the real endpoint answers for a blank id or an account it cannot resolve. The drawer
  // has its own "nothing references this resource" state for it.
  if (!resourceId) return {};

  const urlProvider = providerForUrl(url);
  // The selected account decides the estate, but the asset is looked up across all three so a
  // request that arrives without an accountId still resolves the thing that was clicked.
  const search: EstateProvider[] = [
    urlProvider,
    ...(['AWS', 'GCP', 'Azure'] as EstateProvider[]).filter((p) => p !== urlProvider),
  ];
  let asset: InventoryAsset | undefined;
  let provider = urlProvider;
  for (const p of search) {
    asset = INVENTORY_BY_PROVIDER[p].find((a) => a.cloudResourceId === resourceId)
      ?? INVENTORY_BY_PROVIDER[p].find((a) => a.name === resourceId);
    if (asset) { provider = p; break; }
  }

  // Findings that name this resource. Every other tab links on the `name-NN` id the posture,
  // threat and CVE-finding fixtures key on, so those joins hit for real there.
  const posture = POSTURE_BY_PROVIDER[provider].filter((p) => p.resourceId === resourceId);
  const threats = demoThreatsAll.filter((t) => t.resourceId === resourceId);
  const baseName = resourceId.includes('/')
    ? resourceId.slice(resourceId.lastIndexOf('/') + 1)
    : resourceId.replace(/-\d+$/, '');
  // The vulnerability fixture carries no resource id, only a name — which is the name those
  // `name-NN` ids were built from.
  const vulns = asset ? [] : demoVulnerabilitiesAll.filter((v) => v.resourceName === baseName);

  const identity: ResourceDetail['identity'] = asset
    ? {
        resourceId,
        name: asset.name,
        resourceType: asset.label,
        assetCategory: asset.assetCategory,
        cloudProvider: asset.cloudProvider,
        cloudAccountId: asset.cloudAccountId,
        region: asset.region,
      }
    : posture.length > 0
      ? {
          resourceId,
          name: posture[0].resourceName,
          resourceType: posture[0].resourceType,
          assetCategory: null,
          cloudProvider: posture[0].cloudProvider,
          cloudAccountId: posture[0].cloudAccountId,
          region: posture[0].region,
        }
      : vulns.length > 0
        ? {
            resourceId,
            name: vulns[0].resourceName,
            resourceType: vulns[0].resourceType,
            assetCategory: null,
            cloudProvider: vulns[0].cloudProvider,
            cloudAccountId: vulns[0].cloudAccountId,
            region: vulns[0].region,
          }
        : threats.length > 0
          ? {
              resourceId,
              name: threats[0].resourceName,
              resourceType: threats[0].resourceType,
              assetCategory: null,
              cloudProvider: threats[0].cloudProvider,
              cloudAccountId: threats[0].cloudAccountId,
              // The real payload omits region on the detection fallback.
              region: null,
            }
          : derivedIdentity(resourceId, baseName, provider);

  const configurations: DetailConfig[] = posture.length > 0
    ? posture.map((p) => ({
        check: p.checkTitle ?? p.title,
        severity: p.severity,
        framework: p.frameworkTitle,
        lastSeenTime: p.lastSeenTime,
      }))
    : asset ? assetConfigs(asset, provider) : [];

  const vulnerabilities: DetailVuln[] = asset ? assetVulns(asset) : vulns.map((v) => ({
    cveId: v.cveId,
    name: v.vulnerabilityName,
    severity: v.severity,
    cvssScore: v.cvssScore,
    epssScore: v.epssScore,
    exploitable: v.exploitable,
    lastScanTime: v.lastScanTime,
  }));

  const detections: DetailDetection[] = threats.length > 0
    ? threats.map((t) => ({
        upwindId: t.upwindId,
        title: t.title,
        severity: t.severity,
        status: t.status,
        mitreTactics: t.mitreTactics,
        lastSeenTime: t.lastSeenTime,
      }))
    : asset ? assetDetections(asset) : [];

  return {
    identity,
    counts: {
      configurations: configurations.length,
      vulnerabilities: vulnerabilities.length,
      detections: detections.length,
    },
    topSeverity: topSeverityOf(configurations, vulnerabilities, detections),
    configurations,
    vulnerabilities,
    detections,
  };
};

// ─── /api/xamsecure/configurations/dashboard ─────────────────────────────────

const postureSeverityCounts = demoPostureAll.reduce<Record<string, number>>((acc, p) => {
  const key = (p.severity ?? 'LOW').toUpperCase();
  acc[key] = (acc[key] ?? 0) + 1;
  return acc;
}, {});

const avgComplianceScore =
  Math.round((demoCompliance.reduce((a, f) => a + (f.score ?? 0), 0) / demoCompliance.length) * 10) / 10;

const configDashboardFor = (provider: EstateProvider): ConfigurationsDashboard => {
  const postureRows = POSTURE_BY_PROVIDER[provider];
  const issues = ISSUES_BY_PROVIDER[provider];
  const counts = postureRows.reduce<Record<string, number>>((acc, p) => {
    const key = (p.severity ?? 'LOW').toUpperCase();
    acc[key] = (acc[key] ?? 0) + 1;
    return acc;
  }, {});
  return {
    severityCounts: counts,
    totalFindings: postureRows.length,
    newLast7Days: issues.filter((i) => {
      const seen = i.firstSeen ? Date.parse(i.firstSeen) : 0;
      return Date.now() - seen <= 7 * MS_DAY;
    }).length,
    topFindings: issues.slice(0, 8),
    findingsOverTime: last7Keys().map((key, i) => ({
      date: key.slice(5),
      HIGH: Math.round(((counts.CRITICAL ?? 0) + (counts.HIGH ?? 0)) * (0.86 + i * 0.023)),
      OTHER: Math.round(((counts.MEDIUM ?? 0) + (counts.LOW ?? 0)) * (0.89 + i * 0.018)),
    })),
    frameworkScores: demoCompliance,
    avgComplianceScore,
    frameworkCount: demoCompliance.length,
  };
};

export const demoConfigDashboard: ConfigurationsDashboard = configDashboardFor('AWS');

export const demoConfigDashboardFor = (url: string): ConfigurationsDashboard =>
  configDashboardFor(providerForUrl(url));

// ─── /api/xamsecure/identities ───────────────────────────────────────────────
//
// Had no demo route at all, so it fell to the `/api/xamsecure/` catch-all and got `{}`. The tab
// reads `data?.total ?? 0` for each card and `data?.identities ?? []` for the table, so every
// card showed 0 and the table showed "No identities discovered for the selected account."
//
// Derived rather than hand-written, mirroring XamSecureService.getIdentities: the roster is the
// ACTIVE identity-category assets from the inventory catalogue, annotated with the failing
// posture checks recorded against them. Building it that way means the Identities tab, the
// Inventory catalogue and the Configurations table all describe the same estate — a check you
// can see in Configurations shows up here against the identity it was raised on.
//
// One demo-only bridge is unavoidable: the two fixtures do not share resource ids (posture rows
// are keyed `${name}-${n}`, inventory assets `${type}/${name}`), so a literal id join — which is
// what the backend does — would match nothing. Rows are matched on resourceType instead and
// distributed round-robin across the identities of that type, which leaves some identities
// clean, exactly as the real roster does.

const worseSeverity = (a: string | null, b: string | null): string | null => {
  if (!a) return b;
  if (!b) return a;
  return SEVERITY_ORDER.indexOf(a) <= SEVERITY_ORDER.indexOf(b) ? a : b;
};

const buildIdentities = (provider: EstateProvider): IdentitiesSummary => {
  const active = INVENTORY_BY_PROVIDER[provider].filter((a) => a.status === 'ACTIVE');
  // findIdentityAssets: assetCategory = 'identity' AND status = 'ACTIVE', ordered by name.
  const assets = active
    .filter((a) => a.assetCategory === 'identity')
    .slice()
    .sort((x, y) => (x.name ?? '').localeCompare(y.name ?? ''));

  const posture = POSTURE_BY_PROVIDER[provider];
  const postureByType = posture.reduce<Record<string, PostureFinding[]>>((acc, p) => {
    const t = p.resourceType ?? '';
    (acc[t] ??= []).push(p);
    return acc;
  }, {});

  const perLabel = assets.reduce<Record<string, number>>((acc, a) => {
    const l = a.label ?? '';
    acc[l] = (acc[l] ?? 0) + 1;
    return acc;
  }, {});
  const seen: Record<string, number> = {};

  const identities: IdentityRow[] = assets.map((a) => {
    const label = a.label ?? '';
    const slot = seen[label] ?? 0;
    seen[label] = slot + 1;
    const pool = postureByType[label] ?? [];
    const mine = pool.filter((_, i) => i % Math.max(perLabel[label], 1) === slot);

    const checks = [...new Set(
      mine.map((p) => p.checkTitle ?? p.title ?? '').filter(Boolean),
    )];
    const severity = mine.reduce<string | null>(
      (acc, p) => worseSeverity(acc, p.severity ?? null), null);
    const firsts = mine.map((p) => p.firstSeenTime).filter(Boolean).sort();
    const lasts = mine.map((p) => p.lastSeenTime).filter(Boolean).sort();

    return {
      resourceId: a.cloudResourceId,
      name: a.name,
      // The backend reports the catalogue `label`, not the sub-category.
      resourceType: a.label,
      cloudProvider: a.cloudProvider,
      cloudAccountId: a.cloudAccountId,
      severity: checks.length > 0 ? (severity as Severity | null) : null,
      findingCount: checks.length,
      checks,
      region: a.region,
      firstSeen: checks.length > 0 ? (firsts[0] ?? null) : null,
      lastSeen: checks.length > 0 ? (lasts[lasts.length - 1] ?? null) : null,
      elevatedPrivilege: Boolean(a.hasElevatedPrivilege),
      inInventory: true,
    };
  });

  // Worst severity first, then most failing checks, then privileged, then by name — so the rows
  // that need attention lead and clean identities sort last.
  identities.sort((x, y) => {
    const rank = (s: string | null) => {
      const i = s == null ? -1 : SEVERITY_ORDER.indexOf(s);
      return i < 0 ? Number.MAX_SAFE_INTEGER : i;
    };
    return rank(x.severity) - rank(y.severity)
      || y.findingCount - x.findingCount
      || (x.elevatedPrivilege ? 0 : 1) - (y.elevatedPrivilege ? 0 : 1)
      || (x.name ?? '').localeCompare(y.name ?? '');
  });

  // findElevatedPrivilegeAssets spans every category, not just identities: an instance carrying
  // a highly-privileged role is as interesting as the role. In this demo estate only identity
  // assets carry the flag, so the two lists overlap more than they would in production.
  const privileged: PrivilegedIdentity[] = active
    .filter((a) => a.hasElevatedPrivilege)
    .map((a) => ({
      resourceId: a.cloudResourceId,
      name: a.name,
      resourceType: a.label,
      category: a.assetCategory,
      cloudProvider: a.cloudProvider,
      cloudAccountId: a.cloudAccountId,
      region: a.region,
      internetExposureType: a.internetExposureType,
      sensitivePii: Boolean(a.sensitivePiiAtRest || a.sensitivePiiInTransit),
      hasFindings: identities.some(
        (i) => i.resourceId === a.cloudResourceId && i.findingCount > 0),
    }));

  // Zero-count severities are omitted, as the backend does — the Critical card reads
  // `severityCounts.CRITICAL ?? 0`, so an absent key is the same as 0 to the UI.
  const severityCounts = SEVERITY_ORDER.reduce<Record<string, number>>((acc, sev) => {
    const n = identities.filter((i) => i.severity === sev).length;
    if (n > 0) acc[sev] = n;
    return acc;
  }, {});

  const byType = Object.entries(
    identities.reduce<Record<string, number>>((acc, i) => {
      const t = i.resourceType ?? 'other';
      acc[t] = (acc[t] ?? 0) + 1;
      return acc;
    }, {}),
  )
    .map(([type, count]) => ({ type, count }))
    .sort((a, b) => b.count - a.count);

  return {
    identities,
    severityCounts,
    byType,
    total: identities.length,
    withFindings: identities.filter((i) => i.findingCount > 0).length,
    privilegedTotal: privileged.length,
    privileged,
  };
};

const IDENTITIES_BY_PROVIDER: Record<EstateProvider, IdentitiesSummary> = {
  AWS: buildIdentities('AWS'), GCP: buildIdentities('GCP'), Azure: buildIdentities('Azure'),
};

export const demoIdentitiesFor = (url: string): IdentitiesSummary =>
  IDENTITIES_BY_PROVIDER[providerForUrl(url)];

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

/** Self-service connection is AWS-only, so the GCP/Azure rows deliberately opt out. */
const onboardingRow = (
  accountName: string, cloudAccountId: string, provider: string,
  status: OnboardingAccount['status'], sensorInstallCommand: string | null,
  extra: Partial<OnboardingAccount> = {},
): OnboardingAccount => ({
  accountName, cloudAccountId, provider, status, sensorInstallCommand,
  selfServiceSupported: provider === 'AWS',
  // Demo tenants are entitled; the restriction is a BillOps admin decision, not a demo scenario.
  onboardingAllowed: true,
  stackRequestedAt: null,
  stackName: null,
  stackRegion: null,
  connectionVerifiedAt: null,
  ...extra,
});

export const demoOnboarding: OnboardingAccount[] = [
  onboardingRow('Xammer Production', 'demo-aws-001', 'AWS', 'CONNECTED', null, {
    stackName: 'upwind-readonly-permissions-demo-aws-001',
    stackRegion: 'us-east-1',
    connectionVerifiedAt: hoursAgo(52),
  }),
  // A mid-onboarding AWS account, so the demo shows the Check connection path too.
  onboardingRow('Xammer Staging', 'demo-aws-002', 'AWS', 'PENDING', null, {
    stackName: 'upwind-readonly-permissions-demo-aws-002',
    stackRegion: 'us-east-1',
    stackRequestedAt: hoursAgo(1),
  }),
  onboardingRow('GCP Analytics', 'demo-gcp-001', 'GCP', 'PENDING', HELM_INSTALL),
  onboardingRow('Azure Enterprise', 'demo-azure-001', 'Azure', 'NOT_CONNECTED', null),
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

// ─── /api/xamsecure/vulnerabilities/groups & /threats/groups ────────────────────

/**
 * Group-by rollups derived from the CVE rows above, so the demo's dimensions agree with each
 * other instead of being three unrelated invented lists.
 */
export const demoVulnGroupPage = (url: string): VulnGroupPage => {
  const q = qs(url);
  const dimension = (q.get('dimension') ?? 'image') as VulnGroupDimension;
  const buckets = dimension === 'package' ? PACKAGES.length : dimension === 'resource' ? WORKLOADS.length : 9;
  const groups: VulnGroupRow[] = Array.from({ length: buckets }, (_, i) => {
    const members = demoCveRowsAll.filter((_c, n) => n % buckets === i);
    const worst = SEVERITY_ORDER.find((sev) => members.some((m) => m.severity === sev)) ?? 'LOW';
    const key = dimension === 'package'
      ? at(PACKAGES, i)[0]
      : dimension === 'resource'
        ? at(WORKLOADS, i)[0]
        : `registry.internal/${['api', 'worker', 'web', 'etl', 'auth', 'search', 'billing', 'cron', 'proxy'][i]}`;
    const secondary = dimension === 'package'
      ? `${1 + (i % 4)}.${i % 10}.0`
      : dimension === 'resource'
        ? at(WORKLOADS, i)[1]
        : `sha-${(1000 + i * 37).toString(16)}`;
    return {
      key,
      secondary,
      severity: worst,
      cveCount: members.length,
      findingsCount: members.reduce((a, m) => a + m.findingsCount, 0),
      resourceCount: 1 + (i % 9),
      exploitable: members.some((m) => m.exploitable),
      packageInUse: members.some((m) => m.packageInUse),
      internetExposed: members.some((m) => m.internetExposed),
      runtimeExposed: members.some((m) => m.runtimeExposed),
    };
  });
  const size = Math.max(1, Number(q.get('size')) || 50);
  const number = Math.min(Math.max(0, Number(q.get('page')) || 0), Math.ceil(groups.length / size) - 1);
  return {
    content: groups.slice(number * size, number * size + size),
    totalElements: groups.length,
    totalPages: Math.max(1, Math.ceil(groups.length / size)),
    number,
    dimension,
  };
};

export const demoThreatGroups = (url: string): ThreatGroups => {
  const dimension = (qs(url).get('dimension') ?? 'threat') as ThreatGroupDimension;
  const rows = demoThreatsAll;
  const keyOf = (t: ThreatDetection): string => (dimension === 'resource'
    ? t.resourceName ?? t.resourceId ?? 'unknown'
    : dimension === 'account'
      ? t.cloudAccountId ?? 'unknown'
      : t.title ?? 'unknown');
  const byKey = new Map<string, ThreatDetection[]>();
  rows.forEach((t) => {
    const k = keyOf(t);
    byKey.set(k, [...(byKey.get(k) ?? []), t]);
  });
  const groups: ThreatGroupRow[] = [...byKey.entries()].map(([key, members]) => ({
    key,
    secondary: dimension === 'resource' ? members[0].resourceType
      : dimension === 'account' ? null : members[0].category,
    severity: SEVERITY_ORDER.find((sev) => members.some((m) => m.severity === sev)) ?? 'LOW',
    detections: members.length,
    occurrences: members.reduce((a, m) => a + (m.occurrenceCount ?? 1), 0),
    resourceCount: new Set(members.map((m) => m.resourceId)).size,
    accountCount: new Set(members.map((m) => m.cloudAccountId)).size,
    firstSeenTime: members[0].firstSeenTime,
    lastSeenTime: members[0].lastSeenTime,
  }));
  return { dimension, groups };
};

// ─── /api/xamsecure/code/* ──────────────────────────────────────────────────────

const CODE_BRANCHES = ['main', 'release/2026.08', 'feat/threat-dashboard',
  'fix/sync-streaming', 'chore/icon-bundle'] as const;

/**
 * A plausible 40-hex commit SHA, deterministic per index.
 *
 * The previous form was `(i * 2654435761).toString(16).padStart(40, '0')`, which put 30-odd
 * leading zeros on every value — so every row abbreviated to `0000000` and the whole column read
 * as one repeated commit. Short SHAs are how a reviewer tells builds apart.
 */
const hexSha = (seed: number): string => {
  let x = ((seed + 1) * 2654435761) % 4294967296;
  let out = '';
  while (out.length < 40) {
    x = (x * 1103515245 + 12345) % 4294967296;
    out += x.toString(16).padStart(8, '0');
  }
  return out.slice(0, 40);
};

/**
 * CI scan history. Roughly one scan in nine has no fingerprint, matching the measured 228 of 256
 * — those rows must render as history without a drill-down.
 *
 * The live stream reports both kinds of build for one connected repository, and `image` there is
 * the build name rather than a container image — measured as "XamOps-New" on every scan, IaC ones
 * included. The demo mirrors that rather than inventing per-scan image names.
 */
export const demoCodeScans: CodeScansResponse = {
  available: true,
  scans: Array.from({ length: 64 }, (_, i): CodeScan => {
    const hasFp = i % 9 !== 0;
    const isImage = i % 2 === 0;
    const branch = at(CODE_BRANCHES, i)[0] ?? CODE_BRANCHES[i % CODE_BRANCHES.length];
    return {
      fingerprint: hasFp ? `fp-${(i * 7919).toString(16).padStart(8, '0')}` : null,
      type: isImage ? 'IMAGE_BUILD' : 'IAC_SCAN_BUILD',
      source: 'GITHUB',
      repository: 'XamOps-New',
      branch,
      commitSha: hexSha(i),
      initiator: at(['AdityaM-XR', 'Akshayy172', 'himanshu-xr'], i)[0] ?? 'AdityaM-XR',
      image: 'XamOps-New',
      imageOs: 'linux',
      mainBranchName: 'main',
      pullRequestId: branch === 'main' ? null : `${280 + i}`,
      pullRequestUrl: branch === 'main' ? null : `https://github.com/XamOps/XamOps-New/pull/${280 + i}`,
      createTime: hoursAgo(spread(i, 2, 700)),
      updateTime: hoursAgo(spread(i, 2, 700)),
    };
  }),
};

const codeCve = (
  cveId: string, name: string, severity: Severity, score: string,
  pkg: string, version: string, fixedIn: string | null, exploitable: boolean, status: string,
): CodeCve => ({
  cveId, name, severity, score,
  description: `${name} affecting ${pkg} ${version}.`,
  cvssVector: 'CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:N/I:N/A:L',
  packageName: pkg, packageVersion: version,
  fixAvailable: fixedIn !== null, fixedInVersion: fixedIn,
  exploitable, status,
});

/**
 * Findings for one scan. Only some builds introduce anything — the majority carry pre-existing
 * CVEs and add none, which is the normal case and the one the panel must read well for.
 */
export const demoCodeScanFindings = (fingerprint: string): CodeScanFindings | null => {
  const scan = demoCodeScans.scans.find((s) => s.fingerprint === fingerprint);
  if (!scan) return null;
  // Summed over the whole fingerprint: the commit SHA is zero-padded, so its first character is
  // always '0' and seeding on that made every build look identical.
  const seed = [...fingerprint].reduce((a, ch) => a + ch.charCodeAt(0), 0);
  const introduces = seed % 2 === 0;
  const introduced = introduces ? [
    codeCve('CVE-2026-42508', 'CA Authentication Bypass', 'CRITICAL', '9.1',
      'golang.org/x/crypto', 'v0.32.0', '0.52.0', false, 'introduced'),
    codeCve('CVE-2026-46598', 'Ed25519 Denial of Service', 'MEDIUM', '5.3',
      'golang.org/x/crypto', 'v0.32.0', '0.52.0', false, 'introduced'),
  ] : [];
  const noChange = [
    codeCve('CVE-2025-68121', 'Crypto/TLS Authentication Bypass', 'HIGH', '7.5',
      'openssl', '3.3.7', '3.3.9', true, 'no_change'),
    codeCve('CVE-2026-8376', 'Perl Denial of Service', 'LOW', '3.1',
      'perl', '5.38.2', null, false, 'no_change'),
  ];
  return {
    fingerprint,
    initiator: scan.initiator,
    branch: scan.branch,
    imageName: scan.image,
    imageOs: 'linux',
    cves: { highImpact: introduces ? [introduced[0]] : [], introduced, noChange, resolved: [] },
    counts: {
      highImpact: introduces ? 1 : 0,
      introduced: introduced.length,
      noChange: noChange.length,
      resolved: 0,
    },
  };
};
