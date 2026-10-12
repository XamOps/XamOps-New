/**
 * Workloads (docs/cloudk8s/ui-revamp-design.md §4.2, §4.3). Slice 1 hosts today's Workloads, Pods,
 * Containers and Resources tabs (P2).
 */
import EksWorkloadsTab from '../tabs/EksWorkloadsTab';
import EksPodsTab from '../tabs/EksPodsTab';
import EksContainersTab from '../tabs/EksContainersTab';
import EksResourcesTab from '../tabs/EksResourcesTab';
import type { EksSubtabId } from '../../../types/eksTypes';
import { SectionSubtabs } from './SectionSubtabs';
import type { EksSectionProps } from './sectionTypes';

export default function WorkloadsSection({ eksData, route }: EksSectionProps) {
  const renderPanel = (sub: EksSubtabId) => {
    switch (sub) {
      case 'workloads':
        return <EksWorkloadsTab eksData={eksData} />;
      case 'pods':
        return <EksPodsTab eksData={eksData} />;
      case 'containers':
        return <EksContainersTab eksData={eksData} />;
      case 'resources':
        return <EksResourcesTab eksData={eksData} />;
      default:
        return null;
    }
  };
  return <SectionSubtabs sectionId="workloads" label="Workloads views" route={route} renderPanel={renderPanel} />;
}
