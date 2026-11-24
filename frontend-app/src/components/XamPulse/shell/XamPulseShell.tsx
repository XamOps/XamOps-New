import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { Bell, Boxes, ChartLine, Command, Gauge, Keyboard, Lightbulb, RefreshCw, ScrollText, Server, Settings2, X, type LucideIcon } from 'lucide-react';
import { XAMPULSE_TABS, type XamPulseTab } from '../../../constants/xampulseTabs';
import { useViewContext } from '../../../hooks/xampulse/useViewContext';
import { useOverview, useXpAccounts } from '../../../hooks/xampulse/useXamPulseQueries';
import { resolveTheme, setPrefs, usePrefs } from '../../../hooks/xampulse/prefs';
import { RANGE_PRESETS, formatAgo } from '../../../utils/xampulse/format';
import { DemoPill, Modal } from '../ui/ui';
import { GlobalGate } from './GlobalGate';
import { PulsePalette } from './PulsePalette';
import { useXamTabAccess } from './useXamTabAccess';

/** The PulseIndicator is a heartbeat line driven by real data freshness. */
const PulseIndicator: React.FC<{ lastDataAt: string | null | undefined }> = ({ lastDataAt }) => {
  const age = lastDataAt ? Date.now() - Date.parse(lastDataAt) : null;
  const state = age === null ? 'none' : age < 120_000 ? 'live' : 'stale';
  const text = state === 'live' ? 'Receiving data' : state === 'stale' ? `Last data ${formatAgo(lastDataAt)}` : 'No data yet';
  return (
    <span className="xp-row" style={{ gap: 8, flexWrap: 'nowrap' }} role="status" aria-label={text}>
      <svg className="xp-pulse" data-state={state} viewBox="0 0 64 24" aria-hidden="true"><path d="M0 12 H18 L23 3 L30 21 L36 8 L40 12 H64" /></svg>
      <span className="xp-pulse-text">{text}</span>
    </span>
  );
};

const TAB_ICON: Record<XamPulseTab, LucideIcon> = {
  overview: Gauge, hosts: Server, containers: Boxes, logs: ScrollText, metrics: ChartLine, alerts: Bell, insights: Lightbulb, setup: Settings2,
};

const SHORTCUTS: [string, string][] = [
  ['g then o / h / c / l / m / a / i / s', 'Go to Mission Control, Hosts, Containers, Logs, Metrics, Alerts, Insights, Setup'],
  ['/', 'Focus the log query bar'],
  ['p', 'Pause or resume live tail'],
  ['j / k, Enter', 'Move through log rows and open one'],
  ['Ctrl/Cmd + Shift + K', 'Open XamPulse actions'],
  ['Ctrl/Cmd + K', 'Open the global page search'],
  ['?', 'Show this list'],
  ['Esc', 'Close panels'],
];
const GO_KEYS: Record<string, XamPulseTab> = { o: 'overview', h: 'hosts', c: 'containers', l: 'logs', m: 'metrics', a: 'alerts', i: 'insights', s: 'setup' };

export const XamPulseShell: React.FC<{ tab: XamPulseTab | 'host'; children: React.ReactNode }> = ({ tab, children }) => {
  const ctx = useViewContext();
  const prefs = usePrefs();
  const navigate = useNavigate();
  const accounts = useXpAccounts();
  const overview = useOverview(ctx.scope);
  const { isTabAllowed } = useXamTabAccess();
  const [paletteOpen, setPaletteOpen] = useState(false);
  const [sheetOpen, setSheetOpen] = useState(false);
  const goPending = useRef(false);
  const theme = resolveTheme(prefs.theme);

  const anyDemo = (accounts.data ?? []).some((a) => ctx.effectiveAccountIds.includes(a.cloudAccountId) && a.demo) || overview.data?.demo === true;

  const onKey = useCallback((e: KeyboardEvent) => {
    const el = e.target as HTMLElement | null;
    const typing = !!el && (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA' || el.tagName === 'SELECT' || el.isContentEditable);
    if ((e.metaKey || e.ctrlKey) && e.shiftKey && e.key.toLowerCase() === 'k') { e.preventDefault(); setPaletteOpen((o) => !o); return; }
    if (typing || e.metaKey || e.ctrlKey || e.altKey) return;
    if (goPending.current) {
      goPending.current = false;
      const dest = GO_KEYS[e.key];
      if (dest) { e.preventDefault(); navigate(ctx.link(`/xampulse/${dest}`)); }
      return;
    }
    if (e.key === 'g') { goPending.current = true; window.setTimeout(() => { goPending.current = false; }, 1000); }
    else if (e.key === '?') { e.preventDefault(); setSheetOpen(true); }
    else if (e.key === '/') {
      const q = document.querySelector<HTMLElement>('[data-xp-query]');
      if (q) { e.preventDefault(); q.focus(); }
    }
  }, [navigate, ctx]);
  useEffect(() => { document.addEventListener('keydown', onKey); return () => document.removeEventListener('keydown', onKey); }, [onKey]);

  const accountValue = ctx.accountIds.length > 1 ? 'all' : String(ctx.effectiveAccountIds[0] ?? '');
  const list = accounts.data ?? [];

  return (
    <div className="xp-root xp-shell" data-theme={theme} data-density={prefs.density}>
      <a className="xp-skip" href="#xp-main">Skip to content</a>
      {anyDemo && (
        <div className="xp-demo" role="note"><span className="xp-dot" aria-hidden="true" />DEMO DATA: generated sample data, not from your AWS account.</div>
      )}
      <header className="xp-ctx">
        <span className="xp-brand">XamPulse {anyDemo && <DemoPill />}<span className="xp-brand__sep" aria-hidden="true" /><PulseIndicator lastDataAt={overview.data?.lastDataAt} /></span>
        <label className="xp-sr" htmlFor="xp-account">AWS account</label>
        <select id="xp-account" className="xp-select" value={accountValue} onChange={(e) => ctx.update({ accountIds: e.target.value === 'all' ? list.map((a) => a.cloudAccountId) : [Number(e.target.value)] })}>
          {list.map((a) => <option key={a.cloudAccountId} value={a.cloudAccountId}>{a.name} ({a.awsAccountId})</option>)}
          {list.length > 1 && <option value="all">All accounts</option>}
        </select>
        <span className="xp-spacer" />
        {ctx.absFrom && (
          <button type="button" className="xp-btn xp-btn--sm" onClick={() => ctx.update({ range: '1h', absFrom: null, absTo: null })} title="Reset to a relative range">Custom range <X size={12} strokeWidth={1.75} aria-hidden="true" /></button>
        )}
        <div className="xp-seg xp-seg--mono" role="group" aria-label="Time range">
          {RANGE_PRESETS.map((r) => <button key={r} type="button" aria-pressed={ctx.range === r} onClick={() => ctx.update({ range: r, absFrom: null, absTo: null })}>{r}</button>)}
        </div>
        <button type="button" className="xp-btn xp-btn--ghost xp-btn--icon" onClick={ctx.refresh} aria-label="Refresh data" title="Refresh data"><RefreshCw size={15} strokeWidth={1.5} /></button>
        <button type="button" className="xp-btn" onClick={() => setPaletteOpen(true)}><Command size={13} strokeWidth={1.5} aria-hidden="true" />Actions <span className="xp-kbd">{'\u2318\u21E7K'}</span></button>
        <div className="xp-seg" role="group" aria-label="Theme">
          {(['system', 'light', 'dark'] as const).map((t) => <button key={t} type="button" aria-pressed={prefs.theme === t} onClick={() => setPrefs({ theme: t })}>{t === 'system' ? 'Auto' : t === 'light' ? 'Light' : 'Dark'}</button>)}
        </div>
        <div className="xp-seg" role="group" aria-label="Density">
          {(['comfortable', 'compact'] as const).map((d) => <button key={d} type="button" aria-pressed={prefs.density === d} onClick={() => setPrefs({ density: d })}>{d === 'comfortable' ? 'Roomy' : 'Compact'}</button>)}
        </div>
        <button type="button" className="xp-btn xp-btn--ghost xp-btn--icon" onClick={() => setSheetOpen(true)} aria-label="Keyboard shortcuts" title="Keyboard shortcuts (?)"><Keyboard size={16} strokeWidth={1.5} /></button>
      </header>
      <nav aria-label="XamPulse sections" className="xp-tabs">
        {XAMPULSE_TABS.filter((t) => isTabAllowed(t.accessKey)).map((t) => {
          const Icon = TAB_ICON[t.id];
          return (
            <Link key={t.id} to={ctx.link(`/xampulse/${t.id}`)} className="xp-tab" aria-current={tab === t.id || (tab === 'host' && t.id === 'hosts') ? 'page' : undefined}>
              <Icon size={14} strokeWidth={1.5} aria-hidden="true" />{t.label}
            </Link>
          );
        })}
      </nav>
      <main key={tab === 'host' ? 'hosts' : tab} id="xp-main" className="xp-main" tabIndex={-1}>
        <GlobalGate tab={tab}>{children}</GlobalGate>
      </main>
      <PulsePalette open={paletteOpen} onClose={() => setPaletteOpen(false)} />
      {sheetOpen && (
        <Modal title="Keyboard shortcuts" onClose={() => setSheetOpen(false)}>
          <dl className="xp-shortcuts">
            {SHORTCUTS.map(([k, d]) => (<React.Fragment key={k}><dt><span className="xp-kbd">{k}</span></dt><dd style={{ margin: 0 }}>{d}</dd></React.Fragment>))}
          </dl>
          <div style={{ marginTop: 20, display: 'flex', justifyContent: 'flex-end' }}><button type="button" className="xp-btn" onClick={() => setSheetOpen(false)}>Close</button></div>
        </Modal>
      )}
    </div>
  );
};
