/**
 * Automation (docs/cloudk8s/ui-revamp-design.md §4.2, §9; automation-v2 design §3.1). Routes every
 * registered Automation sub to its page: Overview is access and readiness; Workload autoscaler ›
 * Workloads hosts the realtime branch's Workload rightsizing tab unchanged, with its own gating and
 * copy (implementation plan, Addendum 1 Part B); HPA and VPA are main's combined Pod autoscaler page,
 * split into one page each. The dashboard is passed through for the pages that build on these.
 */
import EksAutomationTab from '../tabs/EksAutomationTab';
import { SectionSubPanel } from './SectionSubPanel';
import OverviewPanel from './automation/OverviewPanel';
import HpaPage from './automation/hpa/HpaPage';
import VpaPage from './automation/vpa/VpaPage';
import type { EksSectionProps } from './sectionTypes';

export default function AutomationSection({ clusterName, accountId, region, route, eksData }: EksSectionProps) {
  return (
    <SectionSubPanel
      sectionId="automation"
      route={route}
      renderPanel={(sub) => {
        switch (sub) {
          case 'overview':
            return <OverviewPanel clusterName={clusterName} workloadAutoscalerHref={route.hrefFor('automation', 'workload-autoscaler')} />;
          case 'workload-autoscaler':
            return <EksAutomationTab clusterName={clusterName} accountId={accountId} region={region} />;
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
