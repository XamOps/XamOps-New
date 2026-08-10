import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { Icon } from '@iconify/react';
import { AnimatePresence, motion } from 'framer-motion';
import { useAccounts } from '../contexts/AccountContext';
import { useTopbar } from '../contexts/TopbarContext';
import { useTabState } from '../hooks/useTabState';
import { useWarData } from '../hooks/useWarData';
import { AriaTabGroup } from '../components/ui';
import type { TabItem } from '../components/ui';
import {
  WarAttackPathBoard,
  WarBlockedBanner,
  WarCategoryStrip,
  WarErrorState,
  WarFilterBar,
  WarFindingsTable,
  WarLoadingState,
  WarNoAccountState,
  WarProviderMismatchState,
  WarScanHealth,
  WarScoreHeader,
  WarTopRisks,
} from '../components/War';

/** Standard content-swap transition used across the app. */
const SWAP = { duration: 0.2, ease: 'easeInOut' } as const;

type WarTab = 'priority' | 'paths' | 'families' | 'controls' | 'scan';

interface WarPageProps {
  /**
   * Which cloud this route serves. The report shape is provider-neutral, so the whole
   * page is shared; only the expected provider and the baseline size differ.
   */
  provider: 'AWS' | 'GCP' | 'Azure';
  /** Controls in this provider's baseline, shown while a first scan is running. */
  baselineSize: number;
}

/**
 * WAR — Workload Attack-surface Review.
 *
 * Answers one question about the selected cloud account: what in this infrastructure
 * would let an attacker or a crypto-miner get in, and what stops the bill from growing
 * once they do?
 *
 * <h2>Why this is tabbed</h2>
 * The verdict and the account context stay pinned at the top, and the five supporting
 * views became tabs. Previously all of them stacked vertically, which put the 86-control
 * table roughly three scroll screens below the fold — so the filter bar and the rows it
 * filtered were more than a viewport apart, and every filter iteration cost a scroll
 * round-trip. The old {@code requestAnimationFrame} scroll-into-view hack existed only to
 * paper over that gap; selecting a card now switches tabs instead, which is what the
 * interaction always meant.
 *
 * The split is by question, not by component:
 *   priority  what do I fix first
 *   paths     how would someone get in and monetise it
 *   families  who owns this, for the auditor and the remediation owner
 *   controls  the full baseline, searchable
 *   scan      how much of the account could we actually read
 *
 * Tab state lives in {@code ?tab=} via useTabState, so a view is linkable and browser
 * back moves between tabs rather than leaving the page.
 */
const WarPage: React.FC<WarPageProps> = ({ provider, baselineSize }) => {
  const { selectedAccountIds, selectedAccountDisplay } = useAccounts();
  const selectedAccountId = selectedAccountIds.length > 0 ? selectedAccountIds[0] : null;
  // The route fixes which provider this page serves; the selector decides whether the
  // chosen account actually matches it.
  const selectedProvider = selectedAccountDisplay?.provider ?? 'AWS';

  const {
    report,
    filteredFindings,
    isLoading,
    isRefreshing,
    error,
    filters,
    setFilter,
    resetFilters,
    loadReport,
    exportCsv,
  } = useWarData({ selectedAccountId });

  const { setActions, clearActions } = useTopbar();
  const [activeTab, setActiveTab] = useTabState<WarTab>('priority');

  /** Control to scroll to and flash once the controls tab has rendered. */
  const [focusControlId, setFocusControlId] = useState<string | null>(null);

  /**
   * Selecting a card filters the control list and shows it.
   *
   * The filter and the list it drives now live in different tabs, so the switch *is* the
   * feedback. Clicking an already-active card clears the filter and stays put, which is
   * what the aria-pressed state promises.
   */
  const selectAndReveal = useCallback(
    (key: 'attackPath' | 'category', value: string) => {
      setFilter(key, value);
      if (value !== 'All') {
        setActiveTab('controls');
      }
    },
    [setFilter, setActiveTab],
  );

  /** Jumping from the fix list to the row it came from. */
  const openControl = useCallback(
    (controlId: string) => {
      // The default OPEN status filter already includes every confirmed exposure, but a
      // stale attack-path or family filter could hide the row we are pointing at.
      resetFilters();
      setFocusControlId(controlId);
      setActiveTab('controls');
    },
    [resetFilters, setActiveTab],
  );

  // Title lives in the topbar, matching every other page in the app.
  useEffect(() => {
    setActions(
      <div className="flex items-center gap-3">
        {/* The Topbar already renders the page title from the route, so this block
            carries only the subtitle and the actions. */}
        <span className="hidden lg:block text-[11px] text-[rgba(0,0,0,0.55)] whitespace-nowrap pr-3 border-r border-[rgba(0,0,0,0.12)]">
          Workload Attack-surface Review
        </span>

        {report && (
          <span className="hidden xl:flex items-center gap-1.5 text-[11px] text-[rgba(0,0,0,0.55)]">
            <Icon icon="mdi:clock-outline" width={12} />
            {report.generatedAt}
          </span>
        )}

        <button
          onClick={exportCsv}
          disabled={!report}
          className="flex items-center gap-1.5 px-2.5 py-1.5 rounded-md text-[12px] font-medium border bg-white text-[rgba(0,0,0,0.6)] border-[rgba(0,0,0,0.12)] hover:border-[rgba(0,0,0,0.18)] hover:text-[rgba(0,0,0,0.88)] transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
        >
          <Icon icon="mdi:download-outline" width={12} />
          <span className="hidden lg:inline">Export CSV</span>
        </button>

        <button
          onClick={() => loadReport(true)}
          disabled={isRefreshing || isLoading}
          className="flex items-center gap-1.5 px-3 py-1.5 bg-[rgba(0,0,0,0.88)] text-white rounded-md text-[12px] font-medium hover:bg-black transition-colors disabled:opacity-50"
        >
          <Icon
            icon={isRefreshing ? 'mdi:loading' : 'mdi:radar'}
            width={12}
            className={isRefreshing ? 'animate-spin' : ''}
          />
          <span>{isRefreshing ? 'Scanning…' : 'Re-scan'}</span>
        </button>
      </div>,
    );
    return () => clearActions();
  }, [report, provider, isRefreshing, isLoading, exportCsv, loadReport,
      setActions, clearActions]);

  /**
   * Counts on the tabs so the reader can triage before clicking.
   *
   * Only shown where the number means "needs your attention" — a count on every tab is
   * decoration that trains people to ignore all of them.
   */
  const tabs = useMemo<TabItem[]>(() => {
    if (!report) return [];

    const openPaths = report.attackPaths.filter((p) => p.failed + p.warned > 0).length;
    const openFamilies = report.categories.filter((c) => c.failed + c.warned > 0).length;
    const scanIssues = report.scanWarnings.length
      + report.deniedActions.length
      + report.blockedActions.length;

    return [
      {
        id: 'priority',
        label: <TabLabel text="Fix first" count={report.topRisks.length} tone="alert" />,
        icon: 'mdi:target',
      },
      {
        id: 'paths',
        label: <TabLabel text="Attack paths" count={openPaths} tone="alert" />,
        icon: 'mdi:sitemap-outline',
      },
      {
        id: 'families',
        label: <TabLabel text="Control families" count={openFamilies} tone="alert" />,
        icon: 'mdi:shield-outline',
      },
      {
        id: 'controls',
        label: <TabLabel text="All controls" count={report.totalControls} tone="neutral" />,
        icon: 'mdi:format-list-bulleted',
      },
      {
        id: 'scan',
        label: <TabLabel text="Scan health" count={scanIssues} tone="warn" />,
        icon: 'mdi:radar',
      },
    ];
  }, [report]);

  return (
    <main className="flex-1 px-6 pt-4 pb-28 bg-[#fafafa] min-h-screen">
      <div className="space-y-3">
        {!selectedAccountId ? (
          <WarNoAccountState />
        ) : selectedProvider !== provider ? (
          <WarProviderMismatchState expected={provider} actual={selectedProvider} />
        ) : (
          <AnimatePresence mode="wait">
            {isLoading && !report ? (
              <motion.div
                key="loading"
                initial={{ opacity: 0, y: 12 }}
                animate={{ opacity: 1, y: 0 }}
                exit={{ opacity: 0, y: -12 }}
                transition={SWAP}
              >
                <WarLoadingState isRefreshing={isRefreshing} baselineSize={baselineSize} />
              </motion.div>
            ) : error && !report ? (
              <motion.div
                key="error"
                initial={{ opacity: 0, y: 12 }}
                animate={{ opacity: 1, y: 0 }}
                exit={{ opacity: 0, y: -12 }}
                transition={SWAP}
              >
                <WarErrorState message={error} onRetry={() => loadReport(true)} />
              </motion.div>
            ) : report ? (
              <motion.div
                key="report"
                initial={{ opacity: 0, y: 12 }}
                animate={{ opacity: 1, y: 0 }}
                exit={{ opacity: 0, y: -12 }}
                transition={SWAP}
                className="space-y-3"
              >
                {/* A stale-data notice matters more than the failed refresh itself. */}
                {error && (
                  <div className="flex items-center gap-3 px-4 py-3 rounded-xl border bg-amber-50 text-amber-800 border-amber-200">
                    <span className="w-2 h-2 rounded-full bg-amber-600 animate-pulse shrink-0" />
                    <p className="text-[11.5px] leading-relaxed">
                      Showing the last completed scan. The refresh did not finish: {error}
                    </p>
                  </div>
                )}

                {/* Pinned above the tabs: the verdict and why to trust it. Putting these
                    behind a tab would let someone read a findings list without the
                    coverage caveat that qualifies it. */}
                <WarScoreHeader report={report} />
                <WarBlockedBanner report={report} onRetry={() => loadReport(true)} />

                <div className="bg-white border border-[#e5e7eb] rounded-xl px-2 pt-1">
                  <AriaTabGroup
                    variant="neutral"
                    tabs={tabs}
                    selectedKey={activeTab}
                    onSelectionChange={(key) => setActiveTab(key as WarTab)}
                  />
                </div>

                <AnimatePresence mode="wait">
                  <motion.div
                    key={activeTab}
                    initial={{ opacity: 0, y: 8 }}
                    animate={{ opacity: 1, y: 0 }}
                    exit={{ opacity: 0, y: -4 }}
                    transition={{ duration: 0.18, ease: 'easeOut' }}
                    role="tabpanel"
                    aria-label={activeTab}
                  >
                    {activeTab === 'priority' && (
                      <WarTopRisks
                        risks={report.topRisks}
                        unverifiedCount={report.statusCounts.UNKNOWN ?? 0}
                        onSelectFinding={openControl}
                      />
                    )}

                    {activeTab === 'paths' && (
                      <WarAttackPathBoard
                        detailed
                        paths={report.attackPaths}
                        activePath={filters.attackPath}
                        onSelect={(path) => selectAndReveal('attackPath', path)}
                      />
                    )}

                    {activeTab === 'families' && (
                      <WarCategoryStrip
                        categories={report.categories}
                        activeCategory={filters.category}
                        onSelect={(category) => selectAndReveal('category', category)}
                      />
                    )}

                    {activeTab === 'controls' && (
                      <div className="space-y-2">
                        <WarFilterBar
                          report={report}
                          filters={filters}
                          resultCount={filteredFindings.length}
                          onChange={setFilter}
                          onReset={resetFilters}
                        />
                        <WarFindingsTable
                          findings={filteredFindings}
                          onResetFilters={resetFilters}
                          focusControlId={focusControlId}
                          onFocusHandled={() => setFocusControlId(null)}
                        />
                      </div>
                    )}

                    {activeTab === 'scan' && (
                      <WarScanHealth
                        report={report}
                        onRescan={() => loadReport(true)}
                        isRefreshing={isRefreshing}
                      />
                    )}
                  </motion.div>
                </AnimatePresence>
              </motion.div>
            ) : null}
          </AnimatePresence>
        )}
      </div>
    </main>
  );
};

/**
 * Tab label with an optional count.
 *
 * `alert` is for open exposure, `warn` for our own scan gaps, `neutral` for a plain total.
 * Zero renders as a checkmark rather than a "0" badge, because a grey zero still reads as
 * something to go and look at.
 */
const TabLabel: React.FC<{
  text: string;
  count: number;
  tone: 'alert' | 'warn' | 'neutral';
}> = ({ text, count, tone }) => (
  <span className="flex items-center gap-1.5">
    {text}
    {count === 0 && tone !== 'neutral' ? (
      <Icon icon="mdi:check" width={12} className="text-emerald-700" />
    ) : (
      <span
        className={`px-1.5 py-0.5 rounded-full text-[10px] font-bold tabular-nums ${
          tone === 'alert'
            ? 'bg-red-600 text-white'
            : tone === 'warn'
              ? 'bg-amber-700 text-white'
              : 'bg-[rgba(0,0,0,0.06)] text-[rgba(0,0,0,0.6)]'
        }`}
      >
        {count}
      </span>
    )}
  </span>
);

export default WarPage;
