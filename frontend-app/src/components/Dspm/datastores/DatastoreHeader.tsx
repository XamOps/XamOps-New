import React from 'react';
import { ConsoleOnlyButton } from './ui/ConsoleOnly';

/**
 * Page title, under the console's "Data Inventory" section label, and the console's scan actions. The label
 * is a <p> so "Datastores" stays the page's only h1. The actions run scans in Matters, which XamLens never
 * does, so they are greyed (ConsoleOnly). Endpoint and On-Prem also show the console's "⋯" overflow.
 */
const DatastoreHeader: React.FC<{ headerOverflow: boolean }> = ({ headerOverflow }) => (
  <div className="flex flex-wrap items-start justify-between gap-3">
    <div>
      <p className="text-[10px] font-semibold uppercase tracking-wider text-[rgba(0,0,0,0.4)]">Data Inventory</p>
      <h1 className="text-lg font-bold text-[rgba(0,0,0,0.88)]">Datastores</h1>
      <p className="mt-0.5 text-xs text-[rgba(0,0,0,0.5)]">Unified view across all data sources</p>
    </div>
    <div className="flex items-center gap-2">
      <ConsoleOnlyButton variant="primary">Run Scan Now</ConsoleOnlyButton>
      <ConsoleOnlyButton variant="secondary">Manage Scans</ConsoleOnlyButton>
      {headerOverflow && <ConsoleOnlyButton variant="secondary" icon="mdi:dots-horizontal" label="More actions" />}
    </div>
  </div>
);

export default DatastoreHeader;
