import React, { useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Icon } from '@iconify/react';
import {
  fetchLabelSpend,
  type LabelSpendItem,
  type LabelSpendTotal,
} from '../../services/resourceLabelsApi';
import { LABEL_SPEND_KEY } from '../../hooks/useResourceLabels';
import type { ResourceLabel } from '../../types/resourceLabels';
import { shortenId } from '../../utils/shortenId';

/**
 * The month's bill as one bar, split by label, above the Charges by service table.
 *
 * <p>With "All labels" in the filter the bar is the whole bill: one segment per label in its own
 * colour, then the unallocated remainder in grey. Hovering a label grows its segment to the full
 * width and divides it by resource, so the reader sees what that label is made of without leaving
 * the bar; moving off the bar puts it back. Clicking a label keeps it open by selecting it in the
 * filter.
 *
 * <p>With one label picked in the filter, the bar starts in that expanded state and stays there.
 *
 * <p>Built as a single row of segments whose widths animate, rather than two bars swapped in and
 * out: the hovered segment visibly grows into the bar it becomes, which is what makes the second
 * view read as a close-up of the first.
 *
 * <p>Figures come from the server, which divides every bill line by the resource's own labels
 * first and then by its service's labels, so no cost is counted under two labels and the labels
 * plus the unallocated figure add up to the bill.
 */

/** Resource segments drawn individually inside a label; the rest merge into one "others" segment. */
const MAX_SEGMENTS = 12;
/** A resource narrower than this share of its label is merged into "others" too. */
const MIN_SEGMENT_PCT = 1.5;
const UNALLOCATED_COLOR = '#D1D5DB';
const TRANSITION = 'width 320ms ease, opacity 200ms ease';

interface Segment {
  key: string;
  title: string;
  subtitle: string;
  amount: number;
  /** Share of the label's positive total, 0–100. */
  pct: number;
  /** Opacity applied to the label colour when expanded, so neighbours are told apart. */
  shade: number;
}

const itemTitle = (item: LabelSpendItem) =>
  item.resourceId ? item.name ?? shortenId(item.resourceId, 40) : `${item.service} — charges with no resource`;

const itemSubtitle = (item: LabelSpendItem) => {
  const parts = [item.service];
  if (item.resourceId && item.region) parts.push(item.region);
  if (item.percent != null && Math.abs(item.percent - 100) > 0.005) {
    parts.push(`${Number(item.percent.toFixed(2))}% of it`);
  }
  if (item.via === 'service') parts.push('via service label');
  else if (item.via === 'resource+service') parts.push('via resource and service label');
  return parts.join(' · ');
};

/**
 * One label's resources as bar segments. Credits net against the label's total but cannot be drawn
 * as a width, so only positive contributions are drawn.
 */
const resourceSegments = (spend: LabelSpendTotal): Segment[] => {
  const positive = spend.items.filter((i) => i.amount > 0).sort((a, b) => b.amount - a.amount);
  const sum = positive.reduce((acc, i) => acc + i.amount, 0);
  if (sum <= 0) return [];

  const shown: Segment[] = [];
  let othersAmount = 0;
  let othersCount = 0;
  positive.forEach((item, i) => {
    const pct = (item.amount / sum) * 100;
    if (shown.length < MAX_SEGMENTS && pct >= MIN_SEGMENT_PCT) {
      shown.push({
        key: `${item.resourceId}|${item.service}`,
        title: itemTitle(item),
        subtitle: itemSubtitle(item),
        amount: item.amount,
        pct,
        shade: 1 - (i % 4) * 0.18,
      });
    } else {
      othersAmount += item.amount;
      othersCount += 1;
    }
  });
  // Items the server did not list still count toward the total.
  othersCount += Math.max(0, spend.itemCount - spend.items.length);
  if (othersAmount > 0) {
    shown.push({
      key: '__others__',
      title: `${othersCount} other ${othersCount === 1 ? 'resource' : 'resources'}`,
      subtitle: 'Each smaller than the segments beside it',
      amount: othersAmount,
      pct: (othersAmount / sum) * 100,
      shade: 0.3,
    });
  }
  return shown;
};

const pctText = (n: number) => `${Number(n.toFixed(2))}%`;

interface Props {
  accountId: string;
  period: string;
  basis: string;
  monthLabel: string;
  /** The label picked in the filter, or null for "All labels". */
  selected: ResourceLabel | null;
  money: (v: number) => string;
  /** Picks a label in the filter, or clears it with null. */
  onSelect: (labelId: number | null) => void;
}

const LabelSpendPanel: React.FC<Props> = ({ accountId, period, basis, monthLabel, selected, money, onSelect }) => {
  /** The label grown to the full bar by hovering, when nothing is picked in the filter. */
  const [hoverLabelId, setHoverLabelId] = useState<number | null>(null);
  /** The resource segment or the unallocated segment under the pointer. */
  const [hoverPart, setHoverPart] = useState<string | null>(null);

  const { data, isPending, isError, error } = useQuery({
    queryKey: [LABEL_SPEND_KEY, accountId, period, basis],
    queryFn: () => fetchLabelSpend(accountId, period, basis),
    // An Athena read behind it; label changes invalidate it explicitly.
    staleTime: 5 * 60 * 1000,
  });

  const labels = useMemo(() => (data?.labels ?? []).filter((l) => l.total > 0), [data]);
  const segmentsByLabel = useMemo(() => {
    const m = new Map<number, Segment[]>();
    for (const l of labels) m.set(l.labelId, resourceSegments(l));
    return m;
  }, [labels]);

  const unallocated = Math.max(0, data?.unallocatedTotal ?? 0);
  const drawnTotal = labels.reduce((acc, l) => acc + l.total, 0) + unallocated;

  const expandedId = selected?.id ?? hoverLabelId;
  const expanded = labels.find((l) => l.labelId === expandedId) ?? null;
  const pinned = selected !== null;

  const reset = () => {
    setHoverLabelId(null);
    setHoverPart(null);
  };

  // ── Header ──────────────────────────────────────────────────────────────────────

  const header = (() => {
    if (data?.status !== 'OK') return null;
    if (pinned) {
      const own = data.labels.find((l) => l.labelId === selected.id);
      return (
        <>
          <span className="inline-flex items-center gap-1.5">
            <span className="w-2.5 h-2.5 rounded-full flex-shrink-0" style={{ backgroundColor: selected.color }} />
            <span className="text-[13px] font-semibold text-black">{selected.name}</span>
          </span>
          <span className="text-[16px] font-bold text-black tabular-nums">{money(own?.total ?? 0)}</span>
          <span className="text-[11.5px] text-[rgba(0,0,0,0.5)]">
            {own?.resourceCount ?? 0} {(own?.resourceCount ?? 0) === 1 ? 'resource' : 'resources'} ·{' '}
            {pctText(own?.share ?? 0)} of the bill · {monthLabel}
          </span>
        </>
      );
    }
    const labelledPct = data.billTotal > 0 ? (data.allocatedTotal / data.billTotal) * 100 : 0;
    return (
      <>
        <span className="text-[13px] font-semibold text-black">Total</span>
        <span className="text-[16px] font-bold text-black tabular-nums">{money(data.billTotal)}</span>
        <span className="text-[11.5px] text-[rgba(0,0,0,0.5)]">
          {money(data.allocatedTotal)} labelled ({pctText(labelledPct)}) · {money(data.unallocatedTotal)} unallocated ·{' '}
          {monthLabel}
        </span>
      </>
    );
  })();

  // ── Line under the bar ──────────────────────────────────────────────────────────

  const caption = (() => {
    if (expanded) {
      const seg = (segmentsByLabel.get(expanded.labelId) ?? []).find((s) => s.key === hoverPart);
      if (seg) {
        return (
          <>
            <span className="font-medium text-black">{seg.title}</span>{' '}
            <span className="tabular-nums">
              {money(seg.amount)} · {Number(seg.pct.toFixed(1))}% of {expanded.name}
            </span>
            <span className="text-[rgba(0,0,0,0.4)]"> · {seg.subtitle}</span>
          </>
        );
      }
      return (
        <>
          <span className="font-medium" style={{ color: expanded.color }}>
            {expanded.name}
          </span>{' '}
          <span className="tabular-nums">
            {money(expanded.total)} · {expanded.resourceCount}{' '}
            {expanded.resourceCount === 1 ? 'resource' : 'resources'} · {pctText(expanded.share)} of the bill
          </span>
          <span className="text-[rgba(0,0,0,0.4)]">
            {pinned ? ' · Hover a segment to see which resource it is.' : ' · Hover a resource, or click to keep this open.'}
          </span>
        </>
      );
    }
    if (hoverPart === '__unallocated__' && data) {
      const p = data.billTotal > 0 ? (data.unallocatedTotal / data.billTotal) * 100 : 0;
      return (
        <>
          <span className="font-medium text-black">Unallocated</span>{' '}
          <span className="tabular-nums">
            {money(data.unallocatedTotal)} · {pctText(p)} of the bill
          </span>
          <span className="text-[rgba(0,0,0,0.4)]"> · No label covers this spend.</span>
        </>
      );
    }
    // A legend, so each colour can be named without hovering it.
    return (
      <span className="inline-flex items-center gap-3">
        {labels.map((l) => (
          <span key={l.labelId} className="inline-flex items-center gap-1">
            <span className="w-2 h-2 rounded-full" style={{ backgroundColor: l.color }} />
            <span className="text-black">{l.name}</span>
            <span className="tabular-nums">{money(l.total)}</span>
          </span>
        ))}
        <span className="inline-flex items-center gap-1">
          <span className="w-2 h-2 rounded-full" style={{ backgroundColor: UNALLOCATED_COLOR }} />
          Unallocated <span className="tabular-nums">{money(data?.unallocatedTotal ?? 0)}</span>
        </span>
      </span>
    );
  })();

  // ── Render ──────────────────────────────────────────────────────────────────────

  const pinnedEmpty = pinned && data?.status === 'OK' && !expanded;

  return (
    <div className="no-print mb-4 rounded-xl border border-[rgba(0,0,0,0.1)] px-4 py-3">
      <div className="flex items-center justify-between gap-3 flex-wrap">
        <div className="flex items-baseline gap-2 min-w-0 flex-wrap">{header}</div>
        {pinned && (
          <button
            type="button"
            onClick={() => onSelect(null)}
            className="inline-flex items-center gap-1 text-[11.5px] text-[rgba(0,0,0,0.5)] hover:text-black"
          >
            <Icon icon="mdi:close" width={13} />
            All labels
          </button>
        )}
      </div>

      {isPending && <p className="text-[12px] text-[rgba(0,0,0,0.45)] mt-2">Working out label spend…</p>}

      {isError && (
        <p className="text-[12px] text-[#B91C1C] mt-2">
          Could not load label spend. {error instanceof Error ? error.message : ''}
        </p>
      )}

      {data?.status === 'NO_REPORT' && (
        <p className="text-[12px] text-[rgba(0,0,0,0.55)] mt-2">
          {data.message ?? 'No Cost and Usage Report covers this month.'}
        </p>
      )}

      {pinnedEmpty && (
        <p className="text-[12px] text-[rgba(0,0,0,0.5)] mt-2">Nothing billed in {monthLabel} carries this label.</p>
      )}

      {data?.status === 'OK' && !pinnedEmpty && drawnTotal > 0 && (
        <>
          <div
            className="mt-2.5 h-3 w-full rounded-full overflow-hidden flex bg-[rgba(0,0,0,0.04)]"
            onMouseLeave={reset}
          >
            {labels.map((l) => {
              const isExpanded = expanded?.labelId === l.labelId;
              const width = expanded ? (isExpanded ? 100 : 0) : (l.total / drawnTotal) * 100;
              const segs = segmentsByLabel.get(l.labelId) ?? [];
              return (
                <div
                  key={l.labelId}
                  tabIndex={expanded && !isExpanded ? -1 : 0}
                  role="button"
                  aria-label={`${l.name}: ${money(l.total)}. ${pinned ? '' : 'Press Enter to keep it open.'}`}
                  onMouseEnter={() => {
                    if (!pinned) setHoverLabelId(l.labelId);
                  }}
                  onFocus={() => {
                    if (!pinned) setHoverLabelId(l.labelId);
                  }}
                  onClick={() => {
                    if (!pinned) onSelect(l.labelId);
                  }}
                  onKeyDown={(e) => {
                    if (!pinned && (e.key === 'Enter' || e.key === ' ')) {
                      e.preventDefault();
                      onSelect(l.labelId);
                    }
                  }}
                  className={`h-full flex overflow-hidden focus:outline-none ${pinned ? '' : 'cursor-pointer'}`}
                  style={{
                    width: `${width}%`,
                    // A label too small to see still gets a sliver, so it can be hovered.
                    minWidth: !expanded && l.total > 0 ? 4 : 0,
                    transition: TRANSITION,
                    // Separates labels in the whole-bill view; resources separate themselves.
                    boxShadow: expanded ? 'none' : 'inset -1px 0 0 #fff',
                  }}
                >
                  {segs.map((s, i) => (
                    <div
                      key={s.key}
                      onMouseEnter={() => {
                        if (isExpanded) setHoverPart(s.key);
                      }}
                      title={isExpanded ? `${s.title} — ${money(s.amount)}` : `${l.name} — ${money(l.total)}`}
                      className="h-full"
                      style={{
                        width: `${s.pct}%`,
                        backgroundColor: l.color,
                        // Solid in the whole-bill view, so a label reads as one block; shaded and
                        // separated once it is the whole bar.
                        opacity: isExpanded ? (hoverPart && hoverPart !== s.key ? s.shade * 0.45 : s.shade) : 1,
                        boxShadow: isExpanded && i < segs.length - 1 ? 'inset -1px 0 0 #fff' : 'none',
                        transition: TRANSITION,
                      }}
                    />
                  ))}
                </div>
              );
            })}

            {/* What no label covers. Not expandable: the server does not list its resources. */}
            {unallocated > 0 && (
              <div
                onMouseEnter={() => {
                  if (!expanded) setHoverPart('__unallocated__');
                }}
                title={`Unallocated — ${money(unallocated)}`}
                className="h-full"
                style={{
                  width: expanded ? '0%' : `${(unallocated / drawnTotal) * 100}%`,
                  backgroundColor: UNALLOCATED_COLOR,
                  transition: TRANSITION,
                }}
              />
            )}
          </div>

          <div className="mt-1.5 h-4 text-[11px] text-[rgba(0,0,0,0.55)] overflow-hidden whitespace-nowrap text-ellipsis">
            {caption}
          </div>
        </>
      )}
    </div>
  );
};

export default LabelSpendPanel;
