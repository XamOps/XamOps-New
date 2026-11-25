import React from 'react';
import { useHostCost, useHostExposure } from '../../../hooks/xampulse/useXamPulseQueries';
import { useViewContext } from '../../../hooks/xampulse/useViewContext';
import { QueryBoundary } from '../ui/QueryBoundary';
import { Card, SkeletonBlock } from '../ui/ui';
import { LimitFinding } from './ThrottleRadar';
import type { XpCostPerf, XpExposure } from '../../../types/xampulse';
import { formatPct } from '../../../utils/xampulse/format';

const EXPOSURE_LABEL: Record<string, string> = {
  INTERNET: 'Internet', PUBLIC_RANGE: 'Allowed public range', PRIVATE: 'Private network', BLOCKED: 'Blocked by security groups', LOCAL: 'This host only',
};
const EXPOSURE_ORDER: Record<string, number> = { INTERNET: 0, PUBLIC_RANGE: 1, PRIVATE: 2, BLOCKED: 3, LOCAL: 4 };

/** What listens on the host and who can reach it: the agent's sockets against the instance's security groups. */
export const ExposureView: React.FC<{ x: XpExposure }> = ({ x }) => {
  const socks = [...x.sockets].sort((a, b) => EXPOSURE_ORDER[a.exposure] - EXPOSURE_ORDER[b.exposure] || a.port - b.port);
  return (
    <>
      <div className={`xp-banner xp-banner--${x.measured ? 'ok' : 'info'}`} style={{ marginBottom: 10 }}><strong>{x.measured ? 'Measured' : 'Not measured'}.</strong> {x.measured ? 'Listening sockets were compared with security-group ingress.' : 'Listening-socket telemetry is unavailable for this host.'}</div>
      {x.findings.length > 0 && <div className="xp-radar__findings" style={{ marginBottom: 10 }}>{x.findings.map((f) => <LimitFinding key={`${f.code}-${f.title}`} f={f} />)}</div>}
      {x.findings.length === 0 && x.measured && <p style={{ margin: '0 0 10px' }}>Nothing sensitive is reachable from the internet.</p>}
      {socks.length > 0 && (
        <div className="xp-table-wrap"><table className="xp-table">
          <caption className="xp-sr">Listening sockets and who can reach them</caption>
          <thead><tr><th scope="col">Port</th><th scope="col">Service</th><th scope="col">Listens on</th><th scope="col">Reachable from</th></tr></thead>
          <tbody>{socks.map((s) => (
            <tr key={`${s.protocol}-${s.port}`} className={s.exposure === 'INTERNET' ? 'xp-peers__odd' : undefined}>
              <td className="xp-mono">{s.port}/{s.protocol}</td>
              <td>{s.service ?? '—'}</td>
              <td className="xp-mono" style={{ fontSize: 12 }}>{s.bind === 'all' ? 'all interfaces' : s.address}</td>
              <td title={s.via}>{EXPOSURE_LABEL[s.exposure] ?? s.exposure}{s.via && <div className="xp-muted" style={{ fontSize: 11.5 }}>{s.via}</div>}</td>
            </tr>
          ))}</tbody>
        </table></div>
      )}
      <p className="xp-muted" style={{ fontSize: 11.5, margin: '8px 0 0' }}>
        {x.securityGroups.length > 0 ? `Security groups: ${x.securityGroups.join(', ')}. ` : ''}{x.publicIp ? `Public IPv4 ${x.publicIp}. ` : 'No public IPv4 address. '}{x.note}
      </p>
    </>
  );
};

const VERDICT_TONE: Record<XpCostPerf['verdict'], string> = { UNDERSIZED: 'warn', OVERSIZED: 'warn', FITS: 'ok', BURSTABLE: 'ok', NOT_ENOUGH_DATA: 'nodata' };
const VERDICT_LABEL: Record<XpCostPerf['verdict'], string> = { UNDERSIZED: 'Near its limits', OVERSIZED: 'Mostly idle', FITS: 'Sized for its load', BURSTABLE: 'Burstable, low use', NOT_ENOUGH_DATA: 'Not enough data' };

const usd = (v: number | undefined, digits: number): string => (v === undefined ? '—' : `$${v.toFixed(digits)}`);

/** Price next to measured use. A missing price is said in words, never shown as $0. */
export const CostView: React.FC<{ c: XpCostPerf }> = ({ c }) => (
  <>
    <div className="xp-row" style={{ gap: 8, marginBottom: 8 }}>
      <span className={`xp-chip xp-chip--${VERDICT_TONE[c.verdict]}`}>{VERDICT_LABEL[c.verdict]}</span>
      <span className="xp-muted" style={{ fontSize: 12 }}>{c.instanceType}{c.vcpus ? ` · ${c.vcpus} vCPU` : ''}{c.memoryGiB ? ` · ${c.memoryGiB} GiB` : ''}{c.lifecycle === 'spot' ? ' · Spot' : ''}</span>
    </div>
    <p style={{ margin: '0 0 10px' }}>{c.verdictText}</p>
    <dl className="xp-backtest__counts">
      <div><dt>{c.priceBasis === 'SPOT_CURRENT' ? 'Spot price' : 'List price'}</dt><dd>{c.hourlyUsd === undefined ? 'not stated' : `${usd(c.hourlyUsd, 4)}/h`}</dd></div>
      <div><dt>Per month</dt><dd>{c.monthlyUsd === undefined ? '—' : `$${c.monthlyUsd.toLocaleString('en-US', { maximumFractionDigits: 0 })}`}</dd></div>
      <div><dt>CPU avg / p95</dt><dd>{formatPct(c.cpuAvgPct, 0)} / {formatPct(c.cpuP95Pct, 0)}</dd></div>
      <div><dt>Memory avg / p95</dt><dd>{formatPct(c.memAvgPct, 0)} / {formatPct(c.memP95Pct, 0)}</dd></div>
      {c.usedVcpusAvg !== undefined && <div><dt title="vCPUs times average CPU use">vCPUs used (avg)</dt><dd>{c.usedVcpusAvg} of {c.vcpus}</dd></div>}
      {c.costPerUsedVcpuHour !== undefined && <div><dt title="Hourly price divided by the vCPUs actually used on average">Per used vCPU-hour</dt><dd>{usd(c.costPerUsedVcpuHour, 3)}</dd></div>}
    </dl>
    <ul className="xp-backtest__notes" style={{ marginTop: 8 }}>{c.notes.map((n) => <li key={n}>{n}</li>)}</ul>
  </>
);

/** The AWS section's security and cost cards. Neither calls a billed API. */
export const ExposureAndCost: React.FC<{ accountId: number; instanceId: string }> = ({ accountId, instanceId }) => {
  const ctx = useViewContext();
  const exposure = useHostExposure(accountId, instanceId);
  const cost = useHostCost(accountId, instanceId, ctx.from, ctx.to);
  return (
    <div className="xp-grid xp-grid--2">
      <Card title="Exposure" id="xp-exposure">
        <QueryBoundary query={exposure} skeleton={<SkeletonBlock height={140} label="Matching listening sockets with security groups" />}>
          {(x) => <ExposureView x={x} />}
        </QueryBoundary>
      </Card>
      <Card title="Cost and use" id="xp-cost">
        <QueryBoundary query={cost} skeleton={<SkeletonBlock height={140} label="Reading the price and this range's use" />}>
          {(c) => <CostView c={c} />}
        </QueryBoundary>
      </Card>
    </div>
  );
};
