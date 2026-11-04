/**
 * Switch cluster, in place (Phase A design §3). Lives in the topbar next to the cluster name.
 *
 * Every entry is a real link to `/eks-details?…` built by `clusterSwitchHref`, so a click is a normal
 * navigation (the page remounts with the new cluster and its own capabilities), middle-click and
 * copy-link work, and Back returns to the previous cluster. "All clusters" is the fleet list, not an
 * aggregate view. The list comes from the same read the fleet page uses; a cluster the page would
 * reject as an invalid link is never offered.
 */
import { useEffect, useId, useMemo, useRef, useState, type KeyboardEvent } from 'react';
import { Icon } from '@iconify/react';
import { Link, useLocation } from 'react-router-dom';
import { useK8sClusters } from '../../hooks/useK8sClusters';
import { clusterSwitchHref } from '../../utils/k8sClusterLink';

export interface ClusterSwitcherProps {
  accountId: string;
  clusterName: string;
  region: string;
  version?: string | null;
}

export const SWITCHER_LABEL = 'Switch cluster';
export const ALL_CLUSTERS_TEXT = 'All clusters';
/** Past this many entries the panel offers a local filter. */
const FILTER_THRESHOLD = 8;
const FILTER_MAX_LENGTH = 100;

const focusRing = 'outline-none focus-visible:ring-2 focus-visible:ring-[rgba(0,0,0,0.88)] focus-visible:ring-offset-2';
const itemClass = `flex items-baseline gap-2 rounded-md px-3 py-2 text-[13px] text-[rgba(0,0,0,0.88)] hover:bg-[rgba(0,0,0,0.04)] ${focusRing}`;

interface Entry {
  name: string;
  region: string;
  version?: string | null;
  connected?: boolean;
  current: boolean;
}

export function ClusterSwitcher({ accountId, clusterName, region, version }: ClusterSwitcherProps) {
  const [open, setOpen] = useState(false);
  const [filter, setFilter] = useState('');
  const { status, clusters, reload } = useK8sClusters(accountId);
  const location = useLocation();
  const panelId = useId();
  const rootRef = useRef<HTMLDivElement>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const panelRef = useRef<HTMLDivElement>(null);

  // The current cluster is always listed, even when the list lacks it (stale cache, failed read).
  const entries = useMemo<Entry[]>(() => {
    const listed: Entry[] = clusters.map((c) => ({
      name: c.name, region: c.region, version: c.version, connected: c.connected,
      current: c.name === clusterName && c.region === region,
    }));
    if (!listed.some((e) => e.current)) listed.unshift({ name: clusterName, region, version, current: true });
    return listed;
  }, [clusters, clusterName, region, version]);

  const term = filter.trim().toLowerCase();
  const visible = term ? entries.filter((e) => e.name.toLowerCase().includes(term)) : entries;

  // Close on a route change.
  useEffect(() => { setOpen(false); }, [location.key]);

  // Close on an outside press.
  useEffect(() => {
    if (!open) return undefined;
    const onPointerDown = (event: Event) => {
      if (rootRef.current && event.target instanceof Node && !rootRef.current.contains(event.target)) setOpen(false);
    };
    document.addEventListener('pointerdown', onPointerDown);
    return () => document.removeEventListener('pointerdown', onPointerDown);
  }, [open]);

  const items = (): HTMLElement[] =>
    Array.from(panelRef.current?.querySelectorAll<HTMLElement>('[data-switcher-item]') ?? []);

  const close = (returnFocus: boolean) => {
    setOpen(false);
    if (returnFocus) triggerRef.current?.focus();
  };

  const onKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    if (event.key === 'Escape' && open) {
      event.preventDefault();
      close(true);
      return;
    }
    if (!open || !['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) return;
    const list = items();
    if (list.length === 0) return;
    event.preventDefault();
    const at = list.indexOf(document.activeElement as HTMLElement);
    let next = 0;
    if (event.key === 'ArrowDown') next = at < 0 ? 0 : (at + 1) % list.length;
    else if (event.key === 'ArrowUp') next = at <= 0 ? list.length - 1 : at - 1;
    else if (event.key === 'End') next = list.length - 1;
    list[next].focus();
  };

  return (
    <div ref={rootRef} className="relative" onKeyDown={onKeyDown}>
      <button
        ref={triggerRef}
        type="button"
        aria-expanded={open}
        aria-controls={panelId}
        aria-label={SWITCHER_LABEL}
        onClick={() => setOpen((v) => !v)}
        className={`flex items-center gap-1.5 rounded-lg border border-[rgba(0,0,0,0.12)] bg-white px-2.5 py-1.5 text-xs font-semibold text-[rgba(0,0,0,0.6)] transition-colors hover:border-[rgba(0,0,0,0.25)] hover:text-[rgba(0,0,0,0.88)] ${focusRing}`}
      >
        <Icon icon="mdi:swap-horizontal" width={14} aria-hidden="true" />
        <span aria-hidden="true">Switch</span>
      </button>

      {open && (
        <div
          id={panelId}
          ref={panelRef}
          role="group"
          aria-label="Clusters"
          aria-busy={status === 'loading' || undefined}
          className="absolute left-0 top-full z-50 mt-1 w-72 rounded-lg border border-[rgba(0,0,0,0.12)] bg-white p-1 shadow-[0_8px_24px_rgba(0,0,0,0.12)]"
        >
          <Link
            to={`/cloudk8s?accountIds=${encodeURIComponent(accountId)}`}
            data-switcher-item=""
            className={itemClass}
          >
            <Icon icon="mdi:view-grid-outline" width={14} aria-hidden="true" className="self-center" />
            <span className="font-semibold">{ALL_CLUSTERS_TEXT}</span>
          </Link>
          <div className="my-1 border-t border-[rgba(0,0,0,0.08)]" role="separator" />

          {status === 'loading' && (
            <p role="status" className="px-3 py-2 text-[12px] text-[rgba(0,0,0,0.6)]">Loading clusters…</p>
          )}
          {status === 'error' && (
            <div className="flex items-center justify-between gap-2 px-3 py-2 text-[12px] text-[rgba(0,0,0,0.6)]">
              <span role="alert">Couldn&apos;t load the cluster list.</span>
              <button type="button" onClick={reload} className={`rounded-md px-2 py-1 font-semibold text-[rgba(0,0,0,0.88)] underline ${focusRing}`}>
                Retry
              </button>
            </div>
          )}

          {entries.length > FILTER_THRESHOLD && (
            <div className="px-2 pb-1">
              <input
                type="text"
                value={filter}
                maxLength={FILTER_MAX_LENGTH}
                onChange={(e) => setFilter(e.target.value.slice(0, FILTER_MAX_LENGTH))}
                aria-label="Filter clusters"
                placeholder="Filter clusters"
                className={`w-full rounded-md border border-[rgba(0,0,0,0.12)] px-2 py-1.5 text-[13px] ${focusRing}`}
              />
            </div>
          )}

          <ul className="max-h-72 overflow-auto">
            {visible.map((e) => (
              <li key={`${e.name}|${e.region}`}>
                <Link
                  to={clusterSwitchHref(location.search, e, accountId)}
                  data-switcher-item=""
                  aria-current={e.current ? 'true' : undefined}
                  className={itemClass}
                >
                  <span className={e.current ? 'font-semibold' : ''}>{e.name}</span>
                  <span className="text-[12px] text-[rgba(0,0,0,0.6)]">{e.region}</span>
                  {e.connected === false && <span className="text-[12px] text-[rgba(0,0,0,0.6)]">Not connected</span>}
                  {e.current && <span className="ml-auto text-[12px] text-[rgba(0,0,0,0.6)]">Current</span>}
                </Link>
              </li>
            ))}
          </ul>
          {visible.length === 0 && (
            <p className="px-3 py-2 text-[12px] text-[rgba(0,0,0,0.6)]">No cluster matches.</p>
          )}
        </div>
      )}
    </div>
  );
}

export default ClusterSwitcher;
