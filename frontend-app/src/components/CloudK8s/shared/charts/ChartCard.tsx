/**
 * The card chrome every v3 chart sits in (design-delta §3; glossary §10). It carries:
 *   - the exact chart title (glossary §10 "Chart titles");
 *   - an on-screen "Source: …" line in the glossary §10 pattern
 *     ("Source: {feature} ({FEAT}, {endpoint}): {query or field}, step {step}, {window}.");
 *   - the empty / partial states of glossary §10 "Empty and partial charts":
 *       · history shorter than the window — a hatched block with "No data before {time}" copy;
 *       · Activity not deployed — the "XamOps starts recording…" copy, card kept, no fake points;
 *       · no suggestions — "No change suggested for any workload.".
 *
 * The chrome is greyscale: no colour reaches this component. Colour only appears in the chart the
 * caller passes as `children`, through chartTokens.
 */
import type { ReactNode } from 'react';
import { Icon } from '@iconify/react';

/** The glossary §10 empty / partial cases. The chart card always stays; only its body changes. */
export type ChartEmptyState =
  | { kind: 'ok' }
  | { kind: 'history-short'; before: string; note?: string }
  | { kind: 'activity-off' }
  | { kind: 'no-suggestions' };

/** Exact copy, glossary §10 "Empty and partial charts". */
export const EMPTY_COPY = {
  activityOff:
    'XamOps starts recording changes when Activity is turned on. Until then this shows nothing.',
  noSuggestions: 'No change suggested for any workload.',
} as const;

export interface ChartCardProps {
  /** The exact title from glossary §10. */
  title: string;
  /**
   * The on-screen source line, glossary §10 pattern. Rendered after a bold "Source:" so the pattern
   * reads "Source: {feature} ({FEAT}, {endpoint}): {query or field}, step {step}, {window}.".
   */
  source: string;
  /** Optional sub-line (e.g. a stated stack total, §3.2). */
  subline?: ReactNode;
  /** The empty / partial state; defaults to a drawn chart ({ kind: 'ok' }). */
  state?: ChartEmptyState;
  /** The chart itself (recharts or plain SVG), shown when state is 'ok'. */
  children?: ReactNode;
  /** The accessible data table, placed under the chart (always carries every layer). */
  table?: ReactNode;
  className?: string;
}

function HatchBlock({ before, note }: { before: string; note?: string }) {
  // A hatched block standing for the span with no history, labelled per glossary §10.
  return (
    <div
      className="flex min-h-[120px] flex-col items-center justify-center gap-1 rounded-md border border-dashed border-[rgba(0,0,0,0.14)] text-center dark:border-[rgba(255,255,255,0.16)]"
      data-empty="history-short"
    >
      <svg width="100%" height="60" aria-hidden="true" focusable="false" className="opacity-70">
        <defs>
          <pattern id="k8s-card-hatch" width="6" height="6" patternUnits="userSpaceOnUse" patternTransform="rotate(45)">
            <line x1="0" y1="0" x2="0" y2="6" className="stroke-series-capacity" strokeWidth="1" />
          </pattern>
        </defs>
        <rect x="0" y="0" width="100%" height="60" fill="url(#k8s-card-hatch)" />
      </svg>
      <span className="text-[12px] text-[rgba(0,0,0,0.6)] dark:text-[rgba(255,255,255,0.6)]">No data before {before}</span>
      {note && <span className="text-[11px] text-[rgba(0,0,0,0.5)] dark:text-[rgba(255,255,255,0.5)]">{note}</span>}
    </div>
  );
}

function EmptyNote({ icon, children }: { icon: string; children: ReactNode }) {
  return (
    <div
      className="flex min-h-[120px] flex-col items-center justify-center gap-2 rounded-md border border-dashed border-[rgba(0,0,0,0.14)] px-4 text-center dark:border-[rgba(255,255,255,0.16)]"
      data-empty=""
    >
      <Icon icon={icon} width={22} aria-hidden="true" className="text-[rgba(0,0,0,0.4)] dark:text-[rgba(255,255,255,0.4)]" />
      <p className="max-w-sm text-[12px] text-[rgba(0,0,0,0.6)] dark:text-[rgba(255,255,255,0.6)]">{children}</p>
    </div>
  );
}

export function ChartCard({ title, source, subline, state = { kind: 'ok' }, children, table, className = '' }: ChartCardProps) {
  return (
    <section
      className={`rounded-lg border border-[rgba(0,0,0,0.08)] bg-white px-4 py-3 dark:border-[rgba(255,255,255,0.1)] dark:bg-[#141414] ${className}`}
      data-chart-card=""
    >
      <header className="mb-2">
        <h3 className="text-[13px] font-semibold text-[rgba(0,0,0,0.88)] dark:text-[rgba(255,255,255,0.92)]">{title}</h3>
        {subline && (
          <p className="mt-0.5 text-[12px] text-[rgba(0,0,0,0.6)] dark:text-[rgba(255,255,255,0.6)]">{subline}</p>
        )}
      </header>

      <div data-chart-body="">
        {state.kind === 'ok' && children}
        {state.kind === 'history-short' && <HatchBlock before={state.before} note={state.note} />}
        {state.kind === 'activity-off' && <EmptyNote icon="mdi:record-circle-outline">{EMPTY_COPY.activityOff}</EmptyNote>}
        {state.kind === 'no-suggestions' && <EmptyNote icon="mdi:lightbulb-off-outline">{EMPTY_COPY.noSuggestions}</EmptyNote>}
      </div>

      {table && <div className="mt-3">{table}</div>}

      <p className="mt-2 text-[11px] text-[rgba(0,0,0,0.5)] dark:text-[rgba(255,255,255,0.5)]" data-chart-source="">
        <span className="font-semibold">Source:</span> {source}
      </p>
    </section>
  );
}

export default ChartCard;
