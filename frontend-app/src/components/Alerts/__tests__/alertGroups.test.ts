import { describe, it, expect } from 'vitest';
import {
  costSummary,
  groupQuotaAlerts,
  incompleteCategories,
  matchesSeverity,
  splitFinding,
} from '../alertGroups';
import type { AlertError, AnomalyAlert, QuotaAlert } from '../../../types/alerts';

const quota = (name: string, service: string, region: string, usage: number, limit: number): QuotaAlert => ({
  id: `${name}-${region}`,
  type: 'QUOTA',
  service,
  name,
  description: '',
  region,
  usage,
  limit,
  severity: usage / limit >= 0.9 ? 'CRITICAL' : 'MEDIUM',
});

const error = (service: string): AlertError => ({
  service,
  errorType: 'API_ERROR',
  message: 'x',
  partial: false,
  timestamp: '',
});

describe('groupQuotaAlerts', () => {
  // The seven quota rows from the xammer-local account, as the page listed them.
  const rows = [
    quota('VPCs per Region', 'VPC', 'ap-south-1', 5, 5),
    quota('Internet gateways per Region', 'Internet Gateway', 'ap-south-1', 5, 5),
    quota('EC2-VPC Elastic IPs', 'Elastic IP', 'ap-south-1', 5, 5),
    quota('Internet gateways per Region', 'Internet Gateway', 'ap-south-2', 5, 5),
    quota('VPCs per Region', 'VPC', 'ap-south-2', 4, 5),
    quota('VPCs per Region', 'VPC', 'us-east-1', 4, 5),
    quota('Internet gateways per Region', 'Internet Gateway', 'us-east-1', 4, 5),
  ];

  it('collapses one limit seen in several regions into one row', () => {
    const groups = groupQuotaAlerts(rows);

    expect(groups.map((g) => g.name).sort()).toEqual(
      ['EC2-VPC Elastic IPs', 'Internet gateways per Region', 'VPCs per Region']
    );
    const vpcs = groups.find((g) => g.name === 'VPCs per Region')!;
    expect(vpcs.usages.map((u) => u.label)).toEqual(['ap-south-1', 'ap-south-2', 'us-east-1']);
  });

  it('takes the worst region as the row severity, and puts the fullest region first', () => {
    const igw = groupQuotaAlerts(rows).find((g) => g.name === 'Internet gateways per Region')!;

    expect(igw.severity).toBe('CRITICAL');
    expect(igw.usages[0].percent).toBe(100);
    expect(igw.usages[igw.usages.length - 1]).toMatchObject({ label: 'us-east-1', percent: 80 });
  });

  it('keeps a per-zone limit as one row with a chip per zone', () => {
    const groups = groupQuotaAlerts([
      quota('NAT gateways per Availability Zone (ap-south-1a)', 'NAT Gateway', 'ap-south-1', 5, 5),
      quota('NAT gateways per Availability Zone (ap-south-1b)', 'NAT Gateway', 'ap-south-1', 4, 5),
    ]);

    expect(groups).toHaveLength(1);
    expect(groups[0].name).toBe('NAT gateways per Availability Zone');
    expect(groups[0].usages.map((u) => [u.label, u.region])).toEqual([
      ['ap-south-1a', 'ap-south-1'],
      ['ap-south-1b', 'ap-south-1'],
    ]);
  });
});

describe('splitFinding', () => {
  it('separates the fix from a Security Hub description', () => {
    const { summary, remediation } = splitFinding(
      'AWS Config should be enabled',
      'This control checks whether AWS Config is enabled. | Remediation: Enable AWS Config in each Region.'
    );

    expect(summary).toBe('This control checks whether AWS Config is enabled.');
    expect(remediation).toBe('Enable AWS Config in each Region.');
  });

  it('drops the repeated title and separates the fix from a XamOps finding', () => {
    const { summary, remediation } = splitFinding(
      'Administrator-level policy attached',
      'Administrator-level policy attached — AdministratorAccess was attached to Akshay-Dev. Remediation: Confirm this level of access is required.'
    );

    expect(summary).toBe('AdministratorAccess was attached to Akshay-Dev.');
    expect(remediation).toBe('Confirm this level of access is required.');
  });

  it('leaves a description with no fix as it is', () => {
    expect(splitFinding('Title', 'Just a description.')).toEqual({
      summary: 'Just a description.',
      remediation: null,
    });
  });
});

describe('costSummary', () => {
  it('drops the service name the row already shows', () => {
    const alert: AnomalyAlert = {
      id: 'a', type: 'ANOMALY', service: 'Amazon S3', name: '', region: 'Global', severity: 'MEDIUM',
      usage: 4, currencySymbol: '$',
      description: 'Amazon S3: $4.00 more than the expected $1.00 on 2026-09-11 — 5.0× normal.',
    };

    expect(costSummary(alert)).toBe('$4.00 more than the expected $1.00 on 2026-09-11 — 5.0× normal.');
  });
});

describe('matchesSeverity', () => {
  it('treats high and medium as warnings, and shows low only under All', () => {
    expect(matchesSeverity('HIGH', 'WARNING')).toBe(true);
    expect(matchesSeverity('MEDIUM', 'WARNING')).toBe(true);
    expect(matchesSeverity('CRITICAL', 'WARNING')).toBe(false);
    expect(matchesSeverity('LOW', 'WARNING')).toBe(false);
    expect(matchesSeverity('LOW', 'ALL')).toBe(true);
  });
});

describe('incompleteCategories', () => {
  it('marks only the section whose check failed', () => {
    expect([...incompleteCategories([error('Security Hub')])]).toEqual(['SECURITY']);
    expect([...incompleteCategories([error('Cost anomalies'), error('VPC quotas')])].sort())
      .toEqual(['COST', 'LIMITS']);
  });

  it('marks every section when the whole scan failed', () => {
    expect([...incompleteCategories([error('Alerts scan')])].sort()).toEqual(['COST', 'LIMITS', 'SECURITY']);
  });

  it('marks nothing when nothing failed', () => {
    expect(incompleteCategories([]).size).toBe(0);
  });
});
