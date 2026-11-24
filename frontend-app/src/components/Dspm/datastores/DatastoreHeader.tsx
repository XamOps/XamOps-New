import React, { type RefObject } from 'react';
import { ConsoleOnlyButton } from './ui/ConsoleOnly';

interface Props {
  headerOverflow: boolean;
  /** Opens Scan Management (design 6.8). */
  onManageScans: () => void;
  /** Attached to "Manage Scans", so the page can focus it again when Scan Management closes. */
  manageScansRef: RefObject<HTMLButtonElement | null>;
}

/**
 * Page title and the console's scan actions. "Manage Scans" opens the view-only Scan Management drawer;
 * "Run Scan Now" runs scans in Matters, which XamLens never does, so it is greyed (ConsoleOnly), as is
 * the console's "⋯" overflow on Endpoint and On-Prem. The "Data Inventory" section label is in the
 * sidebar, as in the console, not on the page.
 */
const DatastoreHeader: React.FC<Props> = ({ headerOverflow, onManageScans, manageScansRef }) => (
  <div className="flex flex-wrap items-start justify-between gap-3">
    <div>
      <h1 className="text-lg font-bold text-[rgba(0,0,0,0.88)]">Datastores</h1>
      <p className="mt-0.5 text-xs text-[rgba(0,0,0,0.5)]">Unified view across all data sources</p>
    </div>
    <div className="flex items-center gap-2">
      <ConsoleOnlyButton variant="primary">Run Scan Now</ConsoleOnlyButton>
      <button
        ref={manageScansRef}
        type="button"
        onClick={onManageScans}
        className="inline-flex items-center gap-1.5 rounded-lg border border-[rgba(0,0,0,0.12)] bg-white px-3 py-1.5 text-xs font-semibold text-[rgba(0,0,0,0.7)] transition-colors hover:bg-[rgba(0,0,0,0.03)] focus:outline-none focus-visible:ring-2 focus-visible:ring-[rgba(0,0,0,0.25)]"
      >
        Manage Scans
      </button>
      {headerOverflow && <ConsoleOnlyButton variant="secondary" icon="mdi:dots-horizontal" label="More actions" />}
    </div>
  </div>
);

export default DatastoreHeader;
