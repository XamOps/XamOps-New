import React from 'react';
import { ConsoleOnlyButton } from '../datastores/ui/ConsoleOnly';

/**
 * Page title, the auto-scan note and the greyed Run Scan: no API key can start a scan (GAP-65). The
 * console's "Last Scan" time has no API source, so it is left out.
 */
const MisconfigurationHeader: React.FC = () => (
  <div className="flex flex-wrap items-start justify-between gap-3">
    <h1 className="text-lg font-bold text-[rgba(0,0,0,0.88)]">Misconfigurations</h1>
    <div className="flex items-center gap-3">
      <span className="text-[11px] text-[rgba(0,0,0,0.5)]">Auto-Scan runs every 24 hours</span>
      <ConsoleOnlyButton variant="primary">Run Scan</ConsoleOnlyButton>
    </div>
  </div>
);

export default MisconfigurationHeader;
