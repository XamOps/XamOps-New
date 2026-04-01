/**
 * EKS Cost Tab — Cast.ai / KubeCost Premium Level
 *
 * Hero gradient strip, KubeCost-style namespace bars, node cost cards,
 * workload leaderboard, and intelligent savings opportunities.
 */

import React, { useState, useMemo } from 'react';

interface EksCostTabProps {
  eksData: any;
}

interface CostRecommendation {
  id: string;
  priority: 'high' | 'medium' | 'low';
  category: 'rightsizing' | 'autoscaling' | 'scheduling' | 'storage' | 'networking';
  title: string;
  description: string;
  impact: string;
  savingsEstimate: number;
  basisExplanation: string;
  actionItems: string[];
  affectedResources: string[];
}

const NS_BAR_COLORS = [
  'bg-indigo-500', 'bg-blue-500', 'bg-violet-500', 'bg-purple-500',
  'bg-cyan-500', 'bg-teal-500', 'bg-emerald-500', 'bg-sky-500',
];

const EksCostTab: React.FC<EksCostTabProps> = ({ eksData }) => {
  const { dashboard, sortedNodesByCost } = eksData;
  const [showRecommendationInfo, setShowRecommendationInfo] = useState<string | null>(null);
  const [deploymentPage, setDeploymentPage] = useState(0);
  const [daemonSetPage, setDaemonSetPage] = useState(0);
  const [statefulSetPage, setStatefulSetPage] = useState(0);
  const [namespacePage, setNamespacePage] = useState(0);
  const [nodePage, setNodePage] = useState(0);
  const [workloadTab, setWorkloadTab] = useState<'deployments' | 'daemonsets' | 'statefulsets'>('deployments');
  const itemsPerPage = 10;
  const nodeItemsPerPage = 6;

  if (!dashboard) return null;

  const dailyCost = dashboard.costMetrics?.dailyCost || 0;
  const monthlyCost = dailyCost * 30;

  function getNamespaceCosts() {
    let costs = dashboard.costMetrics?.namespaceCosts || [];
    if (costs.length > 0) {
      costs.forEach((ns: any) => {
        if (!ns.podCount) {
          ns.podCount = dashboard.workloadHealth?.pods?.filter(
            (p: any) => p.namespace === ns.namespace
          ).length || 0;
        }
      });
      return costs.sort((a: any, b: any) => b.dailyCost - a.dailyCost);
    }
    const namespaceMap = new Map<string, any>();
    dashboard.workloadHealth?.pods?.forEach((pod: any) => {
      const ns = pod.namespace || 'default';
      if (!namespaceMap.has(ns)) {
        namespaceMap.set(ns, { namespace: ns, cpuUsageMillicores: 0, memoryUsageMb: 0, podCount: 0, dailyCost: 0, monthlyCost: 0 });
      }
      const nsData = namespaceMap.get(ns);
      nsData.podCount++;
      nsData.cpuUsageMillicores += pod.cpuUsage || 0;
      nsData.memoryUsageMb += (pod.memoryUsage || 0) / (1024 * 1024);
      const podCpuCost = ((pod.cpuUsage || 0) / 1000) * 0.0416;
      const podMemCost = ((pod.memoryUsage || 0) / (1024 * 1024 * 1024)) * 0.00456;
      nsData.dailyCost += (podCpuCost + podMemCost) * 24;
    });
    const result = Array.from(namespaceMap.values());
    result.forEach(ns => { ns.monthlyCost = ns.dailyCost * 30; });
    return result.sort((a, b) => b.dailyCost - a.dailyCost);
  }

  function getWorkloadCosts() {
    let costs = dashboard.costMetrics?.workloadCosts || [];
    if (costs.length > 0) {
      costs.forEach((wl: any) => {
        if (!wl.podCount) {
          const wlPods = dashboard.workloadHealth?.pods?.filter(
            (p: any) => p.namespace === wl.namespace && (p.ownerName === wl.name || p.name.startsWith(wl.name))
          ) || [];
          wl.podCount = wlPods.length || wl.replicas || 0;
        }
      });
      return costs.sort((a: any, b: any) => b.dailyCost - a.dailyCost);
    }
    const workloads: any[] = [];
    dashboard.workloadHealth?.deployments?.forEach((deployment: any) => {
      const pods = dashboard.workloadHealth?.pods?.filter(
        (pod: any) => pod.namespace === deployment.namespace && (pod.ownerName === deployment.name || pod.name.includes(deployment.name))
      ) || [];
      let cpu = 0, mem = 0;
      pods.forEach((pod: any) => { cpu += pod.cpuUsage || 0; mem += (pod.memoryUsage || 0) / (1024 * 1024); });
      const daily = (cpu / 1000) * 0.0416 * 24 + (mem / 1024) * 0.00456 * 24;
      workloads.push({ name: deployment.name, namespace: deployment.namespace, kind: 'Deployment', cpuUsageMillicores: cpu, memoryUsageMb: mem, podCount: pods.length || deployment.replicas || 0, dailyCost: daily, monthlyCost: daily * 30 });
    });
    dashboard.workloadHealth?.daemonsets?.forEach((ds: any) => {
      const pods = dashboard.workloadHealth?.pods?.filter(
        (pod: any) => pod.namespace === ds.namespace && (pod.ownerName === ds.name || pod.name.includes(ds.name))
      ) || [];
      let cpu = 0, mem = 0;
      pods.forEach((pod: any) => { cpu += pod.cpuUsage || 0; mem += (pod.memoryUsage || 0) / (1024 * 1024); });
      const daily = (cpu / 1000) * 0.0416 * 24 + (mem / 1024) * 0.00456 * 24;
      workloads.push({ name: ds.name, namespace: ds.namespace, kind: 'DaemonSet', cpuUsageMillicores: cpu, memoryUsageMb: mem, podCount: pods.length || ds.ready || 0, dailyCost: daily, monthlyCost: daily * 30 });
    });
    dashboard.workloadHealth?.statefulsets?.forEach((ss: any) => {
      const pods = dashboard.workloadHealth?.pods?.filter(
        (pod: any) => pod.namespace === ss.namespace && (pod.ownerName === ss.name || pod.name.includes(ss.name))
      ) || [];
      let cpu = 0, mem = 0;
      pods.forEach((pod: any) => { cpu += pod.cpuUsage || 0; mem += (pod.memoryUsage || 0) / (1024 * 1024); });
      const daily = (cpu / 1000) * 0.0416 * 24 + (mem / 1024) * 0.00456 * 24;
      workloads.push({ name: ss.name, namespace: ss.namespace, kind: 'StatefulSet', cpuUsageMillicores: cpu, memoryUsageMb: mem, podCount: pods.length || ss.replicas || 0, dailyCost: daily, monthlyCost: daily * 30 });
    });
    return workloads.sort((a, b) => b.dailyCost - a.dailyCost);
  }

  function getDeploymentCosts() { return getWorkloadCosts().filter((wl: any) => wl.kind?.toLowerCase() === 'deployment'); }
  function getDaemonSetCosts() { return getWorkloadCosts().filter((wl: any) => wl.kind?.toLowerCase() === 'daemonset'); }
  function getStatefulSetCosts() { return getWorkloadCosts().filter((wl: any) => wl.kind?.toLowerCase() === 'statefulset'); }

  const recommendations = useMemo<CostRecommendation[]>(() => {
    const recs: CostRecommendation[] = [];
    const namespaceCosts = getNamespaceCosts();
    const totalNsCost = namespaceCosts.reduce((sum: number, ns: any) => sum + (ns.dailyCost || 0), 0);
    namespaceCosts.filter((ns: any) => (ns.dailyCost / Math.max(totalNsCost, 0.01)) * 100 > 30).forEach((ns: any) => {
      const pct = ((ns.dailyCost / Math.max(totalNsCost, 0.01)) * 100).toFixed(1);
      recs.push({
        id: `expensive-namespace-${ns.namespace}`,
        priority: 'medium',
        category: 'rightsizing',
        title: `Namespace "${ns.namespace}" High Cost`,
        description: `This namespace accounts for ${pct}% of total cluster cost ($${ns.dailyCost.toFixed(2)}/day).`,
        impact: `Review workloads in this namespace for optimization opportunities`,
        savingsEstimate: ns.dailyCost * 0.3 * 30,
        basisExplanation: `Namespace costs are calculated by aggregating CPU and memory usage of all pods, then applying AWS EC2 pricing rates.`,
        actionItems: ['Audit all workloads in this namespace for necessity', 'Check for idle or forgotten deployments', 'Review pod resource requests vs actual usage', 'Consider implementing resource quotas'],
        affectedResources: [ns.namespace],
      });
    });
    const hasKarpenter = dashboard.karpenter?.healthy || false;
    if (!hasKarpenter && dailyCost > 0) {
      const est = dailyCost * 0.15;
      recs.push({
        id: 'no-autoscaling',
        priority: 'high',
        category: 'autoscaling',
        title: 'Cloud-Native Autoscaling Recommended',
        description: "Advanced autoscaling (like Karpenter) is not active. This limits your cluster's ability to automatically right-size node provisioning.",
        impact: `Potential savings of $${(est * 30).toFixed(2)}/month with intelligent orchestration`,
        savingsEstimate: est * 30,
        basisExplanation: `Intelligent autoscalers can reduce cluster costs by 15-20% through automated consolidation and optimal instance selection.`,
        actionItems: ['Install and configure Karpenter for intelligent node provisioning', 'Define NodePools with appropriate instance type constraints', 'Set up Pod Disruption Budgets for safe scaling', 'Monitor and tune consolidation settings'],
        affectedResources: ['cluster-wide'],
      });
    }
    const idleWorkloads = getWorkloadCosts().filter((wl: any) => (wl.cpuUsageMillicores || 0) < 10 && (wl.memoryUsageMb || 0) < 100);
    if (idleWorkloads.length > 2) {
      const idleCost = idleWorkloads.reduce((sum: number, wl: any) => sum + (wl.dailyCost || 0), 0);
      recs.push({
        id: 'idle-workloads',
        priority: 'medium',
        category: 'scheduling',
        title: `${idleWorkloads.length} Low-Usage Workloads Detected`,
        description: `Found ${idleWorkloads.length} workloads with minimal CPU (<10m) and memory usage (<100MB). These may be idle or test workloads.`,
        impact: `Save $${(idleCost * 30).toFixed(2)}/month by cleaning up`,
        savingsEstimate: idleCost * 30,
        basisExplanation: `Workloads are classified as idle when CPU usage is below 10 millicores and memory usage is below 100MB.`,
        actionItems: ['Review and delete unused test/development workloads', 'Implement workload scheduling for non-production environments', 'Use namespaces to organize and track workload purposes', 'Set up regular audits of running workloads'],
        affectedResources: idleWorkloads.map((wl: any) => `${wl.namespace}/${wl.name}`).slice(0, 5),
      });
    }
    return recs.sort((a, b) => {
      const w = { high: 3, medium: 2, low: 1 };
      if (w[a.priority] !== w[b.priority]) return w[b.priority] - w[a.priority];
      return b.savingsEstimate - a.savingsEstimate;
    });
  }, [sortedNodesByCost, dashboard]);

  const namespaceCosts = getNamespaceCosts();
  const totalNsCost = namespaceCosts.reduce((s: number, n: any) => s + (n.dailyCost || 0), 0);
  const totalSavings = recommendations.reduce((s, r) => s + r.savingsEstimate, 0);
  const hourlyRate = dailyCost / 24;

  const deploymentCosts = getDeploymentCosts();
  const daemonSetCosts = getDaemonSetCosts();
  const statefulSetCosts = getStatefulSetCosts();
  const activeWorkloads = workloadTab === 'deployments' ? deploymentCosts : workloadTab === 'daemonsets' ? daemonSetCosts : statefulSetCosts;
  const activeWorkloadPage = workloadTab === 'deployments' ? deploymentPage : workloadTab === 'daemonsets' ? daemonSetPage : statefulSetPage;
  const setActiveWorkloadPage = workloadTab === 'deployments' ? setDeploymentPage : workloadTab === 'daemonsets' ? setDaemonSetPage : setStatefulSetPage;
  const maxWorkloadCost = Math.max(...activeWorkloads.map((w: any) => w.dailyCost || 0), 0.01);

  const sortedNodes = [...sortedNodesByCost].sort((a: any, b: any) => {
    const aReady = (!a.condition || a.condition === 'Ready' || a.condition === 'ready') ? 1 : 0;
    const bReady = (!b.condition || b.condition === 'Ready' || b.condition === 'ready') ? 1 : 0;
    if (aReady !== bReady) return bReady - aReady;
    return (b.dailyCost || 0) - (a.dailyCost || 0);
  });

  return (
    <div className="space-y-6">

      {/* ═══ HERO: Cost Command Center ═══ */}
      <div className="bg-gradient-to-br from-emerald-600 via-teal-600 to-cyan-700 rounded-2xl p-8 text-white shadow-xl relative overflow-hidden">
        {/* Decorative circles */}
        <div className="absolute -top-12 -right-12 w-48 h-48 bg-white/5 rounded-full"></div>
        <div className="absolute -bottom-8 -left-8 w-32 h-32 bg-white/5 rounded-full"></div>

        <div className="relative">
          <div className="flex items-center justify-between mb-8">
            <div>
              <p className="text-emerald-200 text-xs font-bold uppercase tracking-widest mb-1">Cost Intelligence</p>
              <h2 className="text-2xl font-black text-white">Cluster Spending Analysis</h2>
            </div>
            <div className="flex items-center gap-2 bg-white/10 backdrop-blur px-4 py-2 rounded-full border border-white/20">
              <div className="w-2 h-2 rounded-full bg-green-300 animate-pulse"></div>
              <span className="text-white text-sm font-semibold">Live</span>
            </div>
          </div>

          <div className="grid grid-cols-1 md:grid-cols-3 gap-4 mb-6">
            {/* Daily Cost */}
            <div className="bg-white/10 backdrop-blur rounded-xl p-5 border border-white/20">
              <p className="text-emerald-200 text-xs font-bold uppercase tracking-widest mb-3">Today's Spend</p>
              <div className="flex items-baseline gap-1">
                <span className="text-5xl font-black text-white">${dailyCost.toFixed(2)}</span>
              </div>
              <p className="text-emerald-200 text-sm mt-1">per day</p>
            </div>

            {/* Monthly Projection */}
            <div className="bg-white/10 backdrop-blur rounded-xl p-5 border border-white/20">
              <p className="text-emerald-200 text-xs font-bold uppercase tracking-widest mb-3">Monthly Projection</p>
              <div className="flex items-baseline gap-1">
                <span className="text-5xl font-black text-white">${monthlyCost.toFixed(0)}</span>
              </div>
              <p className="text-emerald-200 text-sm mt-1">30-day estimate</p>
            </div>

            {/* Savings */}
            <div className={`rounded-xl p-5 border ${totalSavings > 0 ? 'bg-amber-400/20 border-amber-300/30' : 'bg-white/10 border-white/20'}`}>
              <p className={`text-xs font-bold uppercase tracking-widest mb-3 ${totalSavings > 0 ? 'text-amber-200' : 'text-emerald-200'}`}>Potential Savings</p>
              <div className="flex items-baseline gap-1">
                <span className={`text-5xl font-black ${totalSavings > 0 ? 'text-amber-300' : 'text-white'}`}>${totalSavings.toFixed(0)}</span>
              </div>
              <p className={`text-sm mt-1 ${totalSavings > 0 ? 'text-amber-200' : 'text-emerald-200'}`}>per month identified</p>
            </div>
          </div>

          {/* Secondary stats */}
          <div className="flex items-center gap-6 pt-5 border-t border-white/10 flex-wrap">
            <div className="text-sm text-emerald-100">
              <span className="font-bold text-white">{sortedNodes.length}</span> nodes running
            </div>
            <div className="text-sm text-emerald-100">
              <span className="font-bold text-white">${hourlyRate.toFixed(4)}</span>/hr cluster rate
            </div>
            <div className="text-sm text-emerald-100">
              <span className="font-bold text-white">{namespaceCosts.length}</span> active namespaces
            </div>
            {recommendations.length > 0 && (
              <div className="ml-auto flex items-center gap-2 bg-amber-400/20 border border-amber-300/30 rounded-full px-4 py-1.5">
                <i className="fas fa-lightbulb text-amber-300 text-xs"></i>
                <span className="text-amber-200 text-sm font-semibold">{recommendations.length} optimization{recommendations.length > 1 ? 's' : ''} found</span>
              </div>
            )}
          </div>
        </div>
      </div>

      {/* ═══ NAMESPACE COST BREAKDOWN (KubeCost style) ═══ */}
      <div className="bg-white rounded-2xl border border-gray-200 shadow-sm overflow-hidden">
        <div className="px-6 py-5 border-b border-gray-100 flex items-center justify-between">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 bg-indigo-50 rounded-xl flex items-center justify-center">
              <i className="fas fa-layer-group text-indigo-600"></i>
            </div>
            <div>
              <h3 className="text-base font-bold text-gray-900">Namespace Cost Breakdown</h3>
              <p className="text-xs text-gray-500">{namespaceCosts.length} namespaces · sorted by spend</p>
            </div>
          </div>
          <span className="text-xs bg-indigo-50 text-indigo-700 font-bold px-3 py-1.5 rounded-full">
            ${totalNsCost.toFixed(2)}/day total
          </span>
        </div>

        <div className="p-6 space-y-4">
          {namespaceCosts.slice(namespacePage * itemsPerPage, (namespacePage + 1) * itemsPerPage).map((ns: any, i: number) => {
            const idx = namespacePage * itemsPerPage + i;
            const costPct = totalNsCost > 0 ? (ns.dailyCost / totalNsCost) * 100 : 0;
            const barColor = NS_BAR_COLORS[idx % NS_BAR_COLORS.length];
            return (
              <div key={ns.namespace} className="group">
                <div className="flex items-center justify-between mb-2">
                  <div className="flex items-center gap-2">
                    <span className="text-xs text-gray-400 font-mono w-5 text-right">{idx + 1}</span>
                    <div className={`w-2.5 h-2.5 rounded-full ${barColor}`}></div>
                    <span className="font-mono text-sm font-bold text-gray-900 group-hover:text-indigo-600 transition-colors">{ns.namespace}</span>
                    <span className="text-xs bg-gray-100 text-gray-600 px-2 py-0.5 rounded-full font-medium">{ns.podCount} pods</span>
                  </div>
                  <div className="flex items-center gap-4">
                    <span className="text-xs text-gray-400 font-medium">{costPct.toFixed(1)}%</span>
                    <span className="font-bold text-gray-900 text-sm">${(ns.dailyCost || 0).toFixed(2)}<span className="text-xs text-gray-500 font-normal">/day</span></span>
                    <span className="text-xs text-gray-400 w-20 text-right">${((ns.dailyCost || 0) * 30).toFixed(0)}/mo</span>
                  </div>
                </div>
                <div className="h-2 bg-gray-100 rounded-full overflow-hidden ml-7">
                  <div
                    className={`h-full ${barColor} rounded-full transition-all duration-700`}
                    style={{ width: `${Math.max(costPct, 0.5)}%` }}
                  ></div>
                </div>
              </div>
            );
          })}
          {namespaceCosts.length === 0 && (
            <div className="py-12 text-center">
              <i className="fas fa-layer-group text-gray-200 text-4xl mb-3"></i>
              <p className="text-gray-500 text-sm">No namespace cost data available</p>
            </div>
          )}
        </div>

        {namespaceCosts.length > itemsPerPage && (
          <div className="px-6 py-4 bg-gray-50 border-t border-gray-100 flex items-center justify-between">
            <span className="text-sm text-gray-500">
              {namespacePage * itemsPerPage + 1}–{Math.min((namespacePage + 1) * itemsPerPage, namespaceCosts.length)} of {namespaceCosts.length}
            </span>
            <div className="flex gap-2">
              <button onClick={() => setNamespacePage(p => Math.max(0, p - 1))} disabled={namespacePage === 0}
                className="px-3 py-1.5 text-sm bg-white border border-gray-200 rounded-lg hover:bg-gray-50 disabled:opacity-40 font-medium">Previous</button>
              <button onClick={() => setNamespacePage(p => p + 1)} disabled={(namespacePage + 1) * itemsPerPage >= namespaceCosts.length}
                className="px-3 py-1.5 text-sm bg-white border border-gray-200 rounded-lg hover:bg-gray-50 disabled:opacity-40 font-medium">Next</button>
            </div>
          </div>
        )}
      </div>

      {/* ═══ NODE FLEET COST CARDS ═══ */}
      <div className="bg-white rounded-2xl border border-gray-200 shadow-sm overflow-hidden">
        <div className="px-6 py-5 border-b border-gray-100">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 bg-blue-50 rounded-xl flex items-center justify-center">
              <i className="fas fa-server text-blue-600"></i>
            </div>
            <div>
              <h3 className="text-base font-bold text-gray-900">Node Fleet Cost</h3>
              <p className="text-xs text-gray-500">{sortedNodes.length} nodes · by daily spend</p>
            </div>
          </div>
        </div>

        <div className="p-6">
          <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-4">
            {sortedNodes.slice(nodePage * nodeItemsPerPage, (nodePage + 1) * nodeItemsPerPage).map((node: any, i: number) => {
              const isReady = !node.condition || node.condition === 'Ready' || node.condition === 'ready';
              const isSpot = node.capacityType === 'spot';
              const instanceType = node.instanceType || dashboard.nodes?.find((n: any) => n.name === node.nodeName)?.instanceType || 'Unknown';
              return (
                <div key={i} className="relative bg-gradient-to-br from-gray-50 to-white rounded-xl p-5 border border-gray-200 hover:border-blue-300 hover:shadow-md transition-all group">
                  {/* Capacity badge */}
                  {node.capacityType && (
                    <span className={`absolute top-3 right-3 text-xs px-2.5 py-1 rounded-full font-bold ${isSpot ? 'bg-purple-100 text-purple-700' : 'bg-blue-100 text-blue-700'}`}>
                      {isSpot ? '⚡ Spot' : '💎 On-Demand'}
                    </span>
                  )}

                  <div className="flex items-center gap-3 mb-4">
                    <div className={`w-10 h-10 rounded-lg flex items-center justify-center flex-shrink-0 ${isReady ? 'bg-green-100' : 'bg-red-100'}`}>
                      <i className={`fas fa-server text-sm ${isReady ? 'text-green-600' : 'text-red-600'}`}></i>
                    </div>
                    <div className="min-w-0 pr-16">
                      <p className="text-xs font-mono text-gray-900 font-semibold truncate">{node.nodeName}</p>
                      <p className="text-xs text-gray-500 mt-0.5">{instanceType}</p>
                    </div>
                  </div>

                  <div className="flex items-baseline gap-1 mb-1">
                    <span className="text-4xl font-black text-gray-900">${(node.dailyCost || 0).toFixed(2)}</span>
                    <span className="text-xs text-gray-500 mb-1">/day</span>
                  </div>
                  <p className="text-xs text-gray-400 mb-4">${((node.dailyCost || 0) * 30).toFixed(0)}/month · ${(node.hourlyRate || 0).toFixed(4)}/hr</p>

                  <div className={`inline-flex items-center gap-1.5 text-xs px-2.5 py-1 rounded-full font-semibold ${isReady ? 'bg-green-100 text-green-700' : 'bg-red-100 text-red-700'}`}>
                    <div className={`w-1.5 h-1.5 rounded-full ${isReady ? 'bg-green-500 animate-pulse' : 'bg-red-500'}`}></div>
                    {isReady ? 'Running' : (node.condition || 'Not Ready')}
                  </div>
                </div>
              );
            })}
          </div>

          {sortedNodes.length === 0 && (
            <div className="py-12 text-center">
              <i className="fas fa-server text-gray-200 text-4xl mb-3"></i>
              <p className="text-gray-500 text-sm">No node cost data available</p>
            </div>
          )}

          {sortedNodes.length > nodeItemsPerPage && (
            <div className="flex items-center justify-between mt-6 pt-4 border-t border-gray-100">
              <span className="text-sm text-gray-500">
                Showing {nodePage * nodeItemsPerPage + 1}–{Math.min((nodePage + 1) * nodeItemsPerPage, sortedNodes.length)} of {sortedNodes.length}
              </span>
              <div className="flex gap-2">
                <button onClick={() => setNodePage(p => Math.max(0, p - 1))} disabled={nodePage === 0}
                  className="px-3 py-1.5 text-sm bg-white border border-gray-200 rounded-lg hover:bg-gray-50 disabled:opacity-40 font-medium">Previous</button>
                <button onClick={() => setNodePage(p => p + 1)} disabled={(nodePage + 1) * nodeItemsPerPage >= sortedNodes.length}
                  className="px-3 py-1.5 text-sm bg-white border border-gray-200 rounded-lg hover:bg-gray-50 disabled:opacity-40 font-medium">Next</button>
              </div>
            </div>
          )}
        </div>
      </div>

      {/* ═══ WORKLOAD COST LEADERBOARD ═══ */}
      <div className="bg-white rounded-2xl border border-gray-200 shadow-sm overflow-hidden">
        <div className="px-6 py-5 border-b border-gray-100 flex items-center justify-between flex-wrap gap-3">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 bg-purple-50 rounded-xl flex items-center justify-center">
              <i className="fas fa-cubes text-purple-600"></i>
            </div>
            <div>
              <h3 className="text-base font-bold text-gray-900">Workload Cost Ranking</h3>
              <p className="text-xs text-gray-500">{getWorkloadCosts().length} total workloads</p>
            </div>
          </div>
          {/* Workload type tabs */}
          <div className="flex items-center gap-1 bg-gray-100 p-1 rounded-xl">
            {[
              { key: 'deployments', label: 'Deployments', count: deploymentCosts.length, color: 'text-blue-600' },
              { key: 'daemonsets', label: 'DaemonSets', count: daemonSetCosts.length, color: 'text-emerald-600' },
              { key: 'statefulsets', label: 'StatefulSets', count: statefulSetCosts.length, color: 'text-purple-600' },
            ].map(tab => (
              <button
                key={tab.key}
                onClick={() => { setWorkloadTab(tab.key as any); setDeploymentPage(0); setDaemonSetPage(0); setStatefulSetPage(0); }}
                className={`px-3 py-1.5 rounded-lg text-sm font-semibold transition-all ${workloadTab === tab.key ? `bg-white shadow-sm ${tab.color}` : 'text-gray-500 hover:text-gray-700'}`}
              >
                {tab.label}
                <span className="ml-1.5 text-xs opacity-60">({tab.count})</span>
              </button>
            ))}
          </div>
        </div>

        <div className="divide-y divide-gray-50">
          {activeWorkloads.slice(activeWorkloadPage * itemsPerPage, (activeWorkloadPage + 1) * itemsPerPage).map((wl: any, i: number) => {
            const rank = activeWorkloadPage * itemsPerPage + i + 1;
            const costShare = (wl.dailyCost / Math.max(maxWorkloadCost, 0.01)) * 100;
            const kindColors: Record<string, string> = { Deployment: 'bg-blue-100 text-blue-700', DaemonSet: 'bg-emerald-100 text-emerald-700', StatefulSet: 'bg-purple-100 text-purple-700' };
            const barColors: Record<string, string> = { Deployment: 'bg-blue-500', DaemonSet: 'bg-emerald-500', StatefulSet: 'bg-purple-500' };
            const rankColors = ['text-yellow-500', 'text-gray-400', 'text-amber-600'];
            return (
              <div key={i} className="px-6 py-4 hover:bg-gray-50 transition-colors group">
                <div className="flex items-center gap-4">
                  <span className={`text-lg font-black w-6 text-center flex-shrink-0 ${rank <= 3 ? rankColors[rank - 1] : 'text-gray-300'}`}>
                    {rank <= 3 ? ['①', '②', '③'][rank - 1] : rank}
                  </span>
                  <div className="flex-1 min-w-0">
                    <div className="flex items-center gap-2 mb-1">
                      <span className="font-bold text-gray-900 text-sm truncate group-hover:text-purple-600 transition-colors">{wl.name}</span>
                      <span className={`text-xs px-2 py-0.5 rounded-full font-bold flex-shrink-0 ${kindColors[wl.kind] || 'bg-gray-100 text-gray-600'}`}>{wl.kind}</span>
                      <span className="text-xs bg-gray-100 text-gray-500 px-2 py-0.5 rounded-full font-medium flex-shrink-0">{wl.namespace}</span>
                    </div>
                    <div className="flex items-center gap-3">
                      <div className="flex-1 h-1.5 bg-gray-100 rounded-full overflow-hidden">
                        <div className={`h-full ${barColors[wl.kind] || 'bg-gray-400'} rounded-full`} style={{ width: `${costShare}%` }}></div>
                      </div>
                      <span className="text-xs text-gray-400 flex-shrink-0">{wl.podCount} pod{wl.podCount !== 1 ? 's' : ''}</span>
                    </div>
                  </div>
                  <div className="text-right flex-shrink-0">
                    <div className="text-sm font-black text-gray-900">${(wl.dailyCost || 0).toFixed(2)}<span className="text-xs text-gray-400 font-normal">/day</span></div>
                    <div className="text-xs text-gray-400">${(wl.monthlyCost || 0).toFixed(0)}/mo</div>
                  </div>
                </div>
              </div>
            );
          })}

          {activeWorkloads.length === 0 && (
            <div className="px-6 py-12 text-center text-gray-500">
              <i className="fas fa-cubes text-gray-200 text-3xl mb-3"></i>
              <p className="text-sm">No {workloadTab} cost data available</p>
            </div>
          )}
        </div>

        {activeWorkloads.length > itemsPerPage && (
          <div className="px-6 py-4 bg-gray-50 border-t border-gray-100 flex items-center justify-between">
            <span className="text-sm text-gray-500">
              {activeWorkloadPage * itemsPerPage + 1}–{Math.min((activeWorkloadPage + 1) * itemsPerPage, activeWorkloads.length)} of {activeWorkloads.length}
            </span>
            <div className="flex gap-2">
              <button onClick={() => setActiveWorkloadPage(p => Math.max(0, p - 1))} disabled={activeWorkloadPage === 0}
                className="px-3 py-1.5 text-sm bg-white border border-gray-200 rounded-lg hover:bg-gray-50 disabled:opacity-40 font-medium">Previous</button>
              <button onClick={() => setActiveWorkloadPage(p => p + 1)} disabled={(activeWorkloadPage + 1) * itemsPerPage >= activeWorkloads.length}
                className="px-3 py-1.5 text-sm bg-white border border-gray-200 rounded-lg hover:bg-gray-50 disabled:opacity-40 font-medium">Next</button>
            </div>
          </div>
        )}
      </div>

      {/* ═══ SAVINGS OPPORTUNITIES ═══ */}
      {recommendations.length > 0 && (
        <div className="bg-gradient-to-br from-amber-50 via-yellow-50 to-orange-50 rounded-2xl border-2 border-amber-200 shadow-sm overflow-hidden">
          <div className="px-6 py-5 border-b border-amber-200/60 flex items-center justify-between">
            <div className="flex items-center gap-3">
              <div className="w-10 h-10 bg-amber-100 rounded-xl flex items-center justify-center">
                <i className="fas fa-lightbulb text-amber-600"></i>
              </div>
              <div>
                <h3 className="text-base font-bold text-gray-900">Savings Opportunities</h3>
                <p className="text-xs text-gray-600">{recommendations.length} optimization{recommendations.length > 1 ? 's' : ''} identified</p>
              </div>
            </div>
            <div className="bg-emerald-100 border border-emerald-200 rounded-xl px-4 py-2">
              <p className="text-xs text-emerald-600 font-semibold uppercase tracking-wide">Potential savings</p>
              <p className="text-xl font-black text-emerald-700">${totalSavings.toFixed(2)}<span className="text-sm font-normal text-emerald-600">/mo</span></p>
            </div>
          </div>

          <div className="p-6 space-y-4">
            {recommendations.map((rec) => {
              const prioConfig = {
                high: { bg: 'bg-red-50', border: 'border-red-200', icon: 'text-red-600', badge: 'bg-red-100 text-red-700', iconName: 'exclamation-circle' },
                medium: { bg: 'bg-amber-50', border: 'border-amber-200', icon: 'text-amber-600', badge: 'bg-amber-100 text-amber-700', iconName: 'exclamation-triangle' },
                low: { bg: 'bg-blue-50', border: 'border-blue-200', icon: 'text-blue-600', badge: 'bg-blue-100 text-blue-700', iconName: 'info-circle' },
              }[rec.priority];
              return (
                <div key={rec.id} className={`${prioConfig.bg} border-2 ${prioConfig.border} rounded-xl p-5`}>
                  <div className="flex items-start gap-4">
                    <div className={`w-10 h-10 rounded-xl flex items-center justify-center flex-shrink-0 bg-white border ${prioConfig.border}`}>
                      <i className={`fas fa-${prioConfig.iconName} ${prioConfig.icon}`}></i>
                    </div>
                    <div className="flex-1 min-w-0">
                      <div className="flex items-start justify-between gap-3 mb-2">
                        <div>
                          <h4 className="font-bold text-gray-900 text-sm">{rec.title}</h4>
                          <span className={`inline-flex items-center gap-1 text-xs font-bold uppercase px-2 py-0.5 rounded-full mt-1 ${prioConfig.badge}`}>
                            {rec.priority} priority
                          </span>
                        </div>
                        {rec.savingsEstimate > 0 && (
                          <div className="text-right flex-shrink-0 bg-emerald-50 border border-emerald-200 rounded-lg px-3 py-2">
                            <div className="text-lg font-black text-emerald-600">${rec.savingsEstimate.toFixed(2)}</div>
                            <div className="text-xs text-emerald-500">potential/month</div>
                          </div>
                        )}
                      </div>
                      <p className="text-sm text-gray-700 mb-2">{rec.description}</p>
                      <p className="text-sm font-semibold text-gray-900 mb-3">{rec.impact}</p>

                      <button
                        onClick={() => setShowRecommendationInfo(showRecommendationInfo === rec.id ? null : rec.id)}
                        className="flex items-center gap-1.5 text-xs text-gray-500 hover:text-gray-800 font-medium mb-2"
                      >
                        <i className="fas fa-info-circle"></i>
                        How is this calculated?
                        <i className={`fas fa-chevron-${showRecommendationInfo === rec.id ? 'up' : 'down'} text-xs`}></i>
                      </button>
                      {showRecommendationInfo === rec.id && (
                        <div className="bg-white rounded-lg p-3 mb-3 text-xs text-gray-700 border border-gray-200">
                          <strong>Calculation Basis:</strong> {rec.basisExplanation}
                        </div>
                      )}

                      <div className="grid grid-cols-1 md:grid-cols-2 gap-1 mb-2">
                        {rec.actionItems.map((action, idx) => (
                          <div key={idx} className="flex items-start gap-2 text-xs text-gray-700">
                            <i className="fas fa-check text-emerald-500 mt-0.5 flex-shrink-0"></i>
                            <span>{action}</span>
                          </div>
                        ))}
                      </div>
                      {rec.affectedResources.length > 0 && (
                        <p className="text-xs text-gray-500 mt-2">
                          <strong>Affected:</strong> {rec.affectedResources.slice(0, 3).join(', ')}{rec.affectedResources.length > 3 ? ` +${rec.affectedResources.length - 3} more` : ''}
                        </p>
                      )}
                    </div>
                  </div>
                </div>
              );
            })}
          </div>
        </div>
      )}
    </div>
  );
};

export default EksCostTab;
