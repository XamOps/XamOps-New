import React, { useMemo, useState } from 'react';
import { Icon } from '@iconify/react';
import { Link, useParams } from 'react-router-dom';
import { useAccounts } from '../contexts/AccountContext';
import { useXamSecureData } from '../hooks/useXamSecureData';
import StoryDrawer from '../components/XamSecure/StoryDrawer';
import type { ThreatStoryDetail } from '../types/xamsecure';
import {
  XAMSECURE_TABS, VALID_XAMSECURE_TABS, firstTabForGroups, groupForTab, tabDef,
  type XamSecureGroup, type XamSecureTab,
} from '../constants/xamsecureTabs';
import { useDspmStatus, type DspmSurface } from '../hooks/useDspmData';
import DspmAssetsTab from '../components/Dspm/DspmAssetsTab';
import DspmStatusTab from '../components/Dspm/DspmStatusTab';
import ComingSoonTab from '../components/XamSecure/ComingSoonTab';
import {
  XamSecureHeader, IssuesTab, AttackSurfaceTab, OverviewTab, ThreatsSection, VulnerabilitiesTab,
  ConfigurationsTab, InventoryTab, ApiSecurityTab, ComponentsTab, CodeTab, WorkflowsTab, OnboardingTab,
  IdentitiesTab,
} from '../components/XamSecure';

// The tab catalog lives in constants/xamsecureTabs so the sidebar reads the same list. It used
// to be declared here and hand-copied into Sidebar.tsx, which is how the Code tab shipped with
// no nav entry and Packages kept showing as Components.
export type { XamSecureTab };
export { XAMSECURE_TABS };

/** DSPM asset tabs and the backend surface each one reads (`/api/dspm/{surface}/…`). */
const DSPM_SURFACES: Partial<Record<XamSecureTab, DspmSurface>> = {
  'dspm-cloud': 'cloud',
  'dspm-saas': 'saas',
  'dspm-endpoint': 'endpoint',
};

const XamSecurePage: React.FC = () => {
  const { selectedAccountIds, accounts, isLoading: accountsLoading } = useAccounts();
  // Send EVERY selected account, not just the first. Taking [0] meant that in
  // multi-account mode, selecting two accounts showed less data than selecting none
  // (no selection falls through to "all accounts I own" on the server).
  const selectedAccountId = selectedAccountIds.length > 0 ? selectedAccountIds.join(',') : null;
  const { tab } = useParams<{ tab?: string }>();

  // DSPM connection state, which also tells us whether the org holds the add-on at all. Fetched here
  // rather than in the tab component because the landing-tab decision below needs it.
  const { status: dspmStatus, statusLoading: dspmLoading, reloadStatus } = useDspmStatus();

  /**
   * Groups this client can actually use.
   *
   * CNAPP is always included: its own tabs already handle the "no connected accounts" case, and there is
   * no cheap entitlement signal for it the way `dspmStatus.entitled` is for DSPM.
   */
  const availableGroups = useMemo<XamSecureGroup[]>(() => {
    const groups: XamSecureGroup[] = ['CNAPP'];
    if (dspmStatus?.entitled) groups.push('DSPM');
    return groups;
  }, [dspmStatus?.entitled]);

  /**
   * The tab on screen.
   *
   * Falls back to the first tab the client is entitled to rather than always to `overview`. `/xamsecure`
   * with no tab used to redirect straight to `overview`, which is a CNAPP tab — a DSPM-only customer
   * landed on an Upwind page with no data and no route out.
   */
  const activeTab = useMemo<XamSecureTab>(() => {
    if (tab && VALID_XAMSECURE_TABS.has(tab)) return tab as XamSecureTab;
    return firstTabForGroups(availableGroups) ?? 'overview';
  }, [tab, availableGroups]);

  /** Which product owns the current tab. Decides whether the account-picker guard applies. */
  const activeGroup = groupForTab(activeTab) ?? 'CNAPP';
  const isDspmTab = activeGroup === 'DSPM';
  const dspmSurface = DSPM_SURFACES[activeTab];

  /**
   * Definition of the tab on screen, for the coming-soon placeholder.
   *
   * An unbuilt tab short-circuits every other state below — including the account-picker prompt and the
   * DSPM connection states. Asking someone to select an account, or telling them their workspace is being
   * provisioned, is misleading for a view that would show nothing either way.
   */
  const activeDef = tabDef(activeTab);
  const isComingSoonTab = Boolean(activeDef?.comingSoon);

  // Held here so paging these server-filtered tables preserves the active severity filter.
  const [postureSeverity, setPostureSeverity] = useState('');
  const [cveSeverity, setCveSeverity] = useState('');
  const [cveView, setCveView] = useState('');

  const {
    summary, issues, attackSurface, threats, vulnDashboard, vulnByCve, posture, inventory,
    inventoryCategories, inventoryRisk, networkTopology, sensorCoverage, compliance, configDashboard, onboarding, apiSecurity, components,
    threatPolicies, workflows, stories, threatOverview, loadThreatOverview, identities,
    vulnGroups, loadVulnGroups, threatGroups, loadThreatGroups,
    codeScans, loadCodeScanFindings, loadFrameworkControls, loadAssetRelationships,
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
  // Checked before every other state. A tab that is not built yet has nothing to say about accounts,
  // entitlements or connections, so none of the prompts below apply to it.
  if (isComingSoonTab && activeDef) {
    return (
      <main className="flex-1 flex flex-col min-h-screen bg-white">
        <XamSecureHeader isLoading={false} onRefresh={refresh} group={activeGroup} />
        <div className="p-6">
          <ComingSoonTab title={activeDef.label} blurb={activeDef.comingSoonBlurb} />
        </div>
      </main>
    );
  }

  // Scoped to CNAPP. DSPM assets carry a Matters integration id rather than a cloud account number, so
  // there is nothing for the picker to filter on — and applying this guard to them would leave a
  // DSPM-only customer with no cloud accounts staring at "Add a cloud account" forever, for a product
  // that does not require one.
  if (noAccountSelected && !isDspmTab) {
    const stillLoadingAccounts = accountsLoading && accounts.length === 0;
    return (
      <main className="flex-1 flex flex-col min-h-screen bg-white">
        <XamSecureHeader isLoading={stillLoadingAccounts} onRefresh={refresh} group={activeGroup} />
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
      <XamSecureHeader isLoading={isLoading} onRefresh={refresh} group={activeGroup} />
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
                          onTrendRange={(d) => { void loadThreatOverview(true, d); }}
                          groups={threatGroups}
                          onGroupBy={(d) => { void loadThreatGroups(d); }} />
        )}
        {activeTab === 'vulnerabilities' && (
          <VulnerabilitiesTab dashboard={vulnDashboard} byCve={vulnByCve} isLoading={isLoading}
                              onCvePage={(p) => loadVulnByCve(p, cveSeverity, cveView)}
                              loadCveFindings={loadCveFindings}
                              loadResourceDetail={loadResourceDetail}
                              onCveSeverity={(s) => { setCveSeverity(s); loadVulnByCve(0, s, cveView); }}
                              onCveView={(v) => { setCveView(v); setCveSeverity(''); loadVulnByCve(0, '', v); }}
                              groups={vulnGroups}
                              onGroupBy={(d, p) => { void loadVulnGroups(d, p ?? 0); }} />
        )}
        {activeTab === 'posture' && (
          <ConfigurationsTab dashboard={configDashboard} posture={posture} compliance={compliance}
                             isLoading={isLoading} onPage={(p) => loadPosture(p, postureSeverity)}
                             loadResourceDetail={loadResourceDetail}
                             onSeverity={(s) => { setPostureSeverity(s); loadPosture(0, s); }}
                             issues={issues}
                             loadControls={loadFrameworkControls} />
        )}
        {activeTab === 'inventory' && (
          <InventoryTab data={inventory} categories={inventoryCategories} risk={inventoryRisk}
                        network={networkTopology}
                        isLoading={isLoading} onLoad={(page, category) => loadInventory(page, category)}
                        loadResourceDetail={loadResourceDetail}
                        loadRelationships={loadAssetRelationships} />
        )}
        {activeTab === 'api-security' && <ApiSecurityTab data={apiSecurity} isLoading={isLoading} />}
        {activeTab === 'identities' && (
          <IdentitiesTab data={identities} isLoading={isLoading}
                         loadResourceDetail={loadResourceDetail} />
        )}
        {activeTab === 'components' && <ComponentsTab data={components} isLoading={isLoading} />}
        {activeTab === 'code' && (
          <CodeTab data={codeScans} isLoading={isLoading} loadFindings={loadCodeScanFindings} />
        )}
        {activeTab === 'workflows' && <WorkflowsTab data={workflows} isLoading={isLoading} />}
        {activeTab === 'onboarding' && (
          <OnboardingTab accounts={onboarding} isLoading={isLoading} onRefresh={refresh}
                         coverage={sensorCoverage} />
        )}

        {/* ── DSPM (Matters.ai) ── */}
        {isDspmTab && dspmLoading && (
          <p className="py-16 text-center text-sm text-gray-400">Loading data security…</p>
        )}
        {/* Keyed by surface so filters, paging and search reset when switching tabs: a provider=S3
            filter means nothing on Endpoint. */}
        {dspmSurface && !dspmLoading && dspmStatus && (
          <DspmAssetsTab key={dspmSurface} surface={dspmSurface} status={dspmStatus}
                         onRetry={reloadStatus} />
        )}
        {activeTab === 'dspm-status' && !dspmLoading && dspmStatus && (
          <DspmStatusTab status={dspmStatus} onRetry={reloadStatus} />
        )}
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
