/**
 * The current subtab's panel of a section (docs/cloudk8s/ui-revamp-design.md §4.2; implementation
 * plan, Addendum 1, J-6).
 *
 * The subtabs are listed in the left sidebar under the section (ClusterSectionsNav), not in a
 * horizontal strip, so a section renders only the panel the URL names. A `sub` this section doesn't
 * register falls back to its default, as the resolver does.
 */
import type { ReactNode } from 'react';
import type { SectionRoute } from '../../../hooks/useEksSectionRoute';
import type { EksSectionId, EksSubtabId } from '../../../types/eksTypes';

export interface SectionSubPanelProps {
  sectionId: EksSectionId;
  route: SectionRoute<EksSectionId, EksSubtabId>;
  renderPanel: (sub: EksSubtabId) => ReactNode;
}

export function SectionSubPanel({ sectionId, route, renderPanel }: SectionSubPanelProps) {
  const section = route.registry.find((s) => s.id === sectionId);
  if (!section) return null;
  const sub = section.subs.some((s) => s.id === route.sub) ? route.sub : section.defaultSub;
  return <>{renderPanel(sub)}</>;
}

export default SectionSubPanel;
