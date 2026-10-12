import { describe, expect, it } from 'vitest';
import { toVerticalEngineView } from '../../api/verticalRightsizerAdapter';
import { demoVerticalStatus } from '../../demo/k8sAutomationMockData';
import { demoEksDashboard } from '../../demo/xamopsMockData';
import type { EksDashboard } from '../../types/eksTypes';
import {
  capacityTypeCounts, clusterCapacity, clusterNodes, freedCapacity, hpaStates, instanceTypeCounts, largestNodeAllocatable,
  nodeProvisioners, notRunningWorkloads, podPlacement,
} from '../k8sDerivations';
import { formatGib, formatMoney, formatVcpu } from '../k8sQuantities';

const demo = demoEksDashboard as unknown as EksDashboard;
const clone = (edit?: (d: any) => void): EksDashboard => {
  const d = structuredClone(demo) as any;
  edit?.(d);
  return d;
};

describe('Overview figures (glossary §9, rule 6)', () => {
  it('formats cluster totals as vCPU with two decimals, memory in Mi or GiB, and money in whole dollars', () => {
    expect(formatVcpu(8000)).toBe('8.00 vCPU');
    expect(formatVcpu(null)).toBe('—');
    expect(formatGib(250)).toBe('250Mi');
    expect(formatGib(1024)).toBe('1 GiB');
    expect(formatGib(15339)).toBe('14.98 GiB');
    expect(formatMoney(0.374)).toBe('$0.37');
    expect(formatMoney(1480.4)).toBe('$1,480');
    expect(formatMoney(null)).toBe('$—');
  });

  it('sums provisioned and allocatable from the nodes, and keeps used unknown until Prometheus reports it', () => {
    const { cpu, memory } = clusterCapacity(demo);
    expect([cpu.provisioned, cpu.allocatable, cpu.overhead]).toEqual([26000, 25520, 480]);
    expect(cpu.requested).toBe(2143);
    expect(cpu.used).toBe(12480);
    expect(memory.used).toBe(40 * 1024);
    const unreported = clusterCapacity(clone((d) => { d.clusterOverview.cpuUsagePercent = 0; d.clusterOverview.memoryUsagePercent = 0; delete d.nodeUtilization; }));
    expect([unreported.cpu.used, unreported.memory.used]).toEqual([null, null]);
    const noCapacity = clusterCapacity(clone((d) => { delete d.k8sNodes[0].capacityCpu; }));
    expect([noCapacity.cpu.provisioned, noCapacity.cpu.overhead, noCapacity.cpu.allocatable]).toEqual([null, null, 25520]);
  });

  it('counts capacity types, instance types and the largest node from node labels and quantities', () => {
    const nodes = clusterNodes(demo);
    expect(capacityTypeCounts(nodes)).toEqual({ onDemand: 3, spot: 3, unknown: 0 });
    expect(capacityTypeCounts(clusterNodes(clone((d) => { d.k8sNodes[5].labels = {}; d.k8sNodes[5].capacityType = undefined; })))).toEqual({ onDemand: 2, spot: 3, unknown: 1 });
    expect(instanceTypeCounts(nodes).map((t) => `${t.count}×${t.type}`)).toEqual(['2×c5.xlarge', '2×m5.xlarge', '1×m5.2xlarge', '1×r5.large']);
    expect(largestNodeAllocatable(nodes)).toEqual({ cpuM: 7910, memMi: expect.closeTo(30744.4, 0) });
  });

  it('places pods on a node or waiting for one, and lists the unschedulable largest ask first', () => {
    expect(podPlacement(demo)).toMatchObject({ total: 18, onNode: 16, waiting: 2, failed: 0, completed: 0, unschedulable: [] });
    const placement = podPlacement(clone((d) => {
      for (const [name, cpu] of [['data-pipeline-pending-1', '2'], ['report-job-pending-1', '8']] as const) {
        const pod = d.k8sPods.find((p: any) => p.name === name);
        pod.unschedulable = true;
        pod.containers = [{ name: 'c', cpuRequest: cpu, memoryRequest: '1Gi' }];
      }
    }))!;
    expect(placement.unschedulable.map((p) => p.name)).toEqual(['report-job-pending-1', 'data-pipeline-pending-1']);
    expect(podPlacement(clone((d) => { d.k8sPods = []; d.workloadHealth = {}; }))).toBeNull();
  });

  it('marks a workload Not running only when every pod is Pending and unschedulable', () => {
    const allStuck = clone((d) => {
      for (const p of d.k8sPods.filter((x: any) => x.name.startsWith('report-job-'))) { p.phase = 'Pending'; p.unschedulable = true; }
    });
    const stuck = (d: EksDashboard) => [...notRunningWorkloads(d)];
    // Jobs aren't workloads the engine sizes, so nothing is listed.
    expect(stuck(allStuck)).toEqual([]);
    const stuckDeployment = clone((d) => {
      for (const p of d.k8sPods.filter((x: any) => x.name.startsWith('api-server-'))) { p.phase = 'Pending'; p.unschedulable = true; }
    });
    expect(stuck(stuckDeployment)).toEqual(['production/Deployment/api-server']);
    const oneRunning = clone((d) => {
      const pods = d.k8sPods.filter((x: any) => x.name.startsWith('api-server-'));
      pods.slice(1).forEach((p: any) => { p.phase = 'Pending'; p.unschedulable = true; });
    });
    expect(stuck(oneRunning)).toEqual([]);
  });

  it('detects Karpenter from its data or its labels, and the Cluster Autoscaler from its Deployment', () => {
    expect(nodeProvisioners(demo).karpenter).not.toBeNull();
    expect(nodeProvisioners(clone((d) => { d.karpenter = null; d.k8sNodes.forEach((n: any) => { delete n.labels['karpenter.sh/nodepool']; }); }))).toEqual({ karpenter: null, clusterAutoscaler: false });
    expect(nodeProvisioners(clone((d) => { d.k8sDeployments.push({ name: 'cluster-autoscaler', namespace: 'kube-system' }); })).clusterAutoscaler).toBe(true);
    expect(nodeProvisioners(clone((d) => { d.k8sDeployments.push({ name: 'cluster-autoscaler-aws-cluster-autoscaler', namespace: 'kube-system' }); })).clusterAutoscaler).toBe(true);
    expect(nodeProvisioners(clone((d) => { d.k8sDeployments.push({ name: 'autoscaler-ui', namespace: 'docs' }); })).clusterAutoscaler).toBe(false);
  });

  it('reads HPAs as reported, telling an absent list from an empty one', () => {
    expect(hpaStates(demo)!.map((h) => `${h.name}:${h.atMax}`)).toEqual(['api-server-hpa:false', 'worker-hpa:false', 'frontend-hpa:false']);
    expect(hpaStates(clone((d) => { d.k8sHpas[1].currentReplicas = 5; }))![1].atMax).toBe(true);
    expect(hpaStates(clone((d) => { d.k8sHpas = []; }))).toEqual([]);
    expect(hpaStates(clone((d) => { delete d.k8sHpas; }))).toBeNull();
  });

  it('frees capacity, never dollars, from requests going down times replicas', () => {
    const view = toVerticalEngineView(demoVerticalStatus('prod-eks-cluster')!);
    const over = view.workloads.filter((w) => w.ref.name === 'api-staging' || w.ref.name === 'alertmanager');
    expect(freedCapacity(over, demo)).toEqual({ cpuM: 95, memMi: 6, workloads: 2, replicasUnknown: 0 });
    const scaled = over.map((w) => ({ ...w, replicas: 3 }));
    expect(freedCapacity(scaled, demo)).toMatchObject({ cpuM: 285, memMi: 18 });
  });
});
