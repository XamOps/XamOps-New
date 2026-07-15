import React, { useState } from 'react';
import type { PageResp, ThreatDetection, ThreatPolicy } from '../../types/xamsecure';
import ThreatsTab from './ThreatsTab';
import ThreatPoliciesTab from './ThreatPoliciesTab';

interface Props {
  threats: PageResp<ThreatDetection> | null;
  policies: ThreatPolicy[] | null;
  isLoading: boolean;
  onPage: (page: number) => void;
  onArchive?: (upwindId: string) => void;
}

/**
 * Upwind's Threats area: the runtime detections feed plus detection Policies,
 * under one tab with Detections / Policies sub-views (Upwind nests policies here
 * rather than as a separate top-level nav item).
 */
const ThreatsSection: React.FC<Props> = ({ threats, policies, isLoading, onPage, onArchive }) => {
  const [view, setView] = useState<'detections' | 'policies'>('detections');
  return (
    <div className="space-y-4">
      <div className="flex items-center gap-2">
        <ViewTab label="Detections" active={view === 'detections'} onClick={() => setView('detections')} />
        <ViewTab label="Policies" active={view === 'policies'} onClick={() => setView('policies')} />
      </div>
      {view === 'detections'
        ? <ThreatsTab threats={threats} isLoading={isLoading} onPage={onPage} onArchive={onArchive} />
        : <ThreatPoliciesTab data={policies} isLoading={isLoading} />}
    </div>
  );
};

const ViewTab: React.FC<{ label: string; active: boolean; onClick: () => void }> = ({ label, active, onClick }) => (
  <button
    onClick={onClick}
    className={`px-3 py-1.5 text-xs font-semibold rounded-lg transition-colors ${
      active ? 'bg-gray-900 text-white' : 'bg-white text-gray-600 border border-gray-200 hover:bg-gray-50'}`}
  >
    {label}
  </button>
);

export default ThreatsSection;
