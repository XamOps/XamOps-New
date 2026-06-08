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


const EksCostTab: React.FC<EksCostTabProps> = ({ eksData }) => {
  const { dashboard, sortedNodesByCost } = eksData;
  // const [showRecommendationInfo, setShowRecommendationInfo] = useState<string | null>(null);
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

  // In agent mode, pods come from k8sPods (k8s-resources collector), not workloadHealth.pods
  const allPods: any[] = (dashboard.k8sPods?.length ? dashboard.k8sPods : dashboard.workloadHealth?.pods) || [];

  function getNamespaceCosts() {
    let costs = dashboard.costMetrics?.namespaceCosts || [];
    if (costs.length > 0) {
      costs.forEach((ns: any) => {
        if (!ns.podCount) {
          ns.podCount = allPods.filter((p: any) => p.namespace === ns.namespace).length || 0;
        }
      });
      return costs.sort((a: any, b: any) => b.dailyCost - a.dailyCost);
    }
    const namespaceMap = new Map<string, any>();
    allPods.forEach((pod: any) => {
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
          const wlPods = allPods.filter(
            (p: any) => p.namespace === wl.namespace &&
              (p.ownerName === wl.name || p.controllerName === wl.name || p.name.startsWith(wl.name))
          );
          wl.podCount = wlPods.length || wl.replicas || 0;
        }
      });
      return costs.sort((a: any, b: any) => b.dailyCost - a.dailyCost);
    }
    const workloads: any[] = [];

    // Use k8sDeployments (agent mode) or workloadHealth.deployments (prometheus mode)
    const deployments: any[] = (dashboard.k8sDeployments?.length ? dashboard.k8sDeployments : dashboard.workloadHealth?.deployments) || [];
    deployments.forEach((deployment: any) => {
      const pods = allPods.filter(
        (pod: any) => pod.namespace === deployment.namespace &&
          (pod.ownerName === deployment.name || pod.controllerName === deployment.name || pod.name.includes(deployment.name))
      );
      let cpu = 0, mem = 0;
      pods.forEach((pod: any) => { cpu += pod.cpuUsage || 0; mem += (pod.memoryUsage || 0) / (1024 * 1024); });
      const daily = (cpu / 1000) * 0.0416 * 24 + (mem / 1024) * 0.00456 * 24;
      workloads.push({ name: deployment.name, namespace: deployment.namespace, kind: 'Deployment', cpuUsageMillicores: cpu, memoryUsageMb: mem, podCount: pods.length || deployment.readyReplicas || deployment.replicas || 0, dailyCost: daily, monthlyCost: daily * 30 });
    });

    const daemonSets: any[] = (dashboard.k8sDaemonSets?.length ? dashboard.k8sDaemonSets : dashboard.workloadHealth?.daemonsets) || [];
    daemonSets.forEach((ds: any) => {
      const pods = allPods.filter(
        (pod: any) => pod.namespace === ds.namespace &&
          (pod.ownerName === ds.name || pod.controllerName === ds.name || pod.name.includes(ds.name))
      );
      let cpu = 0, mem = 0;
      pods.forEach((pod: any) => { cpu += pod.cpuUsage || 0; mem += (pod.memoryUsage || 0) / (1024 * 1024); });
      const daily = (cpu / 1000) * 0.0416 * 24 + (mem / 1024) * 0.00456 * 24;
      workloads.push({ name: ds.name, namespace: ds.namespace, kind: 'DaemonSet', cpuUsageMillicores: cpu, memoryUsageMb: mem, podCount: pods.length || ds.numberReady || ds.ready || 0, dailyCost: daily, monthlyCost: daily * 30 });
    });

    const statefulSets: any[] = (dashboard.k8sStatefulSets?.length ? dashboard.k8sStatefulSets : dashboard.workloadHealth?.statefulsets) || [];
    statefulSets.forEach((ss: any) => {
      const pods = allPods.filter(
        (pod: any) => pod.namespace === ss.namespace &&
          (pod.ownerName === ss.name || pod.controllerName === ss.name || pod.name.includes(ss.name))
      );
      let cpu = 0, mem = 0;
      pods.forEach((pod: any) => { cpu += pod.cpuUsage || 0; mem += (pod.memoryUsage || 0) / (1024 * 1024); });
      const daily = (cpu / 1000) * 0.0416 * 24 + (mem / 1024) * 0.00456 * 24;
      workloads.push({ name: ss.name, namespace: ss.namespace, kind: 'StatefulSet', cpuUsageMillicores: cpu, memoryUsageMb: mem, podCount: pods.length || ss.readyReplicas || ss.replicas || 0, dailyCost: daily, monthlyCost: daily * 30 });
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
    <div className="space-y-5">

      {/* ── KPI Strip ── */}
      <div className="grid grid-cols-2 sm:grid-cols-4 gap-3">
        <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-lg px-5 py-4">
          <div className="text-[11px] font-medium uppercase tracking-wider text-[rgba(0,0,0,0.4)] mb-1">TODAY'S SPEND</div>
          <div className="text-[26px] font-semibold leading-none text-[rgba(0,0,0,0.88)]">${dailyCost.toFixed(2)}</div>
          <div className="text-[11px] text-[rgba(0,0,0,0.4)] mt-1">per day · ${hourlyRate.toFixed(4)}/hr</div>
        </div>
        <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-lg px-5 py-4">
          <div className="text-[11px] font-medium uppercase tracking-wider text-[rgba(0,0,0,0.4)] mb-1">MONTHLY EST.</div>
          <div className="text-[26px] font-semibold leading-none text-[rgba(0,0,0,0.88)]">${monthlyCost.toFixed(0)}</div>
          <div className="text-[11px] text-[rgba(0,0,0,0.4)] mt-1">30-day projection</div>
        </div>
        <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-lg px-5 py-4">
          <div className="text-[11px] font-medium uppercase tracking-wider text-[rgba(0,0,0,0.4)] mb-1">POTENTIAL SAVINGS</div>
          <div className={`text-[26px] font-semibold leading-none ${totalSavings > 0 ? 'text-[#1a7f37]' : 'text-[rgba(0,0,0,0.88)]'}`}>${totalSavings.toFixed(0)}</div>
          <div className="text-[11px] text-[rgba(0,0,0,0.4)] mt-1">per month identified</div>
        </div>
        <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-lg px-5 py-4">
          <div className="text-[11px] font-medium uppercase tracking-wider text-[rgba(0,0,0,0.4)] mb-1">ACTIVE NAMESPACES</div>
          <div className="text-[26px] font-semibold leading-none text-[rgba(0,0,0,0.88)]">{namespaceCosts.length}</div>
          <div className="text-[11px] text-[rgba(0,0,0,0.4)] mt-1">{sortedNodes.length} nodes running</div>
        </div>
      </div>

      {/* ── Namespace Cost Breakdown ── */}
      <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-lg overflow-hidden">
        <div className="px-5 py-3.5 border-b border-[rgba(0,0,0,0.06)] flex items-center justify-between">
          <span className="text-sm font-semibold text-[rgba(0,0,0,0.88)]">Namespace Cost Breakdown</span>
          <span className="rounded-full px-2.5 py-0.5 text-[11px] font-semibold border border-[rgba(0,0,0,0.12)] text-[rgba(0,0,0,0.5)]">
            ${totalNsCost.toFixed(2)}/day · {namespaceCosts.length} namespaces
          </span>
        </div>

        <div className="p-5 space-y-3">
          {namespaceCosts.slice(namespacePage * itemsPerPage, (namespacePage + 1) * itemsPerPage).map((ns: any, i: number) => {
            const idx = namespacePage * itemsPerPage + i;
            const costPct = totalNsCost > 0 ? (ns.dailyCost / totalNsCost) * 100 : 0;
            return (
              <div key={ns.namespace}>
                <div className="flex items-center justify-between mb-1">
                  <div className="flex items-center gap-2">
                    <span className="text-[11px] text-[rgba(0,0,0,0.4)] font-mono w-4">{idx + 1}</span>
                    <span className="font-mono text-[13px] font-semibold text-[rgba(0,0,0,0.88)]">{ns.namespace}</span>
                    <span className="rounded-full px-2 py-0.5 text-[11px] font-semibold border border-[rgba(0,0,0,0.12)] text-[rgba(0,0,0,0.5)]">{ns.podCount} pods</span>
                  </div>
                  <div className="flex items-center gap-3">
                    <span className="text-[11px] text-[rgba(0,0,0,0.4)]">{costPct.toFixed(1)}%</span>
                    <span className="text-[13px] font-semibold text-[rgba(0,0,0,0.88)]">${(ns.dailyCost || 0).toFixed(2)}<span className="text-[11px] text-[rgba(0,0,0,0.4)] font-normal">/day</span></span>
                    <span className="text-[11px] text-[rgba(0,0,0,0.4)] w-16 text-right">${((ns.dailyCost || 0) * 30).toFixed(0)}/mo</span>
                  </div>
                </div>
                <div className="h-1.5 bg-[rgba(0,0,0,0.06)] rounded-full overflow-hidden ml-6">
                  <div className="h-full bg-[rgba(0,0,0,0.88)] rounded-full transition-all duration-700"
                    style={{ width: `${Math.max(costPct, 0.5)}%` }} />
                </div>
              </div>
            );
          })}
          {namespaceCosts.length === 0 && (
            <div className="py-12 text-center">
              <i className="fas fa-layer-group text-[rgba(0,0,0,0.4)] text-3xl mb-3"></i>
              <p className="text-[13px] text-[rgba(0,0,0,0.5)]">No namespace cost data available</p>
            </div>
          )}
        </div>

        {namespaceCosts.length > itemsPerPage && (
          <div className="px-5 py-3 bg-[rgba(0,0,0,0.02)] border-t border-[rgba(0,0,0,0.06)] flex items-center justify-between">
            <span className="text-[11px] text-[rgba(0,0,0,0.4)]">
              {namespacePage * itemsPerPage + 1}–{Math.min((namespacePage + 1) * itemsPerPage, namespaceCosts.length)} of {namespaceCosts.length}
            </span>
            <div className="flex gap-2">
              <button onClick={() => setNamespacePage(p => Math.max(0, p - 1))} disabled={namespacePage === 0}
                className="px-3 py-1.5 text-[12px] font-semibold bg-white border border-[rgba(0,0,0,0.12)] rounded-lg hover:bg-[rgba(0,0,0,0.02)] disabled:opacity-40 text-[rgba(0,0,0,0.88)]">Previous</button>
              <button onClick={() => setNamespacePage(p => p + 1)} disabled={(namespacePage + 1) * itemsPerPage >= namespaceCosts.length}
                className="px-3 py-1.5 text-[12px] font-semibold bg-white border border-[rgba(0,0,0,0.12)] rounded-lg hover:bg-[rgba(0,0,0,0.02)] disabled:opacity-40 text-[rgba(0,0,0,0.88)]">Next</button>
            </div>
          </div>
        )}
      </div>

      {/* ── Node Fleet Cost ── */}
      <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-lg overflow-hidden">
        <div className="px-5 py-3.5 border-b border-[rgba(0,0,0,0.06)]">
          <span className="text-sm font-semibold text-[rgba(0,0,0,0.88)]">Node Fleet Cost</span>
          <span className="ml-2 text-[11px] text-[rgba(0,0,0,0.4)]">{sortedNodes.length} nodes · by daily spend</span>
        </div>

        <div className="p-5">
          <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-3">
            {sortedNodes.slice(nodePage * nodeItemsPerPage, (nodePage + 1) * nodeItemsPerPage).map((node: any, i: number) => {
              const isReady = !node.condition || node.condition === 'Ready' || node.condition === 'ready';
              const isSpot = node.capacityType === 'spot';
              const instanceType = node.instanceType || dashboard.nodes?.find((n: any) => n.name === node.nodeName)?.instanceType || 'Unknown';
              return (
                <div key={i} className="bg-white border border-[rgba(0,0,0,0.12)] rounded-lg p-4 hover:bg-[rgba(0,0,0,0.02)] transition-colors">
                  <div className="flex items-start justify-between mb-3">
                    <div className="min-w-0 flex-1">
                      <p className="text-[13px] font-mono font-semibold text-[rgba(0,0,0,0.88)] truncate">{node.nodeName}</p>
                      <p className="text-[11px] text-[rgba(0,0,0,0.4)] mt-0.5">{instanceType}</p>
                    </div>
                    {node.capacityType && (
                      <span className="rounded-full px-2.5 py-0.5 text-[11px] font-semibold border ml-2 flex-shrink-0"
                        style={isSpot
                          ? { color: '#0550ae', background: '#ddf4ff', borderColor: 'rgba(84,174,255,0.4)' }
                          : { color: 'rgba(0,0,0,0.5)', background: 'rgba(0,0,0,0.02)', borderColor: 'rgba(0,0,0,0.12)' }}>
                        {isSpot ? 'Spot' : 'On-Demand'}
                      </span>
                    )}
                  </div>

                  <div className="text-[26px] font-semibold text-[rgba(0,0,0,0.88)] leading-none mb-1">
                    ${(node.dailyCost || 0).toFixed(2)}<span className="text-[13px] text-[rgba(0,0,0,0.4)] font-normal">/day</span>
                  </div>
                  <p className="text-[11px] text-[rgba(0,0,0,0.4)] mb-3">${((node.dailyCost || 0) * 30).toFixed(0)}/mo · ${(node.hourlyRate || 0).toFixed(4)}/hr</p>

                  <span className="rounded-full px-2.5 py-0.5 text-[11px] font-semibold border"
                    style={isReady
                      ? { color: '#1a7f37', background: '#dafbe1', borderColor: 'rgba(74,194,107,0.4)' }
                      : { color: '#cf222e', background: '#ffebe9', borderColor: 'rgba(255,129,130,0.4)' }}>
                    {isReady ? 'Ready' : (node.condition || 'Not Ready')}
                  </span>
                </div>
              );
            })}
          </div>

          {sortedNodes.length === 0 && (
            <div className="py-10 text-center col-span-3">
              <p className="text-[13px] text-[rgba(0,0,0,0.5)]">No node cost data available</p>
            </div>
          )}
        </div>

          {sortedNodes.length > nodeItemsPerPage && (
            <div className="flex items-center justify-between mt-4 pt-4 border-t border-[rgba(0,0,0,0.06)]">
              <span className="text-[11px] text-[rgba(0,0,0,0.4)]">
                {nodePage * nodeItemsPerPage + 1}–{Math.min((nodePage + 1) * nodeItemsPerPage, sortedNodes.length)} of {sortedNodes.length}
              </span>
              <div className="flex gap-2">
                <button onClick={() => setNodePage(p => Math.max(0, p - 1))} disabled={nodePage === 0}
                  className="px-3 py-1.5 text-[12px] font-semibold bg-white border border-[rgba(0,0,0,0.12)] rounded-lg hover:bg-[rgba(0,0,0,0.02)] disabled:opacity-40 text-[rgba(0,0,0,0.88)]">Previous</button>
                <button onClick={() => setNodePage(p => p + 1)} disabled={(nodePage + 1) * nodeItemsPerPage >= sortedNodes.length}
                  className="px-3 py-1.5 text-[12px] font-semibold bg-white border border-[rgba(0,0,0,0.12)] rounded-lg hover:bg-[rgba(0,0,0,0.02)] disabled:opacity-40 text-[rgba(0,0,0,0.88)]">Next</button>
              </div>
            </div>
          )}
      </div>

      {/* ── Workload Cost Ranking ── */}
      <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-lg overflow-hidden">
        <div className="px-5 py-3.5 border-b border-[rgba(0,0,0,0.06)] flex items-center justify-between flex-wrap gap-3">
          <span className="text-sm font-semibold text-[rgba(0,0,0,0.88)]">Workload Cost Ranking</span>
          <div className="flex items-center gap-1">
            {[
              { key: 'deployments', label: 'Deployments', count: deploymentCosts.length },
              { key: 'daemonsets', label: 'DaemonSets', count: daemonSetCosts.length },
              { key: 'statefulsets', label: 'StatefulSets', count: statefulSetCosts.length },
            ].map(tab => (
              <button key={tab.key}
                onClick={() => { setWorkloadTab(tab.key as any); setDeploymentPage(0); setDaemonSetPage(0); setStatefulSetPage(0); }}
                className={`px-3 py-1.5 rounded-lg text-[12px] font-semibold transition-colors ${workloadTab === tab.key ? 'bg-[rgba(0,0,0,0.88)] text-white' : 'bg-white text-[rgba(0,0,0,0.5)] border border-[rgba(0,0,0,0.12)] hover:bg-[rgba(0,0,0,0.02)]'}`}>
                {tab.label} <span className="opacity-70">({tab.count})</span>
              </button>
            ))}
          </div>
        </div>

        <table className="w-full">
          <thead>
            <tr className="bg-[rgba(0,0,0,0.02)] border-b border-[rgba(0,0,0,0.12)]">
              <th className="px-5 py-2 text-left text-[11px] font-semibold uppercase tracking-wider text-[rgba(0,0,0,0.4)]">#</th>
              <th className="px-5 py-2 text-left text-[11px] font-semibold uppercase tracking-wider text-[rgba(0,0,0,0.4)]">Workload</th>
              <th className="px-5 py-2 text-left text-[11px] font-semibold uppercase tracking-wider text-[rgba(0,0,0,0.4)]">Namespace</th>
              <th className="px-5 py-2 text-left text-[11px] font-semibold uppercase tracking-wider text-[rgba(0,0,0,0.4)]">Usage share</th>
              <th className="px-5 py-2 text-right text-[11px] font-semibold uppercase tracking-wider text-[rgba(0,0,0,0.4)]">Daily</th>
              <th className="px-5 py-2 text-right text-[11px] font-semibold uppercase tracking-wider text-[rgba(0,0,0,0.4)]">Monthly</th>
            </tr>
          </thead>
          <tbody>
            {activeWorkloads.slice(activeWorkloadPage * itemsPerPage, (activeWorkloadPage + 1) * itemsPerPage).map((wl: any, i: number) => {
              const rank = activeWorkloadPage * itemsPerPage + i + 1;
              const costShare = (wl.dailyCost / Math.max(maxWorkloadCost, 0.01)) * 100;
              return (
                <tr key={i} className="border-b border-[rgba(0,0,0,0.06)] hover:bg-[rgba(0,0,0,0.02)] transition-colors">
                  <td className="px-5 py-2.5 text-[11px] text-[rgba(0,0,0,0.4)] font-mono">{rank}</td>
                  <td className="px-5 py-2.5">
                    <div className="flex items-center gap-2">
                      <span className="text-[13px] font-semibold text-[rgba(0,0,0,0.88)] truncate max-w-[180px]">{wl.name}</span>
                      <span className="rounded-full px-2 py-0.5 text-[11px] font-semibold border border-[rgba(0,0,0,0.12)] text-[rgba(0,0,0,0.5)]">{wl.kind}</span>
                    </div>
                  </td>
                  <td className="px-5 py-2.5 text-[13px] font-mono text-[rgba(0,0,0,0.5)]">{wl.namespace}</td>
                  <td className="px-5 py-2.5 w-36">
                    <div className="flex items-center gap-2">
                      <div className="flex-1 h-1.5 bg-[rgba(0,0,0,0.06)] rounded-full overflow-hidden">
                        <div className="h-full bg-[rgba(0,0,0,0.88)] rounded-full" style={{ width: `${costShare}%` }} />
                      </div>
                      <span className="text-[11px] text-[rgba(0,0,0,0.4)] w-8">{wl.podCount}p</span>
                    </div>
                  </td>
                  <td className="px-5 py-2.5 text-right text-[13px] font-semibold text-[rgba(0,0,0,0.88)]">${(wl.dailyCost || 0).toFixed(2)}</td>
                  <td className="px-5 py-2.5 text-right text-[11px] text-[rgba(0,0,0,0.4)]">${(wl.monthlyCost || 0).toFixed(0)}</td>
                </tr>
              );
            })}
          </tbody>
        </table>
        {activeWorkloads.length === 0 && (
          <div className="py-10 text-center">
            <p className="text-[13px] text-[rgba(0,0,0,0.5)]">No {workloadTab} cost data available</p>
          </div>
        )}
        {activeWorkloads.length > itemsPerPage && (
          <div className="px-5 py-3 bg-[rgba(0,0,0,0.02)] border-t border-[rgba(0,0,0,0.06)] flex items-center justify-between">
            <span className="text-[11px] text-[rgba(0,0,0,0.4)]">
              {activeWorkloadPage * itemsPerPage + 1}–{Math.min((activeWorkloadPage + 1) * itemsPerPage, activeWorkloads.length)} of {activeWorkloads.length}
            </span>
            <div className="flex gap-2">
              <button onClick={() => setActiveWorkloadPage(p => Math.max(0, p - 1))} disabled={activeWorkloadPage === 0}
                className="px-3 py-1.5 text-[12px] font-semibold bg-white border border-[rgba(0,0,0,0.12)] rounded-lg hover:bg-[rgba(0,0,0,0.02)] disabled:opacity-40 text-[rgba(0,0,0,0.88)]">Previous</button>
              <button onClick={() => setActiveWorkloadPage(p => p + 1)} disabled={(activeWorkloadPage + 1) * itemsPerPage >= activeWorkloads.length}
                className="px-3 py-1.5 text-[12px] font-semibold bg-white border border-[rgba(0,0,0,0.12)] rounded-lg hover:bg-[rgba(0,0,0,0.02)] disabled:opacity-40 text-[rgba(0,0,0,0.88)]">Next</button>
            </div>
          </div>
        )}
      </div>

      {/* ── Savings Opportunities (hidden) ── */}
      {/* {recommendations.length > 0 && (
        <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-lg overflow-hidden">
          <div className="px-5 py-3.5 border-b border-[rgba(0,0,0,0.06)] flex items-center justify-between">
            <span className="text-sm font-semibold text-[rgba(0,0,0,0.88)]">Savings Opportunities</span>
            <span className="rounded-full px-2.5 py-0.5 text-[11px] font-semibold border"
              style={{ color: '#1a7f37', background: '#dafbe1', borderColor: 'rgba(74,194,107,0.4)' }}>
              ${totalSavings.toFixed(0)}/mo potential
            </span>
          </div>

          <div className="p-5 space-y-3">
            {recommendations.map((rec) => {
              const prioStyle = rec.priority === 'high'
                ? { color: '#cf222e', bg: '#ffebe9', border: 'rgba(255,129,130,0.4)' }
                : rec.priority === 'medium'
                ? { color: '#9a6700', bg: '#fff8c5', border: 'rgba(212,167,44,0.4)' }
                : { color: '#0550ae', bg: '#ddf4ff', border: 'rgba(84,174,255,0.4)' };
              return (
                <div key={rec.id} className="border border-[rgba(0,0,0,0.12)] rounded-lg p-4 hover:bg-[rgba(0,0,0,0.02)] transition-colors">
                  <div className="flex items-start justify-between gap-3 mb-2">
                    <div className="flex-1 min-w-0">
                      <div className="flex items-center gap-2 mb-1 flex-wrap">
                        <h4 className="text-sm font-semibold text-[rgba(0,0,0,0.88)]">{rec.title}</h4>
                        <span className="rounded-full px-2.5 py-0.5 text-[11px] font-semibold border"
                          style={{ color: prioStyle.color, background: prioStyle.bg, borderColor: prioStyle.border }}>
                          {rec.priority} priority
                        </span>
                      </div>
                      <p className="text-[13px] text-[rgba(0,0,0,0.5)] mb-1">{rec.description}</p>
                      <p className="text-[13px] font-semibold text-[rgba(0,0,0,0.88)]">{rec.impact}</p>
                    </div>
                    {rec.savingsEstimate > 0 && (
                      <div className="text-right flex-shrink-0">
                        <div className="text-[26px] font-semibold text-[#1a7f37] leading-none">${rec.savingsEstimate.toFixed(0)}</div>
                        <div className="text-[11px] text-[rgba(0,0,0,0.4)]">/month</div>
                      </div>
                    )}
                  </div>
                  <button onClick={() => setShowRecommendationInfo(showRecommendationInfo === rec.id ? null : rec.id)}
                    className="text-[12px] text-[rgba(0,0,0,0.88)] hover:underline">
                    {showRecommendationInfo === rec.id ? '▲' : '▼'} How is this calculated?
                  </button>
                  {showRecommendationInfo === rec.id && (
                    <div className="mt-2 p-3 bg-[rgba(0,0,0,0.02)] rounded-lg border border-[rgba(0,0,0,0.12)] text-[11px] text-[rgba(0,0,0,0.5)]">
                      {rec.basisExplanation}
                    </div>
                  )}
                </div>
              );
            })}
          </div>
        </div>
      )} */}
    </div>
  );
};

export default EksCostTab;
