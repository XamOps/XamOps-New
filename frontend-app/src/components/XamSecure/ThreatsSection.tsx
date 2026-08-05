import React, { useState } from 'react';
import { Icon } from '@iconify/react';
import type {
  PageResp, ThreatDetection, ThreatPolicy, ResourceDetail,
  ThreatStoriesSummary, ThreatOverview,
} from '../../types/xamsecure';
import ThreatsTab from './ThreatsTab';
import ThreatPoliciesTab from './ThreatPoliciesTab';
import ThreatsDashboard from './ThreatsDashboard';
import ThreatStoriesTab from './ThreatStoriesTab';

interface Props {
  threats: PageResp<ThreatDetection> | null;
  policies: ThreatPolicy[] | null;
  stories: ThreatStoriesSummary | null;
  overview: ThreatOverview | null;
  isLoading: boolean;
  onPage: (page: number) => void;
  onArchive?: (upwindId: string) => void;
  loadResourceDetail?: (resourceId: string) => Promise<ResourceDetail>;
  /** Opens the shared story panel, which the page owns so it works from Overview too. */
  onOpenStory?: (storyId: string) => void;
}

type View = 'dashboard' | 'stories' | 'detections' | 'policies';

/**
 * Upwind's Threats area, which nests Dashboard / Stories / Detections / Policies under one
 * top-level item rather than splitting them across the sidebar.
 *
 * Their fifth sub-tab, Investigation, is an interactive query builder over raw sensor events.
 * We sync detections, not events, so it is shown disabled with the reason rather than either
 * omitted (which would misrepresent what Upwind offers) or faked over detection data (which
 * would answer a different question than the one the tab implies).
 */
const ThreatsSection: React.FC<Props> = ({
  threats, policies, stories, overview, isLoading, onPage, onArchive,
  loadResourceDetail, onOpenStory,
}) => {
  const [view, setView] = useState<View>('dashboard');

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center gap-2">
        <ViewTab label="Dashboard" active={view === 'dashboard'} onClick={() => setView('dashboard')} />
        <ViewTab label="Stories" count={stories?.stories.length} active={view === 'stories'}
                 onClick={() => setView('stories')} />
        <ViewTab label="Detections" count={threats?.totalElements} active={view === 'detections'}
                 onClick={() => setView('detections')} />
        <ViewTab label="Policies" count={policies?.length} active={view === 'policies'}
                 onClick={() => setView('policies')} />
        <ViewTab
          label="Investigation"
          active={false}
          disabled
          reason="Upwind's investigation view queries raw sensor events. We sync detections, not events, so there is nothing to query here."
          onClick={() => {}}
        />
      </div>

      {view === 'dashboard' && (
        <ThreatsDashboard
          data={overview}
          stories={stories}
          isLoading={isLoading}
          onOpenStory={onOpenStory}
          onSeeAllStories={() => setView('stories')}
        />
      )}
      {view === 'stories' && (
        <ThreatStoriesTab
          stories={stories}
          isLoading={isLoading}
          onOpenStory={onOpenStory}
        />
      )}
      {view === 'detections' && (
        <ThreatsTab threats={threats} isLoading={isLoading} onPage={onPage} onArchive={onArchive}
                    loadResourceDetail={loadResourceDetail} />
      )}
      {view === 'policies' && <ThreatPoliciesTab data={policies} isLoading={isLoading} />}
    </div>
  );
};

const ViewTab: React.FC<{
  label: string; active: boolean; onClick: () => void;
  count?: number; disabled?: boolean; reason?: string;
}> = ({ label, active, onClick, count, disabled, reason }) => (
  <button
    type="button"
    onClick={onClick}
    disabled={disabled}
    title={disabled ? reason : undefined}
    className={`flex items-center gap-1.5 px-3 py-1.5 text-xs font-semibold rounded-lg transition-colors
                focus:outline-none focus-visible:ring-2 focus-visible:ring-gray-900 ${
      disabled
        ? 'bg-white text-gray-300 border border-dashed border-gray-200 cursor-not-allowed'
        : active
          ? 'bg-gray-900 text-white'
          : 'bg-white text-gray-600 border border-gray-200 hover:bg-gray-50'}`}
  >
    {label}
    {count != null && (
      <span className={`text-[10px] font-bold ${active ? 'text-gray-300' : 'text-gray-400'}`}>
        {count.toLocaleString()}
      </span>
    )}
    {disabled && <Icon icon="mdi:information-outline" width={11} />}
  </button>
);

export default ThreatsSection;
