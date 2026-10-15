import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { motion } from 'framer-motion';
import { Icon } from '@iconify/react';
import { useSearchParams } from 'react-router-dom';
import { complianceControlMatchesResource } from '../components/Cost/resourceSecurityDeepLink';
import { useAccounts } from '../contexts/AccountContext';
import { useTopbar } from '../contexts/TopbarContext';
import { useComplianceOps, type CloudProvider } from '../hooks/useComplianceOps';
import type { ComplianceControl } from '../types/compliance';
import ComplianceScoreHeader from '../components/Compliance/ComplianceScoreHeader';
import ComplianceFrameworkStrip from '../components/Compliance/ComplianceFrameworkStrip';
import ComplianceTrendCard from '../components/Compliance/ComplianceTrendCard';
import ComplianceSeverityCard from '../components/Compliance/ComplianceSeverityCard';
import ComplianceProgramCard from '../components/Compliance/ComplianceProgramCard';
import ComplianceFilterBar from '../components/Compliance/ComplianceFilterBar';
import ComplianceControlsTable from '../components/Compliance/ComplianceControlsTable';
import ComplianceControlDrawer, {
  type ExceptionDraft,
  type OwnerDraft,
} from '../components/Compliance/ComplianceControlDrawer';
import {
  ComplianceEmptyState,
  ComplianceErrorState,
  ComplianceScanFailed,
  ComplianceSkeleton,
} from '../components/Compliance/ComplianceStates';
import { BTN_PRIMARY, BTN_SECONDARY, frameworkName } from '../components/Compliance/complianceTokens';

/**
 * ComplianceOps.
 *
 * This page reads a persisted compliance record rather than re-deriving one per request
 * from a scanner JSON blob. Four consequences of that are visible here:
 *
 *  - The trend is real. It plots stored scores from actual scans, and says so when there
 *    are too few to compare. The version it replaces generated a synthetic improving
 *    curve from today's counts.
 *  - Controls are keyed by scanner check id, so a control stays the same row when the
 *    scanner rewords a message.
 *  - Passing controls are shown, with real counts. The data was always fetched and then
 *    discarded.
 *  - Ownership, exceptions and remediation exist, because a compliance record that cannot
 *    hold a human decision is just a list of findings.
 *
 * Composition only — aggregation is server-side, presentation is in components/Compliance.
 */
const ComplianceOpsPage: React.FC = () => {
  const { selectedAccountIds, selectedAccountDisplay } = useAccounts();
  const selectedAccountId = selectedAccountIds.length > 0 ? selectedAccountIds[0] : null;
  const provider = (selectedAccountDisplay?.provider ?? 'AWS') as CloudProvider;

  const {
    report,
    controls,
    filteredControls,
    services,
    frameworkOptions,
    scanStatus,
    isLoading,
    isRefreshing,
    error,
    filters,
    setFilter,
    resetFilters,
    loadReport,
    triggerScan,
  } = useComplianceOps({ accountId: selectedAccountId, provider });

  /**
   * Optional deep-link focus from the Cost drawer's Security & Alerts tab: `?resource=<uid>`.
   *
   * <p>Additive and read-only — absent, the page behaves exactly as before. The param is the exact
   * `ResourceRef.uid` the aggregator matched on, so this re-matches byte-for-byte.
   */
  const [params] = useSearchParams();
  const resource = params.get('resource');
  const focusedControls = useMemo(
    () => filteredControls.filter(
      (c) => complianceControlMatchesResource(c.resources.map((r) => r.uid), resource),
    ),
    [filteredControls, resource],
  );

  const { setActions, clearActions } = useTopbar();
  const [selected, setSelected] = useState<ComplianceControl | null>(null);
  const [trendMode, setTrendMode] = useState<'score' | 'severity'>('score');
  const [actionBusy, setActionBusy] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);
  const listRef = useRef<HTMLDivElement>(null);

  useEffect(() => setSelected(null), [selectedAccountId]);

  // Keep the open drawer pointed at fresh data after a scan or a governance write.
  useEffect(() => {
    if (!selected || !report) return;
    const fresh = report.controls.find((c) => c.checkId === selected.checkId);
    if (fresh && fresh !== selected) setSelected(fresh);
  }, [report, selected]);

  /**
   * Filtering from a summary tile is only useful if the rows come into view. Otherwise
   * the count changes somewhere below the fold and the click looks like it did nothing —
   * exactly the bug the WAR attack-path cards had.
   */
  const selectAndReveal = useCallback((apply: () => void) => {
    apply();
    requestAnimationFrame(() => {
      listRef.current?.scrollIntoView({ behavior: 'smooth', block: 'start' });
    });
  }, []);

  const exportCsv = useCallback(() => {
    if (!report) return;
    const esc = (v: unknown) => `"${String(v ?? '').replace(/"/g, '""')}"`;
    const header = [
      'Check ID', 'Control', 'Service', 'Severity', 'Status', 'Failed Resources',
      'Passed Resources', 'Frameworks', 'Framework Controls', 'Owner', 'Owner Email',
      'Exception Expires', 'Failing For Scans', 'Remediation', 'References',
      'Affected Resources',
    ];
    const rows = report.controls.map((c) => [
      esc(c.checkId),
      esc(c.title ?? ''),
      esc(c.service ?? ''),
      esc(c.severity),
      esc(c.status),
      c.failedResources,
      c.passedResources,
      esc(c.frameworks.map((f) => frameworkName(f.framework)).join('; ')),
      esc(c.frameworks.map((f) => `${f.framework}: ${f.controls.join(' ')}`).join(' | ')),
      esc(c.owner?.name ?? ''),
      esc(c.owner?.email ?? ''),
      esc(c.exception?.expiresAt ?? ''),
      c.failingForScans,
      esc(c.remediation ?? ''),
      esc(c.remediationRefs.filter((r) => r.startsWith('http')).join('\n')),
      esc(c.resources
        .map((r) => {
          const label = r.name && r.name !== r.uid ? `${r.name} (${r.uid})` : (r.name ?? r.uid ?? '');
          return [label, r.region ? `[${r.region}]` : '', r.detail ? `- ${r.detail}` : '']
            .filter(Boolean).join(' ');
        })
        .join('\n')),
    ].join(','));

    const csv = [header.map(esc).join(','), ...rows].join('\n');
    const blob = new Blob(['\uFEFF' + csv], { type: 'text/csv;charset=utf-8;' });
    const link = document.createElement('a');
    link.href = URL.createObjectURL(blob);
    const stamp = (report.scannedAt ?? '').slice(0, 10) || 'unknown-date';
    link.download = `compliance-${report.accountId}-${stamp}.csv`;
    link.click();
    URL.revokeObjectURL(link.href);
  }, [report]);

  // ── Governance actions ────────────────────────────────────────────────────

  const runAction = useCallback(async (fn: () => Promise<Response>) => {
    setActionBusy(true);
    setActionError(null);
    try {
      const res = await fn();
      if (!res.ok) {
        const body = await res.json().catch(() => ({}));
        throw new Error(body.error ?? `Request failed (HTTP ${res.status})`);
      }
      await loadReport();
      return true;
    } catch (err) {
      setActionError(err instanceof Error ? err.message : 'Something went wrong.');
      return false;
    } finally {
      setActionBusy(false);
    }
  }, [loadReport]);

  const saveOwner = useCallback(async (checkId: string, draft: OwnerDraft) => {
    if (!selectedAccountId) return;
    await runAction(() => fetch('/api/xamops/compliance/owners', {
      method: 'POST',
      credentials: 'include',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ accountId: selectedAccountId, checkId, ...draft }),
    }));
  }, [selectedAccountId, runAction]);

  const requestException = useCallback(async (checkId: string, draft: ExceptionDraft) => {
    if (!selectedAccountId) return;
    // The date input gives a plain day; the API wants an instant, so pin it to end of day.
    const expiresAt = new Date(`${draft.expiresAt}T23:59:59Z`).toISOString();
    const ok = await runAction(() => fetch('/api/xamops/compliance/exceptions', {
      method: 'POST',
      credentials: 'include',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        accountId: selectedAccountId,
        checkId,
        justification: draft.justification,
        compensatingControl: draft.compensatingControl,
        expiresAt,
      }),
    }));
    if (ok) setActionError(null);
  }, [selectedAccountId, runAction]);

  const createTask = useCallback(async (checkId: string) => {
    if (!selectedAccountId) return;
    await runAction(() => fetch('/api/xamops/compliance/remediations', {
      method: 'POST',
      credentials: 'include',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ accountId: selectedAccountId, checkId }),
    }));
  }, [selectedAccountId, runAction]);

  // ── Topbar ────────────────────────────────────────────────────────────────

  const scanning = isRefreshing || scanStatus.status === 'RUNNING';

  useEffect(() => {
    setActions(
      <div className="flex items-center gap-2">
        {scanStatus.status === 'RUNNING' && (
          <span className="flex items-center gap-1.5 px-2.5 h-8 rounded-lg bg-[rgba(0,0,0,0.04)] text-[11.5px] font-medium text-[rgba(0,0,0,0.6)]">
            <span className="w-1.5 h-1.5 rounded-full bg-[rgba(0,0,0,0.55)] animate-pulse" />
            Scan running
          </span>
        )}
        {report?.scannedAt && scanStatus.status !== 'RUNNING' && (
          <span className="hidden lg:flex items-center gap-1.5 px-2.5 h-8 rounded-lg text-[11.5px] font-medium text-[rgba(0,0,0,0.4)]">
            <Icon icon="mdi:clock-outline" width={13} />
            {new Date(report.scannedAt).toLocaleDateString()}
          </span>
        )}
        <button
          type="button"
          onClick={exportCsv}
          disabled={!report || controls.length === 0}
          className={BTN_SECONDARY}
        >
          <Icon icon="mdi:file-delimited-outline" width={14} />
          Export
        </button>
        <button type="button" onClick={triggerScan} disabled={scanning} className={BTN_PRIMARY}>
          <Icon icon="mdi:radar" width={14} className={scanning ? 'animate-spin' : ''} />
          {scanning ? 'Scanning…' : 'Run scan'}
        </button>
      </div>,
    );
    return () => clearActions();
  }, [setActions, clearActions, report, controls.length, exportCsv, triggerScan,
    scanning, scanStatus.status]);

  // ── States ────────────────────────────────────────────────────────────────

  const shell = (children: React.ReactNode) => (
    <main className="flex-1 px-6 pt-4 pb-28 bg-[#fafafa] min-h-screen">
      <motion.div
        initial={{ opacity: 0, y: 12 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.2, ease: 'easeInOut' }}
        className="space-y-3"
      >
        {children}
      </motion.div>
    </main>
  );

  if (!selectedAccountId) {
    return shell(
      <ComplianceEmptyState
        icon="mdi:cloud-outline"
        title="No account selected"
        body="Pick a cloud account from the sidebar to see its compliance record."
      />,
    );
  }

  if (isLoading) return shell(<ComplianceSkeleton />);

  if (error && !report) {
    return shell(<ComplianceErrorState message={error} onRetry={() => loadReport()} />);
  }

  if (!report || report.scanStatus === 'NONE') {
    return shell(
      <ComplianceEmptyState
        icon="mdi:shield-search"
        title="No compliance scan on record"
        body={
          scanning
            ? 'A scan is running now. Results appear here as soon as it finishes — this can '
              + 'take up to an hour on a large account.'
            : 'Run a scan to build the compliance record. Each scan is stored, so scores '
              + 'and trends accumulate from the first run onwards.'
        }
        action={{
          label: scanning ? 'Scan running…' : 'Run the first scan',
          icon: scanning ? 'mdi:radar' : 'mdi:play-outline',
          onClick: triggerScan,
          disabled: scanning,
        }}
      />,
    );
  }

  if (report.scanStatus === 'FAILED') {
    return shell(
      <ComplianceScanFailed
        error={report.scanError}
        scannedAt={report.scannedAt}
        onRetry={triggerScan}
        retrying={scanning}
      />,
    );
  }

  // ── Main ──────────────────────────────────────────────────────────────────

  return (
    <>
      {shell(
        <>
          {scanStatus.status === 'RUNNING' && (
            <div className="flex items-center gap-2.5 px-4 py-2.5 rounded-xl bg-white border border-[#e5e7eb]">
              <Icon icon="mdi:radar" width={16} className="text-[rgba(0,0,0,0.5)] animate-spin shrink-0" />
              <p className="text-[12.5px] text-[rgba(0,0,0,0.6)]">
                A scan is running. The figures below are from the last completed scan and
                will refresh automatically when this one finishes.
              </p>
            </div>
          )}

          {error && (
            <div className="flex items-center gap-2.5 px-4 py-2.5 rounded-xl bg-red-50 border border-red-200">
              <Icon icon="mdi:alert-circle-outline" width={16} className="text-red-500 shrink-0" />
              <p className="text-[12.5px] text-red-700">{error}</p>
            </div>
          )}

          <ComplianceScoreHeader
            report={report}
            onSelectStatus={(status) => selectAndReveal(() => setFilter('status', status))}
          />

          <ComplianceFrameworkStrip
            frameworks={report.frameworks}
            selected={filters.framework}
            onSelect={(key) => selectAndReveal(() => setFilter('framework', key))}
          />

          <div className="grid grid-cols-1 lg:grid-cols-3 gap-3">
            <ComplianceSeverityCard
              counts={report.counts}
              severityFilter={filters.severity}
              onSelectSeverity={(s) => selectAndReveal(() => {
                setFilter('severity', s);
                if (s !== 'All') setFilter('status', 'FAIL');
              })}
            />
            <div className="lg:col-span-2">
              <ComplianceTrendCard
                trend={report.trend}
                mode={trendMode}
                onModeChange={setTrendMode}
              />
            </div>
          </div>

          <ComplianceProgramCard
            program={report.program}
            counts={report.counts}
            onShowUnowned={() => selectAndReveal(() => {
              setFilter('status', 'FAIL');
              setFilter('unownedOnly', true);
            })}
            onShowWaived={() => selectAndReveal(() => setFilter('status', 'WAIVED'))}
          />

          <div ref={listRef} className="space-y-3 scroll-mt-16">
            <ComplianceFilterBar
              filters={filters}
              setFilter={setFilter}
              resetFilters={resetFilters}
              services={services}
              frameworkOptions={frameworkOptions}
              resultCount={focusedControls.length}
              totalCount={controls.length}
            />

            <ComplianceControlsTable
              controls={focusedControls}
              onSelect={setSelected}
              onResetFilters={resetFilters}
            />
          </div>
        </>,
      )}

      <ComplianceControlDrawer
        control={selected}
        onClose={() => { setSelected(null); setActionError(null); }}
        onSaveOwner={saveOwner}
        onRequestException={requestException}
        onCreateTask={createTask}
        busy={actionBusy}
        actionError={actionError}
      />
    </>
  );
};

export default ComplianceOpsPage;
