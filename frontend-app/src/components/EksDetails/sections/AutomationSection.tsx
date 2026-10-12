/**
 * Automation (docs/cloudk8s/ui-revamp-design.md §4.2, §9). Slice 1 registers Overview only; the
 * other subtabs arrive with the requests behind them.
 */
import { SectionSubtabs } from './SectionSubtabs';
import OverviewPanel from './automation/OverviewPanel';
import type { EksSectionProps } from './sectionTypes';

export default function AutomationSection({ clusterName, route }: EksSectionProps) {
  return (
    <SectionSubtabs
      sectionId="automation"
      label="Automation views"
      route={route}
      renderPanel={(sub) => (sub === 'overview' ? <OverviewPanel clusterName={clusterName} /> : null)}
    />
  );
}
