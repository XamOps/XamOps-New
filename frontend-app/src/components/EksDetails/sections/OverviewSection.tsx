/**
 * Overview (docs/cloudk8s/ui-revamp-design.md §4.2, §10.1). Summary is the restyled overview: health
 * signals, tiles with sources, top findings, data sources, namespace utilisation and top pods.
 */
import SummaryPanel from './overview/SummaryPanel';
import { SectionSubPanel } from './SectionSubPanel';
import type { EksSectionProps } from './sectionTypes';

export default function OverviewSection(props: EksSectionProps) {
  return (
    <SectionSubPanel
      sectionId="overview"
      route={props.route}
      renderPanel={(sub) => (sub === 'summary' ? <SummaryPanel {...props} /> : null)}
    />
  );
}
