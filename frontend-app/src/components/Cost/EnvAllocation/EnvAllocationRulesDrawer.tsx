import React, { useEffect, useMemo, useState } from 'react';
import { createPortal } from 'react-dom';
import { AnimatePresence, motion } from 'framer-motion';
import { Icon } from '@iconify/react';
import { useMutation, useQuery } from '@tanstack/react-query';
import {
  createEnvironment,
  deleteEnvironment,
  fetchAudit,
  fetchDraft,
  fetchEnvironments,
  fetchVersion,
  fetchVersions,
  previewRules,
  publishDraft,
  saveDraft,
  updateEnvironment,
  type AllocationType,
  type Breakdown,
  type Environment,
  type MatchField,
  type MatchOperator,
  type Rule,
} from '../../../api/envAllocationApi';
import type { CostBasis } from '../../../api/curInsightsApi';
import { queryClient } from '../../../lib/queryClient';
import { lockPageScroll } from '../../../utils/scrollLock';
import { useMoneyIn } from '../costCurrency';
import {
  ALLOCATION_LABELS,
  FIELD_HINTS,
  FIELD_LABELS,
  OPERATOR_LABELS,
  describeAllocation,
  describeMatch,
  monthLabel,
  newRule,
  ruleProblem,
  trimPercent,
  when,
} from './envAllocationText';
import MatchValuePicker from './MatchValuePicker';

/**
 * Where the client's environments and rules are edited, previewed and published.
 *
 * <p>Three steps, kept apart on purpose. <b>Save</b> stores the draft and changes nothing anyone
 * reads. <b>Preview</b> applies the rules on screen — saved or not — to the month the card is
 * showing, so a rule is tried against real spend before it counts. <b>Publish</b> freezes the saved
 * draft as the next version, which is the only thing a stored run ever uses.
 */

export const ENV_ALLOCATION_KEY = 'env-allocation';

type DrawerTab = 'rules' | 'environments' | 'history';

interface EnvAllocationRulesDrawerProps {
  open: boolean;
  onClose: () => void;
  accountId: string;
  /** The month and basis the card is showing; previews use the same. */
  period: string;
  basis: CostBasis;
  currency: string;
}

const inputCls =
  'px-2.5 py-1.5 text-[12.5px] border border-[rgba(0,0,0,0.15)] rounded-lg bg-white focus:outline-none focus:ring-2 focus:ring-black/5';
const buttonCls =
  'inline-flex items-center gap-1.5 px-3 py-1.5 text-[12px] font-medium border border-[rgba(0,0,0,0.15)] rounded-lg hover:border-[rgba(0,0,0,0.35)] transition-colors disabled:opacity-40 disabled:cursor-not-allowed';
const primaryCls =
  'inline-flex items-center gap-1.5 px-3 py-1.5 text-[12px] font-semibold rounded-lg bg-black text-white hover:bg-black/85 transition-colors disabled:opacity-40 disabled:cursor-not-allowed';

const errorText = (e: unknown): string => (e instanceof Error ? e.message : String(e));

const EnvAllocationRulesDrawer: React.FC<EnvAllocationRulesDrawerProps> = ({
  open,
  onClose,
  accountId,
  period,
  basis,
  currency,
}) => {
  const [tab, setTab] = useState<DrawerTab>('rules');

  useEffect(() => (open ? lockPageScroll() : undefined), [open]);
  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [open, onClose]);

  const environments = useQuery({
    queryKey: [ENV_ALLOCATION_KEY, 'environments'],
    queryFn: fetchEnvironments,
    enabled: open,
  });

  return createPortal(
    <AnimatePresence>
      {open && (
        <React.Fragment key="env-allocation-drawer">
          <motion.div
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            transition={{ duration: 0.2, ease: 'easeInOut' }}
            className="fixed inset-0 bg-black/30 z-50"
            onClick={onClose}
          />
          <motion.aside
            initial={{ x: '100%' }}
            animate={{ x: 0 }}
            exit={{ x: '100%' }}
            transition={{ duration: 0.2, ease: 'easeInOut' }}
            role="dialog"
            aria-label="Environment allocation rules"
            className="fixed right-0 top-0 h-full w-full sm:w-[70vw] sm:max-w-[1080px] bg-white shadow-2xl z-50 flex flex-col"
          >
            <div className="px-5 py-4 border-b border-[rgba(0,0,0,0.08)] flex items-start justify-between gap-4">
              <div className="min-w-0 flex items-start gap-3">
                <span className="w-9 h-9 flex-shrink-0 flex items-center justify-center rounded-lg bg-[rgba(37,99,235,0.08)]">
                  <Icon icon="mdi:source-branch" width={18} className="text-[#2563EB]" />
                </span>
                <div>
                  <p className="text-[15px] font-bold text-black">Environments and rules</p>
                  <p className="text-[11.5px] text-[rgba(0,0,0,0.45)] mt-0.5">
                    Rules run top to bottom and the first match takes the line. Previews use{' '}
                    {monthLabel(period)}.
                  </p>
                </div>
              </div>
              <button
                type="button"
                onClick={onClose}
                aria-label="Close"
                className="w-8 h-8 flex items-center justify-center rounded-lg border border-[rgba(0,0,0,0.1)] hover:bg-[rgba(0,0,0,0.04)]"
              >
                <Icon icon="mdi:close" width={16} />
              </button>
            </div>

            <div className="px-5 pt-3 flex gap-1 border-b border-[rgba(0,0,0,0.08)]" role="tablist">
              {(
                [
                  ['rules', 'Rules', 'mdi:format-list-numbered'],
                  ['environments', 'Environments', 'mdi:shape-outline'],
                  ['history', 'Versions & audit', 'mdi:history'],
                ] as [DrawerTab, string, string][]
              ).map(([id, label, icon]) => (
                <button
                  key={id}
                  type="button"
                  role="tab"
                  aria-selected={tab === id}
                  onClick={() => setTab(id)}
                  className={`inline-flex items-center gap-1.5 px-3 py-2 text-[12.5px] font-medium border-b-2 -mb-px transition-colors ${
                    tab === id
                      ? 'border-black text-black'
                      : 'border-transparent text-[rgba(0,0,0,0.5)] hover:text-black'
                  }`}
                >
                  <Icon icon={icon} width={14} />
                  {label}
                </button>
              ))}
            </div>

            <div className="flex-1 overflow-y-auto px-5 py-4">
              {environments.isError && (
                <p className="text-[12px] text-[#B91C1C]">{errorText(environments.error)}</p>
              )}
              {tab === 'rules' && (
                <RulesTab
                  environments={environments.data ?? []}
                  accountId={accountId}
                  period={period}
                  basis={basis}
                  currency={currency}
                />
              )}
              {tab === 'environments' && <EnvironmentsTab environments={environments.data ?? []} />}
              {tab === 'history' && <HistoryTab />}
            </div>
          </motion.aside>
        </React.Fragment>
      )}
    </AnimatePresence>,
    document.body,
  );
};

// ── Rules ────────────────────────────────────────────────────────────────────

interface RulesTabProps {
  environments: Environment[];
  accountId: string;
  period: string;
  basis: CostBasis;
  currency: string;
}

const RulesTab: React.FC<RulesTabProps> = ({ environments, accountId, period, basis, currency }) => {
  const money = useMoneyIn(currency);
  const draft = useQuery({ queryKey: [ENV_ALLOCATION_KEY, 'draft'], queryFn: fetchDraft });
  const [rules, setRules] = useState<Rule[] | null>(null);
  const [dirty, setDirty] = useState(false);
  const [openIndex, setOpenIndex] = useState<number | null>(null);
  const [note, setNote] = useState('');
  const [message, setMessage] = useState<{ tone: 'ok' | 'error'; text: string } | null>(null);
  const [preview, setPreview] = useState<Breakdown | null>(null);
  const [addOpen, setAddOpen] = useState(false);

  // Adopt the server's draft whenever it changes and nothing local is pending.
  useEffect(() => {
    if (draft.data && !dirty) setRules(draft.data.rules);
  }, [draft.data, dirty]);

  const update = (next: Rule[]) => {
    setRules(next);
    setDirty(true);
    setPreview(null);
  };

  const save = useMutation({
    mutationFn: (r: Rule[]) => saveDraft(r),
    onSuccess: (view) => {
      queryClient.setQueryData([ENV_ALLOCATION_KEY, 'draft'], view);
      setRules(view.rules);
      setDirty(false);
      setMessage({ tone: 'ok', text: 'Draft saved. Publish it to use it for runs.' });
    },
    onError: (e) => setMessage({ tone: 'error', text: errorText(e) }),
  });

  const publish = useMutation({
    mutationFn: () => publishDraft(note),
    onSuccess: (v) => {
      setNote('');
      setMessage({ tone: 'ok', text: `Published version ${v.versionNumber}.` });
      void queryClient.invalidateQueries({ queryKey: [ENV_ALLOCATION_KEY] });
    },
    onError: (e) => setMessage({ tone: 'error', text: errorText(e) }),
  });

  const runPreview = useMutation({
    mutationFn: (r: Rule[]) => previewRules(accountId, period, basis, { rules: r }),
    onSuccess: (b) => {
      setPreview(b);
      setMessage(null);
    },
    onError: (e) => setMessage({ tone: 'error', text: errorText(e) }),
  });

  const envName = (id: number) => environments.find((e) => e.id === id)?.name ?? 'removed environment';
  const matchedByPosition = useMemo(() => {
    const m = new Map<number, { cost: number; matched: boolean }>();
    for (const r of preview?.rules ?? []) m.set(r.position, { cost: r.matchedCost, matched: r.matched });
    return m;
  }, [preview]);

  if (draft.isPending || rules === null) {
    return <p className="text-[12px] text-[rgba(0,0,0,0.45)]">Loading rules…</p>;
  }
  if (draft.isError) {
    return <p className="text-[12px] text-[#B91C1C]">{errorText(draft.error)}</p>;
  }

  const problems = rules.map(ruleProblem);
  const firstProblem = problems.findIndex((p) => p !== null);
  const move = (i: number, delta: number) => {
    const j = i + delta;
    if (j < 0 || j >= rules.length) return;
    const next = [...rules];
    [next[i], next[j]] = [next[j], next[i]];
    update(next);
    setOpenIndex(openIndex === i ? j : openIndex === j ? i : openIndex);
  };
  const add = (template: Parameters<typeof newRule>[0]) => {
    const rule = newRule(template);
    // "Everything else" belongs last; anything else goes above an existing catch-all.
    const catchAll = rules.findIndex((r) => r.field === 'ANY');
    const at = template === 'rest' || catchAll < 0 ? rules.length : catchAll;
    const next = [...rules.slice(0, at), rule, ...rules.slice(at)];
    update(next);
    setOpenIndex(at);
    setAddOpen(false);
  };

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between gap-3 flex-wrap">
        <p className="text-[12px] text-[rgba(0,0,0,0.55)]">
          {draft.data.latestVersion > 0
            ? `Latest published: version ${draft.data.latestVersion}.`
            : 'Nothing published yet.'}{' '}
          {dirty
            ? 'You have unsaved changes.'
            : draft.data.unpublishedChanges
              ? 'The saved draft has changes that are not published.'
              : draft.data.latestVersion > 0
                ? 'The draft matches the published version.'
                : ''}
        </p>
        <div className="flex items-center gap-2 flex-wrap">
          <button
            type="button"
            className={buttonCls}
            disabled={runPreview.isPending || firstProblem >= 0}
            onClick={() => runPreview.mutate(rules)}
            title={`Apply these rules to ${monthLabel(period)} without storing anything`}
          >
            <Icon icon={runPreview.isPending ? 'mdi:loading' : 'mdi:eye-outline'} width={14}
              className={runPreview.isPending ? 'animate-spin' : ''} />
            Preview {monthLabel(period)}
          </button>
          <button
            type="button"
            className={buttonCls}
            disabled={!dirty || save.isPending || firstProblem >= 0}
            onClick={() => save.mutate(rules)}
          >
            <Icon icon="mdi:content-save-outline" width={14} />
            Save draft
          </button>
        </div>
      </div>

      {message && (
        <p
          className={`text-[12px] px-3 py-2 rounded-lg border ${
            message.tone === 'ok'
              ? 'text-[#166534] bg-[#F0FDF4] border-[#BBF7D0]'
              : 'text-[#B91C1C] bg-[#FEF2F2] border-[#FECACA]'
          }`}
        >
          {message.text}
        </p>
      )}

      {preview && <PreviewSummary preview={preview} money={money} />}

      {rules.length === 0 && (
        <div className="text-center py-8 rounded-xl border border-dashed border-[rgba(0,0,0,0.12)]">
          <p className="text-[13px] font-semibold text-black">No rules yet</p>
          <p className="text-[12px] text-[rgba(0,0,0,0.5)] mt-1 max-w-md mx-auto">
            Start with your XamOps labels, then add rules for spend that cannot be labelled — Bedrock
            models, spot instances, shared logs — and finish with a rule for everything else.
          </p>
        </div>
      )}

      <ol className="space-y-2">
        {rules.map((rule, i) => {
          const matched = matchedByPosition.get(i + 1);
          const isOpen = openIndex === i;
          return (
            <li key={rule.id ?? `new-${i}`} className="border border-[rgba(0,0,0,0.1)] rounded-xl">
              <div className="flex items-center gap-3 px-3 py-2.5">
                <span className="w-6 h-6 flex-shrink-0 flex items-center justify-center rounded-md bg-[rgba(0,0,0,0.05)] text-[11px] font-semibold">
                  {i + 1}
                </span>
                <button
                  type="button"
                  className="flex-1 min-w-0 text-left"
                  onClick={() => setOpenIndex(isOpen ? null : i)}
                >
                  <p className="text-[13px] font-semibold text-black truncate">{rule.name || 'Untitled rule'}</p>
                  <p className="text-[11.5px] text-[rgba(0,0,0,0.5)] truncate">
                    {summarise(rule, envName)}
                  </p>
                  {problems[i] && <p className="text-[11px] text-[#B45309]">{problems[i]}</p>}
                </button>
                {matched && (
                  <span
                    className={`text-[11.5px] font-medium tabular-nums ${
                      matched.matched ? 'text-black' : 'text-[rgba(0,0,0,0.35)]'
                    }`}
                    title={`What this rule took in the ${monthLabel(period)} preview`}
                  >
                    {matched.matched ? money(matched.cost) : 'matched nothing'}
                  </span>
                )}
                <div className="flex items-center gap-0.5 flex-shrink-0">
                  <IconButton icon="mdi:arrow-up" label="Move up" disabled={i === 0} onClick={() => move(i, -1)} />
                  <IconButton
                    icon="mdi:arrow-down"
                    label="Move down"
                    disabled={i === rules.length - 1}
                    onClick={() => move(i, 1)}
                  />
                  <IconButton
                    icon={isOpen ? 'mdi:chevron-up' : 'mdi:pencil-outline'}
                    label={isOpen ? 'Close' : 'Edit'}
                    onClick={() => setOpenIndex(isOpen ? null : i)}
                  />
                  <IconButton
                    icon="mdi:trash-can-outline"
                    label="Delete rule"
                    onClick={() => {
                      update(rules.filter((_, k) => k !== i));
                      setOpenIndex(null);
                    }}
                  />
                </div>
              </div>
              {isOpen && (
                <RuleEditor
                  rule={rule}
                  environments={environments}
                  accountId={accountId}
                  period={period}
                  money={money}
                  onChange={(r) => update(rules.map((x, k) => (k === i ? r : x)))}
                />
              )}
            </li>
          );
        })}
      </ol>

      <div className="relative">
        <button type="button" className={buttonCls} onClick={() => setAddOpen((o) => !o)}>
          <Icon icon="mdi:plus" width={14} />
          Add rule
        </button>
        {addOpen && (
          <div className="absolute z-10 mt-1 w-72 bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-lg py-1">
            {(
              [
                ['label', 'XamOps label', 'Use a label name, e.g. a label called Prod'],
                ['usage', 'Usage type', 'Bedrock models, spot usage, data transfer'],
                ['service', 'Service', 'A whole service, e.g. CloudWatch'],
                ['rest', 'Everything else', 'A catch-all, placed last'],
              ] as [Parameters<typeof newRule>[0], string, string][]
            ).map(([id, title, hint]) => (
              <button
                key={id}
                type="button"
                onClick={() => add(id)}
                className="w-full text-left px-3 py-2 hover:bg-[rgba(0,0,0,0.03)]"
              >
                <p className="text-[12.5px] font-medium text-black">{title}</p>
                <p className="text-[11px] text-[rgba(0,0,0,0.45)]">{hint}</p>
              </button>
            ))}
          </div>
        )}
      </div>

      <div className="border-t border-[rgba(0,0,0,0.08)] pt-4 space-y-2">
        <p className="text-[12.5px] font-semibold text-black">Publish</p>
        <p className="text-[11.5px] text-[rgba(0,0,0,0.5)]">
          Publishing freezes the saved draft as version{' '}
          {draft.data.latestVersion + 1}. Runs only ever use published versions, and every run keeps
          the version it was made with.
        </p>
        <div className="flex items-center gap-2 flex-wrap">
          <input
            className={`${inputCls} flex-1 min-w-[220px]`}
            placeholder="What changed (optional), e.g. Sonnet split agreed with finance"
            value={note}
            maxLength={500}
            onChange={(e) => setNote(e.target.value)}
          />
          <button
            type="button"
            className={primaryCls}
            disabled={dirty || publish.isPending || !draft.data.unpublishedChanges}
            onClick={() => publish.mutate()}
            title={dirty ? 'Save the draft first' : undefined}
          >
            <Icon icon="mdi:check-decagram-outline" width={14} />
            Publish version {draft.data.latestVersion + 1}
          </button>
        </div>
      </div>
    </div>
  );
};

const summarise = (rule: Rule, envName: (id: number) => string): string => {
  const subject =
    rule.field === 'ANY'
      ? 'Everything else'
      : `${FIELD_LABELS[rule.field]} ${
          OPERATOR_LABELS[rule.operator]
        }${rule.operator === 'EXISTS' ? '' : ` "${rule.value ?? ''}"`}`;
  const target =
    rule.allocation === 'FROM_VALUE'
      ? 'the environment it names'
      : rule.allocation === 'PROPORTIONAL'
        ? 'shared in proportion'
        : rule.splits.length === 0
          ? '…'
          : rule.splits.map((s) => `${trimPercent(s.percent)}% ${envName(s.environmentId)}`).join(', ');
  return `${subject} → ${target}`;
};

const PreviewSummary: React.FC<{ preview: Breakdown; money: (v: number) => string }> = ({ preview, money }) => {
  if (preview.status !== 'PREVIEW') {
    return (
      <p className="text-[12px] px-3 py-2 rounded-lg border border-[rgba(0,0,0,0.1)] bg-[rgba(0,0,0,0.02)]">
        {preview.message}
      </p>
    );
  }
  const total = preview.totalCost ?? 0;
  return (
    <div className="px-3 py-3 rounded-xl border border-[rgba(37,99,235,0.25)] bg-[rgba(37,99,235,0.04)] space-y-2">
      <p className="text-[12px] font-semibold text-black">
        Preview of {monthLabel(preview.period)}: {money(preview.allocatedCost ?? 0)} of {money(total)}{' '}
        allocated
      </p>
      <div className="flex flex-wrap gap-x-4 gap-y-1">
        {preview.environments
          .filter((e) => e.total !== 0)
          .map((e) => (
            <span key={e.name} className="inline-flex items-center gap-1.5 text-[11.5px]">
              <span className="w-2 h-2 rounded-full" style={{ background: e.color ?? '#9CA3AF' }} />
              {e.name} <span className="font-semibold tabular-nums">{money(e.total)}</span>
            </span>
          ))}
      </div>
    </div>
  );
};

const IconButton: React.FC<{ icon: string; label: string; onClick: () => void; disabled?: boolean }> = ({
  icon,
  label,
  onClick,
  disabled,
}) => (
  <button
    type="button"
    aria-label={label}
    title={label}
    disabled={disabled}
    onClick={onClick}
    className="w-7 h-7 flex items-center justify-center rounded-md text-[rgba(0,0,0,0.55)] hover:bg-[rgba(0,0,0,0.05)] hover:text-black disabled:opacity-30 disabled:cursor-not-allowed"
  >
    <Icon icon={icon} width={15} />
  </button>
);

// ── One rule's form ──────────────────────────────────────────────────────────

const FIELDS: MatchField[] = ['LABEL', 'USAGE_TYPE', 'SERVICE', 'CHARGE_TYPE', 'RESOURCE_ID', 'REGION', 'ACCOUNT', 'ANY'];
const OPERATORS: MatchOperator[] = ['CONTAINS', 'EQUALS', 'STARTS_WITH', 'EXISTS'];
const ALLOCATIONS: AllocationType[] = ['FIXED', 'FROM_VALUE', 'PROPORTIONAL'];

interface RuleEditorProps {
  rule: Rule;
  environments: Environment[];
  /** The account and month the pickers read values from; the same the preview uses. */
  accountId: string;
  period: string;
  money: (v: number) => string;
  onChange: (r: Rule) => void;
}

const RuleEditor: React.FC<RuleEditorProps> = ({ rule, environments, accountId, period, money, onChange }) => {
  const set = (patch: Partial<Rule>) => onChange({ ...rule, ...patch });
  const sum = rule.splits.reduce((a, s) => a + (Number.isFinite(s.percent) ? s.percent : 0), 0);
  const unused = environments.filter((e) => !rule.splits.some((s) => s.environmentId === e.id));

  return (
    <div className="px-3 pb-3 pt-1 border-t border-[rgba(0,0,0,0.06)] space-y-3">
      <label className="block">
        <span className="text-[11px] uppercase tracking-wide text-[rgba(0,0,0,0.45)]">Name</span>
        <input
          className={`${inputCls} w-full mt-1`}
          value={rule.name}
          maxLength={120}
          onChange={(e) => set({ name: e.target.value })}
        />
      </label>

      <div>
        <span className="text-[11px] uppercase tracking-wide text-[rgba(0,0,0,0.45)]">Matches when</span>
        <div className="flex items-center gap-2 flex-wrap mt-1">
          <select
            className={inputCls}
            value={rule.field}
            aria-label="Field"
            onChange={(e) => {
              const field = e.target.value as MatchField;
              set({
                field,
                operator: field === 'ANY' ? 'EXISTS' : rule.operator,
                tagKey: null,
                allocation: field === 'ANY' && rule.allocation === 'FROM_VALUE' ? 'FIXED' : rule.allocation,
              });
            }}
          >
            {FIELDS.map((f) => (
              <option key={f} value={f}>
                {FIELD_LABELS[f]}
              </option>
            ))}
            {/* A rule saved before AWS tags were removed keeps its field visible until it is changed. */}
            {rule.field === 'TAG' && <option value="TAG">{FIELD_LABELS.TAG}</option>}
          </select>
          {rule.field !== 'ANY' && (
            <select
              className={inputCls}
              aria-label="Comparison"
              value={rule.operator}
              onChange={(e) => set({ operator: e.target.value as MatchOperator })}
            >
              {OPERATORS.map((o) => (
                <option key={o} value={o}>
                  {OPERATOR_LABELS[o]}
                </option>
              ))}
            </select>
          )}
          {rule.field !== 'ANY' && rule.field !== 'TAG' && rule.operator !== 'EXISTS' && (
            <MatchValuePicker
              accountId={accountId}
              period={period}
              field={rule.field}
              value={rule.value ?? ''}
              onChange={(v) => set({ value: v })}
              money={money}
              className={inputCls}
              placeholder={rule.field === 'LABEL' ? 'Pick or type a label' : 'Pick or type a value'}
              ariaLabel="Value"
              maxLength={255}
            />
          )}
        </div>
        <p className="text-[11px] text-[rgba(0,0,0,0.45)] mt-1">{FIELD_HINTS[rule.field]} Comparisons ignore case.</p>
      </div>

      <div>
        <span className="text-[11px] uppercase tracking-wide text-[rgba(0,0,0,0.45)]">Allocate to</span>
        <div className="flex items-center gap-2 flex-wrap mt-1">
          <select
            className={inputCls}
            aria-label="Allocation"
            value={rule.allocation}
            onChange={(e) => set({ allocation: e.target.value as AllocationType })}
          >
            {ALLOCATIONS.filter((a) => !(a === 'FROM_VALUE' && rule.field === 'ANY')).map((a) => (
              <option key={a} value={a}>
                {ALLOCATION_LABELS[a]}
              </option>
            ))}
          </select>
        </div>
        {rule.allocation === 'FROM_VALUE' && (
          <p className="text-[11px] text-[rgba(0,0,0,0.45)] mt-1">
            The matched value is compared with each environment&apos;s name and match values, so{' '}
            <span className="font-mono">prod</span> and <span className="font-mono">production</span> can both
            mean Prod. A value that names no environment falls through to the next rule.
          </p>
        )}
        {rule.allocation === 'PROPORTIONAL' && (
          <p className="text-[11px] text-[rgba(0,0,0,0.45)] mt-1">
            Shared across environments in proportion to what every other rule gave them this month. Good for
            tax, support and enterprise discounts.
          </p>
        )}
        {rule.allocation === 'FIXED' && (
          <div className="mt-2 space-y-1.5">
            {rule.splits.map((s, k) => (
              <div key={`${s.environmentId}-${k}`} className="flex items-center gap-2">
                <select
                  className={`${inputCls} w-48`}
                  aria-label="Environment"
                  value={s.environmentId}
                  onChange={(e) =>
                    set({
                      splits: rule.splits.map((x, j) =>
                        j === k ? { ...x, environmentId: Number(e.target.value), environmentName: null } : x,
                      ),
                    })
                  }
                >
                  {environments
                    .filter((e) => e.id === s.environmentId || !rule.splits.some((x) => x.environmentId === e.id))
                    .map((e) => (
                      <option key={e.id} value={e.id}>
                        {e.name}
                      </option>
                    ))}
                  {!environments.some((e) => e.id === s.environmentId) && (
                    <option value={s.environmentId}>{s.environmentName ?? 'Removed environment'}</option>
                  )}
                </select>
                <div className="relative">
                  <input
                    className={`${inputCls} w-24 pr-6 text-right tabular-nums`}
                    type="number"
                    min={0}
                    max={100}
                    step="0.01"
                    aria-label="Percent"
                    value={Number.isFinite(s.percent) ? s.percent : ''}
                    onChange={(e) =>
                      set({
                        splits: rule.splits.map((x, j) =>
                          j === k ? { ...x, percent: e.target.value === '' ? NaN : Number(e.target.value) } : x,
                        ),
                      })
                    }
                  />
                  <span className="absolute right-2 top-1/2 -translate-y-1/2 text-[11px] text-[rgba(0,0,0,0.4)]">%</span>
                </div>
                <IconButton
                  icon="mdi:close"
                  label="Remove share"
                  onClick={() => set({ splits: rule.splits.filter((_, j) => j !== k) })}
                />
              </div>
            ))}
            <div className="flex items-center gap-3">
              <button
                type="button"
                className={buttonCls}
                disabled={unused.length === 0}
                onClick={() =>
                  set({
                    splits: [
                      ...rule.splits,
                      {
                        environmentId: unused[0].id,
                        environmentName: null,
                        percent: rule.splits.length === 0 ? 100 : Math.max(0, Number((100 - sum).toFixed(2))),
                      },
                    ],
                  })
                }
              >
                <Icon icon="mdi:plus" width={13} />
                Add environment
              </button>
              {rule.splits.length > 0 && (
                <span
                  className={`text-[11.5px] tabular-nums ${
                    Math.abs(sum - 100) < 0.0001 ? 'text-[#166534]' : 'text-[#B45309]'
                  }`}
                >
                  Total {trimPercent(sum)}%
                </span>
              )}
            </div>
          </div>
        )}
      </div>
    </div>
  );
};

// ── Environments ─────────────────────────────────────────────────────────────

const EnvironmentsTab: React.FC<{ environments: Environment[] }> = ({ environments }) => {
  const [error, setError] = useState<string | null>(null);
  const refresh = () => void queryClient.invalidateQueries({ queryKey: [ENV_ALLOCATION_KEY] });

  const create = useMutation({
    mutationFn: createEnvironment,
    onSuccess: () => {
      setError(null);
      refresh();
    },
    onError: (e) => setError(errorText(e)),
  });

  return (
    <div className="space-y-4">
      <p className="text-[12px] text-[rgba(0,0,0,0.55)]">
        These are the buckets your bill is split into. Rename, recolour or delete any of them. Match values
        are the XamOps label names that mean this environment — a label rule sends a resource labelled{' '}
        <span className="font-mono">prod</span> to whichever environment lists it. Published versions keep
        their own copy, so editing here never changes a past run.
      </p>
      {error && <p className="text-[12px] text-[#B91C1C]">{error}</p>}
      <div className="space-y-2">
        {environments.map((env) => (
          <EnvironmentRow key={env.id} env={env} onError={setError} onSaved={refresh} />
        ))}
      </div>
      <NewEnvironmentForm
        pending={create.isPending}
        onCreate={(input) => create.mutate(input)}
      />
    </div>
  );
};

const parseValues = (raw: string): string[] =>
  raw
    .split(',')
    .map((v) => v.trim())
    .filter(Boolean);

const EnvironmentRow: React.FC<{ env: Environment; onError: (e: string | null) => void; onSaved: () => void }> = ({
  env,
  onError,
  onSaved,
}) => {
  const [name, setName] = useState(env.name);
  const [color, setColor] = useState(env.color);
  const [values, setValues] = useState(env.matchValues.join(', '));
  useEffect(() => {
    setName(env.name);
    setColor(env.color);
    setValues(env.matchValues.join(', '));
  }, [env]);

  const changed =
    name !== env.name || color.toUpperCase() !== env.color.toUpperCase() || values !== env.matchValues.join(', ');

  const save = useMutation({
    mutationFn: () => updateEnvironment(env.id, { name, color, matchValues: parseValues(values), sortOrder: env.sortOrder }),
    onSuccess: () => {
      onError(null);
      onSaved();
    },
    onError: (e) => onError(errorText(e)),
  });
  const remove = useMutation({
    mutationFn: () => deleteEnvironment(env.id),
    onSuccess: () => {
      onError(null);
      onSaved();
    },
    onError: (e) => onError(errorText(e)),
  });

  return (
    <div className="flex items-center gap-2 flex-wrap border border-[rgba(0,0,0,0.1)] rounded-xl px-3 py-2">
      <input
        type="color"
        aria-label={`Colour of ${env.name}`}
        value={color}
        onChange={(e) => setColor(e.target.value)}
        className="w-8 h-8 rounded border border-[rgba(0,0,0,0.12)] cursor-pointer"
      />
      <input
        className={`${inputCls} w-40`}
        aria-label="Name"
        value={name}
        maxLength={60}
        onChange={(e) => setName(e.target.value)}
      />
      <input
        className={`${inputCls} flex-1 min-w-[220px]`}
        aria-label="Match values"
        placeholder="Match values, comma separated"
        value={values}
        onChange={(e) => setValues(e.target.value)}
      />
      <button type="button" className={buttonCls} disabled={!changed || save.isPending} onClick={() => save.mutate()}>
        Save
      </button>
      <IconButton
        icon="mdi:trash-can-outline"
        label={`Delete ${env.name}`}
        onClick={() => {
          if (window.confirm(`Delete the environment "${env.name}"? Past runs keep their own copy.`)) remove.mutate();
        }}
      />
    </div>
  );
};

const NewEnvironmentForm: React.FC<{
  pending: boolean;
  onCreate: (input: { name: string; color: string; matchValues: string[] }) => void;
}> = ({ pending, onCreate }) => {
  const [name, setName] = useState('');
  const [color, setColor] = useState('#0EA5E9');
  const [values, setValues] = useState('');
  return (
    <form
      className="flex items-center gap-2 flex-wrap border border-dashed border-[rgba(0,0,0,0.15)] rounded-xl px-3 py-2"
      onSubmit={(e) => {
        e.preventDefault();
        if (!name.trim()) return;
        onCreate({ name: name.trim(), color, matchValues: parseValues(values) });
        setName('');
        setValues('');
      }}
    >
      <input
        type="color"
        aria-label="Colour"
        value={color}
        onChange={(e) => setColor(e.target.value)}
        className="w-8 h-8 rounded border border-[rgba(0,0,0,0.12)] cursor-pointer"
      />
      <input
        className={`${inputCls} w-40`}
        placeholder="New environment"
        aria-label="New environment name"
        value={name}
        maxLength={60}
        onChange={(e) => setName(e.target.value)}
      />
      <input
        className={`${inputCls} flex-1 min-w-[220px]`}
        placeholder="Match values, e.g. shared, platform"
        aria-label="New environment match values"
        value={values}
        onChange={(e) => setValues(e.target.value)}
      />
      <button type="submit" className={buttonCls} disabled={pending || !name.trim()}>
        <Icon icon="mdi:plus" width={13} />
        Add
      </button>
    </form>
  );
};

// ── Versions and audit ───────────────────────────────────────────────────────

const ACTION_LABELS: Record<string, string> = {
  ENVIRONMENTS_SEEDED: 'Default environments created',
  ENVIRONMENT_CREATED: 'Environment added',
  ENVIRONMENT_UPDATED: 'Environment changed',
  ENVIRONMENT_DELETED: 'Environment deleted',
  DRAFT_SAVED: 'Draft saved',
  VERSION_PUBLISHED: 'Version published',
  RUN_QUEUED: 'Run requested',
  RUN_SUCCEEDED: 'Run finished',
  RUN_FAILED: 'Run failed',
  RUN_LOCKED: 'Run locked',
  RUN_UNLOCKED: 'Run unlocked',
  EXPORTED_CSV: 'CSV exported',
  EXPORTED_PDF: 'PDF exported',
};

/**
 * The rules one published version holds, in order, with the environment names as they were when it
 * was published. Fetched only when the version is opened.
 */
const VersionRules: React.FC<{ versionNumber: number }> = ({ versionNumber }) => {
  const version = useQuery({
    queryKey: [ENV_ALLOCATION_KEY, 'version', versionNumber],
    queryFn: () => fetchVersion(versionNumber),
    // A published version never changes.
    staleTime: Infinity,
  });
  if (version.isPending) {
    return <p className="px-3 pb-2.5 text-[11.5px] text-[rgba(0,0,0,0.45)]">Loading rules…</p>;
  }
  if (version.isError) {
    return <p className="px-3 pb-2.5 text-[11.5px] text-[#B91C1C]">{errorText(version.error)}</p>;
  }
  const envName = (id: number) =>
    version.data.environments.find((e) => e.id === id)?.name ?? 'removed environment';
  return (
    <ol className="px-3 pb-2.5 pt-1 space-y-1 border-t border-[rgba(0,0,0,0.06)]">
      {version.data.rules.map((r, i) => (
        <li key={r.id ?? i} className="flex items-start gap-2 text-[11.5px]">
          <span className="w-5 h-5 flex-shrink-0 flex items-center justify-center rounded bg-[rgba(0,0,0,0.05)] text-[10.5px] font-semibold">
            {i + 1}
          </span>
          <span className="min-w-0">
            <span className="font-medium text-black">{r.name}</span>
            <span className="block text-[rgba(0,0,0,0.55)]">
              {describeMatch(r)} → {describeAllocation(r, envName)}
            </span>
          </span>
        </li>
      ))}
    </ol>
  );
};

const HistoryTab: React.FC = () => {
  const [openVersion, setOpenVersion] = useState<number | null>(null);
  const versions = useQuery({ queryKey: [ENV_ALLOCATION_KEY, 'versions'], queryFn: fetchVersions });
  const audit = useQuery({ queryKey: [ENV_ALLOCATION_KEY, 'audit'], queryFn: fetchAudit });
  return (
    <div className="space-y-6">
      <section>
        <p className="text-[12.5px] font-semibold text-black mb-2">Published versions</p>
        {versions.isPending && <p className="text-[12px] text-[rgba(0,0,0,0.45)]">Loading…</p>}
        {versions.data && versions.data.length === 0 && (
          <p className="text-[12px] text-[rgba(0,0,0,0.45)]">Nothing published yet.</p>
        )}
        <ul className="space-y-1.5">
          {versions.data?.map((v) => {
            const isOpen = openVersion === v.versionNumber;
            return (
              <li key={v.versionNumber} className="border border-[rgba(0,0,0,0.08)] rounded-lg">
                <button
                  type="button"
                  className="w-full flex items-start gap-3 text-[12px] text-left px-2.5 py-2 hover:bg-[rgba(0,0,0,0.02)]"
                  aria-expanded={isOpen}
                  onClick={() => setOpenVersion(isOpen ? null : v.versionNumber)}
                >
                  <Icon icon={isOpen ? 'mdi:chevron-down' : 'mdi:chevron-right'} width={15}
                    className="flex-shrink-0 mt-0.5 text-[rgba(0,0,0,0.4)]" />
                  <span className="px-1.5 py-0.5 rounded bg-[rgba(0,0,0,0.05)] font-semibold">v{v.versionNumber}</span>
                  <span className="flex-1">
                    {v.note || <span className="text-[rgba(0,0,0,0.4)]">No note</span>}
                    <span className="block text-[11px] text-[rgba(0,0,0,0.45)]">
                      {v.ruleCount} rule{v.ruleCount === 1 ? '' : 's'} · {when(v.publishedAt)}
                      {v.publishedBy ? ` · ${v.publishedBy}` : ''}
                    </span>
                  </span>
                </button>
                {isOpen && <VersionRules versionNumber={v.versionNumber} />}
              </li>
            );
          })}
        </ul>
      </section>
      <section>
        <p className="text-[12.5px] font-semibold text-black mb-2">Audit log</p>
        {audit.isPending && <p className="text-[12px] text-[rgba(0,0,0,0.45)]">Loading…</p>}
        <ul className="divide-y divide-[rgba(0,0,0,0.06)]">
          {audit.data?.map((a) => (
            <li key={a.id} className="py-2 text-[12px]">
              <div className="flex items-center justify-between gap-3">
                <span className="font-medium text-black">{ACTION_LABELS[a.action] ?? a.action}</span>
                <span className="text-[11px] text-[rgba(0,0,0,0.45)] flex-shrink-0">
                  {when(a.createdAt)}
                  {a.actor ? ` · ${a.actor}` : ''}
                </span>
              </div>
              {a.detail && a.action !== 'DRAFT_SAVED' && (
                <p className="text-[11px] text-[rgba(0,0,0,0.5)] break-words">{a.detail}</p>
              )}
            </li>
          ))}
        </ul>
      </section>
    </div>
  );
};

export default EnvAllocationRulesDrawer;
