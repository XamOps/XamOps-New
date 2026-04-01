/**
 * EKS Summary Cards — compact KPI strip above tab bar
 * Clean white/cream design matching Cast.ai reference.
 */

import React from 'react';

interface EksSummaryCardsProps {
  eksData: any;
}

const StatCard: React.FC<{
  label: string;
  value: React.ReactNode;
  sub?: string;
  bar?: { pct: number };
  valueColor?: string;
}> = ({ label, value, sub, bar, valueColor }) => (
  <div className="bg-white border border-[#d0d7de] rounded-lg px-5 py-4 flex flex-col gap-1 min-w-0">
    <div className="text-[11px] font-medium uppercase tracking-wider text-[#8b949e]">{label}</div>
    <div className={`text-[26px] font-semibold leading-none ${valueColor || 'text-[#1a1f2e]'}`}>{value}</div>
    {bar && (
      <div className="flex items-center gap-2 mt-1">
        <div className="flex-1 h-1.5 bg-[#e8ecf0] rounded-full overflow-hidden">
          <div
            className="h-full rounded-full bg-[#2f4bde] transition-all duration-700"
            style={{ width: `${Math.min(100, bar.pct)}%` }}
          />
        </div>
        <span className="text-[11px] text-[#8b949e] shrink-0">{bar.pct.toFixed(1)}%</span>
      </div>
    )}
    {sub && !bar && <div className="text-[11px] text-[#8b949e]">{sub}</div>}
  </div>
);

const EksSummaryCards: React.FC<EksSummaryCardsProps> = ({ eksData }) => {
  const { dashboard, totalPodsCount, version } = eksData;

  const totalNodes = dashboard?.clusterOverview?.totalNodes || 0;
  const readyNodes = (() => {
    const nodes = dashboard?.nodeMetrics?.nodes;
    if (!nodes) return totalNodes;
    const unique = nodes.reduce((acc: any[], n: any) => {
      if (!acc.find((x: any) => x.name === n.name)) acc.push(n);
      return acc;
    }, []);
    return unique.filter((n: any) => n.ready).length;
  })();

  const runningPods = dashboard?.clusterOverview?.runningPods ||
    (dashboard?.workloadHealth?.pods || []).filter((p: any) => p.phase === 'Running').length;
  const namespaces = dashboard?.clusterOverview?.totalNamespaces || 0;
  const cpuPct = dashboard?.clusterOverview?.cpuUsagePercent || 0;
  const memPct = dashboard?.clusterOverview?.memoryUsagePercent || 0;
  const dailyCost = dashboard?.costMetrics?.dailyCost || 0;
  const clusterVersion = dashboard?.clusterOverview?.version || version || 'N/A';

  return (
    <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-6 gap-3 mb-0">
      <StatCard
        label="Nodes Ready"
        value={<>{readyNodes}<span className="text-[16px] text-[#8b949e] font-normal">/{totalNodes}</span></>}
        sub={`k8s v${clusterVersion}`}
      />
      <StatCard
        label="Pods Running"
        value={<>{runningPods}<span className="text-[16px] text-[#8b949e] font-normal">/{totalPodsCount || 0}</span></>}
        sub={`${namespaces} namespaces`}
      />
      <StatCard
        label="CPU Usage"
        value={`${cpuPct.toFixed(1)}%`}
        bar={{ pct: cpuPct }}
        valueColor={cpuPct >= 80 ? 'text-[#cf222e]' : cpuPct >= 60 ? 'text-[#9a6700]' : 'text-[#1a1f2e]'}
      />
      <StatCard
        label="Memory Usage"
        value={`${memPct.toFixed(1)}%`}
        bar={{ pct: memPct }}
        valueColor={memPct >= 80 ? 'text-[#cf222e]' : memPct >= 60 ? 'text-[#9a6700]' : 'text-[#1a1f2e]'}
      />
      <StatCard
        label="Cost Today"
        value={`$${dailyCost.toFixed(2)}`}
        sub={`~$${(dailyCost * 30).toFixed(0)}/mo`}
      />
      <StatCard
        label="Namespaces"
        value={namespaces}
        sub="active"
      />
    </div>
  );
};

export default EksSummaryCards;
