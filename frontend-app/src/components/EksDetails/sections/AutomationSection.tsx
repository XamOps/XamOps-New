/**
 * Automation (docs/cloudk8s/ui-revamp-design.md §4.2, §9; automation-v2 design §3.1; UI v3 plan §2).
 * Routes every registered Automation sub to its page: Overview is what automation can do, is doing
 * and has done on this cluster; Workload autoscaler › Workloads reads endpoint 1 once and hosts the
 * Workload rightsizing cards with their own gating (the workload detail is `?workload=` on the same
 * page); Workload autoscaler › Policies is the rules XamOps uses, read-only; Node autoscaler is who
 * changes the node count and what node autoscaling would do, read-only; Consolidation is the
 * automation agent's look-only plan of nodes it could empty or drain, read-only; HPA (with its `?hpa=`
 * detail) and VPA are main's combined Pod autoscaler page, split into one page each; Activity is
 * what XamOps changed.
 */
import { SectionSubPanel } from './SectionSubPanel';
import OverviewPanel from './automation/OverviewPanel';
import WorkloadsPage from './automation/workload/WorkloadsPage';
import PoliciesPage from './automation/workload/PoliciesPage';
import NodeAutoscalerPage from './automation/node/NodeAutoscalerPage';
import ConsolidationPage from './automation/node/ConsolidationPage';
import HpaPage from './automation/hpa/HpaPage';
import VpaPage from './automation/vpa/VpaPage';
import ActivityPage from './automation/activity/ActivityPage';
import type { EksSectionProps } from './sectionTypes';

export default function AutomationSection({ clusterName, accountId, region, route, eksData }: EksSectionProps) {
  const workloadsHref = route.hrefFor('automation', 'workload-autoscaler');
  const policiesHref = route.hrefFor('automation', 'workload-autoscaler-policies');
  return (
    <SectionSubPanel
      sectionId="automation"
      route={route}
      renderPanel={(sub) => {
        switch (sub) {
          case 'overview':
            return (
              <OverviewPanel
                clusterName={clusterName}
                accountId={accountId}
                region={region}
                eksData={eksData}
                hrefs={{
                  workloadAutoscaler: workloadsHref,
                  policies: policiesHref,
                  hpa: route.hrefFor('automation', 'hpa'),
                  vpa: route.hrefFor('automation', 'vpa'),
                  nodeAutoscaler: route.hrefFor('automation', 'node-autoscaler'),
                  nodeAutoscalerLabel: 'Open Node autoscaler',
                  consolidation: route.hrefFor('automation', 'consolidation'),
                }}
              />
            );
          case 'workload-autoscaler':
            return (
              <WorkloadsPage
                clusterName={clusterName}
                accountId={accountId}
                region={region}
                workloadsHref={workloadsHref}
                policiesHref={policiesHref}
              />
            );
          case 'workload-autoscaler-policies':
            return <PoliciesPage clusterName={clusterName} accountId={accountId} region={region} workloadsHref={workloadsHref} />;
          case 'node-autoscaler':
            return <NodeAutoscalerPage clusterName={clusterName} accountId={accountId} region={region} eksData={eksData} />;
          case 'consolidation':
            return <ConsolidationPage clusterName={clusterName} accountId={accountId} region={region} />;
          case 'hpa':
            return <HpaPage clusterName={clusterName} accountId={accountId} region={region} eksData={eksData} />;
          case 'vpa':
            return <VpaPage clusterName={clusterName} accountId={accountId} region={region} eksData={eksData} />;
          case 'activity':
            return <ActivityPage clusterName={clusterName} accountId={accountId} region={region} eksData={eksData} />;
          default:
            return null;
        }
      }}
    />
  );
}
