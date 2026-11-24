import { describe, expect, it } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import type { UseQueryResult } from '@tanstack/react-query';
import { ThrottleRadar, formatEvidence } from './ThrottleRadar';
import type { XpApiError } from '../../../api/xampulseApi';
import type { XpHostLimits } from '../../../types/xampulse';

const q = (data: XpHostLimits): UseQueryResult<XpHostLimits, XpApiError> =>
  ({ data, isLoading: false, error: null, refetch: async () => ({}) }) as unknown as UseQueryResult<XpHostLimits, XpApiError>;

const signals: XpHostLimits['signals'] = [
  { code: 'ena', label: 'AWS network allowances', state: 'LIMITED' },
  { code: 'psi', label: 'CPU, memory and I/O pressure', state: 'OK' },
  { code: 'ebs', label: 'EBS volume and instance limits', state: 'NOT_MEASURED', note: 'Press Refresh in the AWS section.' },
];

describe('ThrottleRadar', () => {
  it('shows each limit with who imposed it, the fix and the measured evidence', () => {
    render(<ThrottleRadar q={q({ instanceId: 'i-1', from: '', to: '', signals, findings: [
      { severity: 'WARNING', code: 'ena_pps', source: 'AWS', title: 'AWS is capping the network', detail: 'd', fix: 'Use a larger size.', evidence: { packets: 4210, per_second: 1.17 } },
    ] })} />);
    const region = screen.getByRole('region', { name: /Throttle Radar/ });
    expect(within(region).getByText('AWS limit')).toBeInTheDocument();
    expect(within(region).getByText('Use a larger size.')).toBeInTheDocument();
    expect(within(region).getByText('packets: 4,210')).toBeInTheDocument();
    expect(within(region).getByText('per second: 1.17/s')).toBeInTheDocument();
    expect(within(region).queryByRole('alert'), 'a warning is not an alert').toBeNull();
  });

  it('says plainly when nothing was hit, and how many checks were measurable', () => {
    render(<ThrottleRadar q={q({ instanceId: 'i-1', from: '', to: '', signals, findings: [] })} />);
    expect(screen.getByText('No limits hit in this range.')).toBeInTheDocument();
    expect(screen.getByText(/2 of 3 limit checks could be measured/)).toBeInTheDocument();
    expect(screen.getByText(/1 check not measurable here/)).toBeInTheDocument();
    expect(screen.getByText('Press Refresh in the AWS section.')).toBeInTheDocument();
  });

  it('formats evidence by meaning: shares as percent, percents as is, counts whole', () => {
    expect(formatEvidence('stalled_share', 0.123)).toBe('12%');
    expect(formatEvidence('steal_avg', 0.05)).toBe('5.0%');
    expect(formatEvidence('instance_iops_exceeded_pct', 25)).toBe('25%');
    expect(formatEvidence('window_seconds', 3600)).toBe('3,600 s');
    expect(formatEvidence('packets', 1800.4)).toBe('1,800');
  });
});
