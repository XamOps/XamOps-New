/**
 * Automation (docs/cloudk8s/ui-revamp-design.md §4.2, §9). Slice 1 registers Overview only; the
 * other subtabs arrive with the requests behind them.
 */
import { SectionSubPanel } from './SectionSubPanel';
import OverviewPanel from './automation/OverviewPanel';
import type { EksSectionProps } from './sectionTypes';

export default function AutomationSection({ clusterName, route }: EksSectionProps) {
  return (
    <SectionSubPanel
      sectionId="automation"
      route={route}
      renderPanel={(sub) => (sub === 'overview' ? <OverviewPanel clusterName={clusterName} /> : null)}
    />
  );
}
