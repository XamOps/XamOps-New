/**
 * Cost (docs/cloudk8s/ui-revamp-design.md §4.2). Slice 1: Cluster hosts today's Cost tab (P2).
 */
import EksCostTab from '../tabs/EksCostTab';
import { SectionSubtabs } from './SectionSubtabs';
import type { EksSectionProps } from './sectionTypes';

export default function CostSection({ eksData, route }: EksSectionProps) {
  return (
    <SectionSubtabs
      sectionId="cost"
      label="Cost views"
      route={route}
      renderPanel={(sub) => (sub === 'cluster' ? <EksCostTab eksData={eksData} /> : null)}
    />
  );
}
