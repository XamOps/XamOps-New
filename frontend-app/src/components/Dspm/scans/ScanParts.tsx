import React, { useCallback, useState } from 'react';
import { Icon } from '@iconify/react';
import type { ScanSchedule, ScanTarget } from '../../../types/dspmScans';
import { DspmProviderIcon } from '../DspmIcons';
import { driveIconKey } from '../datastores/datastoreVisuals';
import { TONE_CLASSES } from '../management/remediationModel';
import { frequencyText, lastRunText, runStatusTone } from './scanFormat';

/*
 * Pieces the Scan Management panels share: the server-paged list state, the status chip, the panel's
 * one-line description, a focusable row that opens a detail layer, the targets list and the detail
 * layers' header.
 */

export const DEFAULT_PAGE_SIZE = 10;

/**
 * The page of a server list, tied to its filters: any change of `filterKey` (search, a filter, the page
 * size) reads page 1 in the same render, so no request for the old page goes out.
 */
export function useFilteredPage(filterKey: string): [number, (page: number) => void] {
  const [paging, setPaging] = useState({ key: filterKey, page: 1 });
  const page = paging.key === filterKey ? paging.page : 1;
  const setPage = useCallback((next: number) => setPaging({ key: filterKey, page: next }), [filterKey]);
  return [page, setPage];
}

/** '' (the filter's "All") as null, so `withQuery` leaves it out. */
export const orNull = (value: string): string | null => (value === '' ? null : value);

export const Dash: React.FC = () => <span className="text-[rgba(0,0,0,0.4)]">-</span>;

export const Muted: React.FC<{ text: string; className?: string }> = ({ text, className }) => (
  <span className={`block truncate text-[11px] text-[rgba(0,0,0,0.45)] ${className ?? ''}`} title={text}>{text}</span>
);

/** A run's (or a run datastore's) status chip; `title` is its tooltip. */
export const ScanStatusChip: React.FC<{ status: string | null; label: string | null; title?: string }> = ({
  status, label, title,
}) => {
  const text = label ?? status;
  if (!text) return <Dash />;
  return (
    <span
      title={title}
      className={`inline-flex w-fit items-center whitespace-nowrap rounded border px-1.5 py-0.5 text-[10px] font-semibold ${TONE_CLASSES[runStatusTone(status)].chip}`}
    >
      {text}
    </span>
  );
};

/** The console's one-line description at the top of a panel. */
export const PanelIntro: React.FC<{ text: string }> = ({ text }) => (
  <p className="text-xs text-[rgba(0,0,0,0.55)]">{text}</p>
);

/** Row classes of a table row that opens a detail layer. */
const OPEN_ROW = 'cursor-pointer border-t border-[rgba(0,0,0,0.06)] hover:bg-[rgba(0,0,0,0.02)] focus:outline-none focus-visible:bg-[rgba(0,0,0,0.04)]';
const PLAIN_ROW = 'border-t border-[rgba(0,0,0,0.06)]';

/**
 * A table row that opens a detail layer by click or Enter, passing itself so the layer can return focus
 * to it. Without `onOpen` (a row with no id) it is a plain row.
 */
export const OpenRow: React.FC<{ onOpen?: (row: HTMLElement) => void; children: React.ReactNode }> = ({ onOpen, children }) => {
  if (!onOpen) return <tr className={PLAIN_ROW}>{children}</tr>;
  return (
    <tr
      tabIndex={0}
      onClick={e => onOpen(e.currentTarget)}
      onKeyDown={e => {
        if (e.key === 'Enter' && e.target === e.currentTarget) {
          e.preventDefault();
          onOpen(e.currentTarget);
        }
      }}
      className={OPEN_ROW}
    >
      {children}
    </tr>
  );
};

/** Body cell style of the Scan Management tables. */
export const TD = 'px-3 py-2.5 align-top text-[rgba(0,0,0,0.75)]';

/** The targets of a schedule or scope: subtype icon and name, and "Showing N of M targets" when cut. */
export const TargetsList: React.FC<{ targets: ScanTarget[]; total: number }> = ({ targets, total }) => (
  <div className="space-y-2">
    <p className="text-[11px] font-semibold uppercase tracking-wide text-[rgba(0,0,0,0.45)]">Targets</p>
    {targets.length === 0 ? (
      <p className="text-xs text-[rgba(0,0,0,0.45)]">No targets</p>
    ) : (
      <ul className="divide-y divide-[rgba(0,0,0,0.06)] rounded-xl border border-[rgba(0,0,0,0.08)]">
        {targets.map((target, i) => (
          <li key={target.id ?? `target-${i}`} className="flex items-center gap-2 px-3 py-2 text-xs">
            <DspmProviderIcon provider={driveIconKey(target.subtype)} size={16} />
            <span className="min-w-0 flex-1">
              <span className="block truncate text-[rgba(0,0,0,0.8)]" title={target.name ?? undefined}>{target.name ?? '-'}</span>
              {target.subtypeLabel && <Muted text={target.subtypeLabel} />}
            </span>
          </li>
        ))}
      </ul>
    )}
    {targets.length < total && (
      <p className="text-[11px] text-[rgba(0,0,0,0.45)]">
        Showing {targets.length.toLocaleString('en-US')} of {total.toLocaleString('en-US')} targets
      </p>
    )}
  </div>
);

/** A label and value row of a detail layer. */
export const DetailField: React.FC<{ label: string; children: React.ReactNode }> = ({ label, children }) => (
  <div className="grid grid-cols-[9rem_1fr] items-start gap-3 text-xs">
    <span className="text-[rgba(0,0,0,0.45)]">{label}</span>
    <span className="min-w-0 text-[rgba(0,0,0,0.8)]">{children}</span>
  </div>
);

/** The header of a detail layer: title, subtitle and the close X (focused on open). */
export const DetailHeader: React.FC<{
  title: string;
  subtitle?: string | null;
  closeButton: React.RefObject<HTMLButtonElement | null>;
  onClose: () => void;
}> = ({ title, subtitle, closeButton, onClose }) => (
  <div className="flex items-start gap-3 border-b border-[rgba(0,0,0,0.06)] px-5 pb-3 pt-4">
    <div className="min-w-0 flex-1">
      <h2 className="truncate text-base font-bold text-[rgba(0,0,0,0.88)]" title={title}>{title}</h2>
      {subtitle && <p className="mt-0.5 truncate text-[11px] text-[rgba(0,0,0,0.45)]" title={subtitle}>{subtitle}</p>}
    </div>
    <CloseX buttonRef={closeButton} onClose={onClose} />
  </div>
);

/** The drawers' close X. */
export const CloseX: React.FC<{ buttonRef: React.RefObject<HTMLButtonElement | null>; onClose: () => void }> = ({
  buttonRef, onClose,
}) => (
  <button
    ref={buttonRef}
    type="button"
    onClick={onClose}
    aria-label="Close"
    className="flex h-7 w-7 flex-shrink-0 items-center justify-center rounded-md border border-[rgba(0,0,0,0.12)] text-[rgba(0,0,0,0.5)] transition-colors hover:bg-[rgba(0,0,0,0.04)] hover:text-[rgba(0,0,0,0.85)]"
  >
    <Icon icon="mdi:close" width={14} />
  </button>
);

/** A white card with a title and an optional description (Scan Type, Scope Selection, the Endpoint cards). */
export const SettingsCard: React.FC<{ title: string; description?: string; children: React.ReactNode }> = ({
  title, description, children,
}) => (
  <section className="space-y-3 rounded-xl border border-[rgba(0,0,0,0.08)] bg-white p-4">
    <div>
      <h3 className="text-sm font-semibold text-[rgba(0,0,0,0.85)]">{title}</h3>
      {description && <p className="mt-0.5 text-[11px] text-[rgba(0,0,0,0.5)]">{description}</p>}
    </div>
    {children}
  </section>
);

/** The subtitle under a schedule's name: Paused, else Legacy, else nothing. */
export function scheduleSubtitle(schedule: Pick<ScanSchedule, 'state' | 'legacy'>): string | null {
  if (schedule.state === 'PAUSED') return 'Paused';
  if (schedule.legacy) return 'Legacy';
  return null;
}

/** The Frequency cell. */
export const FrequencyCell: React.FC<{ schedule: ScanSchedule }> = ({ schedule }) => {
  const frequency = frequencyText(schedule.timing);
  if (frequency === null) return <Dash />;
  return (
    <span className="block">
      <span className="block">{frequency.label}</span>
      {frequency.detail && <Muted text={frequency.detail} />}
    </span>
  );
};

/** The Scan Status cell: "Not run yet", else the last run's chip. */
export const LastRunCell: React.FC<{ schedule: ScanSchedule }> = ({ schedule }) => {
  const never = lastRunText(schedule.lastRunAt);
  if (never !== null) return <span className="text-[rgba(0,0,0,0.55)]">{never}</span>;
  return <ScanStatusChip status={schedule.lastRunStatus} label={schedule.lastRunStatusLabel} />;
};
