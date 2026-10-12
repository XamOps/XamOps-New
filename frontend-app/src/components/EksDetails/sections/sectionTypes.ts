/**
 * What the cluster page hands every primary section (docs/cloudk8s/ui-revamp-design.md §4.2, §15).
 */
import type { UseEksDashboardReturn } from '../../../hooks/useEksDashboard';
import type { SectionRoute } from '../../../hooks/useEksSectionRoute';
import type { EksSectionId, EksSubtabId } from '../../../types/eksTypes';

export interface EksSectionProps {
  clusterName: string;
  /** The page's one dashboard hook; today's tabs read it unchanged (P2). */
  eksData: UseEksDashboardReturn;
  /** The page's `?tab=&sub=` route, so the URL has one owner. */
  route: SectionRoute<EksSectionId, EksSubtabId>;
}
