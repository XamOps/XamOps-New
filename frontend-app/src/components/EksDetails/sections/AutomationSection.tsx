/**
 * Automation (docs/cloudk8s/ui-revamp-design.md §4.2, §9; automation-v2 design §3.1; UI v3 plan §2).
 * Routes every registered Automation sub to its page: Overview is what automation can do, is doing
 * and has done on this cluster; Workload autoscaler › Workloads reads endpoint 1 once and hosts the
 * Workload rightsizing cards with their own gating (the workload detail is `?workload=` on the same
 * page); Workload autoscaler › Policies is the rules XamOps uses, read-only; HPA and VPA are main's
 * combined Pod autoscaler page, split into one page each.
 */
import { SectionSubPanel } from './SectionSubPanel';
import OverviewPanel from './automation/OverviewPanel';
import WorkloadsPage from './automation/workload/WorkloadsPage';
import PoliciesPage from './automation/workload/PoliciesPage';
import HpaPage from './automation/hpa/HpaPage';
import VpaPage from './automation/vpa/VpaPage';
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
                  // Node autoscaler registers with its page (FEAT-003); until then its card points at Nodes.
                  nodeAutoscaler: route.hrefFor('nodes', 'nodes'),
                  nodeAutoscalerLabel: 'Open Nodes',
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
          case 'hpa':
            return <HpaPage clusterName={clusterName} accountId={accountId} region={region} eksData={eksData} />;
          case 'vpa':
            return <VpaPage clusterName={clusterName} accountId={accountId} region={region} eksData={eksData} />;
          default:
            return null;
        }
      }}
    />
  );
}
