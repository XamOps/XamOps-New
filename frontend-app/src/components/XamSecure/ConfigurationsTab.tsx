import React, { useState } from 'react';
import type {
  PageResp, PostureFinding, ComplianceFramework, ConfigurationsDashboard as Dashboard,
  ResourceDetail, IssueSummary, FrameworkControls,
} from '../../types/xamsecure';
import PostureTab from './PostureTab';
import ComplianceTab from './ComplianceTab';
import ConfigurationsDashboard from './ConfigurationsDashboard';

interface Props {
  dashboard: Dashboard | null;
  posture: PageResp<PostureFinding> | null;
  compliance: ComplianceFramework[] | null;
  isLoading: boolean;
  onPage: (page: number) => void;
  loadResourceDetail?: (resourceId: string) => Promise<ResourceDetail>;
  /** Applies a server-side severity filter to the findings list. */
  onSeverity?: (severity: string) => void;
  /** Forwarded to the frameworks view to list each framework's failing controls. */
  issues?: IssueSummary[] | null;
  /** Per-control breakdown for a framework, fetched when one is opened. */
  loadControls?: (frameworkId: string) => Promise<FrameworkControls | null>;
}

/**
 * Mirrors Upwind's "Configurations" area with three sub-views:
 *   Dashboard        — compliance score, findings summary, findings-over-time, top findings.
 *   Findings         — the per-resource posture findings table.
 *   Frameworks & rules — compliance frameworks (Upwind has no separate "Compliance" nav item).
 * Upwind's "Rules" list has no data via the API, so it is not shown.
 */
const ConfigurationsTab: React.FC<Props> = ({
  dashboard, posture, compliance, isLoading, onPage, loadResourceDetail, onSeverity, issues,
  loadControls,
}) => {
  const [view, setView] = useState<'dashboard' | 'findings' | 'frameworks'>('dashboard');
  return (
    <div className="space-y-4">
      <div className="flex items-center gap-2">
        <ViewTab label="Dashboard" active={view === 'dashboard'} onClick={() => setView('dashboard')} />
        <ViewTab label="Findings" active={view === 'findings'} onClick={() => setView('findings')} />
        <ViewTab label="Frameworks & rules" active={view === 'frameworks'} onClick={() => setView('frameworks')} />
      </div>
      {view === 'dashboard' && <ConfigurationsDashboard data={dashboard} isLoading={isLoading} />}
      {view === 'findings' && (
        <PostureTab data={posture} isLoading={isLoading} onPage={onPage}
                    loadResourceDetail={loadResourceDetail}
                    severityCounts={dashboard?.severityCounts}
                    onSeverity={onSeverity} />
      )}
      {view === 'frameworks' && (
        <ComplianceTab data={compliance} isLoading={isLoading} issues={issues}
                        loadControls={loadControls} />
      )}
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
