import React, { useState } from 'react';
import { SegmentedToggle } from '../datastores/ui/SegmentedToggle';
import SaasScanConfiguration from './SaasScanConfiguration';
import EndpointScanConfiguration from './EndpointScanConfiguration';
import { PanelIntro } from './ScanParts';

export type ScanPlatform = 'saas' | 'endpoint';

const PLATFORMS = [
  { value: 'saas' as const, label: 'SaaS' },
  { value: 'endpoint' as const, label: 'Endpoint' },
];

/**
 * Scan Configurations (FR-SM-3, FR-SM-4): the SaaS | Endpoint switch over the two read-only settings
 * views. Each view mounts when first shown and stays mounted, so switching back keeps its state.
 */
const ScanConfigurationsTab: React.FC<{ initialPlatform: ScanPlatform }> = ({ initialPlatform }) => {
  const [platform, setPlatform] = useState<ScanPlatform>(initialPlatform);
  const [shown, setShown] = useState<ReadonlySet<ScanPlatform>>(() => new Set([initialPlatform]));
  const choose = (next: ScanPlatform) => {
    setPlatform(next);
    setShown(previous => (previous.has(next) ? previous : new Set([...previous, next])));
  };
  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <PanelIntro text="Scan settings for SaaS apps and endpoint agents." />
        <SegmentedToggle options={PLATFORMS} value={platform} onChange={choose} label="Platform" />
      </div>
      {shown.has('saas') && <div hidden={platform !== 'saas'}><SaasScanConfiguration /></div>}
      {shown.has('endpoint') && <div hidden={platform !== 'endpoint'}><EndpointScanConfiguration /></div>}
    </div>
  );
};

export default ScanConfigurationsTab;
