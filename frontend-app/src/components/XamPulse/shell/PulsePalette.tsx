import React, { useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { XAMPULSE_TABS, xamPulseHostPath } from '../../../constants/xampulseTabs';
import { useViewContext } from '../../../hooks/xampulse/useViewContext';
import { useHosts } from '../../../hooks/xampulse/useXamPulseQueries';
import { setPrefs, usePrefs } from '../../../hooks/xampulse/prefs';

interface Item { id: string; label: string; hint?: string; run: () => void }

/** XamPulse actions palette (Ctrl/Cmd+Shift+K, or the Actions button). Page links also live in the global palette. */
export const PulsePalette: React.FC<{ open: boolean; onClose: () => void }> = ({ open, onClose }) => {
  const navigate = useNavigate();
  const ctx = useViewContext();
  const prefs = usePrefs();
  const hosts = useHosts(ctx.effectiveAccountIds, { size: 50, sort: 'health' });
  const [term, setTerm] = useState('');
  const [active, setActive] = useState(0);
  const inputRef = useRef<HTMLInputElement>(null);

  const items = useMemo<Item[]>(() => {
    const go = (path: string) => () => navigate(ctx.link(path));
    const list: Item[] = [
      ...XAMPULSE_TABS.map((t) => ({ id: `tab-${t.id}`, label: `Go to ${t.label}`, hint: t.blurb, run: go(`/xampulse/${t.id}`) })),
      { id: 'tail', label: 'Open live tail', hint: 'Stream logs as they arrive', run: () => navigate(ctx.link('/xampulse/logs', { live: true })) },
      { id: 'alert', label: 'Create alert', hint: 'Start a new rule', run: () => navigate(ctx.link('/xampulse/alerts'), { state: { newRule: true } }) },
      { id: 'connect', label: 'Connect an account', hint: 'One-step onboarding', run: go('/xampulse/setup') },
      { id: 'theme', label: `Switch to ${prefs.theme === 'light' ? 'dark' : 'light'} theme`, run: () => setPrefs({ theme: prefs.theme === 'light' ? 'dark' : 'light' }) },
      { id: 'density', label: `Use ${prefs.density === 'compact' ? 'comfortable' : 'compact'} density`, run: () => setPrefs({ density: prefs.density === 'compact' ? 'comfortable' : 'compact' }) },
    ];
    (hosts.data?.items ?? []).forEach((h) => list.push({ id: `host-${h.accountId}-${h.instanceId}`, label: `Jump to host ${h.name}`, hint: h.instanceId, run: () => navigate(ctx.link(xamPulseHostPath(h.instanceId, h.accountId))) }));
    return list;
  }, [navigate, ctx, prefs.theme, prefs.density, hosts.data]);

  const shown = useMemo(() => {
    const t = term.trim().toLowerCase();
    return t ? items.filter((i) => `${i.label} ${i.hint ?? ''}`.toLowerCase().includes(t)) : items;
  }, [items, term]);

  useEffect(() => { if (open) { setTerm(''); setActive(0); window.setTimeout(() => inputRef.current?.focus(), 0); } }, [open]);
  useEffect(() => { setActive(0); }, [term]);

  if (!open) return null;
  const choose = (i: Item | undefined) => { if (i) { onClose(); i.run(); } };
  return (
    <>
      <div className="xp-scrim" onClick={onClose} aria-hidden="true" />
      <div className="xp-palette" role="dialog" aria-modal="true" aria-label="XamPulse actions">
        <input ref={inputRef} className="xp-input" style={{ width: '100%', padding: '0 18px' }}
          placeholder="Type an action or a host name" aria-label="Search actions" role="combobox" aria-expanded="true" aria-controls="xp-pal-list" aria-activedescendant={shown[active] ? `xp-pal-${shown[active].id}` : undefined}
          value={term} onChange={(e) => setTerm(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'ArrowDown') { e.preventDefault(); setActive((a) => Math.min(shown.length - 1, a + 1)); }
            else if (e.key === 'ArrowUp') { e.preventDefault(); setActive((a) => Math.max(0, a - 1)); }
            else if (e.key === 'Enter') { e.preventDefault(); choose(shown[active]); }
            else if (e.key === 'Escape') { e.preventDefault(); onClose(); }
          }} />
        <ul id="xp-pal-list" role="listbox" aria-label="Actions">
          {shown.length === 0 && <li className="xp-muted" aria-selected="false" role="option">No matching actions</li>}
          {shown.slice(0, 40).map((i, idx) => (
            <li key={i.id} id={`xp-pal-${i.id}`} role="option" aria-selected={idx === active} onMouseEnter={() => setActive(idx)} onClick={() => choose(i)}>
              <span>{i.label}</span><span className="xp-muted xp-mono" style={{ fontSize: 11 }}>{i.hint}</span>
            </li>
          ))}
        </ul>
      </div>
    </>
  );
};
