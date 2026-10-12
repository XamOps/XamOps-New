/**
 * Security (docs/cloudk8s/ui-revamp-design.md §4.2). Slice 1: Vulnerabilities hosts today's Security
 * tab (P2); Compliance and Runtime split out of it in slice 2.
 */
import EksSecurityTab from '../tabs/EksSecurityTab';
import { SectionSubPanel } from './SectionSubPanel';
import type { EksSectionProps } from './sectionTypes';

export default function SecuritySection({ eksData, route }: EksSectionProps) {
  return (
    <SectionSubPanel
      sectionId="security"
      route={route}
      renderPanel={(sub) => (sub === 'vulnerabilities' ? <EksSecurityTab eksData={eksData} /> : null)}
    />
  );
}
