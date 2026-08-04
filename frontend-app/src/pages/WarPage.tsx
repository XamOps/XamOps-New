import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Icon } from '@iconify/react';
import { AnimatePresence, motion } from 'framer-motion';
import { useAccounts } from '../contexts/AccountContext';
import { useTopbar } from '../contexts/TopbarContext';
import { useWarData } from '../hooks/useWarData';
import {
  WarAttackPathBoard,
  WarBlockedBanner,
  WarCategoryStrip,
  WarErrorState,
  WarFilterBar,
  WarFindingsTable,
  WarLoadingState,
  WarNoAccountState,
  WarProviderNotReadyState,
  WarScoreHeader,
  WarTopRisks,
} from '../components/War';
import { CARD } from '../components/War/warTokens';

/** Standard content-swap transition used across the app. */
const SWAP = { duration: 0.2, ease: 'easeInOut' } as const;

/**
 * WAR — Workload Attack-surface Review.
 *
 * Answers one question about the selected cloud account: what in this infrastructure
 * would let an attacker or a crypto-miner get in, and what stops the bill from growing
 * once they do?
 *
 * Structured answer-first. The score and risk verdict lead, then the attack paths that
 * produced that verdict, then the ordered fix list, and only then the full 86-control
 * table. Someone who reads the top third should already know whether to page the
 * on-call.
 */
const WarPage: React.FC = () => {
  const { selectedAccountIds, selectedAccountDisplay } = useAccounts();
  const selectedAccountId = selectedAccountIds.length > 0 ? selectedAccountIds[0] : null;
  const provider = selectedAccountDisplay?.provider ?? 'AWS';

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
  const findingsRef = useRef<HTMLDivElement>(null);

  /**
   * Selecting a card scrolls to the results it produced.
   *
   * The findings table sits below the score, fix list and family strip, so filtering it
   * from a card at the top of the page changed nothing in the viewport — the interaction
   * looked broken even though the filter had applied.
   */
  const selectAndReveal = useCallback(
    (key: 'attackPath' | 'category', value: string) => {
      setFilter(key, value);
      if (value !== 'All') {
        // Defer past the filter re-render so the list has its new height.
        requestAnimationFrame(() => {
          findingsRef.current?.scrollIntoView({ behavior: 'smooth', block: 'start' });
        });
      }
    },
    [setFilter],
  );

  // Title lives in the topbar, matching every other page in the app.
  useEffect(() => {
    setActions(
      <div className="flex items-center gap-3">
        <div className="hidden lg:block min-w-0 pr-3 border-r border-[rgba(0,0,0,0.12)]">
          <h1 className="text-[13px] font-semibold text-[rgba(0,0,0,0.88)] leading-none">
            WAR
          </h1>
          <p className="text-[11px] text-[rgba(0,0,0,0.35)] mt-0.5 whitespace-nowrap">
            Workload Attack-surface Review
          </p>
        </div>

        {report && (
          <span className="hidden xl:flex items-center gap-1.5 text-[11px] text-[rgba(0,0,0,0.35)]">
            <Icon icon="mdi:clock-outline" width={12} />
            {report.generatedAt}
          </span>
        )}

        <button
          onClick={exportCsv}
          disabled={!report}
          className="flex items-center gap-1.5 px-2.5 py-1.5 rounded-md text-[12px] font-medium border bg-white text-[rgba(0,0,0,0.5)] border-[rgba(0,0,0,0.12)] hover:border-[rgba(0,0,0,0.18)] hover:text-[rgba(0,0,0,0.88)] transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
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
  }, [report, isRefreshing, isLoading, exportCsv, loadReport, setActions, clearActions]);

  return (
    <main className="flex-1 px-6 pt-4 pb-28 bg-[#fafafa] min-h-screen">
      <div className="space-y-3">
        {!selectedAccountId ? (
          <WarNoAccountState />
        ) : provider !== 'AWS' ? (
          <WarProviderNotReadyState provider={provider} />
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
                <WarLoadingState isRefreshing={isRefreshing} />
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
                  <div className="flex items-center gap-3 px-4 py-3 rounded-xl border bg-amber-50 text-amber-700 border-amber-200">
                    <span className="w-2 h-2 rounded-full bg-amber-500 animate-pulse shrink-0" />
                    <p className="text-[11.5px] leading-relaxed">
                      Showing the last completed scan. The refresh did not finish: {error}
                    </p>
                  </div>
                )}

                <WarScoreHeader report={report} />

                <WarBlockedBanner report={report} onRetry={() => loadReport(true)} />

                <WarAttackPathBoard
                  paths={report.attackPaths}
                  activePath={filters.attackPath}
                  onSelect={(path) => selectAndReveal('attackPath', path)}
                />

                <WarTopRisks
                  risks={report.topRisks}
                  deniedActions={report.deniedActions}
                  blockedActions={report.blockedActions}
                  unverifiedCount={report.statusCounts.UNKNOWN ?? 0}
                />

                <WarCategoryStrip
                  categories={report.categories}
                  activeCategory={filters.category}
                  onSelect={(category) => selectAndReveal('category', category)}
                />

                <ScanWarnings warnings={report.scanWarnings} />

                <div ref={findingsRef} className="scroll-mt-16 space-y-2">
                  <WarFilterBar
                    report={report}
                    filters={filters}
                    resultCount={filteredFindings.length}
                    onChange={setFilter}
                    onReset={resetFilters}
                  />

                  <WarFindingsTable findings={filteredFindings} onResetFilters={resetFilters} />
                </div>
              </motion.div>
            ) : null}
          </AnimatePresence>
        )}
      </div>
    </main>
  );
};

/**
 * Per-region check failures, collapsed by default.
 *
 * A controlled expander rather than <details>, so it carries aria-expanded and matches
 * the disclosure pattern used elsewhere.
 */
const ScanWarnings: React.FC<{ warnings: string[] }> = ({ warnings }) => {
  const [isOpen, setIsOpen] = useState(false);

  if (warnings.length === 0) return null;

  return (
    <div className={CARD}>
      <button
        onClick={() => setIsOpen((v) => !v)}
        aria-expanded={isOpen}
        className="w-full flex items-center gap-2 px-4 py-2.5 text-left hover:bg-[#fafafa] transition-colors rounded-xl"
      >
        <Icon icon="mdi:information-outline" width={13} className="text-[rgba(0,0,0,0.35)]" />
        <span className="text-[12px] font-medium text-[rgba(0,0,0,0.6)]">
          {warnings.length} region or service check
          {warnings.length === 1 ? '' : 's'} did not complete
        </span>
        <Icon
          icon={isOpen ? 'mdi:chevron-up' : 'mdi:chevron-down'}
          width={15}
          className="text-[rgba(0,0,0,0.25)] ml-auto"
        />
      </button>

      {isOpen && (
        <ul className="px-4 pb-3 pt-1 space-y-1 border-t border-[#e5e7eb] max-h-64 overflow-y-auto">
          {warnings.map((warning, i) => (
            <li key={i} className="text-[10.5px] font-mono text-[rgba(0,0,0,0.45)] leading-relaxed">
              {warning}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
};

export default WarPage;
