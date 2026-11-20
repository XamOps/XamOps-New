import React from 'react';
import { ConsoleOnlyButton } from '../datastores/ui/ConsoleOnly';
import { ConsoleOnlyRegion, TEXT_PRIMARY, TEXT_SECONDARY } from './ConsoleParts';

/**
 * Page title and, at the right as in the console, "Last Scan:" over "Auto-Scan runs every 24 hours",
 * then the greyed Run Scan: no API key can start a scan (GAP-65). The scan time comes from the console's
 * `discovery/last-scan-time`, which answers a project key with 401, so its value is greyed with no time.
 */
const MisconfigurationHeader: React.FC = () => (
  <div className="flex flex-wrap items-start justify-between gap-3">
    <h1 className={`text-xl font-semibold ${TEXT_PRIMARY}`}>Misconfigurations</h1>
    <div className="flex items-start gap-5">
      <div className="flex flex-col items-start">
        <div className="flex items-center gap-[5px] text-sm">
          <span className={TEXT_SECONDARY}>Last Scan:</span>
          <ConsoleOnlyRegion label="Last Scan time" inline>
            <span className={`${TEXT_PRIMARY} px-0.5`}>-</span>
          </ConsoleOnlyRegion>
        </div>
        <span className={`text-xs ${TEXT_SECONDARY}`}>Auto-Scan runs every 24 hours</span>
      </div>
      <ConsoleOnlyButton>Run Scan</ConsoleOnlyButton>
    </div>
  </div>
);

export default MisconfigurationHeader;
