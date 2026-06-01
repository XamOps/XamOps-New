/**
 * XamOps Demo Mock Data  (ROLE_XAMOPS)
 */

import type { Account } from '../types/account';

// ─── /api/xamops/account-manager/accounts ────────────────────────────────────
export const demoAccounts: Account[] = [
  { id: 'demo-aws-001', name: 'Xammer Production',  provider: 'AWS',   status: 'CONNECTED', grafanaIp: 'play.grafana.org', grafanaDashboardUid: '000000012', grafanaDashboardSlug: 'grafana-play-home' },
  { id: 'demo-aws-002', name: 'Xammer Staging',     provider: 'AWS',   status: 'CONNECTED' },
  { id: 'demo-gcp-001', name: 'GCP Analytics',      provider: 'GCP',   status: 'CONNECTED' },
  { id: 'demo-azure-001', name: 'Azure Enterprise', provider: 'Azure', status: 'CONNECTED' },
];

// ─── /api/xamops/dashboard/granular/kpi ──────────────────────────────────────
export const demoGranularKPI = {
  accountId: 'demo-aws-001',
  lastUpdated: new Date().toISOString(),
  selectedAccount: {
    kpi: {
      mtdSpend: 18420.75,
      forecastedSpend: 24800.00,
      lastMonthSpend: 116101.00,
      potentialSavings: 3150.50,
    },
  },
};

// ─── /api/xamops/dashboard/granular/inventory ────────────────────────────────
export const demoGranularInventory = {
  accountId: 'demo-aws-001',
  selectedAccount: {
    resourceInventory: {
      ec2Count: 94,
      s3Count: 48,
      rdsCount: 22,
      vpcCount: 8,
      lambdaCount: 34,
      ebsCount: 67,
      route53Count: 12,
      loadBalancerCount: 6,
      ecsCount: 9,
      eksCount: 3,
      lightsailCount: 0,
      amplifyCount: 2,
    },
  },
};

// ─── /api/xamops/dashboard/granular/map ──────────────────────────────────────
export const demoGranularMap = {
  accountId: 'demo-aws-001',
  selectedAccount: {
    regionStatus: [
      { regionId: 'us-east-1',    latitude: 38.89,  longitude: -77.03, status: 'ACTIVE',      regionName: 'US East (N. Virginia)',  resourceCount: 142 },
      { regionId: 'eu-west-1',    latitude: 53.35,  longitude: -6.26,  status: 'ACTIVE',      regionName: 'Europe (Ireland)',       resourceCount: 86 },
      { regionId: 'ap-south-1',   latitude: 19.07,  longitude: 72.87,  status: 'SEMI_ACTIVE',  regionName: 'Asia Pacific (Mumbai)',  resourceCount: 54 },
      { regionId: 'us-west-2',    latitude: 45.52,  longitude: -122.67, status: 'SUSTAINABLE', regionName: 'US West (Oregon)',       resourceCount: 30 },
    ],
  },
};

// ─── /api/xamops/dashboard/granular/cost ─────────────────────────────────────
export const demoGranularCost = {
  accountId: 'demo-aws-001',
  selectedAccount: {
    costHistory: {
      labels: ['Nov', 'Dec', 'Jan', 'Feb', 'Mar', 'Apr'],
      costs:  [14200, 16800, 15400, 17900, 116101, 18420],
      anomalies: [false, false, false, false, false, false],
    },
    billingSummary: {
      totalCost: 18420.75,
      services: [
        { serviceName: 'EC2',         cost: 7840.00, percentage: 42.6 },
        { serviceName: 'RDS',         cost: 3680.50, percentage: 20.0 },
        { serviceName: 'S3',          cost: 1920.25, percentage: 10.4 },
        { serviceName: 'Lambda',      cost: 1240.00, percentage: 6.7  },
        { serviceName: 'CloudFront',  cost: 840.00,  percentage: 4.6  },
        { serviceName: 'Others',      cost: 2900.00, percentage: 15.7 },
      ],
    },
  },
};

// ─── /api/xamops/dashboard/granular/optimization ─────────────────────────────
export const demoGranularOptimization = {
  accountId: 'demo-aws-001',
  selectedAccount: {
    optimizationSummary: {
      totalRecommendations: 12,
      totalPotentialSavings: 3150.50,
      potentialMonthlySavings: 3150.50,
      criticalAlerts: 3,
      criticalAlertsCount: 3,
      ec2RecommendationCount: 5,
      ebsRecommendationCount: 4,
      lambdaRecommendationCount: 3,
    },
    costAnomalies: [],
    ec2Recommendations: [
      { instanceId: 'i-0abc1001', instanceType: 't3.large',   currentCost: 140.00, recommendedType: 't3.medium',  estimatedSavings: 70.00,  reason: 'Low CPU utilisation (<10% avg)' },
      { instanceId: 'i-0abc1002', instanceType: 'm5.xlarge',  currentCost: 320.00, recommendedType: 'm5.large',   estimatedSavings: 160.00, reason: 'Consistently under-utilised memory' },
      { instanceId: 'i-0abc1003', instanceType: 'r5.2xlarge', currentCost: 580.00, recommendedType: 'r5.xlarge',  estimatedSavings: 290.00, reason: 'Memory < 40% for 30 days' },
    ],
    ebsRecommendations: [
      { volumeId: 'vol-0def2001', volumeType: 'gp2', size: 500, currentCost: 50.00,  recommendation: 'Upgrade to gp3 for lower cost',   estimatedSavings: 15.00 },
      { volumeId: 'vol-0def2002', volumeType: 'gp2', size: 200, currentCost: 20.00,  recommendation: 'Delete — unattached for 60 days',   estimatedSavings: 20.00 },
    ],
    lambdaRecommendations: [
      { functionName: 'data-processor',  currentMemory: 1024, recommendedMemory: 512, currentCost: 48.00, estimatedSavings: 22.00, reason: 'Memory utilisation < 30%' },
    ],
  },
};

// ─── /api/xamops/dashboard/data/multi-account ────────────────────────────────
export const demoMultiAccountDashboard = {
  accounts: [
    {
      accountId: 'demo-aws-001',
      accountName: 'Xammer Production',
      provider: 'AWS',
      kpi: { mtdSpend: 12340.50, forecastedSpend: 16500.00, lastMonthSpend: 116101.00, potentialSavings: 2100.00 },
      resourceInventory: { ec2Count: 62, s3Count: 32, rdsCount: 14, vpcCount: 5, lambdaCount: 22, ebsCount: 44, route53Count: 8, loadBalancerCount: 4, ecsCount: 6, eksCount: 2, lightsailCount: 0, amplifyCount: 1 },
      regionStatus: demoGranularMap.selectedAccount.regionStatus,
      costHistory: { labels: ['Nov', 'Dec', 'Jan', 'Feb', 'Mar', 'Apr'], costs: [8900, 10200, 9600, 11100, 10200, 12340], anomalies: [false, false, false, false, false, false] },
      optimization: { summary: { totalRecommendations: 8, totalPotentialSavings: 2100, criticalAlerts: 2, ec2RecommendationCount: 3, ebsRecommendationCount: 3, lambdaRecommendationCount: 2 }, anomalies: [], ec2Recommendations: [], ebsRecommendations: [], lambdaRecommendations: [] },
      billingSummary: { totalCost: 12340.50, services: [{ serviceName: 'EC2', cost: 5200, percentage: 42.1 }, { serviceName: 'RDS', cost: 2400, percentage: 19.4 }] },
      lastUpdated: new Date().toISOString(),
    },
    {
      accountId: 'demo-aws-002',
      accountName: 'Xammer Staging',
      provider: 'AWS',
      kpi: { mtdSpend: 6080.25, forecastedSpend: 8300.00, lastMonthSpend: 116101.00, potentialSavings: 1050.50 },
      resourceInventory: { ec2Count: 32, s3Count: 16, rdsCount: 8, vpcCount: 3, lambdaCount: 12, ebsCount: 23, route53Count: 4, loadBalancerCount: 2, ecsCount: 3, eksCount: 1, lightsailCount: 0, amplifyCount: 1 },
      regionStatus: [],
      costHistory: { labels: ['Nov', 'Dec', 'Jan', 'Feb', 'Mar', 'Apr'], costs: [5300, 6600, 5800, 6800, 6140, 6080], anomalies: [false, false, false, false, false, false] },
      optimization: { summary: { totalRecommendations: 4, totalPotentialSavings: 1050, criticalAlerts: 1, ec2RecommendationCount: 2, ebsRecommendationCount: 1, lambdaRecommendationCount: 1 }, anomalies: [], ec2Recommendations: [], ebsRecommendations: [], lambdaRecommendations: [] },
      billingSummary: { totalCost: 6080.25, services: [{ serviceName: 'EC2', cost: 2640, percentage: 43.4 }, { serviceName: 'RDS', cost: 1280, percentage: 21.0 }] },
      lastUpdated: new Date().toISOString(),
    },
  ],
  aggregated: {
    accountId: 'all',
    accountName: 'All Accounts',
    provider: 'AWS',
    kpi: { mtdSpend: 18420.75, forecastedSpend: 24800.00, lastMonthSpend: 116101.00, potentialSavings: 3150.50 },
    resourceInventory: { ec2Count: 94, s3Count: 48, rdsCount: 22, vpcCount: 8, lambdaCount: 34, ebsCount: 67, route53Count: 12, loadBalancerCount: 6, ecsCount: 9, eksCount: 3, lightsailCount: 0, amplifyCount: 2 },
    regionStatus: demoGranularMap.selectedAccount.regionStatus,
    costHistory: { labels: ['Nov', 'Dec', 'Jan', 'Feb', 'Mar', 'Apr'], costs: [14200, 16800, 15400, 17900, 116101, 18420], anomalies: [false, false, false, false, false, false] },
    optimization: { summary: { totalRecommendations: 12, totalPotentialSavings: 3150, criticalAlerts: 3, ec2RecommendationCount: 5, ebsRecommendationCount: 4, lambdaRecommendationCount: 3 }, anomalies: [], ec2Recommendations: [], ebsRecommendations: [], lambdaRecommendations: [] },
    billingSummary: { totalCost: 18420.75, services: [{ serviceName: 'EC2', cost: 7840, percentage: 42.6 }, { serviceName: 'RDS', cost: 3680, percentage: 20.0 }, { serviceName: 'S3', cost: 1920, percentage: 10.4 }] },
    lastUpdated: new Date().toISOString(),
  },
};

// ─── /api/xamops/dashboard/layout ────────────────────────────────────────────
export const demoDashboardLayout = {
  widgetOrder: ['widget-kpi', 'widget-middle', 'widget-optimization', 'widget-costs'],
};

// ─── /api/xamops/dashboard/unified/summary ───────────────────────────────────
export const demoUnifiedSummary = {
  selectedAccount: { awsAccountCount: 2, gcpAccountCount: 1, azureAccountCount: 1 },
};

// ─── /api/xamops/dashboard/unified/aws ───────────────────────────────────────
export const demoAwsData = {
  lastUpdated: new Date().toISOString(),
  selectedAccount: {
    monthToDateSpend: 18420.75,
    forecastedSpend: 24800.00,
    securityScore: 82,
    resourceInventory: { totalCount: 312, ec2Count: 94, s3Count: 48, rdsCount: 22 },
    optimizationSummary: { criticalAlerts: 3, totalPotentialSavings: 3150.50 },
    costHistory: {
      labels: ['Nov', 'Dec', 'Jan', 'Feb', 'Mar', 'Apr'],
      costs:  [14200, 16800, 15400, 17900, 116101, 18420],
    },
    subAccounts: [
      { id: 'demo-aws-001', name: 'Xammer Production', provider: 'AWS', monthToDateSpend: 12340.50, forecastedSpend: 16500.00, resourceCount: 210, resourceInventory: { totalCount: 210 }, optimizationSummary: { criticalAlerts: 2, totalPotentialSavings: 2100.00 }, securityScore: 80 },
      { id: 'demo-aws-002', name: 'Xammer Staging',    provider: 'AWS', monthToDateSpend: 6080.25,  forecastedSpend: 8300.00,  resourceCount: 102, resourceInventory: { totalCount: 102 }, optimizationSummary: { criticalAlerts: 1, totalPotentialSavings: 1050.50 }, securityScore: 86 },
    ],
  },
};

// ─── /api/xamops/dashboard/unified/gcp ───────────────────────────────────────
export const demoGcpData = {
  lastUpdated: new Date().toISOString(),
  selectedAccount: {
    monthToDateSpend: 7340.00, forecastedSpend: 9800.00, securityScore: 78,
    resourceInventory: { totalCount: 148 },
    optimizationSummary: { criticalAlerts: 1, totalPotentialSavings: 1240.00 },
    costHistory: { labels: ['Nov', 'Dec', 'Jan', 'Feb', 'Mar', 'Apr'], costs: [5800, 6400, 6100, 7000, 7200, 7340] },
    subAccounts: [
      { id: 'demo-gcp-001', name: 'GCP Analytics', provider: 'GCP', monthToDateSpend: 7340.00, forecastedSpend: 9800.00, resourceCount: 148, resourceInventory: { totalCount: 148 }, optimizationSummary: { criticalAlerts: 1, totalPotentialSavings: 1240.00 }, securityScore: 78 },
    ],
  },
};

// ─── /api/xamops/dashboard/unified/azure ─────────────────────────────────────
export const demoAzureData = {
  lastUpdated: new Date().toISOString(),
  selectedAccount: {
    monthToDateSpend: 5210.30, forecastedSpend: 6900.00, securityScore: 75,
    resourceInventory: { totalCount: 96 },
    optimizationSummary: { criticalAlerts: 2, totalPotentialSavings: 880.00 },
    costHistory: { labels: ['Nov', 'Dec', 'Jan', 'Feb', 'Mar', 'Apr'], costs: [4200, 4900, 4600, 5100, 5000, 5210] },
    subAccounts: [
      { id: 'demo-azure-001', name: 'Azure Enterprise', provider: 'Azure', monthToDateSpend: 5210.30, forecastedSpend: 6900.00, resourceCount: 96, resourceInventory: { totalCount: 96 }, optimizationSummary: { criticalAlerts: 2, totalPotentialSavings: 880.00 }, securityScore: 75 },
    ],
  },
};

export const demoHealth = { status: 'healthy' };
export const demoTicketCount = { count: 4 };

// ─── /api/xamops/costs/breakdown ─────────────────────────────────────────────
export const demoCostBreakdown = [
  { name: 'EC2',                  amount: 7840.00 },
  { name: 'RDS',                  amount: 3680.50 },
  { name: 'S3',                   amount: 1920.25 },
  { name: 'Lambda',               amount: 1240.00 },
  { name: 'CloudFront',           amount: 840.00  },
  { name: 'ElastiCache',          amount: 620.00  },
  { name: 'EKS',                  amount: 580.00  },
  { name: 'CloudWatch',           amount: 380.00  },
  { name: 'Route 53',             amount: 180.00  },
  { name: 'Data Transfer',        amount: 1140.00 },
];

// ─── /api/xamops/costs/historical-trend ──────────────────────────────────────
export const demoCostHistoricalTrend = {
  labels: ['Nov', 'Dec', 'Jan', 'Feb', 'Mar', 'Apr'],
  costs:  [14200, 16800, 15400, 17900, 116101, 18420],
};

// ─── /api/xamops/forecast/cost ───────────────────────────────────────────────
export const demoCostForecast = {
  status: 'success',
  forecast: {
    dates: ['2026-05-01', '2026-05-08', '2026-05-15', '2026-05-22', '2026-05-29', '2026-06-05'],
    predictions:  [4800, 5100, 5400, 5200, 5600, 5800],
    upperBounds:  [5200, 5500, 5900, 5700, 6100, 6300],
    lowerBounds:  [4400, 4700, 4900, 4700, 5100, 5300],
  },
};

// ─── /api/xamops/waste ────────────────────────────────────────────────────────
// WastedResource[] — matches the type in src/types/waste.ts
export const demoWasteData = [
  { resourceId: 'i-0abc1003', resourceName: 'prod-idle-01',   resourceType: 'EC2 Instance', region: 'us-east-1',  reason: 'Low CPU utilisation (<5%)',      monthlySavings: 420  },
  { resourceId: 'snap-0def4001', resourceName: 'db-backup-jan', resourceType: 'Snapshot',    region: 'us-east-1',  reason: 'Orphaned snapshot',               monthlySavings: 18   },
  { resourceId: 'vol-0ghi7001', resourceName: 'data-volume-01', resourceType: 'EBS Volume',  region: 'eu-west-1',  reason: 'Unattached EBS volume',           monthlySavings: 65   },
  { resourceId: 'eip-0jkl0001', resourceName: '52.23.145.67',  resourceType: 'Elastic IP',  region: 'us-west-2',  reason: 'Unassociated Elastic IP',         monthlySavings: 3.6  },
  { resourceId: 'i-0abc1004', resourceName: 'staging-web-02',  resourceType: 'EC2 Instance', region: 'ap-south-1', reason: 'Stopped instance (30+ days)',     monthlySavings: 280  },
];

// /api/xamops/security/findings — hook does Array.isArray(data) ? data : (data.findings || data)
// SecurityFinding type: resourceId, region, category, severity, description
export const demoSecurityData = [
  { resourceId: 's3-xammer-prod-001', region: 'us-east-1', category: 'Storage',  severity: 'CRITICAL', description: 'S3 bucket public access enabled',        complianceFramework: 'CIS', controlId: '2.1.5' },
  { resourceId: 'iam-policy-001',     region: 'us-east-1', category: 'Identity', severity: 'HIGH',     description: 'MFA not enabled on IAM user',            complianceFramework: 'CIS', controlId: '1.5'   },
  { resourceId: 'iam-key-002',        region: 'us-east-1', category: 'Identity', severity: 'MEDIUM',   description: 'Unused IAM access key older than 90 days',complianceFramework: 'CIS', controlId: '1.14'  },
  { resourceId: 'sg-0abc1001',        region: 'eu-west-1', category: 'Network',  severity: 'HIGH',     description: 'Security group allows 0.0.0.0/0 on SSH',  complianceFramework: 'CIS', controlId: '5.2'   },
  { resourceId: 'rds-xammer-001',     region: 'us-east-1', category: 'Database', severity: 'MEDIUM',   description: 'RDS instance not encrypted at rest',       complianceFramework: 'CIS', controlId: '2.3.1' },
  { resourceId: 'ec2-prod-web-01',    region: 'ap-south-1',category: 'Compute',  severity: 'LOW',      description: 'EC2 instance using IMDSv1',                complianceFramework: 'CIS', controlId: '5.6'   },
];

// /api/xamops/rightsizing/recommendations — AWSRecommendation[] (hook does Array.isArray check)
export const demoRightsizingData = [
  { resourceId: 'i-0abc1001', instanceName: 'prod-web-01',  service: 'EC2', currentType: 't3.large',   recommendedType: 't3.medium',  currentMonthlyCost: 840,  recommendedMonthlyCost: 420,  estimatedMonthlySavings: 420,  recommendationReason: 'CPU utilisation below 15% over 14 days', region: 'us-east-1' },
  { resourceId: 'i-0abc1002', instanceName: 'prod-api-01',  service: 'EC2', currentType: 'm5.xlarge',  recommendedType: 'm5.large',   currentMonthlyCost: 1360, recommendedMonthlyCost: 680,  estimatedMonthlySavings: 680,  recommendationReason: 'Average CPU < 20%, memory headroom available', region: 'us-east-1' },
  { resourceId: 'i-0abc1003', instanceName: 'prod-db-01',   service: 'EC2', currentType: 'r5.2xlarge', recommendedType: 'r5.xlarge',  currentMonthlyCost: 3480, recommendedMonthlyCost: 1740, estimatedMonthlySavings: 1740, recommendationReason: 'Memory utilisation consistently below 45%', region: 'eu-west-1' },
];

// ─── /api/xamops/metrics/insights ────────────────────────────────────────────
// PerformanceInsight[] — matches src/types/performance.ts PerformanceInsight interface
export const demoPerformanceInsights = [
  { id: 'pi-001', insight: 'High CPU Utilization', description: '3 EC2 instances with sustained CPU > 85%', severity: 'CRITICAL' as const, category: 'PERFORMANCE' as const, account: 'Xammer Production', quantity: 3, resourceType: 'EC2', resourceId: 'i-0abc1001', recommendation: 'Scale up instance type or optimise workload', potentialSavings: 0, region: 'us-east-1', createdDate: '2026-04-28' },
  { id: 'pi-002', insight: 'Memory Pressure Detected', description: 'RDS instance memory consistently above 90%', severity: 'CRITICAL' as const, category: 'PERFORMANCE' as const, account: 'Xammer Production', quantity: 1, resourceType: 'RDS', resourceId: 'rds-xammer-001', recommendation: 'Upgrade to a higher memory instance class', potentialSavings: 0, region: 'us-east-1', createdDate: '2026-04-27' },
  { id: 'pi-003', insight: 'Idle EC2 Instances', description: '2 instances with CPU < 5% for 14 days', severity: 'WARNING' as const, category: 'COST' as const, account: 'Xammer Staging', quantity: 2, resourceType: 'EC2', resourceId: 'i-0abc1003', recommendation: 'Stop or terminate idle instances to reduce costs', potentialSavings: 420, region: 'eu-west-1', createdDate: '2026-04-26' },
  { id: 'pi-004', insight: 'Unencrypted EBS Volumes', description: '4 EBS volumes without encryption enabled', severity: 'WARNING' as const, category: 'SECURITY' as const, account: 'Xammer Production', quantity: 4, resourceType: 'EBS', recommendation: 'Enable EBS encryption for data-at-rest protection', potentialSavings: 0, region: 'us-east-1', createdDate: '2026-04-25' },
  { id: 'pi-005', insight: 'Missing Resource Tags', description: '23 resources lack required cost allocation tags', severity: 'WARNING' as const, category: 'BEST_PRACTICE' as const, account: 'Xammer Production', quantity: 23, recommendation: 'Apply Environment, Owner, and Project tags consistently', potentialSavings: 0, region: 'us-east-1', createdDate: '2026-04-24' },
  { id: 'pi-006', insight: 'gp2 Volumes Should be gp3', description: '6 gp2 volumes can be upgraded to gp3 for cost savings', severity: 'WEAK_WARNING' as const, category: 'COST' as const, account: 'Xammer Staging', quantity: 6, resourceType: 'EBS', recommendation: 'Migrate gp2 volumes to gp3 for 20% cost reduction', potentialSavings: 84, region: 'us-east-1', createdDate: '2026-04-23' },
];

// PerformanceSummary — matches src/types/performance.ts PerformanceSummary interface
export const demoPerformanceSummary = {
  performanceScore: 72,
  totalInsights: 6,
  critical: 2,
  warning: 3,
  weakWarning: 1,
};

// Keep old name for any remaining imports
export const demoPerformanceData = demoPerformanceInsights;

// ─── /api/xamops/cloudguard/alerts ───────────────────────────────────────────
// AlertsResponse shape: {alerts: Alert[], errors: AlertError[], metadata: AlertsMetadata}
// Each Alert must have: id, service, type ('QUOTA'|'ANOMALY'|'SECURITY'), name, usage, description, region, limit? (for QUOTA)
export const demoAlertsData = {
  alerts: [
    { id: 'qa-001', service: 'EC2', type: 'QUOTA', name: 'Running On-Demand Standard Instances', limit: 100, usage: 94, description: 'vCPU quota at 94% — approaching service limit', region: 'us-east-1' },
    { id: 'qa-002', service: 'VPC', type: 'QUOTA', name: 'VPCs per Region', limit: 5, usage: 4, description: 'VPC count at 80% of regional limit', region: 'us-east-1' },
    { id: 'qa-003', service: 'RDS', type: 'QUOTA', name: 'DB Instances', limit: 40, usage: 22, description: 'RDS instance count within normal range', region: 'us-east-1' },
    { id: 'an-001', service: 'EC2', type: 'ANOMALY', name: 'EC2 Cost Spike', usage: 0, description: 'EC2 spend increased 42% vs prior week — possible runaway instance', region: 'us-east-1' },
    { id: 'an-002', service: 'S3', type: 'ANOMALY', name: 'S3 Data Transfer Anomaly', usage: 0, description: 'Unusual outbound data transfer from S3 bucket xammer-prod-assets', region: 'eu-west-1' },
  ],
  errors: [],
  metadata: {
    totalServices: 3,
    successfulServices: 3,
    failedServices: 0,
    cacheHit: false,
    fetchDuration: 1240,
  },
};

// ─── /api/xamops/aiops/* ──────────────────────────────────────────────────────
// LlmKpis
export const demoAIOpsKpis = {
  totalSpend: 1284.60,
  requestsToday: 4820,
  requestsThisMonth: 142600,
  todayVsYesterdayPct: 8.4,
  monthVsLastMonthPct: 12.1,
  inputTokens: 84200000,
  outputTokens: 21400000,
  avgLatencyMs: 842,
};

// AllTimeMetrics
export const demoAIOpsAllTimeMetrics = {
  allTimeCost: 6420.80,
  allTimeTokens: 480000000,
  allTimeRequests: 820400,
};

// CostTrend
export const demoAIOpsAdminCostTrend = {
  labels: ['Nov', 'Dec', 'Jan', 'Feb', 'Mar', 'Apr'],
  costs:  [680, 820, 940, 1020, 1140, 1285],
};

// ProviderCostBreakdown[]
export const demoAIOpsProviderBreakdown = [
  { provider: 'OPENAI',    cost: 620.40, requestCount: 68400 },
  { provider: 'ANTHROPIC', cost: 482.20, requestCount: 41200 },
  { provider: 'GEMINI',    cost: 182.00, requestCount: 33000 },
];

// ModelUsageStat[]
export const demoAIOpsModelUsage = [
  { model: 'gpt-4o',              provider: 'OPENAI',    requests: 42000, totalTokens: 62400000, cost: 480.20, avgLatencyMs: 1240 },
  { model: 'gpt-4o-mini',        provider: 'OPENAI',    requests: 26400, totalTokens: 21000000, cost: 140.20, avgLatencyMs: 620  },
  { model: 'claude-sonnet-4-6',  provider: 'ANTHROPIC', requests: 28000, totalTokens: 38200000, cost: 342.80, avgLatencyMs: 980  },
  { model: 'claude-haiku-4-5',   provider: 'ANTHROPIC', requests: 13200, totalTokens: 12400000, cost: 139.40, avgLatencyMs: 420  },
  { model: 'gemini-2.0-flash',   provider: 'GEMINI',    requests: 33000, totalTokens: 18400000, cost: 182.00, avgLatencyMs: 540  },
];

// CostAnomaly[]
export const demoAIOpsAnomalies = [
  { date: '2026-04-18', provider: 'OPENAI',    actualCost: 84.20, expectedCost: 42.10, deviationPct: 100.0, severity: 'CRITICAL' as const },
  { date: '2026-04-22', provider: 'ANTHROPIC', actualCost: 52.40, expectedCost: 38.20, deviationPct: 37.2,  severity: 'WARNING'  as const },
];

// LlmProviderConfig[] — empty so page shows "Add Provider" state, no crash
export const demoAIOpsProviders = [
  { id: 1, provider: 'OPENAI',    displayName: 'OpenAI GPT-4o',      status: 'ACTIVE',   proxyBaseUrl: 'https://api.openai.com',         trackingToken: 'xm-demo-openai-001',    createdAt: '2026-01-10T09:00:00Z' },
  { id: 2, provider: 'ANTHROPIC', displayName: 'Anthropic Claude',   status: 'ACTIVE',   proxyBaseUrl: 'https://api.anthropic.com',       trackingToken: 'xm-demo-anthropic-001', createdAt: '2026-01-12T11:00:00Z' },
  { id: 3, provider: 'GEMINI',    displayName: 'Google Gemini Flash', status: 'ACTIVE',  proxyBaseUrl: 'https://generativelanguage.googleapis.com', trackingToken: 'xm-demo-gemini-001', createdAt: '2026-02-01T08:00:00Z' },
];

// ─── /api/xamops/finops/* sub-paths ──────────────────────────────────────────
// billing-summary: Array<{ serviceName, monthToDateCost }>
export const demoFinOpsBillingSummary = [
  { serviceName: 'EC2',           monthToDateCost: 7840.00 },
  { serviceName: 'RDS',           monthToDateCost: 3680.50 },
  { serviceName: 'S3',            monthToDateCost: 1920.25 },
  { serviceName: 'Lambda',        monthToDateCost: 1240.00 },
  { serviceName: 'Data Transfer', monthToDateCost: 1140.00 },
  { serviceName: 'CloudFront',    monthToDateCost:  840.00 },
  { serviceName: 'EKS',           monthToDateCost:  540.00 },
  { serviceName: 'ElastiCache',   monthToDateCost:  520.00 },
  { serviceName: 'CloudWatch',    monthToDateCost:  420.00 },
  { serviceName: 'Route 53',      monthToDateCost:  279.25 },
]; // total = $18,420.00

// cost-trend: { labels, costs, anomalies }
// costs[length-2] drives lastMonthSpend KPI → must equal dashboard's $116,101
export const demoFinOpsCostTrend = {
  labels:    ['Nov', 'Dec', 'Jan', 'Feb', 'Mar', 'Apr'],
  costs:     [11200, 12800, 13400, 14900, 116101, 18420],
  anomalies: [false, false, false, true,  false, false],
};

// regional-breakdown: Array<{ region, cost }>  — total aligned with MTD $18,420
export const demoFinOpsRegionalBreakdown = [
  { region: 'us-east-1',  cost: 8840 },
  { region: 'eu-west-1',  cost: 5120 },
  { region: 'ap-south-1', cost: 3200 },
  { region: 'us-west-2',  cost: 1260 },
];

// tagging-compliance: matches TaggingCompliance type in src/types/finops.ts
export const demoFinOpsTaggingCompliance = {
  compliancePercentage: 74,
  totalResourcesScanned: 191,
  untaggedResourcesCount: 49,
  untaggedResources: [
    { resourceId: 'i-0abc1001', resourceType: 'EC2',     region: 'us-east-1' },
    { resourceId: 'vol-0ghi001',resourceType: 'EBS',     region: 'us-east-1' },
    { resourceId: 'rds-0abc001',resourceType: 'RDS',     region: 'eu-west-1' },
    { resourceId: 'lmb-0abc001',resourceType: 'Lambda',  region: 'us-east-1' },
    { resourceId: 's3-bucket-01',resourceType: 'S3',    region: 'us-east-1' },
  ],
};

// Keep old name for backward compat with anything that imported it
export const demoFinOpsReport = demoFinOpsBillingSummary;

// SonarQube: return empty array — shows empty state without crashing
export const demoSonarQubeProjects = [
  { id: 1, projectName: 'cloud-api-service',    projectKey: 'xammer:cloud-api-service',    serverUrl: 'https://sonarcloud.io', token: 'demo-token' },
  { id: 2, projectName: 'frontend-app',          projectKey: 'xammer:frontend-app',          serverUrl: 'https://sonarcloud.io', token: 'demo-token' },
  { id: 3, projectName: 'infra-terraform',       projectKey: 'xammer:infra-terraform',       serverUrl: 'https://sonarcloud.io', token: 'demo-token' },
  { id: 4, projectName: 'data-pipeline-service', projectKey: 'xammer:data-pipeline-service', serverUrl: 'https://sonarcloud.io', token: 'demo-token' },
];

export const demoSonarQubeMetrics = {
  qualityGateStatus: 'OK',
  linesOfCode: 42840,
  bugs: 3,
  vulnerabilities: 1,
  securityHotspots: 5,
  codeSmells: 47,
  coverage: 82.4,
  duplicationDensity: 3.1,
  duplicatedLines: 280,
  linesToCover: 18600,
  techDebt: 840,
  reliabilityRating: 'A',
  securityRating: 'A',
  securityReviewRating: 'B',
  maintainabilityRating: 'A',
};

// ─── /api/xamops/gcp/costs/breakdown ─────────────────────────────────────────
export const demoGcpCostBreakdown = [
  { name: 'Compute Engine', amount: 3240.00 },
  { name: 'Cloud SQL',      amount: 1620.00 },
  { name: 'Cloud Storage',  amount: 840.00  },
  { name: 'GKE',            amount: 940.00  },
  { name: 'BigQuery',       amount: 700.00  },
];

// ─── /api/xamops/reservations ─────────────────────────────────────────────────
export const demoReservationInventory = {
  analysis: { utilizationPercentage: 78.4, coveragePercentage: 65.2 },
  inventory: [
    { reservationId: 'ri-0abc0001', instanceType: 'm5.large',  instanceCount: 5, scope: 'Region', utilizationPercentage: 82.3, state: 'active', start: '2024-01-01', end: '2025-01-01' },
    { reservationId: 'ri-0abc0002', instanceType: 't3.medium', instanceCount: 3, scope: 'Region', utilizationPercentage: 94.1, state: 'active', start: '2024-06-01', end: '2025-06-01' },
    { reservationId: 'ri-0abc0003', instanceType: 'r5.xlarge', instanceCount: 2, scope: 'Region', utilizationPercentage: 71.0, state: 'active', start: '2023-12-01', end: '2024-12-01' },
  ],
  savingsPlans: [
    { savingsPlanId: 'sp-0abc0001', type: 'ComputeSavingsPlans', region: 'us-east-1', commitment: '0.50/hr', paymentOption: 'NoUpfront', state: 'active', start: '2024-01-01', end: '2026-01-01', utilizationPercentage: 84.6, usedCommitment: '0.42', unusedCommitment: '0.08', netSavings: '184.20' },
    { savingsPlanId: 'sp-0abc0002', type: 'EC2InstanceSavingsPlans', region: 'eu-west-1', commitment: '0.20/hr', paymentOption: 'PartialUpfront', state: 'active', start: '2025-01-01', end: '2026-01-01', utilizationPercentage: 97.1, usedCommitment: '0.19', unusedCommitment: '0.01', netSavings: '68.40' },
  ],
  savingsPlanRecommendations: [
    { savingsPlanType: 'COMPUTE_SP', region: null, instanceFamily: null, recommendedCommitment: '1.20', upfrontCost: '0.00', estimatedMonthlyCost: '876.00', estimatedMonthlySavings: '412.50', estimatedSavingsPercentage: '32.0', currentAverageHourlyOnDemandSpend: '1.77' },
    { savingsPlanType: 'EC2_INSTANCE_SP', region: 'us-east-1', instanceFamily: 'm5', recommendedCommitment: '0.35', upfrontCost: '0.00', estimatedMonthlyCost: '255.50', estimatedMonthlySavings: '98.20', estimatedSavingsPercentage: '27.8', currentAverageHourlyOnDemandSpend: '0.48' },
  ],
  historicalData: {
    labels: ['Nov', 'Dec', 'Jan', 'Feb', 'Mar', 'Apr'],
    utilizationPercentages: [72, 75, 78, 81, 78, 82],
    coveragePercentages:    [58, 61, 63, 65, 64, 67],
  },
};

// /api/xamops/reservations/recommendations
export const demoReservationRecommendations = [
  { instanceFamily: 'm5', region: 'us-east-1', platform: 'Linux', recommendedInstances: 3, onDemandCost: 420, estimatedMonthlyCost: 280, monthlySavings: 140 },
  { instanceFamily: 'r5', region: 'eu-west-1', platform: 'Linux', recommendedInstances: 2, onDemandCost: 680, estimatedMonthlyCost: 460, monthlySavings: 220 },
  { instanceFamily: 't3', region: 'us-east-1', platform: 'Linux', recommendedInstances: 5, onDemandCost: 240, estimatedMonthlyCost: 165, monthlySavings: 75  },
];

// /api/xamops/reservations/sp-recommendations
export const demoSpRecommendations = [
  { savingsPlanType: 'COMPUTE_SP',      region: null,        instanceFamily: null, recommendedCommitment: '1.20', upfrontCost: '0.00', estimatedMonthlyCost: '876.00', estimatedMonthlySavings: '412.50', estimatedSavingsPercentage: '32.0', currentAverageHourlyOnDemandSpend: '1.77' },
  { savingsPlanType: 'EC2_INSTANCE_SP', region: 'us-east-1', instanceFamily: 'm5', recommendedCommitment: '0.35', upfrontCost: '0.00', estimatedMonthlyCost: '255.50', estimatedMonthlySavings: '98.20',  estimatedSavingsPercentage: '27.8', currentAverageHourlyOnDemandSpend: '0.48' },
];

// ─── /api/autospotting/* ──────────────────────────────────────────────────────
export const demoAutoSpottingCosts = {
  summary: {
    total_current_hourly_cost:  4.82,
    total_ondemand_hourly_cost: 8.40,
    total_actual_savings:       3.58,
    total_potential_savings:    4.20,
  },
  asgs: [
    { asg_name: 'prod-web-asg',  region: 'us-east-1', autospotting_enabled: true,  instance_count: 5, spot_instance_count: 4, ondemand_instance_count: 1, instance_types: ['t3.large', 't3.xlarge'], current_hourly_cost: 1.82, ondemand_hourly_cost: 3.20, actual_hourly_savings: 1.38, potential_hourly_savings: 1.80, eks_managed: false },
    { asg_name: 'prod-api-asg',  region: 'us-east-1', autospotting_enabled: true,  instance_count: 3, spot_instance_count: 3, ondemand_instance_count: 0, instance_types: ['m5.large'],            current_hourly_cost: 1.20, ondemand_hourly_cost: 2.10, actual_hourly_savings: 0.90, potential_hourly_savings: 1.05, eks_managed: false },
    { asg_name: 'staging-asg',   region: 'eu-west-1', autospotting_enabled: false, instance_count: 2, spot_instance_count: 0, ondemand_instance_count: 2, instance_types: ['t3.medium'],           current_hourly_cost: 0.80, ondemand_hourly_cost: 0.80, actual_hourly_savings: 0,    potential_hourly_savings: 0.48, eks_managed: false },
  ],
};

export const demoAutoSpottingHistory = {
  data_points: [
    { timestamp: '2026-04-24T00:00:00Z', total_actual_savings: 78.4, total_potential_savings: 92.0 },
    { timestamp: '2026-04-25T00:00:00Z', total_actual_savings: 84.2, total_potential_savings: 94.5 },
    { timestamp: '2026-04-26T00:00:00Z', total_actual_savings: 82.6, total_potential_savings: 91.8 },
    { timestamp: '2026-04-27T00:00:00Z', total_actual_savings: 86.1, total_potential_savings: 95.2 },
    { timestamp: '2026-04-28T00:00:00Z', total_actual_savings: 85.3, total_potential_savings: 94.0 },
    { timestamp: '2026-04-29T00:00:00Z', total_actual_savings: 87.4, total_potential_savings: 96.1 },
    { timestamp: '2026-04-30T00:00:00Z', total_actual_savings: 85.9, total_potential_savings: 93.8 },
  ],
  summary: { total_savings: 589.9, average_hourly_savings: 3.45, peak_savings: 4.21 },
};

// ─── /api/ebs/volumes/* ───────────────────────────────────────────────────────
export const demoEbsVolumes = [
  { volumeId: 'vol-0ghi7001', region: 'us-east-1', volumeType: 'gp2', sizeGb: 100, state: 'available', attached: false, encrypted: true,  monthlyCost: 10.0, recommendation: 'DELETE_UNATTACHED', recommendationReason: 'Unattached for over 30 days', estimatedSavings: 10.0 },
  { volumeId: 'vol-0ghi7002', region: 'us-east-1', volumeType: 'gp2', sizeGb: 500, state: 'in-use',    attached: true,  instanceId: 'i-0abc1001', device: '/dev/sda1', isRootVolume: true,  encrypted: true,  monthlyCost: 50.0, usedPercent: 42, usedGb: 210, freeGb: 290, recommendation: 'SHRINK',        recommendationReason: 'Usage below 50% consistently',         recommendedSizeGb: 300, estimatedSavings: 20.0 },
  { volumeId: 'vol-0ghi7003', region: 'eu-west-1', volumeType: 'gp2', sizeGb: 200, state: 'in-use',    attached: true,  instanceId: 'i-0abc1002', device: '/dev/sdb',  isRootVolume: false, encrypted: false, monthlyCost: 20.0, usedPercent: 78, usedGb: 156, freeGb: 44,  recommendation: 'UPGRADE_TYPE',  recommendationReason: 'Upgrade to gp3 for 20% cost savings',  recommendedType: 'gp3',            estimatedSavings: 4.0  },
  { volumeId: 'vol-0ghi7004', region: 'us-east-1', volumeType: 'gp3', sizeGb: 50,  state: 'in-use',    attached: true,  instanceId: 'i-0abc1003', device: '/dev/sda1', isRootVolume: true,  encrypted: true,  monthlyCost: 4.0,  recommendation: 'NONE',          recommendationReason: 'Optimally configured' },
];

// ─── /api/ebs/monitoring/{accountId}/{volumeId} ───────────────────────────────
const _ebsPts = (fn: (i: number) => number) =>
  Array.from({ length: 60 }, (_, i) => ({
    ts: 1748174400000 - (59 - i) * 60_000,
    v: fn(i),
  }));

export const demoEbsMonitoringData = {
  volumeId: 'vol-0ghi7001',
  region: 'us-east-1',
  period: 60,
  metrics: {
    writeBytes:     _ebsPts(i => 90_000 + 40_000 * Math.sin(i * 0.3)),
    readBytes:      _ebsPts(i =>  8_000 +  3_000 * Math.sin(i * 0.25)),
    writeOps:       _ebsPts(i => 22 + 10  * Math.sin(i * 0.3)),
    readOps:        _ebsPts(i =>  3 +  1.5 * Math.sin(i * 0.25)),
    totalWriteTime: _ebsPts(i => (22 + 10  * Math.sin(i * 0.3))  * 0.002),
    totalReadTime:  _ebsPts(i => ( 3 +  1.5 * Math.sin(i * 0.25)) * 0.003),
    queueLength:    _ebsPts(i => 0.3 + 0.15 * Math.sin(i * 0.5)),
    idleTime:       _ebsPts(i => 52  +  5   * Math.sin(i * 0.4)),
  },
};

// ─── /api/autospotting/analytics/launches/{accountId} ────────────────────────
export const demoAutoSpottingLaunchAnalytics = {
  total_attempts: 1847,
  success_rate: 94.2,
  total_successes: 1740,
  total_failures: 107,
  by_instance_type: {
    'm5.large':   { attempts: 620, successes: 598, failures: 22, success_rate: 96.5 },
    'm5.xlarge':  { attempts: 410, successes: 392, failures: 18, success_rate: 95.6 },
    'c5.2xlarge': { attempts: 380, successes: 358, failures: 22, success_rate: 94.2 },
    't3.large':   { attempts: 290, successes: 260, failures: 30, success_rate: 89.7 },
    'r5.large':   { attempts: 147, successes: 132, failures: 15, success_rate: 89.8 },
  },
  by_region: {
    'us-east-1':  { attempts: 980, successes: 930, failures: 50, success_rate: 94.9 },
    'eu-west-1':  { attempts: 520, successes: 490, failures: 30, success_rate: 94.2 },
    'ap-south-1': { attempts: 347, successes: 320, failures: 27, success_rate: 92.2 },
  },
  top_failure_reasons: [
    { reason: 'InsufficientInstanceCapacity', count: 52, percentage: 48.6 },
    { reason: 'SpotMaxPriceTooLow',           count: 31, percentage: 29.0 },
    { reason: 'InstanceLimitExceeded',        count: 14, percentage: 13.1 },
    { reason: 'RequestExpired',               count: 10, percentage:  9.3 },
  ],
  recommended_types: [
    { instance_type: 'm5.large',   region: 'us-east-1', success_rate: 96.5, avg_launch_time: '< 1 min', total_launches: 598 },
    { instance_type: 'm5.xlarge',  region: 'us-east-1', success_rate: 95.6, avg_launch_time: '< 2 min', total_launches: 392 },
    { instance_type: 'c5.2xlarge', region: 'eu-west-1', success_rate: 94.2, avg_launch_time: '< 2 min', total_launches: 358 },
    { instance_type: 't3.large',   region: 'ap-south-1',success_rate: 89.7, avg_launch_time: '< 3 min', total_launches: 260 },
  ],
};

// ─── /api/autospotting/events/{accountId} ─────────────────────────────────────
export const demoAutoSpottingActionsHistory = {
  events: [
    { timestamp: '2026-05-04T14:22:00Z', event_type: 'spot_launch',        result: 'success',      asg_name: 'prod-web-asg',     region: 'us-east-1',  old_instance_id: 'i-0old001', old_instance_type: 't3.large',   old_lifecycle: 'on-demand', old_hourly_price: 0.0832, new_instance_id: 'i-0new001', new_instance_type: 'm5.large',   new_lifecycle: 'spot', new_hourly_price: 0.0218, estimated_savings: 45.20  },
    { timestamp: '2026-05-04T13:45:00Z', event_type: 'spot_launch',        result: 'success',      asg_name: 'prod-api-asg',     region: 'us-east-1',  old_instance_id: 'i-0old002', old_instance_type: 'm5.xlarge',  old_lifecycle: 'on-demand', old_hourly_price: 0.192,  new_instance_id: 'i-0new002', new_instance_type: 'm5.xlarge',  new_lifecycle: 'spot', new_hourly_price: 0.0512, estimated_savings: 103.68 },
    { timestamp: '2026-05-04T11:30:00Z', event_type: 'spot_interruption',  result: 'interruption', asg_name: 'prod-worker-asg',  region: 'eu-west-1',  old_instance_id: 'i-0old003', old_instance_type: 'c5.2xlarge', old_lifecycle: 'spot',      old_hourly_price: 0.062,  new_instance_id: 'i-0new003', new_instance_type: 'c5.2xlarge', new_lifecycle: 'spot', new_hourly_price: 0.058,  estimated_savings: 0,     reason: 'AWS spot interruption' },
    { timestamp: '2026-05-04T10:15:00Z', event_type: 'spot_launch',        result: 'success',      asg_name: 'prod-web-asg',     region: 'us-east-1',  old_instance_id: 'i-0old004', old_instance_type: 't3.large',   old_lifecycle: 'on-demand', old_hourly_price: 0.0832, new_instance_id: 'i-0new004', new_instance_type: 'm5.large',   new_lifecycle: 'spot', new_hourly_price: 0.0218, estimated_savings: 45.20  },
    { timestamp: '2026-05-03T16:40:00Z', event_type: 'spot_launch',        result: 'success',      asg_name: 'staging-asg',      region: 'ap-south-1', old_instance_id: 'i-0old005', old_instance_type: 't3.medium',  old_lifecycle: 'on-demand', old_hourly_price: 0.0464, new_instance_id: 'i-0new005', new_instance_type: 't3.medium',  new_lifecycle: 'spot', new_hourly_price: 0.0124, estimated_savings: 24.48  },
    { timestamp: '2026-05-03T14:20:00Z', event_type: 'spot_launch',        result: 'success',      asg_name: 'prod-api-asg',     region: 'us-east-1',  old_instance_id: 'i-0old006', old_instance_type: 'm5.xlarge',  old_lifecycle: 'on-demand', old_hourly_price: 0.192,  new_instance_id: 'i-0new006', new_instance_type: 'm5.xlarge',  new_lifecycle: 'spot', new_hourly_price: 0.0512, estimated_savings: 103.68 },
    { timestamp: '2026-05-03T09:00:00Z', event_type: 'spot_launch',        result: 'success',      asg_name: 'prod-worker-asg',  region: 'eu-west-1',  old_instance_id: 'i-0old007', old_instance_type: 'c5.2xlarge', old_lifecycle: 'on-demand', old_hourly_price: 0.384,  new_instance_id: 'i-0new007', new_instance_type: 'c5.2xlarge', new_lifecycle: 'spot', new_hourly_price: 0.062,  estimated_savings: 235.44 },
    { timestamp: '2026-05-02T18:55:00Z', event_type: 'spot_interruption',  result: 'interruption', asg_name: 'prod-web-asg',     region: 'us-east-1',  old_instance_id: 'i-0old008', old_instance_type: 'm5.large',   old_lifecycle: 'spot',      old_hourly_price: 0.0218, new_instance_id: 'i-0new008', new_instance_type: 'm5.large',   new_lifecycle: 'spot', new_hourly_price: 0.0228, estimated_savings: 0,     reason: 'AWS spot interruption' },
    { timestamp: '2026-05-02T15:30:00Z', event_type: 'spot_launch',        result: 'success',      asg_name: 'prod-web-asg',     region: 'us-east-1',  old_instance_id: 'i-0old009', old_instance_type: 't3.large',   old_lifecycle: 'on-demand', old_hourly_price: 0.0832, new_instance_id: 'i-0new009', new_instance_type: 'm5.large',   new_lifecycle: 'spot', new_hourly_price: 0.0228, estimated_savings: 44.16  },
    { timestamp: '2026-05-02T11:10:00Z', event_type: 'spot_launch',        result: 'success',      asg_name: 'staging-asg',      region: 'ap-south-1', old_instance_id: 'i-0old010', old_instance_type: 't3.medium',  old_lifecycle: 'on-demand', old_hourly_price: 0.0464, new_instance_id: 'i-0new010', new_instance_type: 't3.medium',  new_lifecycle: 'spot', new_hourly_price: 0.0124, estimated_savings: 24.48  },
    { timestamp: '2026-05-01T20:00:00Z', event_type: 'spot_launch',        result: 'success',      asg_name: 'prod-api-asg',     region: 'us-east-1',  old_instance_id: 'i-0old011', old_instance_type: 'm5.xlarge',  old_lifecycle: 'on-demand', old_hourly_price: 0.192,  new_instance_id: 'i-0new011', new_instance_type: 'm5.xlarge',  new_lifecycle: 'spot', new_hourly_price: 0.0512, estimated_savings: 103.68 },
    { timestamp: '2026-05-01T08:30:00Z', event_type: 'spot_launch',        result: 'success',      asg_name: 'prod-worker-asg',  region: 'eu-west-1',  old_instance_id: 'i-0old012', old_instance_type: 'c5.2xlarge', old_lifecycle: 'on-demand', old_hourly_price: 0.384,  new_instance_id: 'i-0new012', new_instance_type: 'c5.2xlarge', new_lifecycle: 'spot', new_hourly_price: 0.062,  estimated_savings: 235.44 },
  ],
  summary: {
    total_replacements: 1740,
    total_interruptions: 18,
    total_estimated_savings: 2613.40,
  },
};

// ─── /api/autospotting/analytics/risp-coverage/{accountId} ───────────────────
export const demoAutoSpottingRispCoverage = {
  collected_at: '2026-05-04T10:00:00Z',
  total_ri_utilization_percent: 78.5,
  total_sp_utilization_percent: 85.2,
  ri_coverages: [
    { instance_type: 'm5.large',   region: 'us-east-1',  availability_zone: 'us-east-1a', coverage_percent: 92.0 },
    { instance_type: 'm5.xlarge',  region: 'us-east-1',  availability_zone: 'us-east-1b', coverage_percent: 88.5 },
    { instance_type: 'r5.2xlarge', region: 'us-east-1',  availability_zone: 'us-east-1a', coverage_percent: 100.0 },
    { instance_type: 'c5.2xlarge', region: 'eu-west-1',  availability_zone: 'eu-west-1a', coverage_percent: 75.0 },
    { instance_type: 't3.large',   region: 'ap-south-1', availability_zone: 'ap-south-1a',coverage_percent: 60.0 },
    { instance_type: 'm5.large',   region: 'eu-west-1',  availability_zone: 'eu-west-1b', coverage_percent: 55.0 },
  ],
  sp_coverages: [
    { plan_type: 'Compute Savings Plan',       coverage_percent: 87.5 },
    { plan_type: 'EC2 Instance Savings Plan',  coverage_percent: 82.8 },
  ],
};

// ─── /api/xamops/cloudlist/services-paginated ────────────────────────────────
export const demoCloudlistServices = {
  services: [
    {
      serviceType: 'EC2 Instance',
      resources: [
        { id: 'i-0abc1001', name: 'prod-web-01',       type: 'EC2 Instance', region: 'us-east-1',  state: 'running', launchTime: '2026-01-15T08:00:00Z', details: { Type: 't3.large',   instanceType: 't3.large',   privateIp: '10.0.1.10', publicIp: '54.80.1.10',  vpcId: 'vpc-001', subnetId: 'subnet-001', keyName: 'prod-key' } },
        { id: 'i-0abc1002', name: 'prod-api-01',       type: 'EC2 Instance', region: 'us-east-1',  state: 'running', launchTime: '2026-01-15T08:05:00Z', details: { Type: 'm5.xlarge',  instanceType: 'm5.xlarge',  privateIp: '10.0.1.11', publicIp: '54.80.1.11',  vpcId: 'vpc-001', subnetId: 'subnet-001', keyName: 'prod-key' } },
        { id: 'i-0abc1003', name: 'prod-idle-01',      type: 'EC2 Instance', region: 'us-east-1',  state: 'running', launchTime: '2026-02-01T09:00:00Z', details: { Type: 't3.medium',  instanceType: 't3.medium',  privateIp: '10.0.1.12', publicIp: null,          vpcId: 'vpc-001', subnetId: 'subnet-002', keyName: 'prod-key' } },
        { id: 'i-0abc1004', name: 'staging-web-02',    type: 'EC2 Instance', region: 'ap-south-1', state: 'running', launchTime: '2026-02-10T10:00:00Z', details: { Type: 't3.small',   instanceType: 't3.small',   privateIp: '10.1.1.10', publicIp: '13.233.1.10', vpcId: 'vpc-002', subnetId: 'subnet-003', keyName: 'staging-key' } },
        { id: 'i-0abc1005', name: 'prod-worker-01',    type: 'EC2 Instance', region: 'us-east-1',  state: 'running', launchTime: '2026-01-20T07:30:00Z', details: { Type: 'c5.2xlarge', instanceType: 'c5.2xlarge', privateIp: '10.0.2.10', publicIp: null,          vpcId: 'vpc-001', subnetId: 'subnet-002', keyName: 'prod-key' } },
        { id: 'i-0abc1006', name: 'prod-worker-02',    type: 'EC2 Instance', region: 'us-east-1',  state: 'running', launchTime: '2026-01-20T07:35:00Z', details: { Type: 'c5.2xlarge', instanceType: 'c5.2xlarge', privateIp: '10.0.2.11', publicIp: null,          vpcId: 'vpc-001', subnetId: 'subnet-002', keyName: 'prod-key' } },
        { id: 'i-0abc1007', name: 'prod-db-proxy',     type: 'EC2 Instance', region: 'us-east-1',  state: 'running', launchTime: '2026-01-18T06:00:00Z', details: { Type: 'r5.2xlarge', instanceType: 'r5.2xlarge', privateIp: '10.0.3.10', publicIp: null,          vpcId: 'vpc-001', subnetId: 'subnet-004', keyName: 'prod-key' } },
        { id: 'i-0abc1008', name: 'eu-web-01',         type: 'EC2 Instance', region: 'eu-west-1',  state: 'running', launchTime: '2026-03-01T08:00:00Z', details: { Type: 't3.large',   instanceType: 't3.large',   privateIp: '10.2.1.10', publicIp: '52.18.1.10',  vpcId: 'vpc-003', subnetId: 'subnet-005', keyName: 'eu-key' } },
        { id: 'i-0abc1009', name: 'dev-bastion',       type: 'EC2 Instance', region: 'us-east-1',  state: 'stopped', launchTime: '2026-03-15T12:00:00Z', details: { Type: 't3.micro',   instanceType: 't3.micro',   privateIp: '10.0.1.20', publicIp: null,          vpcId: 'vpc-001', subnetId: 'subnet-001', keyName: 'dev-key' } },
        { id: 'i-0abc1010', name: 'prod-monitoring',   type: 'EC2 Instance', region: 'us-east-1',  state: 'running', launchTime: '2026-01-10T05:00:00Z', details: { Type: 'm5.large',   instanceType: 'm5.large',   privateIp: '10.0.4.10', publicIp: '54.80.2.10',  vpcId: 'vpc-001', subnetId: 'subnet-006', keyName: 'prod-key' } },
      ],
    },
    {
      serviceType: 'S3 Bucket',
      resources: [
        { id: 'xammer-prod-assets',    name: 'xammer-prod-assets',    type: 'S3 Bucket', region: 'us-east-1',  state: 'active', launchTime: '2025-06-01T00:00:00Z', details: { versioning: 'Enabled',  encryption: 'AES-256', accessControl: 'Private',      sizeGb: '142', objectCount: '48302' } },
        { id: 'xammer-prod-backups',   name: 'xammer-prod-backups',   type: 'S3 Bucket', region: 'us-east-1',  state: 'active', launchTime: '2025-06-01T00:00:00Z', details: { versioning: 'Enabled',  encryption: 'AES-256', accessControl: 'Private',      sizeGb: '890', objectCount: '12400' } },
        { id: 'xammer-logs-archive',   name: 'xammer-logs-archive',   type: 'S3 Bucket', region: 'eu-west-1',  state: 'active', launchTime: '2025-07-15T00:00:00Z', details: { versioning: 'Suspended',encryption: 'AES-256', accessControl: 'Private',      sizeGb: '2310',objectCount: '982100' } },
        { id: 'xammer-staging-data',   name: 'xammer-staging-data',   type: 'S3 Bucket', region: 'ap-south-1', state: 'active', launchTime: '2025-09-01T00:00:00Z', details: { versioning: 'Enabled',  encryption: 'AES-256', accessControl: 'Private',      sizeGb: '34',  objectCount: '5100' } },
        { id: 'xammer-static-web',     name: 'xammer-static-web',     type: 'S3 Bucket', region: 'us-east-1',  state: 'active', launchTime: '2025-06-10T00:00:00Z', details: { versioning: 'Enabled',  encryption: 'None',    accessControl: 'Public-read',  sizeGb: '4',   objectCount: '1230' } },
        { id: 'xammer-ml-datasets',    name: 'xammer-ml-datasets',    type: 'S3 Bucket', region: 'us-east-1',  state: 'active', launchTime: '2025-10-01T00:00:00Z', details: { versioning: 'Enabled',  encryption: 'AES-256', accessControl: 'Private',      sizeGb: '5600',objectCount: '320' } },
        { id: 'xammer-tf-state',       name: 'xammer-tf-state',       type: 'S3 Bucket', region: 'us-east-1',  state: 'active', launchTime: '2025-05-01T00:00:00Z', details: { versioning: 'Enabled',  encryption: 'aws:kms', accessControl: 'Private',      sizeGb: '1',   objectCount: '88' } },
        { id: 'xammer-codepipeline',   name: 'xammer-codepipeline',   type: 'S3 Bucket', region: 'us-east-1',  state: 'active', launchTime: '2025-05-15T00:00:00Z', details: { versioning: 'Suspended',encryption: 'AES-256', accessControl: 'Private',      sizeGb: '8',   objectCount: '4200' } },
      ],
    },
    {
      serviceType: 'RDS Instance',
      resources: [
        { id: 'rds-xammer-prod-001',  name: 'xammer-prod-mysql',   type: 'RDS Instance', region: 'us-east-1',  state: 'available', launchTime: '2026-01-10T06:00:00Z', details: { engine: 'mysql 8.0',      instanceClass: 'db.r5.2xlarge', multiAZ: 'true',  storage: '500 GB', backupRetention: '7 days', endpoint: 'xammer-prod-mysql.cluster-xyz.us-east-1.rds.amazonaws.com' } },
        { id: 'rds-xammer-prod-002',  name: 'xammer-prod-pg',      type: 'RDS Instance', region: 'us-east-1',  state: 'available', launchTime: '2026-01-12T06:00:00Z', details: { engine: 'postgres 15.3',  instanceClass: 'db.m5.xlarge',  multiAZ: 'true',  storage: '200 GB', backupRetention: '7 days', endpoint: 'xammer-prod-pg.xyz.us-east-1.rds.amazonaws.com' } },
        { id: 'rds-xammer-stage-001', name: 'xammer-stage-mysql',  type: 'RDS Instance', region: 'ap-south-1', state: 'available', launchTime: '2026-02-01T07:00:00Z', details: { engine: 'mysql 8.0',      instanceClass: 'db.t3.large',   multiAZ: 'false', storage: '100 GB', backupRetention: '3 days', endpoint: 'xammer-stage-mysql.xyz.ap-south-1.rds.amazonaws.com' } },
        { id: 'rds-xammer-eu-001',    name: 'xammer-eu-pg',        type: 'RDS Instance', region: 'eu-west-1',  state: 'available', launchTime: '2026-03-01T08:00:00Z', details: { engine: 'postgres 15.3',  instanceClass: 'db.m5.large',   multiAZ: 'true',  storage: '150 GB', backupRetention: '7 days', endpoint: 'xammer-eu-pg.xyz.eu-west-1.rds.amazonaws.com' } },
        { id: 'rds-xammer-dev-001',   name: 'xammer-dev-mysql',    type: 'RDS Instance', region: 'us-east-1',  state: 'stopped',   launchTime: '2026-03-10T09:00:00Z', details: { engine: 'mysql 8.0',      instanceClass: 'db.t3.medium',  multiAZ: 'false', storage: '50 GB',  backupRetention: '1 day',  endpoint: 'xammer-dev-mysql.xyz.us-east-1.rds.amazonaws.com' } },
      ],
    },
    {
      serviceType: 'VPC',
      resources: [
        { id: 'vpc-001', name: 'prod-vpc',    type: 'VPC', region: 'us-east-1',  state: 'available', launchTime: null, details: { cidr: '10.0.0.0/16',  isDefault: 'false', subnets: '6', routeTables: '4', internetGateway: 'igw-001', dhcpOptions: 'dopt-001' } },
        { id: 'vpc-002', name: 'staging-vpc', type: 'VPC', region: 'ap-south-1', state: 'available', launchTime: null, details: { cidr: '10.1.0.0/16',  isDefault: 'false', subnets: '3', routeTables: '2', internetGateway: 'igw-002', dhcpOptions: 'dopt-002' } },
        { id: 'vpc-003', name: 'eu-vpc',      type: 'VPC', region: 'eu-west-1',  state: 'available', launchTime: null, details: { cidr: '10.2.0.0/16',  isDefault: 'false', subnets: '4', routeTables: '3', internetGateway: 'igw-003', dhcpOptions: 'dopt-003' } },
        { id: 'vpc-004', name: 'default-vpc', type: 'VPC', region: 'us-east-1',  state: 'available', launchTime: null, details: { cidr: '172.31.0.0/16', isDefault: 'true',  subnets: '6', routeTables: '1', internetGateway: 'igw-004', dhcpOptions: 'dopt-004' } },
      ],
    },
    {
      serviceType: 'Load Balancer',
      resources: [
        { id: 'alb-prod-web-001',  name: 'prod-web-alb',  type: 'Load Balancer', region: 'us-east-1',  state: 'active', launchTime: '2026-01-15T08:00:00Z', details: { type: 'application', scheme: 'internet-facing', dns: 'prod-web-alb-123.us-east-1.elb.amazonaws.com', vpcId: 'vpc-001', zones: 'us-east-1a, us-east-1b' } },
        { id: 'alb-prod-api-001',  name: 'prod-api-alb',  type: 'Load Balancer', region: 'us-east-1',  state: 'active', launchTime: '2026-01-15T08:10:00Z', details: { type: 'application', scheme: 'internal',         dns: 'prod-api-alb-456.us-east-1.elb.amazonaws.com', vpcId: 'vpc-001', zones: 'us-east-1a, us-east-1b' } },
        { id: 'nlb-prod-data-001', name: 'prod-data-nlb', type: 'Load Balancer', region: 'eu-west-1',  state: 'active', launchTime: '2026-03-01T08:00:00Z', details: { type: 'network',     scheme: 'internal',         dns: 'prod-data-nlb-789.eu-west-1.elb.amazonaws.com', vpcId: 'vpc-003', zones: 'eu-west-1a, eu-west-1b' } },
      ],
    },
    {
      serviceType: 'Lambda Function',
      resources: [
        { id: 'lambda-cost-sync',       name: 'xammer-cost-sync',       type: 'Lambda Function', region: 'us-east-1',  state: 'active', launchTime: null, details: { runtime: 'python3.12', memory: '512 MB', timeout: '300s', lastModified: '2026-04-10' } },
        { id: 'lambda-alert-notifier',  name: 'xammer-alert-notifier',  type: 'Lambda Function', region: 'us-east-1',  state: 'active', launchTime: null, details: { runtime: 'nodejs20.x',memory: '256 MB', timeout: '30s',  lastModified: '2026-03-28' } },
        { id: 'lambda-report-gen',      name: 'xammer-report-gen',      type: 'Lambda Function', region: 'eu-west-1',  state: 'active', launchTime: null, details: { runtime: 'python3.12', memory: '1024 MB',timeout: '600s', lastModified: '2026-04-15' } },
        { id: 'lambda-tag-enforcer',    name: 'xammer-tag-enforcer',    type: 'Lambda Function', region: 'us-east-1',  state: 'active', launchTime: null, details: { runtime: 'python3.12', memory: '256 MB', timeout: '60s',  lastModified: '2026-04-01' } },
      ],
    },
  ],
  currentPage: 0,
  totalPages: 1,
  totalServices: 6,
  hasNext: false,
  hasPrevious: false,
};

// ─── /api/xamops/cloudlist/all — flat list used by CloudSitter ───────────────
export const demoCloudlistAll = (demoCloudlistServices.services as any[]).flatMap((g: any) => g.resources);

// ─── /api/xamops/gcp/cloudlist/services-paginated ────────────────────────────
export const demoGcpCloudListPage = {
  totalServices: 6,
  totalResources: 148,
  hasNext: false,
  services: [
    {
      serviceType: 'Compute Engine',
      resourceCount: 24,
      resources: [
        { id: 'gce-prod-001', name: 'gce-prod-analytics-01', type: 'Compute Engine', location: 'us-central1-a', status: 'RUNNING' },
        { id: 'gce-prod-002', name: 'gce-prod-batch-01',     type: 'Compute Engine', location: 'us-central1-b', status: 'RUNNING' },
        { id: 'gce-prod-003', name: 'gce-analytics-02',      type: 'Compute Engine', location: 'us-central1-a', status: 'RUNNING' },
        { id: 'gce-prod-004', name: 'gce-prod-worker-01',    type: 'Compute Engine', location: 'us-central1-c', status: 'RUNNING' },
        { id: 'gce-prod-005', name: 'gce-prod-worker-02',    type: 'Compute Engine', location: 'us-central1-c', status: 'RUNNING' },
        { id: 'gce-eu-001',   name: 'gce-eu-web-01',         type: 'Compute Engine', location: 'europe-west1-b', status: 'RUNNING' },
        { id: 'gce-eu-002',   name: 'gce-eu-api-01',         type: 'Compute Engine', location: 'europe-west1-b', status: 'RUNNING' },
        { id: 'gce-asia-001', name: 'gce-asia-web-01',       type: 'Compute Engine', location: 'asia-south1-a',  status: 'RUNNING' },
        { id: 'gce-dev-001',  name: 'gce-dev-bastion',       type: 'Compute Engine', location: 'us-central1-a', status: 'TERMINATED' },
      ],
    },
    {
      serviceType: 'Cloud Storage',
      resourceCount: 18,
      resources: [
        { id: 'gcs-001', name: 'xammer-gcs-data-lake',      type: 'Cloud Storage', location: 'US',          status: 'ACTIVE' },
        { id: 'gcs-002', name: 'xammer-gcs-backups',        type: 'Cloud Storage', location: 'US',          status: 'ACTIVE' },
        { id: 'gcs-003', name: 'xammer-gcs-ml-datasets',    type: 'Cloud Storage', location: 'EU',          status: 'ACTIVE' },
        { id: 'gcs-004', name: 'xammer-gcs-logs',           type: 'Cloud Storage', location: 'ASIA',        status: 'ACTIVE' },
        { id: 'gcs-005', name: 'xammer-gcs-artifacts',      type: 'Cloud Storage', location: 'US',          status: 'ACTIVE' },
        { id: 'gcs-006', name: 'xammer-gcs-terraform-state',type: 'Cloud Storage', location: 'US',          status: 'ACTIVE' },
      ],
    },
    {
      serviceType: 'Cloud SQL',
      resourceCount: 6,
      resources: [
        { id: 'sql-001', name: 'cloudsql-prod-postgres',  type: 'Cloud SQL', location: 'us-central1', status: 'RUNNABLE' },
        { id: 'sql-002', name: 'cloudsql-analytics-pg',   type: 'Cloud SQL', location: 'us-central1', status: 'RUNNABLE' },
        { id: 'sql-003', name: 'cloudsql-eu-mysql',        type: 'Cloud SQL', location: 'europe-west1', status: 'RUNNABLE' },
        { id: 'sql-004', name: 'cloudsql-staging-pg',      type: 'Cloud SQL', location: 'us-central1', status: 'RUNNABLE' },
        { id: 'sql-005', name: 'cloudsql-dev-pg',          type: 'Cloud SQL', location: 'us-central1', status: 'SUSPENDED' },
      ],
    },
    {
      serviceType: 'GKE Clusters',
      resourceCount: 2,
      resources: [
        { id: 'gke-001', name: 'prod-gke-cluster',    type: 'GKE Cluster', location: 'us-central1', status: 'RUNNING' },
        { id: 'gke-002', name: 'analytics-gke-cluster', type: 'GKE Cluster', location: 'europe-west1', status: 'RUNNING' },
      ],
    },
    {
      serviceType: 'Cloud Functions',
      resourceCount: 22,
      resources: [
        { id: 'fn-001', name: 'data-ingest-trigger',   type: 'Cloud Function', location: 'us-central1', status: 'ACTIVE' },
        { id: 'fn-002', name: 'alert-notifier',        type: 'Cloud Function', location: 'us-central1', status: 'ACTIVE' },
        { id: 'fn-003', name: 'report-generator',      type: 'Cloud Function', location: 'us-central1', status: 'ACTIVE' },
        { id: 'fn-004', name: 'cost-anomaly-detector', type: 'Cloud Function', location: 'europe-west1', status: 'ACTIVE' },
        { id: 'fn-005', name: 'resource-cleaner',      type: 'Cloud Function', location: 'us-central1', status: 'ACTIVE' },
      ],
    },
    {
      serviceType: 'BigQuery',
      resourceCount: 8,
      resources: [
        { id: 'bq-001', name: 'analytics_prod',      type: 'BigQuery Dataset', location: 'US', status: 'ACTIVE' },
        { id: 'bq-002', name: 'billing_export',      type: 'BigQuery Dataset', location: 'US', status: 'ACTIVE' },
        { id: 'bq-003', name: 'ml_training_data',    type: 'BigQuery Dataset', location: 'US', status: 'ACTIVE' },
        { id: 'bq-004', name: 'logs_archive',        type: 'BigQuery Dataset', location: 'EU', status: 'ACTIVE' },
      ],
    },
  ],
};

// ─── /api/azure/cloudlist/resources ──────────────────────────────────────────
export const demoAzureCloudListResources = [
  {
    serviceType: 'Virtual Machines',
    resources: [
      { id: '/subscriptions/demo/vm-prod-api-01',    name: 'vm-prod-api-01',    region: 'East US',       state: 'running',     type: 'Standard_D4s_v3' },
      { id: '/subscriptions/demo/vm-prod-web-01',    name: 'vm-prod-web-01',    region: 'East US',       state: 'running',     type: 'Standard_D2s_v3' },
      { id: '/subscriptions/demo/vm-prod-worker-01', name: 'vm-prod-worker-01', region: 'East US',       state: 'running',     type: 'Standard_D4s_v3' },
      { id: '/subscriptions/demo/vm-eu-web-01',      name: 'vm-eu-web-01',      region: 'West Europe',   state: 'running',     type: 'Standard_D2s_v3' },
      { id: '/subscriptions/demo/vm-eu-api-01',      name: 'vm-eu-api-01',      region: 'West Europe',   state: 'running',     type: 'Standard_D2s_v3' },
      { id: '/subscriptions/demo/vm-staging-web-01', name: 'vm-staging-web-01', region: 'East US',       state: 'running',     type: 'Standard_B2s' },
      { id: '/subscriptions/demo/vm-dev-bastion',    name: 'vm-dev-bastion',    region: 'Southeast Asia',state: 'deallocated', type: 'Standard_B1s' },
    ],
  },
  {
    serviceType: 'Storage Accounts',
    resources: [
      { id: '/subscriptions/demo/st-prod-data',    name: 'stproddata',    region: 'East US',     state: 'available', type: 'StorageV2 (LRS)' },
      { id: '/subscriptions/demo/st-prod-backups', name: 'stprodbackups', region: 'East US',     state: 'available', type: 'StorageV2 (GRS)' },
      { id: '/subscriptions/demo/st-eu-data',      name: 'steudata',      region: 'West Europe', state: 'available', type: 'StorageV2 (LRS)' },
      { id: '/subscriptions/demo/st-logs',         name: 'stlogs',        region: 'East US',     state: 'available', type: 'BlobStorage (LRS)' },
    ],
  },
  {
    serviceType: 'SQL Databases',
    resources: [
      { id: '/subscriptions/demo/sql-prod-001', name: 'sql-prod-analytics', region: 'East US',     state: 'online', type: 'Standard S3' },
      { id: '/subscriptions/demo/sql-prod-002', name: 'sql-prod-billing',   region: 'East US',     state: 'online', type: 'Standard S2' },
      { id: '/subscriptions/demo/sql-eu-001',   name: 'sql-eu-reporting',   region: 'West Europe', state: 'online', type: 'Standard S1' },
    ],
  },
  {
    serviceType: 'App Services',
    resources: [
      { id: '/subscriptions/demo/app-prod-api',     name: 'app-prod-api',     region: 'East US',     state: 'running', type: 'P2v3' },
      { id: '/subscriptions/demo/app-prod-web',     name: 'app-prod-web',     region: 'East US',     state: 'running', type: 'P1v3' },
      { id: '/subscriptions/demo/app-eu-web',       name: 'app-eu-web',       region: 'West Europe', state: 'running', type: 'P1v3' },
      { id: '/subscriptions/demo/app-staging-api',  name: 'app-staging-api',  region: 'East US',     state: 'running', type: 'S1' },
    ],
  },
  {
    serviceType: 'Virtual Networks',
    resources: [
      { id: '/subscriptions/demo/vnet-prod',    name: 'vnet-prod',    region: 'East US',       state: 'available', type: '10.0.0.0/16' },
      { id: '/subscriptions/demo/vnet-eu',      name: 'vnet-eu',      region: 'West Europe',   state: 'available', type: '10.1.0.0/16' },
      { id: '/subscriptions/demo/vnet-asia',    name: 'vnet-asia',    region: 'Southeast Asia',state: 'available', type: '10.2.0.0/16' },
      { id: '/subscriptions/demo/vnet-staging', name: 'vnet-staging', region: 'East US',       state: 'available', type: '10.3.0.0/16' },
    ],
  },
  {
    serviceType: 'Kubernetes Services',
    resources: [
      { id: '/subscriptions/demo/aks-prod-001',  name: 'aks-prod-cluster',  region: 'East US',     state: 'running', type: '3 nodes / Standard_D4s_v3' },
      { id: '/subscriptions/demo/aks-eu-001',    name: 'aks-eu-cluster',    region: 'West Europe', state: 'running', type: '2 nodes / Standard_D2s_v3' },
    ],
  },
  {
    serviceType: 'Function Apps',
    resources: [
      { id: '/subscriptions/demo/func-data-processor', name: 'func-data-processor', region: 'East US',     state: 'running', type: 'Premium P1v3' },
      { id: '/subscriptions/demo/func-alert-handler',  name: 'func-alert-handler',  region: 'East US',     state: 'running', type: 'Consumption' },
      { id: '/subscriptions/demo/func-report-gen',     name: 'func-report-gen',     region: 'West Europe', state: 'running', type: 'Consumption' },
    ],
  },
];

// ─── /api/xamops/gcp/cloudmap/vpcs ──────────────────────────────────────────
export const demoGcpCloudMapVpcs = [
  { id: 'vpc-gcp-001', name: 'prod-vpc',      region: 'us-central1'  },
  { id: 'vpc-gcp-002', name: 'analytics-vpc', region: 'europe-west1' },
  { id: 'vpc-gcp-003', name: 'asia-vpc',      region: 'asia-south1'  },
];

// ─── /api/xamops/gcp/cloudmap/graph ─────────────────────────────────────────
export const demoGcpCloudMapGraph: object[] = [
  { group: 'nodes', data: { id: 'vpc-gcp-001', label: 'prod-vpc\n10.10.0.0/16', type: 'VPC' } },
  { group: 'nodes', data: { id: 'subnet-gcp-001', label: 'subnet-us-central1\n10.10.1.0/24', type: 'Subnet', parent: 'vpc-gcp-001' } },
  { group: 'nodes', data: { id: 'subnet-gcp-002', label: 'subnet-us-central1b\n10.10.2.0/24', type: 'Subnet', parent: 'vpc-gcp-001' } },
  { group: 'nodes', data: { id: 'gce-prod-001', label: 'gce-prod-analytics-01\nn2-standard-8', type: 'GCE Instance', parent: 'subnet-gcp-001' } },
  { group: 'nodes', data: { id: 'gce-prod-002', label: 'gce-prod-batch-01\nn2-standard-4',     type: 'GCE Instance', parent: 'subnet-gcp-001' } },
  { group: 'nodes', data: { id: 'sql-prod-001', label: 'cloudsql-prod-postgres\ndb-n1-std-4',   type: 'Cloud SQL',    parent: 'subnet-gcp-002' } },
  { group: 'nodes', data: { id: 'gke-001',      label: 'prod-gke-cluster\n3 nodes',             type: 'GKE Cluster',  parent: 'subnet-gcp-002' } },
  { group: 'nodes', data: { id: 'gcs-001',      label: 'xammer-gcs-data\nCloud Storage'        , type: 'Cloud Storage' } },
  { group: 'nodes', data: { id: 'lb-gcp-001',   label: 'prod-http-lb\nHTTP(S) LB',             type: 'Load Balancer' } },
  { group: 'edges', data: { id: 'eg-lb-gce1',  source: 'lb-gcp-001',   target: 'gce-prod-001', label: ':80'   } },
  { group: 'edges', data: { id: 'eg-lb-gce2',  source: 'lb-gcp-001',   target: 'gce-prod-002', label: ':80'   } },
  { group: 'edges', data: { id: 'eg-gce-sql',  source: 'gce-prod-001', target: 'sql-prod-001', label: ':5432' } },
  { group: 'edges', data: { id: 'eg-gke-sql',  source: 'gke-001',      target: 'sql-prod-001', label: ':5432' } },
  { group: 'edges', data: { id: 'eg-gce-gcs',  source: 'gce-prod-002', target: 'gcs-001',      label: 'gs://' } },
];

// ─── /api/xamops/cloudmap/vpcs ───────────────────────────────────────────────
export const demoCloudMapVpcs = [
  { id: 'vpc-001', name: 'prod-vpc',    region: 'us-east-1'  },
  { id: 'vpc-002', name: 'staging-vpc', region: 'ap-south-1' },
  { id: 'vpc-003', name: 'eu-vpc',      region: 'eu-west-1'  },
  { id: 'vpc-004', name: 'default-vpc', region: 'us-east-1'  },
];

// ─── /api/xamops/cloudmap/graph ──────────────────────────────────────────────
export const demoCloudMapGraph: object[] = [
  // VPC node (parent)
  { group: 'nodes', data: { id: 'vpc-001', label: 'prod-vpc\n10.0.0.0/16', type: 'VPC' } },
  // Subnets
  { group: 'nodes', data: { id: 'subnet-001', label: 'subnet-001\n10.0.1.0/24\nus-east-1a', type: 'Subnet', parent: 'vpc-001' } },
  { group: 'nodes', data: { id: 'subnet-002', label: 'subnet-002\n10.0.2.0/24\nus-east-1b', type: 'Subnet', parent: 'vpc-001' } },
  { group: 'nodes', data: { id: 'subnet-003', label: 'subnet-003\n10.0.3.0/24\nus-east-1a', type: 'Subnet', parent: 'vpc-001' } },
  // Internet Gateway
  { group: 'nodes', data: { id: 'igw-001', label: 'igw-001\nInternet Gateway', type: 'Internet Gateway', parent: 'vpc-001' } },
  // Load Balancers
  { group: 'nodes', data: { id: 'alb-prod-web-001', label: 'prod-web-alb\nApplication LB', type: 'Load Balancer', parent: 'subnet-001' } },
  { group: 'nodes', data: { id: 'alb-prod-api-001', label: 'prod-api-alb\nInternal LB',    type: 'Load Balancer', parent: 'subnet-002' } },
  // EC2 Instances
  { group: 'nodes', data: { id: 'i-0abc1001', label: 'prod-web-01\nt3.large',   type: 'EC2 Instance', parent: 'subnet-001' } },
  { group: 'nodes', data: { id: 'i-0abc1002', label: 'prod-api-01\nm5.xlarge',  type: 'EC2 Instance', parent: 'subnet-001' } },
  { group: 'nodes', data: { id: 'i-0abc1005', label: 'prod-worker-01\nc5.2xl',  type: 'EC2 Instance', parent: 'subnet-002' } },
  { group: 'nodes', data: { id: 'i-0abc1006', label: 'prod-worker-02\nc5.2xl',  type: 'EC2 Instance', parent: 'subnet-002' } },
  { group: 'nodes', data: { id: 'i-0abc1007', label: 'prod-db-proxy\nr5.2xl',   type: 'EC2 Instance', parent: 'subnet-003' } },
  // RDS
  { group: 'nodes', data: { id: 'rds-prod-001', label: 'rds-prod-postgres\ndb.r5.large', type: 'RDS Instance', parent: 'subnet-003' } },
  // S3
  { group: 'nodes', data: { id: 's3-001', label: 'xammer-data-lake\nS3 Bucket', type: 'S3 Bucket' } },
  // Edges
  { group: 'edges', data: { id: 'e-igw-alb1', source: 'igw-001',         target: 'alb-prod-web-001', label: 'inbound' } },
  { group: 'edges', data: { id: 'e-alb1-ec2-1', source: 'alb-prod-web-001', target: 'i-0abc1001',      label: ':80'    } },
  { group: 'edges', data: { id: 'e-alb1-ec2-2', source: 'alb-prod-web-001', target: 'i-0abc1002',      label: ':80'    } },
  { group: 'edges', data: { id: 'e-alb2-w1',  source: 'alb-prod-api-001', target: 'i-0abc1005',      label: ':8080'  } },
  { group: 'edges', data: { id: 'e-alb2-w2',  source: 'alb-prod-api-001', target: 'i-0abc1006',      label: ':8080'  } },
  { group: 'edges', data: { id: 'e-ec2-rds',  source: 'i-0abc1007',       target: 'rds-prod-001',    label: ':5432'  } },
  { group: 'edges', data: { id: 'e-w1-rds',   source: 'i-0abc1005',       target: 'i-0abc1007',      label: 'proxy'  } },
  { group: 'edges', data: { id: 'e-w2-rds',   source: 'i-0abc1006',       target: 'i-0abc1007',      label: 'proxy'  } },
  { group: 'edges', data: { id: 'e-ec2-s3',   source: 'i-0abc1005',       target: 's3-001',          label: 's3'     } },
];

// ─── Generic empty fallback ───────────────────────────────────────────────────
export const demoGenericEmpty = {};
export const demoEmptyList: never[] = [];

// ─── /api/xamops/k8s/clusters ─────────────────────────────────────────────────
export const demoK8sClusters = [
  { name: 'prod-eks-cluster',    region: 'us-east-1', version: '1.29', connected: true  },
  { name: 'staging-eks-cluster', region: 'us-east-1', version: '1.28', connected: false },
  { name: 'eu-eks-cluster',      region: 'eu-west-1', version: '1.29', connected: true  },
];

// ─── /api/cicd/github/runs ────────────────────────────────────────────────────
export const demoGithubRuns = [
  { id: 11001, name: 'Deploy to Production',  status: 'completed', conclusion: 'success', displayStatus: 'Success', html_url: '', updated_at: '2026-04-30T10:15:00Z', run_number: 142, workflow_id: 1001, repository: { full_name: 'xammer/cloud-app' } },
  { id: 11002, name: 'Build & Test',           status: 'completed', conclusion: 'success', displayStatus: 'Success', html_url: '', updated_at: '2026-04-30T09:45:00Z', run_number: 141, workflow_id: 1002, repository: { full_name: 'xammer/cloud-app' } },
  { id: 11003, name: 'Security Scan',          status: 'completed', conclusion: 'failure', displayStatus: 'Failed',  html_url: '', updated_at: '2026-04-29T16:00:00Z', run_number: 140, workflow_id: 1003, repository: { full_name: 'xammer/cloud-app' } },
  { id: 11004, name: 'Infrastructure Preview', status: 'completed', conclusion: 'success', displayStatus: 'Success', html_url: '', updated_at: '2026-04-29T14:30:00Z', run_number: 139, workflow_id: 1004, repository: { full_name: 'xammer/infra'      } },
];

// ─── /api/cicd/*/metrics ─────────────────────────────────────────────────────
export const demoCicdGithubMetrics = {
  successRate: 87.5,
  totalRuns: 48,
  failureCount: 6,
  avgDurationSeconds: 342,
  prevSuccessRate: 82.0,
  prevTotalRuns: 44,
  prevFailureCount: 8,
  prevAvgDurationSeconds: 378,
  dora: { deploymentFrequency: 1.4, mttr: 5400, changeFailureRate: 12.5 },
  dailyStats: [
    { date: '2026-05-22', successCount: 5, failureCount: 1, avgDuration: 320 },
    { date: '2026-05-23', successCount: 7, failureCount: 0, avgDuration: 298 },
    { date: '2026-05-24', successCount: 6, failureCount: 2, avgDuration: 361 },
    { date: '2026-05-25', successCount: 8, failureCount: 1, avgDuration: 344 },
    { date: '2026-05-26', successCount: 4, failureCount: 1, avgDuration: 390 },
    { date: '2026-05-27', successCount: 9, failureCount: 1, avgDuration: 312 },
    { date: '2026-05-28', successCount: 3, failureCount: 0, avgDuration: 330 },
  ],
  topFailingStages: [
    { name: 'Build', failureCount: 3 },
    { name: 'Security Scan', failureCount: 2 },
    { name: 'Deploy', failureCount: 1 },
  ],
  topFailingWorkflows: [
    { name: 'Security Scan', failureCount: 2, successRate: 50.0 },
    { name: 'Deploy to Production', failureCount: 2, successRate: 60.0 },
    { name: 'Build & Test', failureCount: 2, successRate: 80.0 },
  ],
};

export const demoCicdJenkinsMetrics = {
  successRate: 75.0,
  totalRuns: 20,
  failureCount: 5,
  avgDurationSeconds: 480,
  prevSuccessRate: 70.0,
  prevTotalRuns: 18,
  prevFailureCount: 6,
  prevAvgDurationSeconds: 520,
  dora: null,
  dailyStats: [
    { date: '2026-05-22', successCount: 2, failureCount: 1, avgDuration: 460 },
    { date: '2026-05-23', successCount: 3, failureCount: 1, avgDuration: 500 },
    { date: '2026-05-24', successCount: 2, failureCount: 0, avgDuration: 440 },
    { date: '2026-05-25', successCount: 4, failureCount: 2, avgDuration: 520 },
    { date: '2026-05-26', successCount: 2, failureCount: 1, avgDuration: 480 },
    { date: '2026-05-27', successCount: 2, failureCount: 0, avgDuration: 490 },
    { date: '2026-05-28', successCount: 0, failureCount: 0, avgDuration: 0 },
  ],
  topFailingStages: [
    { name: 'Test', failureCount: 3 },
    { name: 'Package', failureCount: 2 },
  ],
  topFailingWorkflows: [
    { name: 'nightly-build', failureCount: 3, successRate: 40.0 },
    { name: 'integration-tests', failureCount: 2, successRate: 60.0 },
  ],
};

// ─── /api/dev_ops_script ──────────────────────────────────────────────────────
export const demoDevOpsScripts = [
  { id: 1, name: 'EC2 Auto Shutdown',       description: 'Automatically stop idle EC2 instances outside business hours to save cost',    category: 'bash',      code: '#!/bin/bash\nregion=${1:-us-east-1}\necho "Stopping idle EC2 in $region..."\naws ec2 stop-instances --region $region ...',              downloads: 142, lastUpdated: '2026-04-01', version: '1.2.0', usage: 'bash auto-shutdown.sh us-east-1'     },
  { id: 2, name: 'S3 Lifecycle Policy',     description: 'Apply intelligent-tiering lifecycle policies to reduce long-term storage costs', category: 'terraform', code: 'resource "aws_s3_bucket_lifecycle_configuration" "example" {\n  bucket = var.bucket_id\n  rule { id = "tier" status = "Enabled" ... }\n}', downloads: 98,  lastUpdated: '2026-03-15', version: '2.0.1', usage: 'terraform apply -var bucket_id=my-bucket' },
  { id: 3, name: 'RDS Backup Validator',    description: 'Validate that all RDS instances have automated backups enabled with 7-day retention', category: 'python', code: 'import boto3\n\ndef check_rds_backups(region="us-east-1"):\n    client = boto3.client("rds", region_name=region)\n    ...',        downloads: 76,  lastUpdated: '2026-04-10', version: '1.0.3', usage: 'python rds_backup_check.py'          },
  { id: 4, name: 'K8s Resource Limits',     description: 'Enforce CPU and memory resource limits on all Kubernetes deployments via Ansible', category: 'ansible',  code: '---\n- name: Apply K8s Resource Limits\n  hosts: localhost\n  tasks:\n    - name: Patch deployments ...',                                 downloads: 54,  lastUpdated: '2026-02-28', version: '1.1.0', usage: 'ansible-playbook k8s-limits.yml'      },
  { id: 5, name: 'IAM Policy Auditor',      description: 'Scan IAM policies for overly-permissive wildcards and generate a remediation report', category: 'python', code: 'import boto3, json\n\ndef audit_iam_policies():\n    iam = boto3.client("iam")\n    policies = iam.list_policies(Scope="Local")...',        downloads: 120, lastUpdated: '2026-04-20', version: '1.3.1', usage: 'python iam_auditor.py'               },
];

// ─── /api/dbops/*/summary ─────────────────────────────────────────────────────
export const demoDbSavingsSummary = {
  totalDbMonthlyCost: 2840.00,
  totalInstances: 8,
  idleInstances: 2,
  idleSavingsPotential: 280.00,
  rightsizingCandidates: 3,
  rightsizingSavingsPotential: 440.00,
  snapshotWasteCost: 64.00,
  totalPotentialSavings: 784.00,
  savingsPercentage: 27.6,
};

export const demoDbInstances = [
  { instanceId: 'rds-prod-001', instanceClass: 'db.m5.xlarge', engine: 'postgres', engineVersion: '15.4', status: 'available', region: 'us-east-1', multiAz: 'true',  storageType: 'gp3', allocatedStorage: 200, publiclyAccessible: false, storageEncrypted: true,  backupEnabled: true, backupRetentionDays: '7', endpoint: 'rds-prod-001.us-east-1.rds.amazonaws.com', estimatedMonthlyCost: 680.00, maxConnections: 1000 },
  { instanceId: 'rds-prod-002', instanceClass: 'db.r5.large',  engine: 'mysql',    engineVersion: '8.0.35', status: 'available', region: 'us-east-1', multiAz: 'false', storageType: 'gp2', allocatedStorage: 100, publiclyAccessible: false, storageEncrypted: false, backupEnabled: true, backupRetentionDays: '7', endpoint: 'rds-prod-002.us-east-1.rds.amazonaws.com', estimatedMonthlyCost: 420.00, maxConnections: 500  },
  { instanceId: 'rds-staging-001', instanceClass: 'db.t3.medium', engine: 'postgres', engineVersion: '15.4', status: 'available', region: 'eu-west-1', multiAz: 'false', storageType: 'gp2', allocatedStorage: 50, publiclyAccessible: false, storageEncrypted: true, backupEnabled: true, backupRetentionDays: '3', endpoint: 'rds-staging-001.eu-west-1.rds.amazonaws.com', estimatedMonthlyCost: 140.00, maxConnections: 200 },
];

export const demoDbCosts = [
  { service: 'Amazon RDS', region: 'us-east-1', cost: 1680.00, percentOfTotal: 59.2 },
  { service: 'Amazon RDS', region: 'eu-west-1', cost:  620.00, percentOfTotal: 21.8 },
  { service: 'DB Snapshots', region: 'us-east-1', cost: 280.00, percentOfTotal: 9.9 },
  { service: 'DB Storage',   region: 'us-east-1', cost: 260.00, percentOfTotal: 9.1 },
];

export const demoDbIdle = [
  { instanceId: 'rds-dev-001',  instanceClass: 'db.t3.small', engine: 'mysql',    region: 'us-east-1', avgCpuPercent: 1.2, avgConnections: 0, estimatedMonthlyCost: 80.00, potentialSavings: 80.00, recommendation: 'Stop or delete — zero connections for 30 days' },
  { instanceId: 'rds-test-002', instanceClass: 'db.t3.micro', engine: 'postgres', region: 'eu-west-1', avgCpuPercent: 0.4, avgConnections: 0, estimatedMonthlyCost: 40.00, potentialSavings: 40.00, recommendation: 'Stop or delete — zero connections for 45 days' },
];

export const demoDbRightsizing = [
  { instanceId: 'rds-prod-002',    currentInstanceClass: 'db.r5.large',   recommendedInstanceClass: 'db.t3.medium', engine: 'mysql',    region: 'us-east-1', avgCpuPercent: 8.4, avgFreeableMemoryMb: 14200, currentMonthlyCost: 420.00, recommendedMonthlyCost: 140.00, estimatedSavings: 280.00, reason: 'CPU below 10%, memory utilisation low over 30 days' },
  { instanceId: 'rds-staging-001', currentInstanceClass: 'db.t3.medium', recommendedInstanceClass: 'db.t3.small',  engine: 'postgres', region: 'eu-west-1', avgCpuPercent: 4.2, avgFreeableMemoryMb: 3600,  currentMonthlyCost: 140.00, recommendedMonthlyCost: 80.00,  estimatedSavings:  60.00, reason: 'Average CPU below 5%, memory headroom available'    },
];

// ─── /api/xamops/aiops/* enhanced data ───────────────────────────────────────
export const demoAIOpsFilterOptions = {
  providers: ['OPENAI', 'ANTHROPIC', 'GEMINI'],
  models: ['gpt-4o', 'gpt-4o-mini', 'claude-sonnet-4-6', 'claude-haiku-4-5', 'gemini-2.0-flash'],
  agents: ['backend-agent', 'data-agent', 'frontend-agent'],
};

export const demoAIOpsModelDailyAnalysis = {
  labels: ['Apr 24', 'Apr 25', 'Apr 26', 'Apr 27', 'Apr 28', 'Apr 29', 'Apr 30'],
  models: ['gpt-4o', 'claude-sonnet-4-6', 'gemini-2.0-flash'],
  costByModel: {
    'gpt-4o':             [14.2, 15.8, 13.6, 16.4, 15.9, 17.2, 16.8],
    'claude-sonnet-4-6':  [10.8, 11.4, 10.2, 12.1, 11.6, 12.8, 11.9],
    'gemini-2.0-flash':   [ 5.4,  5.8,  5.2,  6.1,  5.7,  6.4,  5.9],
  },
  tokensByModel: {
    'gpt-4o':            [2100000, 2340000, 2010000, 2420000, 2350000, 2540000, 2480000],
    'claude-sonnet-4-6': [1580000, 1680000, 1500000, 1780000, 1710000, 1880000, 1750000],
    'gemini-2.0-flash':  [ 800000,  860000,  770000,  900000,  840000,  950000,  870000],
  },
};

export const demoAIOpsInsightsSummary = {
  insights: [
    { id: 'i-001', type: 'COST_OPTIMIZATION', severity: 'ACTIONABLE', title: 'Route batch jobs to Gemini Flash', description: 'Batch processing workloads could save 60% by routing to Gemini 2.0 Flash instead of GPT-4o', impact: '$280/month', actionable: true,  actionLabel: 'Create Routing Rule', metadata: {} },
    { id: 'i-002', type: 'USAGE_PATTERN',     severity: 'INFO',       title: 'Peak usage 9–11am weekdays',     description: 'Request volume spikes 3× during morning hours — consider pre-warming or rate limiting',  impact: null,         actionable: false, actionLabel: null,                  metadata: {} },
    { id: 'i-003', type: 'ANOMALY',           severity: 'WARNING',    title: 'OpenAI spend 42% above baseline', description: 'April 18 showed unusual spike in OpenAI costs. Investigate potential runaway requests.',  impact: '$42 excess', actionable: true,  actionLabel: 'View Anomaly',        metadata: {} },
  ],
  totalInsights: 3,
  actionableCount: 2,
  estimatedSavingsUsd: 280.00,
  generatedAt: new Date().toISOString(),
};

export const demoAIOpsRoutingStats = {
  totalRoutedRequests: 0,
  estimatedSavingsUsd: 0,
  actualSpendUsd: 0,
  wouldHaveSpentUsd: 0,
  rules: [],
  savingsByRule: [],
};

export const demoAIOpsTeamAnalytics = {
  teams: [
    { teamName: 'Backend Team',  requests: 68400, totalTokens: 48200000, cost: 620.40, avgLatencyMs: 980, modelsUsed: 3, costPct: 48.3 },
    { teamName: 'Frontend Team', requests: 41200, totalTokens: 28400000, cost: 382.20, avgLatencyMs: 740, modelsUsed: 2, costPct: 29.7 },
    { teamName: 'Data Science',  requests: 33000, totalTokens: 18400000, cost: 282.00, avgLatencyMs: 620, modelsUsed: 2, costPct: 22.0 },
  ],
  costTrend: {
    labels: ['Nov', 'Dec', 'Jan', 'Feb', 'Mar', 'Apr'],
    seriesByTeam: {
      'Backend Team':  [280, 340, 390, 420, 460, 620],
      'Frontend Team': [180, 220, 260, 290, 320, 382],
      'Data Science':  [120, 160, 190, 220, 250, 282],
    },
  },
  totalTeams: 3,
  totalCost: 1284.60,
};

export const demoAIOpsRequestLogs = {
  content: [
    { id: 1001, timestamp: '2026-04-30T10:14:32Z', provider: 'OPENAI',    model: 'gpt-4o',             agentName: 'backend-agent',  inputTokens: 1240, outputTokens: 480, cost: 0.0242, latencyMs: 1840, statusCode: 200, error: false },
    { id: 1002, timestamp: '2026-04-30T10:12:18Z', provider: 'ANTHROPIC', model: 'claude-sonnet-4-6',  agentName: null,             inputTokens: 860,  outputTokens: 320, cost: 0.0148, latencyMs: 960,  statusCode: 200, error: false },
    { id: 1003, timestamp: '2026-04-30T10:10:45Z', provider: 'GEMINI',    model: 'gemini-2.0-flash',   agentName: 'data-agent',     inputTokens: 2100, outputTokens: 840, cost: 0.0084, latencyMs: 520,  statusCode: 200, error: false },
    { id: 1004, timestamp: '2026-04-30T10:08:22Z', provider: 'OPENAI',    model: 'gpt-4o-mini',        agentName: null,             inputTokens: 420,  outputTokens: 180, cost: 0.0024, latencyMs: 480,  statusCode: 200, error: false },
    { id: 1005, timestamp: '2026-04-30T10:06:11Z', provider: 'ANTHROPIC', model: 'claude-haiku-4-5',   agentName: 'frontend-agent', inputTokens: 640,  outputTokens: 240, cost: 0.0044, latencyMs: 380,  statusCode: 200, error: false },
  ],
  page: 0, size: 10, totalElements: 142600, totalPages: 14260,
};

export const demoAIOpsPerformanceMetrics = {
  latencyByModel: [
    { model: 'gpt-4o',           provider: 'OPENAI',    p50: 1240, p95: 2840, p99: 4200, avgLatencyMs: 1420, totalRequests: 42000 },
    { model: 'claude-sonnet-4-6',provider: 'ANTHROPIC', p50: 840,  p95: 1980, p99: 2840, avgLatencyMs: 980,  totalRequests: 28000 },
    { model: 'gemini-2.0-flash', provider: 'GEMINI',    p50: 420,  p95: 980,  p99: 1420, avgLatencyMs: 540,  totalRequests: 33000 },
  ],
  errorRateTrend: [
    { date: '2026-04-24', totalRequests: 18400, errors: 92,  errorRate: 0.5 },
    { date: '2026-04-25', totalRequests: 19800, errors: 99,  errorRate: 0.5 },
    { date: '2026-04-26', totalRequests: 17600, errors: 106, errorRate: 0.6 },
    { date: '2026-04-27', totalRequests: 21200, errors: 127, errorRate: 0.6 },
    { date: '2026-04-28', totalRequests: 20400, errors: 102, errorRate: 0.5 },
    { date: '2026-04-29', totalRequests: 22400, errors: 112, errorRate: 0.5 },
    { date: '2026-04-30', totalRequests: 22800, errors: 137, errorRate: 0.6 },
  ],
  latencyDistribution: [
    { bucket: '0-500ms',    count: 42000 },
    { bucket: '500-1000ms', count: 38600 },
    { bucket: '1-2s',       count: 28400 },
    { bucket: '2-5s',       count: 18200 },
    { bucket: '5s+',        count:  4200 },
  ],
  modelReliability: [
    { model: 'gpt-4o',           provider: 'OPENAI',    totalRequests: 42000, errorCount: 210, errorRate: 0.5, avgLatencyMs: 1420, reliabilityScore: 96.8 },
    { model: 'claude-sonnet-4-6',provider: 'ANTHROPIC', totalRequests: 28000, errorCount: 140, errorRate: 0.5, avgLatencyMs: 980,  reliabilityScore: 97.2 },
    { model: 'gemini-2.0-flash', provider: 'GEMINI',    totalRequests: 33000, errorCount: 198, errorRate: 0.6, avgLatencyMs: 540,  reliabilityScore: 96.4 },
  ],
};

export const demoAIOpsComputeMetrics = {
  totalRequests: 142600,
  totalTokens: 105600000,
  totalComputeTimeSec: 124200,
  totalCost: 1284.60,
  providerBreakdown: [
    { provider: 'OPENAI',    totalRequests: 68400, totalInputTokens: 42000000, totalOutputTokens: 18000000, totalCost: 620.40, avgLatencyMs: 1240, computeTimeSec: 54200 },
    { provider: 'ANTHROPIC', totalRequests: 41200, totalInputTokens: 28400000, totalOutputTokens: 10200000, totalCost: 482.20, avgLatencyMs: 980,  computeTimeSec: 42400 },
    { provider: 'GEMINI',    totalRequests: 33000, totalInputTokens: 14400000, totalOutputTokens:  5600000, totalCost: 182.00, avgLatencyMs: 540,  computeTimeSec: 27600 },
  ],
  dailyTrend: {
    labels: ['Apr 24', 'Apr 25', 'Apr 26', 'Apr 27', 'Apr 28', 'Apr 29', 'Apr 30'],
    inputTokens:     [13400000, 14800000, 12800000, 15200000, 14600000, 16200000, 15800000],
    outputTokens:    [ 4200000,  4600000,  4000000,  4800000,  4600000,  5100000,  5000000],
    computeTimeSec:  [    16400,    18200,    15600,    18800,    18000,    20200,    19400],
  },
  modelAllocation: [
    { model: 'gpt-4o',           provider: 'OPENAI',    requests: 42000, totalTokens: 48000000, cost: 480.20, computeTimeSec: 38400, pctOfTotalCompute: 40.2 },
    { model: 'claude-sonnet-4-6',provider: 'ANTHROPIC', requests: 28000, totalTokens: 36800000, cost: 342.80, computeTimeSec: 32000, pctOfTotalCompute: 30.8 },
    { model: 'gemini-2.0-flash', provider: 'GEMINI',    requests: 33000, totalTokens: 18400000, cost: 182.00, computeTimeSec: 22800, pctOfTotalCompute: 22.0 },
    { model: 'gpt-4o-mini',      provider: 'OPENAI',    requests: 26400, totalTokens: 18200000, cost: 140.20, computeTimeSec: 14400, pctOfTotalCompute:  7.0 },
  ],
};

export const demoAIOpsInferenceOverview = {
  totalEndpoints: 5, activeEndpoints: 4, degradedEndpoints: 1,
  totalCost: 1284.60, avgLatency: 892,
  endpoints: [
    { id: 1, provider: 'OPENAI',    model: 'gpt-4o',           displayName: 'GPT-4o Production', status: 'ACTIVE',   totalRequests: 42000, totalCost: 480.20, avgLatencyMs: 1420, errorRate: 0.5, uptimePct: 99.8, lastRequestAt: '2026-04-30T10:14:32Z', createdAt: '2026-01-15T00:00:00Z' },
    { id: 2, provider: 'ANTHROPIC', model: 'claude-sonnet-4-6',displayName: 'Claude Sonnet',     status: 'ACTIVE',   totalRequests: 28000, totalCost: 342.80, avgLatencyMs: 980,  errorRate: 0.5, uptimePct: 99.9, lastRequestAt: '2026-04-30T10:12:18Z', createdAt: '2026-01-20T00:00:00Z' },
    { id: 3, provider: 'GEMINI',    model: 'gemini-2.0-flash', displayName: 'Gemini Flash',      status: 'ACTIVE',   totalRequests: 33000, totalCost: 182.00, avgLatencyMs: 540,  errorRate: 0.6, uptimePct: 99.7, lastRequestAt: '2026-04-30T10:10:45Z', createdAt: '2026-02-01T00:00:00Z' },
    { id: 4, provider: 'OPENAI',    model: 'gpt-4o-mini',      displayName: 'GPT-4o Mini',       status: 'ACTIVE',   totalRequests: 26400, totalCost: 140.20, avgLatencyMs: 480,  errorRate: 0.3, uptimePct: 99.9, lastRequestAt: '2026-04-30T10:08:22Z', createdAt: '2026-02-15T00:00:00Z' },
    { id: 5, provider: 'ANTHROPIC', model: 'claude-haiku-4-5', displayName: 'Claude Haiku',      status: 'DEGRADED', totalRequests: 13200, totalCost: 139.40, avgLatencyMs: 2840, errorRate: 4.2, uptimePct: 94.2, lastRequestAt: '2026-04-30T09:20:00Z', createdAt: '2026-03-01T00:00:00Z' },
  ],
};

export const demoAIOpsApiKeys = [
  { id: 1, provider: 'OPENAI',    displayName: 'GPT-4o Production Key', maskedKey: 'sk-...abc1',     status: 'ACTIVE', healthStatus: 'HEALTHY', requestCount: 68400, errorCount: 342, lastUsedAt: '2026-04-30T10:14:32Z', lastErrorAt: null, lastErrorMessage: null, usageLimit: null, createdAt: '2026-01-15T00:00:00Z' },
  { id: 2, provider: 'ANTHROPIC', displayName: 'Claude API Key',        maskedKey: 'sk-ant-...def2', status: 'ACTIVE', healthStatus: 'HEALTHY', requestCount: 41200, errorCount: 206, lastUsedAt: '2026-04-30T10:12:18Z', lastErrorAt: null, lastErrorMessage: null, usageLimit: null, createdAt: '2026-01-20T00:00:00Z' },
  { id: 3, provider: 'GEMINI',    displayName: 'Gemini API Key',        maskedKey: 'AIza...ghi3',    status: 'ACTIVE', healthStatus: 'HEALTHY', requestCount: 33000, errorCount: 198, lastUsedAt: '2026-04-30T10:10:45Z', lastErrorAt: null, lastErrorMessage: null, usageLimit: null, createdAt: '2026-02-01T00:00:00Z' },
];

export const demoAIOPsBudgetSummary = {
  totalBudgetUsd: 2000.00,
  totalSpendUsd: 1284.60,
  overallUsagePct: 64.2,
  activeBudgetCount: 2,
  alertsThisMonth: 1,
  budgets: [
    { budgetId: 1, budgetName: 'Monthly AI Budget', providerConfigId: null, providerName: null,     monthlyLimitUsd: 1500, currentSpendUsd: 1284.60, usagePct: 85.6, autoDisable: false, status: 'ACTIVE', thresholds: [75, 90, 100], breachedThresholds: [75], projectedSpend: 1485.00, burnRate: 41.44, estimatedRunoutDay: 5  },
    { budgetId: 2, budgetName: 'OpenAI Budget',     providerConfigId: 1,    providerName: 'OPENAI', monthlyLimitUsd: 800,  currentSpendUsd: 620.40,  usagePct: 77.6, autoDisable: true,  status: 'ACTIVE', thresholds: [75, 90, 100], breachedThresholds: [75], projectedSpend: 716.00,  burnRate: 20.01, estimatedRunoutDay: 12 },
  ],
  recentAlerts: [],
};

// ─── /api/xamops/eks/{cluster}/dashboard ─────────────────────────────────────
export const demoEksDashboard = {
  lastSyncedAt: new Date().toISOString(),
  clusterOverview: {
    totalNodes: 6,
    totalNamespaces: 8,
    runningPods: 52,
    pendingPods: 2,
    failedPods: 0,
    cpuUsageMillicores: 12480,
    cpuCapacityMillicores: 24000,
    cpuUsagePercent: 52.0,
    memoryUsageBytes: 42949672960,
    memoryCapacityBytes: 68719476736,
    memoryUsagePercent: 62.5,
  },
  costMetrics: {
    dailyCost: 48.60,
    monthlyCost: 1480.20,
    nodeCosts: [
      { nodeName: 'ip-10-0-1-101.us-east-1.compute.internal', instanceType: 'm5.xlarge',  hourlyRate: 0.192, dailyCost: 4.61, cpuUsagePercent: 58.0 },
      { nodeName: 'ip-10-0-1-102.us-east-1.compute.internal', instanceType: 'm5.xlarge',  hourlyRate: 0.192, dailyCost: 4.61, cpuUsagePercent: 47.0 },
      { nodeName: 'ip-10-0-1-103.us-east-1.compute.internal', instanceType: 'm5.2xlarge', hourlyRate: 0.384, dailyCost: 9.22, cpuUsagePercent: 61.0 },
      { nodeName: 'ip-10-0-1-104.us-east-1.compute.internal', instanceType: 'c5.xlarge',  hourlyRate: 0.170, dailyCost: 4.08, cpuUsagePercent: 44.0 },
      { nodeName: 'ip-10-0-1-105.us-east-1.compute.internal', instanceType: 'c5.xlarge',  hourlyRate: 0.170, dailyCost: 4.08, cpuUsagePercent: 38.0 },
      { nodeName: 'ip-10-0-1-106.us-east-1.compute.internal', instanceType: 'r5.large',   hourlyRate: 0.126, dailyCost: 3.02, cpuUsagePercent: 31.0 },
    ],
    namespaceCosts: [
      { namespace: 'production',  dailyCost: 22.40, monthlyCost: 672.00,  cpuUsageMillicores: 6200, memoryUsageMb: 12288, podCount: 24, costPercentage: 45.4 },
      { namespace: 'staging',     dailyCost: 12.80, monthlyCost: 384.00,  cpuUsageMillicores: 3400, memoryUsageMb:  8192, podCount: 14, costPercentage: 26.3 },
      { namespace: 'monitoring',  dailyCost:  8.20, monthlyCost: 246.00,  cpuUsageMillicores: 1800, memoryUsageMb:  6144, podCount:  8, costPercentage: 16.6 },
      { namespace: 'kube-system', dailyCost:  5.20, monthlyCost: 156.00,  cpuUsageMillicores:  880, memoryUsageMb:  3072, podCount: 12, costPercentage: 10.6 },
    ],
  },
  securityMetrics: {
    totalEvents: 3,
    eventsByPriority: { WARNING: 2, NOTICE: 1 },
    alerts: [
      { rule: 'Terminal shell in container', priority: 'WARNING', count: 2, lastSeen: '2026-04-30T08:12:00Z', namespace: 'staging' },
      { rule: 'Read sensitive file',         priority: 'NOTICE',  count: 1, lastSeen: '2026-04-29T14:44:00Z', namespace: 'production' },
    ],
  },
  resourceUtilization: {
    nodes: [
      { nodeName: 'ip-10-0-1-101', cpuUsagePercent: 58.0, memoryUsagePercent: 64.0, cpuRequestsPercent: 72.0, memoryRequestsPercent: 68.0 },
      { nodeName: 'ip-10-0-1-102', cpuUsagePercent: 47.0, memoryUsagePercent: 59.0, cpuRequestsPercent: 61.0, memoryRequestsPercent: 62.0 },
      { nodeName: 'ip-10-0-1-103', cpuUsagePercent: 61.0, memoryUsagePercent: 67.0, cpuRequestsPercent: 74.0, memoryRequestsPercent: 70.0 },
      { nodeName: 'ip-10-0-1-104', cpuUsagePercent: 44.0, memoryUsagePercent: 51.0, cpuRequestsPercent: 55.0, memoryRequestsPercent: 58.0 },
      { nodeName: 'ip-10-0-1-105', cpuUsagePercent: 38.0, memoryUsagePercent: 48.0, cpuRequestsPercent: 50.0, memoryRequestsPercent: 52.0 },
      { nodeName: 'ip-10-0-1-106', cpuUsagePercent: 31.0, memoryUsagePercent: 42.0, cpuRequestsPercent: 40.0, memoryRequestsPercent: 45.0 },
    ],
  },
  // k8sPods: preferred by hooks over workloadHealth.pods; each pod needs containers[] for the Containers tab
  k8sPods: [
    { name: 'api-server-6d8f7b9c4-xk2pq',  namespace: 'production',  phase: 'Running', qosClass: 'Guaranteed', nodeName: 'ip-10-0-1-101', cpuUsage: 320,  memoryUsage: 512,  podIp: '10.0.1.20', age: '18d', ownerKind: 'ReplicaSet', ownerName: 'api-server-6d8f7b9c4',  cpuRequest: 256,  memoryRequest: 512,  cpuLimit: 512,  memoryLimit: 1024, containers: [{ name: 'api-server',   image: 'xammer/api-server:v2.4.1',   state: 'running', ready: true, restartCount: 0, cpuRequest: '256m', cpuLimit: '512m', memoryRequest: '512Mi', memoryLimit: '1Gi'  }] },
    { name: 'api-server-6d8f7b9c4-ml9wr',  namespace: 'production',  phase: 'Running', qosClass: 'Guaranteed', nodeName: 'ip-10-0-1-102', cpuUsage: 280,  memoryUsage: 488,  podIp: '10.0.1.21', age: '18d', ownerKind: 'ReplicaSet', ownerName: 'api-server-6d8f7b9c4',  cpuRequest: 256,  memoryRequest: 512,  cpuLimit: 512,  memoryLimit: 1024, containers: [{ name: 'api-server',   image: 'xammer/api-server:v2.4.1',   state: 'running', ready: true, restartCount: 0, cpuRequest: '256m', cpuLimit: '512m', memoryRequest: '512Mi', memoryLimit: '1Gi'  }] },
    { name: 'api-server-6d8f7b9c4-rz8tq',  namespace: 'production',  phase: 'Running', qosClass: 'Guaranteed', nodeName: 'ip-10-0-1-103', cpuUsage: 295,  memoryUsage: 500,  podIp: '10.0.1.22', age: '18d', ownerKind: 'ReplicaSet', ownerName: 'api-server-6d8f7b9c4',  cpuRequest: 256,  memoryRequest: 512,  cpuLimit: 512,  memoryLimit: 1024, containers: [{ name: 'api-server',   image: 'xammer/api-server:v2.4.1',   state: 'running', ready: true, restartCount: 1, cpuRequest: '256m', cpuLimit: '512m', memoryRequest: '512Mi', memoryLimit: '1Gi'  }] },
    { name: 'worker-7c9f4d5e2-nt3vb',       namespace: 'production',  phase: 'Running', qosClass: 'Burstable',  nodeName: 'ip-10-0-1-103', cpuUsage: 180,  memoryUsage: 256,  podIp: '10.0.1.23', age: '12d', ownerKind: 'ReplicaSet', ownerName: 'worker-7c9f4d5e2',      cpuRequest: 100,  memoryRequest: 256,  cpuLimit: 400,  memoryLimit: 512,  containers: [{ name: 'worker',       image: 'xammer/worker:v1.8.0',       state: 'running', ready: true, restartCount: 0, cpuRequest: '100m', cpuLimit: '400m', memoryRequest: '256Mi', memoryLimit: '512Mi' }] },
    { name: 'worker-7c9f4d5e2-pk4cz',       namespace: 'production',  phase: 'Running', qosClass: 'Burstable',  nodeName: 'ip-10-0-1-104', cpuUsage: 165,  memoryUsage: 244,  podIp: '10.0.1.24', age: '12d', ownerKind: 'ReplicaSet', ownerName: 'worker-7c9f4d5e2',      cpuRequest: 100,  memoryRequest: 256,  cpuLimit: 400,  memoryLimit: 512,  containers: [{ name: 'worker',       image: 'xammer/worker:v1.8.0',       state: 'running', ready: true, restartCount: 0, cpuRequest: '100m', cpuLimit: '400m', memoryRequest: '256Mi', memoryLimit: '512Mi' }] },
    { name: 'frontend-5b8c9d7f6-qw2xp',     namespace: 'production',  phase: 'Running', qosClass: 'Burstable',  nodeName: 'ip-10-0-1-101', cpuUsage: 95,   memoryUsage: 180,  podIp: '10.0.1.25', age: '10d', ownerKind: 'ReplicaSet', ownerName: 'frontend-5b8c9d7f6',    cpuRequest: 50,   memoryRequest: 128,  cpuLimit: 200,  memoryLimit: 256,  containers: [{ name: 'frontend',     image: 'xammer/frontend:v3.1.2',     state: 'running', ready: true, restartCount: 0, cpuRequest: '50m',  cpuLimit: '200m', memoryRequest: '128Mi', memoryLimit: '256Mi' }] },
    { name: 'frontend-5b8c9d7f6-hm7vn',     namespace: 'production',  phase: 'Running', qosClass: 'Burstable',  nodeName: 'ip-10-0-1-102', cpuUsage: 88,   memoryUsage: 172,  podIp: '10.0.1.26', age: '10d', ownerKind: 'ReplicaSet', ownerName: 'frontend-5b8c9d7f6',    cpuRequest: 50,   memoryRequest: 128,  cpuLimit: 200,  memoryLimit: 256,  containers: [{ name: 'frontend',     image: 'xammer/frontend:v3.1.2',     state: 'running', ready: true, restartCount: 0, cpuRequest: '50m',  cpuLimit: '200m', memoryRequest: '128Mi', memoryLimit: '256Mi' }] },
    { name: 'prometheus-0',                  namespace: 'monitoring',  phase: 'Running', qosClass: 'Guaranteed', nodeName: 'ip-10-0-1-101', cpuUsage: 420,  memoryUsage: 1024, podIp: '10.0.2.10', age: '25d', ownerKind: 'StatefulSet', ownerName: 'prometheus',           cpuRequest: 500,  memoryRequest: 2048, cpuLimit: 1000, memoryLimit: 4096, containers: [{ name: 'prometheus',   image: 'prom/prometheus:v2.45.0',    state: 'running', ready: true, restartCount: 0, cpuRequest: '500m', cpuLimit: '1000m',memoryRequest: '2Gi', memoryLimit: '4Gi'  }] },
    { name: 'grafana-6c7d8e9f0-ab3kx',      namespace: 'monitoring',  phase: 'Running', qosClass: 'Burstable',  nodeName: 'ip-10-0-1-105', cpuUsage: 140,  memoryUsage: 320,  podIp: '10.0.2.11', age: '25d', ownerKind: 'ReplicaSet', ownerName: 'grafana-6c7d8e9f0',     cpuRequest: 100,  memoryRequest: 256,  cpuLimit: 300,  memoryLimit: 512,  containers: [{ name: 'grafana',      image: 'grafana/grafana:10.2.0',     state: 'running', ready: true, restartCount: 0, cpuRequest: '100m', cpuLimit: '300m', memoryRequest: '256Mi', memoryLimit: '512Mi' }] },
    { name: 'alertmanager-0',               namespace: 'monitoring',  phase: 'Running', qosClass: 'Guaranteed', nodeName: 'ip-10-0-1-106', cpuUsage: 55,   memoryUsage: 96,   podIp: '10.0.2.12', age: '25d', ownerKind: 'StatefulSet', ownerName: 'alertmanager',         cpuRequest: 50,   memoryRequest: 128,  cpuLimit: 200,  memoryLimit: 256,  containers: [{ name: 'alertmanager', image: 'prom/alertmanager:v0.26.0',  state: 'running', ready: true, restartCount: 0, cpuRequest: '50m',  cpuLimit: '200m', memoryRequest: '128Mi', memoryLimit: '256Mi' }] },
    { name: 'api-staging-84c6d9f7-vx5pt',   namespace: 'staging',     phase: 'Running', qosClass: 'Burstable',  nodeName: 'ip-10-0-1-104', cpuUsage: 120,  memoryUsage: 200,  podIp: '10.0.3.10', age: '5d',  ownerKind: 'ReplicaSet', ownerName: 'api-staging-84c6d9f7',  cpuRequest: 100,  memoryRequest: 256,  cpuLimit: 300,  memoryLimit: 512,  containers: [{ name: 'api-server',   image: 'xammer/api-server:v2.4.0',   state: 'running', ready: true, restartCount: 2, cpuRequest: '100m', cpuLimit: '300m', memoryRequest: '256Mi', memoryLimit: '512Mi' }] },
    { name: 'worker-staging-92f1e4c8-kd2mz',namespace: 'staging',     phase: 'Running', qosClass: 'Burstable',  nodeName: 'ip-10-0-1-105', cpuUsage: 75,   memoryUsage: 128,  podIp: '10.0.3.11', age: '5d',  ownerKind: 'ReplicaSet', ownerName: 'worker-staging-92f1e4c8', cpuRequest: 50,   memoryRequest: 128,  cpuLimit: 200,  memoryLimit: 256,  containers: [{ name: 'worker',       image: 'xammer/worker:v1.7.5',       state: 'running', ready: true, restartCount: 0, cpuRequest: '50m',  cpuLimit: '200m', memoryRequest: '128Mi', memoryLimit: '256Mi' }] },
    { name: 'coredns-7c65d6d98-hz9kp',      namespace: 'kube-system', phase: 'Running', qosClass: 'Burstable',  nodeName: 'ip-10-0-1-101', cpuUsage: 18,   memoryUsage: 30,   podIp: '10.0.0.10', age: '30d', ownerKind: 'ReplicaSet', ownerName: 'coredns-7c65d6d98',     cpuRequest: 100,  memoryRequest: 70,   cpuLimit: 0,    memoryLimit: 170,  containers: [{ name: 'coredns',      image: 'registry.k8s.io/coredns/coredns:v1.10.1', state: 'running', ready: true, restartCount: 0, cpuRequest: '100m', cpuLimit: '',     memoryRequest: '70Mi',  memoryLimit: '170Mi' }] },
    { name: 'coredns-7c65d6d98-rn4qs',      namespace: 'kube-system', phase: 'Running', qosClass: 'Burstable',  nodeName: 'ip-10-0-1-102', cpuUsage: 14,   memoryUsage: 28,   podIp: '10.0.0.11', age: '30d', ownerKind: 'ReplicaSet', ownerName: 'coredns-7c65d6d98',     cpuRequest: 100,  memoryRequest: 70,   cpuLimit: 0,    memoryLimit: 170,  containers: [{ name: 'coredns',      image: 'registry.k8s.io/coredns/coredns:v1.10.1', state: 'running', ready: true, restartCount: 0, cpuRequest: '100m', cpuLimit: '',     memoryRequest: '70Mi',  memoryLimit: '170Mi' }] },
    { name: 'aws-node-t7bkx',               namespace: 'kube-system', phase: 'Running', qosClass: 'Guaranteed', nodeName: 'ip-10-0-1-103', cpuUsage: 8,    memoryUsage: 42,   podIp: '10.0.0.12', age: '30d', ownerKind: 'DaemonSet',  ownerName: 'aws-node',              cpuRequest: 25,   memoryRequest: 0,    cpuLimit: 0,    memoryLimit: 0,    containers: [{ name: 'aws-node',     image: '602401143452.dkr.ecr.us-east-1.amazonaws.com/amazon-k8s-cni:v1.15.5', state: 'running', ready: true, restartCount: 0, cpuRequest: '25m', cpuLimit: '', memoryRequest: '', memoryLimit: '' }] },
    { name: 'kube-proxy-9hvms',             namespace: 'kube-system', phase: 'Running', qosClass: 'Guaranteed', nodeName: 'ip-10-0-1-104', cpuUsage: 5,    memoryUsage: 20,   podIp: '10.0.0.13', age: '30d', ownerKind: 'DaemonSet',  ownerName: 'kube-proxy',            cpuRequest: 100,  memoryRequest: 0,    cpuLimit: 0,    memoryLimit: 0,    containers: [{ name: 'kube-proxy',   image: '602401143452.dkr.ecr.us-east-1.amazonaws.com/eks/kube-proxy:v1.29.0',  state: 'running', ready: true, restartCount: 0, cpuRequest: '100m',cpuLimit: '', memoryRequest: '', memoryLimit: '' }] },
    { name: 'data-pipeline-pending-1',      namespace: 'staging',     phase: 'Pending', qosClass: 'BestEffort', nodeName: '',              cpuUsage: 0,    memoryUsage: 0,    podIp: '',          age: '2m',  ownerKind: 'Job',        ownerName: 'data-pipeline',         cpuRequest: 0,    memoryRequest: 0,    cpuLimit: 0,    memoryLimit: 0,    containers: [{ name: 'pipeline',     image: 'xammer/data-pipeline:v0.9.1', state: 'waiting', ready: false, restartCount: 0, waitingReason: 'ContainerCreating' }] },
    { name: 'report-job-pending-1',         namespace: 'staging',     phase: 'Pending', qosClass: 'BestEffort', nodeName: '',              cpuUsage: 0,    memoryUsage: 0,    podIp: '',          age: '1m',  ownerKind: 'Job',        ownerName: 'report-job',            cpuRequest: 0,    memoryRequest: 0,    cpuLimit: 0,    memoryLimit: 0,    containers: [{ name: 'reporter',     image: 'xammer/reporter:v1.2.0',     state: 'waiting', ready: false, restartCount: 0, waitingReason: 'PodScheduled'      }] },
  ],
  workloadHealth: {
    pods: [],
    deployments: [
      { name: 'api-server', namespace: 'production', replicas: 3, availableReplicas: 3, readyReplicas: 3 },
      { name: 'worker',     namespace: 'production', replicas: 2, availableReplicas: 2, readyReplicas: 2 },
      { name: 'frontend',   namespace: 'production', replicas: 2, availableReplicas: 2, readyReplicas: 2 },
      { name: 'grafana',    namespace: 'monitoring', replicas: 1, availableReplicas: 1, readyReplicas: 1 },
      { name: 'api-staging',namespace: 'staging',    replicas: 1, availableReplicas: 1, readyReplicas: 1 },
    ],
  },
  nodeMetrics: {
    nodes: [
      { name: 'ip-10-0-1-101', ready: true, diskPressure: false, memoryPressure: false, pidPressure: false, instanceType: 'm5.xlarge',  cpuCapacity: 4000,  memoryCapacity: 16384, capacityType: 'on-demand', zone: 'us-east-1a', podCount: 18, runningPodsCount: 18 },
      { name: 'ip-10-0-1-102', ready: true, diskPressure: false, memoryPressure: false, pidPressure: false, instanceType: 'm5.xlarge',  cpuCapacity: 4000,  memoryCapacity: 16384, capacityType: 'spot',      zone: 'us-east-1b', podCount: 14, runningPodsCount: 14 },
      { name: 'ip-10-0-1-103', ready: true, diskPressure: false, memoryPressure: false, pidPressure: false, instanceType: 'm5.2xlarge', cpuCapacity: 8000,  memoryCapacity: 32768, capacityType: 'on-demand', zone: 'us-east-1c', podCount: 26, runningPodsCount: 26 },
      { name: 'ip-10-0-1-104', ready: true, diskPressure: false, memoryPressure: false, pidPressure: false, instanceType: 'c5.xlarge',  cpuCapacity: 4000,  memoryCapacity: 8192,  capacityType: 'spot',      zone: 'us-east-1a', podCount: 10, runningPodsCount: 10 },
      { name: 'ip-10-0-1-105', ready: true, diskPressure: false, memoryPressure: false, pidPressure: false, instanceType: 'c5.xlarge',  cpuCapacity: 4000,  memoryCapacity: 8192,  capacityType: 'spot',      zone: 'us-east-1b', podCount:  8, runningPodsCount:  8 },
      { name: 'ip-10-0-1-106', ready: true, diskPressure: false, memoryPressure: false, pidPressure: false, instanceType: 'r5.large',   cpuCapacity: 2000,  memoryCapacity: 16384, capacityType: 'on-demand', zone: 'us-east-1c', podCount:  6, runningPodsCount:  6 },
    ],
  },
  // Containers tab: enriched CPU/memory metrics per container
  containerMetrics: {
    containers: [
      { name: 'api-server',   pod: 'api-server-6d8f7b9c4-xk2pq',  namespace: 'production',  cpuUsage: 320, memoryUsage: 512,  fsUsage: 128 },
      { name: 'api-server',   pod: 'api-server-6d8f7b9c4-ml9wr',  namespace: 'production',  cpuUsage: 280, memoryUsage: 488,  fsUsage: 128 },
      { name: 'api-server',   pod: 'api-server-6d8f7b9c4-rz8tq',  namespace: 'production',  cpuUsage: 295, memoryUsage: 500,  fsUsage: 128 },
      { name: 'worker',       pod: 'worker-7c9f4d5e2-nt3vb',       namespace: 'production',  cpuUsage: 180, memoryUsage: 256,  fsUsage: 64  },
      { name: 'worker',       pod: 'worker-7c9f4d5e2-pk4cz',       namespace: 'production',  cpuUsage: 165, memoryUsage: 244,  fsUsage: 64  },
      { name: 'frontend',     pod: 'frontend-5b8c9d7f6-qw2xp',     namespace: 'production',  cpuUsage:  95, memoryUsage: 180,  fsUsage: 32  },
      { name: 'frontend',     pod: 'frontend-5b8c9d7f6-hm7vn',     namespace: 'production',  cpuUsage:  88, memoryUsage: 172,  fsUsage: 32  },
      { name: 'prometheus',   pod: 'prometheus-0',                  namespace: 'monitoring',  cpuUsage: 420, memoryUsage: 1024, fsUsage: 512 },
      { name: 'grafana',      pod: 'grafana-6c7d8e9f0-ab3kx',      namespace: 'monitoring',  cpuUsage: 140, memoryUsage: 320,  fsUsage: 96  },
      { name: 'alertmanager', pod: 'alertmanager-0',               namespace: 'monitoring',  cpuUsage:  55, memoryUsage:  96,  fsUsage: 24  },
      { name: 'api-server',   pod: 'api-staging-84c6d9f7-vx5pt',   namespace: 'staging',     cpuUsage: 120, memoryUsage: 200,  fsUsage: 64  },
      { name: 'worker',       pod: 'worker-staging-92f1e4c8-kd2mz',namespace: 'staging',     cpuUsage:  75, memoryUsage: 128,  fsUsage: 32  },
      { name: 'coredns',      pod: 'coredns-7c65d6d98-hz9kp',      namespace: 'kube-system', cpuUsage:  18, memoryUsage:  30,  fsUsage:  8  },
      { name: 'coredns',      pod: 'coredns-7c65d6d98-rn4qs',      namespace: 'kube-system', cpuUsage:  14, memoryUsage:  28,  fsUsage:  8  },
      { name: 'aws-node',     pod: 'aws-node-t7bkx',               namespace: 'kube-system', cpuUsage:   8, memoryUsage:  42,  fsUsage:  4  },
      { name: 'kube-proxy',   pod: 'kube-proxy-9hvms',             namespace: 'kube-system', cpuUsage:   5, memoryUsage:  20,  fsUsage:  4  },
    ],
  },
  // Overview tab: top CPU/memory pods and namespace utilization
  topCpuPods: [
    { name: 'prometheus-0',                 namespace: 'monitoring',  cpuMillicores: 420, memoryMB: 1024 },
    { name: 'api-server-6d8f7b9c4-xk2pq',  namespace: 'production',  cpuMillicores: 320, memoryMB:  512 },
    { name: 'api-server-6d8f7b9c4-rz8tq',  namespace: 'production',  cpuMillicores: 295, memoryMB:  500 },
    { name: 'api-server-6d8f7b9c4-ml9wr',  namespace: 'production',  cpuMillicores: 280, memoryMB:  488 },
    { name: 'worker-7c9f4d5e2-nt3vb',       namespace: 'production',  cpuMillicores: 180, memoryMB:  256 },
  ],
  topMemoryPods: [
    { name: 'prometheus-0',                 namespace: 'monitoring',  cpuMillicores: 420, memoryMB: 1024 },
    { name: 'api-server-6d8f7b9c4-xk2pq',  namespace: 'production',  cpuMillicores: 320, memoryMB:  512 },
    { name: 'api-server-6d8f7b9c4-rz8tq',  namespace: 'production',  cpuMillicores: 295, memoryMB:  500 },
    { name: 'api-server-6d8f7b9c4-ml9wr',  namespace: 'production',  cpuMillicores: 280, memoryMB:  488 },
    { name: 'grafana-6c7d8e9f0-ab3kx',      namespace: 'monitoring',  cpuMillicores: 140, memoryMB:  320 },
  ],
  namespaceUtilization: [
    { namespace: 'production',  cpuUsageMillicores: 1338, cpuLimitMillicores: 2224, memoryUsageMB: 2092,  memoryLimitMB: 4096,  podCount: 7  },
    { namespace: 'monitoring',  cpuUsageMillicores:  615, cpuLimitMillicores: 1500, memoryUsageMB: 1440,  memoryLimitMB: 4864,  podCount: 3  },
    { namespace: 'staging',     cpuUsageMillicores:  195, cpuLimitMillicores:  500, memoryUsageMB:  328,  memoryLimitMB:  768,  podCount: 4  },
    { namespace: 'kube-system', cpuUsageMillicores:   45, cpuLimitMillicores:  325, memoryUsageMB:  120,  memoryLimitMB:  510,  podCount: 4  },
  ],
  // Resources tab: k8sServices, k8sIngresses, k8sPvcs, k8sHpas, k8sEvents
  k8sServices: [
    { name: 'api-server-svc',    namespace: 'production',  type: 'ClusterIP',    clusterIP: '10.100.1.10', ports: '80:8080/TCP', age: '18d' },
    { name: 'api-server-lb',     namespace: 'production',  type: 'LoadBalancer', clusterIP: '10.100.1.11', externalIP: '54.80.1.20', ports: '443:8443/TCP', age: '18d' },
    { name: 'worker-svc',        namespace: 'production',  type: 'ClusterIP',    clusterIP: '10.100.1.12', ports: '9090/TCP',  age: '12d' },
    { name: 'frontend-svc',      namespace: 'production',  type: 'LoadBalancer', clusterIP: '10.100.1.13', externalIP: '54.80.1.21', ports: '80:3000/TCP', age: '10d' },
    { name: 'prometheus-svc',    namespace: 'monitoring',  type: 'ClusterIP',    clusterIP: '10.100.2.10', ports: '9090/TCP',  age: '25d' },
    { name: 'grafana-svc',       namespace: 'monitoring',  type: 'NodePort',     clusterIP: '10.100.2.11', ports: '3000:30900/TCP', age: '25d' },
    { name: 'alertmanager-svc',  namespace: 'monitoring',  type: 'ClusterIP',    clusterIP: '10.100.2.12', ports: '9093/TCP',  age: '25d' },
    { name: 'kube-dns',          namespace: 'kube-system', type: 'ClusterIP',    clusterIP: '10.100.0.10', ports: '53:53/UDP,53:53/TCP', age: '30d' },
  ],
  k8sIngresses: [
    { name: 'api-ingress',      namespace: 'production',  host: 'api.xammer.in',      path: '/',  tls: true,  ingressClass: 'nginx', age: '18d' },
    { name: 'frontend-ingress', namespace: 'production',  host: 'app.xammer.in',      path: '/',  tls: true,  ingressClass: 'nginx', age: '10d' },
    { name: 'grafana-ingress',  namespace: 'monitoring',  host: 'grafana.xammer.in',  path: '/',  tls: true,  ingressClass: 'nginx', age: '25d' },
  ],
  k8sPvcs: [
    { name: 'prometheus-data',   namespace: 'monitoring',  status: 'Bound',   storageClass: 'gp3', capacity: '50Gi',  accessMode: 'ReadWriteOnce', volumeName: 'pvc-prom-001', age: '25d' },
    { name: 'grafana-storage',   namespace: 'monitoring',  status: 'Bound',   storageClass: 'gp3', capacity: '10Gi',  accessMode: 'ReadWriteOnce', volumeName: 'pvc-graf-001', age: '25d' },
    { name: 'api-logs',          namespace: 'production',  status: 'Bound',   storageClass: 'gp3', capacity: '20Gi',  accessMode: 'ReadWriteMany', volumeName: 'pvc-api-logs', age: '18d' },
    { name: 'worker-queue',      namespace: 'production',  status: 'Bound',   storageClass: 'gp2', capacity: '5Gi',   accessMode: 'ReadWriteOnce', volumeName: 'pvc-wrkr-001', age: '12d' },
    { name: 'staging-data',      namespace: 'staging',     status: 'Pending', storageClass: 'gp3', capacity: '10Gi',  accessMode: 'ReadWriteOnce', volumeName: '',             age: '2d'  },
  ],
  k8sHpas: [
    { name: 'api-server-hpa', namespace: 'production', minReplicas: 2, maxReplicas: 10, currentReplicas: 3, desiredReplicas: 3, targetCPUUtilization: 70, currentCPUUtilization: 58, age: '18d' },
    { name: 'worker-hpa',     namespace: 'production', minReplicas: 1, maxReplicas:  5, currentReplicas: 2, desiredReplicas: 2, targetCPUUtilization: 60, currentCPUUtilization: 45, age: '12d' },
    { name: 'frontend-hpa',   namespace: 'production', minReplicas: 2, maxReplicas:  8, currentReplicas: 2, desiredReplicas: 2, targetCPUUtilization: 65, currentCPUUtilization: 32, age: '10d' },
  ],
  k8sEvents: [
    { type: 'Warning', reason: 'BackOff',           namespace: 'staging',     name: 'api-staging-84c6d9f7-vx5pt',    message: 'Back-off restarting failed container api-server in pod',        lastTimestamp: '2026-05-04T14:10:00Z', count: 2 },
    { type: 'Normal',  reason: 'Pulled',            namespace: 'production',  name: 'api-server-6d8f7b9c4-rz8tq',   message: 'Successfully pulled image xammer/api-server:v2.4.1',           lastTimestamp: '2026-05-04T13:45:00Z', count: 1 },
    { type: 'Warning', reason: 'FailedScheduling',  namespace: 'staging',     name: 'data-pipeline-pending-1',       message: 'Insufficient cpu: 1 node(s) had insufficient CPU',              lastTimestamp: '2026-05-04T14:15:00Z', count: 3 },
    { type: 'Normal',  reason: 'ScalingReplicaSet', namespace: 'production',  name: 'api-server',                   message: 'Scaled up replica set api-server-6d8f7b9c4 to 3',              lastTimestamp: '2026-05-03T09:00:00Z', count: 1 },
    { type: 'Normal',  reason: 'NodeReady',         namespace: 'kube-system', name: 'ip-10-0-1-106',                message: 'Node ip-10-0-1-106 status is now: NodeReady',                  lastTimestamp: '2026-05-01T08:00:00Z', count: 1 },
  ],
  kubescape: null,
  trivy: null,
  karpenter: null,
};

// ─── Observability (/api/observability/*) ────────────────────────────────────
export const demoObservabilityConfig = {
  orgId: 'xammer-demo-001',
  accountName: 'Xammer Production',
  retentionDays: 14,
  provisionedAt: '2026-01-10T09:00:00Z',
};

export const demoObservabilityStreams = [
  { name: 'k8s-logs',        stream_type: 'logs',    stats: { doc_count: 142600, storage_size: 2048000 } },
  { name: 'app-logs',        stream_type: 'logs',    stats: { doc_count: 284200, storage_size: 4096000 } },
  { name: 'nginx-logs',      stream_type: 'logs',    stats: { doc_count:  48000, storage_size:  512000 } },
  { name: 'node-metrics',    stream_type: 'metrics', stats: { doc_count: 864000, storage_size: 1024000 } },
  { name: 'k8s-metrics',     stream_type: 'metrics', stats: { doc_count: 432000, storage_size:  768000 } },
  { name: 'traces-default',  stream_type: 'traces',  stats: { doc_count:  32400, storage_size:  256000 } },
];

export const demoObservabilityOverview = {
  orgId: 'xammer-demo-001',
  accountName: 'Xammer Production',
  retentionDays: 14,
  streamCount: 6,
  logStreamCount: 3,
  metricStreamCount: 2,
  traceStreamCount: 1,
  totalEvents: 1803200,
  ingestedSizeMb: 8420.0,
  compressedSizeMb: 684.0,
  indexSizeMb: 142.0,
  streams: [
    { name: 'k8s-logs',       stream_type: 'logs',    stats: { doc_count: 142600, storage_size: 2048000 } },
    { name: 'app-logs',       stream_type: 'logs',    stats: { doc_count: 284200, storage_size: 4096000 } },
    { name: 'nginx-logs',     stream_type: 'logs',    stats: { doc_count:  48000, storage_size:  512000 } },
    { name: 'node-metrics',   stream_type: 'metrics', stats: { doc_count: 864000, storage_size: 1024000 } },
    { name: 'k8s-metrics',    stream_type: 'metrics', stats: { doc_count: 432000, storage_size:  768000 } },
    { name: 'traces-default', stream_type: 'traces',  stats: { doc_count:  32400, storage_size:  256000 } },
  ],
  provisionedAt: '2026-01-10T09:00:00Z',
};

export const demoObservabilityLogs = {
  hits: [
    { _timestamp: Date.now() - 5000,  level: 'info',  message: 'Request processed successfully',  service: 'api-server',   namespace: 'production', pod_name: 'api-server-6d8f7b9c4-xk2pq',  container_name: 'api-server'   },
    { _timestamp: Date.now() - 12000, level: 'warn',  message: 'High memory usage detected: 82%', service: 'worker',       namespace: 'production', pod_name: 'worker-7c9f4d5e2-nt3vb',       container_name: 'worker'       },
    { _timestamp: Date.now() - 24000, level: 'info',  message: 'Health check passed',              service: 'frontend',     namespace: 'production', pod_name: 'frontend-8b4d5e6f-pq7rs',     container_name: 'frontend'     },
    { _timestamp: Date.now() - 38000, level: 'error', message: 'Connection timeout to RDS after 30s', service: 'api-server', namespace: 'production', pod_name: 'api-server-6d8f7b9c4-ml9wr', container_name: 'api-server' },
    { _timestamp: Date.now() - 55000, level: 'info',  message: 'Synced 1240 records from S3',     service: 'data-sync',    namespace: 'production', pod_name: 'data-sync-5c3e4f2d-yz1ab',    container_name: 'data-sync'    },
    { _timestamp: Date.now() - 72000, level: 'info',  message: 'Cache warmed: 8420 keys loaded',  service: 'api-server',   namespace: 'production', pod_name: 'api-server-6d8f7b9c4-xk2pq',  container_name: 'api-server'   },
    { _timestamp: Date.now() - 90000, level: 'warn',  message: 'Retry attempt 2/3 for job #8821',  service: 'worker',      namespace: 'production', pod_name: 'worker-7c9f4d5e2-nt3vb',       container_name: 'worker'       },
    { _timestamp: Date.now() - 110000, level: 'info', message: 'Deployment rolled out: v2.14.1',  service: 'k8s-events',   namespace: 'kube-system', pod_name: 'kube-controller-manager',     container_name: 'controller'   },
  ],
  total: 142600,
};

export const demoObservabilityHistogram = {
  hits: [
    { key: new Date(Date.now() - 55 * 60000).toISOString(), num: 842  },
    { key: new Date(Date.now() - 50 * 60000).toISOString(), num: 1240 },
    { key: new Date(Date.now() - 45 * 60000).toISOString(), num: 980  },
    { key: new Date(Date.now() - 40 * 60000).toISOString(), num: 1480 },
    { key: new Date(Date.now() - 35 * 60000).toISOString(), num: 1860 },
    { key: new Date(Date.now() - 30 * 60000).toISOString(), num: 2240 },
    { key: new Date(Date.now() - 25 * 60000).toISOString(), num: 1920 },
    { key: new Date(Date.now() - 20 * 60000).toISOString(), num: 2480 },
    { key: new Date(Date.now() - 15 * 60000).toISOString(), num: 2840 },
    { key: new Date(Date.now() - 10 * 60000).toISOString(), num: 2160 },
    { key: new Date(Date.now() -  5 * 60000).toISOString(), num: 1640 },
    { key: new Date(Date.now()             ).toISOString(), num: 820  },
  ],
};

export const demoObservabilityAlerts = [
  { name: 'high-error-rate',    stream_type: 'logs',    stream_name: 'app-logs',      is_real_time: true,  enabled: true,  severity: 'critical', description: 'Error rate > 5% for 5 minutes',    destinations: ['slack-alerts'], query_condition: { type: 'promql', promql: 'rate(errors_total[5m]) > 0.05' }, trigger_condition: { period: 5, frequency: 1, silence: 30, threshold: 0.05, operator: 'gt' } },
  { name: 'pod-crash-loop',     stream_type: 'logs',    stream_name: 'k8s-logs',      is_real_time: true,  enabled: true,  severity: 'high',     description: 'Pod restart count > 3 in 10m',     destinations: ['slack-alerts'], query_condition: { type: 'custom' },                                                      trigger_condition: { period: 10, frequency: 2, silence: 60, threshold: 3, operator: 'gt' } },
  { name: 'high-memory',        stream_type: 'metrics', stream_name: 'k8s-metrics',   is_real_time: false, enabled: true,  severity: 'warning',  description: 'Container memory > 90% of limit',  destinations: [],               query_condition: { type: 'promql', promql: 'container_memory_usage > 0.9' },         trigger_condition: { period: 5, frequency: 1, silence: 15, threshold: 0.9,  operator: 'gt' } },
];

export const demoObservabilityDestinations = [
  { name: 'slack-alerts', url: 'https://hooks.slack.com/services/demo/alert-webhook', method: 'POST', type: 'slack', skip_tls_verify: false },
];

export const demoObservabilityClusters = [
  { clusterName: 'prod-eks-cluster',    region: 'us-east-1' },
  { clusterName: 'staging-eks-cluster', region: 'us-east-1' },
];

// ─── DbOps instance timeseries (/api/dbops/aws/*/instance/*/timeseries) ───────
// Generate simple time series points for the last 24 hours
function genTs(base: number, count = 24, jitter = 0.2) {
  const now = Date.now();
  return Array.from({ length: count }, (_, i) => ({
    ts: now - (count - i) * 3600_000,
    v: +(base * (1 + (Math.random() - 0.5) * jitter)).toFixed(2),
  }));
}
export const demoDbInstanceTimeseries = {
  instanceId: 'rds-prod-001',
  region: 'us-east-1',
  cpu:                genTs(34.0),
  connections:        genTs(142),
  readIops:           genTs(820),
  writeIops:          genTs(420),
  readLatencyMs:      genTs(1.8),
  writeLatencyMs:     genTs(2.4),
  freeStorageGb:      genTs(148),
  freeableMemoryMb:   genTs(4200),
  networkReceiveMbps: genTs(12.4),
  networkTransmitMbps: genTs(8.6),
};

// ─── Workspace Licenses (/api/workspace/*) ────────────────────────────────────
export const demoWorkspaceStatus = {
  connected: true,
  domain: 'xammer.in',
  adminEmail: 'admin@xammer.in',
  totalUsers: 84,
  activeUsers: 71,
  inactiveUsers: 9,
  suspendedUsers: 4,
  lastSyncedAt: new Date(Date.now() - 3600_000).toISOString(),
};

export const demoWorkspaceLicenseSummary = {
  totalSeats: 84,
  activeSeats: 71,
  inactiveSeats: 9,
  suspendedSeats: 4,
  utilizationPercent: 84.5,
  neverLoggedIn: 2,
};

export const demoWorkspaceUsers = [
  { email: 'akshay@xammer.in',   fullName: 'Akshay Sharma',    orgUnit: '/Engineering',  status: 'ACTIVE',    lastLoginTime: new Date(Date.now() - 86400_000).toISOString(),   daysSinceLogin: 1,  isAdmin: true  },
  { email: 'priya@xammer.in',    fullName: 'Priya Nair',       orgUnit: '/Engineering',  status: 'ACTIVE',    lastLoginTime: new Date(Date.now() - 172800_000).toISOString(),  daysSinceLogin: 2,  isAdmin: false },
  { email: 'rohan@xammer.in',    fullName: 'Rohan Mehta',      orgUnit: '/Marketing',    status: 'ACTIVE',    lastLoginTime: new Date(Date.now() - 259200_000).toISOString(),  daysSinceLogin: 3,  isAdmin: false },
  { email: 'sneha@xammer.in',    fullName: 'Sneha Patel',      orgUnit: '/Operations',   status: 'INACTIVE',  lastLoginTime: new Date(Date.now() - 4320000_000).toISOString(), daysSinceLogin: 50, isAdmin: false },
  { email: 'vijay@xammer.in',    fullName: 'Vijay Kumar',      orgUnit: '/Sales',        status: 'ACTIVE',    lastLoginTime: new Date(Date.now() - 345600_000).toISOString(),  daysSinceLogin: 4,  isAdmin: false },
  { email: 'ananya@xammer.in',   fullName: 'Ananya Reddy',     orgUnit: '/Engineering',  status: 'ACTIVE',    lastLoginTime: new Date(Date.now() - 432000_000).toISOString(),  daysSinceLogin: 5,  isAdmin: false },
  { email: 'deepak@xammer.in',   fullName: 'Deepak Singh',     orgUnit: '/Finance',      status: 'SUSPENDED', lastLoginTime: null,                                              daysSinceLogin: null, isAdmin: false },
  { email: 'meera@xammer.in',    fullName: 'Meera Joshi',      orgUnit: '/Operations',   status: 'INACTIVE',  lastLoginTime: new Date(Date.now() - 5184000_000).toISOString(), daysSinceLogin: 60, isAdmin: false },
];

export const demoWorkspaceOptimization = [
  { type: 'INACTIVE_USER',            email: 'sneha@xammer.in',  fullName: 'Sneha Patel',  orgUnit: '/Operations', daysSinceLogin: 50,   recommendation: 'Deactivate or remove — no login in 50 days' },
  { type: 'INACTIVE_USER',            email: 'meera@xammer.in',  fullName: 'Meera Joshi',  orgUnit: '/Operations', daysSinceLogin: 60,   recommendation: 'Deactivate or remove — no login in 60 days' },
  { type: 'SUSPENDED_WITH_LICENSE',   email: 'deepak@xammer.in', fullName: 'Deepak Singh', orgUnit: '/Finance',    daysSinceLogin: null, recommendation: 'Remove license from suspended user to save cost' },
];

// ─── MarketOps (/api/marketops/*) ────────────────────────────────────────────
export const demoMarketOpsSubscriptions = [
  { id: 1, productCode: 'prod-abc123', productTitle: 'XamOps Cloud Management Platform', vendorName: 'Xammer Inc.', subscriptionStatus: 'ACTIVE',   expiryDate: '2027-01-01T00:00:00Z', totalCostUsd: 4800.00, syncedAt: new Date().toISOString() },
  { id: 2, productCode: 'prod-def456', productTitle: 'CloudGuard Security Suite',        vendorName: 'Check Point', subscriptionStatus: 'ACTIVE',   expiryDate: '2026-12-31T00:00:00Z', totalCostUsd: 2400.00, syncedAt: new Date().toISOString() },
  { id: 3, productCode: 'prod-ghi789', productTitle: 'Datadog Infrastructure Monitoring', vendorName: 'Datadog',    subscriptionStatus: 'INACTIVE', expiryDate: '2026-03-31T00:00:00Z', totalCostUsd:  840.00, syncedAt: new Date().toISOString() },
];

// ─── Tickets (/api/xamops/tickets/category/Technical) ────────────────────────
export const demoTickets = [
  // AWS account tickets
  { id: 3001, subject: 'EC2 instance unreachable after SG change',   description: 'Instance i-0abc123 stopped responding after security group update. Need urgent review.', category: 'Technical', service: 'Compute / VMs',     severity: 'High',   status: 'open',        accountId: 'demo-aws-001', region: 'us-east-1',    createdAt: new Date(Date.now() - 3600_000).toISOString()   },
  { id: 3002, subject: 'S3 bucket policy - cross-account access',    description: 'Need cross-account read access configured for bucket xammer-data-lake.',                   category: 'Technical', service: 'Storage',           severity: 'Normal', status: 'in_progress', accountId: 'demo-aws-001', region: 'us-east-1',    createdAt: new Date(Date.now() - 86400_000).toISOString()  },
  { id: 3003, subject: 'RDS connection pool exhausted during peak',  description: 'PostgreSQL RDS hitting max_connections during morning traffic spike (9-11am).',           category: 'Technical', service: 'Database',          severity: 'High',   status: 'open',        accountId: 'demo-aws-001', region: 'us-east-1',    createdAt: new Date(Date.now() - 172800_000).toISOString() },
  { id: 3004, subject: 'CloudWatch alarm not firing for memory',     description: 'Memory utilisation alarm configured but not triggering when threshold exceeded.',          category: 'Technical', service: 'Monitoring & Logs', severity: 'Normal', status: 'closed',      accountId: 'demo-aws-001', region: 'eu-west-1',    createdAt: new Date(Date.now() - 432000_000).toISOString() },
  { id: 3005, subject: 'IAM role missing S3 write permission',       description: 'Lambda function getting AccessDenied when writing to xammer-reports bucket.',             category: 'Technical', service: 'Security & IAM',   severity: 'High',   status: 'open',        accountId: 'demo-aws-001', region: 'us-east-1',    createdAt: new Date(Date.now() - 604800_000).toISOString() },
  // GCP account tickets
  { id: 3006, subject: 'GCE instance unresponsive after firewall rule update', description: 'VM gce-prod-analytics-01 stopped responding after VPC firewall rule change. SSH timeout on port 22.', category: 'Technical', service: 'Compute / VMs',     severity: 'High',   status: 'open',        accountId: 'demo-gcp-001', region: 'us-central1',  createdAt: new Date(Date.now() - 7200_000).toISOString()   },
  { id: 3007, subject: 'BigQuery job quota exceeded for ETL pipeline', description: 'Nightly ETL pipeline failing with QUOTA_EXCEEDED on BigQuery concurrent queries. Need quota increase.', category: 'Technical', service: 'Database',          severity: 'Normal', status: 'in_progress', accountId: 'demo-gcp-001', region: 'us-central1',  createdAt: new Date(Date.now() - 180000_000).toISOString() },
  { id: 3008, subject: 'Cloud Storage bucket ACL misconfiguration',  description: 'Storage bucket xammer-gcp-exports has public read enabled — needs immediate lock-down.',             category: 'Technical', service: 'Security & IAM',   severity: 'High',   status: 'open',        accountId: 'demo-gcp-001', region: 'europe-west1', createdAt: new Date(Date.now() - 259200_000).toISOString() },
  // Azure account tickets
  { id: 3009, subject: 'Azure VM boot failure after OS update',      description: 'VM azure-prod-app-01 stuck in ProvisioningFailed state after applying latest OS patches.', category: 'Technical', service: 'Compute / VMs',     severity: 'High',   status: 'open',        accountId: 'demo-azure-001', region: 'eastus',     createdAt: new Date(Date.now() - 14400_000).toISOString()  },
  { id: 3010, subject: 'NSG blocking inbound traffic on port 443',   description: 'Application Gateway health probe failing — NSG rule blocking 443 from Azure health check IPs.', category: 'Technical', service: 'Networking',        severity: 'Normal', status: 'in_progress', accountId: 'demo-azure-001', region: 'eastus',     createdAt: new Date(Date.now() - 345600_000).toISOString() },
];

// Single ticket returned by /api/xamops/tickets/{id}
export const demoTicketDetail = {
  ...demoTickets[0],
  creatorUsername: 'akshay',
  assignedToId: null,
  assignedToUsername: null,
  replies: [
    { id: 9001, message: 'We have received your ticket and will investigate the security group change that impacted instance i-0abc123. Our team is reviewing the change log.', authorId: 2, authorUsername: 'Support Team', admin: true,  isInternal: false, createdAt: new Date(Date.now() - 2700_000).toISOString() },
    { id: 9002, message: 'Thanks for the update. The instance is still unreachable. Can you confirm if the SG rollback will be done today?',                                    authorId: 1, authorUsername: 'akshay',       admin: false, isInternal: false, createdAt: new Date(Date.now() - 1800_000).toISOString() },
    { id: 9003, message: 'Rolled back the inbound rule on port 443 that was inadvertently removed. Instance should be reachable now — please verify.',                          authorId: 2, authorUsername: 'Support Team', admin: true,  isInternal: false, createdAt: new Date(Date.now() - 900_000).toISOString()  },
  ],
};

// ─── DbOps Slow Queries (/api/dbops/aws/*/slow-queries) ──────────────────────
export const demoDbSlowQueries = {
  logsEnabled: true,
  message: null,
  queries: [
    { instanceId: 'rds-prod-001', region: 'us-east-1', queryTimeSeconds: 12.84, lockTimeSeconds: 0.42, rowsExamined: 284200, rowsSent: 142, query: 'SELECT u.*, p.* FROM users u LEFT JOIN profiles p ON u.id = p.user_id WHERE u.status = ? AND u.created_at > ? ORDER BY u.created_at DESC LIMIT 1000', queryTimestamp: new Date(Date.now() - 1800_000).toISOString() },
    { instanceId: 'rds-prod-001', region: 'us-east-1', queryTimeSeconds: 8.21,  lockTimeSeconds: 0.12, rowsExamined: 142600, rowsSent: 42,  query: 'UPDATE orders SET status = ?, updated_at = NOW() WHERE tenant_id = ? AND status IN (?, ?) AND created_at < ?',                                                      queryTimestamp: new Date(Date.now() - 3600_000).toISOString() },
    { instanceId: 'rds-prod-001', region: 'us-east-1', queryTimeSeconds: 5.96,  lockTimeSeconds: 0.08, rowsExamined: 98400,  rowsSent: 280, query: 'SELECT a.*, b.cost FROM aws_resources a JOIN billing_entries b ON a.resource_id = b.resource_id WHERE a.account_id = ? AND b.month = ?',                            queryTimestamp: new Date(Date.now() - 5400_000).toISOString() },
    { instanceId: 'rds-prod-002', region: 'us-east-1', queryTimeSeconds: 4.13,  lockTimeSeconds: 0.22, rowsExamined: 64800,  rowsSent: 18,  query: 'DELETE FROM audit_logs WHERE created_at < ? AND tenant_id = ?',                                                                                                        queryTimestamp: new Date(Date.now() - 7200_000).toISOString() },
  ],
};

// ─── DbOps Agent Vacuum Stats (/api/dbops/aws/*/agent-vacuum-stats) ───────────
export const demoAgentVacuumStats = [
  { dsnAlias: 'rds-prod-001', dbEngine: 'postgres', dbHost: 'rds-prod-001.us-east-1.rds.amazonaws.com', schemaName: 'public', tableName: 'users',         nLiveTup: 284200, nDeadTup: 4280, deadTupRatio: 1.48, lastVacuum: new Date(Date.now() - 86400_000).toISOString(),  lastAutoVacuum: new Date(Date.now() - 43200_000).toISOString(),  lastAnalyze: new Date(Date.now() - 86400_000).toISOString(), lastAutoAnalyze: new Date(Date.now() - 43200_000).toISOString(), collectedAt: new Date().toISOString() },
  { dsnAlias: 'rds-prod-001', dbEngine: 'postgres', dbHost: 'rds-prod-001.us-east-1.rds.amazonaws.com', schemaName: 'public', tableName: 'orders',        nLiveTup: 142600, nDeadTup: 8420, deadTupRatio: 5.57, lastVacuum: new Date(Date.now() - 172800_000).toISOString(), lastAutoVacuum: new Date(Date.now() - 86400_000).toISOString(),  lastAnalyze: new Date(Date.now() - 172800_000).toISOString(), lastAutoAnalyze: new Date(Date.now() - 86400_000).toISOString(), collectedAt: new Date().toISOString() },
  { dsnAlias: 'rds-prod-001', dbEngine: 'postgres', dbHost: 'rds-prod-001.us-east-1.rds.amazonaws.com', schemaName: 'public', tableName: 'billing_entries', nLiveTup: 864000, nDeadTup: 2160, deadTupRatio: 0.25, lastVacuum: new Date(Date.now() - 259200_000).toISOString(), lastAutoVacuum: new Date(Date.now() - 129600_000).toISOString(), lastAnalyze: new Date(Date.now() - 259200_000).toISOString(), lastAutoAnalyze: new Date(Date.now() - 129600_000).toISOString(), collectedAt: new Date().toISOString() },
  { dsnAlias: 'rds-prod-001', dbEngine: 'postgres', dbHost: 'rds-prod-001.us-east-1.rds.amazonaws.com', schemaName: 'public', tableName: 'audit_logs',    nLiveTup: 432000, nDeadTup: 21600, deadTupRatio: 4.76, lastVacuum: null,                                             lastAutoVacuum: new Date(Date.now() - 172800_000).toISOString(), lastAnalyze: null,                                             lastAutoAnalyze: new Date(Date.now() - 172800_000).toISOString(), collectedAt: new Date().toISOString() },
];

// ─── DbOps Agent User Activity (/api/dbops/aws/*/agent-user-activity) ─────────
export const demoAgentUserActivity = [
  { dsnAlias: 'rds-prod-001', dbEngine: 'postgres', dbHost: 'rds-prod-001.us-east-1.rds.amazonaws.com', username: 'app_user',    activeConnections: 14, idleConnections: 8,  totalConnections: 22, callCount: 48420, totalTimeSeconds: 1840.2, collectedAt: new Date().toISOString() },
  { dsnAlias: 'rds-prod-001', dbEngine: 'postgres', dbHost: 'rds-prod-001.us-east-1.rds.amazonaws.com', username: 'read_only',   activeConnections: 6,  idleConnections: 12, totalConnections: 18, callCount: 28600, totalTimeSeconds: 420.8,  collectedAt: new Date().toISOString() },
  { dsnAlias: 'rds-prod-001', dbEngine: 'postgres', dbHost: 'rds-prod-001.us-east-1.rds.amazonaws.com', username: 'migrations',  activeConnections: 0,  idleConnections: 2,  totalConnections: 2,  callCount: 84,    totalTimeSeconds: 12.4,   collectedAt: new Date().toISOString() },
  { dsnAlias: 'rds-prod-002', dbEngine: 'mysql',    dbHost: 'rds-prod-002.us-east-1.rds.amazonaws.com', username: 'app_backend', activeConnections: 8,  idleConnections: 4,  totalConnections: 12, callCount: 18400, totalTimeSeconds: 680.6,  collectedAt: new Date().toISOString() },
];

// ─── DbOps Agent Query Stats (/api/dbops/aws/*/agent-query-stats) ────────────
export const demoAgentQueryStats = [
  { dsnAlias: 'rds-prod-001', dbEngine: 'postgres', dbHost: 'rds-prod-001.us-east-1.rds.amazonaws.com', queryDigest: 'SELECT u.*, p.* FROM users u LEFT JOIN profiles p ON u.id = p.user_id WHERE u.status = $1 AND u.created_at > $2 ORDER BY u.created_at DESC LIMIT $3', callCount: 48420, totalTimeSeconds: 1840.2, avgTimeSeconds: 0.03799, maxTimeSeconds: 12.84, rowsExamined: 284200, rowsSent: 14200, sharedBlksHit: 982400, sharedBlksRead: 14200, noIndexUsed: false, lockTimeSeconds: 0.042, collectedAt: new Date().toISOString() },
  { dsnAlias: 'rds-prod-001', dbEngine: 'postgres', dbHost: 'rds-prod-001.us-east-1.rds.amazonaws.com', queryDigest: 'UPDATE orders SET status = $1, updated_at = NOW() WHERE tenant_id = $2 AND status IN ($3, $4) AND created_at < $5', callCount: 28600, totalTimeSeconds: 960.4, avgTimeSeconds: 0.03358, maxTimeSeconds: 8.21, rowsExamined: 142600, rowsSent: 0, sharedBlksHit: 284000, sharedBlksRead: 28600, noIndexUsed: false, lockTimeSeconds: 0.128, collectedAt: new Date().toISOString() },
  { dsnAlias: 'rds-prod-001', dbEngine: 'postgres', dbHost: 'rds-prod-001.us-east-1.rds.amazonaws.com', queryDigest: 'SELECT a.*, b.cost FROM aws_resources a JOIN billing_entries b ON a.resource_id = b.resource_id WHERE a.account_id = $1 AND b.month = $2', callCount: 12840, totalTimeSeconds: 720.6, avgTimeSeconds: 0.05612, maxTimeSeconds: 5.96, rowsExamined: 98400, rowsSent: 12840, sharedBlksHit: 196800, sharedBlksRead: 2400, noIndexUsed: false, lockTimeSeconds: 0.008, collectedAt: new Date().toISOString() },
  { dsnAlias: 'rds-prod-001', dbEngine: 'postgres', dbHost: 'rds-prod-001.us-east-1.rds.amazonaws.com', queryDigest: 'DELETE FROM audit_logs WHERE created_at < $1 AND tenant_id = $2', callCount: 84, totalTimeSeconds: 48.2, avgTimeSeconds: 0.57381, maxTimeSeconds: 4.13, rowsExamined: 64800, rowsSent: 0, sharedBlksHit: 8400, sharedBlksRead: 64800, noIndexUsed: true, lockTimeSeconds: 0.220, collectedAt: new Date().toISOString() },
  { dsnAlias: 'rds-prod-001', dbEngine: 'postgres', dbHost: 'rds-prod-001.us-east-1.rds.amazonaws.com', queryDigest: 'SELECT COUNT(*), SUM(cost), AVG(cost) FROM billing_entries WHERE account_id = $1 AND month BETWEEN $2 AND $3 GROUP BY service_name', callCount: 6420, totalTimeSeconds: 284.8, avgTimeSeconds: 0.04436, maxTimeSeconds: 2.84, rowsExamined: 864000, rowsSent: 6420, sharedBlksHit: 1728000, sharedBlksRead: 0, noIndexUsed: false, lockTimeSeconds: 0.000, collectedAt: new Date().toISOString() },
  { dsnAlias: 'rds-prod-001', dbEngine: 'postgres', dbHost: 'rds-prod-001.us-east-1.rds.amazonaws.com', queryDigest: 'INSERT INTO api_request_logs (tenant_id, user_id, endpoint, method, status_code, duration_ms, created_at) VALUES ($1, $2, $3, $4, $5, $6, NOW())', callCount: 184000, totalTimeSeconds: 184.0, avgTimeSeconds: 0.00100, maxTimeSeconds: 0.48, rowsExamined: 0, rowsSent: 184000, sharedBlksHit: 184000, sharedBlksRead: 0, noIndexUsed: false, lockTimeSeconds: 0.000, collectedAt: new Date().toISOString() },
  { dsnAlias: 'rds-prod-001', dbEngine: 'postgres', dbHost: 'rds-prod-001.us-east-1.rds.amazonaws.com', queryDigest: 'SELECT r.*, t.name AS tag_name FROM cloud_resources r LEFT JOIN resource_tags t ON r.id = t.resource_id WHERE r.account_id = $1 AND r.status = $2', callCount: 3280, totalTimeSeconds: 196.8, avgTimeSeconds: 0.06000, maxTimeSeconds: 1.92, rowsExamined: 432000, rowsSent: 3280, sharedBlksHit: 42000, sharedBlksRead: 8400, noIndexUsed: true, lockTimeSeconds: 0.000, collectedAt: new Date().toISOString() },
  { dsnAlias: 'rds-prod-002', dbEngine: 'mysql',    dbHost: 'rds-prod-002.us-east-1.rds.amazonaws.com', queryDigest: 'SELECT * FROM sessions WHERE user_id = ? AND expires_at > NOW() AND revoked = ?', callCount: 18400, totalTimeSeconds: 68.0, avgTimeSeconds: 0.00370, maxTimeSeconds: 0.62, rowsExamined: 18400, rowsSent: 18400, sharedBlksHit: 36800, sharedBlksRead: 0, noIndexUsed: false, lockTimeSeconds: 0.000, collectedAt: new Date().toISOString() },
];

// ─── BillOps User Tickets (/api/billops/tickets/category/Account%20and%20Billing) ──
export const demoBillopsUserTickets = [
  // AWS account tickets
  { id: 5001, subject: 'Invoice discrepancy for March billing cycle',         description: 'Charged $2,480 but invoice shows $2,650 — please clarify the $170 difference before we process payment.',          status: 'OPEN',        severity: 'High',   category: 'Account and Billing', service: 'Billing & Invoices',  region: 'us-east-1',    accountId: 'demo-aws-001',   createdAt: new Date(Date.now() - 7200_000).toISOString(),   creatorUsername: 'akshay', hasUnreadAdminReply: true  },
  { id: 5002, subject: 'Request to upgrade support plan to Business tier',    description: 'We need SLA-backed support with 4-hour response time. Please initiate upgrade from Developer to Business plan.',   status: 'IN_PROGRESS', severity: 'Normal', category: 'Account and Billing', service: 'Account Management', region: 'us-east-1',    accountId: 'demo-aws-001',   createdAt: new Date(Date.now() - 86400_000).toISOString(),  creatorUsername: 'akshay', hasUnreadAdminReply: false },
  { id: 5003, subject: 'Add secondary billing contact for finance team',      description: 'Finance team lead priya@xammer.in should receive all invoice emails going forward. Please update billing contacts.', status: 'CLOSED',      severity: 'Low',    category: 'Account and Billing', service: 'Account Management', region: 'us-east-1',    accountId: 'demo-aws-001',   createdAt: new Date(Date.now() - 432000_000).toISOString(), creatorUsername: 'akshay', hasUnreadAdminReply: false, closedAt: new Date(Date.now() - 259200_000).toISOString() },
  // GCP account tickets
  { id: 5004, subject: 'GCP billing spike — BigQuery charges 3× higher than forecast', description: 'April BigQuery spend is $3,240 vs forecast of $1,080. Need breakdown of unexpected query charges and a cost anomaly investigation.', status: 'OPEN',        severity: 'High',   category: 'Account and Billing', service: 'Billing & Invoices',  region: 'us-central1',  accountId: 'demo-gcp-001',   createdAt: new Date(Date.now() - 10800_000).toISOString(),  creatorUsername: 'akshay', hasUnreadAdminReply: true  },
  { id: 5005, subject: 'Request GCP committed-use discount review',                     description: 'We have been running Compute Engine at 80%+ capacity for 6 months. Please advise on CUD eligibility and expected savings.', status: 'IN_PROGRESS', severity: 'Normal', category: 'Account and Billing', service: 'Account Management', region: 'us-central1',  accountId: 'demo-gcp-001',   createdAt: new Date(Date.now() - 259200_000).toISOString(), creatorUsername: 'akshay', hasUnreadAdminReply: false },
  // Azure account tickets
  { id: 5006, subject: 'Azure Reserved Instance invoice not reflecting discount', description: 'March invoice shows on-demand pricing for VM instances that should be covered by our 1-year reservation. Please reconcile.', status: 'OPEN',        severity: 'High',   category: 'Account and Billing', service: 'Billing & Invoices',  region: 'eastus',       accountId: 'demo-azure-001', createdAt: new Date(Date.now() - 18000_000).toISOString(),  creatorUsername: 'akshay', hasUnreadAdminReply: false },
];

// Single BillOps ticket detail returned by /api/billops/tickets/{id}
export const demoBillopsTicketDetail = {
  ...demoBillopsUserTickets[0],
  replies: [
    { id: 8001, message: 'Thank you for reaching out. We have reviewed your March invoice. The $170 difference is due to a prorated charge for the mid-month account upgrade completed on March 15th. A detailed breakdown has been sent to your registered billing email.', authorId: 10, authorUsername: 'Support Team', admin: true,  isInternal: false, createdAt: new Date(Date.now() - 5400_000).toISOString() },
    { id: 8002, message: 'Thank you for the explanation. Could you also send a revised invoice PDF that includes this breakdown line item? Our finance team requires it for the payment approval.',                                                                            authorId: 1,  authorUsername: 'akshay',       admin: false, isInternal: false, createdAt: new Date(Date.now() - 3600_000).toISOString() },
    { id: 8003, message: 'Absolutely — the revised invoice (INV-2026-03-R1) with the prorated charge itemised has been emailed to billing@xammer.in. You can also download it from the Billing section of your account dashboard.',                                          authorId: 10, authorUsername: 'Support Team', admin: true,  isInternal: false, createdAt: new Date(Date.now() - 1800_000).toISOString() },
  ],
};

// ─── BillOps User Dashboard (/api/billops/dashboard/user) ────────────────────
export const demoBillopsUserDashboard = {
  currentMonthSpending: 18420.75,
  lastMonthSpending: 116101.00,
  totalAccounts: 4,
  awsAccountsCount: 2,
  gcpAccountsCount: 1,
  azureAccountsCount: 1,
  pendingInvoices: 2,
  openTickets: 3,
  spendingTrend: [
    { date: '2025-11', cost: 14200, costByProvider: { AWS: 9800,  GCP: 2400, Azure: 2000 } },
    { date: '2025-12', cost: 15800, costByProvider: { AWS: 10800, GCP: 2800, Azure: 2200 } },
    { date: '2026-01', cost: 14900, costByProvider: { AWS: 10200, GCP: 2600, Azure: 2100 } },
    { date: '2026-02', cost: 16800, costByProvider: { AWS: 11400, GCP: 3100, Azure: 2300 } },
    { date: '2026-03', cost: 116101, costByProvider: { AWS: 88000, GCP: 16101, Azure: 12000 } },
    { date: '2026-04', cost: 18420, costByProvider: { AWS: 12480, GCP: 3640, Azure: 2300 } },
  ],
  spendingByProvider: { AWS: 12480, GCP: 3640, Azure: 2300 },
  accounts: [
    { id: 'demo-aws-001',   accountName: 'Xammer Production',  provider: 'AWS',   status: 'ACTIVE',   currentMonthSpending: 9840,  lastMonthSpending: 60000  },
    { id: 'demo-aws-002',   accountName: 'Xammer Staging',     provider: 'AWS',   status: 'ACTIVE',   currentMonthSpending: 2640,  lastMonthSpending: 28000  },
    { id: 'demo-gcp-001',   accountName: 'GCP Analytics',      provider: 'GCP',   status: 'ACTIVE',   currentMonthSpending: 3640,  lastMonthSpending: 16101  },
    { id: 'demo-azure-001', accountName: 'Azure Enterprise',   provider: 'Azure', status: 'ACTIVE',   currentMonthSpending: 2300,  lastMonthSpending: 12000  },
  ],
  recentInvoices: [
    { id: 'inv-2026-04-001', invoiceNumber: 'INV-2026-04-001', billingPeriod: '2026-04', amount: 18420.75, status: 'FINALIZED' },
    { id: 'inv-2026-03-001', invoiceNumber: 'INV-2026-03-001', billingPeriod: '2026-03', amount: 116101.00, status: 'PAID'      },
  ],
  recentTickets: [
    { id: '3001', subject: 'EC2 instance unreachable after SG change', category: 'Technical',        severity: 'HIGH',   status: 'OPEN'        },
    { id: '3003', subject: 'RDS connection pool exhausted during peak', category: 'Technical',        severity: 'HIGH',   status: 'OPEN'        },
    { id: '5001', subject: 'Invoice discrepancy for March billing',    category: 'Account and Billing', severity: 'MEDIUM', status: 'IN_PROGRESS' },
  ],
  lastUpdated: new Date().toISOString(),
};

// ─── Billing Summary (/api/billops/billing) ───────────────────────────────────
export const demoBillingData = {
  costHistory: [
    { date: '2025-11', cost: 14200 },
    { date: '2025-12', cost: 15800 },
    { date: '2026-01', cost: 14900 },
    { date: '2026-02', cost: 16800 },
    { date: '2026-03', cost: 116101 },
    { date: '2026-04', cost: 18420 },
  ],
  serviceBreakdown: [
    { name: 'EC2',        cost: 7840.00 },
    { name: 'RDS',        cost: 3680.50 },
    { name: 'S3',         cost: 1920.25 },
    { name: 'Lambda',     cost: 1240.00 },
    { name: 'CloudFront', cost: 840.00  },
    { name: 'GCP Compute',cost: 2180.00 },
    { name: 'Azure VMs',  cost: 720.00  },
  ],
};

// ─── Billing Detailed Breakdown (/api/billops/detailed-breakdown) ─────────────
export const demoBillingDetailedBreakdown = [
  {
    serviceName: 'EC2', totalCost: 7840.00,
    regionCosts: [
      { regionName: 'us-east-1', cost: 5200.00, resources: [
        { resourceId: 'i-0abc1001', resourceName: 'prod-api-server-01', cost: 1840.00, quantity: 720, unit: 'Hrs' },
        { resourceId: 'i-0abc1002', resourceName: 'prod-api-server-02', cost: 1680.00, quantity: 720, unit: 'Hrs' },
        { resourceId: 'i-0abc1003', resourceName: 'prod-worker-01',     cost: 1680.00, quantity: 720, unit: 'Hrs' },
      ]},
      { regionName: 'eu-west-1',  cost: 2640.00, resources: [
        { resourceId: 'i-0def2001', resourceName: 'eu-api-server-01',   cost: 1380.00, quantity: 720, unit: 'Hrs' },
        { resourceId: 'i-0def2002', resourceName: 'eu-worker-01',       cost: 1260.00, quantity: 720, unit: 'Hrs' },
      ]},
    ],
  },
  {
    serviceName: 'RDS', totalCost: 3680.50,
    regionCosts: [
      { regionName: 'us-east-1', cost: 2480.50, resources: [
        { resourceId: 'rds-prod-001', resourceName: 'rds-prod-postgres', cost: 1640.00, quantity: 720, unit: 'Hrs' },
        { resourceId: 'rds-prod-002', resourceName: 'rds-prod-mysql',    cost: 840.50,  quantity: 720, unit: 'Hrs' },
      ]},
      { regionName: 'eu-west-1',  cost: 1200.00, resources: [
        { resourceId: 'rds-staging-001', resourceName: 'rds-staging-postgres', cost: 1200.00, quantity: 720, unit: 'Hrs' },
      ]},
    ],
  },
  {
    serviceName: 'S3', totalCost: 1920.25,
    regionCosts: [
      { regionName: 'us-east-1', cost: 1920.25, resources: [
        { resourceId: 's3-xammer-data', resourceName: 'xammer-data-lake',    cost: 980.00,  quantity: 18400, unit: 'GB-Mo' },
        { resourceId: 's3-xammer-bkp',  resourceName: 'xammer-backups',      cost: 520.25,  quantity: 8200,  unit: 'GB-Mo' },
        { resourceId: 's3-xammer-logs', resourceName: 'xammer-access-logs',  cost: 420.00,  quantity: 4200,  unit: 'GB-Mo' },
      ]},
    ],
  },
  {
    serviceName: 'Lambda', totalCost: 1240.00,
    regionCosts: [
      { regionName: 'us-east-1', cost: 1240.00, resources: [
        { resourceId: 'lambda-001', resourceName: 'xammer-api-handler',   cost: 620.00, quantity: 4200000, unit: 'Invocations' },
        { resourceId: 'lambda-002', resourceName: 'xammer-data-pipeline', cost: 620.00, quantity: 2100000, unit: 'Invocations' },
      ]},
    ],
  },
];

// ─── Invoices (/api/admin/invoices) ───────────────────────────────────────────
// awsAccountId / gcpProjectId / azureSubscriptionId must match the demo account IDs
// so the useInvoices filter (which compares against selectedAccountId) can find them.
export const demoBillopsUserInvoices = [
  {
    id: 1, invoiceNumber: 'INV-2026-04-001', awsAccountId: 'demo-aws-001', gcpProjectId: 'demo-gcp-001', azureSubscriptionId: 'demo-azure-001',
    accountName: 'Xammer Production', billingPeriod: '2026-04', invoiceDate: '2026-04-30',
    amount: 18420.75, preDiscountTotal: 19368.17, discountAmount: 947.42, taxAmount: 0,
    status: 'FINALIZED',
    lineItems: [
      { id: 101, serviceName: 'EC2',     regionName: 'us-east-1', resourceName: 'prod-api-server-01', usageQuantity: 720,     unit: 'Hrs',    cost: 1840.00 },
      { id: 102, serviceName: 'EC2',     regionName: 'us-east-1', resourceName: 'prod-api-server-02', usageQuantity: 720,     unit: 'Hrs',    cost: 1680.00 },
      { id: 103, serviceName: 'RDS',     regionName: 'us-east-1', resourceName: 'rds-prod-postgres',  usageQuantity: 720,     unit: 'Hrs',    cost: 1640.00 },
      { id: 104, serviceName: 'S3',      regionName: 'us-east-1', resourceName: 'xammer-data-lake',   usageQuantity: 18400,   unit: 'GB-Mo',  cost: 980.00  },
      { id: 105, serviceName: 'Lambda',  regionName: 'us-east-1', resourceName: 'api-handler',        usageQuantity: 4200000, unit: 'Reqs',   cost: 620.00  },
    ],
    discounts: [
      { id: 201, description: 'Committed Use Discount (12-month)', serviceName: 'EC2', percentage: 5 },
    ],
  },
  {
    id: 2, invoiceNumber: 'INV-2026-03-001', awsAccountId: 'demo-aws-001', gcpProjectId: 'demo-gcp-001', azureSubscriptionId: 'demo-azure-001',
    accountName: 'Xammer Production', billingPeriod: '2026-03', invoiceDate: '2026-03-31',
    amount: 116101.00, preDiscountTotal: 116961.01, discountAmount: 860.01, taxAmount: 0,
    status: 'FINALIZED',
    lineItems: [
      { id: 111, serviceName: 'EC2',    regionName: 'us-east-1', resourceName: 'prod-api-server-01', usageQuantity: 744,     unit: 'Hrs',   cost: 1897.20 },
      { id: 112, serviceName: 'RDS',    regionName: 'us-east-1', resourceName: 'rds-prod-postgres',  usageQuantity: 744,     unit: 'Hrs',   cost: 1690.80 },
      { id: 113, serviceName: 'S3',     regionName: 'us-east-1', resourceName: 'xammer-data-lake',   usageQuantity: 17200,   unit: 'GB-Mo', cost: 912.00  },
    ],
    discounts: [
      { id: 211, description: 'Committed Use Discount (12-month)', serviceName: 'EC2', percentage: 5 },
    ],
  },
  {
    id: 3, invoiceNumber: 'INV-2026-02-001', awsAccountId: 'demo-aws-001', gcpProjectId: 'demo-gcp-001', azureSubscriptionId: 'demo-azure-001',
    accountName: 'Xammer Production', billingPeriod: '2026-02', invoiceDate: '2026-02-28',
    amount: 16800.00, preDiscountTotal: 17640.00, discountAmount: 840.00, taxAmount: 0,
    status: 'FINALIZED',
    lineItems: [
      { id: 121, serviceName: 'EC2',    regionName: 'us-east-1', resourceName: 'prod-api-server-01', usageQuantity: 672, unit: 'Hrs',   cost: 1713.60 },
      { id: 122, serviceName: 'RDS',    regionName: 'us-east-1', resourceName: 'rds-prod-postgres',  usageQuantity: 672, unit: 'Hrs',   cost: 1528.80 },
    ],
    discounts: [],
  },
];

// ─── Credits (/api/billops/credits/user/:userId) ──────────────────────────────
export const demoBillopsUserCredits = [
  { id: 1, awsAccountId: 'demo-aws-001', expectedCredits: 500,  services: 'EC2, RDS',               useCase: 'Service disruption compensation for March us-east-1 outage (3h downtime on 2026-03-18)',     priority: 'high',   userId: 1, submittedDate: '2026-04-15T10:00:00Z', status: 'approved'               },
  { id: 2, awsAccountId: 'demo-aws-001', expectedCredits: 250,  services: 'S3, CloudFront',          useCase: 'Upgrade testing environment to mirror production storage for load testing in Q2',             priority: 'medium', userId: 1, submittedDate: '2026-04-10T14:30:00Z', status: 'documentation_review'  },
  { id: 3, awsAccountId: 'demo-gcp-001', expectedCredits: 1000, services: 'GCP Compute, BigQuery',   useCase: 'Startup program eligibility — requested GCP credits to offset data pipeline migration costs', priority: 'high',   userId: 1, submittedDate: '2026-03-20T09:00:00Z', status: 'technical_verification' },
  { id: 4, awsAccountId: 'demo-aws-002', expectedCredits: 150,  services: 'Lambda, API Gateway',     useCase: 'Serverless workshop — credits to run architecture proof-of-concept in staging',               priority: 'low',    userId: 1, submittedDate: '2026-02-28T11:00:00Z', status: 'rejected'               },
];

// ─── Agreements (/api/billops/agreements/account/:accountId) ──────────────────
export const demoBillopsUserAgreements = [
  { id: 1, fileName: 'XamOps_Enterprise_MSA_2026.pdf',       status: 'FINALIZED', uploadedAt: '2026-01-03T09:00:00Z', finalizedAt: '2026-01-05T14:00:00Z', downloadUrl: '#', uploadedBy: 'admin@xammer.in',   cloudAccountId: 1 },
  { id: 2, fileName: 'AWS_Reseller_Agreement_2026.pdf',       status: 'FINALIZED', uploadedAt: '2026-01-10T10:00:00Z', finalizedAt: '2026-01-12T11:00:00Z', downloadUrl: '#', uploadedBy: 'contracts@xammer.in', cloudAccountId: 1 },
  { id: 3, fileName: 'GCP_Service_Addendum_Q1_2026.pdf',      status: 'FINALIZED', uploadedAt: '2026-02-01T08:00:00Z', finalizedAt: '2026-02-03T16:00:00Z', downloadUrl: '#', uploadedBy: 'admin@xammer.in',   cloudAccountId: 1 },
  { id: 4, fileName: 'Azure_Subscription_Amendment_Apr26.pdf',status: 'DRAFT',     uploadedAt: '2026-04-20T15:00:00Z', finalizedAt: null,                    downloadUrl: '#', uploadedBy: 'contracts@xammer.in', cloudAccountId: 1 },
];

// ─── GCP Dashboard (/api/xamops/gcp/dashboard) ───────────────────────────────
export const demoGcpDashboard = {
  accountId: 'demo-gcp-001',
  accountName: 'GCP Analytics',
  projectId: 'demo-gcp-001',
  provider: 'GCP',
  kpi: {
    mtdSpend: 7340.00,
    forecastedSpend: 9800.00,
    lastMonthSpend: 116101.00,
    potentialSavings: 1240.00,
  },
  resourceInventory: {
    gceInstances: 24,
    cloudStorageBuckets: 18,
    cloudSqlInstances: 6,
    gkeClusters: 2,
    vpcNetworks: 4,
    cloudDnsZones: 3,
    loadBalancers: 5,
    cloudNatGateways: 2,
    artifactRepositories: 8,
    kmsKeys: 12,
    cloudFunctions: 22,
    cloudBuildTriggers: 7,
    secrets: 14,
    cloudArmorPolicies: 3,
  },
  regionStatus: [
    { region: 'us-central1',   latitude: 41.8, longitude: -93.1, resourceCount: 62, status: 'active' },
    { region: 'europe-west1',  latitude: 50.4, longitude: 4.4,   resourceCount: 48, status: 'active' },
    { region: 'asia-south1',   latitude: 19.1, longitude: 72.9,  resourceCount: 38, status: 'active' },
    { region: 'us-east1',      latitude: 33.8, longitude: -81.0, resourceCount: 18, status: 'inactive' },
  ],
  costHistory: {
    monthlyCosts: [
      { date: '2025-11-01', cost: 5820, isAnomaly: false },
      { date: '2025-12-01', cost: 6340, isAnomaly: false },
      { date: '2026-01-01', cost: 6180, isAnomaly: false },
      { date: '2026-02-01', cost: 6540, isAnomaly: false },
      { date: '2026-03-01', cost: 6820, isAnomaly: false },
      { date: '2026-04-01', cost: 7340, isAnomaly: false },
    ],
    anomalies: [],
  },
  optimization: {
    summary: { totalRecommendations: 8, totalPotentialSavings: 1240.00, highConfidenceRecommendations: 4 },
    anomalies: [],
    gceRecommendations: [
      { resourceId: 'gce-prod-001', resourceName: 'gce-prod-analytics-01', resourceType: 'GCE', currentSize: 'n2-standard-8', recommendedSize: 'n2-standard-4', currentCost: 340, potentialSavings: 170, savingsPercentage: 50, zone: 'us-central1-a', reason: 'CPU utilization < 20% over 14 days', confidence: 'high' },
      { resourceId: 'gce-prod-002', resourceName: 'gce-prod-batch-01',     resourceType: 'GCE', currentSize: 'n2-standard-4', recommendedSize: 'n2-standard-2', currentCost: 170, potentialSavings: 85,  savingsPercentage: 50, zone: 'us-central1-b', reason: 'Idle outside business hours', confidence: 'medium' },
    ],
    cloudSqlRecommendations: [
      { resourceId: 'sql-prod-001', resourceName: 'cloudsql-prod-postgres', resourceType: 'CloudSQL', currentSize: 'db-n1-standard-4', recommendedSize: 'db-n1-standard-2', currentCost: 280, potentialSavings: 140, savingsPercentage: 50, zone: 'us-central1', reason: 'Memory utilization < 30%', confidence: 'high' },
    ],
    gkeRecommendations: [],
  },
  billingSummary: {
    topServices: [
      { serviceName: 'Compute Engine', cost: 2840.00, percentage: 38.7, trend: 'up' },
      { serviceName: 'BigQuery',        cost: 1620.00, percentage: 22.1, trend: 'stable' },
      { serviceName: 'Cloud SQL',       cost: 940.00,  percentage: 12.8, trend: 'up' },
      { serviceName: 'Cloud Storage',   cost: 820.00,  percentage: 11.2, trend: 'down' },
      { serviceName: 'Others',          cost: 1120.00, percentage: 15.2, trend: 'stable' },
    ],
    totalCost: 7340.00,
    serviceCount: 12,
  },
  lastUpdated: new Date().toISOString(),
  cacheStatus: 'fresh',
};

// ─── GCP FinOps (/api/xamops/gcp/finops/report) ──────────────────────────────
export const demoGcpFinOpsReport = {
  kpis: {
    monthToDateSpend: 7340.00,
    forecastedSpend: 9800.00,
    lastMonthSpend: 116101.00,
    potentialSavings: 1240.00,
  },
  costBreakdown: {
    byService: [
      { name: 'Compute Engine', amount: 2840.00 },
      { name: 'BigQuery',        amount: 1620.00 },
      { name: 'Cloud SQL',       amount: 940.00  },
      { name: 'Cloud Storage',   amount: 820.00  },
      { name: 'Cloud Functions', amount: 520.00  },
      { name: 'Others',          amount: 600.00  },
    ],
    byRegion: [
      { name: 'us-central1',  amount: 3280.00 },
      { name: 'europe-west1', amount: 2460.00 },
      { name: 'asia-south1',  amount: 1600.00 },
    ],
  },
  rightsizingRecommendations: [
    { resourceName: 'gce-prod-analytics-01', service: 'Compute Engine', location: 'us-central1-a', currentMachineType: 'n2-standard-8', recommendedMachineType: 'n2-standard-4', recommendationType: 'COST_SAVINGS', monthlySavings: 170 },
    { resourceName: 'cloudsql-prod-postgres', service: 'Cloud SQL',     location: 'us-central1',   currentMachineType: 'db-n1-standard-4', recommendedMachineType: 'db-n1-standard-2', recommendationType: 'COST_SAVINGS', monthlySavings: 140 },
    { resourceName: 'gce-prod-batch-01',      service: 'Compute Engine', location: 'us-central1-b', currentMachineType: 'n2-standard-4', recommendedMachineType: 'n2-standard-2', recommendationType: 'COST_SAVINGS', monthlySavings: 85 },
  ],
  wastedResources: [
    { resourceId: 'disk-orphan-001', resourceType: 'Persistent Disk', region: 'us-central1', reason: 'Unattached for 30+ days', monthlySavings: 48 },
    { resourceId: 'ip-unused-002',   resourceType: 'Static IP',       region: 'europe-west1', reason: 'Reserved but unassigned',  monthlySavings: 7  },
  ],
  costAnomalies: [],
  budgets: [],
};

// ─── GCP Cost Forecast (/api/xamops/gcp/forecast/cost) ───────────────────────
export const demoGcpCostForecast = Array.from({ length: 30 }, (_, i) => {
  const d = new Date(2026, 3, 30 + i);
  const base = 244 + Math.sin(i * 0.4) * 20;
  return {
    ds: d.toISOString().split('T')[0],
    yhat: parseFloat((base + (Math.random() - 0.5) * 30).toFixed(2)),
    yhat_lower: parseFloat((base - 40).toFixed(2)),
    yhat_upper: parseFloat((base + 40).toFixed(2)),
  };
});

// ─── GCP Waste (/api/xamops/gcp/optimization/waste-report) ───────────────────
export const demoGcpWasteData = [
  { resourceName: 'disk-orphan-us-001',    type: 'Persistent Disk', location: 'us-central1-a', monthlySavings: 48 },
  { resourceName: 'ip-static-eu-002',      type: 'Static IP',       location: 'europe-west1',   monthlySavings: 7  },
  { resourceName: 'gce-idle-batch-01',     type: 'Compute Engine',  location: 'us-central1-b', monthlySavings: 212 },
  { resourceName: 'snapshot-old-2025-q3',  type: 'Snapshot',        location: 'us-central1',   monthlySavings: 31 },
  { resourceName: 'nat-unused-asia-001',   type: 'Cloud NAT',       location: 'asia-south1',   monthlySavings: 18 },
];

// ─── GCP Rightsizing (/api/xamops/gcp/rightsizing/recommendations) ───────────
export const demoGcpRightsizingData = [
  { resourceName: 'gce-prod-analytics-01', service: 'Compute Engine', location: 'us-central1-a', currentMachineType: 'n2-standard-8', recommendedMachineType: 'n2-standard-4', recommendationType: 'COST_SAVINGS', monthlySavings: 170 },
  { resourceName: 'cloudsql-prod-postgres', service: 'Cloud SQL',      location: 'us-central1',   currentMachineType: 'db-n1-standard-4', recommendedMachineType: 'db-n1-standard-2', recommendationType: 'COST_SAVINGS', monthlySavings: 140 },
  { resourceName: 'gce-prod-batch-01',      service: 'Compute Engine', location: 'us-central1-b', currentMachineType: 'n2-standard-4', recommendedMachineType: 'n2-standard-2', recommendationType: 'COST_SAVINGS', monthlySavings: 85 },
  { resourceName: 'gce-staging-api-01',     service: 'Compute Engine', location: 'europe-west1-b', currentMachineType: 'n2-standard-4', recommendedMachineType: 'e2-standard-2', recommendationType: 'COST_SAVINGS', monthlySavings: 62 },
];

// ─── GCP Security (/api/xamops/gcp/security/findings|iam-drift|container-vulnerabilities)
export const demoGcpSecurityFindings = [
  { resourceName: 'gce-prod-analytics-01', category: 'NETWORK_SECURITY', description: 'Firewall rule allows ingress from 0.0.0.0/0 on port 22', severity: 'HIGH', compliance: 'CIS GCP 3.6' },
  { resourceName: 'storage-bucket-logs',   category: 'IAM',             description: 'Cloud Storage bucket is publicly accessible', severity: 'CRITICAL', compliance: 'CIS GCP 5.1' },
  { resourceName: 'cloudsql-prod-postgres',category: 'SQL',              description: 'Database instance has no authorized networks configured', severity: 'HIGH', compliance: 'CIS GCP 6.2' },
  { resourceName: 'gke-prod-cluster-01',   category: 'CONTAINER',       description: 'GKE cluster master authorized networks not enabled', severity: 'MEDIUM', compliance: 'CIS GCP 7.5' },
  { resourceName: 'iam-binding-compute',   category: 'IAM',             description: 'Service account has overly broad roles/editor binding', severity: 'HIGH', compliance: 'CIS GCP 1.5' },
  { resourceName: 'kms-key-prod-001',      category: 'ENCRYPTION',      description: 'KMS key rotation period exceeds 90 days', severity: 'MEDIUM', compliance: 'CIS GCP 1.10' },
];

export const demoGcpIamDrift = [
  { principal: 'serviceAccount:ci-pipeline@demo-gcp-001.iam.gserviceaccount.com', role: 'roles/storage.admin',   resource: 'projects/demo-gcp-001', changeType: 'ADDED',   detectedAt: new Date(Date.now() - 86400_000 * 2).toISOString() },
  { principal: 'user:dev@xammer.in',                                               role: 'roles/compute.admin',  resource: 'projects/demo-gcp-001', changeType: 'REMOVED', detectedAt: new Date(Date.now() - 86400_000 * 5).toISOString() },
];

export const demoGcpContainerVulns = [
  { imageName: 'gcr.io/demo-gcp-001/api-server:v2.1', severity: 'HIGH',   cveId: 'CVE-2024-1234', packageName: 'openssl', fixedVersion: '3.1.4', description: 'Buffer overflow in TLS handshake' },
  { imageName: 'gcr.io/demo-gcp-001/worker:v1.8',     severity: 'MEDIUM', cveId: 'CVE-2024-5678', packageName: 'libssl',  fixedVersion: '3.0.9', description: 'Memory leak in SSL context' },
  { imageName: 'gcr.io/demo-gcp-001/api-server:v2.1', severity: 'LOW',    cveId: 'CVE-2023-9012', packageName: 'curl',    fixedVersion: '8.4.0', description: 'Integer overflow in URL parsing' },
];

// ─── GCP Performance (/api/xamops/gcp/performance-insights/insights|summary) ─
export const demoGcpPerformanceInsights = [
  { id: 'pi-001', insight: 'High CPU utilization on gce-prod-analytics-01', description: 'Average CPU > 85% over last 7 days, consider scaling up', severity: 'CRITICAL', category: 'PERFORMANCE', account: 'demo-gcp-001', resourceType: 'GCE Instance', resourceId: 'gce-prod-analytics-01', recommendation: 'Upgrade to n2-standard-16 or enable autoscaling', potentialSavings: 0, region: 'us-central1-a' },
  { id: 'pi-002', insight: 'BigQuery slot utilization near limit',           description: 'Peak slot usage at 92% for the analytics dataset', severity: 'WARNING',  category: 'PERFORMANCE', account: 'demo-gcp-001', resourceType: 'BigQuery',      resourceId: 'analytics_dataset',     recommendation: 'Purchase flex slots or optimize query concurrency', potentialSavings: 0, region: 'us-central1' },
  { id: 'pi-003', insight: 'Cloud SQL connection pool exhaustion risk',      description: 'max_connections approaching limit during peak hours', severity: 'WARNING',  category: 'PERFORMANCE', account: 'demo-gcp-001', resourceType: 'Cloud SQL',     resourceId: 'cloudsql-prod-postgres', recommendation: 'Enable connection pooling via Cloud SQL Proxy', potentialSavings: 0, region: 'us-central1' },
  { id: 'pi-004', insight: 'GKE node pool not using autoscaling',           description: 'Node pool is statically sized — wastes capacity at low-load', severity: 'WEAK_WARNING', category: 'BEST_PRACTICE', account: 'demo-gcp-001', resourceType: 'GKE',    resourceId: 'gke-prod-cluster-01',   recommendation: 'Enable cluster autoscaler with min=2 max=10', potentialSavings: 280, region: 'us-central1' },
];

export const demoGcpPerformanceSummary = {
  performanceScore: 72,
  totalInsights: 4,
  critical: 1,
  warning: 2,
  weakWarning: 1,
};

// ─── GCP Alerts (/api/xamops/gcp/cloudguard/alerts) ──────────────────────────
export const demoGcpAlerts = [
  { id: 'gcp-alert-001', service: 'Compute Engine', name: 'CPU Quota — us-central1',  description: 'CPUS quota at 78% in us-central1',     status: 'WARNING',  usage: 78,  limit: 100, type: 'QUOTA',   region: 'us-central1'  },
  { id: 'gcp-alert-002', service: 'BigQuery',        name: 'BigQuery Bytes Processed', description: 'Daily bytes processed quota at 91%',    status: 'HIGH',     usage: 91,  limit: 100, type: 'QUOTA',   region: 'global'       },
  { id: 'gcp-alert-003', service: 'Cloud SQL',       name: 'Cost Anomaly — Cloud SQL', description: 'Unexpected 34% cost spike on Cloud SQL', status: 'CRITICAL', usage: 134, limit: 100, type: 'ANOMALY', region: 'us-central1'  },
  { id: 'gcp-alert-004', service: 'Cloud Storage',   name: 'Bucket Egress Warning',   description: 'Inter-region egress near budget limit',  status: 'WARNING',  usage: 68,  limit: 100, type: 'QUOTA',   region: 'europe-west1' },
  { id: 'gcp-alert-005', service: 'IAM',             name: 'Overprivileged SA',        description: 'Service account with owner role detected', status: 'HIGH',   usage: 1,   limit: 0,   type: 'SECURITY',region: 'global'       },
];

// ─── GCP Billing (/api/billops/billing/gcp/dashboard) ───────────────────────
export const demoGcpBillingDashboard = {
  totalCost: 7340.00,
  costThisMonth: 7340.00,
  costLastMonth: 6820.00,
  costHistory: [
    { month: '2025-11', date: '2025-11', amount: 5820,  cost: 5820  },
    { month: '2025-12', date: '2025-12', amount: 6340,  cost: 6340  },
    { month: '2026-01', date: '2026-01', amount: 6180,  cost: 6180  },
    { month: '2026-02', date: '2026-02', amount: 6540,  cost: 6540  },
    { month: '2026-03', date: '2026-03', amount: 6820,  cost: 6820  },
    { month: '2026-04', date: '2026-04', amount: 7340,  cost: 7340  },
  ],
  serviceBreakdown: [
    { serviceName: 'Compute Engine',   name: 'Compute Engine',   amount: 2840.00 },
    { serviceName: 'BigQuery',          name: 'BigQuery',          amount: 1620.00 },
    { serviceName: 'Cloud SQL',         name: 'Cloud SQL',         amount: 940.00  },
    { serviceName: 'Cloud Storage',     name: 'Cloud Storage',     amount: 820.00  },
    { serviceName: 'Cloud Functions',   name: 'Cloud Functions',   amount: 520.00  },
    { serviceName: 'Kubernetes Engine', name: 'Kubernetes Engine', amount: 380.00  },
    { serviceName: 'Others',            name: 'Others',            amount: 220.00  },
  ],
};

// ─── GKE Clusters (/api/xamops/gke/clusters) ─────────────────────────────────
export const demoGkeClusters = [
  { name: 'gke-prod-cluster-01',    location: 'us-central1',   region: 'us-central1',   zone: 'us-central1-a',  version: '1.28.5-gke.1217000', currentNodeVersion: '1.28.5-gke.1217000', connected: true,  status: 'RUNNING', nodeCount: 6, endpoint: '10.128.0.2', masterAuthorizedNetworksEnabled: true, loggingService: 'logging.googleapis.com/kubernetes',  monitoringService: 'monitoring.googleapis.com/kubernetes' },
  { name: 'gke-staging-cluster-01', location: 'europe-west1',  region: 'europe-west1',  zone: 'europe-west1-b', version: '1.28.3-gke.1418000', currentNodeVersion: '1.28.3-gke.1418000', connected: true,  status: 'RUNNING', nodeCount: 3, endpoint: '10.132.0.4', masterAuthorizedNetworksEnabled: false,loggingService: 'logging.googleapis.com/kubernetes',  monitoringService: 'monitoring.googleapis.com/kubernetes' },
];

// ─── GCP Disk Rightsizing (/api/gcp/disks/{accountId}) ───────────────────────
export const demoGcpDisks = [
  { diskName: 'data-disk-prod-01',      zone: 'us-central1-a', region: 'us-central1', diskType: 'pd-ssd',      sizeGb: 500, status: 'READY', attached: true,  instanceName: 'gce-prod-analytics-01', monthlyCost: 85,  usedPercent: 31, usedGb: 155, freeGb: 345, agentDeployed: true,  recommendation: 'SHRINK',             recommendedSizeGb: 200, estimatedSavings: 51, recommendationReason: 'Average usage 31% over 30 days — 200 GB is sufficient' },
  { diskName: 'data-disk-prod-02',      zone: 'us-central1-b', region: 'us-central1', diskType: 'pd-ssd',      sizeGb: 200, status: 'READY', attached: true,  instanceName: 'gce-prod-batch-01',    monthlyCost: 34,  usedPercent: 88, usedGb: 176, freeGb: 24,  agentDeployed: true,  recommendation: 'EXPAND',             recommendedSizeGb: 300, estimatedSavings: -17,recommendationReason: 'Disk usage at 88% — approaching capacity' },
  { diskName: 'orphan-snapshot-disk-01',zone: 'us-central1-a', region: 'us-central1', diskType: 'pd-standard', sizeGb: 100, status: 'READY', attached: false, instanceName: undefined,              monthlyCost: 4,   usedPercent: 0,  usedGb: 0,   freeGb: 100, agentDeployed: false, recommendation: 'DELETE_UNATTACHED',  recommendedSizeGb: 0,   estimatedSavings: 4,  recommendationReason: 'Disk unattached for 45+ days' },
  { diskName: 'boot-disk-staging-01',   zone: 'europe-west1-b',region: 'europe-west1',diskType: 'pd-balanced', sizeGb: 100, status: 'READY', attached: true,  instanceName: 'gce-staging-api-01',  monthlyCost: 10,  usedPercent: 24, usedGb: 24,  freeGb: 76,  agentDeployed: true,  recommendation: 'SHRINK',             recommendedSizeGb: 50,  estimatedSavings: 5,  recommendationReason: 'Usage consistently below 30% over 30 days' },
  { diskName: 'sql-data-disk-prod',     zone: 'us-central1-a', region: 'us-central1', diskType: 'pd-ssd',      sizeGb: 250, status: 'READY', attached: true,  instanceName: 'cloudsql-prod-postgres',monthlyCost: 42,  usedPercent: 54, usedGb: 135, freeGb: 115, agentDeployed: true,  recommendation: 'NONE',               recommendedSizeGb: 250, estimatedSavings: 0,  recommendationReason: 'Utilization optimal' },
];

// ─── GCP CUD Recommendations (/api/xamops/gcp/reservations/cuds) ─────────────
export const demoGcpCudRecommendations = [
  { recommendationId: 'gcp-cud-001', currentMachineType: 'n2-standard-4', location: 'us-central1', monthlySavings: 310, reason: 'High steady-state Compute Engine usage (>80% over 30 days)', description: 'Purchase a 1-year committed use discount for n2-standard-4 in us-central1' },
  { recommendationId: 'gcp-cud-002', currentMachineType: 'db-n1-standard-2', location: 'us-central1', monthlySavings: 140, reason: 'Cloud SQL instance runs continuously with consistent load', description: 'Purchase a 1-year CUD for Cloud SQL db-n1-standard-2' },
  { recommendationId: 'gcp-cud-003', currentMachineType: 'n2-standard-8', location: 'europe-west1', monthlySavings: 220, reason: 'Consistent high memory workload, no scaling observed', description: 'Purchase a 3-year committed use discount for n2-standard-8 in europe-west1 (55% savings)' },
];

// ─── GCP AutoSpotting (/api/gcp/autospotting/migs/all-regions) ───────────────
// Field names match what useGcpSpotAutomationData maps: name, isEnabled,
// instanceCount, spotInstanceCount, ondemandInstanceCount,
// currentHourlyCost, actualHourlySavings, potentialHourlySavings
export const demoGcpAutoSpottingMigs = [
  { name: 'prod-worker-mig', region: 'us-central1',  zone: 'us-central1-a',  isEnabled: true,  instanceCount: 4, spotInstanceCount: 3, ondemandInstanceCount: 1, currentHourlyCost: 0.84, actualHourlySavings: 0.52, potentialHourlySavings: 0.58 },
  { name: 'batch-jobs-mig',  region: 'us-central1',  zone: 'us-central1-b',  isEnabled: true,  instanceCount: 6, spotInstanceCount: 4, ondemandInstanceCount: 2, currentHourlyCost: 1.12, actualHourlySavings: 0.68, potentialHourlySavings: 0.78 },
  { name: 'staging-api-mig', region: 'europe-west1', zone: 'europe-west1-b', isEnabled: false, instanceCount: 2, spotInstanceCount: 0, ondemandInstanceCount: 2, currentHourlyCost: 0.48, actualHourlySavings: 0,    potentialHourlySavings: 0.27 },
];

// History shape: { data_points: GcpHistoryDataPoint[], summary: GcpHistorySummary }
export const demoGcpAutoSpottingHistory = {
  data_points: Array.from({ length: 14 }, (_, i) => ({
    timestamp: new Date(Date.now() - (13 - i) * 86400_000).toISOString(),
    total_actual_savings:    parseFloat((0.9 + Math.random() * 0.4).toFixed(4)),
    total_potential_savings: parseFloat((1.2 + Math.random() * 0.4).toFixed(4)),
  })),
  summary: {
    total_savings: 17.4,
    average_hourly_savings: 0.87,
    peak_savings: 1.42,
  },
};

// ─── Azure Dashboard (/api/xamops/azure/dashboard-data) ──────────────────────
export const demoAzureDashboard = {
  accountId: 'demo-azure-001',
  accountName: 'Azure Enterprise',
  provider: 'Azure',
  // Top-level fields read by fetchAzureDashboardData transform
  monthToDateSpend: 5210.30,
  forecastedSpend: 6900.00,
  lastMonthSpend: 116101.00,
  kpi: {
    mtdSpend: 5210.30,
    forecastedSpend: 6900.00,
    lastMonthSpend: 116101.00,
    potentialSavings: 880.00,
  },
  resourceInventory: {
    virtualMachines: 18,
    storageAccounts: 12,
    sqlDatabases: 8,
    virtualNetworks: 5,
    functions: 14,
    disks: 32,
    dnsZones: 3,
    loadBalancers: 4,
    containerInstances: 6,
    kubernetesServices: 2,
    appServices: 10,
    staticWebApps: 3,
  },
  regionStatus: [
    { name: 'East US',       latitude: 37.4, longitude: -78.6,  status: 'ACTIVE'      },
    { name: 'West Europe',   latitude: 52.4, longitude: 4.9,    status: 'ACTIVE'      },
    { name: 'Southeast Asia',latitude: 1.3,  longitude: 103.8,  status: 'SEMI_ACTIVE' },
    { name: 'Central India', latitude: 18.5, longitude: 73.9,   status: 'SUSTAINABLE' },
  ],
  costHistory: {
    labels: ['Nov', 'Dec', 'Jan', 'Feb', 'Mar', 'Apr'],
    costs: [4200, 4680, 4420, 4780, 4840, 5210],
    anomalies: [false, false, false, false, false, false],
  },
  optimization: {
    summary: { totalPotentialSavings: 880.00, criticalAlertsCount: 2 },
    vmRecommendations: [
      { resourceId: '/subscriptions/demo/vm-prod-001', currentType: 'Standard_D4s_v3', recommendedType: 'Standard_D2s_v3', estimatedMonthlySavings: 220, currentMonthlyCost: 440 },
    ],
    diskRecommendations: [
      { resourceId: '/subscriptions/demo/disk-001', currentType: 'Premium_LRS', recommendedType: 'Standard_LRS', estimatedMonthlySavings: 85, currentMonthlyCost: 120 },
    ],
    functionRecommendations: [],
    costAnomalies: [],
  },
  billingSummary: [
    { serviceName: 'Virtual Machines', monthToDateCost: 1840 },
    { serviceName: 'SQL Database',     monthToDateCost: 980  },
    { serviceName: 'Storage',          monthToDateCost: 620  },
    { serviceName: 'App Service',      monthToDateCost: 540  },
    { serviceName: 'Functions',        monthToDateCost: 320  },
    { serviceName: 'Others',           monthToDateCost: 910  },
  ],
  costByRegion: { 'East US': 2840, 'West Europe': 1640, 'Southeast Asia': 730 },
  optimizationSummary: { totalPotentialSavings: 880.00, criticalAlertsCount: 2 },
  vmRecommendations: [
    { resourceId: '/subscriptions/demo/vm-prod-001', currentType: 'Standard_D4s_v3', recommendedType: 'Standard_D2s_v3', estimatedMonthlySavings: 220, currentMonthlyCost: 440 },
  ],
  diskRecommendations: [
    { resourceId: '/subscriptions/demo/disk-001', currentType: 'Premium_LRS', recommendedType: 'Standard_LRS', estimatedMonthlySavings: 85, currentMonthlyCost: 120 },
  ],
  functionRecommendations: [],
  costAnomalies: [],
  lastUpdated: new Date().toISOString(),
  cacheStatus: 'fresh',
};

// ─── Azure FinOps (/api/azure/finops-report) ─────────────────────────────────
export const demoAzureFinOpsReport = {
  kpis: {
    totalSpend: 5210.30,
    forecastedSpend: 6900.00,
    yoyChange: 12.4,
    momChange: 7.7,
  },
  totalPotentialSavings: 360,
  costBreakdown: [
    { service: 'Virtual Machines', cost: 1840, change: 8.2  },
    { service: 'SQL Database',     cost: 980,  change: 3.4  },
    { service: 'Storage',          cost: 620,  change: -1.2 },
    { service: 'App Service',      cost: 540,  change: 5.8  },
    { service: 'Functions',        cost: 320,  change: 11.3 },
    { service: 'Others',           cost: 910,  change: 4.0  },
  ],
  costByRegion: [
    { region: 'East US',        cost: 2840 },
    { region: 'West Europe',    cost: 1640 },
    { region: 'Southeast Asia', cost: 730  },
  ],
  rightsizingItems: [
    { resourceName: 'vm-prod-api-01',    resourceType: 'Virtual Machine', currentSku: 'Standard_D4s_v3', targetSku: 'Standard_D2s_v3', monthlySavings: 220, annualSavings: 2640, impact: 'High',   location: 'East US'     },
    { resourceName: 'vm-staging-web-01', resourceType: 'Virtual Machine', currentSku: 'Standard_D2s_v3', targetSku: 'Standard_B2s',    monthlySavings: 85,  annualSavings: 1020, impact: 'Medium', location: 'East US'     },
    { resourceName: 'func-data-processor',resourceType: 'Function App',   currentSku: 'Premium_P1v3',    targetSku: 'Consumption',     monthlySavings: 55,  annualSavings: 660,  impact: 'Low',    location: 'West Europe' },
  ],
  wasteItems: [
    { resourceId: '/subscriptions/demo/disk-orphan-001', resourceName: 'data-disk-prod-orphan',  resourceType: 'Unattached Disk',    region: 'East US',       reason: 'Unattached for 30+ days',         monthlySavings: 52 },
    { resourceId: '/subscriptions/demo/ip-unused-001',   resourceName: 'public-ip-east-unused',  resourceType: 'Orphaned Public IP', region: 'West Europe',   reason: 'Not associated with any resource', monthlySavings: 3  },
    { resourceId: '/subscriptions/demo/vm-stopped-001',  resourceName: 'vm-dev-stopped-01',      resourceType: 'Stopped VM',         region: 'East US',       reason: 'Stopped 14+ days, incurring disk charges', monthlySavings: 134 },
  ],
  // Legacy fields for backward compatibility
  rightsizingRecommendations: [
    { resourceId: '/subscriptions/demo/vm-prod-001', service: 'Virtual Machines', currentType: 'Standard_D4s_v3', recommendedType: 'Standard_D2s_v3', estimatedMonthlySavings: 220 },
    { resourceId: '/subscriptions/demo/disk-001',    service: 'Disk Storage',     currentType: 'Premium_LRS',     recommendedType: 'Standard_LRS',     estimatedMonthlySavings: 85  },
  ],
  wastedResources: [
    { resourceId: '/subscriptions/demo/disk-orphan-001', resourceType: 'Unattached Disk', region: 'East US',     reason: 'Unattached for 30+ days', monthlySavings: 52 },
    { resourceId: '/subscriptions/demo/ip-unused-001',   resourceType: 'Orphaned Public IP', region: 'West Europe', reason: 'Not associated with any resource', monthlySavings: 3  },
  ],
  costAnomalies: [],
  budgets: [],
};

// ─── Azure Waste (/api/xamops/azure/waste) ────────────────────────────────────
export const demoAzureWasteData = [
  { resourceId: '/subscriptions/demo/disk-orphan-001', resourceName: 'data-disk-prod-orphan',  resourceType: 'Unattached Disk',    region: 'East US',       reason: 'Unattached for 30+ days',                  monthlySavings: 52  },
  { resourceId: '/subscriptions/demo/ip-unused-001',   resourceName: 'public-ip-east-unused',  resourceType: 'Orphaned Public IP', region: 'West Europe',   reason: 'Not associated with any resource',          monthlySavings: 3   },
  { resourceId: '/subscriptions/demo/vm-stopped-001',  resourceName: 'vm-dev-stopped-01',      resourceType: 'Stopped VM',         region: 'East US',       reason: 'Stopped for 14+ days, still incurring disk charges', monthlySavings: 134 },
  { resourceId: '/subscriptions/demo/nic-unused-001',  resourceName: 'nic-detached-staging',   resourceType: 'Unattached NIC',     region: 'Southeast Asia', reason: 'NIC not attached to any VM',               monthlySavings: 1   },
];

// ─── Azure Rightsizing (/api/xamops/azure/rightsizing/recommendations) ────────
export const demoAzureRightsizingData = {
  status: 'OK',
  recommendations: [
    { recommendationId: 'az-rs-001', resourceId: '/subscriptions/demo/vm-prod-001',    resourceName: 'vm-prod-api-01',    resourceType: 'Virtual Machine', category: 'Cost Savings',    impact: 'High',   currentSku: 'Standard_D4s_v3', targetSku: 'Standard_D2s_v3', monthlySavings: 220, annualSavings: 2640, currency: 'USD', problem: 'CPU P95 < 15%', solution: 'Downsize VM', maxCpuP95: '14.2', maxMemoryP95: '42.0', location: 'East US'       },
    { recommendationId: 'az-rs-002', resourceId: '/subscriptions/demo/vm-staging-001', resourceName: 'vm-staging-web-01', resourceType: 'Virtual Machine', category: 'Cost Savings',    impact: 'Medium', currentSku: 'Standard_D2s_v3', targetSku: 'Standard_B2s',    monthlySavings: 85,  annualSavings: 1020, currency: 'USD', problem: 'Low utilization outside business hours', solution: 'Switch to burstable tier', maxCpuP95: '22.8', maxMemoryP95: '38.6', location: 'East US'       },
    { recommendationId: 'az-rs-003', resourceId: '/subscriptions/demo/func-001',       resourceName: 'func-data-processor', resourceType: 'Function App',  category: 'Best Practice',   impact: 'Low',    currentSku: 'Premium_P1v3',    targetSku: 'Consumption',     monthlySavings: 62,  annualSavings: 744,  currency: 'USD', problem: 'Function runs infrequently',             solution: 'Switch to Consumption plan', maxCpuP95: '8.0', maxMemoryP95: '24.0',  location: 'West Europe'   },
  ],
};

// ─── Azure Reservations (/api/xamops/azure/reservations) ─────────────────────
export const demoAzureReservations = {
  reservations: [
    { reservationId: 'az-ri-001', displayName: 'VM Reserved — D4s v3 East US',   sku: 'Standard_D4s_v3', quantity: 2, state: 'Succeeded', term: 'P1Y', billingPlan: 'Upfront', expiryDate: '2027-01-01', location: 'eastus',     scope: 'Single subscription', utilizationPercentage: 88 },
    { reservationId: 'az-ri-002', displayName: 'SQL DB Reserved — GP Gen5 2 vCores', sku: 'GP_Gen5_2', quantity: 1, state: 'Succeeded', term: 'P3Y', billingPlan: 'Upfront', expiryDate: '2029-01-01', location: 'eastus',     scope: 'Single subscription', utilizationPercentage: 95 },
    { reservationId: 'az-ri-003', displayName: 'App Service Reserved — P2v3',    sku: 'P2v3',            quantity: 1, state: 'Succeeded', term: 'P1Y', billingPlan: 'Monthly', expiryDate: '2027-03-15', location: 'westeurope', scope: 'Single subscription', utilizationPercentage: 72 },
  ],
  savingsPlans: [
    { savingsPlanId: 'az-sp-001', displayName: 'Compute Savings Plan — $200/hr', commitment: '$200.00/hour', paymentOption: 'Monthly', term: 'P3Y', state: 'Active', startDate: '2026-01-01', expiryDate: '2029-01-01', appliedScope: 'All subscriptions' },
  ],
  metrics: { totalReservations: 3, activeReservations: 3, totalSavingsPlans: 1, activeSavingsPlans: 1 },
  recommendations: [
    { serviceName: 'Virtual Machines', currentMonthlyCost: 1840, estimatedSavingsPercentage: 40, estimatedMonthlySavings: 736, recommendedTerm: '1 year', reason: 'High steady-state usage (> 80% utilization)' },
  ],
};

// ─── Azure Security (/api/xamops/azure/security) ──────────────────────────────
export const demoAzureSecurityData = {
  status: 'OK',
  secureScore: 68,
  maxScore: 100,
  alerts: [
    { alertId: 'az-sec-001', alertName: 'VM brute force detected',      severity: 'High',   status: 'Active',   resourceName: 'vm-prod-api-01',     description: 'Multiple failed SSH login attempts detected',              timeGenerated: new Date(Date.now() - 3600_000).toISOString()     },
    { alertId: 'az-sec-002', alertName: 'Suspicious IP communication',  severity: 'Medium', status: 'Active',   resourceName: 'vm-staging-web-01',  description: 'Outbound traffic to known malicious IP 45.227.255.0',      timeGenerated: new Date(Date.now() - 7200_000).toISOString()     },
    { alertId: 'az-sec-003', alertName: 'Storage account public access',severity: 'Medium', status: 'Dismissed',resourceName: 'storageprod001',      description: 'Storage account allows public blob access',                timeGenerated: new Date(Date.now() - 86400_000).toISOString()    },
  ],
  assessments: [
    { assessmentId: 'az-asm-001', displayName: 'MFA not enabled for subscription owners',       severity: 'High',   statusCode: 'Unhealthy', resourceId: '/subscriptions/demo',                    resourceName: 'Azure Enterprise',  category: 'Identity',  remediation: 'Enable MFA for all privileged accounts', complianceControl: 'SOC2 CC6.1' },
    { assessmentId: 'az-asm-002', displayName: 'SQL servers should use customer-managed keys',  severity: 'Medium', statusCode: 'Unhealthy', resourceId: '/subscriptions/demo/sql-prod-001',        resourceName: 'sql-prod-postgres', category: 'Data',      remediation: 'Configure CMK for SQL server encryption' },
    { assessmentId: 'az-asm-003', displayName: 'Endpoint protection should be installed on VMs',severity: 'High',  statusCode: 'Unhealthy', resourceId: '/subscriptions/demo/vm-staging-web-01',   resourceName: 'vm-staging-web-01', category: 'Compute',   remediation: 'Install Microsoft Defender for Endpoint' },
  ],
};

// ─── Azure Performance (/api/xamops/azure/performance) ───────────────────────
export const demoAzurePerformanceData = {
  status: 'OK',
  performanceScore: 74,
  insights: [
    { insightId: 'az-pi-001', resourceName: 'vm-prod-api-01',    resourceType: 'Virtual Machine', region: 'East US',       severity: 'High',   description: 'CPU utilization consistently above 85%',            recommendation: 'Scale up to Standard_D8s_v3 or enable VMSS autoscaling' },
    { insightId: 'az-pi-002', resourceName: 'sql-prod-postgres',  resourceType: 'SQL Database',    region: 'East US',       severity: 'Medium', description: 'DTU consumption near limit during business hours',  recommendation: 'Upgrade to vCore model or enable elastic pool' },
    { insightId: 'az-pi-003', resourceName: 'func-data-processor',resourceType: 'Function App',    region: 'West Europe',   severity: 'Low',    description: 'Cold start latency > 3s on first invocation',       recommendation: 'Enable Always On or use Premium plan' },
    { insightId: 'az-pi-004', resourceName: 'storageprod001',     resourceType: 'Storage Account', region: 'Southeast Asia',severity: 'Medium', description: 'Throttling detected on blob operations',            recommendation: 'Increase request rate limits or use geo-redundant storage' },
  ],
};

// ─── Azure Alerts (/api/xamops/azure/alerts) ──────────────────────────────────
export const demoAzureAlertsData = {
  status: 'OK',
  alerts: [
    { alertId: 'az-alert-001', alertName: 'High CPU — vm-prod-api-01',      description: 'CPU > 90% for 15 minutes', severity: 'Sev1', severityLabel: 'Critical',      alertState: 'New',          monitorCondition: 'Fired',    signalType: 'Metric',      resourceName: 'vm-prod-api-01',     resourceGroup: 'rg-production', resourceType: 'Microsoft.Compute/virtualMachines', startDateTime: new Date(Date.now() - 1800_000).toISOString()  },
    { alertId: 'az-alert-002', alertName: 'SQL DTU Limit Warning',           description: 'DTU usage at 88%',         severity: 'Sev2', severityLabel: 'Error',         alertState: 'Acknowledged', monitorCondition: 'Fired',    signalType: 'Metric',      resourceName: 'sql-prod-postgres',  resourceGroup: 'rg-production', resourceType: 'Microsoft.Sql/servers/databases',   startDateTime: new Date(Date.now() - 3600_000).toISOString()  },
    { alertId: 'az-alert-003', alertName: 'Storage Throttling Detected',     description: 'Blob operations throttled',severity: 'Sev2', severityLabel: 'Error',         alertState: 'New',          monitorCondition: 'Fired',    signalType: 'Metric',      resourceName: 'storageprod001',     resourceGroup: 'rg-data',       resourceType: 'Microsoft.Storage/storageAccounts', startDateTime: new Date(Date.now() - 7200_000).toISOString()  },
    { alertId: 'az-alert-004', alertName: 'App Service Response Time Spike', description: 'P95 latency > 2s',         severity: 'Sev3', severityLabel: 'Warning',       alertState: 'New',          monitorCondition: 'Fired',    signalType: 'Metric',      resourceName: 'app-service-prod',   resourceGroup: 'rg-production', resourceType: 'Microsoft.Web/sites',               startDateTime: new Date(Date.now() - 900_000).toISOString()   },
    { alertId: 'az-alert-005', alertName: 'AKS Node CPU Alert',              description: 'Node pool CPU > 80%',      severity: 'Sev3', severityLabel: 'Warning',       alertState: 'Resolved',     monitorCondition: 'Resolved', signalType: 'Metric',      resourceName: 'aks-prod-cluster',   resourceGroup: 'rg-production', resourceType: 'Microsoft.ContainerService/managedClusters', startDateTime: new Date(Date.now() - 86400_000).toISOString() },
  ],
};

// ─── Azure AutoSpotting (/api/azure/autospotting/vmss/all-regions) ────────────
// Field names must match what useAzureSpotAutomationData reads exactly
export const demoAzureAutoSpottingVmss = [
  { name: 'prod-worker-vmss',  resourceGroup: 'rg-production', region: 'East US',     isEnabled: true,  aksManaged: false, instanceCount: 4, spotInstanceCount: 3, ondemandInstanceCount: 1, vmSku: 'Standard_D4s_v3', currentHourlyCost: 0.52, actualHourlySavings: 0.31, potentialHourlySavings: 0.42 },
  { name: 'batch-jobs-vmss',   resourceGroup: 'rg-production', region: 'East US',     isEnabled: true,  aksManaged: false, instanceCount: 6, spotInstanceCount: 5, ondemandInstanceCount: 1, vmSku: 'Standard_D8s_v3', currentHourlyCost: 0.71, actualHourlySavings: 0.48, potentialHourlySavings: 0.58 },
  { name: 'staging-api-vmss',  resourceGroup: 'rg-staging',    region: 'West Europe', isEnabled: false, aksManaged: false, instanceCount: 2, spotInstanceCount: 0, ondemandInstanceCount: 2, vmSku: 'Standard_D2s_v3', currentHourlyCost: 0.22, actualHourlySavings: 0.00, potentialHourlySavings: 0.13 },
];

// Field names must match what useAzureSpotAutomationData.fetchHistoryData reads: data.data_points, data.summary
export const demoAzureAutoSpottingHistory = {
  data_points: [
    { timestamp: new Date(Date.now() - 6 * 86400_000).toISOString(), total_actual_savings: 0.72, total_potential_savings: 1.10 },
    { timestamp: new Date(Date.now() - 5 * 86400_000).toISOString(), total_actual_savings: 0.80, total_potential_savings: 1.18 },
    { timestamp: new Date(Date.now() - 4 * 86400_000).toISOString(), total_actual_savings: 0.75, total_potential_savings: 1.14 },
    { timestamp: new Date(Date.now() - 3 * 86400_000).toISOString(), total_actual_savings: 0.83, total_potential_savings: 1.22 },
    { timestamp: new Date(Date.now() - 2 * 86400_000).toISOString(), total_actual_savings: 0.91, total_potential_savings: 1.35 },
    { timestamp: new Date(Date.now() - 1 * 86400_000).toISOString(), total_actual_savings: 0.87, total_potential_savings: 1.30 },
    { timestamp: new Date().toISOString(),                            total_actual_savings: 0.94, total_potential_savings: 1.42 },
  ],
  summary: { total_savings: 17.4, average_hourly_savings: 0.87, peak_savings: 1.42 },
};

// ─── AKS Clusters (/api/xamops/aks/clusters) ─────────────────────────────────
export const demoAksClusters = [
  { name: 'aks-prod-cluster',    resourceGroup: 'rg-production', location: 'eastus',       kubernetesVersion: '1.28.5', provisioningState: 'Succeeded', nodeCount: 5, connected: true  },
  { name: 'aks-staging-cluster', resourceGroup: 'rg-staging',    location: 'westeurope',   kubernetesVersion: '1.27.9', provisioningState: 'Succeeded', nodeCount: 2, connected: true  },
  { name: 'aks-data-cluster',    resourceGroup: 'rg-data',       location: 'centralindia', kubernetesVersion: '1.27.9', provisioningState: 'Succeeded', nodeCount: 3, connected: false },
];

// ─── Observability Metrics (/api/observability/metrics/query) ─────────────────
// PromQL matrix format expected by useObservabilityData fetchMetrics()
function genMetricValues(base: number, count = 24, jitter = 0.15): [number, string][] {
  const now = Math.floor(Date.now() / 1000);
  return Array.from({ length: count }, (_, i) => [
    now - (count - i) * 300,
    (base * (1 + (Math.random() - 0.5) * jitter)).toFixed(2),
  ]);
}
export const demoObservabilityMetrics = {
  data: {
    resultType: 'matrix',
    result: [
      { metric: { host: 'prod-server-01', job: 'node-exporter' }, values: genMetricValues(62.4) },
      { metric: { host: 'prod-server-02', job: 'node-exporter' }, values: genMetricValues(48.8) },
      { metric: { host: 'staging-server', job: 'node-exporter' }, values: genMetricValues(31.2) },
    ],
  },
};

// ─── GKE Cluster Dashboard (/api/xamops/gke/{name}/dashboard) ─────────────────
export const demoGkeDashboard = {
  clusterName: 'gke-prod-cluster-01',
  location: 'us-central1',
  status: 'RUNNING',
  version: '1.28.5-gke.1217000',
  endpoint: '34.122.45.67',
  connected: true,
  lastSyncedAt: new Date().toISOString(),
  overview: {
    totalNodes: 6,
    totalPods: 48,
    totalNamespaces: 8,
    cpuUsageMillicores: 14200,
    cpuCapacityMillicores: 24000,
    cpuUsagePercent: 59.2,
    memoryUsageBytes: 52_428_800_000,
    memoryCapacityBytes: 103_079_510_016,
    memoryUsagePercent: 50.9,
  },
  nodePools: [
    { name: 'default-pool', machineType: 'n2-standard-4', nodeCount: 3, version: '1.28.5-gke.1217000', autoscalingEnabled: true, minNodes: 2, maxNodes: 6, status: 'RUNNING', diskSizeGb: '100', imageType: 'COS_CONTAINERD' },
    { name: 'batch-pool',   machineType: 'n2-standard-8', nodeCount: 3, version: '1.28.5-gke.1217000', autoscalingEnabled: true, minNodes: 1, maxNodes: 5, status: 'RUNNING', diskSizeGb: '200', imageType: 'COS_CONTAINERD' },
  ],
  workloadHealth: {
    clusterHealthScore: 92,
    pods: [
      { name: 'api-server-7d9f4b-xk2p', namespace: 'production', phase: 'Running', nodeName: 'gke-prod-node-001', restartCount: 0, cpuUsage: 420, memoryUsage: 512, cpuRequest: 500, memoryRequest: 512, age: '14d', podIp: '10.0.1.4', ownerKind: 'Deployment', ownerName: 'api-server' },
      { name: 'api-server-7d9f4b-mn3q', namespace: 'production', phase: 'Running', nodeName: 'gke-prod-node-002', restartCount: 1, cpuUsage: 380, memoryUsage: 490, cpuRequest: 500, memoryRequest: 512, age: '14d', podIp: '10.0.1.5', ownerKind: 'Deployment', ownerName: 'api-server' },
      { name: 'worker-6c8d5f-pq7r',     namespace: 'production', phase: 'Running', nodeName: 'gke-prod-node-003', restartCount: 0, cpuUsage: 1840, memoryUsage: 2048, cpuRequest: 2000, memoryRequest: 2048, age: '8d', podIp: '10.0.1.6', ownerKind: 'Deployment', ownerName: 'worker' },
      { name: 'postgres-0',             namespace: 'databases',  phase: 'Running', nodeName: 'gke-prod-node-004', restartCount: 0, cpuUsage: 620, memoryUsage: 1536, cpuRequest: 1000, memoryRequest: 2048, age: '30d', podIp: '10.0.2.1', ownerKind: 'StatefulSet', ownerName: 'postgres' },
      { name: 'redis-0',                namespace: 'databases',  phase: 'Running', nodeName: 'gke-prod-node-005', restartCount: 0, cpuUsage: 80,  memoryUsage: 256,  cpuRequest: 100,  memoryRequest: 256, age: '30d', podIp: '10.0.2.2', ownerKind: 'StatefulSet', ownerName: 'redis' },
      { name: 'nginx-ingress-abc12',    namespace: 'ingress',    phase: 'Running', nodeName: 'gke-prod-node-001', restartCount: 0, cpuUsage: 120, memoryUsage: 128,  cpuRequest: 200,  memoryRequest: 256, age: '45d', podIp: '10.0.3.1', ownerKind: 'DaemonSet',  ownerName: 'nginx-ingress' },
      { name: 'batch-job-etl-9xk2m',   namespace: 'batch',      phase: 'Running', nodeName: 'gke-prod-node-006', restartCount: 0, cpuUsage: 3200, memoryUsage: 4096, cpuRequest: 4000, memoryRequest: 4096, age: '2d', podIp: '10.0.4.1', ownerKind: 'Job', ownerName: 'batch-etl' },
      { name: 'monitoring-agent-n1',    namespace: 'monitoring', phase: 'Running', nodeName: 'gke-prod-node-002', restartCount: 0, cpuUsage: 60,  memoryUsage: 192,  cpuRequest: 100,  memoryRequest: 256, age: '60d', podIp: '10.0.5.1', ownerKind: 'DaemonSet',  ownerName: 'monitoring-agent' },
    ],
    deployments: [
      { name: 'api-server', namespace: 'production', replicas: 2, availableReplicas: 2, readyReplicas: 2 },
      { name: 'worker',     namespace: 'production', replicas: 1, availableReplicas: 1, readyReplicas: 1 },
      { name: 'frontend',   namespace: 'production', replicas: 2, availableReplicas: 2, readyReplicas: 2 },
    ],
    statefulsets: [
      { name: 'postgres', namespace: 'databases', replicas: 1, readyReplicas: 1, kind: 'StatefulSet' },
      { name: 'redis',    namespace: 'databases', replicas: 1, readyReplicas: 1, kind: 'StatefulSet' },
    ],
    daemonsets: [
      { name: 'nginx-ingress',    namespace: 'ingress',    replicas: 6, readyReplicas: 6, kind: 'DaemonSet' },
      { name: 'monitoring-agent', namespace: 'monitoring', replicas: 6, readyReplicas: 6, kind: 'DaemonSet' },
    ],
  },
  costMetrics: {
    dailyCost: 48.20,
    namespaceCosts: [
      { namespace: 'production', cpuUsageMillicores: 8400, memoryUsageMb: 6144, podCount: 5, dailyCost: 28.40, monthlyCost: 852.00 },
      { namespace: 'databases',  cpuUsageMillicores: 2800, memoryUsageMb: 4096, podCount: 2, dailyCost: 12.60, monthlyCost: 378.00 },
      { namespace: 'batch',      cpuUsageMillicores: 2400, memoryUsageMb: 4096, podCount: 1, dailyCost: 5.80,  monthlyCost: 174.00 },
      { namespace: 'ingress',    cpuUsageMillicores: 600,  memoryUsageMb: 768,  podCount: 6, dailyCost: 1.40,  monthlyCost: 42.00  },
    ],
    workloadCosts: [
      { name: 'worker',     namespace: 'production', kind: 'Deployment', cpuUsageMillicores: 3200, memoryUsageMb: 4096, podCount: 1, dailyCost: 18.20, monthlyCost: 546.00 },
      { name: 'postgres',   namespace: 'databases',  kind: 'StatefulSet', cpuUsageMillicores: 2200, memoryUsageMb: 3072, podCount: 1, dailyCost: 10.80, monthlyCost: 324.00 },
      { name: 'api-server', namespace: 'production', kind: 'Deployment', cpuUsageMillicores: 2800, memoryUsageMb: 2048, podCount: 2, dailyCost: 8.40,  monthlyCost: 252.00 },
      { name: 'batch-etl',  namespace: 'batch',      kind: 'Job',        cpuUsageMillicores: 3200, memoryUsageMb: 4096, podCount: 1, dailyCost: 5.80,  monthlyCost: 174.00 },
    ],
  },
  resourceUtilization: {
    nodes: [
      { nodeName: 'gke-prod-node-001', cpuUsagePercent: 62.4, memoryUsagePercent: 58.2, cpuUsageMillicores: 2496, cpuCapacityMillicores: 4000, cpuRequestsPercent: 70.0, memoryRequestsPercent: 65.0 },
      { nodeName: 'gke-prod-node-002', cpuUsagePercent: 54.8, memoryUsagePercent: 48.6, cpuUsageMillicores: 2192, cpuCapacityMillicores: 4000, cpuRequestsPercent: 60.0, memoryRequestsPercent: 55.0 },
      { nodeName: 'gke-prod-node-003', cpuUsagePercent: 78.2, memoryUsagePercent: 72.4, cpuUsageMillicores: 3128, cpuCapacityMillicores: 4000, cpuRequestsPercent: 85.0, memoryRequestsPercent: 80.0 },
      { nodeName: 'gke-prod-node-004', cpuUsagePercent: 42.1, memoryUsagePercent: 62.8, cpuUsageMillicores: 3368, cpuCapacityMillicores: 8000, cpuRequestsPercent: 50.0, memoryRequestsPercent: 70.0 },
      { nodeName: 'gke-prod-node-005', cpuUsagePercent: 38.4, memoryUsagePercent: 44.2, cpuUsageMillicores: 3072, cpuCapacityMillicores: 8000, cpuRequestsPercent: 45.0, memoryRequestsPercent: 50.0 },
      { nodeName: 'gke-prod-node-006', cpuUsagePercent: 84.6, memoryUsagePercent: 78.1, cpuUsageMillicores: 6768, cpuCapacityMillicores: 8000, cpuRequestsPercent: 90.0, memoryRequestsPercent: 85.0 },
    ],
  },
  nodeMetrics: {
    nodes: [
      { name: 'gke-prod-node-001', ready: true, diskPressure: false, memoryPressure: false, instanceType: 'n2-standard-4', zone: 'us-central1-a', internalIp: '10.128.0.1', kubeletVersion: 'v1.28.5', nodePool: 'default-pool', cpuCapacity: 4000, memoryCapacity: 15360, cpuUsage: 62, cpuUsageMillicores: 2496, memoryUsage: 58, podCount: 14 },
      { name: 'gke-prod-node-002', ready: true, diskPressure: false, memoryPressure: false, instanceType: 'n2-standard-4', zone: 'us-central1-b', internalIp: '10.128.0.2', kubeletVersion: 'v1.28.5', nodePool: 'default-pool', cpuCapacity: 4000, memoryCapacity: 15360, cpuUsage: 55, cpuUsageMillicores: 2192, memoryUsage: 49, podCount: 12 },
      { name: 'gke-prod-node-003', ready: true, diskPressure: false, memoryPressure: false, instanceType: 'n2-standard-4', zone: 'us-central1-c', internalIp: '10.128.0.3', kubeletVersion: 'v1.28.5', nodePool: 'default-pool', cpuCapacity: 4000, memoryCapacity: 15360, cpuUsage: 78, cpuUsageMillicores: 3128, memoryUsage: 72, podCount: 10 },
      { name: 'gke-prod-node-004', ready: true, diskPressure: false, memoryPressure: false, instanceType: 'n2-standard-8', zone: 'us-central1-a', internalIp: '10.128.0.4', kubeletVersion: 'v1.28.5', nodePool: 'batch-pool',   cpuCapacity: 8000, memoryCapacity: 30720, cpuUsage: 42, cpuUsageMillicores: 3368, memoryUsage: 63, podCount: 6  },
      { name: 'gke-prod-node-005', ready: true, diskPressure: false, memoryPressure: false, instanceType: 'n2-standard-8', zone: 'us-central1-b', internalIp: '10.128.0.5', kubeletVersion: 'v1.28.5', nodePool: 'batch-pool',   cpuCapacity: 8000, memoryCapacity: 30720, cpuUsage: 38, cpuUsageMillicores: 3072, memoryUsage: 44, podCount: 4  },
      { name: 'gke-prod-node-006', ready: true, diskPressure: false, memoryPressure: true,  instanceType: 'n2-standard-8', zone: 'us-central1-c', internalIp: '10.128.0.6', kubeletVersion: 'v1.28.5', nodePool: 'batch-pool',   cpuCapacity: 8000, memoryCapacity: 30720, cpuUsage: 85, cpuUsageMillicores: 6768, memoryUsage: 78, podCount: 2  },
    ],
  },
  containerMetrics: {
    containers: [
      { name: 'api-server',      pod: 'api-server-7d9f4b-xk2p', namespace: 'production', image: 'gcr.io/xammer/api-server:v2.4.1',  state: 'running', ready: true,  restartCount: 0, cpuUsage: 420,  memoryUsage: 512,  fsUsage: 2.1 },
      { name: 'api-server',      pod: 'api-server-7d9f4b-mn3q', namespace: 'production', image: 'gcr.io/xammer/api-server:v2.4.1',  state: 'running', ready: true,  restartCount: 1, cpuUsage: 380,  memoryUsage: 490,  fsUsage: 2.0 },
      { name: 'worker',          pod: 'worker-6c8d5f-pq7r',     namespace: 'production', image: 'gcr.io/xammer/worker:v1.8.3',     state: 'running', ready: true,  restartCount: 0, cpuUsage: 1840, memoryUsage: 2048, fsUsage: 5.4 },
      { name: 'postgres',        pod: 'postgres-0',             namespace: 'databases',  image: 'postgres:15.2',                   state: 'running', ready: true,  restartCount: 0, cpuUsage: 620,  memoryUsage: 1536, fsUsage: 45.2 },
      { name: 'redis',           pod: 'redis-0',                namespace: 'databases',  image: 'redis:7.2-alpine',               state: 'running', ready: true,  restartCount: 0, cpuUsage: 80,   memoryUsage: 256,  fsUsage: 0.8 },
      { name: 'nginx-ingress',   pod: 'nginx-ingress-abc12',    namespace: 'ingress',    image: 'k8s.gcr.io/ingress-nginx:v1.8.1', state: 'running', ready: true,  restartCount: 0, cpuUsage: 120,  memoryUsage: 128,  fsUsage: 0.2 },
      { name: 'etl-processor',   pod: 'batch-job-etl-9xk2m',   namespace: 'batch',      image: 'gcr.io/xammer/etl:v3.1.0',       state: 'running', ready: true,  restartCount: 0, cpuUsage: 3200, memoryUsage: 4096, fsUsage: 12.6 },
      { name: 'monitoring-agent',pod: 'monitoring-agent-n1',    namespace: 'monitoring', image: 'gcr.io/google-containers/prometheus-to-sd:v0.11.3', state: 'running', ready: true, restartCount: 0, cpuUsage: 60, memoryUsage: 192, fsUsage: 0.4 },
    ],
  },
  securityMetrics: {
    totalEvents: 3,
    eventsByPriority: { WARNING: 2, NOTICE: 1 },
    alerts: [
      { rule: 'Terminal shell in container', priority: 'WARNING', count: 2, lastSeen: new Date(Date.now() - 7200_000).toISOString(), namespace: 'production' },
      { rule: 'Sensitive file opened',       priority: 'NOTICE',  count: 1, lastSeen: new Date(Date.now() - 14400_000).toISOString(), namespace: 'databases' },
    ],
  },
};

// ─── GCP/Azure Prowler Compliance (/api/xamops/gcp/security/prowler/*) ────────
// The ComplianceOps page calls prowler/status and prowler/findings (not /security/findings).
// findings shape: { status_code, severity, finding_info: {title, desc}, resources: [{uid, region}], unmapped: { compliance: { 'CIS': ['1.2'], 'SOC2': ['CC6.1'] } } }
export const demoGcpProwlerStatus = { status: 'COMPLETED', findingsCount: 24 };
export const demoGcpProwlerFindings = [
  { status_code: 'FAIL', severity: 'critical', finding_info: { title: 'GCE instances should not use default service account', desc: 'Default service account with editor role attached to 3 GCE instances grants excessive permissions.' }, resources: [{ uid: 'gce-prod-analytics-01', region: 'us-central1' }], unmapped: { compliance: { CIS: ['4.1', '4.2'], SOC2: ['CC6.1', 'CC6.3'], 'PCI-DSS': ['7.1.2'] } } },
  { status_code: 'FAIL', severity: 'high',     finding_info: { title: 'Cloud Storage buckets should not be publicly accessible', desc: 'Bucket xammer-gcp-exports has allUsers read permission — data exposure risk.' },                    resources: [{ uid: 'xammer-gcp-exports', region: 'us-central1' }],   unmapped: { compliance: { CIS: ['5.1'], SOC2: ['CC6.1', 'CC7.2'], 'PCI-DSS': ['1.3.2'], HIPAA: ['164.312(a)(1)'] } } },
  { status_code: 'FAIL', severity: 'high',     finding_info: { title: 'Cloud SQL instances should have SSL enforcement enabled', desc: 'CloudSQL instance cloudsql-prod-postgres does not enforce SSL connections.' },                        resources: [{ uid: 'cloudsql-prod-postgres', region: 'us-central1' }], unmapped: { compliance: { CIS: ['6.2'], SOC2: ['CC6.7'], 'PCI-DSS': ['4.1'], NIST: ['AC-17', 'SC-8'] } } },
  { status_code: 'FAIL', severity: 'medium',   finding_info: { title: 'GKE clusters should have binary authorization enabled', desc: 'Binary Authorization not enforced on gke-prod-cluster-01 — arbitrary container images can run.' },    resources: [{ uid: 'gke-prod-cluster-01', region: 'us-central1' }],    unmapped: { compliance: { CIS: ['7.3'], SOC2: ['CC6.6'], 'ISO-27001': ['A.14.2.7'] } } },
  { status_code: 'FAIL', severity: 'medium',   finding_info: { title: 'Cloud KMS keys should have rotation enabled', desc: '4 KMS keys have not been rotated in over 90 days — exceeds key rotation policy.' },                             resources: [{ uid: 'projects/xammer/locations/global/keyRings/prod', region: 'global' }], unmapped: { compliance: { CIS: ['1.8'], SOC2: ['CC6.7'], 'PCI-DSS': ['3.6.4'], NIST: ['SC-12'] } } },
  { status_code: 'FAIL', severity: 'medium',   finding_info: { title: 'GCP project audit logging should be enabled for all services', desc: 'Data access audit logs are not enabled for BigQuery, Compute Engine, and Cloud Storage.' },  resources: [{ uid: 'project/xammer-analytics-prod', region: 'global' }], unmapped: { compliance: { CIS: ['2.1'], SOC2: ['CC7.2', 'CC7.3'], HIPAA: ['164.312(b)'], NIST: ['AU-2'] } } },
  { status_code: 'FAIL', severity: 'low',      finding_info: { title: 'VPC networks should restrict firewall rules with 0.0.0.0/0', desc: '2 firewall rules allow unrestricted SSH ingress from any source IP.' },                         resources: [{ uid: 'vpc-prod-network', region: 'global' }],              unmapped: { compliance: { CIS: ['3.6', '3.7'], SOC2: ['CC6.6'], 'PCI-DSS': ['1.2.1'] } } },
  { status_code: 'PASS', severity: 'medium',   finding_info: { title: 'GKE nodes should have shielded nodes enabled', desc: 'Shielded nodes are enabled on all node pools.' },                                                               resources: [{ uid: 'gke-prod-cluster-01', region: 'us-central1' }],    unmapped: { compliance: { CIS: ['7.5'], SOC2: ['CC6.6'] } } },
];

export const demoAzureProwlerStatus = { status: 'COMPLETED', findingsCount: 18 };
export const demoAzureProwlerFindings = [
  { status_code: 'FAIL', severity: 'critical', finding_info: { title: 'Azure subscriptions should have MFA enabled for all users', desc: 'Multi-factor authentication is not enforced for 3 users with privileged roles.' },       resources: [{ uid: 'subscription/demo-azure-001', region: 'eastus' }],   unmapped: { compliance: { CIS: ['1.1.2'], SOC2: ['CC6.1'], 'PCI-DSS': ['8.3'] } } },
  { status_code: 'FAIL', severity: 'high',     finding_info: { title: 'Storage accounts should restrict public blob access', desc: 'Azure Blob Storage account xammer-prod-storage has public access enabled.' },                 resources: [{ uid: 'xammer-prod-storage', region: 'eastus' }],            unmapped: { compliance: { CIS: ['3.5'], SOC2: ['CC6.1', 'CC7.2'], 'PCI-DSS': ['1.3.2'] } } },
  { status_code: 'FAIL', severity: 'high',     finding_info: { title: 'Azure SQL servers should have auditing enabled', desc: 'SQL Server xammer-prod-sql does not have server-level auditing configured.' },                     resources: [{ uid: 'xammer-prod-sql', region: 'eastus' }],                unmapped: { compliance: { CIS: ['4.1'], SOC2: ['CC7.2'], HIPAA: ['164.312(b)'], NIST: ['AU-2'] } } },
  { status_code: 'FAIL', severity: 'medium',   finding_info: { title: 'Azure VM disks should be encrypted with customer-managed keys', desc: '5 VM disks using platform-managed keys instead of customer-managed keys.' },       resources: [{ uid: 'azure-prod-app-01', region: 'eastus' }],              unmapped: { compliance: { CIS: ['7.2'], SOC2: ['CC6.7'], 'PCI-DSS': ['3.4'] } } },
  { status_code: 'FAIL', severity: 'medium',   finding_info: { title: 'Network Security Groups should not allow unrestricted inbound SSH', desc: 'NSG rule allows inbound SSH (port 22) from any source (0.0.0.0/0).' },        resources: [{ uid: 'nsg-prod-eastus', region: 'eastus' }],                unmapped: { compliance: { CIS: ['6.2'], SOC2: ['CC6.6'], 'PCI-DSS': ['1.2.1'] } } },
  { status_code: 'FAIL', severity: 'low',      finding_info: { title: 'Azure Key Vault should have purge protection enabled', desc: 'Key Vault xammer-prod-kv does not have purge protection — keys can be permanently deleted.' }, resources: [{ uid: 'xammer-prod-kv', region: 'eastus' }],                unmapped: { compliance: { CIS: ['8.1'], SOC2: ['A1.2'], NIST: ['SC-12'] } } },
];
