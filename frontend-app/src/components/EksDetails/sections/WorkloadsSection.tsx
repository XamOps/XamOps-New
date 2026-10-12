/**
 * Workloads (docs/cloudk8s/ui-revamp-design.md §4.2, §4.3, §10.4). Workloads is the re-homed workload
 * list with the engine's recommendations; Pods, Containers and Resources still host today's tabs (P2).
 */
import EksPodsTab from '../tabs/EksPodsTab';
import EksContainersTab from '../tabs/EksContainersTab';
import EksResourcesTab from '../tabs/EksResourcesTab';
import type { EksSubtabId } from '../../../types/eksTypes';
import { SectionSubPanel } from './SectionSubPanel';
import WorkloadsPanel from './workloads/WorkloadsPanel';
import type { EksSectionProps } from './sectionTypes';

export default function WorkloadsSection(props: EksSectionProps) {
  const { eksData, route } = props;
  const renderPanel = (sub: EksSubtabId) => {
    switch (sub) {
      case 'workloads':
        return <WorkloadsPanel {...props} />;
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
  return <SectionSubPanel sectionId="workloads" route={route} renderPanel={renderPanel} />;
}
