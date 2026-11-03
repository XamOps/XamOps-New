/**
 * XamOps Page Visibility section of the BillOps admin User Manager.
 *
 * Sets platform-wide rules on XamOps modules and tabs, so access changes for every XamOps user at
 * once instead of editing users one by one:
 *   - Default:      each user's own tab access decides (no rule).
 *   - Hide for all: removed from every XamOps user's sidebar, and its URL shows "not available".
 *   - Show to all:  shown to every XamOps user, even when their own tab list leaves it out.
 * Each rule can list exceptions: users the rule skips, who keep their own tab access.
 *
 * A tab rule wins over its module's rule, so "hide Observability, but show Logs to everyone" works.
 * Changes reach users on their next page load or navigation.
 *
 * Built from XAMOPS_MODULES, so a page added to the catalog shows up here with no extra wiring.
 */
import React, { useMemo, useState } from 'react';
import { Icon } from '@iconify/react';
import { AlertDialog, DialogPanel, DialogRoot } from '../ui';
import { XAMOPS_MODULES } from '../../constants/xamopsTabs';
import { useXamOpsPageRules } from '../../hooks/useXamOpsPageRules';
import type { XamPageRule, XamPageRuleMode } from '../../types/xamopsPageRules';
import type { BillOpsUser } from '../../types/billopsUserManager';

type Choice = XamPageRuleMode | null;

const CHOICES: { value: Choice; label: string; icon: string }[] = [
  { value: null,    label: 'Default',      icon: 'mdi:account-cog-outline' },
  { value: 'HIDE',  label: 'Hide for all', icon: 'mdi:eye-off-outline' },
  { value: 'GRANT', label: 'Show to all',  icon: 'mdi:eye-outline' },
];

const MODE_PILL: Record<XamPageRuleMode, { label: string; className: string; icon: string }> = {
  HIDE:  { label: 'Hidden for all', className: 'bg-rose-50 border-rose-200 text-rose-700',          icon: 'mdi:eye-off-outline' },
  GRANT: { label: 'Shown to all',   className: 'bg-emerald-50 border-emerald-200 text-emerald-700', icon: 'mdi:eye-outline' },
};

const MAX_SEARCH_RESULTS = 20;

/** "Observability" for a module, "Observability › Logs" for a tab. */
const KEY_LABELS: Record<string, string> = Object.fromEntries(
  XAMOPS_MODULES.flatMap(m => [
    [m.key, m.label] as const,
    ...m.tabs.map(t => [t.key, `${m.label} › ${t.label}`] as const),
  ]),
);
const labelOf = (key: string) => KEY_LABELS[key] ?? key;

interface PendingChange {
  key: string;
  isModule: boolean;
  from: Choice;
  to: Choice;
  exceptionCount: number;
  /** Tab rules under a module, which keep winning over the module rule. */
  tabOverrides: number;
}

interface Props {
  /** Every XamOps user the admin can see; the exception picker searches these. */
  xamopsUsers: BillOpsUser[];
}

const XamOpsPageVisibilitySection: React.FC<Props> = ({ xamopsUsers }) => {
  const {
    rules, canEdit, loading, error, savingKey, mutationError,
    refresh, setMode, addException, removeException, clearMutationError,
  } = useXamOpsPageRules();

  const [open, setOpen] = useState(false);
  const [expandedModules, setExpandedModules] = useState<Set<string>>(new Set());
  const [pending, setPending] = useState<PendingChange | null>(null);
  const [exceptionsKey, setExceptionsKey] = useState<string | null>(null);

  const ruleList = Object.values(rules);
  const hiddenCount = ruleList.filter(r => r.mode === 'HIDE').length;
  const grantedCount = ruleList.filter(r => r.mode === 'GRANT').length;

  const toggleModule = (key: string) =>
    setExpandedModules(prev => {
      const next = new Set(prev);
      if (next.has(key)) next.delete(key); else next.add(key);
      return next;
    });

  const requestChange = (key: string, to: Choice) => {
    const current = rules[key];
    const from = current?.mode ?? null;
    if (from === to) return;
    const module = XAMOPS_MODULES.find(m => m.key === key);
    clearMutationError();
    setPending({
      key,
      isModule: !!module,
      from,
      to,
      exceptionCount: current?.exceptions.length ?? 0,
      tabOverrides: module ? module.tabs.filter(t => rules[t.key]).length : 0,
    });
  };

  const confirmChange = async () => {
    if (!pending) return;
    const ok = await setMode(pending.key, pending.to);
    if (ok) setPending(null);
  };

  const pendingDescription = (p: PendingChange): React.ReactNode => {
    const label = <strong>{labelOf(p.key)}</strong>;
    const scope = p.isModule ? 'every tab in it' : 'this page';
    return (
      <span className="block space-y-2">
        {p.to === 'HIDE' && (
          <span className="block">
            {label} will be hidden for every XamOps user ({scope}): it leaves the sidebar and its URL
            shows "This page isn't available". Users you add as exceptions keep their own access.
          </span>
        )}
        {p.to === 'GRANT' && (
          <span className="block">
            {label} will be shown to every XamOps user ({scope}), including users whose own tab list
            leaves it out. Users you add as exceptions keep their own access.
          </span>
        )}
        {p.to === null && (
          <span className="block">
            {label} goes back to each user's own tab access, as set in their user settings.
          </span>
        )}
        {p.exceptionCount > 0 && (
          <span className="block">
            The {p.exceptionCount} exception{p.exceptionCount === 1 ? '' : 's'} on the current rule will be removed.
          </span>
        )}
        {p.isModule && p.to !== null && p.tabOverrides > 0 && (
          <span className="block">
            {p.tabOverrides} tab{p.tabOverrides === 1 ? ' has its' : 's have their'} own rule, which still takes priority over this one.
          </span>
        )}
        <span className="block">Takes effect on each user's next page load.</span>
        {mutationError && (
          <span className="flex items-start gap-2 p-3 bg-rose-50 border border-rose-100 rounded-lg text-xs text-rose-600">
            <Icon icon="mdi:alert-circle-outline" width={14} className="flex-shrink-0 mt-0.5" />
            <span>{mutationError}</span>
          </span>
        )}
      </span>
    );
  };

  return (
    <div className="bg-white border border-[rgba(0,0,0,0.08)] rounded-xl shadow-sm overflow-hidden">
      {/* Header */}
      <div className="flex flex-wrap items-center gap-3 px-4 py-3 border-b border-[rgba(0,0,0,0.06)]">
        <div className="w-9 h-9 rounded-xl bg-[rgba(0,0,0,0.04)] border border-[rgba(0,0,0,0.06)] flex items-center justify-center flex-shrink-0">
          <Icon icon="mdi:eye-settings-outline" width={16} color="rgba(0,0,0,0.5)" />
        </div>
        <div className="min-w-[200px]">
          <h2 className="text-sm font-bold text-[rgba(0,0,0,0.88)]">XamOps Page Visibility</h2>
          <p className="text-[11px] text-[rgba(0,0,0,0.4)] leading-tight">
            Hide a page from every XamOps user, or show it to all of them. Applies on each user's next page load.
          </p>
        </div>
        <div className="flex items-center gap-2 ml-auto">
          {!loading && !error && (
            <>
              {hiddenCount > 0 && <RulePill mode="HIDE" text={`${hiddenCount} hidden`} />}
              {grantedCount > 0 && <RulePill mode="GRANT" text={`${grantedCount} shown to all`} />}
              {hiddenCount === 0 && grantedCount === 0 && (
                <span className="text-[10px] font-semibold text-[rgba(0,0,0,0.35)] uppercase tracking-wider">No rules</span>
              )}
            </>
          )}
          <button
            type="button"
            onClick={() => { void refresh(); }}
            disabled={loading}
            aria-label="Refresh page visibility rules"
            className="flex items-center gap-1.5 px-2.5 py-1.5 rounded-lg text-[10px] font-bold uppercase tracking-widest border border-[rgba(0,0,0,0.08)] bg-white text-[rgba(0,0,0,0.5)] hover:bg-[rgba(0,0,0,0.03)] disabled:opacity-50 transition-colors"
          >
            <Icon icon="mdi:refresh" width={11} className={loading ? 'animate-spin' : undefined} />
            Refresh
          </button>
          <button
            type="button"
            onClick={() => setOpen(o => !o)}
            aria-expanded={open}
            className="flex items-center gap-1.5 px-2.5 py-1.5 rounded-lg text-[10px] font-bold uppercase tracking-widest bg-[rgba(0,0,0,0.88)] hover:bg-black text-white transition-colors"
          >
            <Icon icon={open ? 'mdi:chevron-up' : 'mdi:chevron-down'} width={12} />
            {open ? 'Close' : 'Manage'}
          </button>
        </div>
      </div>

      {error && (
        <div className="flex items-center gap-3 m-4 p-3 bg-rose-50 border border-rose-100 rounded-xl text-xs text-rose-600">
          <Icon icon="mdi:alert-circle-outline" width={15} className="flex-shrink-0" />
          {error}
        </div>
      )}

      {open && !error && (
        loading ? (
          <div className="flex flex-col items-center justify-center py-10 gap-3">
            <Icon icon="mdi:loading" width={24} color="rgba(0,0,0,0.25)" className="animate-spin" />
            <p className="text-xs text-[rgba(0,0,0,0.4)]">Loading rules…</p>
          </div>
        ) : (
          <div className="p-4 space-y-3">
            {!canEdit && (
              <div className="flex items-start gap-2 p-3 bg-amber-50 border border-amber-200 rounded-lg text-xs text-amber-800">
                <Icon icon="mdi:lock-outline" width={14} className="flex-shrink-0 mt-0.5" />
                <span>
                  Read only. These rules affect every customer, so only admins with access to every
                  account can change them.
                </span>
              </div>
            )}
            <p className="text-[11px] text-[rgba(0,0,0,0.45)]">
              A rule on a tab wins over the rule on its module. <strong>Default</strong> means each
              user's own tab access (set when editing the user) decides.
            </p>

            {XAMOPS_MODULES.map(mod => {
              const moduleRule = rules[mod.key];
              const expanded = expandedModules.has(mod.key);
              const tabRuleCount = mod.tabs.filter(t => rules[t.key]).length;
              return (
                <div key={mod.key} className="border border-[rgba(0,0,0,0.08)] rounded-xl overflow-hidden">
                  <div className="flex flex-wrap items-center gap-2.5 px-3 py-2.5 bg-[rgba(0,0,0,0.025)]">
                    <button
                      type="button"
                      onClick={() => toggleModule(mod.key)}
                      aria-expanded={expanded}
                      aria-label={`${expanded ? 'Collapse' : 'Expand'} ${mod.label} tabs`}
                      className="flex items-center gap-2 min-w-[160px] text-left"
                    >
                      <Icon icon={expanded ? 'mdi:chevron-down' : 'mdi:chevron-right'} width={14} color="rgba(0,0,0,0.45)" />
                      <Icon icon={mod.icon} width={14} color="rgba(0,0,0,0.6)" />
                      <span className="text-xs font-bold text-[rgba(0,0,0,0.8)]">{mod.label}</span>
                      {tabRuleCount > 0 && (
                        <span className="text-[10px] font-semibold text-[rgba(0,0,0,0.4)]">
                          · {tabRuleCount} tab rule{tabRuleCount === 1 ? '' : 's'}
                        </span>
                      )}
                    </button>
                    <div className="flex items-center gap-2 ml-auto">
                      <ExceptionsButton rule={moduleRule} onOpen={() => setExceptionsKey(mod.key)} />
                      <ModeControl
                        value={moduleRule?.mode ?? null}
                        disabled={!canEdit || savingKey === mod.key}
                        onChange={to => requestChange(mod.key, to)}
                        label={mod.label}
                      />
                    </div>
                  </div>

                  {expanded && (
                    <div className="divide-y divide-[rgba(0,0,0,0.04)]">
                      {mod.tabs.map(tab => {
                        const tabRule = rules[tab.key];
                        return (
                          <div key={tab.key} className="flex flex-wrap items-center gap-2.5 px-3 py-2 pl-10">
                            <Icon icon={tab.icon} width={13} color="rgba(0,0,0,0.45)" />
                            <span className="text-[11px] font-medium text-[rgba(0,0,0,0.7)]">{tab.label}</span>
                            {!tabRule && moduleRule && (
                              <span className="text-[10px] text-[rgba(0,0,0,0.4)]">
                                Inherits “{MODE_PILL[moduleRule.mode].label}” from {mod.label}
                              </span>
                            )}
                            <div className="flex items-center gap-2 ml-auto">
                              <ExceptionsButton rule={tabRule} onOpen={() => setExceptionsKey(tab.key)} />
                              <ModeControl
                                value={tabRule?.mode ?? null}
                                disabled={!canEdit || savingKey === tab.key}
                                onChange={to => requestChange(tab.key, to)}
                                label={tab.label}
                                compact
                              />
                            </div>
                          </div>
                        );
                      })}
                    </div>
                  )}
                </div>
              );
            })}
          </div>
        )
      )}

      <AlertDialog
        isOpen={!!pending}
        title={
          pending?.to === 'HIDE' ? `Hide ${pending ? labelOf(pending.key) : ''} for everyone?`
          : pending?.to === 'GRANT' ? `Show ${pending ? labelOf(pending.key) : ''} to everyone?`
          : `Reset ${pending ? labelOf(pending.key) : ''} to default?`
        }
        variant={pending?.to === 'HIDE' ? 'danger' : pending?.to === 'GRANT' ? 'warning' : 'info'}
        confirmLabel={savingKey && pending && savingKey === pending.key ? 'Saving…' : 'Confirm'}
        confirmDisabled={!!pending && savingKey === pending.key}
        onConfirm={() => { void confirmChange(); }}
        onCancel={() => { clearMutationError(); setPending(null); }}
        description={pending ? pendingDescription(pending) : undefined}
      />

      <ExceptionsDialog
        tabKey={exceptionsKey}
        rule={exceptionsKey ? rules[exceptionsKey] : undefined}
        xamopsUsers={xamopsUsers}
        canEdit={canEdit}
        saving={!!exceptionsKey && savingKey === exceptionsKey}
        error={mutationError}
        onAdd={userId => exceptionsKey ? addException(exceptionsKey, userId) : Promise.resolve(false)}
        onRemove={userId => exceptionsKey ? removeException(exceptionsKey, userId) : Promise.resolve(false)}
        onClose={() => { clearMutationError(); setExceptionsKey(null); }}
      />

      {/* Save failures outside the two dialogs (they render their own inline). */}
      {mutationError && !pending && !exceptionsKey && (
        <div className="flex items-center gap-3 mx-4 mb-4 p-3 bg-rose-50 border border-rose-100 rounded-xl text-xs text-rose-600">
          <Icon icon="mdi:alert-circle-outline" width={15} className="flex-shrink-0" />
          <span className="flex-1">{mutationError}</span>
          <button type="button" onClick={clearMutationError} aria-label="Dismiss" className="text-rose-400 hover:text-rose-600">
            <Icon icon="mdi:close" width={14} />
          </button>
        </div>
      )}
    </div>
  );
};

// ── Pieces ──────────────────────────────────────────────────────────────────

const RulePill: React.FC<{ mode: XamPageRuleMode; text: string }> = ({ mode, text }) => (
  <span className={`inline-flex items-center gap-1 px-2 py-0.5 rounded-full border text-[10px] font-bold uppercase tracking-wider ${MODE_PILL[mode].className}`}>
    <Icon icon={MODE_PILL[mode].icon} width={11} />
    {text}
  </span>
);

const ModeControl: React.FC<{
  value: Choice;
  disabled: boolean;
  onChange: (to: Choice) => void;
  label: string;
  compact?: boolean;
}> = ({ value, disabled, onChange, label, compact }) => (
  <div role="radiogroup" aria-label={`${label} visibility`} className="inline-flex rounded-lg border border-[rgba(0,0,0,0.1)] bg-white overflow-hidden">
    {CHOICES.map(choice => {
      const active = value === choice.value;
      const activeClass = choice.value === 'HIDE'
        ? 'bg-rose-600 text-white'
        : choice.value === 'GRANT'
          ? 'bg-emerald-600 text-white'
          : 'bg-[rgba(0,0,0,0.88)] text-white';
      return (
        <button
          key={choice.label}
          type="button"
          role="radio"
          aria-checked={active}
          disabled={disabled}
          onClick={() => onChange(choice.value)}
          title={choice.label}
          className={`flex items-center gap-1 ${compact ? 'px-2 py-1' : 'px-2.5 py-1.5'} text-[10px] font-bold uppercase tracking-wider border-r last:border-r-0 border-[rgba(0,0,0,0.08)] transition-colors disabled:cursor-not-allowed ${
            active ? activeClass : 'text-[rgba(0,0,0,0.5)] hover:bg-[rgba(0,0,0,0.03)] disabled:opacity-50'
          }`}
        >
          <Icon icon={choice.icon} width={11} />
          {choice.label}
        </button>
      );
    })}
  </div>
);

const ExceptionsButton: React.FC<{ rule: XamPageRule | undefined; onOpen: () => void }> = ({ rule, onOpen }) => {
  if (!rule) return null;
  return (
    <button
      type="button"
      onClick={onOpen}
      className="flex items-center gap-1 px-2 py-1 rounded-lg text-[10px] font-bold uppercase tracking-wider border border-[rgba(0,0,0,0.1)] bg-white text-[rgba(0,0,0,0.6)] hover:bg-[rgba(0,0,0,0.03)] transition-colors"
    >
      <Icon icon="mdi:account-multiple-check-outline" width={11} />
      Exceptions ({rule.exceptions.length})
    </button>
  );
};

const ExceptionsDialog: React.FC<{
  tabKey: string | null;
  rule: XamPageRule | undefined;
  xamopsUsers: BillOpsUser[];
  canEdit: boolean;
  saving: boolean;
  error: string | null;
  onAdd: (userId: number) => Promise<boolean>;
  onRemove: (userId: number) => Promise<boolean>;
  onClose: () => void;
}> = ({ tabKey, rule, xamopsUsers, canEdit, saving, error, onAdd, onRemove, onClose }) => {
  const [query, setQuery] = useState('');

  const listedIds = useMemo(
    () => new Set((rule?.exceptions ?? []).map(e => String(e.userId))),
    [rule],
  );

  const results = useMemo(() => {
    const q = query.trim().toLowerCase();
    if (!q) return [];
    return xamopsUsers
      .filter(u => !listedIds.has(String(u.id)))
      .filter(u =>
        [u.username, u.email, u.clientName].some(v => (v ?? '').toLowerCase().includes(q)))
      .slice(0, MAX_SEARCH_RESULTS);
  }, [query, xamopsUsers, listedIds]);

  const close = () => { setQuery(''); onClose(); };

  const label = tabKey ? labelOf(tabKey) : '';
  const explanation = rule?.mode === 'HIDE'
    ? `These users still see ${label}, according to their own tab access. Everyone else has it hidden.`
    : `These users don't get ${label} from this rule; their own tab access decides. Everyone else sees it.`;

  return (
    <DialogRoot isOpen={!!tabKey && !!rule} onClose={close}>
      <DialogPanel
        title={`Exceptions · ${label}`}
        onClose={close}
        footer={
          <button
            type="button"
            onClick={close}
            className="px-4 py-2 rounded-xl text-xs font-bold bg-[rgba(0,0,0,0.88)] hover:bg-black text-white transition-colors"
          >
            Done
          </button>
        }
      >
        {rule && (
          <div className="space-y-4">
            <div className="flex items-center gap-2">
              <RulePill mode={rule.mode} text={MODE_PILL[rule.mode].label} />
              {saving && <Icon icon="mdi:loading" width={13} color="rgba(0,0,0,0.3)" className="animate-spin" aria-hidden="true" />}
            </div>
            <p className="text-xs text-[rgba(0,0,0,0.6)]">{explanation}</p>

            {error && (
              <div className="flex items-start gap-2 p-3 bg-rose-50 border border-rose-100 rounded-lg text-xs text-rose-600">
                <Icon icon="mdi:alert-circle-outline" width={14} className="flex-shrink-0 mt-0.5" />
                <span>{error}</span>
              </div>
            )}

            {/* Current exceptions */}
            <div>
              <p className="text-[10px] font-semibold text-[rgba(0,0,0,0.5)] uppercase tracking-wider mb-1.5">
                Current exceptions ({rule.exceptions.length})
              </p>
              {rule.exceptions.length === 0 ? (
                <p className="text-[11px] text-[rgba(0,0,0,0.4)]">None. The rule applies to every XamOps user.</p>
              ) : (
                <div className="space-y-1.5 max-h-[200px] overflow-y-auto pr-1">
                  {rule.exceptions.map(e => (
                    <div key={e.userId} className="flex items-center gap-2 px-2.5 py-2 border border-[rgba(0,0,0,0.06)] rounded-lg">
                      <Icon icon="mdi:account-outline" width={14} color="rgba(0,0,0,0.45)" />
                      <span className="flex flex-col min-w-0 flex-1">
                        <span className="text-[11px] font-medium text-[rgba(0,0,0,0.75)] truncate">{e.username}</span>
                        <span className="text-[10px] text-[rgba(0,0,0,0.4)] truncate">
                          {[e.email, e.clientName].filter(Boolean).join(' · ')}
                        </span>
                      </span>
                      {canEdit && (
                        <button
                          type="button"
                          onClick={() => { void onRemove(e.userId); }}
                          disabled={saving}
                          aria-label={`Remove ${e.username} from exceptions`}
                          className="flex items-center gap-1 px-2 py-1 rounded-md text-[10px] font-bold uppercase tracking-wider text-rose-600 border border-rose-200 bg-white hover:bg-rose-50 disabled:opacity-50 transition-colors"
                        >
                          <Icon icon="mdi:close" width={11} />
                          Remove
                        </button>
                      )}
                    </div>
                  ))}
                </div>
              )}
            </div>

            {/* Search & add */}
            {canEdit && (
              <div>
                <label htmlFor="xam-page-exception-search" className="block text-[10px] font-semibold text-[rgba(0,0,0,0.5)] uppercase tracking-wider mb-1.5">
                  Add a XamOps user
                </label>
                <div className="relative">
                  <Icon icon="mdi:magnify" width={14} color="rgba(0,0,0,0.35)" className="absolute left-2.5 top-1/2 -translate-y-1/2" />
                  <input
                    id="xam-page-exception-search"
                    type="search"
                    value={query}
                    onChange={ev => setQuery(ev.target.value)}
                    placeholder="Search by username, email or client…"
                    className="w-full pl-8 pr-3 py-2 text-xs bg-[rgba(0,0,0,0.03)] border border-[rgba(0,0,0,0.08)] rounded-lg text-[rgba(0,0,0,0.88)] placeholder-[rgba(0,0,0,0.3)] focus:outline-none focus:ring-1 focus:ring-[rgba(0,0,0,0.2)] transition-colors"
                  />
                </div>
                {query.trim() && (
                  results.length === 0 ? (
                    <p className="mt-2 text-[11px] text-[rgba(0,0,0,0.4)]">No XamOps users match "{query.trim()}".</p>
                  ) : (
                    <div className="mt-2 space-y-1.5 max-h-[220px] overflow-y-auto pr-1">
                      {results.map(u => (
                        <div key={u.id} className="flex items-center gap-2 px-2.5 py-2 border border-[rgba(0,0,0,0.06)] rounded-lg">
                          <Icon icon="mdi:account-outline" width={14} color="rgba(0,0,0,0.45)" />
                          <span className="flex flex-col min-w-0 flex-1">
                            <span className="text-[11px] font-medium text-[rgba(0,0,0,0.75)] truncate">{u.username}</span>
                            <span className="text-[10px] text-[rgba(0,0,0,0.4)] truncate">
                              {[u.email, u.clientName].filter(Boolean).join(' · ')}
                            </span>
                          </span>
                          <button
                            type="button"
                            onClick={() => { void onAdd(Number(u.id)); }}
                            disabled={saving}
                            className="flex items-center gap-1 px-2 py-1 rounded-md text-[10px] font-bold uppercase tracking-wider bg-[rgba(0,0,0,0.88)] hover:bg-black text-white disabled:opacity-50 transition-colors"
                          >
                            <Icon icon="mdi:plus" width={11} />
                            Add
                          </button>
                        </div>
                      ))}
                    </div>
                  )
                )}
              </div>
            )}
          </div>
        )}
      </DialogPanel>
    </DialogRoot>
  );
};

export default XamOpsPageVisibilitySection;
