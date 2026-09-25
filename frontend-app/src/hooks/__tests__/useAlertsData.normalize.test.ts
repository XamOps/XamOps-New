import { describe, it, expect } from 'vitest';
import { normaliseAlerts } from '../useAlertsData';

/**
 * How the AWS Alerts page turns the backend's rows into table rows. Pins the two facts that were
 * wrong: live security findings were dropped, and severity was ignored for everything but quotas.
 */
describe('normaliseAlerts', () => {
  it('keeps live security findings, which arrive typed SECURITY_FINDING', () => {
    const [row] = normaliseAlerts([
      { id: 'finding-1', type: 'SECURITY_FINDING', service: 'GuardDuty', name: 'Crypto mining', severity: 'HIGH' },
    ]);

    expect(row).toMatchObject({ type: 'SECURITY', source: 'LIVE', severity: 'HIGH' });
  });

  it('marks Security Hub findings as such, so they link to the AWS console', () => {
    const [row] = normaliseAlerts([
      { id: 'securityhub-1', type: 'SECURITY', service: 'Software and Configuration Checks', name: 'Public bucket', severity: 'CRITICAL' },
    ]);

    expect(row).toMatchObject({ type: 'SECURITY', source: 'SECURITY_HUB', severity: 'CRITICAL' });
  });

  it('sorts by severity across types, then by how full a quota is', () => {
    const rows = normaliseAlerts([
      { id: 'q-80', type: 'QUOTA', service: 'VPC', name: 'VPCs', usage: 4, limit: 5, severity: 'MEDIUM' },
      { id: 'sec', type: 'SECURITY', service: 'IAM', name: 'Root keys', severity: 'CRITICAL' },
      { id: 'cost', type: 'ANOMALY', service: 'EC2', name: 'Cost spike', usage: 120, severity: 'HIGH' },
      { id: 'q-95', type: 'QUOTA', service: 'VPC', name: 'EIPs', usage: 19, limit: 20, severity: 'CRITICAL' },
      { id: 'q-76', type: 'QUOTA', service: 'VPC', name: 'IGWs', usage: 38, limit: 50, severity: 'MEDIUM' },
    ]);

    expect(rows.map((r) => r.id)).toEqual(['q-95', 'sec', 'cost', 'q-80', 'q-76']);
  });

  it('derives severity when a cached or demo row has none', () => {
    const rows = normaliseAlerts([
      { id: 'q', type: 'QUOTA', service: 'VPC', name: 'VPCs', usage: 95, limit: 100 },
      { id: 'a', type: 'ANOMALY', service: 'EC2', name: 'Spike', usage: 10, status: 'CRITICAL' },
      { id: 'b', type: 'ANOMALY', service: 'S3', name: 'Spike', usage: 10 },
    ]);

    expect(rows.find((r) => r.id === 'q')?.severity).toBe('CRITICAL');
    expect(rows.find((r) => r.id === 'a')?.severity).toBe('CRITICAL');
    expect(rows.find((r) => r.id === 'b')?.severity).toBe('MEDIUM');
  });

  it('carries the anomaly currency, defaulting to dollars', () => {
    const [withSymbol, withoutSymbol] = normaliseAlerts([
      { id: 'a', type: 'ANOMALY', service: 'EC2', name: 'Spike', usage: 10, severity: 'HIGH', currencySymbol: '₹' },
      { id: 'b', type: 'ANOMALY', service: 'S3', name: 'Spike', usage: 10, severity: 'HIGH' },
    ]);

    expect(withSymbol).toMatchObject({ currencySymbol: '₹' });
    expect(withoutSymbol).toMatchObject({ currencySymbol: '$' });
  });

  it('drops rows of an unknown type rather than rendering them half-filled', () => {
    expect(normaliseAlerts([{ id: 'x', type: 'SOMETHING_ELSE', name: 'x' }])).toEqual([]);
  });
});
