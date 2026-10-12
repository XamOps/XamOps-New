/**
 * Nodes (docs/cloudk8s/ui-revamp-design.md §4.2, §4.3). Slice 1 hosts today's Nodes, Karpenter and
 * Performance tabs; Performance becomes Utilization (P2).
 */
import EksNodesTab from '../tabs/EksNodesTab';
import EksKarpenterTab from '../tabs/EksKarpenterTab';
import EksPerformanceTab from '../tabs/EksPerformanceTab';
import type { EksSubtabId } from '../../../types/eksTypes';
import NodeGroupsPanel from './nodes/NodeGroupsPanel';
import { SectionSubPanel } from './SectionSubPanel';
import type { EksSectionProps } from './sectionTypes';

export default function NodesSection(props: EksSectionProps) {
  const { eksData, route } = props;
  const renderPanel = (sub: EksSubtabId) => {
    switch (sub) {
      case 'nodes':
        return <EksNodesTab eksData={eksData} clusterName={props.clusterName} />;
      case 'node-groups':
        return <NodeGroupsPanel {...props} />;
      case 'karpenter':
        return <EksKarpenterTab eksData={eksData} />;
      case 'utilization':
        return <EksPerformanceTab eksData={eksData} />;
      default:
        return null;
    }
  };
  return <SectionSubPanel sectionId="nodes" route={route} renderPanel={renderPanel} />;
}
