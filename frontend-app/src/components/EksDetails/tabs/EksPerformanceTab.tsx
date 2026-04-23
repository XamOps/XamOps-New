/**
 * EKS Performance Tab — Cast.ai Premium Level
 *
 * Dark gradient hero, SVG donut gauges, node performance cards,
 * and actionable performance insights.
 */

import React from 'react';

interface EksPerformanceTabProps {
  eksData: any;
}

const DonutGauge: React.FC<{
  pct: number;
  color: string;
  trackColor?: string;
  size?: number;
  strokeWidth?: number;
  children: React.ReactNode;
}> = ({ pct, color, trackColor = '#e5e7eb', size = 140, strokeWidth = 10, children }) => {
  const r = (size - strokeWidth * 2) / 2;
  const circ = 2 * Math.PI * r;
  const filled = Math.max(0, Math.min(100, pct)) / 100 * circ;
  return (
    <div className="relative flex items-center justify-center" style={{ width: size, height: size }}>
      <svg width={size} height={size} className="-rotate-90">
        <circle cx={size / 2} cy={size / 2} r={r} fill="none" stroke={trackColor} strokeWidth={strokeWidth} />
        <circle
          cx={size / 2} cy={size / 2} r={r}
          fill="none" stroke={color} strokeWidth={strokeWidth}
          strokeLinecap="round"
          strokeDasharray={`${filled} ${circ}`}
          className="transition-all duration-1000"
        />
      </svg>
      <div className="absolute inset-0 flex flex-col items-center justify-center">
        {children}
      </div>
    </div>
  );
};

const EksPerformanceTab: React.FC<EksPerformanceTabProps> = ({ eksData }) => {
  const { dashboard } = eksData;
  if (!dashboard) return null;

  const cpuUsagePercent = dashboard.clusterOverview?.cpuUsagePercent || 0;
  const memoryUsagePercent = dashboard.clusterOverview?.memoryUsagePercent || 0;
  const cpuUsageMillicores = dashboard.clusterOverview?.cpuUsageMillicores || 0;
  const cpuCapacityMillicores = dashboard.clusterOverview?.cpuCapacityMillicores || 0;
  const memoryUsageBytes = dashboard.clusterOverview?.memoryUsageBytes || 0;
  const memoryCapacityBytes = dashboard.clusterOverview?.memoryCapacityBytes || 0;
  const totalNodes = dashboard.clusterOverview?.totalNodes || 0;
  const totalPods = dashboard.workloadHealth?.pods?.length || 0;

  const allUtilizationNodes = dashboard.resourceUtilization?.nodes || [];
  const nodeMetrics = dashboard.nodeMetrics?.nodes || [];

  const readyNodes = allUtilizationNodes.filter((utilNode: any) => {
    const nodeInfo = nodeMetrics.find((n: any) => n.name === utilNode.nodeName);
    // nodeMetrics may be empty in agent-only mode; fall back to condition string from resourceUtilization
    return nodeInfo?.ready === true ||
           utilNode.condition === 'Ready' ||
           utilNode.condition === 'ready';
  });

  const getCpuColor = () => {
    if (cpuUsagePercent >= 80) return '#ef4444';
    if (cpuUsagePercent >= 60) return '#f59e0b';
    return '#10b981';
  };

  const getMemoryColor = () => {
    if (memoryUsagePercent >= 80) return '#ef4444';
    if (memoryUsagePercent >= 60) return '#f59e0b';
    return '#6366f1';
  };

  const formatBytes = (bytes: number) => `${(bytes / (1024 * 1024 * 1024)).toFixed(1)} GB`;
  const totalCpuCores = (cpuCapacityMillicores / 1000).toFixed(1);
  const usedCpuCores = (cpuUsageMillicores / 1000).toFixed(1);
  const totalMemoryGB = (memoryCapacityBytes / (1024 * 1024 * 1024)).toFixed(1);
  const usedMemoryGB = (memoryUsageBytes / (1024 * 1024 * 1024)).toFixed(1);
  const podDensity = totalNodes > 0 ? (totalPods / totalNodes).toFixed(1) : '0';
  const efficiencyScore = ((cpuUsagePercent + memoryUsagePercent) / 2).toFixed(0);
  const isOptimal = cpuUsagePercent >= 40 && cpuUsagePercent <= 75 && memoryUsagePercent >= 40 && memoryUsagePercent <= 75;
  const runningPods = dashboard.workloadHealth?.pods?.filter((p: any) => p.phase === 'Running').length || 0;
  const avgNodeCpuUsage = readyNodes.length > 0
    ? (readyNodes.reduce((s: number, n: any) => s + (n.cpuUsagePercent || 0), 0) / readyNodes.length).toFixed(1) : '0';
  const avgNodeMemUsage = readyNodes.length > 0
    ? (readyNodes.reduce((s: number, n: any) => s + (n.memoryUsagePercent || 0), 0) / readyNodes.length).toFixed(1) : '0';


  return (
    <div className="space-y-5">

      {/* ── KPI Strip ── */}
      <div className="grid grid-cols-2 sm:grid-cols-4 gap-3">
        <div className="bg-white border border-[#d0d7de] rounded-lg px-5 py-4">
          <div className="text-[11px] font-medium uppercase tracking-wider text-[#8b949e] mb-1">ACTIVE NODES</div>
          <div className="text-[26px] font-semibold leading-none text-[#1a1f2e]">{readyNodes.length}</div>
          <div className="text-[11px] text-[#8b949e] mt-1">{podDensity} pods/node avg</div>
        </div>
        <div className="bg-white border border-[#d0d7de] rounded-lg px-5 py-4">
          <div className="text-[11px] font-medium uppercase tracking-wider text-[#8b949e] mb-1">RUNNING PODS</div>
          <div className="text-[26px] font-semibold leading-none text-[#1a1f2e]">{runningPods}</div>
          <div className="text-[11px] text-[#8b949e] mt-1">of {totalPods} total</div>
        </div>
        <div className="bg-white border border-[#d0d7de] rounded-lg px-5 py-4">
          <div className="text-[11px] font-medium uppercase tracking-wider text-[#8b949e] mb-1">CPU USAGE</div>
          <div className={`text-[26px] font-semibold leading-none ${cpuUsagePercent >= 80 ? 'text-[#cf222e]' : cpuUsagePercent >= 60 ? 'text-[#9a6700]' : 'text-[#1a1f2e]'}`}>{cpuUsagePercent.toFixed(0)}%</div>
          <div className="text-[11px] text-[#8b949e] mt-1">{usedCpuCores} / {totalCpuCores} cores</div>
        </div>
        <div className="bg-white border border-[#d0d7de] rounded-lg px-5 py-4">
          <div className="text-[11px] font-medium uppercase tracking-wider text-[#8b949e] mb-1">MEMORY USAGE</div>
          <div className={`text-[26px] font-semibold leading-none ${memoryUsagePercent >= 80 ? 'text-[#cf222e]' : memoryUsagePercent >= 60 ? 'text-[#9a6700]' : 'text-[#1a1f2e]'}`}>{memoryUsagePercent.toFixed(0)}%</div>
          <div className="text-[11px] text-[#8b949e] mt-1">{usedMemoryGB} / {totalMemoryGB} GB</div>
        </div>
      </div>

      {/* ── Resource Utilization Gauges ── */}
      <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
        <div className="bg-white border border-[#d0d7de] rounded-lg p-5">
          <div className="flex items-center justify-between mb-4">
            <span className="text-sm font-semibold text-[#1a1f2e]">CPU Utilization</span>
            <span className="rounded-full px-2.5 py-0.5 text-[11px] font-semibold border"
              style={cpuUsagePercent >= 80 ? { color: '#cf222e', background: '#ffebe9', borderColor: 'rgba(255,129,130,0.4)' }
                : cpuUsagePercent >= 60 ? { color: '#9a6700', background: '#fff8c5', borderColor: 'rgba(212,167,44,0.4)' }
                : { color: '#1a7f37', background: '#dafbe1', borderColor: 'rgba(74,194,107,0.4)' }}>
              {cpuUsagePercent >= 80 ? 'High Load' : cpuUsagePercent >= 60 ? 'Moderate' : 'Healthy'}
            </span>
          </div>
          <div className="flex items-center gap-6">
            <DonutGauge pct={cpuUsagePercent} color={getCpuColor()} size={140} strokeWidth={12}>
              <span className="text-[26px] font-semibold" style={{ color: getCpuColor() }}>{cpuUsagePercent.toFixed(0)}</span>
              <span className="text-[11px] text-[#8b949e]">%</span>
            </DonutGauge>
            <div className="flex-1 space-y-3">
              <div>
                <div className="flex justify-between text-[11px] text-[#8b949e] mb-1">
                  <span>Used</span><span className="font-semibold text-[#1a1f2e]">{Math.round(cpuUsageMillicores)}m / {Math.round(cpuCapacityMillicores)}m</span>
                </div>
                <div className="h-1.5 bg-[#e8ecf0] rounded-full overflow-hidden">
                  <div className="h-full bg-[#2f4bde] rounded-full transition-all duration-700" style={{ width: `${cpuUsagePercent}%` }} />
                </div>
              </div>
              <div className="grid grid-cols-2 gap-2">
                <div className="border border-[#d0d7de] rounded-lg p-3">
                  <div className="text-[11px] text-[#8b949e] mb-1">Cores Used</div>
                  <div className="text-[20px] font-semibold text-[#1a1f2e]">{usedCpuCores}</div>
                  <div className="text-[11px] text-[#8b949e]">of {totalCpuCores}</div>
                </div>
                <div className="border border-[#d0d7de] rounded-lg p-3">
                  <div className="text-[11px] text-[#8b949e] mb-1">Avg / Node</div>
                  <div className="text-[20px] font-semibold text-[#1a1f2e]">{avgNodeCpuUsage}%</div>
                  <div className="text-[11px] text-[#8b949e]">average</div>
                </div>
              </div>
            </div>
          </div>
        </div>

        <div className="bg-white border border-[#d0d7de] rounded-lg p-5">
          <div className="flex items-center justify-between mb-4">
            <span className="text-sm font-semibold text-[#1a1f2e]">Memory Utilization</span>
            <span className="rounded-full px-2.5 py-0.5 text-[11px] font-semibold border"
              style={memoryUsagePercent >= 80 ? { color: '#cf222e', background: '#ffebe9', borderColor: 'rgba(255,129,130,0.4)' }
                : memoryUsagePercent >= 60 ? { color: '#9a6700', background: '#fff8c5', borderColor: 'rgba(212,167,44,0.4)' }
                : { color: '#1a7f37', background: '#dafbe1', borderColor: 'rgba(74,194,107,0.4)' }}>
              {memoryUsagePercent >= 80 ? 'High Load' : memoryUsagePercent >= 60 ? 'Moderate' : 'Healthy'}
            </span>
          </div>
          <div className="flex items-center gap-6">
            <DonutGauge pct={memoryUsagePercent} color={getMemoryColor()} size={140} strokeWidth={12}>
              <span className="text-[26px] font-semibold" style={{ color: getMemoryColor() }}>{memoryUsagePercent.toFixed(0)}</span>
              <span className="text-[11px] text-[#8b949e]">%</span>
            </DonutGauge>
            <div className="flex-1 space-y-3">
              <div>
                <div className="flex justify-between text-[11px] text-[#8b949e] mb-1">
                  <span>Used</span><span className="font-semibold text-[#1a1f2e]">{formatBytes(memoryUsageBytes)} / {formatBytes(memoryCapacityBytes)}</span>
                </div>
                <div className="h-1.5 bg-[#e8ecf0] rounded-full overflow-hidden">
                  <div className="h-full bg-[#2f4bde] rounded-full transition-all duration-700" style={{ width: `${memoryUsagePercent}%` }} />
                </div>
              </div>
              <div className="grid grid-cols-2 gap-2">
                <div className="border border-[#d0d7de] rounded-lg p-3">
                  <div className="text-[11px] text-[#8b949e] mb-1">Used</div>
                  <div className="text-[20px] font-semibold text-[#1a1f2e]">{usedMemoryGB} GB</div>
                  <div className="text-[11px] text-[#8b949e]">consumed</div>
                </div>
                <div className="border border-[#d0d7de] rounded-lg p-3">
                  <div className="text-[11px] text-[#8b949e] mb-1">Avg / Node</div>
                  <div className="text-[20px] font-semibold text-[#1a1f2e]">{avgNodeMemUsage}%</div>
                  <div className="text-[11px] text-[#8b949e]">average</div>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>

      {/* ── Node Resource Breakdown ── */}
      <div className="bg-white border border-[#d0d7de] rounded-lg overflow-hidden">
        <div className="px-5 py-3.5 border-b border-[#e8ecf0]">
          <span className="text-sm font-semibold text-[#1a1f2e]">Node Resource Breakdown</span>
          <span className="ml-2 text-[11px] text-[#8b949e]">{readyNodes.length} active nodes</span>
        </div>

        <div className="divide-y divide-[#e8ecf0]">
          {readyNodes.map((node: any, index: number) => {
            const cpuReqPercent = node.cpuRequestsPercent != null ? node.cpuRequestsPercent
              : (node.cpuCapacity ? (node.cpuRequests / (node.cpuCapacity * 1000)) * 100 : 0);
            const memReqPercent = node.memoryRequestsPercent != null ? node.memoryRequestsPercent
              : (node.memoryCapacity ? (node.memoryRequests / (node.memoryCapacity * 1024)) * 100 : 0);
            const cpu = node.cpuUsagePercent || 0;
            const mem = node.memoryUsagePercent || 0;
            const cpuMillicoresNode = Math.round(node.cpuUsageMillicores || 0);
            const cpuColor = cpu >= 80 ? '#cf222e' : cpu >= 60 ? '#9a6700' : '#2f4bde';
            const memColor = mem >= 80 ? '#cf222e' : mem >= 60 ? '#9a6700' : '#2f4bde';

            return (
              <div key={index} className="px-5 py-4 hover:bg-[#f6f8fa] transition-colors">
                <div className="flex items-center justify-between mb-3">
                  <span className="font-mono text-[13px] font-semibold text-[#1a1f2e]">{node.nodeName}</span>
                  <div className="flex items-center gap-2">
                    <span className="rounded-full px-2.5 py-0.5 text-[11px] font-semibold border"
                      style={cpu >= 80 ? { color: '#cf222e', background: '#ffebe9', borderColor: 'rgba(255,129,130,0.4)' }
                        : cpu >= 60 ? { color: '#9a6700', background: '#fff8c5', borderColor: 'rgba(212,167,44,0.4)' }
                        : { color: '#1a7f37', background: '#dafbe1', borderColor: 'rgba(74,194,107,0.4)' }}>
                      CPU {cpu.toFixed(0)}%
                    </span>
                    <span className="rounded-full px-2.5 py-0.5 text-[11px] font-semibold border"
                      style={mem >= 80 ? { color: '#cf222e', background: '#ffebe9', borderColor: 'rgba(255,129,130,0.4)' }
                        : mem >= 60 ? { color: '#9a6700', background: '#fff8c5', borderColor: 'rgba(212,167,44,0.4)' }
                        : { color: '#1a7f37', background: '#dafbe1', borderColor: 'rgba(74,194,107,0.4)' }}>
                      MEM {mem.toFixed(0)}%
                    </span>
                  </div>
                </div>

                <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
                  {[
                    { label: 'CPU Usage', pct: cpu, val: `${cpuMillicoresNode}m`, color: cpuColor },
                    { label: 'Mem Usage', pct: mem, val: `${Math.round(node.memoryUsageGb || 0)} GB`, color: memColor },
                    { label: 'CPU Requests', pct: cpuReqPercent, val: node.cpuRequests > 0 ? `${Math.round(node.cpuRequests)}m` : null, color: '#2f4bde' },
                    { label: 'Mem Requests', pct: memReqPercent, val: node.memoryRequests > 0 ? `${Math.round(node.memoryRequests)} MB` : null, color: '#2f4bde' },
                  ].map((bar, bi) => (
                    <div key={bi}>
                      <div className="flex items-center justify-between text-[11px] mb-1">
                        <span className="text-[#8b949e]">{bar.label}</span>
                        <span className="font-semibold" style={{ color: bar.color }}>{(bar.pct || 0).toFixed(1)}%</span>
                      </div>
                      <div className="h-1.5 bg-[#e8ecf0] rounded-full overflow-hidden">
                        <div className="h-full rounded-full transition-all" style={{ width: `${Math.min(100, bar.pct)}%`, background: bar.color }} />
                      </div>
                      {bar.val && <div className="text-[11px] text-[#8b949e] mt-0.5">{bar.val}</div>}
                    </div>
                  ))}
                </div>
              </div>
            );
          })}

          {readyNodes.length === 0 && (
            <div className="py-14 text-center">
              <p className="text-[13px] text-[#57606a]">No active node metrics available</p>
            </div>
          )}
        </div>
      </div>

      {/* ── Performance Insights ── */}
      <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
        <div className="bg-white border border-[#d0d7de] rounded-lg p-5 flex flex-col items-center text-center">
          <DonutGauge
            pct={Number(efficiencyScore)}
            color={Number(efficiencyScore) >= 60 ? '#1a7f37' : Number(efficiencyScore) >= 40 ? '#9a6700' : '#cf222e'}
            size={120} strokeWidth={10}
          >
            <span className="text-[26px] font-semibold" style={{ color: Number(efficiencyScore) >= 60 ? '#1a7f37' : Number(efficiencyScore) >= 40 ? '#9a6700' : '#cf222e' }}>
              {efficiencyScore}%
            </span>
          </DonutGauge>
          <div className="text-sm font-semibold text-[#1a1f2e] mt-3">Resource Efficiency</div>
          <div className="text-[11px] text-[#8b949e] mt-0.5">Avg CPU + memory utilization</div>
          <span className="mt-3 rounded-full px-2.5 py-0.5 text-[11px] font-semibold border"
            style={Number(efficiencyScore) >= 60 ? { color: '#1a7f37', background: '#dafbe1', borderColor: 'rgba(74,194,107,0.4)' }
              : Number(efficiencyScore) >= 40 ? { color: '#9a6700', background: '#fff8c5', borderColor: 'rgba(212,167,44,0.4)' }
              : { color: '#cf222e', background: '#ffebe9', borderColor: 'rgba(255,129,130,0.4)' }}>
            {Number(efficiencyScore) >= 60 ? 'Good utilization' : Number(efficiencyScore) >= 40 ? 'Acceptable' : 'Under-utilized'}
          </span>
        </div>

        <div className="bg-white border border-[#d0d7de] rounded-lg p-5 flex flex-col items-center text-center">
          <div className="text-[26px] font-semibold text-[#1a1f2e] mt-4">{podDensity}</div>
          <div className="text-[11px] text-[#8b949e]">pods/node avg</div>
          <div className="text-sm font-semibold text-[#1a1f2e] mt-2">Pod Density</div>
          <div className="text-[11px] text-[#8b949e] mt-0.5 mb-3">Workload distribution</div>
          <div className="grid grid-cols-2 gap-2 w-full">
            <div className="border border-[#d0d7de] rounded-lg p-3">
              <div className="text-[11px] text-[#8b949e]">Total</div>
              <div className="text-[20px] font-semibold text-[#1a1f2e]">{totalPods}</div>
            </div>
            <div className="border border-[#d0d7de] rounded-lg p-3">
              <div className="text-[11px] text-[#8b949e]">Running</div>
              <div className="text-[20px] font-semibold text-[#1a7f37]">{runningPods}</div>
            </div>
          </div>
        </div>

        <div className="bg-white border border-[#d0d7de] rounded-lg p-5 flex flex-col items-center text-center">
          <div className="text-sm font-semibold text-[#1a1f2e] mt-2">Allocation Status</div>
          <div className="text-[11px] text-[#8b949e] mt-0.5 mb-3">{isOptimal ? 'Resources in optimal 40–75% band' : 'Resources outside optimal range'}</div>
          <span className="rounded-full px-2.5 py-0.5 text-[11px] font-semibold border mb-4"
            style={isOptimal ? { color: '#1a7f37', background: '#dafbe1', borderColor: 'rgba(74,194,107,0.4)' }
              : { color: '#9a6700', background: '#fff8c5', borderColor: 'rgba(212,167,44,0.4)' }}>
            {isOptimal ? 'Optimized' : 'Needs Tuning'}
          </span>
          <div className="grid grid-cols-2 gap-2 w-full">
            <div className="border border-[#d0d7de] rounded-lg p-3">
              <div className="text-[11px] font-medium uppercase text-[#8b949e]">CPU</div>
              <div className="text-[20px] font-semibold" style={{ color: cpuUsagePercent >= 80 ? '#cf222e' : '#1a1f2e' }}>{cpuUsagePercent.toFixed(0)}%</div>
            </div>
            <div className="border border-[#d0d7de] rounded-lg p-3">
              <div className="text-[11px] font-medium uppercase text-[#8b949e]">Memory</div>
              <div className="text-[20px] font-semibold" style={{ color: memoryUsagePercent >= 80 ? '#cf222e' : '#1a1f2e' }}>{memoryUsagePercent.toFixed(0)}%</div>
            </div>
          </div>
        </div>
      </div>

    </div>
  );
};

export default EksPerformanceTab;
