import React, { useMemo, useState } from 'react';
import { Icon } from '@iconify/react';
import { useLabelHistory } from '../../hooks/useResourceLabels';
import type {
  LabelAuditChange,
  LabelAuditEvent,
  LabelChangeSource,
  ResourceLabel,
} from '../../types/resourceLabels';
import { shortenId } from '../../utils/shortenId';

/**
 * The label audit trail as a list: who changed which labels and shares, when, from which screen,
 * and what each change did.
 *
 * <p>Two scopes. For a whole account each entry names what it changed ("changed labels on
 * xamops-prod-nat"), and an entry that touched many resources can be expanded to list them. For a
 * single resource or service the target is implied, so entries read "changed labels", "added prod".
 *
 * <p>Entries carry each target's full set of labels before and after; what is shown is the
 * difference between the two, so "prod 40%, uat 40%" becoming "prod 60%, uat 40%" reads as
 * "prod 40% → 60%". Names and colours are the ones the label had at the time, so a renamed or
 * deleted label still reads as it did when the change was made.
 */

interface ShareSnap {
  labelId: number;
  name: string | null;
  color: string | null;
  /** Null means the whole cost. */
  percent: number | null;
}

interface LabelSnap {
  id: number | null;
  name: string | null;
  color: string | null;
  description: string | null;
}

interface Chip {
  id: number | null;
  name: string;
  color: string;
}

type ChipOf = (id: number | null, name: string | null, color: string | null) => Chip;

const NEUTRAL = '#9CA3AF';

const SOURCE_TEXT: Record<LabelChangeSource, string> = {
  COST_RESOURCE_PANEL: 'Cost page · resource panel',
  COST_SERVICE_ROW: 'Cost page · service row',
  CASCADE: 'Cost page · connected resources',
  CLOUD_LIST: 'Cloud List',
  LABELS_PAGE: 'Labels page',
};

const parse = <T,>(raw: string | null): T | null => {
  if (!raw) return null;
  try {
    return JSON.parse(raw) as T;
  } catch {
    return null;
  }
};

const pct = (p: number | null | undefined) => `${Number((p ?? 100).toFixed(2))}%`;
const samePct = (a: number | null, b: number | null) => Math.abs((a ?? 100) - (b ?? 100)) < 0.005;

/** "just now", "12 min ago", "3 h ago", "2 d ago", then the date. */
const ago = (iso: string): string => {
  const t = Date.parse(iso);
  if (Number.isNaN(t)) return '';
  const s = Math.max(0, Math.floor((Date.now() - t) / 1000));
  if (s < 60) return 'just now';
  const m = Math.floor(s / 60);
  if (m < 60) return `${m} min ago`;
  const h = Math.floor(m / 60);
  if (h < 24) return `${h} h ago`;
  const d = Math.floor(h / 24);
  if (d < 7) return `${d} d ago`;
  return new Date(t).toLocaleDateString(undefined, { day: 'numeric', month: 'short', year: 'numeric' });
};

const targetText = (c: LabelAuditChange | undefined): string => {
  if (!c) return 'an item';
  const name = c.targetName || (c.targetId ? shortenId(c.targetId, 40) : 'unknown');
  return c.targetType === 'SERVICE' ? `service ${name}` : name;
};

// ── Differences ───────────────────────────────────────────────────────────────

interface ShareDiff {
  kind: 'added' | 'removed' | 'changed';
  chip: Chip;
  from: number | null;
  to: number | null;
}

const diffShares = (before: ShareSnap[] | null, after: ShareSnap[] | null, chipOf: ChipOf): ShareDiff[] => {
  const was = new Map((before ?? []).map((s) => [s.labelId, s]));
  const now = new Map((after ?? []).map((s) => [s.labelId, s]));
  const out: ShareDiff[] = [];
  for (const [id, s] of now) {
    const prev = was.get(id);
    if (!prev) out.push({ kind: 'added', chip: chipOf(id, s.name, s.color), from: null, to: s.percent });
    else if (!samePct(prev.percent, s.percent)) {
      out.push({ kind: 'changed', chip: chipOf(id, s.name, s.color), from: prev.percent, to: s.percent });
    }
  }
  for (const [id, s] of was) {
    if (!now.has(id)) out.push({ kind: 'removed', chip: chipOf(id, s.name, s.color), from: s.percent, to: null });
  }
  return out;
};

// ── Pieces ────────────────────────────────────────────────────────────────────

const Pill: React.FC<{ chip: Chip; struck?: boolean; title?: string; children?: React.ReactNode }> = ({
  chip,
  struck = false,
  title,
  children,
}) => (
  <span
    title={title}
    className={`inline-flex items-center gap-1 rounded-full border px-1.5 py-px text-[11px] font-semibold whitespace-nowrap ${
      struck ? 'line-through opacity-60' : ''
    }`}
    style={{ backgroundColor: `${chip.color}14`, borderColor: `${chip.color}55`, color: chip.color }}
  >
    {chip.name}
    {children}
  </span>
);

const Diffs: React.FC<{ diffs: ShareDiff[] }> = ({ diffs }) => (
  <span className="inline-flex flex-wrap items-center gap-1">
    {diffs.map((d) => (
      <Pill
        key={`${d.kind}-${d.chip.id ?? d.chip.name}`}
        chip={d.chip}
        struck={d.kind === 'removed'}
        title={
          d.kind === 'added'
            ? `Added ${d.chip.name} at ${pct(d.to)}`
            : d.kind === 'removed'
              ? `Removed ${d.chip.name}, which had ${pct(d.from)}`
              : `${d.chip.name} changed from ${pct(d.from)} to ${pct(d.to)}`
        }
      >
        <span className="font-medium tabular-nums opacity-80">
          {d.kind === 'added' && `+${pct(d.to)}`}
          {d.kind === 'removed' && pct(d.from)}
          {d.kind === 'changed' && `${pct(d.from)} → ${pct(d.to)}`}
        </span>
      </Pill>
    ))}
  </span>
);

const LabelEdits: React.FC<{ before: LabelSnap | null; after: LabelSnap | null }> = ({ before, after }) => {
  const parts: React.ReactNode[] = [];
  if ((before?.name ?? '') !== (after?.name ?? '')) {
    parts.push(
      <span key="name">
        renamed <span className="font-medium text-black">{before?.name}</span> →{' '}
        <span className="font-medium text-black">{after?.name}</span>
      </span>,
    );
  }
  if ((before?.color ?? '') !== (after?.color ?? '')) {
    parts.push(
      <span key="color" className="inline-flex items-center gap-1">
        colour
        <span className="w-2.5 h-2.5 rounded-full" style={{ backgroundColor: before?.color ?? NEUTRAL }} />→
        <span className="w-2.5 h-2.5 rounded-full" style={{ backgroundColor: after?.color ?? NEUTRAL }} />
      </span>,
    );
  }
  if ((before?.description ?? '') !== (after?.description ?? '')) {
    parts.push(<span key="description">description changed</span>);
  }
  if (parts.length === 0) return null;
  return (
    <span className="inline-flex flex-wrap items-center gap-x-2 gap-y-0.5">
      {parts.map((p, i) => (
        <React.Fragment key={i}>
          {i > 0 && <span className="text-[rgba(0,0,0,0.25)]">·</span>}
          {p}
        </React.Fragment>
      ))}
    </span>
  );
};

// ── One entry ─────────────────────────────────────────────────────────────────

const EventRow: React.FC<{
  event: LabelAuditEvent;
  scope: 'account' | 'item';
  chipOf: ChipOf;
  compact: boolean;
}> = ({ event: e, scope, chipOf, compact }) => {
  const [expanded, setExpanded] = useState(false);
  const rejected = e.outcome === 'REJECTED';
  const label = chipOf(e.labelId, e.labelName, e.labelColor);
  const targets = e.changes.filter((c) => c.targetType !== 'LABEL');
  const labelRow = e.changes.find((c) => c.targetType === 'LABEL');
  const targetTotal = Math.max(0, e.changeCount - (e.changes.length - targets.length));
  const first = targets[0];
  const many = scope === 'account' && targetTotal > 1;

  let sentence: React.ReactNode;
  let body: React.ReactNode = null;

  switch (e.action) {
    case 'LABELS_SET': {
      sentence = (
        <>
          {rejected ? 'tried to change labels' : 'changed labels'}
          {scope === 'account' && first && (
            <>
              {' '}on <span className="font-medium text-black">{targetText(first)}</span>
            </>
          )}
        </>
      );
      if (first) {
        const before = parse<ShareSnap[]>(first.before);
        const after = parse<ShareSnap[]>(first.after);
        if (rejected) {
          body = (
            <span className="inline-flex flex-wrap items-center gap-1">
              <span>Asked for</span>
              {(after ?? []).length === 0 ? (
                <span>no labels</span>
              ) : (
                (after ?? []).map((s) => (
                  <Pill key={s.labelId} chip={chipOf(s.labelId, s.name, s.color)}>
                    <span className="font-medium tabular-nums opacity-80">{pct(s.percent)}</span>
                  </Pill>
                ))
              )}
            </span>
          );
        } else {
          const diffs = diffShares(before, after, chipOf);
          body = diffs.length > 0 ? <Diffs diffs={diffs} /> : null;
        }
      }
      break;
    }
    case 'LABEL_ASSIGNED':
    case 'LABEL_REMOVED': {
      const adding = e.action === 'LABEL_ASSIGNED';
      const verb = rejected ? (adding ? 'tried to add' : 'tried to remove') : adding ? 'added' : 'removed';
      sentence = (
        <>
          {verb} <Pill chip={label} />
          {scope === 'account' &&
            (many ? (
              <>
                {' '}
                {adding ? 'to' : 'from'} {targetTotal} resources
              </>
            ) : (
              first && (
                <>
                  {' '}
                  {adding ? 'to' : 'from'} <span className="font-medium text-black">{targetText(first)}</span>
                </>
              )
            ))}
        </>
      );
      break;
    }
    case 'LABEL_CREATED':
      sentence = (
        <>
          {rejected ? 'tried to create label' : 'created label'}{' '}
          {rejected ? (
            <span className="font-medium text-black">{parse<LabelSnap>(labelRow?.after ?? null)?.name ?? ''}</span>
          ) : (
            <Pill chip={label} />
          )}
        </>
      );
      break;
    case 'LABEL_UPDATED':
      sentence = (
        <>
          {rejected ? 'tried to edit label' : 'edited label'} <Pill chip={label} />
        </>
      );
      if (!rejected && labelRow) {
        body = <LabelEdits before={parse<LabelSnap>(labelRow.before)} after={parse<LabelSnap>(labelRow.after)} />;
      }
      break;
    case 'LABEL_DELETED':
      sentence = (
        <>
          {rejected ? 'tried to delete label' : 'deleted label'} <Pill chip={label} struck={!rejected} />
          {!rejected && scope === 'item' && ', which took it off here'}
          {!rejected && scope === 'account' && targetTotal > 0 && (
            <>
              {' '}
              — it was on {targetTotal} {targetTotal === 1 ? 'item' : 'items'} in this account
            </>
          )}
        </>
      );
      if (!rejected && scope === 'item' && first) {
        const diffs = diffShares(parse<ShareSnap[]>(first.before), parse<ShareSnap[]>(first.after), chipOf);
        body = diffs.length > 0 ? <Diffs diffs={diffs} /> : null;
      }
      break;
    default:
      sentence = <>{String(e.action).toLowerCase().replace(/_/g, ' ')}</>;
  }

  // The resources an entry touched, for one that touched several — or, for a deleted label, every
  // item in this account it was on, with the share it had there.
  const listable =
    scope === 'account' &&
    targets.length > 0 &&
    (many || (e.action === 'LABEL_DELETED' && targetTotal > 0)) &&
    !rejected;

  return (
    <li className={compact ? 'py-2' : 'py-3'}>
      <div className="flex items-center gap-1.5 flex-wrap text-[11px] text-[rgba(0,0,0,0.45)]">
        <span className="font-semibold text-[rgba(0,0,0,0.75)]">{e.actor || 'Someone'}</span>
        <span>·</span>
        <time dateTime={e.at} title={Number.isNaN(Date.parse(e.at)) ? undefined : new Date(e.at).toLocaleString()}>
          {ago(e.at)}
        </time>
        {e.source && (
          <>
            <span>·</span>
            <span>{SOURCE_TEXT[e.source] ?? e.source}</span>
          </>
        )}
        {rejected && (
          <span className="ml-1 inline-flex items-center gap-0.5 rounded px-1 py-px text-[10px] font-semibold uppercase tracking-wide bg-[rgba(245,158,11,0.12)] text-[#B45309]">
            <Icon icon="mdi:alert-outline" width={11} />
            Not saved
          </span>
        )}
      </div>

      <div className={`mt-1 ${compact ? 'text-[11.5px]' : 'text-[12.5px]'} text-[rgba(0,0,0,0.7)] leading-relaxed`}>
        <span className="inline-flex flex-wrap items-center gap-1">{sentence}</span>
      </div>

      {body && <div className="mt-1 text-[11.5px] text-[rgba(0,0,0,0.6)]">{body}</div>}

      {rejected && e.detail && <p className="mt-1 text-[11.5px] text-[#B45309]">{e.detail}</p>}
      {!rejected && e.detail && <p className="mt-1 text-[11px] text-[rgba(0,0,0,0.45)]">{e.detail}</p>}

      {listable && (
        <div className="mt-1.5">
          <button
            type="button"
            onClick={() => setExpanded((v) => !v)}
            aria-expanded={expanded}
            className="inline-flex items-center gap-0.5 text-[11px] font-medium text-[#4285F4] hover:underline"
          >
            <Icon icon={expanded ? 'mdi:chevron-down' : 'mdi:chevron-right'} width={13} />
            {expanded ? 'Hide' : `Show ${targetTotal === 1 ? 'it' : `all ${targetTotal}`}`}
          </button>
          {expanded && (
            <ul className="mt-1 ml-3.5 space-y-0.5">
              {targets.map((c) => {
                const had = parse<ShareSnap[]>(c.before)?.find((s) => s.labelId === e.labelId);
                return (
                  <li
                    key={`${c.targetType}-${c.targetId}`}
                    className="flex items-center gap-2 text-[11.5px] text-[rgba(0,0,0,0.65)]"
                  >
                    <span className="truncate" title={c.targetId ?? undefined}>
                      {targetText(c)}
                    </span>
                    {e.action !== 'LABEL_ASSIGNED' && had && (
                      <span className="text-[rgba(0,0,0,0.4)] tabular-nums whitespace-nowrap">had {pct(had.percent)}</span>
                    )}
                  </li>
                );
              })}
              {targetTotal > targets.length && (
                <li className="text-[11px] text-[rgba(0,0,0,0.4)]">and {targetTotal - targets.length} more</li>
              )}
            </ul>
          )}
        </div>
      )}
    </li>
  );
};

// ── List ──────────────────────────────────────────────────────────────────────

interface Props {
  accountId: string;
  /** One resource or service, or null for everything on the account. */
  target: { type: 'RESOURCE' | 'SERVICE'; id: string } | null;
  /** The client's labels now, to name a label an entry only knows by id. */
  allLabels: ResourceLabel[];
  /** Tighter spacing, for the label popover. */
  compact?: boolean;
}

const LabelHistoryList: React.FC<Props> = ({ accountId, target, allLabels, compact = false }) => {
  const { data, isPending, isError, error, fetchNextPage, hasNextPage, isFetchingNextPage } = useLabelHistory(
    accountId,
    target,
  );

  const chipOf = useMemo<ChipOf>(() => {
    const byId = new Map(allLabels.map((l) => [l.id, l]));
    return (id, name, color) => {
      const live = id != null ? byId.get(id) : undefined;
      return {
        id,
        name: name ?? live?.name ?? (id != null ? `label #${id}` : 'a label'),
        color: color ?? live?.color ?? NEUTRAL,
      };
    };
  }, [allLabels]);

  const events = useMemo(() => data?.pages.flatMap((p) => p.events) ?? [], [data]);

  if (isPending) {
    return <p className="text-[12px] text-[rgba(0,0,0,0.45)] py-2">Loading history…</p>;
  }
  if (isError) {
    return (
      <p className="text-[12px] text-[#B91C1C] py-2">
        Could not load the history. {error instanceof Error ? error.message : ''}
      </p>
    );
  }
  if (events.length === 0) {
    return (
      <p className="text-[12px] text-[rgba(0,0,0,0.5)] py-2">
        {target
          ? `No label changes recorded for this ${target.type === 'SERVICE' ? 'service' : 'resource'} yet.`
          : 'No label changes recorded for this account yet.'}{' '}
        Changes made from now on appear here.
      </p>
    );
  }

  return (
    <div>
      <ol className="divide-y divide-[rgba(0,0,0,0.06)]">
        {events.map((e) => (
          <EventRow key={e.id} event={e} scope={target ? 'item' : 'account'} chipOf={chipOf} compact={compact} />
        ))}
      </ol>
      {hasNextPage && (
        <button
          type="button"
          onClick={() => void fetchNextPage()}
          disabled={isFetchingNextPage}
          className="mt-2 w-full py-1.5 text-[11.5px] font-medium text-[#4285F4] border border-[rgba(0,0,0,0.1)] rounded-lg hover:bg-[rgba(66,133,244,0.05)] disabled:opacity-50"
        >
          {isFetchingNextPage ? 'Loading…' : 'Load older changes'}
        </button>
      )}
    </div>
  );
};

export default LabelHistoryList;
