/**
 * Nodes (docs/cloudk8s/ui-revamp-design.md §4.2, §4.3). Slice 1 hosts today's Nodes, Karpenter and
 * Performance tabs; Performance becomes Utilization (P2).
 */
import EksNodesTab from '../tabs/EksNodesTab';
import EksKarpenterTab from '../tabs/EksKarpenterTab';
import EksPerformanceTab from '../tabs/EksPerformanceTab';
import type { EksSubtabId } from '../../../types/eksTypes';
import { SectionSubtabs } from './SectionSubtabs';
import type { EksSectionProps } from './sectionTypes';

export default function NodesSection({ eksData, route }: EksSectionProps) {
  const renderPanel = (sub: EksSubtabId) => {
    switch (sub) {
      case 'nodes':
        return <EksNodesTab eksData={eksData} />;
      case 'karpenter':
        return <EksKarpenterTab eksData={eksData} />;
      case 'utilization':
        return <EksPerformanceTab eksData={eksData} />;
      default:
        return null;
    }
  };
  return <SectionSubtabs sectionId="nodes" label="Nodes views" route={route} renderPanel={renderPanel} />;
}
