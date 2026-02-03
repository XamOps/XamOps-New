/**
 * EKS Cost Tab
 *
 * Professional cost analysis with intelligent recommendations
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
  const [showRecommendationInfo, setShowRecommendationInfo] = useState<string | null>(null);

  const [deploymentPage, setDeploymentPage] = useState(0);
  const [daemonSetPage, setDaemonSetPage] = useState(0);
  const [statefulSetPage, setStatefulSetPage] = useState(0);
  const [namespacePage, setNamespacePage] = useState(0);
  const [nodePage, setNodePage] = useState(0);
  const itemsPerPage = 10;
  const nodeItemsPerPage = 5;

  if (!dashboard) return null;

  // Helper functions
  function getNamespaceCosts() {
    let costs = dashboard.costMetrics?.namespaceCosts || [];

    // If API returns costs but missing pod counts, fill them in
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
        namespaceMap.set(ns, {
          namespace: ns,
          cpuUsageMillicores: 0,
          memoryUsageMb: 0,
          podCount: 0,
          dailyCost: 0,
          monthlyCost: 0
        });
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
    result.forEach(ns => {
      ns.monthlyCost = ns.dailyCost * 30;
    });

    return result.sort((a, b) => b.dailyCost - a.dailyCost);
  }

  function getWorkloadCosts() {
    let costs = dashboard.costMetrics?.workloadCosts || [];

    // If API returns costs but missing pod counts, fill them in
    if (costs.length > 0) {
      costs.forEach((wl: any) => {
        if (!wl.podCount) {
          const wlPods = dashboard.workloadHealth?.pods?.filter(
            (p: any) => p.namespace === wl.namespace &&
              (p.ownerName === wl.name || p.name.startsWith(wl.name))
          ) || [];
          wl.podCount = wlPods.length || wl.replicas || 0;
        }
      });
      return costs.sort((a: any, b: any) => b.dailyCost - a.dailyCost);
    }

    const workloads: any[] = [];

    // Process Deployments
    dashboard.workloadHealth?.deployments?.forEach((deployment: any) => {
      const deploymentPods = dashboard.workloadHealth?.pods?.filter(
        (pod: any) => pod.namespace === deployment.namespace &&
          (pod.ownerName === deployment.name || pod.name.includes(deployment.name))
      ) || [];

      let cpuUsage = 0;
      let memoryUsage = 0;
      deploymentPods.forEach((pod: any) => {
        cpuUsage += pod.cpuUsage || 0;
        memoryUsage += (pod.memoryUsage || 0) / (1024 * 1024);
      });

      const cpuCost = (cpuUsage / 1000) * 0.0416 * 24;
      const memCost = (memoryUsage / 1024) * 0.00456 * 24;
      const dailyCost = cpuCost + memCost;

      workloads.push({
        name: deployment.name,
        namespace: deployment.namespace,
        kind: 'Deployment',
        cpuUsageMillicores: cpuUsage,
        memoryUsageMb: memoryUsage,
        podCount: deploymentPods.length || deployment.replicas || 0,
        dailyCost,
        monthlyCost: dailyCost * 30
      });
    });

    // Process DaemonSets
    dashboard.workloadHealth?.daemonsets?.forEach((ds: any) => {
      const dsPods = dashboard.workloadHealth?.pods?.filter(
        (pod: any) => pod.namespace === ds.namespace &&
          (pod.ownerName === ds.name || pod.name.includes(ds.name))
      ) || [];

      let cpuUsage = 0;
      let memoryUsage = 0;
      dsPods.forEach((pod: any) => {
        cpuUsage += pod.cpuUsage || 0;
        memoryUsage += (pod.memoryUsage || 0) / (1024 * 1024);
      });

      const cpuCost = (cpuUsage / 1000) * 0.0416 * 24;
      const memCost = (memoryUsage / 1024) * 0.00456 * 24;
      const dailyCost = cpuCost + memCost;

      workloads.push({
        name: ds.name,
        namespace: ds.namespace,
        kind: 'DaemonSet',
        cpuUsageMillicores: cpuUsage,
        memoryUsageMb: memoryUsage,
        podCount: dsPods.length || ds.ready || 0,
        dailyCost,
        monthlyCost: dailyCost * 30
      });
    });

    // Process StatefulSets
    dashboard.workloadHealth?.statefulsets?.forEach((ss: any) => {
      const ssPods = dashboard.workloadHealth?.pods?.filter(
        (pod: any) => pod.namespace === ss.namespace &&
          (pod.ownerName === ss.name || pod.name.includes(ss.name))
      ) || [];

      let cpuUsage = 0;
      let memoryUsage = 0;
      ssPods.forEach((pod: any) => {
        cpuUsage += pod.cpuUsage || 0;
        memoryUsage += (pod.memoryUsage || 0) / (1024 * 1024);
      });

      const cpuCost = (cpuUsage / 1000) * 0.0416 * 24;
      const memCost = (memoryUsage / 1024) * 0.00456 * 24;
      const dailyCost = cpuCost + memCost;

      workloads.push({
        name: ss.name,
        namespace: ss.namespace,
        kind: 'StatefulSet',
        cpuUsageMillicores: cpuUsage,
        memoryUsageMb: memoryUsage,
        podCount: ssPods.length || ss.replicas || 0,
        dailyCost,
        monthlyCost: dailyCost * 30
      });
    });

    return workloads.sort((a, b) => b.dailyCost - a.dailyCost);
  }

  function getDeploymentCosts() {
    return getWorkloadCosts().filter((wl: any) => wl.kind?.toLowerCase() === 'deployment');
  }

  function getDaemonSetCosts() {
    return getWorkloadCosts().filter((wl: any) => wl.kind?.toLowerCase() === 'daemonset');
  }

  function getStatefulSetCosts() {
    return getWorkloadCosts().filter((wl: any) => wl.kind?.toLowerCase() === 'statefulset');
  }

  // Intelligent recommendation engine
  const recommendations = useMemo<CostRecommendation[]>(() => {
    const recs: CostRecommendation[] = [];


    // 3. Expensive Namespaces
    const namespaceCosts = getNamespaceCosts();
    const totalNamespaceCost = namespaceCosts.reduce((sum: number, ns: any) => sum + (ns.dailyCost || 0), 0);
    const expensiveNamespaces = namespaceCosts.filter((ns: any) => {
      const costPercent = (ns.dailyCost / totalNamespaceCost) * 100;
      return costPercent > 30;
    });

    if (expensiveNamespaces.length > 0) {
      expensiveNamespaces.forEach((ns: any) => {
        const costPercent = ((ns.dailyCost / totalNamespaceCost) * 100).toFixed(1);
        recs.push({
          id: `expensive-namespace-${ns.namespace}`,
          priority: 'medium',
          category: 'rightsizing',
          title: `Namespace "${ns.namespace}" High Cost`,
          description: `This namespace accounts for ${costPercent}% of total cluster cost ($${ns.dailyCost.toFixed(2)}/day).`,
          impact: `Review workloads in this namespace for optimization opportunities`,
          savingsEstimate: ns.dailyCost * 0.3 * 30,
          basisExplanation: `Namespace costs are calculated by aggregating resource usage (CPU and memory) of all pods within the namespace, then applying AWS EC2 pricing rates.`,
          actionItems: [
            'Audit all workloads in this namespace for necessity',
            'Check for idle or forgotten deployments',
            'Review pod resource requests vs actual usage',
            'Consider implementing resource quotas'
          ],
          affectedResources: [ns.namespace]
        });
      });
    }

    // 4. No Autoscaling Configured
    const hasKarpenter = dashboard.karpenter?.healthy || false;
    if (!hasKarpenter && (dashboard.costMetrics?.dailyCost || 0) > 0) {
      // Use a more conservative estimate for potential savings (15-20%)
      const estimatedSavings = (dashboard.costMetrics?.dailyCost || 0) * 0.15;
      recs.push({
        id: 'no-autoscaling',
        priority: 'high',
        category: 'autoscaling',
        title: 'Cloud-Native Autoscaling Recommended',
        description: 'Advanced autoscaling (like Karpenter) is not active. This limits your cluster\'s ability to automatically right-size node provisioning.',
        impact: `Potential savings of $${(estimatedSavings * 30).toFixed(2)}/month with intelligent orchestration`,
        savingsEstimate: estimatedSavings * 30,
        basisExplanation: `Intelligent autoscalers can reduce cluster costs by 15-20% through automated consolidation and optimal instance selection. This estimate is based on typical industry benchmarks applied to your current spend.`,
        actionItems: [
          'Install and configure Karpenter for intelligent node provisioning',
          'Define NodePools with appropriate instance type constraints',
          'Set up Pod Disruption Budgets for safe scaling',
          'Monitor and tune consolidation settings'
        ],
        affectedResources: ['cluster-wide']
      });
    }

    // 5. Idle Resources
    const idleWorkloads = getWorkloadCosts().filter((wl: any) => {
      return (wl.cpuUsageMillicores || 0) < 10 && (wl.memoryUsageMb || 0) < 100;
    });

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
        basisExplanation: `Workloads are classified as idle when CPU usage is below 10 millicores and memory usage is below 100MB. Even idle pods consume cluster resources and incur costs.`,
        actionItems: [
          'Review and delete unused test/development workloads',
          'Implement workload scheduling for non-production environments',
          'Use namespaces to organize and track workload purposes',
          'Set up regular audits of running workloads'
        ],
        affectedResources: idleWorkloads.map((wl: any) => `${wl.namespace}/${wl.name}`).slice(0, 5)
      });
    }

    // Sort by priority and savings
    return recs.sort((a, b) => {
      const priorityWeight = { high: 3, medium: 2, low: 1 };
      if (priorityWeight[a.priority] !== priorityWeight[b.priority]) {
        return priorityWeight[b.priority] - priorityWeight[a.priority];
      }
      return b.savingsEstimate - a.savingsEstimate;
    });
  }, [sortedNodesByCost, dashboard, getNamespaceCosts, getWorkloadCosts]);

  const getPriorityColor = (priority: string) => {
    switch (priority) {
      case 'high': return 'bg-red-50 border-red-200 text-red-700';
      case 'medium': return 'bg-amber-50 border-amber-200 text-amber-700';
      case 'low': return 'bg-blue-50 border-blue-200 text-blue-700';
      default: return 'bg-gray-50 border-gray-200 text-gray-700';
    }
  };

  const getPriorityIcon = (priority: string) => {
    switch (priority) {
      case 'high': return 'exclamation-circle';
      case 'medium': return 'exclamation-triangle';
      case 'low': return 'info-circle';
      default: return 'info-circle';
    }
  };

  return (
    <div className="space-y-6">
      {/* Cost Summary */}
      <section className="bg-white rounded-xl shadow-sm p-6 border border-gray-200">
        <div className="flex items-center gap-3 mb-6">
          <div className="w-10 h-10 rounded-lg bg-slate-100 flex items-center justify-center">
            <i className="fas fa-dollar-sign text-slate-700 text-lg"></i>
          </div>
          <h3 className="text-xl font-bold text-gray-900">Cost Overview</h3>
        </div>
        <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
          <div className="p-5 bg-gray-50 rounded-lg border border-gray-200">
            <div className="text-xs font-semibold text-gray-600 uppercase tracking-wide mb-2">Daily Cost</div>
            <div className="text-3xl font-bold text-gray-900">
              ${(dashboard.costMetrics?.dailyCost || 0).toFixed(2)}
            </div>
            <div className="text-xs text-gray-500 mt-1">Current spend per day</div>
          </div>
          <div className="p-5 bg-gray-50 rounded-lg border border-gray-200">
            <div className="text-xs font-semibold text-gray-600 uppercase tracking-wide mb-2">Monthly Projection</div>
            <div className="text-3xl font-bold text-gray-900">
              ${((dashboard.costMetrics?.dailyCost || 0) * 30).toFixed(2)}
            </div>
            <div className="text-xs text-gray-500 mt-1">Projected for 30 days</div>
          </div>
        </div>
      </section>


      {/* Node Cost Breakdown */}
      <section className="bg-white rounded-xl shadow-sm p-6 border border-gray-200">
        <div className="flex items-center gap-3 mb-6">
          <div className="w-10 h-10 rounded-lg bg-blue-50 flex items-center justify-center">
            <i className="fas fa-server text-blue-600 text-lg"></i>
          </div>
          <h3 className="text-xl font-bold text-gray-900">
            Node Cost Breakdown
            <span className="ml-2 text-sm font-normal text-gray-500">
              ({sortedNodesByCost.filter((n: any) => n.condition === 'Ready' || n.condition === 'ready' || !n.condition).length} running nodes)
            </span>
          </h3>
        </div>
        <div className="overflow-x-auto">
          <table className="w-full">
            <thead>
              <tr className="border-b-2 border-gray-200">
                <th className="text-left px-4 py-3 text-xs font-semibold text-gray-600 uppercase tracking-wide">
                  Node Name
                </th>
                <th className="text-left px-4 py-3 text-xs font-semibold text-gray-600 uppercase tracking-wide">
                  Instance Type
                </th>
                <th className="text-right px-4 py-3 text-xs font-semibold text-gray-600 uppercase tracking-wide">
                  Hourly Rate
                </th>
                <th className="text-right px-4 py-3 text-xs font-semibold text-gray-600 uppercase tracking-wide">
                  Daily Cost
                </th>
              </tr>
            </thead>
            <tbody>
              {sortedNodesByCost
                .filter((node: any) => true) // No capacity filter
                .sort((a: any, b: any) => {
                  const aReady = (a.condition === 'Ready' || a.condition === 'ready' || !a.condition) ? 1 : 0;
                  const bReady = (b.condition === 'Ready' || b.condition === 'ready' || !b.condition) ? 1 : 0;
                  if (aReady !== bReady) return bReady - aReady;
                  // Then prioritize nodes with known instance types
                  const aHasType = a.instanceType && a.instanceType !== 'Unknown' ? 1 : 0;
                  const bHasType = b.instanceType && b.instanceType !== 'Unknown' ? 1 : 0;
                  if (aHasType !== bHasType) return bHasType - aHasType;
                  return (b.dailyCost || 0) - (a.dailyCost || 0);
                })
                .slice(nodePage * nodeItemsPerPage, (nodePage + 1) * nodeItemsPerPage)
                .map((node: any, index: number) => {
                  // Fallback for instance type if missing in cost data
                  const instanceType = node.instanceType ||
                    dashboard.nodes?.find((n: any) => n.name === node.nodeName)?.instanceType ||
                    'Unknown';

                  return (
                    <tr key={index} className="border-t border-gray-100 hover:bg-gray-50 transition-colors">
                      <td className="px-4 py-3 text-sm font-mono font-semibold text-gray-900">
                        {node.nodeName}
                        {(!node.condition || (node.condition !== 'Ready' && node.condition !== 'ready')) && (
                          <span className="ml-2 inline-flex items-center px-2 py-0.5 rounded text-xs font-medium bg-red-100 text-red-800">
                            {node.condition || 'Not Ready'}
                          </span>
                        )}
                      </td>
                      <td className="px-4 py-3 text-sm text-gray-600">{instanceType}</td>
                      <td className="px-4 py-3 text-sm text-right font-medium text-gray-700">
                        ${(node.hourlyRate || 0).toFixed(4)}
                      </td>
                      <td className="px-4 py-3 text-sm text-right font-bold text-gray-900">
                        ${(node.dailyCost || 0).toFixed(2)}
                      </td>
                    </tr>
                  );
                })}
              {sortedNodesByCost.length === 0 && (
                <tr>
                  <td colSpan={4} className="px-4 py-8 text-center text-gray-500">
                    <i className="fas fa-server text-gray-300 text-2xl mb-2"></i>
                    <p className="text-sm">No node cost data available</p>
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
        {/* Node Pagination */}
        {sortedNodesByCost.length > nodeItemsPerPage && (
          <div className="flex items-center justify-between mt-4 pt-4 border-t border-gray-200">
            <div className="text-sm text-gray-600">
              Showing {nodePage * nodeItemsPerPage + 1} to{' '}
              {Math.min((nodePage + 1) * nodeItemsPerPage, sortedNodesByCost.length)} of{' '}
              {sortedNodesByCost.length} nodes
            </div>
            <div className="flex gap-2">
              <button
                onClick={() => setNodePage(Math.max(0, nodePage - 1))}
                disabled={nodePage === 0}
                className="px-3 py-1.5 text-sm border border-gray-300 rounded-md hover:bg-gray-50 disabled:opacity-50 disabled:cursor-not-allowed font-medium"
              >
                Previous
              </button>
              <button
                onClick={() => setNodePage(nodePage + 1)}
                disabled={(nodePage + 1) * nodeItemsPerPage >= sortedNodesByCost.length}
                className="px-3 py-1.5 text-sm border border-gray-300 rounded-md hover:bg-gray-50 disabled:opacity-50 disabled:cursor-not-allowed font-medium"
              >
                Next
              </button>
            </div>
          </div>
        )}
      </section>

      {/* Cost by Namespace */}
      <section className="bg-white rounded-xl shadow-sm p-6 border border-gray-200">
        <div className="flex items-center gap-3 mb-6">
          <div className="w-10 h-10 rounded-lg bg-indigo-50 flex items-center justify-center">
            <i className="fas fa-layer-group text-indigo-600 text-lg"></i>
          </div>
          <h3 className="text-xl font-bold text-gray-900">Cost by Namespace</h3>
        </div>
        <div className="overflow-x-auto">
          <table className="w-full">
            <thead>
              <tr className="border-b-2 border-gray-200">
                <th className="text-left px-4 py-3 text-xs font-semibold text-gray-600 uppercase tracking-wide">
                  Namespace
                </th>
                <th className="text-right px-4 py-3 text-xs font-semibold text-gray-600 uppercase tracking-wide">
                  Pods
                </th>
                <th className="text-right px-4 py-3 text-xs font-semibold text-gray-600 uppercase tracking-wide">
                  CPU (m)
                </th>
                <th className="text-right px-4 py-3 text-xs font-semibold text-gray-600 uppercase tracking-wide">
                  Memory (MB)
                </th>
                <th className="text-right px-4 py-3 text-xs font-semibold text-gray-600 uppercase tracking-wide">
                  $/Day
                </th>
                <th className="text-right px-4 py-3 text-xs font-semibold text-gray-600 uppercase tracking-wide">
                  $/Month
                </th>
              </tr>
            </thead>
            <tbody>
              {getNamespaceCosts()
                .slice(namespacePage * itemsPerPage, (namespacePage + 1) * itemsPerPage)
                .map((ns: any, index: number) => (
                  <tr key={index} className="border-t border-gray-100 hover:bg-gray-50 transition-colors">
                    <td className="px-4 py-3 text-sm font-mono font-semibold text-gray-900">
                      {ns.namespace}
                    </td>
                    <td className="px-4 py-3 text-sm text-right text-gray-700 font-medium">
                      {ns.podCount || 0}
                    </td>
                    <td className="px-4 py-3 text-sm text-right text-gray-600">
                      {(ns.cpuUsageMillicores || 0).toFixed(0)}
                    </td>
                    <td className="px-4 py-3 text-sm text-right text-gray-600">
                      {(ns.memoryUsageMb || 0).toFixed(0)}
                    </td>
                    <td className="px-4 py-3 text-sm text-right font-semibold text-gray-900">
                      ${(ns.dailyCost || 0).toFixed(2)}
                    </td>
                    <td className="px-4 py-3 text-sm text-right font-bold text-gray-900">
                      ${(ns.monthlyCost || 0).toFixed(2)}
                    </td>
                  </tr>
                ))}
              {getNamespaceCosts().length === 0 && (
                <tr>
                  <td colSpan={6} className="px-4 py-8 text-center text-gray-500">
                    <i className="fas fa-layer-group text-gray-300 text-2xl mb-2"></i>
                    <p className="text-sm">No namespace cost data available</p>
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
        {/* Namespace Pagination */}
        {getNamespaceCosts().length > itemsPerPage && (
          <div className="flex items-center justify-between mt-4 pt-4 border-t border-gray-200">
            <div className="text-sm text-gray-600">
              Showing {namespacePage * itemsPerPage + 1} to{' '}
              {Math.min((namespacePage + 1) * itemsPerPage, getNamespaceCosts().length)} of{' '}
              {getNamespaceCosts().length} namespaces
            </div>
            <div className="flex gap-2">
              <button
                onClick={() => setNamespacePage(Math.max(0, namespacePage - 1))}
                disabled={namespacePage === 0}
                className="px-3 py-1.5 text-sm border border-gray-300 rounded-md hover:bg-gray-50 disabled:opacity-50 disabled:cursor-not-allowed font-medium"
              >
                Previous
              </button>
              <button
                onClick={() => setNamespacePage(namespacePage + 1)}
                disabled={(namespacePage + 1) * itemsPerPage >= getNamespaceCosts().length}
                className="px-3 py-1.5 text-sm border border-gray-300 rounded-md hover:bg-gray-50 disabled:opacity-50 disabled:cursor-not-allowed font-medium"
              >
                Next
              </button>
            </div>
          </div>
        )}
      </section>

      {/* Cost by Workload */}
      <section className="bg-white rounded-xl shadow-sm p-6 border border-gray-200">
        <div className="flex items-center gap-3 mb-6">
          <div className="w-10 h-10 rounded-lg bg-purple-50 flex items-center justify-center">
            <i className="fas fa-cubes text-purple-600 text-lg"></i>
          </div>
          <h3 className="text-xl font-bold text-gray-900">
            Cost by Workload
            <span className="ml-2 text-sm font-normal text-gray-500">
              ({getWorkloadCosts().length} total)
            </span>
          </h3>
        </div>

        {/* Deployments */}
        {getDeploymentCosts().length > 0 && (
          <div className="mb-6">
            <h4 className="text-sm font-semibold text-gray-700 mb-3 flex items-center gap-2">
              <i className="fas fa-rocket text-blue-600"></i>
              Deployments ({getDeploymentCosts().length})
            </h4>
            <div className="overflow-x-auto">
              <table className="w-full">
                <thead>
                  <tr className="border-b-2 border-gray-200">
                    <th className="text-left px-4 py-3 text-xs font-semibold text-gray-600 uppercase tracking-wide">
                      Name
                    </th>
                    <th className="text-left px-4 py-3 text-xs font-semibold text-gray-600 uppercase tracking-wide">
                      Namespace
                    </th>
                    <th className="text-right px-4 py-3 text-xs font-semibold text-gray-600 uppercase tracking-wide">
                      CPU (m)
                    </th>
                    <th className="text-right px-4 py-3 text-xs font-semibold text-gray-600 uppercase tracking-wide">
                      Memory (MB)
                    </th>
                    <th className="text-right px-4 py-3 text-xs font-semibold text-gray-600 uppercase tracking-wide">
                      $/Day
                    </th>
                    <th className="text-right px-4 py-3 text-xs font-semibold text-gray-600 uppercase tracking-wide">
                      $/Month
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {getDeploymentCosts()
                    .slice(deploymentPage * itemsPerPage, (deploymentPage + 1) * itemsPerPage)
                    .map((wl: any, index: number) => (
                      <tr key={index} className="border-t border-gray-100 hover:bg-gray-50 transition-colors">
                        <td className="px-4 py-3 text-sm font-mono font-semibold text-gray-900">
                          {wl.name}
                        </td>
                        <td className="px-4 py-3 text-sm text-gray-600">{wl.namespace}</td>
                        <td className="px-4 py-3 text-sm text-right text-gray-600">
                          {(wl.cpuUsageMillicores || 0).toFixed(0)}
                        </td>
                        <td className="px-4 py-3 text-sm text-right text-gray-600">
                          {(wl.memoryUsageMb || 0).toFixed(0)}
                        </td>
                        <td className="px-4 py-3 text-sm text-right font-semibold text-gray-900">
                          ${(wl.dailyCost || 0).toFixed(2)}
                        </td>
                        <td className="px-4 py-3 text-sm text-right font-bold text-gray-900">
                          ${(wl.monthlyCost || 0).toFixed(2)}
                        </td>
                      </tr>
                    ))}
                </tbody>
              </table>
            </div>
            {getDeploymentCosts().length > itemsPerPage && (
              <div className="flex items-center justify-between mt-4 pt-4 border-t border-gray-200">
                <div className="text-sm text-gray-600">
                  Showing {deploymentPage * itemsPerPage + 1} to{' '}
                  {Math.min((deploymentPage + 1) * itemsPerPage, getDeploymentCosts().length)} of{' '}
                  {getDeploymentCosts().length}
                </div>
                <div className="flex gap-2">
                  <button
                    onClick={() => setDeploymentPage(Math.max(0, deploymentPage - 1))}
                    disabled={deploymentPage === 0}
                    className="px-3 py-1.5 text-sm border border-gray-300 rounded-md hover:bg-gray-50 disabled:opacity-50 disabled:cursor-not-allowed font-medium"
                  >
                    Previous
                  </button>
                  <button
                    onClick={() => setDeploymentPage(deploymentPage + 1)}
                    disabled={(deploymentPage + 1) * itemsPerPage >= getDeploymentCosts().length}
                    className="px-3 py-1.5 text-sm border border-gray-300 rounded-md hover:bg-gray-50 disabled:opacity-50 disabled:cursor-not-allowed font-medium"
                  >
                    Next
                  </button>
                </div>
              </div>
            )}
          </div>
        )}

        {/* DaemonSets */}
        {getDaemonSetCosts().length > 0 && (
          <div className="mb-6">
            <h4 className="text-sm font-semibold text-gray-700 mb-3 flex items-center gap-2">
              <i className="fas fa-network-wired text-emerald-600"></i>
              DaemonSets ({getDaemonSetCosts().length})
            </h4>
            <div className="overflow-x-auto">
              <table className="w-full">
                <thead>
                  <tr className="border-b-2 border-gray-200">
                    <th className="text-left px-4 py-3 text-xs font-semibold text-gray-600 uppercase tracking-wide">
                      Name
                    </th>
                    <th className="text-left px-4 py-3 text-xs font-semibold text-gray-600 uppercase tracking-wide">
                      Namespace
                    </th>
                    <th className="text-right px-4 py-3 text-xs font-semibold text-gray-600 uppercase tracking-wide">
                      CPU (m)
                    </th>
                    <th className="text-right px-4 py-3 text-xs font-semibold text-gray-600 uppercase tracking-wide">
                      Memory (MB)
                    </th>
                    <th className="text-right px-4 py-3 text-xs font-semibold text-gray-600 uppercase tracking-wide">
                      $/Day
                    </th>
                    <th className="text-right px-4 py-3 text-xs font-semibold text-gray-600 uppercase tracking-wide">
                      $/Month
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {getDaemonSetCosts()
                    .slice(daemonSetPage * itemsPerPage, (daemonSetPage + 1) * itemsPerPage)
                    .map((wl: any, index: number) => (
                      <tr key={index} className="border-t border-gray-100 hover:bg-gray-50 transition-colors">
                        <td className="px-4 py-3 text-sm font-mono font-semibold text-gray-900">
                          {wl.name}
                        </td>
                        <td className="px-4 py-3 text-sm text-gray-600">{wl.namespace}</td>
                        <td className="px-4 py-3 text-sm text-right text-gray-600">
                          {(wl.cpuUsageMillicores || 0).toFixed(0)}
                        </td>
                        <td className="px-4 py-3 text-sm text-right text-gray-600">
                          {(wl.memoryUsageMb || 0).toFixed(0)}
                        </td>
                        <td className="px-4 py-3 text-sm text-right font-semibold text-gray-900">
                          ${(wl.dailyCost || 0).toFixed(2)}
                        </td>
                        <td className="px-4 py-3 text-sm text-right font-bold text-gray-900">
                          ${(wl.monthlyCost || 0).toFixed(2)}
                        </td>
                      </tr>
                    ))}
                </tbody>
              </table>
            </div>
            {getDaemonSetCosts().length > itemsPerPage && (
              <div className="flex items-center justify-between mt-4 pt-4 border-t border-gray-200">
                <div className="text-sm text-gray-600">
                  Showing {daemonSetPage * itemsPerPage + 1} to{' '}
                  {Math.min((daemonSetPage + 1) * itemsPerPage, getDaemonSetCosts().length)} of{' '}
                  {getDaemonSetCosts().length}
                </div>
                <div className="flex gap-2">
                  <button
                    onClick={() => setDaemonSetPage(Math.max(0, daemonSetPage - 1))}
                    disabled={daemonSetPage === 0}
                    className="px-3 py-1.5 text-sm border border-gray-300 rounded-md hover:bg-gray-50 disabled:opacity-50 disabled:cursor-not-allowed font-medium"
                  >
                    Previous
                  </button>
                  <button
                    onClick={() => setDaemonSetPage(daemonSetPage + 1)}
                    disabled={(daemonSetPage + 1) * itemsPerPage >= getDaemonSetCosts().length}
                    className="px-3 py-1.5 text-sm border border-gray-300 rounded-md hover:bg-gray-50 disabled:opacity-50 disabled:cursor-not-allowed font-medium"
                  >
                    Next
                  </button>
                </div>
              </div>
            )}
          </div>
        )}

        {/* StatefulSets */}
        {getStatefulSetCosts().length > 0 && (
          <div>
            <h4 className="text-sm font-semibold text-gray-700 mb-3 flex items-center gap-2">
              <i className="fas fa-database text-purple-600"></i>
              StatefulSets ({getStatefulSetCosts().length})
            </h4>
            <div className="overflow-x-auto">
              <table className="w-full">
                <thead>
                  <tr className="border-b-2 border-gray-200">
                    <th className="text-left px-4 py-3 text-xs font-semibold text-gray-600 uppercase tracking-wide">
                      Name
                    </th>
                    <th className="text-left px-4 py-3 text-xs font-semibold text-gray-600 uppercase tracking-wide">
                      Namespace
                    </th>
                    <th className="text-right px-4 py-3 text-xs font-semibold text-gray-600 uppercase tracking-wide">
                      CPU (m)
                    </th>
                    <th className="text-right px-4 py-3 text-xs font-semibold text-gray-600 uppercase tracking-wide">
                      Memory (MB)
                    </th>
                    <th className="text-right px-4 py-3 text-xs font-semibold text-gray-600 uppercase tracking-wide">
                      $/Day
                    </th>
                    <th className="text-right px-4 py-3 text-xs font-semibold text-gray-600 uppercase tracking-wide">
                      $/Month
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {getStatefulSetCosts()
                    .slice(statefulSetPage * itemsPerPage, (statefulSetPage + 1) * itemsPerPage)
                    .map((wl: any, index: number) => (
                      <tr key={index} className="border-t border-gray-100 hover:bg-gray-50 transition-colors">
                        <td className="px-4 py-3 text-sm font-mono font-semibold text-gray-900">
                          {wl.name}
                        </td>
                        <td className="px-4 py-3 text-sm text-gray-600">{wl.namespace}</td>
                        <td className="px-4 py-3 text-sm text-right text-gray-600">
                          {(wl.cpuUsageMillicores || 0).toFixed(0)}
                        </td>
                        <td className="px-4 py-3 text-sm text-right text-gray-600">
                          {(wl.memoryUsageMb || 0).toFixed(0)}
                        </td>
                        <td className="px-4 py-3 text-sm text-right font-semibold text-gray-900">
                          ${(wl.dailyCost || 0).toFixed(2)}
                        </td>
                        <td className="px-4 py-3 text-sm text-right font-bold text-gray-900">
                          ${(wl.monthlyCost || 0).toFixed(2)}
                        </td>
                      </tr>
                    ))}
                </tbody>
              </table>
            </div>
            {getStatefulSetCosts().length > itemsPerPage && (
              <div className="flex items-center justify-between mt-4 pt-4 border-t border-gray-200">
                <div className="text-sm text-gray-600">
                  Showing {statefulSetPage * itemsPerPage + 1} to{' '}
                  {Math.min((statefulSetPage + 1) * itemsPerPage, getStatefulSetCosts().length)} of{' '}
                  {getStatefulSetCosts().length}
                </div>
                <div className="flex gap-2">
                  <button
                    onClick={() => setStatefulSetPage(Math.max(0, statefulSetPage - 1))}
                    disabled={statefulSetPage === 0}
                    className="px-3 py-1.5 text-sm border border-gray-300 rounded-md hover:bg-gray-50 disabled:opacity-50 disabled:cursor-not-allowed font-medium"
                  >
                    Previous
                  </button>
                  <button
                    onClick={() => setStatefulSetPage(statefulSetPage + 1)}
                    disabled={(statefulSetPage + 1) * itemsPerPage >= getStatefulSetCosts().length}
                    className="px-3 py-1.5 text-sm border border-gray-300 rounded-md hover:bg-gray-50 disabled:opacity-50 disabled:cursor-not-allowed font-medium"
                  >
                    Next
                  </button>
                </div>
              </div>
            )}
          </div>
        )}

        {getWorkloadCosts().length === 0 && (
          <div className="py-8 text-center text-gray-500">
            <i className="fas fa-cubes text-gray-300 text-2xl mb-2"></i>
            <p className="text-sm">No workload cost data available</p>
          </div>
        )}
      </section>

      {/* Intelligent Recommendations */}
      {recommendations.length > 0 && (
        <section className="bg-gradient-to-br from-amber-50 to-yellow-50 rounded-xl shadow-sm p-6 border-2 border-amber-200">
          <div className="flex items-center gap-3 mb-6">
            <div className="w-10 h-10 rounded-lg bg-amber-100 flex items-center justify-center">
              <i className="fas fa-lightbulb text-amber-600 text-lg"></i>
            </div>
            <div className="flex-1">
              <h3 className="text-xl font-bold text-gray-900">Cost Optimization Recommendations</h3>
              <p className="text-sm text-gray-600">
                {recommendations.length} opportunity{recommendations.length > 1 ? 'ies' : 'y'} identified • Potential savings: $
                {recommendations.reduce((sum, r) => sum + r.savingsEstimate, 0).toFixed(2)}/month
              </p>
            </div>
          </div>

          <div className="space-y-3">
            {recommendations.map((rec) => (
              <div
                key={rec.id}
                className={`bg-white rounded-lg p-5 border-2 transition-all ${getPriorityColor(rec.priority)}`}
              >
                <div className="flex items-start gap-4">
                  <div className={`w-10 h-10 rounded-lg flex items-center justify-center flex-shrink-0 ${rec.priority === 'high' ? 'bg-red-100' : rec.priority === 'medium' ? 'bg-amber-100' : 'bg-blue-100'}`}>
                    <i className={`fas fa-${getPriorityIcon(rec.priority)} ${rec.priority === 'high' ? 'text-red-600' : rec.priority === 'medium' ? 'text-amber-600' : 'text-blue-600'} text-lg`}></i>
                  </div>
                  <div className="flex-1 min-w-0">
                    <div className="flex items-start justify-between gap-2 mb-2">
                      <div>
                        <h4 className="text-base font-semibold text-gray-900">{rec.title}</h4>
                        <span className={`inline-block px-2 py-0.5 rounded text-xs font-semibold uppercase mt-1 ${rec.priority === 'high' ? 'bg-red-100 text-red-700' : rec.priority === 'medium' ? 'bg-amber-100 text-amber-700' : 'bg-blue-100 text-blue-700'}`}>
                          {rec.priority} priority
                        </span>
                      </div>
                      {rec.savingsEstimate > 0 && (
                        <div className="text-right flex-shrink-0">
                          <div className="text-lg font-bold text-emerald-600">
                            ${rec.savingsEstimate.toFixed(2)}
                          </div>
                          <div className="text-xs text-gray-500">potential/month</div>
                        </div>
                      )}
                    </div>

                    <p className="text-sm text-gray-700 mb-3">{rec.description}</p>
                    <div className="text-sm font-semibold text-gray-900 mb-2">{rec.impact}</div>

                    {/* Basis Explanation - Collapsible */}
                    <button
                      onClick={() => setShowRecommendationInfo(showRecommendationInfo === rec.id ? null : rec.id)}
                      className="flex items-center gap-2 text-xs text-gray-600 hover:text-gray-900 mb-2 font-medium"
                    >
                      <i className={`fas fa-info-circle text-sm`}></i>
                      <span>How is this calculated?</span>
                      <i className={`fas fa-chevron-${showRecommendationInfo === rec.id ? 'up' : 'down'} text-xs`}></i>
                    </button>

                    {showRecommendationInfo === rec.id && (
                      <div className="bg-gray-50 rounded-lg p-3 mb-3 text-xs text-gray-700 border border-gray-200">
                        <strong className="text-gray-900">Calculation Basis:</strong> {rec.basisExplanation}
                      </div>
                    )}

                    {/* Action Items */}
                    <div className="mb-3">
                      <div className="text-xs font-semibold text-gray-700 mb-2 uppercase tracking-wide">Recommended Actions:</div>
                      <ul className="space-y-1">
                        {rec.actionItems.map((action, idx) => (
                          <li key={idx} className="text-xs text-gray-700 flex items-start gap-2">
                            <i className="fas fa-check text-emerald-600 mt-0.5 flex-shrink-0"></i>
                            <span>{action}</span>
                          </li>
                        ))}
                      </ul>
                    </div>

                    {/* Affected Resources */}
                    {rec.affectedResources.length > 0 && (
                      <div className="text-xs text-gray-600">
                        <strong>Affected:</strong> {rec.affectedResources.slice(0, 3).join(', ')}
                        {rec.affectedResources.length > 3 && ` and ${rec.affectedResources.length - 3} more`}
                      </div>
                    )}
                  </div>
                </div>
              </div>
            ))}
          </div>
        </section>
      )}
    </div>
  );
};

export default EksCostTab;
