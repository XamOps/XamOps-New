import React, { useEffect, useMemo, useState } from 'react';
import { Icon } from '@iconify/react';
import { useMutation, useQuery } from '@tanstack/react-query';
import {
  downloadRunExport,
  fetchBreakdown,
  fetchRunHistory,
  lockRun,
  previewRules,
  queueRuns,
  unlockRun,
  type Breakdown,
  type BreakdownRow,
  type EnvironmentTotal,
  type RunView,
} from '../../../api/envAllocationApi';
import type { CostBasis } from '../../../api/curInsightsApi';
import { queryClient } from '../../../lib/queryClient';
import { useMoneyIn } from '../costCurrency';
import { shortenId } from '../../../utils/shortenId';
import EnvAllocationRulesDrawer, { ENV_ALLOCATION_KEY } from './EnvAllocationRulesDrawer';
import {
  KIND_LABELS,
  currentMonth,
  describeAllocation,
  describeMatch,
  monthLabel,
  monthOptions,
  trimPercent,
  when,
} from './envAllocationText';

/**
 * The bill split by environment — Prod, UAT, R&D, or whatever the client calls theirs.
 *
 * <p>A tab of the Cost page's billing card rather than a page of its own, because it reads the same
 * report as "Charges by service" and only cuts it differently.
 *
 * <p>A closed month shows its stored run: the official one if a run is locked, otherwise the
 * newest. Stored runs are what the audit export is built from, so every figure here can be traced to
 * a run id, a rule version and a rule. The open month is calculated live and never stored.
 */

interface EnvironmentAllocationSectionProps {
  accountId: string;
  /** The card's tab control, rendered where this section's title would be. */
  heading: React.ReactNode;
  currency?: string;
}

const buttonCls =
  'inline-flex items-center gap-1.5 px-3 py-1.5 text-[12px] font-medium border border-[rgba(0,0,0,0.15)] rounded-lg bg-white hover:border-[rgba(0,0,0,0.35)] transition-colors disabled:opacity-40 disabled:cursor-not-allowed';
const primaryCls =
  'inline-flex items-center gap-1.5 px-3 py-1.5 text-[12px] font-semibold rounded-lg bg-black text-white hover:bg-black/85 transition-colors disabled:opacity-40 disabled:cursor-not-allowed';
const selectCls =
  'px-3 py-1.5 text-[12.5px] border border-[rgba(0,0,0,0.15)] rounded-lg bg-white focus:outline-none focus:ring-2 focus:ring-black/5';

const errorText = (e: unknown): string => (e instanceof Error ? e.message : String(e));

const isBusy = (r: RunView | null | undefined) => r?.status === 'QUEUED' || r?.status === 'RUNNING';

const EnvironmentAllocationSection: React.FC<EnvironmentAllocationSectionProps> = ({
  accountId,
  heading,
  currency = 'USD',
}) => {
  const money = useMoneyIn(currency);
  const months = useMemo(monthOptions, []);
  const [period, setPeriod] = useState(months[0].value);
  const [basis, setBasis] = useState<CostBasis>('unblended');
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [runPanelOpen, setRunPanelOpen] = useState(false);
  const [historyOpen, setHistoryOpen] = useState(false);
  const [preview, setPreview] = useState<Breakdown | null>(null);
  const [notice, setNotice] = useState<{ tone: 'ok' | 'error'; text: string } | null>(null);
  const isOpenMonth = period === currentMonth();

  // A preview belongs to the month and basis it was made for.
  useEffect(() => setPreview(null), [period, basis, accountId]);

  const breakdown = useQuery({
    queryKey: [ENV_ALLOCATION_KEY, 'breakdown', accountId, period, basis],
    queryFn: () => fetchBreakdown(accountId, period, basis),
    retry: false,
    // While a run is in flight, check back until it lands.
    refetchInterval: (q) => {
      const d = q.state.data;
      return d && (d.status === 'RUNNING' || isBusy(d.pendingRun)) ? 5000 : false;
    },
  });

  const history = useQuery({
    queryKey: [ENV_ALLOCATION_KEY, 'runs', accountId],
    queryFn: () => fetchRunHistory(accountId),
    enabled: historyOpen || runPanelOpen,
    refetchInterval: (q) => (q.state.data?.some(isBusy) ? 5000 : false),
  });

  const refreshAll = () => void queryClient.invalidateQueries({ queryKey: [ENV_ALLOCATION_KEY] });

  const runPreview = useMutation({
    mutationFn: () => previewRules(accountId, period, basis, { useDraft: false }),
    onSuccess: (b) => {
      setPreview(b);
      setNotice(null);
    },
    onError: (e) => setNotice({ tone: 'error', text: errorText(e) }),
  });

  const queue = useMutation({
    mutationFn: (range: { from: string; to: string }) => queueRuns(accountId, range.from, range.to, basis),
    onSuccess: (runs) => {
      setNotice({
        tone: 'ok',
        text: `${runs.length === 1 ? 'One month' : `${runs.length} months`} queued. Results appear here as each finishes.`,
      });
      setPreview(null);
      setRunPanelOpen(false);
      refreshAll();
    },
    onError: (e) => setNotice({ tone: 'error', text: errorText(e) }),
  });

  const lock = useMutation({
    mutationFn: (args: { id: number; lock: boolean }) => (args.lock ? lockRun(args.id) : unlockRun(args.id)),
    onSuccess: () => refreshAll(),
    onError: (e) => setNotice({ tone: 'error', text: errorText(e) }),
  });

  const exportRun = useMutation({
    mutationFn: (args: { id: number; format: 'csv' | 'pdf' }) => downloadRunExport(args.id, args.format),
    onError: (e) => setNotice({ tone: 'error', text: errorText(e) }),
  });

  const shown: Breakdown | undefined = preview ?? breakdown.data;
  const run = shown?.run ?? null;
  const subheading = shown
    ? shown.status === 'RUN'
      ? `${monthLabel(period)} — stored run #${run?.id} with rule version ${shown.versionNumber}${
          run?.locked ? ', locked' : ''
        }`
      : shown.status === 'PREVIEW'
        ? `${monthLabel(period)}${isOpenMonth ? ' so far' : ''} — calculated live${
            shown.versionNumber ? ` with version ${shown.versionNumber}` : ''
          }, not stored`
        : `${monthLabel(period)} — split by environment`
    : `${monthLabel(period)} — split by environment`;

  return (
    <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-6">
      <div className="flex items-start justify-between gap-4 mb-1 flex-wrap">
        <div>
          {heading}
          <p className="text-[12px] text-[rgba(0,0,0,0.45)] mt-1">{subheading}</p>
        </div>
        <div className="flex items-center gap-2 flex-wrap">
          <select className={selectCls} aria-label="Month" value={period} onChange={(e) => setPeriod(e.target.value)}>
            {months.map((m, i) => (
              <option key={m.value} value={m.value}>
                {m.label}
                {i === 0 ? ' (so far)' : ''}
              </option>
            ))}
          </select>
          <select
            className={selectCls}
            aria-label="Cost basis"
            value={basis}
            onChange={(e) => setBasis(e.target.value as CostBasis)}
            title="Unblended matches the invoice. Amortized spreads Savings Plan and RI fees across their term."
          >
            <option value="unblended">Unblended</option>
            <option value="amortized">Amortized</option>
          </select>
          <button type="button" className={buttonCls} onClick={() => setDrawerOpen(true)}>
            <Icon icon="mdi:tune-variant" width={14} />
            Rules &amp; environments
          </button>
          <button type="button" className={buttonCls} onClick={() => setRunPanelOpen((o) => !o)}>
            <Icon icon="mdi:play-circle-outline" width={14} />
            Run months
          </button>
        </div>
      </div>

      {runPanelOpen && (
        <RunMonthsPanel
          months={months.slice(1)}
          basis={basis}
          pending={queue.isPending}
          onRun={(from, to) => queue.mutate({ from, to })}
          onCancel={() => setRunPanelOpen(false)}
        />
      )}

      {notice && (
        <p
          className={`mt-4 text-[12px] px-3 py-2 rounded-lg border ${
            notice.tone === 'ok'
              ? 'text-[#166534] bg-[#F0FDF4] border-[#BBF7D0]'
              : 'text-[#B91C1C] bg-[#FEF2F2] border-[#FECACA]'
          }`}
        >
          {notice.text}
        </p>
      )}

      {breakdown.isPending && !preview && (
        <div className="mt-4 space-y-3">
          <div className="animate-pulse h-8 rounded-lg bg-[rgba(0,0,0,0.04)]" />
          <div className="animate-pulse h-24 rounded-lg bg-[rgba(0,0,0,0.04)]" />
        </div>
      )}

      {breakdown.isError && !preview && (
        <Banner icon="mdi:alert-circle-outline" tone="error">
          {errorText(breakdown.error)}
        </Banner>
      )}

      {shown && (
        <StatusBanner
          breakdown={shown}
          isPreview={preview !== null}
          previewPending={runPreview.isPending}
          runPending={queue.isPending}
          onPreview={() => runPreview.mutate()}
          onRunMonth={() => queue.mutate({ from: period, to: period })}
          onOpenRules={() => setDrawerOpen(true)}
          onClearPreview={() => setPreview(null)}
          isOpenMonth={isOpenMonth}
        />
      )}

      {shown && shown.totalCost !== null && (shown.status === 'RUN' || shown.status === 'PREVIEW') && (
        <>
          <Totals breakdown={shown} money={money} />
          {run && shown.status === 'RUN' && !preview && (
            <div className="mt-4 flex items-center gap-2 flex-wrap">
              <button
                type="button"
                className={buttonCls}
                disabled={exportRun.isPending}
                onClick={() => exportRun.mutate({ id: run.id, format: 'csv' })}
              >
                <Icon icon="mdi:file-delimited-outline" width={14} />
                Export CSV
              </button>
              <button
                type="button"
                className={buttonCls}
                disabled={exportRun.isPending}
                onClick={() => exportRun.mutate({ id: run.id, format: 'pdf' })}
              >
                <Icon icon="mdi:file-pdf-box" width={14} />
                Export PDF
              </button>
              <button
                type="button"
                className={buttonCls}
                disabled={lock.isPending}
                onClick={() => lock.mutate({ id: run.id, lock: !run.locked })}
                title={
                  run.locked
                    ? 'Unlock to allow this month to be run again'
                    : 'Lock to mark this as the official figure; a locked month cannot be re-run'
                }
              >
                <Icon icon={run.locked ? 'mdi:lock-open-variant-outline' : 'mdi:lock-outline'} width={14} />
                {run.locked ? 'Unlock' : 'Lock as official'}
              </button>
              {!run.locked &&
                shown.latestVersion !== null &&
                shown.versionNumber !== null &&
                shown.latestVersion > shown.versionNumber && (
                  <button
                    type="button"
                    className={primaryCls}
                    disabled={queue.isPending}
                    onClick={() => queue.mutate({ from: period, to: period })}
                  >
                    <Icon icon="mdi:refresh" width={14} />
                    Re-run with version {shown.latestVersion}
                  </button>
                )}
              <span className="text-[11px] text-[rgba(0,0,0,0.45)]">
                Run {when(run.finishedAt)}
                {run.startedBy ? ` by ${run.startedBy}` : ''}
                {run.locked && run.lockedBy ? ` · locked by ${run.lockedBy}` : ''}
              </span>
            </div>
          )}
          <BreakdownTable breakdown={shown} money={money} />
          <RulesTaken breakdown={shown} money={money} />
        </>
      )}

      <div className="mt-5 border-t border-[rgba(0,0,0,0.06)] pt-3">
        <button
          type="button"
          className="inline-flex items-center gap-1 text-[12px] font-medium text-[rgba(0,0,0,0.6)] hover:text-black"
          onClick={() => setHistoryOpen((o) => !o)}
        >
          <Icon icon={historyOpen ? 'mdi:chevron-down' : 'mdi:chevron-right'} width={15} />
          Run history
        </button>
        {historyOpen && (
          <RunHistory
            runs={history.data}
            loading={history.isPending}
            money={money}
            busy={lock.isPending || exportRun.isPending}
            onShow={(r) => {
              setBasis(r.basis);
              setPeriod(r.period);
            }}
            onExport={(id, format) => exportRun.mutate({ id, format })}
            onLock={(id, l) => lock.mutate({ id, lock: l })}
          />
        )}
      </div>

      <EnvAllocationRulesDrawer
        open={drawerOpen}
        onClose={() => setDrawerOpen(false)}
        accountId={accountId}
        period={period}
        basis={basis}
        currency={currency}
      />
    </div>
  );
};

// ── Status ───────────────────────────────────────────────────────────────────

const Banner: React.FC<{ icon: string; tone: 'info' | 'warn' | 'error'; children: React.ReactNode }> = ({
  icon,
  tone,
  children,
}) => {
  const tones = {
    info: 'bg-[rgba(0,0,0,0.02)] border-[rgba(0,0,0,0.07)] text-[rgba(0,0,0,0.6)]',
    warn: 'bg-[#FFFBEB] border-[#FDE68A] text-[#92400E]',
    error: 'bg-[#FEF2F2] border-[#FECACA] text-[#B91C1C]',
  };
  return (
    <div className={`mt-4 flex items-start gap-2.5 px-3.5 py-3 border rounded-lg ${tones[tone]}`}>
      <Icon icon={icon} width={15} className="flex-shrink-0 mt-0.5" />
      <div className="text-[11.5px] leading-relaxed flex-1">{children}</div>
    </div>
  );
};

interface StatusBannerProps {
  breakdown: Breakdown;
  isPreview: boolean;
  previewPending: boolean;
  runPending: boolean;
  isOpenMonth: boolean;
  onPreview: () => void;
  onRunMonth: () => void;
  onOpenRules: () => void;
  onClearPreview: () => void;
}

const StatusBanner: React.FC<StatusBannerProps> = ({
  breakdown,
  isPreview,
  previewPending,
  runPending,
  isOpenMonth,
  onPreview,
  onRunMonth,
  onOpenRules,
  onClearPreview,
}) => {
  switch (breakdown.status) {
    case 'NO_REPORT':
      return (
        <Banner icon="mdi:database-off-outline" tone="info">
          {breakdown.message || 'No Cost and Usage Report covers this month.'} Environment allocation reads the
          report, so only months it covers can be split.
        </Banner>
      );
    case 'NO_RULES':
      return (
        <Banner icon="mdi:source-branch" tone="info">
          <span>{breakdown.message} </span>
          <button type="button" className="font-semibold underline" onClick={onOpenRules}>
            Set up rules
          </button>
        </Banner>
      );
    case 'RUNNING':
      return (
        <Banner icon="mdi:progress-clock" tone="info">
          {breakdown.message} This card updates when it finishes.
        </Banner>
      );
    case 'NOT_RUN':
      return (
        <Banner icon="mdi:calendar-clock" tone="info">
          <p>{breakdown.message}</p>
          <div className="flex items-center gap-2 mt-2 flex-wrap">
            <button type="button" className={primaryCls} disabled={runPending} onClick={onRunMonth}>
              <Icon icon="mdi:play" width={14} />
              Run {monthLabel(breakdown.period)} with version {breakdown.latestVersion}
            </button>
            <button type="button" className={buttonCls} disabled={previewPending} onClick={onPreview}>
              <Icon icon={previewPending ? 'mdi:loading' : 'mdi:eye-outline'} width={14}
                className={previewPending ? 'animate-spin' : ''} />
              Preview first
            </button>
          </div>
        </Banner>
      );
    case 'PREVIEW':
      return (
        <Banner icon="mdi:eye-outline" tone="info">
          <span>{breakdown.message} </span>
          {isPreview && !isOpenMonth && (
            <>
              <button type="button" className="font-semibold underline mr-3" disabled={runPending} onClick={onRunMonth}>
                Run and store it
              </button>
              <button type="button" className="underline" onClick={onClearPreview}>
                Close preview
              </button>
            </>
          )}
        </Banner>
      );
    case 'RUN':
      return breakdown.message || breakdown.pendingRun ? (
        <Banner icon="mdi:information-outline" tone={breakdown.message ? 'warn' : 'info'}>
          {breakdown.message}
          {breakdown.pendingRun && ' A newer run of this month is in progress.'}
        </Banner>
      ) : null;
    default:
      return null;
  }
};

// ── Totals ───────────────────────────────────────────────────────────────────

const Totals: React.FC<{ breakdown: Breakdown; money: (v: number) => string }> = ({ breakdown, money }) => {
  const total = breakdown.totalCost ?? 0;
  const positive = breakdown.environments.filter((e) => e.total > 0);
  const barTotal = positive.reduce((a, e) => a + e.total, 0);
  const visible = breakdown.environments.filter((e) => e.total !== 0 || !e.unallocated);

  return (
    <div className="mt-5">
      <div className="flex items-end justify-between gap-4 flex-wrap mb-2">
        <p className="text-[12px] text-[rgba(0,0,0,0.6)]">
          <span className="font-semibold text-black">{money(breakdown.allocatedCost ?? 0)}</span> of{' '}
          {money(total)} allocated
          {(breakdown.unallocatedCost ?? 0) !== 0 && (
            <>
              {' '}
              · <span className="font-semibold text-[#6B7280]">{money(breakdown.unallocatedCost ?? 0)}</span>{' '}
              unallocated
            </>
          )}
        </p>
        <p className="text-[11px] text-[rgba(0,0,0,0.4)]">Environments add up to the bill total exactly.</p>
      </div>
      {barTotal > 0 && (
        <div className="flex h-3 rounded-full overflow-hidden bg-[rgba(0,0,0,0.05)]" aria-hidden>
          {positive.map((e) => (
            <div
              key={e.name}
              style={{ width: `${(e.total / barTotal) * 100}%`, background: e.color ?? '#9CA3AF' }}
              title={`${e.name}: ${money(e.total)}`}
            />
          ))}
        </div>
      )}
      <div className="mt-4 grid grid-cols-2 md:grid-cols-3 xl:grid-cols-6 gap-3">
        {visible.map((e) => (
          <EnvCard key={e.name} env={e} money={money} />
        ))}
      </div>
    </div>
  );
};

const EnvCard: React.FC<{ env: EnvironmentTotal; money: (v: number) => string }> = ({ env, money }) => (
  <div className="border border-[rgba(0,0,0,0.08)] rounded-xl px-3 py-2.5">
    <p className="flex items-center gap-1.5 text-[11.5px] font-medium text-[rgba(0,0,0,0.6)]">
      <span className="w-2 h-2 rounded-full flex-shrink-0" style={{ background: env.color ?? '#9CA3AF' }} />
      <span className="truncate">{env.name}</span>
    </p>
    <p className="text-[16px] font-bold text-black tabular-nums mt-0.5">{money(env.total)}</p>
    <p className="text-[10.5px] text-[rgba(0,0,0,0.45)] tabular-nums">
      {trimPercent(env.share)}% of bill
      {!env.unallocated && (env.direct !== 0 || env.split !== 0) && (
        <>
          <br />
          direct {money(env.direct)} · split {money(env.split)}
        </>
      )}
    </p>
  </div>
);

// ── Breakdown table ──────────────────────────────────────────────────────────

interface RuleGroup {
  key: string;
  ruleName: string;
  rulePosition: number | null;
  kind: BreakdownRow['kind'];
  percent: number | null;
  amount: number;
  sourceCost: number;
  services: BreakdownRow[];
}

const KIND_STYLES: Record<BreakdownRow['kind'], string> = {
  TAGGED: 'bg-[#DCFCE7] text-[#166534]',
  RULE: 'bg-[#DBEAFE] text-[#1E40AF]',
  SPLIT: 'bg-[#FEF3C7] text-[#92400E]',
  PROPORTIONAL: 'bg-[#EDE9FE] text-[#5B21B6]',
  UNALLOCATED: 'bg-[#F3F4F6] text-[#4B5563]',
};

const BreakdownTable: React.FC<{ breakdown: Breakdown; money: (v: number) => string }> = ({ breakdown, money }) => {
  const [open, setOpen] = useState<Set<string>>(new Set());
  const toggle = (k: string) =>
    setOpen((prev) => {
      const next = new Set(prev);
      if (next.has(k)) next.delete(k);
      else next.add(k);
      return next;
    });

  const groups = useMemo(() => {
    const byEnv = new Map<string, Map<string, RuleGroup>>();
    for (const r of breakdown.rows) {
      const env = byEnv.get(r.environment) ?? new Map<string, RuleGroup>();
      byEnv.set(r.environment, env);
      const key = `${r.ruleId ?? 'none'}|${r.kind}|${r.percent ?? ''}`;
      const g = env.get(key) ?? {
        key,
        ruleName: r.ruleName ?? (r.kind === 'UNALLOCATED' ? 'No rule matched' : '—'),
        rulePosition: r.rulePosition,
        kind: r.kind,
        percent: r.percent,
        amount: 0,
        sourceCost: 0,
        services: [],
      };
      g.amount += r.amount;
      g.sourceCost += r.sourceCost;
      g.services.push(r);
      env.set(key, g);
    }
    return byEnv;
  }, [breakdown.rows]);

  const envs = breakdown.environments.filter((e) => groups.has(e.name));
  if (envs.length === 0) {
    return (
      <p className="mt-5 text-[12px] text-[rgba(0,0,0,0.45)]">Nothing was billed to this account in this month.</p>
    );
  }

  return (
    <div className="mt-6 overflow-x-auto -mx-2">
      <table className="w-full min-w-[640px] text-[12.5px]">
        <thead>
          <tr className="text-left text-[11px] uppercase tracking-wide text-[rgba(0,0,0,0.4)]">
            <th className="font-medium px-2 pb-2">Environment / rule / service / resource</th>
            <th className="font-medium px-2 pb-2">How</th>
            <th className="font-medium px-2 pb-2 text-right">Share</th>
            <th className="font-medium px-2 pb-2 text-right">Of</th>
            <th className="font-medium px-2 pb-2 text-right">Amount</th>
          </tr>
        </thead>
        <tbody>
          {envs.map((env) => {
            const envOpen = open.has(env.name);
            const ruleGroups = [...(groups.get(env.name)?.values() ?? [])].sort((a, b) => b.amount - a.amount);
            return (
              <React.Fragment key={env.name}>
                <tr
                  className="border-t border-[rgba(0,0,0,0.06)] cursor-pointer hover:bg-[rgba(0,0,0,0.015)]"
                  onClick={() => toggle(env.name)}
                >
                  <td className="px-2 py-2.5 font-semibold text-black">
                    <span className="inline-flex items-center gap-1.5">
                      <Icon icon={envOpen ? 'mdi:chevron-down' : 'mdi:chevron-right'} width={14}
                        className="text-[rgba(0,0,0,0.35)]" />
                      <span className="w-2 h-2 rounded-full" style={{ background: env.color ?? '#9CA3AF' }} />
                      {env.name}
                    </span>
                  </td>
                  <td className="px-2 py-2.5 text-[11.5px] text-[rgba(0,0,0,0.5)]">
                    {ruleGroups.length} rule{ruleGroups.length === 1 ? '' : 's'}
                  </td>
                  <td className="px-2 py-2.5 text-right text-[rgba(0,0,0,0.5)] tabular-nums">{trimPercent(env.share)}%</td>
                  <td className="px-2 py-2.5" />
                  <td className="px-2 py-2.5 text-right font-semibold tabular-nums">{money(env.total)}</td>
                </tr>
                {envOpen &&
                  ruleGroups.map((g) => {
                    const gKey = `${env.name}|${g.key}`;
                    const gOpen = open.has(gKey);
                    return (
                      <React.Fragment key={gKey}>
                        <tr
                          className="bg-[rgba(0,0,0,0.012)] cursor-pointer hover:bg-[rgba(0,0,0,0.025)]"
                          onClick={() => toggle(gKey)}
                        >
                          <td className="pl-8 pr-2 py-2">
                            <span className="inline-flex items-center gap-1.5">
                              <Icon icon={gOpen ? 'mdi:chevron-down' : 'mdi:chevron-right'} width={13}
                                className="text-[rgba(0,0,0,0.35)]" />
                              {g.rulePosition !== null && (
                                <span className="text-[10.5px] px-1 rounded bg-[rgba(0,0,0,0.06)] font-semibold">
                                  {g.rulePosition}
                                </span>
                              )}
                              <span className="font-medium">{g.ruleName}</span>
                            </span>
                          </td>
                          <td className="px-2 py-2">
                            <span className={`px-1.5 py-0.5 rounded text-[10.5px] font-medium ${KIND_STYLES[g.kind]}`}>
                              {KIND_LABELS[g.kind]}
                            </span>
                          </td>
                          <td className="px-2 py-2 text-right tabular-nums text-[rgba(0,0,0,0.6)]">
                            {g.kind === 'UNALLOCATED' ? '' : `${trimPercent(g.percent)}%`}
                          </td>
                          <td className="px-2 py-2 text-right tabular-nums text-[rgba(0,0,0,0.45)]">
                            {g.kind === 'SPLIT' || g.kind === 'PROPORTIONAL' ? money(g.sourceCost) : ''}
                          </td>
                          <td className="px-2 py-2 text-right tabular-nums">{money(g.amount)}</td>
                        </tr>
                        {gOpen &&
                          [...g.services]
                            .sort((a, b) => b.amount - a.amount)
                            .map((s) => {
                              // Within one rule group a service appears once, so it keys the row.
                              const sKey = `${gKey}|svc|${s.service ?? ''}`;
                              const resources = s.resources ?? [];
                              const expandable = resources.length > 0;
                              const sOpen = expandable && open.has(sKey);
                              const shared = g.kind === 'SPLIT' || g.kind === 'PROPORTIONAL';
                              const hidden = (s.resourceCount ?? 0) - resources.length;
                              return (
                                <React.Fragment key={sKey}>
                                  <tr
                                    className={`text-[12px] ${
                                      expandable ? 'cursor-pointer hover:bg-[rgba(0,0,0,0.015)]' : ''
                                    }`}
                                    onClick={expandable ? () => toggle(sKey) : undefined}
                                  >
                                    <td className="pl-12 pr-2 py-1.5 text-[rgba(0,0,0,0.65)]">
                                      <span className="inline-flex items-center gap-1.5">
                                        {/* A fixed-width slot, so service names line up whether or
                                            not the row has resources to open. */}
                                        <span className="w-[13px] inline-flex">
                                          {expandable && (
                                            <Icon icon={sOpen ? 'mdi:chevron-down' : 'mdi:chevron-right'}
                                              width={13} className="text-[rgba(0,0,0,0.35)]" />
                                          )}
                                        </span>
                                        {s.service || 'Other'}
                                      </span>
                                    </td>
                                    <td className="px-2 py-1.5 text-[11px] text-[rgba(0,0,0,0.4)]">
                                      {expandable
                                        ? `${s.resourceCount} resource${s.resourceCount === 1 ? '' : 's'}`
                                        : `${s.lineCount} line${s.lineCount === 1 ? '' : 's'}`}
                                    </td>
                                    <td className="px-2 py-1.5" />
                                    <td className="px-2 py-1.5 text-right tabular-nums text-[rgba(0,0,0,0.4)]">
                                      {shared ? money(s.sourceCost) : ''}
                                    </td>
                                    <td className="px-2 py-1.5 text-right tabular-nums text-[rgba(0,0,0,0.65)]">
                                      {money(s.amount)}
                                    </td>
                                  </tr>
                                  {sOpen &&
                                    resources.map((res) => (
                                      <tr key={`${sKey}|${res.resourceId ?? '∅'}`} className="text-[11.5px]">
                                        <td className="pl-[84px] pr-2 py-1">
                                          {res.resourceId ? (
                                            <span title={res.resourceId} className="inline-flex items-baseline gap-2 min-w-0">
                                              <span className="font-mono text-black">{shortenId(res.resourceId, 56)}</span>
                                              {res.region && (
                                                <span className="text-[10.5px] text-[rgba(0,0,0,0.4)]">{res.region}</span>
                                              )}
                                            </span>
                                          ) : (
                                            <span className="italic text-[rgba(0,0,0,0.45)]">
                                              No resource (tax, support, account-level charges)
                                            </span>
                                          )}
                                        </td>
                                        <td className="px-2 py-1 text-[10.5px] text-[rgba(0,0,0,0.35)]">
                                          {res.lineCount} line{res.lineCount === 1 ? '' : 's'}
                                        </td>
                                        <td className="px-2 py-1" />
                                        <td className="px-2 py-1 text-right tabular-nums text-[rgba(0,0,0,0.35)]">
                                          {shared ? money(res.sourceCost) : ''}
                                        </td>
                                        <td className="px-2 py-1 text-right tabular-nums text-[rgba(0,0,0,0.6)]">
                                          {money(res.amount)}
                                        </td>
                                      </tr>
                                    ))}
                                  {sOpen && hidden > 0 && (
                                    <tr className="text-[11px]">
                                      <td colSpan={5} className="pl-[84px] pr-2 py-1 text-[rgba(0,0,0,0.4)]">
                                        Showing the {resources.length} costliest of {s.resourceCount}. The CSV
                                        export of a stored run lists every one.
                                      </td>
                                    </tr>
                                  )}
                                </React.Fragment>
                              );
                            })}
                      </React.Fragment>
                    );
                  })}
              </React.Fragment>
            );
          })}
        </tbody>
      </table>
      <p className="text-[11px] text-[rgba(0,0,0,0.35)] mt-3 px-2 leading-relaxed">
        <span className="font-medium">Label</span> and <span className="font-medium">Rule</span> are direct: the
        whole amount went to one environment. <span className="font-medium">Split</span> and{' '}
        <span className="font-medium">Proportional</span> divide a shared amount, shown in the &quot;Of&quot; column.
        The CSV export lists every line with its usage type, region, charge type and the label it matched.
      </p>
    </div>
  );
};

const RulesTaken: React.FC<{ breakdown: Breakdown; money: (v: number) => string }> = ({ breakdown, money }) => {
  if (breakdown.rules.length === 0) return null;
  const envName = (id: number) =>
    breakdown.rules.flatMap((r) => r.rule.splits).find((s) => s.environmentId === id)?.environmentName ?? '';
  return (
    <div className="mt-6">
      <p className="text-[12.5px] font-semibold text-black mb-2">
        Rules{breakdown.versionNumber ? ` (version ${breakdown.versionNumber})` : ' (draft)'}, first match wins
      </p>
      <ol className="space-y-1">
        {breakdown.rules.map((r) => (
          <li key={r.ruleId ?? r.position} className="flex items-start gap-3 text-[12px]">
            <span className="w-5 h-5 flex-shrink-0 flex items-center justify-center rounded bg-[rgba(0,0,0,0.05)] text-[10.5px] font-semibold">
              {r.position}
            </span>
            <span className="flex-1 min-w-0">
              <span className="font-medium text-black">{r.name}</span>
              <span className="text-[rgba(0,0,0,0.5)]">
                {' '}
                — {describeMatch(r.rule)} → {describeAllocation(r.rule, envName)}
              </span>
            </span>
            <span className={`tabular-nums flex-shrink-0 ${r.matched ? 'text-black' : 'text-[rgba(0,0,0,0.35)]'}`}>
              {r.matched ? money(r.matchedCost) : 'matched nothing'}
            </span>
          </li>
        ))}
      </ol>
    </div>
  );
};

// ── Running months ───────────────────────────────────────────────────────────

const RunMonthsPanel: React.FC<{
  months: { value: string; label: string }[];
  basis: CostBasis;
  pending: boolean;
  onRun: (from: string, to: string) => void;
  onCancel: () => void;
}> = ({ months, basis, pending, onRun, onCancel }) => {
  // Oldest first in the "from" list reads naturally; both default to last month.
  const [from, setFrom] = useState(months[0]?.value ?? '');
  const [to, setTo] = useState(months[0]?.value ?? '');
  const invalid = from > to;
  return (
    <div className="mt-4 px-4 py-3 rounded-xl border border-[rgba(0,0,0,0.1)] bg-[rgba(0,0,0,0.015)]">
      <p className="text-[12.5px] font-semibold text-black">Run closed months</p>
      <p className="text-[11.5px] text-[rgba(0,0,0,0.5)] mt-0.5">
        Each month is read from the Cost and Usage Report, split with the latest published version and stored as
        its own run on the {basis} basis. Locked months are refused; unlock them first.
      </p>
      <div className="flex items-center gap-2 flex-wrap mt-3">
        <label className="text-[12px] text-[rgba(0,0,0,0.6)]">
          From{' '}
          <select className={selectCls} value={from} onChange={(e) => setFrom(e.target.value)}>
            {[...months].reverse().map((m) => (
              <option key={m.value} value={m.value}>
                {m.label}
              </option>
            ))}
          </select>
        </label>
        <label className="text-[12px] text-[rgba(0,0,0,0.6)]">
          to{' '}
          <select className={selectCls} value={to} onChange={(e) => setTo(e.target.value)}>
            {months.map((m) => (
              <option key={m.value} value={m.value}>
                {m.label}
              </option>
            ))}
          </select>
        </label>
        <button type="button" className={primaryCls} disabled={pending || invalid} onClick={() => onRun(from, to)}>
          <Icon icon="mdi:play" width={14} />
          Run
        </button>
        <button type="button" className={buttonCls} onClick={onCancel}>
          Cancel
        </button>
        {invalid && <span className="text-[11.5px] text-[#B45309]">The start month is after the end month.</span>}
      </div>
    </div>
  );
};

const STATUS_STYLES: Record<RunView['status'], string> = {
  QUEUED: 'bg-[#F3F4F6] text-[#4B5563]',
  RUNNING: 'bg-[#DBEAFE] text-[#1E40AF]',
  SUCCEEDED: 'bg-[#DCFCE7] text-[#166534]',
  FAILED: 'bg-[#FEE2E2] text-[#991B1B]',
};

const RunHistory: React.FC<{
  runs: RunView[] | undefined;
  loading: boolean;
  money: (v: number) => string;
  busy: boolean;
  onShow: (r: RunView) => void;
  onExport: (id: number, format: 'csv' | 'pdf') => void;
  onLock: (id: number, lock: boolean) => void;
}> = ({ runs, loading, money, busy, onShow, onExport, onLock }) => {
  if (loading) return <p className="mt-2 text-[12px] text-[rgba(0,0,0,0.45)]">Loading runs…</p>;
  if (!runs || runs.length === 0) {
    return <p className="mt-2 text-[12px] text-[rgba(0,0,0,0.45)]">No runs yet for this account.</p>;
  }
  return (
    <div className="mt-2 overflow-x-auto -mx-2">
      <table className="w-full min-w-[720px] text-[12px]">
        <thead>
          <tr className="text-left text-[10.5px] uppercase tracking-wide text-[rgba(0,0,0,0.4)]">
            <th className="font-medium px-2 py-1.5">Run</th>
            <th className="font-medium px-2 py-1.5">Month</th>
            <th className="font-medium px-2 py-1.5">Version</th>
            <th className="font-medium px-2 py-1.5">Status</th>
            <th className="font-medium px-2 py-1.5 text-right">Total</th>
            <th className="font-medium px-2 py-1.5 text-right">Unallocated</th>
            <th className="font-medium px-2 py-1.5">By</th>
            <th className="font-medium px-2 py-1.5" />
          </tr>
        </thead>
        <tbody>
          {runs.map((r) => (
            <tr key={r.id} className="border-t border-[rgba(0,0,0,0.05)] align-top">
              <td className="px-2 py-1.5 tabular-nums">
                #{r.id}
                {r.locked && <Icon icon="mdi:lock" width={12} className="inline ml-1 text-[#166534]" />}
              </td>
              <td className="px-2 py-1.5">
                {monthLabel(r.period)}
                <span className="block text-[10.5px] text-[rgba(0,0,0,0.4)]">{r.basis}</span>
              </td>
              <td className="px-2 py-1.5">v{r.versionNumber}</td>
              <td className="px-2 py-1.5">
                <span className={`px-1.5 py-0.5 rounded text-[10.5px] font-medium ${STATUS_STYLES[r.status]}`}>
                  {r.status.toLowerCase()}
                </span>
                {r.error && (
                  <span className="block text-[10.5px] text-[rgba(0,0,0,0.5)] max-w-[260px] break-words mt-0.5">
                    {r.error}
                  </span>
                )}
              </td>
              <td className="px-2 py-1.5 text-right tabular-nums">{r.totalCost === null ? '' : money(r.totalCost)}</td>
              <td className="px-2 py-1.5 text-right tabular-nums">
                {r.unallocatedCost === null ? '' : money(r.unallocatedCost)}
              </td>
              <td className="px-2 py-1.5 text-[rgba(0,0,0,0.6)]">
                {r.startedBy}
                <span className="block text-[10.5px] text-[rgba(0,0,0,0.4)]">{when(r.queuedAt)}</span>
              </td>
              <td className="px-2 py-1.5 whitespace-nowrap text-right">
                {r.status === 'SUCCEEDED' && (
                  <span className="inline-flex items-center gap-2">
                    <button type="button" className="underline" onClick={() => onShow(r)}>
                      Show
                    </button>
                    <button type="button" className="underline" disabled={busy} onClick={() => onExport(r.id, 'csv')}>
                      CSV
                    </button>
                    <button type="button" className="underline" disabled={busy} onClick={() => onExport(r.id, 'pdf')}>
                      PDF
                    </button>
                    <button type="button" className="underline" disabled={busy} onClick={() => onLock(r.id, !r.locked)}>
                      {r.locked ? 'Unlock' : 'Lock'}
                    </button>
                  </span>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      <p className="text-[11px] text-[rgba(0,0,0,0.35)] mt-2 px-2">
        &quot;Show&quot; opens that month; the card shows the locked run if there is one, otherwise the newest.
      </p>
    </div>
  );
};

export default EnvironmentAllocationSection;
