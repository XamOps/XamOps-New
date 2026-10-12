/**
 * Overview (docs/cloudk8s/ui-revamp-design.md §4.2). Slice 1: Summary hosts today's Overview tab (P2).
 */
import EksOverviewTab from '../tabs/EksOverviewTab';
import { SectionSubPanel } from './SectionSubPanel';
import type { EksSectionProps } from './sectionTypes';

export default function OverviewSection({ eksData, route }: EksSectionProps) {
  return (
    <SectionSubPanel
      sectionId="overview"
      route={route}
      renderPanel={(sub) => (sub === 'summary' ? <EksOverviewTab eksData={eksData} /> : null)}
    />
  );
}
