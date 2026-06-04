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
  <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-lg px-5 py-4 flex flex-col gap-1 min-w-0">
    <div className="text-[11px] font-medium uppercase tracking-wider text-[rgba(0,0,0,0.4)]">{label}</div>
    <div className={`text-[26px] font-semibold leading-none ${valueColor || 'text-[rgba(0,0,0,0.88)]'}`}>{value}</div>
    {bar && (
      <div className="flex items-center gap-2 mt-1">
        <div className="flex-1 h-1.5 bg-[rgba(0,0,0,0.06)] rounded-full overflow-hidden">
          <div
            className="h-full rounded-full bg-[rgba(0,0,0,0.88)] transition-all duration-700"
            style={{ width: `${Math.min(100, bar.pct)}%` }}
          />
        </div>
        <span className="text-[11px] text-[rgba(0,0,0,0.4)] shrink-0">{bar.pct.toFixed(1)}%</span>
      </div>
    )}
    {sub && !bar && <div className="text-[11px] text-[rgba(0,0,0,0.4)]">{sub}</div>}
  </div>
);

const EksSummaryCards: React.FC<EksSummaryCardsProps> = ({ eksData }) => {
  const { dashboard, totalPodsCount, version, getNodeConditionCount } = eksData;

  const totalNodes = dashboard?.clusterOverview?.totalNodes || 0;
  // getNodeConditionCount falls back to resourceUtilization.nodes condition strings
  // when nodeMetrics.nodes is empty or has nodes without ready=true set
  const readyNodes = getNodeConditionCount ? getNodeConditionCount('ready') : (() => {
    const nodes = dashboard?.nodeMetrics?.nodes;
    if (!nodes || nodes.length === 0) return totalNodes;
    const unique = nodes.reduce((acc: any[], n: any) => {
      if (!acc.find((x: any) => (x.name || x.nodeName) === (n.name || n.nodeName))) acc.push(n);
      return acc;
    }, []);
    const fromReady = unique.filter((n: any) => n.ready).length;
    return fromReady || totalNodes;
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
        value={<>{readyNodes}<span className="text-[16px] text-[rgba(0,0,0,0.4)] font-normal">/{totalNodes}</span></>}
        sub={`k8s v${clusterVersion}`}
      />
      <StatCard
        label="Pods Running"
        value={<>{runningPods}<span className="text-[16px] text-[rgba(0,0,0,0.4)] font-normal">/{totalPodsCount || 0}</span></>}
        sub={`${namespaces} namespaces`}
      />
      <StatCard
        label="CPU Usage"
        value={`${cpuPct.toFixed(1)}%`}
        bar={{ pct: cpuPct }}
        valueColor={cpuPct >= 80 ? 'text-[#cf222e]' : cpuPct >= 60 ? 'text-[#9a6700]' : 'text-[rgba(0,0,0,0.88)]'}
      />
      <StatCard
        label="Memory Usage"
        value={`${memPct.toFixed(1)}%`}
        bar={{ pct: memPct }}
        valueColor={memPct >= 80 ? 'text-[#cf222e]' : memPct >= 60 ? 'text-[#9a6700]' : 'text-[rgba(0,0,0,0.88)]'}
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
