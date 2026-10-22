/**
 * The card chrome every v3 chart sits in (design-delta §3; glossary §10). It carries:
 *   - the exact chart title (glossary §10 "Chart titles");
 *   - an on-screen "Source: …" line in the glossary §10 pattern
 *     ("Source: {feature} ({FEAT}, {endpoint}): {query or field}, step {step}, {window}.");
 *   - the empty / partial states of glossary §10 "Empty and partial charts":
 *       · history shorter than the window — a hatched block with "No data before {time}" copy;
 *       · Activity not deployed — the "XamOps starts recording…" copy, card kept, no fake points;
 *       · no suggestions — "No change suggested for any workload.";
 *       · history not served yet — "History arrives with the usage feed", with the source it will
 *         come from (UI v3 plan §2: every time-series chart until the usage feed ships);
 *       · not available — a chart XamOps can't draw yet, with the reason in plain words;
 *       · partial — drawn, with a note under it ("Prometheus keeps 3 days here.");
 *       · loading — a skeleton in the chart's place while its first read runs (no numbers);
 *       · error — the read failed: what happened, and Retry.
 *
 * The chrome is greyscale: no colour reaches this component. Colour only appears in the chart the
 * caller passes as `children`, through chartTokens.
 */
import type { ReactNode } from 'react';
import { Icon } from '@iconify/react';

/** The glossary §10 empty / partial cases. The chart card always stays; only its body changes. */
export type ChartEmptyState =
  | { kind: 'ok' }
  /** Drawn, but the source covers only part of the window: the chart, then the note ("Prometheus keeps 3 days here."). */
  | { kind: 'partial'; note: string }
  /** The first read is running: a skeleton in the chart's place, no numbers. */
  | { kind: 'loading' }
  /** The read failed: what happened, in words, and Retry. */
  | { kind: 'error'; message: string; onRetry?: () => void }
  | { kind: 'history-short'; before: string; note?: string }
  | { kind: 'activity-off' }
  | { kind: 'no-suggestions' }
  /** A time series XamOps doesn't serve yet; `from` names where its points will come from. */
  | { kind: 'history-pending'; from: string }
  /** Nothing to draw, and why, e.g. "Needs CPU and memory prices, which XamOps doesn't read yet." */
  | { kind: 'unavailable'; reason: string };

/** Exact copy, glossary §10 "Empty and partial charts". */
export const EMPTY_COPY = {
  activityOff:
    'XamOps starts recording changes when Activity is turned on. Until then this shows nothing.',
  noSuggestions: 'No change suggested for any workload.',
  /** UI v3 plan §2: the empty state of every time-series chart until the usage feed ships. */
  historyPending: 'History arrives with the usage feed',
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
  /**
   * The card's view switch (CPU | Memory), in the header so it stays usable in every state: a
   * resource whose read failed can always be switched back.
   */
  actions?: ReactNode;
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
      className="flex min-h-[120px] flex-col items-center justify-center gap-1 rounded-[6px] border border-dashed border-[rgba(0,0,0,0.14)] text-center dark:border-[rgba(255,255,255,0.16)]"
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
      {note && <span className="text-[11px] text-[rgba(0,0,0,0.56)] dark:text-[rgba(255,255,255,0.5)]">{note}</span>}
    </div>
  );
}

function EmptyNote({ icon, kind = '', children }: { icon: string; kind?: string; children: ReactNode }) {
  return (
    <div
      className="flex min-h-[120px] flex-col items-center justify-center gap-2 rounded-[6px] border border-dashed border-[rgba(0,0,0,0.14)] px-4 text-center dark:border-[rgba(255,255,255,0.16)]"
      data-empty={kind}
    >
      <Icon icon={icon} width={22} aria-hidden="true" className="text-[rgba(0,0,0,0.4)] dark:text-[rgba(255,255,255,0.4)]" />
      <p className="max-w-sm text-[12px] text-[rgba(0,0,0,0.6)] dark:text-[rgba(255,255,255,0.6)]">{children}</p>
    </div>
  );
}

export function ChartCard({ title, source, subline, actions, state = { kind: 'ok' }, children, table, className = '' }: ChartCardProps) {
  return (
    <section
      className={`rounded-[8px] border border-[rgba(0,0,0,0.08)] bg-white px-4 py-3 dark:border-[rgba(255,255,255,0.1)] dark:bg-[#141414] ${className}`}
      data-chart-card=""
      data-k8s-kit=""
    >
      <header className="mb-2 flex flex-wrap items-start justify-between gap-2">
        <div className="min-w-0 flex-1">
          <h3 className="text-[13px] font-semibold text-[rgba(0,0,0,0.88)] dark:text-[rgba(255,255,255,0.92)]">{title}</h3>
          {subline && (
            <p className="mt-0.5 text-[12px] text-[rgba(0,0,0,0.6)] dark:text-[rgba(255,255,255,0.6)]">{subline}</p>
          )}
        </div>
        {actions && <div className="shrink-0">{actions}</div>}
      </header>

      <div data-chart-body="">
        {(state.kind === 'ok' || state.kind === 'partial') && children}
        {state.kind === 'partial' && (
          <p className="mt-2 flex items-center gap-1.5 text-[12px] text-[rgba(0,0,0,0.6)] dark:text-[rgba(255,255,255,0.6)]" data-chart-partial="">
            <Icon icon="mdi:information-outline" width={14} aria-hidden="true" className="shrink-0" />
            {state.note}
          </p>
        )}
        {state.kind === 'loading' && (
          // The app's `.skeleton` shimmer is light; inside the kit the dark variant is a flat tint.
          <div className="skeleton h-[160px] rounded-[6px] dark:bg-none dark:bg-[rgba(255,255,255,0.06)]" aria-busy="true" data-empty="loading" />
        )}
        {state.kind === 'error' && (
          <EmptyNote icon="mdi:alert-circle-outline" kind="error">
            <span className="block">{state.message}</span>
            {state.onRetry && (
              <button
                type="button"
                onClick={state.onRetry}
                className="mt-2 rounded-[6px] border border-[rgba(0,0,0,0.12)] bg-white px-3 py-1 text-[12px] font-semibold text-[rgba(0,0,0,0.72)] outline-none hover:bg-[rgba(0,0,0,0.03)] focus-visible:ring-2 focus-visible:ring-[rgba(0,0,0,0.88)] dark:border-[rgba(255,255,255,0.16)] dark:bg-[#141414] dark:text-[rgba(255,255,255,0.8)] dark:hover:bg-[rgba(255,255,255,0.06)] dark:focus-visible:ring-[rgba(255,255,255,0.92)]"
              >
                Retry
              </button>
            )}
          </EmptyNote>
        )}
        {state.kind === 'history-short' && <HatchBlock before={state.before} note={state.note} />}
        {state.kind === 'activity-off' && <EmptyNote icon="mdi:record-circle-outline">{EMPTY_COPY.activityOff}</EmptyNote>}
        {state.kind === 'no-suggestions' && <EmptyNote icon="mdi:lightbulb-off-outline">{EMPTY_COPY.noSuggestions}</EmptyNote>}
        {state.kind === 'history-pending' && (
          <EmptyNote icon="mdi:chart-timeline-variant" kind="history-pending">
            <span className="block font-semibold text-[rgba(0,0,0,0.72)] dark:text-[rgba(255,255,255,0.8)]">{EMPTY_COPY.historyPending}</span>
            <span className="mt-0.5 block">{state.from}</span>
          </EmptyNote>
        )}
        {state.kind === 'unavailable' && (
          <EmptyNote icon="mdi:information-outline" kind="unavailable">
            {state.reason}
          </EmptyNote>
        )}
      </div>

      {table && <div className="mt-3">{table}</div>}

      {/* 0.56 on white is 4.9:1, AA for this 11 px line (Phase-1 review finding 8). */}
      <p className="mt-2 text-[11px] text-[rgba(0,0,0,0.56)] dark:text-[rgba(255,255,255,0.5)]" data-chart-source="">
        <span className="font-semibold">Source:</span> {source}
      </p>
    </section>
  );
}

export default ChartCard;
