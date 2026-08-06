import React, { useMemo, useState } from 'react';
import { Icon } from '@iconify/react';
import { Link, useParams } from 'react-router-dom';
import { useAccounts } from '../contexts/AccountContext';
import { useXamSecureData } from '../hooks/useXamSecureData';
import StoryDrawer from '../components/XamSecure/StoryDrawer';
import type { ThreatStoryDetail } from '../types/xamsecure';
import {
  XamSecureHeader, IssuesTab, AttackSurfaceTab, OverviewTab, ThreatsSection, VulnerabilitiesTab,
  ConfigurationsTab, InventoryTab, ApiSecurityTab, ComponentsTab, WorkflowsTab, OnboardingTab,
  IdentitiesTab,
} from '../components/XamSecure';

export type XamSecureTab =
  | 'issues' | 'overview' | 'attack-surface' | 'threats' | 'vulnerabilities' | 'posture'
  | 'inventory' | 'api-security' | 'identities' | 'components' | 'threat-policies' | 'workflows'
  | 'compliance' | 'onboarding';

export const XAMSECURE_TABS: { id: XamSecureTab; label: string }[] = [
  { id: 'overview', label: 'Home' },
  { id: 'issues', label: 'Issues' },
  { id: 'inventory', label: 'Inventory' },
  { id: 'attack-surface', label: 'Attack Surface' },
  { id: 'vulnerabilities', label: 'Vulnerabilities' },
  { id: 'posture', label: 'Configurations' },
  { id: 'api-security', label: 'APIs' },
  { id: 'identities', label: 'Identities' },
  { id: 'threats', label: 'Threats' },
  // "Packages", not "Components". Upwind's Components area is sensor and agent fleet
  // management (Sensors / Cloud Scanners / Integrations / Workflows, with health and
  // coverage); this tab is a software bill of materials. Sharing their label implied parity
  // with a feature we cannot build — nine candidate sensor endpoints all return 404, so there
  // is no API behind their version.
  { id: 'components', label: 'Packages' },
  // Workflows & Onboarding remain valid routes (deep-link reachable) but are not in the
  // sidebar nav — they map to Upwind's Settings area, not its security nav.
  { id: 'workflows', label: 'Workflows' },
  { id: 'onboarding', label: 'Onboarding' },
];

const VALID_TABS = new Set<string>(XAMSECURE_TABS.map((t) => t.id));

const XamSecurePage: React.FC = () => {
  const { selectedAccountIds, accounts, isLoading: accountsLoading } = useAccounts();
  // Send EVERY selected account, not just the first. Taking [0] meant that in
  // multi-account mode, selecting two accounts showed less data than selecting none
  // (no selection falls through to "all accounts I own" on the server).
  const selectedAccountId = selectedAccountIds.length > 0 ? selectedAccountIds.join(',') : null;
  const { tab } = useParams<{ tab?: string }>();

  const activeTab = useMemo<XamSecureTab>(
    () => (tab && VALID_TABS.has(tab) ? (tab as XamSecureTab) : 'overview'),
    [tab]
  );

  // Held here so paging these server-filtered tables preserves the active severity filter.
  const [postureSeverity, setPostureSeverity] = useState('');
  const [cveSeverity, setCveSeverity] = useState('');
  const [cveView, setCveView] = useState('');

  const {
    summary, issues, attackSurface, threats, vulnDashboard, vulnByCve, posture, inventory,
    inventoryCategories, inventoryRisk, networkTopology, compliance, configDashboard, onboarding, apiSecurity, components,
    threatPolicies, workflows, stories, threatOverview, loadThreatOverview, identities,
    noAccountSelected,
    isLoading, refresh, loadIssueDetail, loadResourceDetail, loadStoryDetail,
    loadThreats, loadVulnByCve, loadCveFindings, loadPosture, loadInventory,
    archiveThreat,
  } = useXamSecureData({ selectedAccountId, activeTab });

  // Threat-story detail panel. Fetched on open rather than with the tab, since the narrative
  // is large and only wanted when someone actually drills in.
  const [story, setStory] = useState<ThreatStoryDetail | null>(null);
  const [storyOpen, setStoryOpen] = useState(false);
  const [storyLoading, setStoryLoading] = useState(false);

  const openStory = React.useCallback(async (storyId: string) => {
    setStoryOpen(true);
    setStoryLoading(true);
    setStory(null);
    try {
      setStory(await loadStoryDetail(storyId));
    } catch (err) {
      console.error('[XamSecure] story load failed:', err);
      setStory(null);
    } finally {
      setStoryLoading(false);
    }
  }, [loadStoryDetail]);

  // An account must be chosen before anything is shown. Falling through to "all accounts I
  // own" would put an unselected account's findings on screen, which for a security view is
  // the wrong default.
  //
  // Three distinct states, because telling someone to pick an account when they have none is
  // a dead end: AccountContext only auto-selects when at least one account exists
  // (`filtered.length > 0`), so a client with zero accounts would otherwise sit on a prompt
  // asking them to use an empty sidebar.
  if (noAccountSelected) {
    const stillLoadingAccounts = accountsLoading && accounts.length === 0;
    return (
      <main className="flex-1 flex flex-col min-h-screen bg-white">
        <XamSecureHeader isLoading={stillLoadingAccounts} onRefresh={refresh} />
        <div className="flex-1 flex items-center justify-center p-6">
          {stillLoadingAccounts ? (
            <p className="text-sm text-gray-400">Loading accounts...</p>
          ) : accounts.length === 0 ? (
            <div className="text-center max-w-sm">
              <Icon icon="mdi:cloud-off-outline" width={40} className="mx-auto text-gray-300" />
              <p className="mt-3 text-sm font-semibold text-gray-900">No cloud accounts connected</p>
              <p className="mt-1 text-xs text-gray-500">
                XamSecure reports on connected cloud accounts. Add one to get started.
              </p>
              <Link to="/account-manager"
                    className="mt-4 inline-block rounded-lg bg-gray-900 px-4 py-2 text-xs font-semibold
                               text-white hover:bg-gray-800 focus:outline-none
                               focus-visible:ring-2 focus-visible:ring-gray-900">
                Go to Account Manager
              </Link>
            </div>
          ) : (
            <div className="text-center max-w-sm">
              <Icon icon="mdi:shield-search" width={40} className="mx-auto text-gray-300" />
              <p className="mt-3 text-sm font-semibold text-gray-900">Select an account</p>
              <p className="mt-1 text-xs text-gray-500">
                Choose a cloud account from the sidebar to see its security findings.
                XamSecure only ever shows data for the accounts you have selected.
              </p>
            </div>
          )}
        </div>
      </main>
    );
  }

  return (
    <main className="flex-1 flex flex-col min-h-screen bg-white">
      <XamSecureHeader isLoading={isLoading} onRefresh={refresh} />
      {/* pb-24 keeps the bottom pager clear of the floating chat-support widget (bottom-right). */}
      <div className="p-6 pb-24 space-y-6">
        {activeTab === 'issues' && (
          <IssuesTab data={issues} isLoading={isLoading} loadDetail={loadIssueDetail}
                     loadResourceDetail={loadResourceDetail} />
        )}
        {activeTab === 'attack-surface' && (
          <AttackSurfaceTab data={attackSurface} isLoading={isLoading} loadDetail={loadIssueDetail}
                            loadResourceDetail={loadResourceDetail} />
        )}
        {activeTab === 'overview' && (
          <OverviewTab summary={summary} issues={issues} threats={threats} onOpenStory={openStory}
                       stories={stories} vulnDashboard={vulnDashboard} isLoading={isLoading} />
        )}
        {activeTab === 'threats' && (
          <ThreatsSection threats={threats} policies={threatPolicies} isLoading={isLoading}
                          stories={stories} overview={threatOverview}
                          onPage={(p) => loadThreats(p)} onArchive={archiveThreat}
                          loadResourceDetail={loadResourceDetail}
                          onOpenStory={openStory}
                          onTrendRange={(d) => { void loadThreatOverview(true, d); }} />
        )}
        {activeTab === 'vulnerabilities' && (
          <VulnerabilitiesTab dashboard={vulnDashboard} byCve={vulnByCve} isLoading={isLoading}
                              onCvePage={(p) => loadVulnByCve(p, cveSeverity, cveView)}
                              loadCveFindings={loadCveFindings}
                              loadResourceDetail={loadResourceDetail}
                              onCveSeverity={(s) => { setCveSeverity(s); loadVulnByCve(0, s, cveView); }}
                              onCveView={(v) => { setCveView(v); setCveSeverity(''); loadVulnByCve(0, '', v); }} />
        )}
        {activeTab === 'posture' && (
          <ConfigurationsTab dashboard={configDashboard} posture={posture} compliance={compliance}
                             isLoading={isLoading} onPage={(p) => loadPosture(p, postureSeverity)}
                             loadResourceDetail={loadResourceDetail}
                             onSeverity={(s) => { setPostureSeverity(s); loadPosture(0, s); }}
                             issues={issues} />
        )}
        {activeTab === 'inventory' && (
          <InventoryTab data={inventory} categories={inventoryCategories} risk={inventoryRisk}
                        network={networkTopology}
                        isLoading={isLoading} onLoad={(page, category) => loadInventory(page, category)}
                        loadResourceDetail={loadResourceDetail} />
        )}
        {activeTab === 'api-security' && <ApiSecurityTab data={apiSecurity} isLoading={isLoading} />}
        {activeTab === 'identities' && (
          <IdentitiesTab data={identities} isLoading={isLoading}
                         loadResourceDetail={loadResourceDetail} />
        )}
        {activeTab === 'components' && <ComponentsTab data={components} isLoading={isLoading} />}
        {activeTab === 'workflows' && <WorkflowsTab data={workflows} isLoading={isLoading} />}
        {activeTab === 'onboarding' && <OnboardingTab accounts={onboarding} isLoading={isLoading} />}
      </div>

      {storyOpen && (
        <StoryDrawer story={story} isLoading={storyLoading}
                     onClose={() => { setStoryOpen(false); setStory(null); }}
                     onOpenResource={undefined} />
      )}
    </main>
  );
};

export default XamSecurePage;
