import React from 'react';
import type { DatastoreRow } from '../../../types/dspmDatastores';
import DatastoreDrawer, { type DrawerRow } from './drawer/DatastoreDrawer';
import EndpointDrawer from './endpoint/EndpointDrawer';
import SaasDrawer from './saas/SaasDrawer';

const SOURCE_ID = /^[0-9a-fA-F]{24}$/;

export const hasSourceId = (row: DatastoreRow): row is DrawerRow =>
  row.sourceId !== null && SOURCE_ID.test(row.sourceId);

/**
 * The drawer a row opens (design 6.3), each loading everything by the row's 24-hex source id: cloud and
 * on-prem rows the datastore drawer, SaaS rows the SaaS drawer, endpoint rows the Endpoint drawer. Any
 * other row (an unknown category, a missing or malformed source id) opens nothing and is not focusable.
 */
export const drawerKind = (row: DatastoreRow): 'datastore' | 'saas' | 'endpoint' | null =>
  !hasSourceId(row) ? null
    : row.category === 'cloud' || row.category === 'on_prem' ? 'datastore'
    : row.category === 'saas' ? 'saas'
    : row.category === 'endpoint' ? 'endpoint'
    : null;

export const canOpenDrawer = (row: DatastoreRow) => drawerKind(row) !== null;

/**
 * The drawer of an open row, keyed by its source id, for every page that lists datastores (the
 * Datastores page, the Dashboard's Top Risky Datastores). Renders nothing for a row that opens nothing.
 * `onClose` must be stable: the drawers put it in a keydown effect's dependencies.
 */
export const DatastoreDrawerHost: React.FC<{ row: DatastoreRow; onClose: () => void }> = ({ row, onClose }) => {
  if (!hasSourceId(row)) return null;
  switch (drawerKind(row)) {
    case 'datastore': return <DatastoreDrawer key={row.sourceId} row={row} onClose={onClose} />;
    case 'saas': return <SaasDrawer key={row.sourceId} row={row} onClose={onClose} />;
    case 'endpoint': return <EndpointDrawer key={row.sourceId} row={row} onClose={onClose} />;
    default: return null;
  }
};
