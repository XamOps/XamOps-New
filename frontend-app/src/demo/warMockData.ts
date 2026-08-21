/**
 * WAR demo data — GET /api/xamops/war/report
 *
 * The WAR page was tabbed into five views (fix first, attack paths, control families,
 * all controls, scan health) and every one of them reads a different slice of the same
 * report, so demo mode needs a report that is internally consistent rather than five
 * hand-written panels that disagree with each other.
 *
 * Two halves:
 *
 *  1. The baseline in {@link ./warControlBaseline} is the real advisory catalog,
 *     extracted from WarControlCatalog.java / GcpWarControlCatalog.java /
 *     AzureWarControlCatalog.java. Control ids, titles, families, severities, attack-path
 *     mappings and doc links are the same reference data the live scan uses.
 *
 *  2. Observed state is synthesised here — which controls passed, what the scan saw, how
 *     many resources are affected — and then aggregated by a port of
 *     WarReportAssembler.assemble(). Scores, coverage gating, path states, category
 *     scores, risk level and the top-risk ordering all come out of those ported rules, so
 *     the tab badges, the score ring and the tables cannot contradict one another.
 *
 * Synthesis is deterministic: status is drawn from a hash of the account id and control
 * id, so a given demo account shows the same posture on every load and across tabs, and
 * the four demo accounts show visibly different postures.
 *
 * <h3>Refreshing the baseline</h3>
 * warControlBaseline.ts is generated. If a catalog changes on the backend, re-extract the
 * {@code new WarControl(...)} rows from the three Java catalogs into the positional
 * tuples that file documents; nothing here needs to change.
 */

import type {
  WarAttackPathSummary,
  WarCategorySummary,
  WarFinding,
  WarReport,
  WarStatus,
} from '../types/war';
import {
  AWS_BASELINE,
  AZURE_BASELINE,
  GCP_BASELINE,
  type RawWarControl,
  type WarBaseline,
} from './warControlBaseline';

type Provider = 'AWS' | 'GCP' | 'Azure';

// ─── Attack paths — mirrors com.xammer.cloud.dto.war.WarAttackPath ────────────

/** Declaration order matters: the assembler groups in enum order, then sorts. */
const ATTACK_PATHS: Array<{ name: string; label: string; description: string }> = [
  { name: 'ACCOUNT_TAKEOVER', label: 'Account Takeover', description: 'Full control of the account or organization' },
  { name: 'CREDENTIAL_THEFT', label: 'Credential Theft', description: 'Long-lived or exposed keys that bots harvest within minutes' },
  { name: 'PRIVILEGE_ESCALATION', label: 'Privilege Escalation', description: 'One compromised identity able to do far more than intended' },
  { name: 'PUBLIC_EXPOSURE', label: 'Public Exposure', description: 'Resources reachable from the open internet' },
  { name: 'LATERAL_MOVEMENT', label: 'Lateral Movement', description: 'Spread from one compromised workload across the fleet' },
  { name: 'CRYPTO_MINING', label: 'Crypto Mining', description: 'Hijacked compute running mining workloads on your bill' },
  { name: 'DATA_EXFILTRATION', label: 'Data Exfiltration', description: 'Bulk data pulled out, with egress charges on top' },
  { name: 'RANSOMWARE', label: 'Ransomware', description: 'Data encrypted or deleted with no clean recovery path' },
  { name: 'AI_TOKEN_ABUSE', label: 'AI / Token Abuse', description: 'Model endpoints invoked at scale by someone else' },
  { name: 'DDOS_AMPLIFICATION', label: 'DDoS Amplification', description: 'Attack traffic driving Auto Scaling into a billing spike' },
  { name: 'BLIND_SPOT', label: 'Detection Blind Spot', description: 'The attack runs, and nothing tells you until the invoice' },
  { name: 'COST_ABUSE', label: 'Cost Abuse', description: 'Ungoverned provisioning that inflates spend without attribution' },
];

const PATH_LABELS: Record<string, string> = Object.fromEntries(
  ATTACK_PATHS.map((p) => [p.name, p.label]),
);

// ─── Deterministic pseudo-randomness ─────────────────────────────────────────

/** FNV-1a. Any stable hash will do; this one is short and has no dependencies. */
const hash = (value: string): number => {
  let h = 2166136261;
  for (let i = 0; i < value.length; i += 1) {
    h ^= value.charCodeAt(i);
    h = Math.imul(h, 16777619);
  }
  return h >>> 0;
};

/** Stable index into a list, from a seed. */
const pick = <T,>(list: T[], seed: number): T => list[seed % list.length];

// ─── Per-provider resource vocabulary ────────────────────────────────────────

interface ResourcePool {
  regions: string[];
  /** Regions the scan failed to finish, quoted in scanWarnings. */
  degradedRegion: string;
  identities: string[];
  networks: string[];
  stores: string[];
  computes: string[];
  clusters: string[];
  aiEndpoints: string[];
  /** Service names used in evidence copy, so each provider reads like itself. */
  terms: {
    threat: string;
    audit: string;
    keys: string;
    flowLogs: string;
    identity: string;
    guardrail: string;
    ai: string;
    k8s: string;
    compute: string;
    store: string;
    /** What a long-lived credential is called here, e.g. "access key". */
    credential: string;
    /**
     * The second credential weakness this provider's identities actually have.
     * AWS IAM users can lack MFA; a GCP service account or an Azure service principal
     * cannot, so naming one would be a finding no engineer could go and verify.
     */
    credentialGap: string;
  };
}

const POOLS: Record<Provider, ResourcePool> = {
  AWS: {
    regions: ['ap-south-1', 'us-east-1', 'eu-west-1'],
    degradedRegion: 'eu-west-1',
    identities: ['svc-deploy-ci', 'analytics-etl', 'legacy-backup-user', 'terraform-runner', 'grafana-reader'],
    networks: ['sg-0af3c21b9d (prod-web)', 'sg-07be5512aa (bastion)', 'sg-0d19f4c880 (data-tier)', 'acl-0b7712ee (public-subnet)'],
    stores: ['s3://xammer-prod-artifacts', 's3://xammer-analytics-raw', 's3://xammer-db-exports', 'rds:prod-orders-primary'],
    computes: ['i-0c41f9ab2e7 (batch-worker-3)', 'i-0ae7731bb04 (api-blue-2)', 'i-05f3c9d1188 (jenkins-agent)', 'i-09b41ce7752 (ml-trainer-1)'],
    clusters: ['eks/prod-platform', 'eks/data-pipeline', 'ecs/checkout-svc'],
    aiEndpoints: ['bedrock/anthropic.claude-3-sonnet', 'bedrock/amazon.titan-embed', 'sagemaker/fraud-scorer-prod'],
    terms: {
      threat: 'GuardDuty', audit: 'CloudTrail', keys: 'KMS', flowLogs: 'VPC Flow Logs',
      identity: 'IAM', guardrail: 'Service Control Policies', ai: 'Bedrock', k8s: 'EKS',
      compute: 'EC2', store: 'S3',
      credential: 'access key',
      credentialGap: 'no MFA device registered, console password still active',
    },
  },
  GCP: {
    regions: ['asia-south1', 'us-central1'],
    degradedRegion: 'us-central1',
    identities: ['ci-deployer@xammer-prod.iam.gserviceaccount.com', 'etl-runner@xammer-prod.iam.gserviceaccount.com', 'legacy-sa-2019@xammer-prod.iam.gserviceaccount.com', 'looker-reader@xammer-prod.iam.gserviceaccount.com'],
    networks: ['default/allow-ssh-any', 'prod-vpc/web-ingress', 'prod-vpc/data-tier', 'analytics-vpc/allow-all-egress'],
    stores: ['gs://xammer-prod-artifacts', 'gs://xammer-analytics-raw', 'bq://xammer-prod.orders', 'sql:prod-orders-primary'],
    computes: ['batch-worker-3 (asia-south1-a)', 'api-blue-2 (asia-south1-b)', 'jenkins-agent (us-central1-a)', 'ml-trainer-1 (us-central1-c)'],
    clusters: ['gke/prod-platform', 'gke/data-pipeline', 'gke/checkout-svc'],
    aiEndpoints: ['vertex/gemini-1.5-pro', 'vertex/text-embedding-004', 'vertex/fraud-scorer-prod'],
    terms: {
      threat: 'Security Command Center', audit: 'Cloud Audit Logs', keys: 'Cloud KMS',
      flowLogs: 'VPC Flow Logs', identity: 'Cloud IAM', guardrail: 'Organization Policy',
      ai: 'Vertex AI', k8s: 'GKE', compute: 'Compute Engine', store: 'Cloud Storage',
      credential: 'user-managed service-account key',
      credentialGap: 'user-managed key active with no rotation policy',
    },
  },
  Azure: {
    regions: ['centralindia', 'eastus'],
    degradedRegion: 'eastus',
    identities: ['sp-deploy-ci', 'sp-analytics-etl', 'legacy-admin-2020', 'sp-terraform-runner'],
    networks: ['nsg-prod-web/AllowAnySSHInbound', 'nsg-bastion/AllowRDP', 'nsg-data-tier/AllowAnyCustom1433', 'nsg-analytics/AllowInternetOutbound'],
    stores: ['xammerprodartifacts (blob)', 'xammeranalyticsraw (blob)', 'sql:prod-orders-primary', 'cosmos:orders-eu'],
    computes: ['vm-batch-worker-3', 'vm-api-blue-2', 'vm-jenkins-agent', 'vm-ml-trainer-1'],
    clusters: ['aks/prod-platform', 'aks/data-pipeline', 'aks/checkout-svc'],
    aiEndpoints: ['openai/gpt-4o-prod', 'openai/text-embedding-3-large', 'ml/fraud-scorer-prod'],
    terms: {
      threat: 'Defender for Cloud', audit: 'Activity Log', keys: 'Key Vault',
      flowLogs: 'NSG Flow Logs', identity: 'Entra ID', guardrail: 'Azure Policy',
      ai: 'Azure OpenAI', k8s: 'AKS', compute: 'Virtual Machines', store: 'Storage Accounts',
      credential: 'client secret',
      credentialGap: 'client secret configured to never expire',
    },
  },
};

// ─── Per-family observed-state vocabulary ────────────────────────────────────

/**
 * How a family's findings read.
 *
 * The advisory gives us the control text; a scan result also needs a verdict sentence and
 * the evidence behind it, because "MFA is not enforced" is an assertion whereas naming the
 * four identities without a device is something an engineer can act on. One profile per
 * family, provider flavour supplied by the {@link ResourcePool} it is handed.
 */
interface FamilyProfile {
  /** False for org-level families, which report against the account rather than a region. */
  regional: boolean;
  /** What the counts are counting, e.g. "identities". */
  unit: string;
  subjects: (pool: ResourcePool) => string[];
  /** One evidence line naming one resource. */
  line: (name: string, pool: ResourcePool, seed: number) => string;
  failSummary: (affected: number, scanned: number, pool: ResourcePool) => string;
  warnSummary: (affected: number, scanned: number, pool: ResourcePool) => string;
  passSummary: (scanned: number, pool: ResourcePool) => string;
  notApplicable: (pool: ResourcePool) => string;
  /** The read this control needed but was refused, quoted on UNKNOWN findings. */
  denied: Record<Provider, string>;
}

const FAMILY_PROFILES: Record<string, FamilyProfile> = {
  'Identity & Access Management': {
    regional: false,
    unit: 'identities',
    subjects: (p) => p.identities,
    line: (name, p, seed) => pick([
      `${name} — ${p.terms.credential} ${420 + (seed % 260)} days old, never rotated`,
      `${name} — ${p.terms.credentialGap}`,
      `${name} — ${p.terms.identity} policy grants a wildcard action on all resources`,
      `${name} — last used ${94 + (seed % 210)} days ago, permissions still standing`,
    ], seed),
    failSummary: (a, s, p) => `${a} of ${s} ${p.terms.identity} identities fail this control`,
    warnSummary: (a, s) => `${a} of ${s} identities are covered; the remainder are exempted by policy`,
    passSummary: (s, p) => `All ${s} ${p.terms.identity} identities satisfy this control`,
    notApplicable: (p) => `No ${p.terms.identity} identities of this kind exist in the account`,
    denied: {
      AWS: 'iam:GenerateCredentialReport',
      GCP: 'resourcemanager.projects.getIamPolicy',
      Azure: 'Microsoft.Authorization/roleAssignments/read',
    },
  },

  'Network Security': {
    regional: true,
    unit: 'rules',
    subjects: (p) => p.networks,
    line: (name, p, seed) => pick([
      `${name} — allows 0.0.0.0/0 on tcp/22`,
      `${name} — allows 0.0.0.0/0 on tcp/3389`,
      `${name} — unrestricted egress to any destination`,
      `${name} — ${p.terms.flowLogs} not enabled on the attached subnet`,
    ], seed),
    failSummary: (a, s) => `${a} of ${s} rules are reachable from the public internet`,
    warnSummary: (a, s) => `${a} of ${s} rules are open, but only to the corporate CIDR`,
    passSummary: (s) => `All ${s} rules restrict access to known CIDRs`,
    notApplicable: (p) => `No ${p.terms.compute} workloads are attached to a public subnet`,
    denied: {
      AWS: 'ec2:DescribeFlowLogs',
      GCP: 'compute.networks.list',
      Azure: 'Microsoft.Network/networkSecurityGroups/read',
    },
  },

  'Data Protection': {
    regional: true,
    unit: 'data stores',
    subjects: (p) => p.stores,
    line: (name, p, seed) => pick([
      `${name} — no default encryption, ${p.terms.keys} key not attached`,
      `${name} — public read grant present in the resource policy`,
      `${name} — versioning off, no recovery point for the last 30 days`,
      `${name} — backups retained 3 days, below the 35-day baseline`,
    ], seed),
    failSummary: (a, s, p) => `${a} of ${s} ${p.terms.store} stores are unprotected`,
    warnSummary: (a, s) => `${a} of ${s} stores are encrypted, but with a provider-managed key`,
    passSummary: (s) => `All ${s} data stores are encrypted and versioned`,
    notApplicable: (p) => `No ${p.terms.store} resources are provisioned in this account`,
    denied: {
      AWS: 's3:GetEncryptionConfiguration',
      GCP: 'storage.buckets.getIamPolicy',
      Azure: 'Microsoft.Storage/storageAccounts/read',
    },
  },

  'Logging & Monitoring': {
    regional: false,
    unit: 'log destinations',
    subjects: (p) => [p.terms.audit, p.terms.flowLogs, `${p.terms.k8s} control-plane logs`, `${p.terms.ai} invocation logs`],
    line: (name, p, seed) => pick([
      `${name} — not enabled in ${pick(p.regions, seed)}`,
      `${name} — delivering to a bucket with no retention policy`,
      `${name} — log validation disabled, tampering would not be detectable`,
      `${name} — no alert routed on write failure`,
    ], seed),
    failSummary: (a, s) => `${a} of ${s} log destinations would not record an incident`,
    warnSummary: (a, s) => `${a} of ${s} destinations are logging, but retention is under 90 days`,
    passSummary: (s) => `All ${s} log destinations are enabled with retention and alerting`,
    notApplicable: (p) => `No ${p.terms.audit} resources are in scope for this account`,
    denied: {
      AWS: 'cloudtrail:GetTrailStatus',
      GCP: 'logging.sinks.list',
      Azure: 'Microsoft.Insights/diagnosticSettings/read',
    },
  },

  'Compute Security': {
    regional: true,
    unit: 'workloads',
    subjects: (p) => [...p.computes, ...p.clusters],
    line: (name, p, seed) => pick([
      `${name} — public IP attached with no load balancer in front`,
      `${name} — instance metadata service v1 still reachable`,
      `${name} — patch level ${2 + (seed % 7)} releases behind the current image`,
      `${name} — ${p.terms.k8s} workload runs privileged with hostPath mounts`,
    ], seed),
    failSummary: (a, s, p) => `${a} of ${s} ${p.terms.compute} workloads fail this control`,
    warnSummary: (a, s) => `${a} of ${s} workloads are hardened; the rest are pending a maintenance window`,
    passSummary: (s) => `All ${s} workloads satisfy this control`,
    notApplicable: (p) => `No ${p.terms.compute} workloads are running in this account`,
    denied: {
      AWS: 'ssm:DescribeInstanceInformation',
      GCP: 'compute.instances.list',
      Azure: 'Microsoft.Compute/virtualMachines/read',
    },
  },

  'Compliance & Governance': {
    regional: false,
    unit: 'resources',
    subjects: (p) => [...p.computes.slice(0, 2), ...p.stores.slice(0, 2)],
    line: (name, p, seed) => pick([
      `${name} — no CostCenter tag, spend cannot be attributed`,
      `${name} — created outside the approved pipeline`,
      `${name} — no owner tag; ${seed % 4 === 0 ? 'no' : 'stale'} contact on record`,
      `${name} — outside every ${p.terms.guardrail} guardrail currently attached`,
    ], seed),
    failSummary: (a, s) => `${a} of ${s} resources sit outside the governance baseline`,
    warnSummary: (a, s) => `${a} of ${s} resources are governed, but the policy is in audit mode only`,
    passSummary: (s, p) => `All ${s} resources are covered by a ${p.terms.guardrail} guardrail`,
    notApplicable: () => 'This account is not the organization management account',
    denied: {
      AWS: 'organizations:ListPoliciesForTarget',
      GCP: 'orgpolicy.policies.list',
      Azure: 'Microsoft.Authorization/policyAssignments/read',
    },
  },

  'AI Services Security': {
    regional: true,
    unit: 'model endpoints',
    subjects: (p) => p.aiEndpoints,
    line: (name, p, seed) => pick([
      `${name} — reachable from the public internet with no private endpoint`,
      `${name} — no token quota configured; ${18 + (seed % 40)}k tokens/min observed`,
      `${name} — invocation logging disabled, prompts are not retained`,
      `${name} — no ${p.terms.guardrail} content filter attached`,
    ], seed),
    failSummary: (a, s, p) => `${a} of ${s} ${p.terms.ai} endpoints are exposed or ungoverned`,
    warnSummary: (a, s) => `${a} of ${s} endpoints are governed, but quotas are set above forecast spend`,
    passSummary: (s, p) => `All ${s} ${p.terms.ai} endpoints are private and quota-bound`,
    notApplicable: (p) => `No ${p.terms.ai} endpoints are deployed in this account`,
    denied: {
      AWS: 'bedrock:GetModelInvocationLoggingConfiguration',
      GCP: 'aiplatform.endpoints.list',
      Azure: 'Microsoft.CognitiveServices/accounts/read',
    },
  },

  'Cryptocurrency Mining Threat Detection': {
    regional: true,
    unit: 'detections',
    subjects: (p) => [...p.computes, ...p.clusters],
    line: (name, p, seed) => pick([
      `${name} — sustained 96% CPU for ${9 + (seed % 60)}h with no matching deployment`,
      `${name} — outbound connection to a known mining pool on tcp/3333`,
      `${name} — ${p.terms.threat} has no detector enabled in ${pick(p.regions, seed)}`,
      `${name} — GPU instance family launched outside the approved allow-list`,
    ], seed),
    failSummary: (a, s, p) => `${a} of ${s} ${p.terms.threat} signals point at unauthorised compute`,
    warnSummary: (a, s, p) => `${a} of ${s} signals are covered; ${p.terms.threat} alerting is email-only`,
    passSummary: (s, p) => `${p.terms.threat} is enabled and clean across all ${s} checks`,
    notApplicable: (p) => `${p.terms.threat} reports no compute in scope for mining detection`,
    denied: {
      AWS: 'guardduty:ListDetectors',
      GCP: 'securitycenter.findings.list',
      Azure: 'Microsoft.Security/pricings/read',
    },
  },
};

/** Families are advisory-defined, but a missing profile must not blank the page. */
const FALLBACK_PROFILE: FamilyProfile = FAMILY_PROFILES['Compliance & Governance'];

// ─── Account posture ─────────────────────────────────────────────────────────

/**
 * Cumulative cut points over a 0-99 hash, applied to automated controls only.
 *
 * A control below {@code fail} fails, below {@code warn} is weakened, and so on; the
 * remainder pass. Non-automated controls are always MANUAL, which is what the backend
 * does when no check exists for them.
 *
 * The four accounts are tuned to land in different verdict bands so the demo shows the
 * page's range rather than the same amber dashboard four times.
 */
interface Posture {
  fail: number;
  warn: number;
  unverified: number;
  notApplicable: number;
}

const POSTURES: Record<string, Posture> = {
  // Production: mostly hardened, a real fix list, near-complete coverage.
  'demo-aws-001': { fail: 15, warn: 25, unverified: 31, notApplicable: 34 },
  // Staging: visibly worse, and the place to see a low score with high coverage.
  'demo-aws-002': { fail: 31, warn: 43, unverified: 49, notApplicable: 53 },
  'demo-gcp-001': { fail: 13, warn: 21, unverified: 28, notApplicable: 32 },
  'demo-azure-001': { fail: 25, warn: 35, unverified: 42, notApplicable: 46 },
};

const DEFAULT_POSTURE: Posture = POSTURES['demo-aws-001'];

/** Advisory severity → score weight. Mirrors WarReportAssembler.SEVERITY_WEIGHT. */
const SEVERITY_WEIGHT: Record<string, number> = {
  critical: 10, high: 6, medium: 3, low: 1,
};

const normaliseSeverity = (severity: string): string => {
  const trimmed = (severity ?? '').trim();
  if (!trimmed) return 'Low';
  return trimmed.charAt(0).toUpperCase() + trimmed.slice(1).toLowerCase();
};

const weightOf = (severity: string): number =>
  SEVERITY_WEIGHT[normaliseSeverity(severity).toLowerCase()] ?? 1;

const isOpen = (status: WarStatus): boolean =>
  status === 'FAIL' || status === 'WARN' || status === 'UNKNOWN';

const isScored = (status: WarStatus): boolean =>
  status === 'PASS' || status === 'FAIL' || status === 'WARN';

// ─── Finding synthesis ───────────────────────────────────────────────────────

const statusFor = (automated: boolean, seed: number, posture: Posture): WarStatus => {
  if (!automated) return 'MANUAL';
  const roll = seed % 100;
  if (roll < posture.fail) return 'FAIL';
  if (roll < posture.warn) return 'WARN';
  if (roll < posture.unverified) return 'UNKNOWN';
  if (roll < posture.notApplicable) return 'NOT_APPLICABLE';
  return 'PASS';
};

const buildFinding = (
  row: RawWarControl,
  baseline: WarBaseline,
  provider: Provider,
  accountId: string,
): WarFinding => {
  const [
    controlId, ordinal, familyIndex, subCategory, title, description, severity,
    tools, reference, controlType, impactIndex, impactLevel, packedPaths, automatedFlag,
  ] = row;

  const category = baseline.families[familyIndex];
  const profile = FAMILY_PROFILES[category] ?? FALLBACK_PROFILE;
  const pool = POOLS[provider];
  const automated = automatedFlag === 1;
  const seed = hash(`${accountId}|${controlId}`);
  const status = statusFor(automated, seed, POSTURES[accountId] ?? DEFAULT_POSTURE);

  const scanned = status === 'MANUAL' || status === 'UNKNOWN'
    ? 0
    : 6 + (seed % 58);
  const affected = status === 'FAIL'
    ? 1 + (seed % Math.max(1, Math.min(scanned, 9)))
    : status === 'WARN'
      ? 1 + (seed % 4)
      : 0;

  const subjects = profile.subjects(pool);
  const evidenceCount = status === 'FAIL'
    ? Math.min(affected, 6)
    : status === 'WARN'
      ? Math.min(affected, 2)
      : status === 'PASS' && seed % 3 === 0
        ? 1
        : 0;
  const evidence = Array.from({ length: evidenceCount }, (_, i) => {
    const lineSeed = seed + i * 31;
    return profile.line(pick(subjects, lineSeed), pool, lineSeed);
  });

  let summary: string;
  switch (status) {
    case 'FAIL':
      summary = profile.failSummary(affected, scanned, pool);
      break;
    case 'WARN':
      summary = profile.warnSummary(affected, scanned, pool);
      break;
    case 'PASS':
      summary = profile.passSummary(scanned, pool);
      break;
    case 'NOT_APPLICABLE':
      summary = profile.notApplicable(pool);
      break;
    case 'UNKNOWN':
      // Matches the backend's wording, which names the cause when it has one rather
      // than leaving the reader to hunt for it.
      summary = `Could not verify — the scan was denied ${profile.denied[provider]}`;
      break;
    default:
      summary = 'Needs human review — no API can prove this from a member account';
  }

  const attackPaths = packedPaths ? packedPaths.split('|') : [];
  const multiplier = status === 'FAIL' ? 2 : status === 'WARN' || status === 'UNKNOWN' ? 1 : 0;

  return {
    controlId,
    ordinal,
    category,
    subCategory,
    title,
    description,
    severity,
    tools,
    reference,
    controlType,
    businessImpact: baseline.impacts[impactIndex],
    impactLevel,
    attackPaths,
    attackPathLabels: attackPaths.map((p) => PATH_LABELS[p] ?? p),
    status,
    automated,
    summary,
    evidence,
    affectedCount: affected,
    scannedCount: scanned,
    region: profile.regional && status !== 'MANUAL' && status !== 'UNKNOWN'
      ? pick(pool.regions, seed)
      : null,
    deniedAction: status === 'UNKNOWN' ? profile.denied[provider] : null,
    riskWeight: weightOf(severity) * multiplier + Math.min(affected, 5),
  };
};

// ─── Aggregation — port of WarReportAssembler.assemble ───────────────────────

/** Below this share of checkable controls verified, the score is not a rating. */
const MIN_COVERAGE = 0.5;

const TOP_RISK_LIMIT = 10;

const countBy = (findings: WarFinding[], status: WarStatus): number =>
  findings.filter((f) => f.status === status).length;

/**
 * Weighted pass rate across controls that produced a verdict. WARN earns half credit;
 * MANUAL, NOT_APPLICABLE and UNKNOWN are excluded from the denominator entirely — an
 * unverifiable control is our blind spot, not the customer's failure.
 */
const scoreOf = (findings: WarFinding[]): number => {
  let earned = 0;
  let possible = 0;
  findings.forEach((f) => {
    if (!isScored(f.status)) return;
    const weight = weightOf(f.severity);
    possible += weight;
    if (f.status === 'PASS') earned += weight;
    if (f.status === 'WARN') earned += Math.floor(weight / 2);
  });
  return possible === 0 ? 0 : Math.round((100 * earned) / possible);
};

const riskLevelOf = (score: number, openCriticals: number): WarReport['riskLevel'] => {
  if (openCriticals >= 3 || score < 50) return 'CRITICAL';
  if (openCriticals >= 1 || score < 70) return 'HIGH';
  return score < 85 ? 'MEDIUM' : 'LOW';
};

const SEVERITY_RANK: Record<string, number> = {
  Critical: 0, High: 1, Medium: 2, Low: 3,
};

const attackPathSummaries = (findings: WarFinding[]): WarAttackPathSummary[] => {
  const summaries: WarAttackPathSummary[] = [];

  ATTACK_PATHS.forEach(({ name, label, description }) => {
    const group = findings.filter((f) => f.attackPaths.includes(name));
    if (group.length === 0) return;

    const failed = countBy(group, 'FAIL');
    const warned = countBy(group, 'WARN');
    const unverified = countBy(group, 'UNKNOWN');
    const passed = countBy(group, 'PASS');

    const worstSeverity = group
      .filter((f) => isOpen(f.status))
      .map((f) => normaliseSeverity(f.severity))
      .sort((a, b) => (SEVERITY_RANK[a] ?? 3) - (SEVERITY_RANK[b] ?? 3))[0] ?? 'None';

    // NEEDS_REVIEW rather than CONTAINED when nothing on the path was actually
    // verified — a green badge over an unexamined path is the false assurance the
    // status vocabulary exists to prevent.
    const state = failed > 0 ? 'OPEN'
      : warned > 0 ? 'PARTIAL'
        : unverified > 0 ? 'UNVERIFIED'
          : passed === 0 ? 'NEEDS_REVIEW'
            : 'CONTAINED';

    summaries.push({
      path: name,
      label,
      description,
      failed,
      warned,
      unverified,
      total: group.length,
      affectedResources: group.reduce((sum, f) => sum + f.affectedCount, 0),
      state,
      worstSeverity,
      topControlIds: group
        .filter((f) => isOpen(f.status))
        .sort((a, b) => b.riskWeight - a.riskWeight)
        .slice(0, 5)
        .map((f) => f.controlId),
    });
  });

  // Most exposed path first: confirmed failures outrank weakened, which outrank merely
  // unverified. This ordering is what the attack-paths tab leads with.
  return summaries.sort((a, b) => b.failed - a.failed
    || b.warned - a.warned
    || b.unverified - a.unverified);
};

const categorySummaries = (
  findings: WarFinding[],
  families: string[],
): WarCategorySummary[] => families
  .map((category) => {
    const group = findings.filter((f) => f.category === category);
    return {
      category,
      total: group.length,
      passed: countBy(group, 'PASS'),
      failed: countBy(group, 'FAIL'),
      warned: countBy(group, 'WARN'),
      manual: countBy(group, 'MANUAL'),
      unknown: countBy(group, 'UNKNOWN'),
      notApplicable: countBy(group, 'NOT_APPLICABLE'),
      score: scoreOf(group),
    };
  })
  .filter((c) => c.total > 0);

/**
 * Report timestamp in the backend's format — {@code yyyy-MM-dd HH:mm:ss z} in
 * Asia/Kolkata. The page prints this string verbatim, so it has to look like the real one
 * rather than an ISO instant.
 */
const generatedAt = (): string => {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Kolkata',
    year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', second: '2-digit',
    hour12: false,
  }).formatToParts(new Date());
  const get = (type: string) => parts.find((p) => p.type === type)?.value ?? '00';
  return `${get('year')}-${get('month')}-${get('day')} `
    + `${get('hour')}:${get('minute')}:${get('second')} IST`;
};

/**
 * Incomplete checks, for the Scan health tab.
 *
 * Every provider has one degraded region in the fixture, because a scan that reads a whole
 * account perfectly is the uninteresting case — the tab exists to explain partial coverage,
 * and demoing it empty would hide the feature.
 */
const scanWarningsFor = (provider: Provider, seed: number): string[] => {
  const pool = POOLS[provider];
  const region = pool.degradedRegion;
  const calls = provider === 'GCP'
    ? ['compute.instances.list', 'container.clusters.list', 'monitoring.timeSeries.list']
    : provider === 'Azure'
      ? ['Microsoft.Compute/virtualMachines/read', 'Microsoft.ContainerService/managedClusters/read', 'Microsoft.Insights/metrics/read']
      : ['ec2:DescribeInstances', 'eks:ListClusters', 'cloudwatch:GetMetricData'];

  const warnings = calls.map((call, i) => `${call} (${region}) — request timed out after `
    + `${25 + ((seed + i * 7) % 20)}s, region skipped`);

  warnings.push(`${pool.terms.flowLogs} (${region}) — partial result set, `
    + `${2 + (seed % 5)} subnets not returned before the scan window closed`);
  warnings.push(`${pool.terms.threat} (${region}) — detector state unavailable, `
    + 'the mining checks for this region are reported as unverified');

  return warnings;
};

const blockedActionsFor = (provider: Provider): string[] => (
  provider === 'GCP'
    ? ['compute.instances.list', 'container.clusters.list']
    : provider === 'Azure'
      ? ['Microsoft.Compute/virtualMachines/read', 'Microsoft.ContainerService/managedClusters/read']
      : ['ec2:DescribeInstances', 'eks:ListClusters']
);

// ─── Report ──────────────────────────────────────────────────────────────────

const BASELINES: Record<Provider, WarBaseline> = {
  AWS: AWS_BASELINE,
  GCP: GCP_BASELINE,
  Azure: AZURE_BASELINE,
};

/** Account id → provider, matching the ids in demoAccounts. */
const providerOf = (accountId: string): Provider => {
  if (accountId.includes('gcp')) return 'GCP';
  if (accountId.includes('azure')) return 'Azure';
  return 'AWS';
};

const ACCOUNT_NAMES: Record<string, string> = {
  'demo-aws-001': 'Xammer Production',
  'demo-aws-002': 'Xammer Staging',
  'demo-gcp-001': 'GCP Analytics',
  'demo-azure-001': 'Azure Enterprise',
};

const buildReport = (accountId: string): WarReport => {
  const provider = providerOf(accountId);
  const baseline = BASELINES[provider];
  const pool = POOLS[provider];
  const accountSeed = hash(accountId);

  const findings = baseline.controls
    .map((row) => buildFinding(row, baseline, provider, accountId));

  // Status keys are pre-seeded so a status absent from this account still reports 0
  // rather than undefined — several panels read these directly.
  const statusCounts: Record<string, number> = {
    PASS: 0, FAIL: 0, WARN: 0, MANUAL: 0, UNKNOWN: 0, NOT_APPLICABLE: 0,
  };
  const severityCounts: Record<string, number> = {
    Critical: 0, High: 0, Medium: 0, Low: 0,
  };

  findings.forEach((f) => {
    statusCounts[f.status] += 1;
    // Severity is counted for open findings only: the headline "N critical open" must
    // not include controls that passed.
    if (isOpen(f.status)) {
      severityCounts[normaliseSeverity(f.severity)] += 1;
    }
  });

  const verifiedControls = statusCounts.PASS + statusCounts.FAIL + statusCounts.WARN;
  const scoreable = verifiedControls + statusCounts.UNKNOWN;
  const assessable = verifiedControls > 0 && verifiedControls >= scoreable * MIN_COVERAGE;
  const score = scoreOf(findings);

  const deniedActions = Array.from(new Set(
    findings
      .filter((f) => f.status === 'UNKNOWN' && f.deniedAction)
      .map((f) => f.deniedAction as string),
  ));

  return {
    accountId,
    accountName: ACCOUNT_NAMES[accountId] ?? 'Demo Account',
    provider,
    generatedAt: generatedAt(),
    scannedRegions: pool.regions,

    warScore: assessable ? score : 0,
    riskLevel: assessable
      ? riskLevelOf(score, severityCounts.Critical)
      : 'UNKNOWN',

    totalControls: baseline.controls.length,
    automatedControls: baseline.controls.filter((row) => row[13] === 1).length,
    verifiedControls,
    assessable,
    // Coverage is partial by region here, not blocked account-wide, so there is no single
    // dominant cause — leaving this null keeps WarBlockedBanner correctly hidden.
    blockedReason: null,

    statusCounts,
    severityCounts,

    attackPaths: attackPathSummaries(findings),
    categories: categorySummaries(findings, baseline.families),
    findings,
    topRisks: findings
      .filter((f) => f.status === 'FAIL' || f.status === 'WARN')
      .sort((a, b) => b.riskWeight - a.riskWeight || a.ordinal - b.ordinal)
      .slice(0, TOP_RISK_LIMIT),

    deniedActions,
    blockedActions: blockedActionsFor(provider),
    scanWarnings: scanWarningsFor(provider, accountSeed),
  };
};

/**
 * Cached per account so every tab reads the same numbers.
 *
 * The page re-reads the report on a re-scan, and the five tabs derive their badge counts
 * from it. Rebuilding on each call would re-roll nothing (the synthesis is seeded) but
 * would move `generatedAt`, so the "last scan" timestamp in the topbar would drift
 * against the report the user is looking at.
 */
const reportCache = new Map<string, WarReport>();

/** GET /api/xamops/war/report?accountId=… */
export const demoWarReport = (accountId?: string | null): WarReport => {
  const key = accountId && accountId.trim() ? accountId : 'demo-aws-001';
  const cached = reportCache.get(key);
  if (cached) return cached;
  const report = buildReport(key);
  reportCache.set(key, report);
  return report;
};

/** POST /api/xamops/war/scan?accountId=… */
export const demoWarScanAck = {
  status: 'STARTED',
  message: 'Scan queued. In demo mode the report is served from a fixed fixture.',
};
