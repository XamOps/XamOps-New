import React from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { RefreshCw } from 'lucide-react';
import { xampulseApi, isXpDemo, type XpApiError } from '../../../api/xampulseApi';
import { useViewContext } from '../../../hooks/xampulse/useViewContext';
import { Card, ErrorState, SkeletonBlock, TonePill } from '../ui/ui';
import { TimeSeriesChart, type ChartSeries } from '../charts/TimeSeriesChart';
import { formatAgo, formatBytes } from '../../../utils/xampulse/format';
import type { XpCloudWatchView, XpEc2Facts, XpEc2Finding } from '../../../types/xampulse';

const TONE: Record<XpEc2Finding['severity'], 'crit' | 'warn' | 'info'> = { CRITICAL: 'crit', WARNING: 'warn', INFO: 'info' };
const checkTone = (s: string): 'ok' | 'crit' | 'nodata' => (s === 'ok' ? 'ok' : s === 'impaired' ? 'crit' : 'nodata');
const daysAgo = (iso?: string): number | null => (iso ? Math.floor((Date.now() - Date.parse(iso)) / 86_400_000) : null);

const Fact: React.FC<{ label: string; children: React.ReactNode }> = ({ label, children }) => (
  <div style={{ minWidth: 0 }}>
    <div className="xp-muted" style={{ fontSize: 10.5, textTransform: 'uppercase', letterSpacing: '0.08em' }}>{label}</div>
    <div style={{ fontSize: 13, marginTop: 2, wordBreak: 'break-word' }}>{children ?? '—'}</div>
  </div>
);

const FactsBody: React.FC<{ f: XpEc2Facts }> = ({ f }) => {
  const mem = f.memoryMiB ? `${(f.memoryMiB / 1024).toFixed(f.memoryMiB % 1024 === 0 ? 0 : 1)} GiB` : null;
  const amiAge = daysAgo(f.amiCreatedAt);
  const up = daysAgo(f.launchTime);
  return (
    <>
      <Card title="What AWS reports" id="xp-aws-status">
        <div className="xp-row" style={{ gap: 8, flexWrap: 'wrap' }}>
          <TonePill tone={checkTone(f.status.system)}>AWS system check: {f.status.system}</TonePill>
          <TonePill tone={checkTone(f.status.instance)}>Instance check: {f.status.instance}</TonePill>
          {f.state && <TonePill tone={f.state === 'running' ? 'ok' : 'warn'}>{f.state}</TonePill>}
        </div>
        {f.scheduledEvents.length > 0 && (
          <ul style={{ margin: '10px 0 0', paddingLeft: 18 }}>
            {f.scheduledEvents.map((e) => <li key={`${e.code}-${e.notBefore ?? ''}`}><strong>{e.code}</strong>{e.notBefore ? ` from ${new Date(e.notBefore).toLocaleString()}` : ''}{e.description ? ` — ${e.description}` : ''}</li>)}
          </ul>
        )}
        {f.findings.length > 0 && (
          <div style={{ marginTop: 12, display: 'grid', gap: 8 }}>
            {f.findings.map((x) => (
              <div key={x.title} style={{ display: 'grid', gridTemplateColumns: 'auto 1fr', gap: 10, alignItems: 'start' }}>
                <TonePill tone={TONE[x.severity]}>{x.severity.toLowerCase()}</TonePill>
                <div><strong style={{ fontWeight: 600 }}>{x.title}</strong><div className="xp-muted" style={{ fontSize: 12.5, marginTop: 2 }}>{x.detail}</div></div>
              </div>
            ))}
          </div>
        )}
      </Card>
      <Card title="Instance" id="xp-aws-facts">
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(150px, 1fr))', gap: 12 }}>
          <Fact label="Type">{f.instanceType}{f.vcpus ? ` · ${f.vcpus} vCPU` : ''}{mem ? ` · ${mem}` : ''}</Fact>
          <Fact label="Architecture / platform">{[f.architecture, f.platform].filter(Boolean).join(' · ') || null}</Fact>
          <Fact label="EBS optimized">{f.ebsOptimized ? 'Yes' : 'No'}</Fact>
          <Fact label="Detailed monitoring">{f.detailedMonitoring ? 'Enabled' : 'Basic monitoring'}</Fact>
          <Fact label="Pricing">{f.lifecycle === 'on-demand' ? 'On-Demand' : f.lifecycle}</Fact>
          <Fact label="CPU credits">{f.burstable ? (f.cpuCredits ?? 'standard') : 'Not burstable'}</Fact>
          <Fact label="Zone">{f.availabilityZone}</Fact>
          <Fact label="Running for">{up === null ? null : `${up} day${up === 1 ? '' : 's'}`}</Fact>
          <Fact label="AMI">{f.amiName ?? f.amiId}{amiAge !== null ? ` (${amiAge} days old)` : ''}</Fact>
          <Fact label="Auto Scaling">{f.autoScalingGroup ? `${f.autoScalingGroup} · ${f.autoScalingLifecycleState ?? ''}` : 'Not in a group'}</Fact>
          <Fact label="Network">{f.networkPerformance}</Fact>
          <Fact label="EBS baseline">{f.ebsBaselineIops ? `${f.ebsBaselineIops.toLocaleString()} IOPS · ${f.ebsBaselineMbps ?? '?'} MB/s` : null}</Fact>
          <Fact label="Private IP">{f.privateIp}</Fact>
          <Fact label="Public IP">{f.publicIp ?? 'None'}</Fact>
          <Fact label="Metadata (IMDS)">{f.imdsV2Required ? 'v2 required' : 'v1 allowed'}</Fact>
          <Fact label="Instance role">{f.iamInstanceProfile ? f.iamInstanceProfile.split('/').pop() : 'None'}</Fact>
          <Fact label="Security groups">{f.securityGroups.join(', ')}</Fact>
          <Fact label="VPC / subnet">{f.vpcId} / {f.subnetId}</Fact>
          <Fact label="AWS facts fetched">{formatAgo(f.fetchedAt)}</Fact>
        </div>
        {Object.keys(f.tags).length > 0 && <details style={{ marginTop: 14 }}><summary>Tags ({Object.keys(f.tags).length})</summary><dl className="xp-dl">{Object.entries(f.tags).map(([key, value]) => <React.Fragment key={key}><dt>{key}</dt><dd>{value}</dd></React.Fragment>)}</dl></details>}
      </Card>
      {f.volumes.length > 0 && (
        <Card title="Volumes" id="xp-aws-vols">
          <div className="xp-table-wrap">
            <table className="xp-table">
              <thead><tr><th>Volume</th><th>Device</th><th>Type</th><th>Size</th><th>IOPS</th><th>Throughput</th><th>Encrypted</th><th>Delete on termination</th></tr></thead>
              <tbody>
                {f.volumes.map((v) => (
                  <tr key={v.volumeId}>
                    <td className="xp-mono">{v.volumeId}</td><td className="xp-mono">{v.device ?? '—'}</td><td>{v.type}</td><td>{v.sizeGiB} GiB</td>
                    <td>{v.iops?.toLocaleString() ?? '—'}</td><td>{v.throughputMiBps ? `${v.throughputMiBps} MiB/s` : '—'}</td>
                    <td>{v.encrypted ? 'Yes' : <TonePill tone="warn">No</TonePill>}</td><td>{v.deleteOnTermination ? 'Yes' : 'No'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </Card>
      )}
    </>
  );
};

const series = (m: Record<string, [number, number][]> | undefined, spec: [string, string][]): ChartSeries[] =>
  !m ? [] : spec.filter(([k]) => (m[k]?.length ?? 0) > 0).map(([k, name]) => ({ key: k, name, points: m[k] }));

const CloudWatchBody: React.FC<{ v: XpCloudWatchView; facts?: XpEc2Facts }> = ({ v, facts }) => {
  const ctx = useViewContext();
  const chart = (title: string, s: ChartSeries[], fmt: (n: number) => string, yMax?: number) => (s.length === 0 ? null : (
    <div key={title} style={{ marginTop: 12 }}>
      <div className="xp-muted" style={{ fontSize: 12, marginBottom: 4 }}>{title}</div>
      <TimeSeriesChart showLegend series={s} height={130} format={fmt} yMax={yMax} ariaLabel={title} fromMs={ctx.fromMs} toMs={ctx.toMs} />
    </div>
  ));
  const i = v.instance;
  const hasAny = Object.keys(i).length > 0 || Object.keys(v.volumes).length > 0;
  if (!hasAny) {
    return <p className="xp-muted" style={{ margin: 0 }}>No CloudWatch data fetched for this time range yet. Press <strong>Refresh</strong> to fetch it from AWS.</p>;
  }
  const volType = (id: string) => facts?.volumes.find((x) => x.volumeId === id);
  return (
    <>
      {chart('CPU utilization (AWS)', series(i, [['cpu.utilization', 'CPU']]), (n) => `${n.toFixed(0)}%`, 100)}
      {chart('CPU credits', series(i, [['cpu.credit_balance', 'Balance'], ['cpu.surplus_balance', 'Surplus (billed)']]), (n) => n.toFixed(0))}
      {chart('Network', series(i, [['net.in_bps', 'In'], ['net.out_bps', 'Out']]), (n) => `${formatBytes(n)}/s`)}
      {chart('Packets', series(i, [['net.in_pps', 'In'], ['net.out_pps', 'Out']]), (n) => `${n.toFixed(0)}/s`)}
      {chart('Instance EBS burst balance', series(i, [['ebs.io_balance_pct', 'IOPS'], ['ebs.byte_balance_pct', 'Throughput']]), (n) => `${n.toFixed(0)}%`, 100)}
      {chart('Status check failures', series(i, [['status.system_failed', 'System'], ['status.instance_failed', 'Instance']]), (n) => n.toFixed(0), 1)}
      {Object.entries(v.volumes).map(([id, m]) => {
        const meta = volType(id);
        return (
          <div key={id} style={{ marginTop: 16, paddingTop: 12, borderTop: '1px solid var(--xp-border)' }}>
            <div style={{ fontWeight: 600, fontSize: 13 }}><span className="xp-mono">{id}</span>{meta ? ` · ${meta.type} · ${meta.sizeGiB} GiB${meta.iops ? ` · ${meta.iops.toLocaleString()} IOPS provisioned` : ''}` : ''}</div>
            {chart('IOPS', series(m, [['iops.read', 'Read'], ['iops.write', 'Write']]), (n) => n.toFixed(0), meta?.iops)}
            {chart('Throughput', series(m, [['mibps.read', 'Read'], ['mibps.write', 'Write']]), (n) => `${n.toFixed(1)} MiB/s`, meta?.throughputMiBps)}
            {chart('Latency per operation', series(m, [['latency_ms.read', 'Read'], ['latency_ms.write', 'Write']]), (n) => `${n.toFixed(1)} ms`)}
            {chart('Queue length', series(m, [['queue_length', 'Queue']]), (n) => n.toFixed(1))}
            {chart('Burst balance', series(m, [['burst_balance', 'Balance']]), (n) => `${n.toFixed(0)}%`, 100)}
          </div>
        );
      })}
    </>
  );
};

/**
 * The AWS view of one instance. Facts load automatically (free EC2 APIs). CloudWatch data is never fetched
 * automatically: the page shows what was fetched before, and Refresh asks CloudWatch now (billed to the customer).
 */
export const AwsPanel: React.FC<{ accountId: number; instanceId: string }> = ({ accountId, instanceId }) => {
  const ctx = useViewContext();
  const client = useQueryClient();
  const live = !isXpDemo();
  const facts = useQuery<XpEc2Facts, XpApiError>({
    queryKey: ['xp', 'live', 'aws', accountId, instanceId], enabled: live && instanceId.startsWith('i-'),
    queryFn: () => xampulseApi.hostAws(accountId, instanceId), staleTime: 60_000, retry: 1, refetchOnWindowFocus: false,
  });
  const cwKey = ['xp', 'live', 'cloudwatch', accountId, instanceId, ctx.from, ctx.to];
  const stored = useQuery<XpCloudWatchView, XpApiError>({
    queryKey: cwKey, enabled: live && instanceId.startsWith('i-'),
    queryFn: () => xampulseApi.hostCloudWatch(accountId, instanceId, ctx.from, ctx.to), retry: 1, refetchOnWindowFocus: false,
  });
  const refresh = useMutation<XpCloudWatchView, XpApiError>({
    mutationFn: () => xampulseApi.refreshHostCloudWatch(accountId, instanceId, ctx.from, ctx.to),
    onSuccess: (v) => { client.setQueryData(cwKey, v); },
  });
  if (!live) return null;
  const v = refresh.data ?? stored.data;
  // Same rule as CloudWatchViewService.specs: 11 instance metrics, 4 more for burstable types, 10 per volume plus
  // BurstBalance only where it exists (gp2, st1, sc1).
  const metricCount = 11 + (facts.data?.burstable ? 4 : 0)
    + (facts.data?.volumes ?? []).reduce((n, vol) => n + 10 + (['gp2', 'st1', 'sc1'].includes(vol.type) ? 1 : 0), 0);
  const requestedMetrics = v?.metricsRequested ?? metricCount;
  const estimatedCost = v?.estimatedCostUsd ?? metricCount * 0.00001;
  const cost = estimatedCost.toFixed(4);
  return (
    <>
      {facts.isLoading && <SkeletonBlock height={160} label="Loading AWS facts" />}
      {facts.error && <ErrorState compact error={facts.error} onRetry={() => { void facts.refetch(); }} />}
      {facts.data && <FactsBody f={facts.data} />}
      <Card
        title="CloudWatch"
        id="xp-aws-cw"
        actions={
          <button type="button" className="xp-btn" disabled={refresh.isPending} onClick={() => refresh.mutate()} aria-label="Refresh CloudWatch data">
            <RefreshCw size={14} aria-hidden="true" style={{ marginRight: 6 }} />{refresh.isPending ? 'Fetching…' : 'Refresh'}
          </button>
        }
      >
        <p className="xp-muted" style={{ marginTop: 0, fontSize: 12 }}>
          {v ? `${v.source === 'REFRESH' ? 'Fetched from AWS' : 'Stored data'} · ${new Date(v.from).toLocaleString()} to ${new Date(v.to).toLocaleString()} · ${v.periodSec}s period. ` : ''}{v?.lastFetchedAt ? `Last fetched ${formatAgo(v.lastFetchedAt)}.` : 'Never fetched for this instance.'}{' '}
          Refresh requests about {requestedMetrics} metrics from CloudWatch (≈ ${cost}), billed to your AWS account. Fetched data is kept, so viewing it again is free.
          {v?.note ? ` ${v.note}` : ''}
        </p>
        {refresh.error && <ErrorState compact error={refresh.error} />}
        {stored.isLoading && !v && <SkeletonBlock height={120} />}
        {stored.error && !v && <ErrorState compact error={stored.error} onRetry={() => { void stored.refetch(); }} />}
        {v && <CloudWatchBody v={v} facts={facts.data} />}
      </Card>
    </>
  );
};
