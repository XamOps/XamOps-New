/**
 * EKS Overview Tab — Cast.ai-inspired clean design
 */

import React from 'react';
import { QuickWin } from '../../../types/eksTypes';

interface EksOverviewTabProps {
  eksData: any;
}

interface DonutGaugeProps {
  value: number;
  size?: number;
  strokeWidth?: number;
  trackColor?: string;
  arcColor: string;
  label: string;
  sublabel?: string;
  valueColor?: string;
}

const DonutGauge: React.FC<DonutGaugeProps> = ({
  value,
  size = 140,
  strokeWidth = 12,
  trackColor = '#e8ecf0',
  arcColor,
  label,
  sublabel,
  valueColor,
}) => {
  const r = (size - strokeWidth) / 2;
  const circ = 2 * Math.PI * r;
  const filled = Math.min(100, Math.max(0, value));
  const dashArray = `${(filled / 100) * circ} ${circ}`;
  const cx = size / 2;
  const cy = size / 2;
  const textColor = valueColor || arcColor;

  return (
    <div className="flex flex-col items-center">
      <div className="relative" style={{ width: size, height: size }}>
        <svg width={size} height={size} viewBox={`0 0 ${size} ${size}`} style={{ transform: 'rotate(-90deg)' }}>
          <circle cx={cx} cy={cy} r={r} fill="none" stroke={trackColor} strokeWidth={strokeWidth} />
          <circle cx={cx} cy={cy} r={r} fill="none" stroke={arcColor} strokeWidth={strokeWidth}
            strokeLinecap="round" strokeDasharray={dashArray} style={{ transition: 'stroke-dasharray 1s ease' }} />
        </svg>
        <div className="absolute inset-0 flex flex-col items-center justify-center">
          <span className="text-2xl font-semibold leading-none" style={{ color: textColor }}>{Math.round(filled)}</span>
          <span className="text-[11px] text-[#8b949e] leading-none mt-0.5">/100</span>
        </div>
      </div>
      <div className="text-center mt-2">
        <div className="text-sm font-semibold text-[#1a1f2e]">{label}</div>
        {sublabel && <div className="text-[11px] text-[#8b949e] mt-0.5">{sublabel}</div>}
      </div>
    </div>
  );
};

interface SmallGaugeProps {
  value: number;
  size?: number;
  arcColor: string;
  trackColor?: string;
  strokeWidth?: number;
  centerLabel: string;
}

const SmallGauge: React.FC<SmallGaugeProps> = ({
  value, size = 100, arcColor, trackColor = '#e8ecf0', strokeWidth = 10, centerLabel,
}) => {
  const r = (size - strokeWidth) / 2;
  const circ = 2 * Math.PI * r;
  const filled = Math.min(100, Math.max(0, value));
  const dashArray = `${(filled / 100) * circ} ${circ}`;
  const cx = size / 2;
  const cy = size / 2;
  return (
    <div className="relative inline-flex" style={{ width: size, height: size }}>
      <svg width={size} height={size} viewBox={`0 0 ${size} ${size}`} style={{ transform: 'rotate(-90deg)' }}>
        <circle cx={cx} cy={cy} r={r} fill="none" stroke={trackColor} strokeWidth={strokeWidth} />
        <circle cx={cx} cy={cy} r={r} fill="none" stroke={arcColor} strokeWidth={strokeWidth}
          strokeLinecap="round" strokeDasharray={dashArray} style={{ transition: 'stroke-dasharray 1s ease' }} />
      </svg>
      <div className="absolute inset-0 flex items-center justify-center">
        <span className="text-sm font-semibold" style={{ color: arcColor }}>{centerLabel}</span>
      </div>
    </div>
  );
};

const UsageBar: React.FC<{ pct: number; color: string }> = ({ pct, color }) => (
  <div className="h-1.5 bg-[#e8ecf0] rounded-full overflow-hidden w-full">
    <div className="h-full rounded-full transition-all duration-1000" style={{ width: `${Math.min(100, pct)}%`, backgroundColor: color }} />
  </div>
);

const EksOverviewTab: React.FC<EksOverviewTabProps> = ({ eksData }) => {
  const {
    dashboard, clusterHealthScore, healthScoreLabel, healthScoreColor,
    resourceScore, costScore, securityScore, quickWins, potentialSavings, totalPodsCount,
  } = eksData;

  if (!dashboard) return null;

  const totalNodes = dashboard.clusterOverview?.totalNodes || 0;
  const readyNodes = (() => {
    const nodes = dashboard?.nodeMetrics?.nodes || [];
    const unique = nodes.reduce((acc: any[], n: any) => {
      if (!acc.find((x: any) => x.name === n.name)) acc.push(n);
      return acc;
    }, []);
    return unique.filter((n: any) => n.ready).length || totalNodes;
  })();
  const notReadyNodes = totalNodes - readyNodes;
  const spotNodes = dashboard?.karpenter?.spotNodesCount || 0;
  const onDemandNodes = dashboard?.karpenter?.onDemandNodesCount || (totalNodes - spotNodes);
  const runningPods = dashboard?.clusterOverview?.runningPods ||
    (dashboard?.workloadHealth?.pods || []).filter((p: any) => p.phase === 'Running').length;
  const pendingPods = dashboard?.clusterOverview?.pendingPods ||
    (dashboard?.workloadHealth?.pods || []).filter((p: any) => p.phase === 'Pending').length;
  const failedPods = dashboard?.clusterOverview?.failedPods ||
    (dashboard?.workloadHealth?.pods || []).filter((p: any) => p.phase === 'Failed').length;
  const cpuPct = dashboard.clusterOverview?.cpuUsagePercent || 0;
  const memPct = dashboard.clusterOverview?.memoryUsagePercent || 0;
  const cpuMillicores = Math.round(dashboard.clusterOverview?.cpuUsageMillicores || 0);
  const cpuCapMillicores = Math.round(dashboard.clusterOverview?.cpuCapacityMillicores || 0);
  const memGB = ((dashboard.clusterOverview?.memoryUsageBytes || 0) / (1024 ** 3)).toFixed(1);
  const memCapGB = ((dashboard.clusterOverview?.memoryCapacityBytes || 0) / (1024 ** 3)).toFixed(1);
  const dailyCost = dashboard.costMetrics?.dailyCost || 0;
  const monthlyCost = dashboard.costMetrics?.monthlyCost || dailyCost * 30;
  const topCpuPods: any[] = dashboard.topCpuPods || [];
  const topMemoryPods: any[] = dashboard.topMemoryPods || [];
  const namespaceUtilization: any[] = dashboard.namespaceUtilization || [];
  const warningEvents = (dashboard.k8sEvents || []).filter((e: any) => e.type === 'Warning').slice(0, 5);

  const gaugeColor = (score: number) => score >= 70 ? '#2f4bde' : score >= 40 ? '#9a6700' : '#cf222e';
  const cpuGaugeColor = cpuPct >= 80 ? '#cf222e' : cpuPct >= 60 ? '#9a6700' : '#2f4bde';
  const memGaugeColor = memPct >= 80 ? '#cf222e' : memPct >= 60 ? '#9a6700' : '#2f4bde';
  const maxCpuPod = topCpuPods.length > 0 ? Math.max(...topCpuPods.map((p: any) => p.cpuMillicores || 0)) : 1;
  const maxMemPod = topMemoryPods.length > 0 ? Math.max(...topMemoryPods.map((p: any) => p.memoryMB || 0)) : 1;

  return (
    <div className="space-y-5">

      {/* Section 1 — Cluster Intelligence */}
      <div className="bg-white border border-[#d0d7de] rounded-lg overflow-hidden">
        <div className="px-5 py-3.5 border-b border-[#e8ecf0] flex items-center justify-between">
          <span className="text-sm font-semibold text-[#1a1f2e]">Cluster Intelligence</span>
          <span className="text-[11px] text-[#8b949e]">Composite scores across resource, cost and security dimensions</span>
        </div>
        <div className="p-5">
          <div className="grid grid-cols-1 md:grid-cols-4 gap-6 items-center">
            <div className="bg-white border border-[#d0d7de] rounded-lg p-5 flex flex-col items-center">
              <DonutGauge value={clusterHealthScore} size={140} strokeWidth={13}
                arcColor={healthScoreColor || gaugeColor(clusterHealthScore)}
                label="Overall Health" sublabel={healthScoreLabel} valueColor={healthScoreColor || gaugeColor(clusterHealthScore)} />
            </div>
            <div className="bg-white border border-[#d0d7de] rounded-lg p-5 flex flex-col items-center">
              <DonutGauge value={resourceScore} size={140} strokeWidth={13}
                arcColor={gaugeColor(resourceScore)} label="Efficiency Score" sublabel="CPU + Memory balance" valueColor={gaugeColor(resourceScore)} />
              <span className="mt-3 rounded-full px-2.5 py-0.5 text-[11px] font-semibold border"
                style={resourceScore >= 70 ? { color: '#1a7f37', background: '#dafbe1', borderColor: 'rgba(74,194,107,0.4)' }
                  : resourceScore >= 40 ? { color: '#9a6700', background: '#fff8c5', borderColor: 'rgba(212,167,44,0.4)' }
                  : { color: '#cf222e', background: '#ffebe9', borderColor: 'rgba(255,129,130,0.4)' }}>
                Resource Efficiency
              </span>
            </div>
            <div className="bg-white border border-[#d0d7de] rounded-lg p-5 flex flex-col items-center">
              <DonutGauge value={costScore} size={140} strokeWidth={13}
                arcColor={gaugeColor(costScore)} label="Cost Score" sublabel="Spend vs utilization" valueColor={gaugeColor(costScore)} />
              <span className="mt-3 rounded-full px-2.5 py-0.5 text-[11px] font-semibold border"
                style={costScore >= 70 ? { color: '#1a7f37', background: '#dafbe1', borderColor: 'rgba(74,194,107,0.4)' }
                  : costScore >= 40 ? { color: '#9a6700', background: '#fff8c5', borderColor: 'rgba(212,167,44,0.4)' }
                  : { color: '#cf222e', background: '#ffebe9', borderColor: 'rgba(255,129,130,0.4)' }}>
                Cost Optimization
              </span>
            </div>
            <div className="bg-white border border-[#d0d7de] rounded-lg p-5 flex flex-col items-center">
              <DonutGauge value={securityScore} size={140} strokeWidth={13}
                arcColor={gaugeColor(securityScore)} label="Security Score" sublabel="Events + vulnerabilities" valueColor={gaugeColor(securityScore)} />
              <span className="mt-3 rounded-full px-2.5 py-0.5 text-[11px] font-semibold border"
                style={securityScore >= 70 ? { color: '#1a7f37', background: '#dafbe1', borderColor: 'rgba(74,194,107,0.4)' }
                  : securityScore >= 40 ? { color: '#9a6700', background: '#fff8c5', borderColor: 'rgba(212,167,44,0.4)' }
                  : { color: '#cf222e', background: '#ffebe9', borderColor: 'rgba(255,129,130,0.4)' }}>
                Security Posture
              </span>
            </div>
          </div>
        </div>
      </div>

      {/* Section 2 — Cost & Resource Utilization */}
      <div className="grid grid-cols-1 lg:grid-cols-5 gap-5">
        {/* Cost */}
        <div className="lg:col-span-3 bg-white border border-[#d0d7de] rounded-lg overflow-hidden">
          <div className="px-5 py-3.5 border-b border-[#e8ecf0]">
            <span className="text-sm font-semibold text-[#1a1f2e]">Cost &amp; Savings</span>
          </div>
          <div className="p-5 flex flex-col gap-4">
            <div className="flex items-end gap-6">
              <div>
                <div className="text-[11px] font-medium uppercase tracking-wider text-[#8b949e] mb-1">TODAY</div>
                <div className="text-[26px] font-semibold text-[#1a1f2e] leading-none">${dailyCost.toFixed(2)}</div>
                <div className="text-[11px] text-[#8b949e] mt-1">per day</div>
              </div>
              <div>
                <div className="text-[11px] font-medium uppercase tracking-wider text-[#8b949e] mb-1">MONTHLY EST.</div>
                <div className="text-[26px] font-semibold text-[#1a1f2e] leading-none">${monthlyCost.toFixed(0)}</div>
                <div className="text-[11px] text-[#8b949e] mt-1">30-day estimate</div>
              </div>
            </div>

            {potentialSavings.affectedNodes > 0 && (
              <div className="rounded-lg border border-[#e8ecf0] p-4 bg-[#f6f8fa]">
                <div className="text-[11px] font-medium uppercase tracking-wider text-[#8b949e] mb-2">SAVINGS OPPORTUNITY</div>
                <div className="grid grid-cols-3 gap-3">
                  <div>
                    <div className="text-[26px] font-semibold text-[#1a7f37] leading-none">${potentialSavings.daily.toFixed(2)}</div>
                    <div className="text-[11px] text-[#8b949e] mt-1">per day</div>
                  </div>
                  <div>
                    <div className="text-[26px] font-semibold text-[#1a7f37] leading-none">${potentialSavings.monthly.toFixed(0)}</div>
                    <div className="text-[11px] text-[#8b949e] mt-1">per month</div>
                  </div>
                  <div>
                    <div className="text-[26px] font-semibold text-[#1a7f37] leading-none">{potentialSavings.affectedNodes}</div>
                    <div className="text-[11px] text-[#8b949e] mt-1">nodes affected</div>
                  </div>
                </div>
              </div>
            )}

            {(dashboard.costMetrics?.nodeCosts || []).length > 0 && (
              <div>
                <div className="text-[11px] font-medium uppercase tracking-wider text-[#8b949e] mb-2">NODE COST BREAKDOWN</div>
                <div className="space-y-2">
                  {(dashboard.costMetrics.nodeCosts as any[]).slice(0, 4).map((node: any, i: number) => (
                    <div key={i} className="flex items-center gap-3">
                      <div className="text-[11px] text-[#57606a] w-32 truncate font-mono">{node.nodeName?.split('.')[0] || node.instanceType}</div>
                      <div className="flex-1">
                        <div className="h-1.5 bg-[#e8ecf0] rounded-full overflow-hidden">
                          <div className="h-full bg-[#2f4bde] rounded-full transition-all duration-1000"
                            style={{ width: `${Math.min(100, (node.dailyCost / (dailyCost || 1)) * 100)}%` }} />
                        </div>
                      </div>
                      <div className="text-[11px] font-semibold text-[#1a1f2e] w-14 text-right">${node.dailyCost?.toFixed(2)}/d</div>
                    </div>
                  ))}
                </div>
              </div>
            )}
          </div>
        </div>

        {/* Resource Utilization */}
        <div className="lg:col-span-2 bg-white border border-[#d0d7de] rounded-lg overflow-hidden">
          <div className="px-5 py-3.5 border-b border-[#e8ecf0]">
            <span className="text-sm font-semibold text-[#1a1f2e]">Resource Utilization</span>
          </div>
          <div className="p-5 flex flex-col gap-5">
            <div className="flex flex-col items-center gap-2">
              <SmallGauge value={cpuPct} size={110} arcColor={cpuGaugeColor} strokeWidth={11} centerLabel={`${cpuPct.toFixed(0)}%`} />
              <div className="text-center">
                <div className="text-sm font-semibold text-[#1a1f2e]">CPU Utilization</div>
                <div className="text-[11px] text-[#8b949e] mt-0.5">{cpuMillicores}m / {cpuCapMillicores}m millicores</div>
              </div>
              <div className="w-full"><UsageBar pct={cpuPct} color={cpuGaugeColor} /></div>
            </div>
            <div className="border-t border-[#e8ecf0]" />
            <div className="flex flex-col items-center gap-2">
              <SmallGauge value={memPct} size={110} arcColor={memGaugeColor} strokeWidth={11} centerLabel={`${memPct.toFixed(0)}%`} />
              <div className="text-center">
                <div className="text-sm font-semibold text-[#1a1f2e]">Memory Utilization</div>
                <div className="text-[11px] text-[#8b949e] mt-0.5">{memGB} GB / {memCapGB} GB</div>
              </div>
              <div className="w-full"><UsageBar pct={memPct} color={memGaugeColor} /></div>
            </div>
          </div>
        </div>
      </div>

      {/* Section 3 — Node Fleet */}
      <div className="bg-white border border-[#d0d7de] rounded-lg overflow-hidden">
        <div className="px-5 py-3.5 border-b border-[#e8ecf0]">
          <span className="text-sm font-semibold text-[#1a1f2e]">Node Fleet</span>
        </div>
        <div className="p-5">
          <div className="flex flex-col md:flex-row gap-6">
            <div className="flex items-center gap-5 flex-shrink-0">
              <div>
                <div className="text-[26px] font-semibold text-[#1a1f2e] leading-none">{totalNodes}</div>
                <div className="text-[11px] font-medium uppercase tracking-wider text-[#8b949e] mt-1">TOTAL NODES</div>
              </div>
              <div className="flex flex-col gap-2">
                <span className="rounded-full px-2.5 py-0.5 text-[11px] font-semibold border"
                  style={{ color: '#1a7f37', background: '#dafbe1', borderColor: 'rgba(74,194,107,0.4)' }}>
                  {readyNodes} Ready
                </span>
                {notReadyNodes > 0 && (
                  <span className="rounded-full px-2.5 py-0.5 text-[11px] font-semibold border"
                    style={{ color: '#cf222e', background: '#ffebe9', borderColor: 'rgba(255,129,130,0.4)' }}>
                    {notReadyNodes} Not Ready
                  </span>
                )}
              </div>
            </div>

            <div className="hidden md:block w-px bg-[#e8ecf0] self-stretch" />

            <div className="flex flex-col gap-2 flex-shrink-0 justify-center">
              <div className="text-[11px] font-medium uppercase tracking-wider text-[#8b949e]">CAPACITY TYPE</div>
              <div className="flex gap-3">
                {spotNodes > 0 && (
                  <div className="border border-[#d0d7de] rounded-lg px-4 py-3">
                    <div className="text-[26px] font-semibold text-[#1a1f2e] leading-none">{spotNodes}</div>
                    <div className="text-[11px] text-[#8b949e] mt-1">Spot</div>
                  </div>
                )}
                <div className="border border-[#d0d7de] rounded-lg px-4 py-3">
                  <div className="text-[26px] font-semibold text-[#1a1f2e] leading-none">{onDemandNodes}</div>
                  <div className="text-[11px] text-[#8b949e] mt-1">On-Demand</div>
                </div>
              </div>
            </div>

            <div className="hidden md:block w-px bg-[#e8ecf0] self-stretch" />

            <div className="flex flex-col gap-2 justify-center">
              <div className="text-[11px] font-medium uppercase tracking-wider text-[#8b949e]">POD DISTRIBUTION</div>
              <div className="flex gap-3 flex-wrap">
                <div className="border border-[#d0d7de] rounded-lg px-4 py-3">
                  <div className="text-[26px] font-semibold text-[#1a7f37] leading-none">{runningPods}</div>
                  <div className="text-[11px] text-[#8b949e] mt-1">Running</div>
                </div>
                {pendingPods > 0 && (
                  <div className="border border-[#d0d7de] rounded-lg px-4 py-3">
                    <div className="text-[26px] font-semibold text-[#9a6700] leading-none">{pendingPods}</div>
                    <div className="text-[11px] text-[#8b949e] mt-1">Pending</div>
                  </div>
                )}
                {failedPods > 0 && (
                  <div className="border border-[#d0d7de] rounded-lg px-4 py-3">
                    <div className="text-[26px] font-semibold text-[#cf222e] leading-none">{failedPods}</div>
                    <div className="text-[11px] text-[#8b949e] mt-1">Failed</div>
                  </div>
                )}
                <div className="border border-[#d0d7de] rounded-lg px-4 py-3">
                  <div className="text-[26px] font-semibold text-[#1a1f2e] leading-none">{totalPodsCount}</div>
                  <div className="text-[11px] text-[#8b949e] mt-1">Total</div>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>

      {/* Section 4 — Top Resource Consumers */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-5">
        <div className="bg-white border border-[#d0d7de] rounded-lg overflow-hidden">
          <div className="px-5 py-3.5 border-b border-[#e8ecf0]">
            <span className="text-sm font-semibold text-[#1a1f2e]">Top CPU Pods</span>
          </div>
          <div className="p-5">
            {topCpuPods.length === 0 ? (
              <div className="py-10 text-center">
                <div className="text-[13px] text-[#57606a]">No CPU metrics yet — data appears after first agent push</div>
              </div>
            ) : (
              <div className="space-y-3">
                {topCpuPods.slice(0, 8).map((pod: any, i: number) => {
                  const barPct = Math.min(100, ((pod.cpuMillicores || 0) / maxCpuPod) * 100);
                  return (
                    <div key={i} className="flex items-center gap-3">
                      <div className="flex-1 min-w-0">
                        <div className="flex items-center gap-2 mb-0.5">
                          <span className="text-[13px] font-mono font-semibold text-[#1a1f2e] truncate max-w-[140px]">{pod.name}</span>
                          <span className="text-[11px] text-[#8b949e] truncate max-w-[80px]">{pod.namespace}</span>
                        </div>
                        <div className="h-1.5 bg-[#e8ecf0] rounded-full overflow-hidden">
                          <div className="h-full rounded-full bg-[#2f4bde] transition-all duration-1000" style={{ width: `${barPct}%` }} />
                        </div>
                      </div>
                      <div className="text-[11px] font-semibold text-[#1a1f2e] w-14 text-right flex-shrink-0">{pod.cpuMillicores}m</div>
                    </div>
                  );
                })}
              </div>
            )}
          </div>
        </div>

        <div className="bg-white border border-[#d0d7de] rounded-lg overflow-hidden">
          <div className="px-5 py-3.5 border-b border-[#e8ecf0]">
            <span className="text-sm font-semibold text-[#1a1f2e]">Top Memory Pods</span>
          </div>
          <div className="p-5">
            {topMemoryPods.length === 0 ? (
              <div className="py-10 text-center">
                <div className="text-[13px] text-[#57606a]">No memory metrics yet — data appears after first agent push</div>
              </div>
            ) : (
              <div className="space-y-3">
                {topMemoryPods.slice(0, 8).map((pod: any, i: number) => {
                  const barPct = Math.min(100, ((pod.memoryMB || 0) / maxMemPod) * 100);
                  const displayMem = (pod.memoryMB || 0) >= 1024
                    ? `${((pod.memoryMB || 0) / 1024).toFixed(1)} GB`
                    : `${Math.round(pod.memoryMB || 0)} MB`;
                  return (
                    <div key={i} className="flex items-center gap-3">
                      <div className="flex-1 min-w-0">
                        <div className="flex items-center gap-2 mb-0.5">
                          <span className="text-[13px] font-mono font-semibold text-[#1a1f2e] truncate max-w-[140px]">{pod.name}</span>
                          <span className="text-[11px] text-[#8b949e] truncate max-w-[80px]">{pod.namespace}</span>
                        </div>
                        <div className="h-1.5 bg-[#e8ecf0] rounded-full overflow-hidden">
                          <div className="h-full rounded-full bg-[#2f4bde] transition-all duration-1000" style={{ width: `${barPct}%` }} />
                        </div>
                      </div>
                      <div className="text-[11px] font-semibold text-[#1a1f2e] w-16 text-right flex-shrink-0">{displayMem}</div>
                    </div>
                  );
                })}
              </div>
            )}
          </div>
        </div>
      </div>

      {/* Section 5 — Namespace Utilization */}
      <div className="bg-white border border-[#d0d7de] rounded-lg overflow-hidden">
        <div className="px-5 py-3.5 border-b border-[#e8ecf0]">
          <span className="text-sm font-semibold text-[#1a1f2e]">Namespace Utilization</span>
        </div>
        <div className="p-5">
          {namespaceUtilization.length === 0 ? (
            <div className="flex flex-col items-center justify-center py-10 text-center">
              <div className="w-12 h-12 rounded-full bg-[#f6f8fa] border border-[#d0d7de] flex items-center justify-center mb-3">
                <i className="fas fa-satellite-dish text-[#8b949e] text-lg"></i>
              </div>
              <div className="text-[13px] font-semibold text-[#57606a]">No namespace data yet</div>
              <div className="text-[11px] text-[#8b949e] mt-1">
                Data appears after first agent push with <span className="font-mono bg-[#f6f8fa] px-1 rounded border border-[#d0d7de]">COLLECTOR_PROMETHEUS_METRICS=true</span>
              </div>
            </div>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full">
                <thead>
                  <tr className="bg-[#f6f8fa] border-b border-[#d0d7de]">
                    <th className="px-5 py-2 text-left text-[11px] font-semibold uppercase tracking-wider text-[#8b949e]">Namespace</th>
                    <th className="px-5 py-2 text-left text-[11px] font-semibold uppercase tracking-wider text-[#8b949e] w-48">CPU Usage</th>
                    <th className="px-5 py-2 text-left text-[11px] font-semibold uppercase tracking-wider text-[#8b949e] w-48">Memory Usage</th>
                    <th className="px-5 py-2 text-center text-[11px] font-semibold uppercase tracking-wider text-[#8b949e] w-20">Pods</th>
                  </tr>
                </thead>
                <tbody>
                  {namespaceUtilization.map((ns: any, i: number) => {
                    const cpuNs = ns.cpuUsagePct || 0;
                    const memNs = ns.memUsagePct || 0;
                    return (
                      <tr key={i} className="border-b border-[#e8ecf0] hover:bg-[#f6f8fa] transition-colors">
                        <td className="px-5 py-2.5">
                          <span className="text-[13px] font-mono font-semibold text-[#1a1f2e]">{ns.namespace}</span>
                        </td>
                        <td className="px-5 py-2.5">
                          <div className="flex items-center gap-2">
                            <div className="flex-1 h-1.5 bg-[#e8ecf0] rounded-full overflow-hidden">
                              <div className="h-full rounded-full bg-[#2f4bde] transition-all" style={{ width: `${Math.min(100, cpuNs)}%` }} />
                            </div>
                            <span className="text-[11px] font-semibold w-10 text-right"
                              style={{ color: cpuNs > 80 ? '#cf222e' : cpuNs > 60 ? '#9a6700' : '#1a1f2e' }}>
                              {cpuNs.toFixed(0)}%
                            </span>
                          </div>
                        </td>
                        <td className="px-5 py-2.5">
                          <div className="flex items-center gap-2">
                            <div className="flex-1 h-1.5 bg-[#e8ecf0] rounded-full overflow-hidden">
                              <div className="h-full rounded-full bg-[#2f4bde] transition-all" style={{ width: `${Math.min(100, memNs)}%` }} />
                            </div>
                            <span className="text-[11px] font-semibold w-10 text-right"
                              style={{ color: memNs > 80 ? '#cf222e' : memNs > 60 ? '#9a6700' : '#1a1f2e' }}>
                              {memNs.toFixed(0)}%
                            </span>
                          </div>
                        </td>
                        <td className="px-5 py-2.5 text-center">
                          <span className="text-[13px] font-semibold text-[#1a1f2e]">{ns.podCount || 0}</span>
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          )}
        </div>
      </div>

      {/* Section 6 — Quick Wins */}
      {quickWins && quickWins.length > 0 && (
        <div className="bg-white border border-[#d0d7de] rounded-lg overflow-hidden">
          <div className="px-5 py-3.5 border-b border-[#e8ecf0] flex items-center justify-between">
            <span className="text-sm font-semibold text-[#1a1f2e]">Quick Wins</span>
            <span className="rounded-full px-2.5 py-0.5 text-[11px] font-semibold border"
              style={{ color: '#9a6700', background: '#fff8c5', borderColor: 'rgba(212,167,44,0.4)' }}>
              {quickWins.length} action{quickWins.length !== 1 ? 's' : ''}
            </span>
          </div>
          <div className="p-5 space-y-3">
            {quickWins.map((win: QuickWin, index: number) => {
              const severityStyle =
                win.type === 'security' ? { color: '#cf222e', bg: '#ffebe9', border: 'rgba(255,129,130,0.4)' }
                : win.type === 'cost' ? { color: '#9a6700', bg: '#fff8c5', border: 'rgba(212,167,44,0.4)' }
                : { color: '#0550ae', bg: '#ddf4ff', border: 'rgba(84,174,255,0.4)' };
              return (
                <div key={index} className="flex items-start gap-4 p-4 border border-[#d0d7de] rounded-lg hover:bg-[#f6f8fa] transition-colors">
                  <div className="w-9 h-9 rounded-lg border border-[#d0d7de] flex items-center justify-center flex-shrink-0 bg-[#f6f8fa]">
                    <i className={`fas ${win.icon} text-[#57606a] text-sm`}></i>
                  </div>
                  <div className="flex-1 min-w-0">
                    <div className="flex items-center gap-2 mb-1 flex-wrap">
                      <h4 className="text-sm font-semibold text-[#1a1f2e]">{win.title}</h4>
                      <span className="rounded-full px-2.5 py-0.5 text-[11px] font-semibold border"
                        style={{ color: severityStyle.color, background: severityStyle.bg, borderColor: severityStyle.border }}>
                        {win.type}
                      </span>
                    </div>
                    <p className="text-[11px] text-[#57606a] mb-3 leading-relaxed">{win.impact}</p>
                    <div className="flex gap-2 flex-wrap">
                      {win.actions.map((action: string, i: number) => (
                        <button key={i}
                          className={`px-3 py-1.5 rounded-lg text-[12px] font-semibold transition-all ${i === 0
                            ? 'bg-[#2f4bde] text-white hover:bg-[#1e3ac8]'
                            : 'bg-white text-[#57606a] hover:bg-[#f6f8fa] border border-[#d0d7de]'}`}>
                          {action}
                        </button>
                      ))}
                    </div>
                  </div>
                  <div className="flex-shrink-0 flex items-center justify-center w-6 h-6 rounded-full bg-[#f6f8fa] border border-[#d0d7de] text-[11px] font-semibold text-[#57606a]">
                    {index + 1}
                  </div>
                </div>
              );
            })}
          </div>
        </div>
      )}

      {/* Section 7 — Cluster Events */}
      {warningEvents.length > 0 && (
        <div className="bg-white border border-[#d0d7de] rounded-lg overflow-hidden">
          <div className="px-5 py-3.5 border-b border-[#e8ecf0] flex items-center justify-between">
            <span className="text-sm font-semibold text-[#1a1f2e]">Cluster Events</span>
            <span className="rounded-full px-2.5 py-0.5 text-[11px] font-semibold border"
              style={{ color: '#cf222e', background: '#ffebe9', borderColor: 'rgba(255,129,130,0.4)' }}>
              {warningEvents.length} warning{warningEvents.length !== 1 ? 's' : ''}
            </span>
          </div>
          <div className="p-5 space-y-2">
            {warningEvents.map((event: any, i: number) => (
              <div key={i} className="flex items-start gap-3 p-3 rounded-lg border border-[#e8ecf0] hover:bg-[#f6f8fa] transition-colors">
                <div className="flex-shrink-0 mt-0.5">
                  <i className="fas fa-circle-exclamation text-[#cf222e] text-sm"></i>
                </div>
                <div className="flex-1 min-w-0">
                  <div className="flex items-center gap-2 mb-0.5 flex-wrap">
                    <span className="text-[11px] font-semibold text-[#cf222e] font-mono">{event.reason || 'Warning'}</span>
                    {event.involvedObject?.name && (
                      <span className="text-[11px] text-[#57606a] truncate max-w-[200px] font-mono">{event.involvedObject.name}</span>
                    )}
                    {event.namespace && (
                      <span className="rounded-full px-2.5 py-0.5 text-[11px] font-semibold border"
                        style={{ color: '#cf222e', background: '#ffebe9', borderColor: 'rgba(255,129,130,0.4)' }}>
                        {event.namespace}
                      </span>
                    )}
                    {(event.count || 0) > 1 && (
                      <span className="text-[11px] font-semibold text-[#57606a]">×{event.count}</span>
                    )}
                  </div>
                  <p className="text-[11px] text-[#57606a] leading-snug line-clamp-2">{event.message}</p>
                </div>
              </div>
            ))}
          </div>
        </div>
      )}

    </div>
  );
};

export default EksOverviewTab;
