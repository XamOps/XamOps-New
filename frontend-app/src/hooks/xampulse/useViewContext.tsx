import React, { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { useSearchParams } from 'react-router-dom';
import type { XpScope } from '../../types/xampulse';
import { parseCompare, type CompareMode } from '../../utils/xampulse/compare';
import { isRangePreset, rangeMs, type RangePreset } from '../../utils/xampulse/format';
import { useXpAccounts } from './useXamPulseQueries';

/**
 * The shared view context (accounts, range, query, live, compare). It lives in the URL so links and refreshes
 * keep it. Keys: accounts, region, range (preset) or from/to (absolute ISO), live, q, compare.
 */
export interface ViewState {
  accountIds: number[];
  region: string | null;
  range: RangePreset | null;
  absFrom: string | null;
  absTo: string | null;
  live: boolean;
  q: string;
  compare: CompareMode;
}

export const DEFAULT_RANGE: RangePreset = '1h';

export function parseViewState(sp: URLSearchParams): ViewState {
  const accounts = (sp.get('accounts') ?? '').split(',').map((s) => Number(s)).filter((n) => Number.isInteger(n) && n > 0);
  const from = sp.get('from');
  const to = sp.get('to');
  const absolute = !!from && !!to && !Number.isNaN(Date.parse(from)) && !Number.isNaN(Date.parse(to)) && Date.parse(to) > Date.parse(from);
  const rangeParam = sp.get('range');
  return {
    accountIds: accounts.slice(0, 20),
    region: sp.get('region'),
    range: absolute ? null : isRangePreset(rangeParam) ? rangeParam : DEFAULT_RANGE,
    absFrom: absolute ? from : null,
    absTo: absolute ? to : null,
    live: sp.get('live') === '1',
    q: sp.get('q') ?? '',
    compare: parseCompare(sp.get('compare')),
  };
}

export function serializeViewState(s: Partial<ViewState>, base?: URLSearchParams): URLSearchParams {
  const sp = new URLSearchParams(base);
  const setOrDelete = (k: string, v: string | null) => { if (v) sp.set(k, v); else sp.delete(k); };
  if ('accountIds' in s) setOrDelete('accounts', s.accountIds && s.accountIds.length ? s.accountIds.join(',') : null);
  if ('region' in s) setOrDelete('region', s.region ?? null);
  if ('range' in s || 'absFrom' in s || 'absTo' in s) {
    if (s.absFrom && s.absTo) {
      sp.set('from', s.absFrom); sp.set('to', s.absTo); sp.delete('range');
    } else {
      sp.delete('from'); sp.delete('to');
      if (s.range) sp.set('range', s.range);
    }
  }
  if ('live' in s) setOrDelete('live', s.live ? '1' : null);
  if ('q' in s) setOrDelete('q', s.q ? s.q : null);
  if ('compare' in s) setOrDelete('compare', s.compare && s.compare !== 'off' ? s.compare : null);
  return sp;
}

export interface ViewContextValue extends ViewState {
  /** Resolved accounts: URL selection, else the first account. Empty while accounts are loading. */
  effectiveAccountIds: number[];
  from: string;
  to: string;
  fromMs: number;
  toMs: number;
  scope: XpScope;
  update: (patch: Partial<ViewState>) => void;
  refresh: () => void;
  /** Builds an in-app link that keeps the context (everything except q, unless given). */
  link: (path: string, extra?: Partial<ViewState>) => string;
}

const Ctx = createContext<ViewContextValue | null>(null);

/** "now" snapped to the minute so query keys stay stable between renders. */
const snap = (ms: number): number => Math.floor(ms / 60_000) * 60_000;

export const ViewContextProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [sp, setSp] = useSearchParams();
  const state = useMemo(() => parseViewState(sp), [sp]);
  const accounts = useXpAccounts();
  const queryClient = useQueryClient();
  const [anchor, setAnchor] = useState<number>(() => snap(Date.now()));

  useEffect(() => {
    if (state.absFrom) return undefined;
    const id = window.setInterval(() => setAnchor(snap(Date.now())), 60_000);
    return () => window.clearInterval(id);
  }, [state.absFrom]);

  const update = useCallback((patch: Partial<ViewState>) => {
    setSp((prev) => serializeViewState(patch, prev), { replace: true });
    if ('range' in patch) setAnchor(snap(Date.now()));
  }, [setSp]);

  const refresh = useCallback(() => {
    setAnchor(Date.now());
    void queryClient.invalidateQueries({ queryKey: ['xp'] });
  }, [queryClient]);

  const effectiveAccountIds = useMemo(() => {
    if (state.accountIds.length) return state.accountIds;
    const first = accounts.data?.[0];
    return first ? [first.cloudAccountId] : [];
  }, [state.accountIds, accounts.data]);

  const value = useMemo<ViewContextValue>(() => {
    const toMs = state.absTo ? Date.parse(state.absTo) : anchor;
    const fromMs = state.absFrom ? Date.parse(state.absFrom) : toMs - rangeMs(state.range ?? DEFAULT_RANGE);
    const from = new Date(fromMs).toISOString();
    const to = new Date(toMs).toISOString();
    const scope: XpScope = { accountIds: effectiveAccountIds, from, to, ...(state.region ? { regions: [state.region] } : {}) };
    const link = (path: string, extra?: Partial<ViewState>): string => {
      const [pathname, own] = path.split('?');
      const merged = serializeViewState({}, sp);
      merged.delete('q');
      // Parameters already on the target (for example q and range from an evidence link) win over the current context.
      new URLSearchParams(own ?? '').forEach((v, k) => { merged.set(k, v); });
      if (new URLSearchParams(own ?? '').has('range')) { merged.delete('from'); merged.delete('to'); }
      const result = extra ? serializeViewState(extra, merged) : merged;
      const qs = result.toString();
      return qs ? `${pathname}?${qs}` : pathname;
    };
    return { ...state, effectiveAccountIds, from, to, fromMs, toMs, scope, update, refresh, link };
  }, [state, anchor, effectiveAccountIds, update, refresh, sp]);

  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
};

export function useViewContext(): ViewContextValue {
  const v = useContext(Ctx);
  if (!v) throw new Error('useViewContext must be used inside ViewContextProvider');
  return v;
}
