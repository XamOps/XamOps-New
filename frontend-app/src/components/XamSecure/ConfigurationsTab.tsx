import React, { useState } from 'react';
import type { PageResp, PostureFinding, ComplianceFramework } from '../../types/xamsecure';
import PostureTab from './PostureTab';
import ComplianceTab from './ComplianceTab';

interface Props {
  posture: PageResp<PostureFinding> | null;
  compliance: ComplianceFramework[] | null;
  isLoading: boolean;
  onPage: (page: number) => void;
}

/**
 * Mirrors Upwind's "Configurations" area: posture findings + compliance frameworks
 * under one tab, with Findings / Frameworks sub-views (Upwind has no separate
 * "Compliance" nav item — frameworks live here).
 */
const ConfigurationsTab: React.FC<Props> = ({ posture, compliance, isLoading, onPage }) => {
  const [view, setView] = useState<'findings' | 'frameworks'>('findings');
  return (
    <div className="space-y-4">
      <div className="flex items-center gap-2">
        <ViewTab label="Findings" active={view === 'findings'} onClick={() => setView('findings')} />
        <ViewTab label="Frameworks" active={view === 'frameworks'} onClick={() => setView('frameworks')} />
      </div>
      {view === 'findings'
        ? <PostureTab data={posture} isLoading={isLoading} onPage={onPage} />
        : <ComplianceTab data={compliance} isLoading={isLoading} />}
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

export default ConfigurationsTab;
