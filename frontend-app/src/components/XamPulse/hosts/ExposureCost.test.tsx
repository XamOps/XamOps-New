import { afterEach, describe, expect, it } from 'vitest';
import { cleanup, render, screen, within } from '@testing-library/react';
import { CostView, ExposureView } from './ExposureCost';
import type { XpCostPerf, XpExposure } from '../../../types/xampulse';

afterEach(cleanup);

describe('ExposureView', () => {
  it('lists the internet-reachable sockets first with the rule that lets them in, and the finding above', () => {
    const x: XpExposure = {
      instanceId: 'i-1', publicIp: '203.0.113.25', measured: true, securityGroups: ['web (sg-1)'],
      sockets: [
        { protocol: 'tcp', port: 9100, bind: 'all', address: '0.0.0.0', service: 'node exporter', exposure: 'BLOCKED' },
        { protocol: 'tcp', port: 6379, bind: 'all', address: '0.0.0.0', service: 'Redis', exposure: 'INTERNET', via: 'web (sg-1) allows 0.0.0.0/0 on port 6379' },
        { protocol: 'tcp', port: 5432, bind: 'loopback', address: '127.0.0.1', service: 'PostgreSQL', exposure: 'LOCAL' },
      ],
      findings: [{ severity: 'CRITICAL', code: 'exposed_sensitive', source: 'AWS', title: 'Redis (6379)/tcp is reachable from the internet', detail: 'd', fix: 'Remove the rule.', evidence: { port: 6379 } }],
      note: 'Network ACLs are not included.',
    };
    render(<ExposureView x={x} />);
    expect(screen.getByRole('alert')).toHaveTextContent('Redis (6379)/tcp is reachable from the internet');
    const rows = within(screen.getByRole('table', { name: 'Listening sockets and who can reach them' })).getAllByRole('row');
    expect(rows[1]).toHaveTextContent('6379/tcp');
    expect(rows[1]).toHaveTextContent('web (sg-1) allows 0.0.0.0/0 on port 6379');
    expect(rows[3]).toHaveTextContent('This host only');
    expect(screen.getByText(/Public IPv4 203\.0\.113\.25/)).toBeInTheDocument();
  });

  it('never says "nothing exposed" when the agent does not report sockets', () => {
    render(<ExposureView x={{ instanceId: 'i-1', measured: false, securityGroups: [], sockets: [], findings: [], note: 'The agent on this host does not report listening sockets yet.' }} />);
    expect(screen.queryByText(/Nothing sensitive/)).toBeNull();
    expect(screen.getByText(/does not report listening sockets/)).toBeInTheDocument();
  });
});

describe('CostView', () => {
  it('shows price against use, and says "not stated" instead of $0 when there is no price', () => {
    const c: XpCostPerf = { instanceId: 'i-1', instanceType: 'm5.xlarge', vcpus: 4, memoryGiB: 16, hourlyUsd: 0.192, monthlyUsd: 140.16, priceBasis: 'ON_DEMAND_LIST',
      cpuAvgPct: 12.5, cpuP95Pct: 12, memAvgPct: 30, memP95Pct: 30, usedVcpusAvg: 0.5, costPerUsedVcpuHour: 0.384, verdict: 'OVERSIZED',
      verdictText: 'Mostly idle.', notes: ['AWS on-demand list price.'] };
    const { unmount } = render(<CostView c={c} />);
    expect(screen.getByText('Mostly idle', { selector: '.xp-chip' })).toBeInTheDocument();
    expect(screen.getByText('$0.1920/h')).toBeInTheDocument();
    expect(screen.getByText('$140')).toBeInTheDocument();
    expect(screen.getByText('0.5 of 4')).toBeInTheDocument();
    expect(screen.getByText('$0.384')).toBeInTheDocument();
    unmount();
    render(<CostView c={{ ...c, hourlyUsd: undefined, monthlyUsd: undefined, costPerUsedVcpuHour: undefined, priceBasis: undefined, notes: ['No price shown: list prices are looked up for Linux/UNIX only.'] }} />);
    expect(screen.getByText('not stated')).toBeInTheDocument();
    expect(screen.queryByText(/\$0\b/)).toBeNull();
  });
});
