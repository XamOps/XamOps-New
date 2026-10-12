/**
 * Cost (docs/cloudk8s/ui-revamp-design.md §4.2). Slice 1: Cluster hosts today's Cost tab (P2).
 */
import EksCostTab from '../tabs/EksCostTab';
import { SectionSubPanel } from './SectionSubPanel';
import type { EksSectionProps } from './sectionTypes';

export default function CostSection({ eksData, route }: EksSectionProps) {
  return (
    <SectionSubPanel
      sectionId="cost"
      route={route}
      renderPanel={(sub) => (sub === 'cluster' ? <EksCostTab eksData={eksData} /> : null)}
    />
  );
}
