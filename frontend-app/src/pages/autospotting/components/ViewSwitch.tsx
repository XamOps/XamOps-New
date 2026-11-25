import React from 'react';
import { AriaTabGroup } from '../../../components/ui';
export type ViewId = 'groups' | 'analytics' | 'activity' | 'commitments';
/** The AWS view switch (design-ux-ops.md section 2.2). The selected view lives in `?view=`. */
export const VIEWS: Array<{ id: ViewId; label: string; icon: string }> = [
  { id: 'groups', label: 'Groups', icon: 'mdi:server-network' },
  { id: 'analytics', label: 'Analytics', icon: 'mdi:chart-line' },
  { id: 'activity', label: 'Activity', icon: 'mdi:history' },
  { id: 'commitments', label: 'Commitments', icon: 'mdi:handshake-outline' },
];
export const ViewSwitch: React.FC<{ view: ViewId; onChange: (v: ViewId) => void }> = ({ view, onChange }) => (
  <AriaTabGroup
    tabs={VIEWS}
    selectedKey={view}
    onSelectionChange={k => onChange(k as ViewId)}
    label="AutoSpotting views"
    className="mb-5"
  />
);
export default ViewSwitch;
