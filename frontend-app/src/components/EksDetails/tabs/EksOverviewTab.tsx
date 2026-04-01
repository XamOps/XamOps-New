/**
 * EKS Overview Tab
 *
 * Cast.ai / ScaleOps premium dashboard with score gauges, cost insights,
 * node fleet, top consumers, namespace utilization, recommendations and events.
 */

import React from 'react';
import { QuickWin } from '../../../types/eksTypes';

interface EksOverviewTabProps {
  eksData: any;
}

// ── Inline SVG donut gauge (no external library) ─────────────────────────────
interface DonutGaugeProps {
  value: number;       // 0-100
  size?: number;       // diameter px
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
  trackColor = '#e5e7eb',
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
        <svg
          width={size}
          height={size}
          viewBox={`0 0 ${size} ${size}`}
          style={{ transform: 'rotate(-90deg)' }}
        >
          <circle cx={cx} cy={cy} r={r} fill="none" stroke={trackColor} strokeWidth={strokeWidth} />
          <circle
            cx={cx}
            cy={cy}
            r={r}
            fill="none"
            stroke={arcColor}
            strokeWidth={strokeWidth}
            strokeLinecap="round"
            strokeDasharray={dashArray}
            style={{ transition: 'stroke-dasharray 1s ease' }}
          />
        </svg>
        <div className="absolute inset-0 flex flex-col items-center justify-center">
          <span className="text-2xl font-black leading-none" style={{ color: textColor }}>
            {Math.round(filled)}
          </span>
          <span className="text-xs text-gray-400 font-medium leading-none mt-0.5">/100</span>
        </div>
      </div>
      <div className="text-center mt-2">
        <div className="text-sm font-bold text-gray-800">{label}</div>
        {sublabel && <div className="text-xs text-gray-500 mt-0.5">{sublabel}</div>}
      </div>
    </div>
  );
};

// ── Small inline gauge for CPU/Memory ────────────────────────────────────────
interface SmallGaugeProps {
  value: number;
  size?: number;
  arcColor: string;
  trackColor?: string;
  strokeWidth?: number;
  centerLabel: string;
}

const SmallGauge: React.FC<SmallGaugeProps> = ({
  value,
  size = 100,
  arcColor,
  trackColor = '#e5e7eb',
  strokeWidth = 10,
  centerLabel,
}) => {
  const r = (size - strokeWidth) / 2;
  const circ = 2 * Math.PI * r;
  const filled = Math.min(100, Math.max(0, value));
  const dashArray = `${(filled / 100) * circ} ${circ}`;
  const cx = size / 2;
  const cy = size / 2;

  return (
    <div className="relative inline-flex" style={{ width: size, height: size }}>
      <svg
        width={size}
        height={size}
        viewBox={`0 0 ${size} ${size}`}
        style={{ transform: 'rotate(-90deg)' }}
      >
        <circle cx={cx} cy={cy} r={r} fill="none" stroke={trackColor} strokeWidth={strokeWidth} />
        <circle
          cx={cx}
          cy={cy}
          r={r}
          fill="none"
          stroke={arcColor}
          strokeWidth={strokeWidth}
          strokeLinecap="round"
          strokeDasharray={dashArray}
          style={{ transition: 'stroke-dasharray 1s ease' }}
        />
      </svg>
      <div className="absolute inset-0 flex items-center justify-center">
        <span className="text-sm font-black" style={{ color: arcColor }}>{centerLabel}</span>
      </div>
    </div>
  );
};

// ── Horizontal usage bar ──────────────────────────────────────────────────────
interface UsageBarProps {
  pct: number;
  colorClass: string;
}

const UsageBar: React.FC<UsageBarProps> = ({ pct, colorClass }) => (
  <div className="w-full h-2 bg-gray-100 rounded-full overflow-hidden">
    <div
      className={`h-full rounded-full bg-gradient-to-r transition-all duration-1000 ${colorClass}`}
      style={{ width: `${Math.min(100, pct)}%` }}
    />
  </div>
);

// ── Main component ────────────────────────────────────────────────────────────
const EksOverviewTab: React.FC<EksOverviewTabProps> = ({ eksData }) => {
  const {
    dashboard,
    clusterHealthScore,
    healthScoreLabel,
    healthScoreColor,
    resourceScore,
    costScore,
    securityScore,
    quickWins,
    potentialSavings,
    totalPodsCount,
  } = eksData;

  if (!dashboard) return null;

  // ── Derived data ─────────────────────────────────────────────────────────

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

  const warningEvents = (dashboard.k8sEvents || [])
    .filter((e: any) => e.type === 'Warning')
    .slice(0, 5);

  // Score gauge colors
  const efficiencyArcColor = resourceScore >= 70 ? '#10b981' : resourceScore >= 40 ? '#f59e0b' : '#ef4444';
  const costArcColor = costScore >= 70 ? '#3b82f6' : costScore >= 40 ? '#f59e0b' : '#ef4444';
  const securityArcColor = securityScore >= 80 ? '#10b981' : securityScore >= 50 ? '#f59e0b' : '#ef4444';

  const cpuGaugeColor = cpuPct >= 80 ? '#ef4444' : cpuPct >= 60 ? '#f59e0b' : '#f97316';
  const memGaugeColor = memPct >= 80 ? '#ef4444' : memPct >= 60 ? '#8b5cf6' : '#3b82f6';

  const maxCpuPod = topCpuPods.length > 0 ? Math.max(...topCpuPods.map((p: any) => p.cpuMillicores || 0)) : 1;
  const maxMemPod = topMemoryPods.length > 0 ? Math.max(...topMemoryPods.map((p: any) => p.memoryMB || 0)) : 1;

  return (
    <div className="space-y-6">

      {/* ══════════════════════════════════════════════════════════════════════
          Section 1 — Cluster Intelligence (score gauges)
      ══════════════════════════════════════════════════════════════════════ */}
      <section className="bg-white rounded-2xl shadow-sm border border-gray-100 p-6">
        <div className="flex items-center gap-3 mb-6">
          <div className="w-9 h-9 rounded-xl bg-gradient-to-br from-indigo-500 to-purple-600 flex items-center justify-center shadow-sm">
            <i className="fas fa-brain text-white text-sm"></i>
          </div>
          <div>
            <h3 className="text-lg font-bold text-gray-900">Cluster Intelligence</h3>
            <p className="text-xs text-gray-500">Composite scores across resource, cost and security dimensions</p>
          </div>
        </div>

        <div className="grid grid-cols-1 md:grid-cols-4 gap-6 items-center">
          {/* Overall */}
          <div className={`flex flex-col items-center p-6 rounded-2xl border-2 ${
            clusterHealthScore >= 80 ? 'border-emerald-200 bg-emerald-50/40'
            : clusterHealthScore >= 60 ? 'border-blue-200 bg-blue-50/40'
            : clusterHealthScore >= 40 ? 'border-amber-200 bg-amber-50/40'
            : 'border-red-200 bg-red-50/40'
          }`}>
            <DonutGauge
              value={clusterHealthScore}
              size={140}
              strokeWidth={13}
              arcColor={healthScoreColor}
              label="Overall Health"
              sublabel={healthScoreLabel}
              valueColor={healthScoreColor}
            />
          </div>

          {/* Efficiency Score */}
          <div className="flex flex-col items-center p-6 rounded-2xl border-2 border-emerald-100 bg-emerald-50/30 hover:border-emerald-300 transition-colors">
            <DonutGauge
              value={resourceScore}
              size={140}
              strokeWidth={13}
              arcColor={efficiencyArcColor}
              label="Efficiency Score"
              sublabel="CPU + Memory balance"
              valueColor={efficiencyArcColor}
            />
            <div className={`mt-3 text-xs font-bold px-3 py-1 rounded-full ${
              resourceScore >= 70 ? 'bg-emerald-100 text-emerald-700' : resourceScore >= 40 ? 'bg-amber-100 text-amber-700' : 'bg-red-100 text-red-700'
            }`}>
              Resource Efficiency
            </div>
          </div>

          {/* Cost Score */}
          <div className="flex flex-col items-center p-6 rounded-2xl border-2 border-blue-100 bg-blue-50/30 hover:border-blue-300 transition-colors">
            <DonutGauge
              value={costScore}
              size={140}
              strokeWidth={13}
              arcColor={costArcColor}
              label="Cost Score"
              sublabel="Spend vs utilization"
              valueColor={costArcColor}
            />
            <div className={`mt-3 text-xs font-bold px-3 py-1 rounded-full ${
              costScore >= 70 ? 'bg-blue-100 text-blue-700' : costScore >= 40 ? 'bg-amber-100 text-amber-700' : 'bg-red-100 text-red-700'
            }`}>
              Cost Optimization
            </div>
          </div>

          {/* Security Score */}
          <div className="flex flex-col items-center p-6 rounded-2xl border-2 border-rose-100 bg-rose-50/30 hover:border-rose-300 transition-colors">
            <DonutGauge
              value={securityScore}
              size={140}
              strokeWidth={13}
              arcColor={securityArcColor}
              label="Security Score"
              sublabel="Events + vulnerabilities"
              valueColor={securityArcColor}
            />
            <div className={`mt-3 text-xs font-bold px-3 py-1 rounded-full ${
              securityScore >= 80 ? 'bg-emerald-100 text-emerald-700' : securityScore >= 50 ? 'bg-amber-100 text-amber-700' : 'bg-red-100 text-red-700'
            }`}>
              Security Posture
            </div>
          </div>
        </div>
      </section>

      {/* ══════════════════════════════════════════════════════════════════════
          Section 2 — Cost & Savings | Resource Utilization (60/40)
      ══════════════════════════════════════════════════════════════════════ */}
      <div className="grid grid-cols-1 lg:grid-cols-5 gap-6">

        {/* Left 60% — Cost & Savings */}
        <div className="lg:col-span-3 bg-white rounded-2xl shadow-sm border border-gray-100 p-6 flex flex-col gap-5">
          <div className="flex items-center gap-3">
            <div className="w-9 h-9 rounded-xl bg-gradient-to-br from-emerald-500 to-teal-600 flex items-center justify-center shadow-sm">
              <i className="fas fa-dollar-sign text-white text-sm"></i>
            </div>
            <h3 className="text-lg font-bold text-gray-900">Cost &amp; Savings</h3>
          </div>

          {/* Big cost numbers */}
          <div className="flex items-end gap-6">
            <div>
              <div className="text-xs font-semibold text-gray-400 uppercase tracking-wider mb-1">Today</div>
              <div className="text-5xl font-black text-gray-900 leading-none">
                <span className="text-2xl font-bold text-gray-400 mr-0.5">$</span>{dailyCost.toFixed(2)}
              </div>
            </div>
            <div className="pb-1">
              <div className="text-xs font-semibold text-gray-400 uppercase tracking-wider mb-1">Monthly Est.</div>
              <div className="text-2xl font-bold text-gray-600">${monthlyCost.toFixed(2)}</div>
            </div>
          </div>

          {/* Savings opportunity */}
          {potentialSavings.affectedNodes > 0 && (
            <div className="rounded-xl bg-emerald-50 border border-emerald-200 p-4">
              <div className="flex items-center gap-2 mb-3">
                <i className="fas fa-leaf text-emerald-600 text-sm"></i>
                <span className="text-sm font-bold text-emerald-800">Savings Opportunity</span>
              </div>
              <div className="grid grid-cols-3 gap-3">
                <div>
                  <div className="text-xl font-black text-emerald-700">${potentialSavings.daily.toFixed(2)}</div>
                  <div className="text-xs text-emerald-600 font-medium">per day</div>
                </div>
                <div>
                  <div className="text-xl font-black text-emerald-700">${potentialSavings.monthly.toFixed(2)}</div>
                  <div className="text-xs text-emerald-600 font-medium">per month</div>
                </div>
                <div>
                  <div className="text-xl font-black text-emerald-700">{potentialSavings.affectedNodes}</div>
                  <div className="text-xs text-emerald-600 font-medium">nodes affected</div>
                </div>
              </div>
            </div>
          )}

          {/* Spot savings row */}
          {spotNodes > 0 && (
            <div className="flex items-center gap-3 rounded-xl bg-amber-50 border border-amber-200 px-4 py-3">
              <span className="text-xl">⚡</span>
              <div>
                <div className="text-sm font-bold text-amber-800">
                  {spotNodes} spot nodes saving ~${(spotNodes * dailyCost / Math.max(1, totalNodes) * 0.7).toFixed(2)}/day
                </div>
                <div className="text-xs text-amber-600">vs. equivalent on-demand pricing</div>
              </div>
            </div>
          )}

          {/* Node cost breakdown — top 4 nodes */}
          {(dashboard.costMetrics?.nodeCosts || []).length > 0 && (
            <div>
              <div className="text-xs font-bold text-gray-500 uppercase tracking-wider mb-2">Node Cost Breakdown</div>
              <div className="space-y-2">
                {(dashboard.costMetrics.nodeCosts as any[]).slice(0, 4).map((node: any, i: number) => (
                  <div key={i} className="flex items-center gap-3">
                    <div className="text-xs text-gray-500 w-32 truncate font-mono">{node.nodeName?.split('.')[0] || node.instanceType}</div>
                    <div className="flex-1">
                      <div className="w-full h-1.5 bg-gray-100 rounded-full overflow-hidden">
                        <div
                          className="h-full bg-gradient-to-r from-indigo-400 to-purple-500 rounded-full transition-all duration-1000"
                          style={{ width: `${Math.min(100, (node.dailyCost / (dailyCost || 1)) * 100)}%` }}
                        />
                      </div>
                    </div>
                    <div className="text-xs font-bold text-gray-700 w-14 text-right">${node.dailyCost?.toFixed(2)}/d</div>
                  </div>
                ))}
              </div>
            </div>
          )}
        </div>

        {/* Right 40% — Resource Utilization */}
        <div className="lg:col-span-2 bg-white rounded-2xl shadow-sm border border-gray-100 p-6 flex flex-col gap-5">
          <div className="flex items-center gap-3">
            <div className="w-9 h-9 rounded-xl bg-gradient-to-br from-orange-500 to-amber-500 flex items-center justify-center shadow-sm">
              <i className="fas fa-tachometer-alt text-white text-sm"></i>
            </div>
            <h3 className="text-lg font-bold text-gray-900">Resource Utilization</h3>
          </div>

          {/* CPU gauge */}
          <div className="flex flex-col items-center gap-2">
            <SmallGauge
              value={cpuPct}
              size={110}
              arcColor={cpuGaugeColor}
              strokeWidth={11}
              centerLabel={`${cpuPct.toFixed(0)}%`}
            />
            <div className="text-center">
              <div className="text-sm font-bold text-gray-800">CPU Utilization</div>
              <div className="text-xs text-gray-500 mt-0.5">{cpuMillicores}m / {cpuCapMillicores}m millicores</div>
            </div>
            <div className="w-full">
              <UsageBar pct={cpuPct} colorClass={cpuPct >= 80 ? 'from-red-500 to-red-600' : cpuPct >= 60 ? 'from-amber-400 to-orange-500' : 'from-orange-400 to-amber-400'} />
            </div>
          </div>

          <div className="border-t border-gray-100" />

          {/* Memory gauge */}
          <div className="flex flex-col items-center gap-2">
            <SmallGauge
              value={memPct}
              size={110}
              arcColor={memGaugeColor}
              strokeWidth={11}
              centerLabel={`${memPct.toFixed(0)}%`}
            />
            <div className="text-center">
              <div className="text-sm font-bold text-gray-800">Memory Utilization</div>
              <div className="text-xs text-gray-500 mt-0.5">{memGB} GB / {memCapGB} GB</div>
            </div>
            <div className="w-full">
              <UsageBar pct={memPct} colorClass={memPct >= 80 ? 'from-red-500 to-red-600' : memPct >= 60 ? 'from-violet-400 to-purple-600' : 'from-blue-400 to-indigo-500'} />
            </div>
          </div>
        </div>
      </div>

      {/* ══════════════════════════════════════════════════════════════════════
          Section 3 — Node Fleet
      ══════════════════════════════════════════════════════════════════════ */}
      <section className="bg-white rounded-2xl shadow-sm border border-gray-100 p-6">
        <div className="flex items-center gap-3 mb-5">
          <div className="w-9 h-9 rounded-xl bg-gradient-to-br from-blue-600 to-indigo-700 flex items-center justify-center shadow-sm">
            <i className="fas fa-server text-white text-sm"></i>
          </div>
          <h3 className="text-lg font-bold text-gray-900">Node Fleet</h3>
        </div>

        <div className="flex flex-col md:flex-row gap-6">
          {/* Total nodes + ready/not */}
          <div className="flex items-center gap-5 flex-shrink-0">
            <div>
              <div className="text-6xl font-black text-gray-900 leading-none">{totalNodes}</div>
              <div className="text-xs font-semibold text-gray-500 uppercase tracking-wide mt-1">Total Nodes</div>
            </div>
            <div className="flex flex-col gap-2">
              <div className="flex items-center gap-2 px-3 py-1.5 bg-emerald-50 border border-emerald-200 rounded-full">
                <div className="w-2 h-2 rounded-full bg-emerald-500"></div>
                <span className="text-sm font-bold text-emerald-700">{readyNodes} Ready</span>
              </div>
              {notReadyNodes > 0 && (
                <div className="flex items-center gap-2 px-3 py-1.5 bg-red-50 border border-red-200 rounded-full">
                  <div className="w-2 h-2 rounded-full bg-red-500"></div>
                  <span className="text-sm font-bold text-red-700">{notReadyNodes} Not Ready</span>
                </div>
              )}
            </div>
          </div>

          <div className="hidden md:block w-px bg-gray-100 self-stretch" />

          {/* Spot / On-Demand split */}
          <div className="flex flex-col gap-3 flex-shrink-0 justify-center">
            <div className="text-xs font-bold text-gray-500 uppercase tracking-wider">Capacity Type</div>
            <div className="flex gap-3">
              {spotNodes > 0 && (
                <div className="flex items-center gap-2 px-4 py-3 rounded-xl bg-amber-50 border border-amber-200">
                  <span className="text-xl">⚡</span>
                  <div>
                    <div className="text-xl font-black text-amber-700">{spotNodes}</div>
                    <div className="text-xs font-semibold text-amber-600">Spot</div>
                  </div>
                </div>
              )}
              <div className="flex items-center gap-2 px-4 py-3 rounded-xl bg-indigo-50 border border-indigo-200">
                <span className="text-xl">💎</span>
                <div>
                  <div className="text-xl font-black text-indigo-700">{onDemandNodes}</div>
                  <div className="text-xs font-semibold text-indigo-600">On-Demand</div>
                </div>
              </div>
            </div>
          </div>

          <div className="hidden md:block w-px bg-gray-100 self-stretch" />

          {/* Pod distribution */}
          <div className="flex flex-col gap-3 justify-center">
            <div className="text-xs font-bold text-gray-500 uppercase tracking-wider">Pod Distribution</div>
            <div className="flex gap-3 flex-wrap">
              <div className="flex items-center gap-2 px-4 py-3 rounded-xl bg-emerald-50 border border-emerald-200">
                <div className="w-2.5 h-2.5 rounded-full bg-emerald-500"></div>
                <div>
                  <div className="text-xl font-black text-emerald-700">{runningPods}</div>
                  <div className="text-xs font-semibold text-emerald-600">Running</div>
                </div>
              </div>
              {pendingPods > 0 && (
                <div className="flex items-center gap-2 px-4 py-3 rounded-xl bg-amber-50 border border-amber-200">
                  <div className="w-2.5 h-2.5 rounded-full bg-amber-500"></div>
                  <div>
                    <div className="text-xl font-black text-amber-700">{pendingPods}</div>
                    <div className="text-xs font-semibold text-amber-600">Pending</div>
                  </div>
                </div>
              )}
              {failedPods > 0 && (
                <div className="flex items-center gap-2 px-4 py-3 rounded-xl bg-red-50 border border-red-200">
                  <div className="w-2.5 h-2.5 rounded-full bg-red-500"></div>
                  <div>
                    <div className="text-xl font-black text-red-700">{failedPods}</div>
                    <div className="text-xs font-semibold text-red-600">Failed</div>
                  </div>
                </div>
              )}
              <div className="flex items-center gap-2 px-4 py-3 rounded-xl bg-gray-50 border border-gray-200">
                <div className="w-2.5 h-2.5 rounded-full bg-gray-400"></div>
                <div>
                  <div className="text-xl font-black text-gray-700">{totalPodsCount}</div>
                  <div className="text-xs font-semibold text-gray-500">Total</div>
                </div>
              </div>
            </div>
          </div>
        </div>
      </section>

      {/* ══════════════════════════════════════════════════════════════════════
          Section 4 — Top Resource Consumers (side by side)
      ══════════════════════════════════════════════════════════════════════ */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">

        {/* Top CPU Pods */}
        <section className="bg-white rounded-2xl shadow-sm border border-gray-100 p-6">
          <div className="flex items-center gap-3 mb-4">
            <div className="w-8 h-8 rounded-xl bg-orange-100 flex items-center justify-center">
              <i className="fas fa-microchip text-orange-600 text-sm"></i>
            </div>
            <h3 className="text-base font-bold text-gray-900">Top CPU Pods</h3>
          </div>
          {topCpuPods.length === 0 ? (
            <div className="text-xs text-gray-400 italic py-4 text-center">
              No CPU metrics yet — data appears after first agent push
            </div>
          ) : (
            <div className="space-y-3">
              {topCpuPods.slice(0, 8).map((pod: any, i: number) => {
                const barPct = Math.min(100, ((pod.cpuMillicores || 0) / maxCpuPod) * 100);
                const barColor = barPct > 75 ? 'from-red-400 to-red-600' : barPct > 50 ? 'from-amber-400 to-orange-500' : 'from-orange-400 to-amber-400';
                return (
                  <div key={i} className="flex items-center gap-3">
                    <div className="flex-1 min-w-0">
                      <div className="flex items-center gap-2 mb-0.5">
                        <span className="text-xs font-mono font-semibold text-gray-800 truncate max-w-[140px]">{pod.name}</span>
                        <span className="text-xs text-gray-400 truncate max-w-[80px]">{pod.namespace}</span>
                      </div>
                      <div className="w-full h-1.5 bg-gray-100 rounded-full overflow-hidden">
                        <div
                          className={`h-full rounded-full bg-gradient-to-r transition-all duration-1000 ${barColor}`}
                          style={{ width: `${barPct}%` }}
                        />
                      </div>
                    </div>
                    <div className="text-xs font-bold text-orange-700 w-14 text-right flex-shrink-0">
                      {pod.cpuMillicores}m
                    </div>
                  </div>
                );
              })}
            </div>
          )}
        </section>

        {/* Top Memory Pods */}
        <section className="bg-white rounded-2xl shadow-sm border border-gray-100 p-6">
          <div className="flex items-center gap-3 mb-4">
            <div className="w-8 h-8 rounded-xl bg-violet-100 flex items-center justify-center">
              <i className="fas fa-memory text-violet-600 text-sm"></i>
            </div>
            <h3 className="text-base font-bold text-gray-900">Top Memory Pods</h3>
          </div>
          {topMemoryPods.length === 0 ? (
            <div className="text-xs text-gray-400 italic py-4 text-center">
              No memory metrics yet — data appears after first agent push
            </div>
          ) : (
            <div className="space-y-3">
              {topMemoryPods.slice(0, 8).map((pod: any, i: number) => {
                const barPct = Math.min(100, ((pod.memoryMB || 0) / maxMemPod) * 100);
                const barColor = barPct > 75 ? 'from-red-400 to-red-600' : barPct > 50 ? 'from-violet-400 to-purple-600' : 'from-blue-400 to-indigo-500';
                const displayMem = (pod.memoryMB || 0) >= 1024
                  ? `${((pod.memoryMB || 0) / 1024).toFixed(1)} GB`
                  : `${Math.round(pod.memoryMB || 0)} MB`;
                return (
                  <div key={i} className="flex items-center gap-3">
                    <div className="flex-1 min-w-0">
                      <div className="flex items-center gap-2 mb-0.5">
                        <span className="text-xs font-mono font-semibold text-gray-800 truncate max-w-[140px]">{pod.name}</span>
                        <span className="text-xs text-gray-400 truncate max-w-[80px]">{pod.namespace}</span>
                      </div>
                      <div className="w-full h-1.5 bg-gray-100 rounded-full overflow-hidden">
                        <div
                          className={`h-full rounded-full bg-gradient-to-r transition-all duration-1000 ${barColor}`}
                          style={{ width: `${barPct}%` }}
                        />
                      </div>
                    </div>
                    <div className="text-xs font-bold text-violet-700 w-16 text-right flex-shrink-0">
                      {displayMem}
                    </div>
                  </div>
                );
              })}
            </div>
          )}
        </section>
      </div>

      {/* ══════════════════════════════════════════════════════════════════════
          Section 5 — Namespace Utilization (KubeCost style)
      ══════════════════════════════════════════════════════════════════════ */}
      <section className="bg-white rounded-2xl shadow-sm border border-gray-100 p-6">
        <div className="flex items-center gap-3 mb-5">
          <div className="w-9 h-9 rounded-xl bg-gradient-to-br from-teal-500 to-cyan-600 flex items-center justify-center shadow-sm">
            <i className="fas fa-layer-group text-white text-sm"></i>
          </div>
          <h3 className="text-lg font-bold text-gray-900">Namespace Utilization</h3>
        </div>

        {namespaceUtilization.length === 0 ? (
          <div className="flex flex-col items-center justify-center py-10 text-center">
            <div className="w-12 h-12 rounded-full bg-gray-50 border border-gray-200 flex items-center justify-center mb-3">
              <i className="fas fa-satellite-dish text-gray-300 text-lg"></i>
            </div>
            <div className="text-sm font-semibold text-gray-500">No namespace data yet</div>
            <div className="text-xs text-gray-400 mt-1">Data appears after first agent push with <span className="font-mono bg-gray-100 px-1 rounded">COLLECTOR_PROMETHEUS_METRICS=true</span></div>
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-gray-100">
                  <th className="text-left text-xs font-bold text-gray-500 uppercase tracking-wider pb-3 pr-4">Namespace</th>
                  <th className="text-left text-xs font-bold text-gray-500 uppercase tracking-wider pb-3 pr-4 w-48">CPU Usage</th>
                  <th className="text-left text-xs font-bold text-gray-500 uppercase tracking-wider pb-3 pr-4 w-48">Memory Usage</th>
                  <th className="text-center text-xs font-bold text-gray-500 uppercase tracking-wider pb-3 w-20">Pods</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-50">
                {namespaceUtilization.map((ns: any, i: number) => {
                  const cpuNs = ns.cpuUsagePct || 0;
                  const memNs = ns.memUsagePct || 0;
                  return (
                    <tr key={i} className="hover:bg-gray-50/60 transition-colors">
                      <td className="py-3 pr-4">
                        <span className="inline-flex items-center gap-1.5 font-mono text-xs font-semibold text-indigo-700 bg-indigo-50 px-2 py-0.5 rounded-md">
                          <i className="fas fa-layer-group text-indigo-400 text-xs"></i>
                          {ns.namespace}
                        </span>
                      </td>
                      <td className="py-3 pr-4">
                        <div className="flex items-center gap-2">
                          <div className="flex-1 h-1.5 bg-gray-100 rounded-full overflow-hidden">
                            <div
                              className={`h-full rounded-full bg-gradient-to-r transition-all ${
                                cpuNs > 80 ? 'from-red-400 to-red-600' : cpuNs > 60 ? 'from-amber-400 to-orange-500' : 'from-orange-300 to-amber-400'
                              }`}
                              style={{ width: `${Math.min(100, cpuNs)}%` }}
                            />
                          </div>
                          <span className={`text-xs font-bold w-10 text-right ${cpuNs > 80 ? 'text-red-600' : cpuNs > 60 ? 'text-amber-600' : 'text-gray-600'}`}>
                            {cpuNs.toFixed(0)}%
                          </span>
                        </div>
                      </td>
                      <td className="py-3 pr-4">
                        <div className="flex items-center gap-2">
                          <div className="flex-1 h-1.5 bg-gray-100 rounded-full overflow-hidden">
                            <div
                              className={`h-full rounded-full bg-gradient-to-r transition-all ${
                                memNs > 80 ? 'from-red-400 to-red-600' : memNs > 60 ? 'from-violet-400 to-purple-600' : 'from-blue-300 to-indigo-400'
                              }`}
                              style={{ width: `${Math.min(100, memNs)}%` }}
                            />
                          </div>
                          <span className={`text-xs font-bold w-10 text-right ${memNs > 80 ? 'text-red-600' : memNs > 60 ? 'text-violet-600' : 'text-gray-600'}`}>
                            {memNs.toFixed(0)}%
                          </span>
                        </div>
                      </td>
                      <td className="py-3 text-center">
                        <span className="inline-flex items-center justify-center w-8 h-6 rounded-md bg-gray-100 text-xs font-bold text-gray-700">
                          {ns.podCount || 0}
                        </span>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </section>

      {/* ══════════════════════════════════════════════════════════════════════
          Section 6 — Quick Wins / Recommendations
      ══════════════════════════════════════════════════════════════════════ */}
      {quickWins && quickWins.length > 0 && (
        <section className="bg-white rounded-2xl shadow-sm border border-gray-100 p-6">
          <div className="flex items-center gap-3 mb-5">
            <div className="w-9 h-9 rounded-xl bg-gradient-to-br from-amber-400 to-orange-500 flex items-center justify-center shadow-sm">
              <i className="fas fa-lightbulb text-white text-sm"></i>
            </div>
            <div>
              <h3 className="text-lg font-bold text-gray-900">Quick Wins</h3>
              <p className="text-xs text-gray-500">Prioritized actions for immediate impact</p>
            </div>
            <div className="ml-auto">
              <span className="inline-flex items-center gap-1 px-3 py-1 rounded-full bg-amber-100 text-amber-700 text-xs font-bold">
                <i className="fas fa-bolt text-xs"></i>
                {quickWins.length} action{quickWins.length !== 1 ? 's' : ''}
              </span>
            </div>
          </div>

          <div className="space-y-3">
            {quickWins.map((win: QuickWin, index: number) => {
              const typeStyles = {
                security: {
                  bg: 'bg-red-50',
                  border: 'border-l-red-500',
                  iconBg: 'bg-red-100',
                  iconColor: 'text-red-600',
                  badge: 'bg-red-100 text-red-700',
                  badgeLabel: 'Security',
                },
                cost: {
                  bg: 'bg-amber-50',
                  border: 'border-l-amber-500',
                  iconBg: 'bg-amber-100',
                  iconColor: 'text-amber-600',
                  badge: 'bg-amber-100 text-amber-700',
                  badgeLabel: 'Cost',
                },
                optimization: {
                  bg: 'bg-indigo-50',
                  border: 'border-l-indigo-500',
                  iconBg: 'bg-indigo-100',
                  iconColor: 'text-indigo-600',
                  badge: 'bg-indigo-100 text-indigo-700',
                  badgeLabel: 'Optimization',
                },
              };
              const style = typeStyles[win.type as keyof typeof typeStyles] || typeStyles.optimization;

              return (
                <div
                  key={index}
                  className={`flex items-start gap-4 p-4 rounded-xl border border-l-4 ${style.bg} ${style.border} border-gray-100 hover:shadow-sm transition-all`}
                >
                  <div className={`w-10 h-10 rounded-xl flex items-center justify-center flex-shrink-0 ${style.iconBg}`}>
                    <i className={`fas ${win.icon} ${style.iconColor} text-base`}></i>
                  </div>
                  <div className="flex-1 min-w-0">
                    <div className="flex items-center gap-2 mb-1 flex-wrap">
                      <h4 className="text-sm font-bold text-gray-900">{win.title}</h4>
                      <span className={`text-xs font-bold px-2 py-0.5 rounded-full ${style.badge}`}>
                        {style.badgeLabel}
                      </span>
                    </div>
                    <p className="text-xs text-gray-600 mb-3 leading-relaxed">{win.impact}</p>
                    <div className="flex gap-2 flex-wrap">
                      {win.actions.map((action: string, i: number) => (
                        <button
                          key={i}
                          className={`px-3 py-1.5 rounded-lg text-xs font-semibold transition-all ${
                            i === 0
                              ? 'bg-gray-900 text-white hover:bg-gray-700'
                              : 'bg-white text-gray-700 hover:bg-gray-100 border border-gray-200'
                          }`}
                        >
                          {action}
                        </button>
                      ))}
                    </div>
                  </div>
                  <div className="flex-shrink-0 flex items-center justify-center w-6 h-6 rounded-full bg-white border border-gray-200 text-xs font-black text-gray-500">
                    {index + 1}
                  </div>
                </div>
              );
            })}
          </div>
        </section>
      )}

      {/* ══════════════════════════════════════════════════════════════════════
          Section 7 — Cluster Events (warnings only)
      ══════════════════════════════════════════════════════════════════════ */}
      {warningEvents.length > 0 && (
        <section className="bg-white rounded-2xl shadow-sm border border-gray-100 p-6">
          <div className="flex items-center gap-3 mb-5">
            <div className="w-9 h-9 rounded-xl bg-gradient-to-br from-red-500 to-rose-600 flex items-center justify-center shadow-sm">
              <i className="fas fa-exclamation-triangle text-white text-sm"></i>
            </div>
            <div>
              <h3 className="text-lg font-bold text-gray-900">Cluster Events</h3>
              <p className="text-xs text-gray-500">Recent warning events from your cluster</p>
            </div>
            <div className="ml-auto">
              <span className="inline-flex items-center gap-1 px-3 py-1 rounded-full bg-red-100 text-red-700 text-xs font-bold">
                <i className="fas fa-exclamation-circle text-xs"></i>
                {warningEvents.length} warning{warningEvents.length !== 1 ? 's' : ''}
              </span>
            </div>
          </div>

          <div className="space-y-2">
            {warningEvents.map((event: any, i: number) => (
              <div key={i} className="flex items-start gap-3 p-3 rounded-xl bg-red-50/60 border border-red-100 hover:bg-red-50 transition-colors">
                <div className="flex-shrink-0 mt-0.5">
                  <i className="fas fa-circle-exclamation text-red-400 text-sm"></i>
                </div>
                <div className="flex-1 min-w-0">
                  <div className="flex items-center gap-2 mb-0.5 flex-wrap">
                    <span className="text-xs font-bold text-red-700 font-mono">{event.reason || 'Warning'}</span>
                    {event.involvedObject?.name && (
                      <span className="text-xs text-gray-500 truncate max-w-[200px] font-mono">{event.involvedObject.name}</span>
                    )}
                    {event.namespace && (
                      <span className="text-xs bg-red-100 text-red-600 px-1.5 py-0.5 rounded font-mono">{event.namespace}</span>
                    )}
                    {(event.count || 0) > 1 && (
                      <span className="text-xs font-bold bg-red-200 text-red-800 px-2 py-0.5 rounded-full">×{event.count}</span>
                    )}
                  </div>
                  <p className="text-xs text-gray-600 leading-snug line-clamp-2">{event.message}</p>
                </div>
              </div>
            ))}
          </div>
        </section>
      )}

    </div>
  );
};

export default EksOverviewTab;
