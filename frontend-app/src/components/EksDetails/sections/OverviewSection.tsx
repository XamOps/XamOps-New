/**
 * Overview (docs/cloudk8s/ui-revamp-design.md §4.2). Slice 1: Summary hosts today's Overview tab (P2).
 */
import EksOverviewTab from '../tabs/EksOverviewTab';
import { SectionSubtabs } from './SectionSubtabs';
import type { EksSectionProps } from './sectionTypes';

export default function OverviewSection({ eksData, route }: EksSectionProps) {
  return (
    <SectionSubtabs
      sectionId="overview"
      label="Overview views"
      route={route}
      renderPanel={(sub) => (sub === 'summary' ? <EksOverviewTab eksData={eksData} /> : null)}
    />
  );
}
