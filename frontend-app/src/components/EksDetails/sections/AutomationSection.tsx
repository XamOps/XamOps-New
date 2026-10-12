/**
 * Automation (docs/cloudk8s/ui-revamp-design.md §4.2, §9). Overview is access and readiness; Workload
 * autoscaler hosts the realtime branch's Workload rightsizing tab unchanged, with its own gating and
 * copy (implementation plan, Addendum 1 Part B). FEAT-005 replaces it with the full Automation pages.
 */
import EksAutomationTab from '../tabs/EksAutomationTab';
import { SectionSubPanel } from './SectionSubPanel';
import OverviewPanel from './automation/OverviewPanel';
import type { EksSectionProps } from './sectionTypes';

export default function AutomationSection({ clusterName, accountId, region, route }: EksSectionProps) {
  return (
    <SectionSubPanel
      sectionId="automation"
      route={route}
      renderPanel={(sub) => {
        if (sub === 'overview') return <OverviewPanel clusterName={clusterName} />;
        if (sub === 'workload-autoscaling') return <EksAutomationTab clusterName={clusterName} accountId={accountId} region={region} />;
        return null;
      }}
    />
  );
}
