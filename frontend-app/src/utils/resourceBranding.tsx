import React from 'react';
import { Icon } from '@iconify/react';

/**
 * Real product marks for the cloud resources, packages, operating systems and telemetry
 * sources XamSecure displays.
 *
 * Why this exists: every one of these was rendered as bare snake_case text. An inventory of
 * 1,414 assets where 556 are Bedrock inference profiles read as an undifferentiated grey wall,
 * so the single most important fact about the estate — that it is AI-heavy — was invisible.
 * GcpInventorySection and AzureInventorySection already map service types to marks for their
 * providers; this fills the same gap for AWS and Kubernetes and shares one map across
 * Inventory, Threats, Packages and the resource drawers.
 *
 * Two rules held throughout:
 *
 * 1. Never borrow another product's logo. AWS has no published mark for security groups, EBS,
 *    flow logs or Bedrock in any icon set, so those get neutral mdi glyphs in AWS's palette
 *    rather than, say, the AWS Shield logo standing in for a security group — which would name
 *    the wrong product. Bedrock is the one that matters most by volume, and a tinted brain
 *    glyph reads as "AWS AI service" without inventing branding.
 * 2. Every id here is verified against the installed collections. Seven invalid names were
 *    already shipping and rendering nothing, so `npm run icons:bundle` reports unknown ids and
 *    these were all checked before use.
 */

interface Mark {
  icon: string;
  /** Applied as a CSS colour. Brand marks from `logos:` carry their own colours and ignore it. */
  color?: string;
  /** Human label; the raw type string is machine-shaped. */
  label: string;
}

const AWS_PURPLE = '#8C4FFF';   // networking family, per AWS's own diagram palette
const AWS_GREEN = '#3F8624';    // storage family
const AWS_RED = '#DD344C';      // security/identity family
const AWS_PINK = '#E7157B';     // ML/AI family

/**
 * Keyed on Upwind's catalog `label` (e.g. `aws_bedrock_inference_profile`) and on the uppercase
 * `resource_type` seen on findings (e.g. `AWS_SECURITY_GROUP`, `KUBERNETES_DEPLOYMENT`), which
 * are the same vocabulary in different case. Lookup normalises, so one entry serves both.
 */
const RESOURCE_MARKS: Record<string, Mark> = {
  // ── Providers ──────────────────────────────────────────────────────────────────
  // ResourceLink's secondary line carries either a resource type or a bare provider name,
  // so both resolve through this one map.
  aws: { icon: 'logos:aws', label: 'AWS' },
  azure: { icon: 'logos:microsoft-azure', label: 'Azure' },
  gcp: { icon: 'logos:google-cloud', label: 'GCP' },
  google: { icon: 'logos:google-cloud', label: 'GCP' },
  kubernetes: { icon: 'logos:kubernetes', label: 'Kubernetes' },

  // ── AI / ML ────────────────────────────────────────────────────────────────────
  // 39% of the sample estate. No AWS Bedrock mark exists in any installed set.
  aws_bedrock_inference_profile: { icon: 'mdi:brain', color: AWS_PINK, label: 'Bedrock inference profile' },
  aws_bedrock_model_invocation_logging_configuration: { icon: 'mdi:brain', color: AWS_PINK, label: 'Bedrock invocation logging' },
  aws_bedrock_guardrail: { icon: 'mdi:brain', color: AWS_PINK, label: 'Bedrock guardrail' },
  aws_bedrock_agent: { icon: 'mdi:robot-outline', color: AWS_PINK, label: 'Bedrock agent' },
  aws_sagemaker_endpoint_config: { icon: 'mdi:brain', color: AWS_PINK, label: 'SageMaker endpoint config' },

  // ── Identity ───────────────────────────────────────────────────────────────────
  aws_iam_role: { icon: 'logos:aws-iam', label: 'IAM role' },
  aws_iam_user: { icon: 'logos:aws-iam', label: 'IAM user' },
  aws_iam_group: { icon: 'logos:aws-iam', label: 'IAM group' },
  aws_iam_custom_policy: { icon: 'logos:aws-iam', label: 'IAM policy' },
  aws_iam_managed_policy: { icon: 'logos:aws-iam', label: 'IAM managed policy' },
  aws_iam_oidc_provider: { icon: 'mdi:account-key-outline', color: AWS_RED, label: 'IAM OIDC provider' },
  aws_iam_instance_profile: { icon: 'logos:aws-iam', label: 'IAM instance profile' },
  kubernetes_service_account: { icon: 'logos:kubernetes', label: 'K8s service account' },

  // ── Networking ─────────────────────────────────────────────────────────────────
  // No AWS mark for security groups; a shield glyph in AWS red, NOT the AWS Shield logo.
  aws_security_group: { icon: 'mdi:shield-outline', color: AWS_RED, label: 'Security group' },
  aws_vpc: { icon: 'logos:aws-vpc', label: 'VPC' },
  aws_subnet: { icon: 'mdi:lan-connect', color: AWS_PURPLE, label: 'Subnet' },
  aws_route_table: { icon: 'mdi:table-network', color: AWS_PURPLE, label: 'Route table' },
  aws_network_acl: { icon: 'mdi:shield-lock-outline', color: AWS_PURPLE, label: 'Network ACL' },
  aws_internet_gateway: { icon: 'mdi:router-network', color: AWS_PURPLE, label: 'Internet gateway' },
  aws_nat_gateway: { icon: 'mdi:router-network', color: AWS_PURPLE, label: 'NAT gateway' },
  aws_elastic_network_interface: { icon: 'mdi:ethernet-cable', color: AWS_PURPLE, label: 'Network interface' },
  aws_flow_log: { icon: 'mdi:swap-vertical', color: AWS_PURPLE, label: 'VPC flow log' },
  aws_route53_dns_record: { icon: 'logos:aws-route53', label: 'Route 53 record' },
  aws_route53_hosted_zone: { icon: 'logos:aws-route53', label: 'Route 53 hosted zone' },
  aws_load_balancer: { icon: 'logos:aws-elb', label: 'Load balancer' },
  aws_cloudfront_distribution: { icon: 'logos:aws-cloudfront', label: 'CloudFront distribution' },
  aws_api_gateway: { icon: 'logos:aws-api-gateway', label: 'API Gateway' },
  aws_waf: { icon: 'logos:aws-waf', label: 'WAF' },

  // ── Compute ────────────────────────────────────────────────────────────────────
  aws_ec2_instance: { icon: 'logos:aws-ec2', label: 'EC2 instance' },
  aws_ec2_ami: { icon: 'logos:aws-ec2', label: 'EC2 AMI' },
  aws_instance: { icon: 'logos:aws-ec2', label: 'EC2 instance' },
  aws_lambda_function: { icon: 'logos:aws-lambda', label: 'Lambda function' },
  aws_ecs_task: { icon: 'logos:aws-ecs', label: 'ECS task' },
  aws_ecs_service: { icon: 'logos:aws-ecs', label: 'ECS service' },
  aws_eks_cluster: { icon: 'logos:aws-eks', label: 'EKS cluster' },
  aws_ssm_managed_instance: { icon: 'logos:aws-systems-manager', label: 'SSM managed instance' },
  aws_auto_scaling_group: { icon: 'logos:aws-ec2', label: 'Auto Scaling group' },

  // ── Kubernetes ─────────────────────────────────────────────────────────────────
  kubernetes_deployment: { icon: 'logos:kubernetes', label: 'K8s deployment' },
  kubernetes_pod: { icon: 'logos:kubernetes', label: 'K8s pod' },
  kubernetes_cron_job: { icon: 'logos:kubernetes', label: 'K8s cron job' },
  kubernetes_stateful_set: { icon: 'logos:kubernetes', label: 'K8s stateful set' },
  kubernetes_daemon_set: { icon: 'logos:kubernetes', label: 'K8s daemon set' },
  kubernetes_cluster: { icon: 'logos:kubernetes', label: 'K8s cluster' },
  kubernetes_node: { icon: 'logos:kubernetes', label: 'K8s node' },

  // ── Storage ────────────────────────────────────────────────────────────────────
  aws_s3_bucket: { icon: 'logos:aws-s3', label: 'S3 bucket' },
  // EBS has no mark in any installed set.
  aws_ebs_volume: { icon: 'mdi:harddisk', color: AWS_GREEN, label: 'EBS volume' },
  aws_ebs_snapshot: { icon: 'mdi:camera-burst', color: AWS_GREEN, label: 'EBS snapshot' },
  aws_glacier_vault: { icon: 'logos:aws-glacier', label: 'Glacier vault' },

  // ── Database ───────────────────────────────────────────────────────────────────
  aws_rds_instance: { icon: 'logos:aws-rds', label: 'RDS instance' },
  aws_rds_cluster: { icon: 'logos:aws-rds', label: 'RDS cluster' },
  aws_dynamodb_table: { icon: 'logos:aws-dynamodb', label: 'DynamoDB table' },

  // ── Security / secrets ─────────────────────────────────────────────────────────
  aws_kms_key: { icon: 'logos:aws-kms', label: 'KMS key' },
  aws_secrets_manager_secret: { icon: 'logos:aws-secrets-manager', label: 'Secrets Manager secret' },
  aws_acm_certificate: { icon: 'logos:aws-certificate-manager', label: 'ACM certificate' },
  aws_config_rule: { icon: 'logos:aws-config', label: 'Config rule' },

  // ── Observability ──────────────────────────────────────────────────────────────
  aws_cloudwatch_log_group: { icon: 'logos:aws-cloudwatch', label: 'CloudWatch log group' },
  aws_cloudwatch_alarm: { icon: 'logos:aws-cloudwatch', label: 'CloudWatch alarm' },
  aws_cloudtrail_trail: { icon: 'logos:aws-cloudtrail', label: 'CloudTrail trail' },

  // ── Messaging ──────────────────────────────────────────────────────────────────
  aws_sns_topic: { icon: 'logos:aws-sns', label: 'SNS topic' },
  aws_sqs_queue: { icon: 'logos:aws-sqs', label: 'SQS queue' },
};

/** Fallbacks by provider family, so an unmapped type still reads better than a bare cube. */
const FALLBACK: Mark = { icon: 'mdi:cube-outline', color: '#9ca3af', label: 'Resource' };
const AWS_FALLBACK: Mark = { icon: 'logos:aws', label: 'AWS resource' };
const K8S_FALLBACK: Mark = { icon: 'logos:kubernetes', label: 'Kubernetes resource' };

/** Turns `aws_bedrock_inference_profile` into "Bedrock inference profile" as a last resort. */
function humanise(raw: string): string {
  return raw
    .replace(/^(aws|azure|gcp|kubernetes)_/i, '')
    .replace(/_/g, ' ')
    .replace(/^\w/, (c) => c.toUpperCase());
}

export function resourceMark(rawType: string | null | undefined): Mark {
  if (!rawType) return FALLBACK;
  const key = rawType.trim().toLowerCase();
  const hit = RESOURCE_MARKS[key];
  if (hit) return hit;
  if (key.startsWith('kubernetes')) return { ...K8S_FALLBACK, label: humanise(rawType) };
  if (key.startsWith('aws')) return { ...AWS_FALLBACK, label: humanise(rawType) };
  return { ...FALLBACK, label: humanise(rawType) };
}

/**
 * Marks a bare provider reference pulled out of an asset's `raw_data` — `sg-0df6...`,
 * `subnet-0a1b...`, a role ARN, a target-group ARN.
 *
 * These references carry no type field of their own: the asset payload gives a field name and a
 * list of strings, nothing more. The id shape is the only signal available, so this maps the
 * documented AWS id prefixes and ARN service segments onto the same marks the rest of the app
 * uses. Anything unrecognised stays neutral instead of guessing at a product.
 */
const ID_PREFIX_TYPES: [string, string][] = [
  // Order matters only in that longer prefixes must precede shorter ones sharing a start;
  // `i-` requires the hyphen at index 1, so `igw-` cannot fall through to it.
  ['sg-', 'aws_security_group'],
  ['subnet-', 'aws_subnet'],
  ['vpc-', 'aws_vpc'],
  ['rtb-', 'aws_route_table'],
  ['acl-', 'aws_network_acl'],
  ['igw-', 'aws_internet_gateway'],
  ['nat-', 'aws_nat_gateway'],
  ['eni-', 'aws_elastic_network_interface'],
  ['fl-', 'aws_flow_log'],
  ['ami-', 'aws_ec2_ami'],
  ['vol-', 'aws_ebs_volume'],
  ['snap-', 'aws_ebs_snapshot'],
  ['i-', 'aws_ec2_instance'],
];

const ARN_TYPES: [RegExp, string][] = [
  [/^arn:[^:]*:iam:[^:]*:[^:]*:role\//i, 'aws_iam_role'],
  [/^arn:[^:]*:iam:[^:]*:[^:]*:user\//i, 'aws_iam_user'],
  [/^arn:[^:]*:iam:[^:]*:[^:]*:group\//i, 'aws_iam_group'],
  [/^arn:[^:]*:iam:[^:]*:[^:]*:policy\//i, 'aws_iam_managed_policy'],
  [/^arn:[^:]*:iam:[^:]*:[^:]*:instance-profile\//i, 'aws_iam_instance_profile'],
  [/^arn:[^:]*:elasticloadbalancing:[^:]*:[^:]*:loadbalancer\//i, 'aws_load_balancer'],
  [/^arn:[^:]*:autoscaling:/i, 'aws_auto_scaling_group'],
  [/^arn:[^:]*:kms:/i, 'aws_kms_key'],
  [/^arn:[^:]*:s3:/i, 'aws_s3_bucket'],
  [/^arn:[^:]*:lambda:/i, 'aws_lambda_function'],
  [/^arn:[^:]*:sns:/i, 'aws_sns_topic'],
  [/^arn:[^:]*:sqs:/i, 'aws_sqs_queue'],
  [/^arn:[^:]*:secretsmanager:/i, 'aws_secrets_manager_secret'],
  [/^arn:[^:]*:acm:/i, 'aws_acm_certificate'],
  [/^arn:[^:]*:dynamodb:/i, 'aws_dynamodb_table'],
  [/^arn:[^:]*:logs:/i, 'aws_cloudwatch_log_group'],
  [/^arn:[^:]*:rds:/i, 'aws_rds_instance'],
  [/^arn:[^:]*:ecs:[^:]*:[^:]*:service\//i, 'aws_ecs_service'],
  [/^arn:[^:]*:eks:/i, 'aws_eks_cluster'],
];

// Target groups belong to the ELB product, so the ELB mark is the correct attribution even
// though the catalog has no `aws_target_group` type of its own.
const TARGET_GROUP: Mark = { icon: 'logos:aws-elb', label: 'Target group' };
const TARGET_GROUP_ARN = /^arn:[^:]*:elasticloadbalancing:[^:]*:[^:]*:targetgroup\//i;

export function referenceMark(ref: string | null | undefined): Mark {
  const id = (ref ?? '').trim();
  if (!id) return FALLBACK;
  if (TARGET_GROUP_ARN.test(id)) return TARGET_GROUP;
  for (const [re, type] of ARN_TYPES) if (re.test(id)) return resourceMark(type);
  const lower = id.toLowerCase();
  for (const [prefix, type] of ID_PREFIX_TYPES) {
    if (lower.startsWith(prefix)) return resourceMark(type);
  }
  // An ARN for a service we have not mapped is still definitely an AWS resource.
  if (lower.startsWith('arn:')) return AWS_FALLBACK;
  return FALLBACK;
}

/** Inventory category rail. Keys are Upwind's catalog `category` values. */
const CATEGORY_MARKS: Record<string, Mark> = {
  ai: { icon: 'mdi:brain', color: AWS_PINK, label: 'AI' },
  networking: { icon: 'mdi:lan-connect', color: AWS_PURPLE, label: 'Networking' },
  identity: { icon: 'logos:aws-iam', label: 'Identity' },
  storage: { icon: 'logos:aws-s3', label: 'Storage' },
  monitoring: { icon: 'logos:aws-cloudwatch', label: 'Monitoring' },
  logging: { icon: 'logos:aws-cloudwatch', label: 'Logging' },
  compute_platform: { icon: 'logos:aws-ec2', label: 'Compute' },
  compute: { icon: 'logos:aws-ec2', label: 'Compute' },
  security: { icon: 'mdi:shield-outline', color: AWS_RED, label: 'Security' },
  messaging: { icon: 'logos:aws-sns', label: 'Messaging' },
  database: { icon: 'logos:aws-rds', label: 'Database' },
};

export function categoryMark(category: string | null | undefined): Mark {
  if (!category) return { icon: 'mdi:help-circle-outline', color: '#9ca3af', label: 'Uncategorised' };
  return CATEGORY_MARKS[category.trim().toLowerCase()]
    ?? { ...FALLBACK, label: humanise(category) };
}

/**
 * Package and OS marks. Matched on a prefix rather than exact name because package names carry
 * suffixes (`busybox-binsh`, `alpine-baselayout-data`, `libssl3`) that all belong to the same
 * upstream project. Order matters: longer, more specific prefixes first.
 */
const PACKAGE_MARKS: ReadonlyArray<readonly [string, Mark]> = [
  ['alpine', { icon: 'simple-icons:alpinelinux', color: '#0D597F', label: 'Alpine' }],
  ['apk-tools', { icon: 'simple-icons:alpinelinux', color: '#0D597F', label: 'Alpine' }],
  ['containerd', { icon: 'simple-icons:containerd', color: '#575757', label: 'containerd' }],
  ['python', { icon: 'logos:python', label: 'Python' }],
  ['redis', { icon: 'logos:redis', label: 'Redis' }],
  ['valkey', { icon: 'logos:redis', label: 'Valkey' }],
  ['libssl', { icon: 'simple-icons:openssl', color: '#721412', label: 'OpenSSL' }],
  ['libcrypto', { icon: 'simple-icons:openssl', color: '#721412', label: 'OpenSSL' }],
  ['openssl', { icon: 'simple-icons:openssl', color: '#721412', label: 'OpenSSL' }],
  ['ssl_client', { icon: 'simple-icons:openssl', color: '#721412', label: 'OpenSSL' }],
  ['ca-certificates', { icon: 'mdi:certificate-outline', color: '#0891b2', label: 'CA certificates' }],
  ['nginx', { icon: 'logos:nginx', label: 'nginx' }],
  ['grafana', { icon: 'logos:grafana', label: 'Grafana' }],
  ['langchain', { icon: 'simple-icons:langchain', color: '#1C3C3C', label: 'LangChain' }],
  ['node', { icon: 'simple-icons:nodedotjs', color: '#5FA04E', label: 'Node.js' }],
  ['curl', { icon: 'simple-icons:curl', color: '#073551', label: 'curl' }],
  ['libcurl', { icon: 'simple-icons:curl', color: '#073551', label: 'curl' }],
  ['perl', { icon: 'simple-icons:perl', color: '#39457E', label: 'Perl' }],
  ['vim', { icon: 'simple-icons:vim', color: '#019733', label: 'Vim' }],
  ['bash', { icon: 'simple-icons:gnubash', color: '#4EAA25', label: 'Bash' }],
  ['glibc', { icon: 'simple-icons:gnu', color: '#A42E2B', label: 'GNU libc' }],
  // No mark exists for musl or busybox in any installed set.
  ['musl', { icon: 'mdi:code-braces', color: '#6b7280', label: 'musl libc' }],
  ['busybox', { icon: 'mdi:console', color: '#6b7280', label: 'BusyBox' }],
];

export function packageMark(name: string | null | undefined): Mark {
  if (!name) return { icon: 'mdi:package-variant-closed', color: '#9ca3af', label: 'Package' };
  const n = name.trim().toLowerCase();
  for (const [prefix, mark] of PACKAGE_MARKS) {
    if (n.startsWith(prefix)) return mark;
  }
  return { icon: 'mdi:package-variant-closed', color: '#9ca3af', label: name };
}

/** Container image OS, from a vulnerability finding's `imageOsName`. */
export function osMark(osName: string | null | undefined): Mark {
  const n = (osName ?? '').trim().toLowerCase();
  if (!n) return { icon: 'mdi:help-circle-outline', color: '#9ca3af', label: 'Unknown OS' };
  if (n.includes('alpine')) return { icon: 'simple-icons:alpinelinux', color: '#0D597F', label: 'Alpine' };
  if (n.includes('ubuntu')) return { icon: 'logos:ubuntu', label: 'Ubuntu' };
  if (n.includes('debian')) return { icon: 'logos:debian', label: 'Debian' };
  // Amazon Linux has no dedicated mark; the AWS wordmark is the correct attribution.
  if (n.includes('amazon') || n.includes('amzn')) return { icon: 'logos:aws', label: 'Amazon Linux' };
  return { icon: 'simple-icons:linux', color: '#111827', label: osName ?? 'Linux' };
}

/**
 * Threat telemetry sources. This panel exists to show which sources are live versus never
 * connected, so naming the actual product is the whole point of it.
 */
export function telemetryMark(source: string | null | undefined): Mark {
  const n = (source ?? '').trim().toLowerCase();
  if (n.includes('cloudtrail')) return { icon: 'logos:aws-cloudtrail', label: 'AWS CloudTrail' };
  if (n.includes('azure')) return { icon: 'logos:microsoft-azure', label: 'Azure logs' };
  if (n.includes('gcp') || n.includes('google')) return { icon: 'logos:google-cloud', label: 'GCP logs' };
  if (n.includes('sensor')) return { icon: 'mdi:radar', color: '#0891b2', label: 'Sensor' };
  if (n.includes('scanner')) return { icon: 'mdi:file-search-outline', color: '#0891b2', label: 'Cloud Scanner' };
  if (n.includes('audit')) return { icon: 'mdi:text-box-outline', color: '#0891b2', label: 'Audit logs' };
  return { icon: 'mdi:satellite-uplink', color: '#9ca3af', label: source ?? 'Unknown source' };
}

/**
 * Renders a mark at a consistent optical size.
 *
 * `logos:` marks are multi-colour SVGs that ignore `color`, which is intentional — a real
 * product mark should keep its own palette. Only the neutral mdi glyphs get tinted.
 */
export const ResourceIcon: React.FC<{
  mark: Mark; size?: number; className?: string;
}> = ({ mark, size = 14, className }) => (
  <Icon
    icon={mark.icon}
    width={size}
    height={size}
    color={mark.color}
    className={className}
    aria-hidden
  />
);

export type { Mark };
