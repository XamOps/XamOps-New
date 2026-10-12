/**
 * A section's registered subtabs as the `sub` SectionTabs (docs/cloudk8s/ui-revamp-design.md §4.2, §14).
 *
 * A section with one registered subtab renders that panel without a tablist: a lone tab offers no
 * choice. The tablist appears once a later slice registers a second subtab (P3).
 */
import type { ReactNode } from 'react';
import { SectionTabs } from '../../CloudK8s/shared/SectionTabs';
import type { SectionRoute } from '../../../hooks/useEksSectionRoute';
import type { EksSectionId, EksSubtabId } from '../../../types/eksTypes';

export interface SectionSubtabsProps {
  sectionId: EksSectionId;
  /** Names the tablist, e.g. "Workloads views". */
  label: string;
  route: SectionRoute<EksSectionId, EksSubtabId>;
  renderPanel: (sub: EksSubtabId) => ReactNode;
}

export function SectionSubtabs({ sectionId, label, route, renderPanel }: SectionSubtabsProps) {
  const section = route.registry.find((s) => s.id === sectionId);
  if (!section) return null;
  const sub = section.subs.some((s) => s.id === route.sub) ? route.sub : section.defaultSub;
  if (section.subs.length < 2) return <>{renderPanel(sub)}</>;
  return (
    <SectionTabs
      variant="sub"
      label={label}
      items={section.subs}
      selectedKey={sub}
      onSelectionChange={(key) => route.select(sectionId, key)}
      renderPanel={renderPanel}
    />
  );
}

export default SectionSubtabs;
