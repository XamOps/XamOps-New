import { describe, it, expect } from 'vitest';
import { groupErrors } from '../AlertsErrorBanner';
import type { AlertError, AlertErrorType } from '../../../types/alerts';

const error = (service: string, errorType: AlertErrorType, message: string): AlertError => ({
  service, errorType, message, partial: false, timestamp: '',
});

describe('groupErrors', () => {
  it('lists expired credentials once, naming every check that hit them', () => {
    // xammer-local on 2026-09-22: every check failed on the same expired STS token.
    const groups = groupErrors([
      error('VPC quotas', 'CREDENTIALS_EXPIRED', 'expired … (Request ID: 85e05702)'),
      error('Network quotas', 'CREDENTIALS_EXPIRED', 'expired … (Request ID: 601bbf7a)'),
      error('Cost anomalies', 'CREDENTIALS_EXPIRED', 'expired … (Request ID: 25bc40dc)'),
    ]);

    expect(groups).toHaveLength(1);
    expect(groups[0].services).toEqual(['VPC quotas', 'Network quotas', 'Cost anomalies']);
  });

  it('treats messages differing only in request id as the same problem', () => {
    const groups = groupErrors([
      error('VPC quotas', 'API_ERROR', 'Throttled (Service: ServiceQuotas, Request ID: aaa)'),
      error('Network quotas', 'API_ERROR', 'Throttled (Service: ServiceQuotas, Request ID: bbb)'),
    ]);

    expect(groups).toHaveLength(1);
  });

  it('keeps different problems apart', () => {
    const groups = groupErrors([
      error('Security Hub', 'PERMISSION_DENIED', 'not authorized'),
      error('Cost anomalies', 'API_ERROR', 'service unavailable'),
    ]);

    expect(groups).toHaveLength(2);
  });
});
