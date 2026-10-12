/**
 * Workloads (docs/cloudk8s/ui-revamp-design.md §4.2, §4.3, §10.4). Workloads is the re-homed workload
 * list with the engine's recommendations; Pods and Containers are the rewritten tabs; Resources lists
 * HPAs, Services, Ingresses, PVCs and Jobs (its events moved to Overview › Events).
 */
import EksPodsTab from '../tabs/EksPodsTab';
import EksContainersTab from '../tabs/EksContainersTab';
import type { EksSubtabId } from '../../../types/eksTypes';
import { SectionSubPanel } from './SectionSubPanel';
import ResourcesPanel from './workloads/ResourcesPanel';
import WorkloadsPanel from './workloads/WorkloadsPanel';
import type { EksSectionProps } from './sectionTypes';

export default function WorkloadsSection(props: EksSectionProps) {
  const { eksData, route } = props;
  const renderPanel = (sub: EksSubtabId) => {
    switch (sub) {
      case 'workloads':
        return <WorkloadsPanel {...props} />;
      case 'pods':
        return <EksPodsTab eksData={eksData} clusterName={props.clusterName} />;
      case 'containers':
        return <EksContainersTab eksData={eksData} clusterName={props.clusterName} />;
      case 'resources':
        return <ResourcesPanel {...props} />;
      default:
        return null;
    }
  };
  return <SectionSubPanel sectionId="workloads" route={route} renderPanel={renderPanel} />;
}
