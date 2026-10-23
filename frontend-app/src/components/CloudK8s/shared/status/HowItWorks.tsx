/**
 * "How the Workload autoscaler works" (design-delta §4.2; glossary §4): the five static steps
 * Measure, Decide, Apply, Verify, Watch, each with its one-liner and a per-step STATUS DOT derived
 * from this cluster's inputs. The highlighted step is the first with a warn or bad state, else the
 * step something is happening in now; it gets a thin top rule in its tone — no filled tinted band,
 * no inner pill.
 *
 * Layout (owner debox review): ONE Surface, not a tall vertical stack of bordered rows. The five
 * steps lay out as a LIGHT HORIZONTAL STRIP — side by side across the row on wide screens, wrapping
 * to 2–3 per row when narrow — separated by whitespace and hairline dividers, never a box per step.
 * Status is a StatusDot (dot + word), never a filled colour pill.
 *
 * The step copy and icons are the exact ones from glossary §4; the per-step states are supplied by
 * the caller (built from endpoint 1 / BE-11 / BE-5 / policy), so this component stays a pure
 * renderer. A collapsed variant is used on the Workloads page.
 */
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { Icon } from '@iconify/react';
import type { Tone } from './statusTones';
import StatusDot from './StatusDot';
import { toneClasses } from './toneClasses';

export type HowStepKey = 'measure' | 'decide' | 'apply' | 'verify' | 'watch';

export const HOW_STEPS: ReadonlyArray<{ key: HowStepKey; title: string; icon: string; line: string }> = [
  { key: 'measure', title: 'Measure', icon: 'mdi:chart-timeline-variant', line: "Reads each container's CPU and memory use from Prometheus in your cluster." },
  { key: 'decide', title: 'Decide', icon: 'mdi:scale-balance', line: 'Works out a size: busy-time CPU and peak memory, plus headroom, never below the minimum.' },
  { key: 'apply', title: 'Apply', icon: 'mdi:gesture-tap-button', line: 'When you click Apply, resizes the running pods in place: one pod first, then the rest.' },
  { key: 'verify', title: 'Verify', icon: 'mdi:shield-check-outline', line: 'Checks that Kubernetes put the new size in every pod, and that nothing restarted.' },
  { key: 'watch', title: 'Watch', icon: 'mdi:eye-outline', line: 'Waits after a change, then keeps measuring. A new suggestion appears only if use changes.' },
];

/**
 * A per-step state, one per step key (a step with no state is drawn without a dot). It becomes a
 * StatusDot (dot + word), so only `tone`, `label` and an optional `detail` are rendered; `icon` is
 * kept on the type so the existing callers (which build it alongside the other status maps) still
 * compile, but the quiet dot no longer draws it.
 */
export interface HowStepChip {
  tone: Tone;
  /** Carried for back-compat with the status maps; the StatusDot renders the dot, not this icon. */
  icon: string;
  label: string;
  detail?: string;
}

export interface HowItWorksProps {
  /** The per-step state (a StatusDot), keyed by step. */
  chips: Partial<Record<HowStepKey, HowStepChip>>;
  /**
   * The Policies link target (the source line is a link to the exact rules). It is a router link,
   * so a caller that passes it renders this inside a Router.
   */
  policiesHref?: string;
  /** Navigation state for the Policies link, e.g. `inPageState(location)` to keep the page mounted. */
  policiesLinkState?: unknown;
  /** Start collapsed (the Workloads page); shows a one-line summary and a Show toggle. */
  collapsed?: boolean;
  /** The one-line summary used in the collapsed state ("Now: {summary}"). */
  summary?: string;
  className?: string;
}

/** The first step whose chip is warn or bad; else the first with an info chip; else -1. */
function highlightStep(chips: HowItWorksProps['chips']): number {
  const problem = HOW_STEPS.findIndex((s) => {
    const tone = chips[s.key]?.tone;
    return tone === 'warn' || tone === 'bad';
  });
  if (problem >= 0) return problem;
  return HOW_STEPS.findIndex((s) => chips[s.key]?.tone === 'info');
}

export function HowItWorks({ chips, policiesHref, policiesLinkState, collapsed = false, summary, className = '' }: HowItWorksProps) {
  const [open, setOpen] = useState(!collapsed);
  const highlight = highlightStep(chips);

  const header = (
    <div className="flex items-start justify-between gap-3">
      <h3 className="text-[13px] font-semibold text-[rgba(0,0,0,0.88)] dark:text-[rgba(255,255,255,0.92)]">
        How the Workload autoscaler works
      </h3>
      {policiesHref && (
        <Link
          to={policiesHref}
          state={policiesLinkState}
          className="text-[12px] text-[rgba(0,0,0,0.6)] underline outline-none focus-visible:ring-2 focus-visible:ring-[rgba(0,0,0,0.88)] dark:text-[rgba(255,255,255,0.6)] dark:focus-visible:ring-[rgba(255,255,255,0.92)]"
        >
          Exact rules: Workload autoscaler › Policies
        </Link>
      )}
    </div>
  );

  if (collapsed && !open) {
    return (
      <section
        className={`rounded-[8px] border border-[rgba(0,0,0,0.08)] bg-white px-4 py-3 dark:border-[rgba(255,255,255,0.1)] dark:bg-[#141414] ${className}`}
        data-how-it-works="collapsed"
        data-k8s-kit=""
      >
        {header}
        <p className="mt-1 text-[12px] text-[rgba(0,0,0,0.6)] dark:text-[rgba(255,255,255,0.6)]">
          How it works: Measure › Decide › Apply › Verify › Watch.{summary ? ` Now: ${summary}` : ''}
        </p>
        <button
          type="button"
          onClick={() => setOpen(true)}
          className="mt-2 text-[12px] font-semibold text-[rgba(0,0,0,0.88)] underline dark:text-[rgba(255,255,255,0.92)]"
        >
          Show
        </button>
      </section>
    );
  }

  return (
    <section
      className={`rounded-[8px] border border-[rgba(0,0,0,0.08)] bg-white px-4 py-3 dark:border-[rgba(255,255,255,0.1)] dark:bg-[#141414] ${className}`}
      data-how-it-works="expanded"
      data-k8s-kit=""
    >
      {header}
      {/*
        The five steps are a LIGHT HORIZONTAL STRIP inside this one Surface (owner debox review): a
        flex row that lays them side by side on wide screens and wraps to 2–3 per row when narrow,
        separated by hairline dividers and whitespace — NOT a tall stack of five bordered boxes, and
        with NO inner per-step pill. Each step is just its number + title, its one-liner and a quiet
        StatusDot (dot + word). The highlighted step carries a thin top rule in its tone; no filled
        tinted band.
      */}
      <ol className="mt-3 flex flex-wrap items-stretch gap-x-6 gap-y-4">
        {HOW_STEPS.map((step, i) => {
          const chip = chips[step.key];
          const highlighted = i === highlight && !!chip;
          const rule = highlighted ? `border-t-2 ${toneClasses(chip!.tone).border}` : 'border-t-2 border-transparent';
          const divider = i > 0 ? 'sm:border-l sm:border-[color:var(--chrome-line)] sm:pl-6' : '';
          return (
            <li
              key={step.key}
              className={`flex min-w-[180px] flex-1 flex-col gap-1 pt-2 ${rule} ${divider}`}
              data-how-step={step.key}
            >
              <span className="flex items-center gap-1 text-[12px] font-semibold text-[rgba(0,0,0,0.88)] dark:text-[rgba(255,255,255,0.92)]">
                <Icon icon={step.icon} width={14} aria-hidden="true" className="shrink-0" />
                {i + 1}. {step.title}
              </span>
              <span className="text-[11px] leading-snug text-[rgba(0,0,0,0.6)] dark:text-[rgba(255,255,255,0.6)]">{step.line}</span>
              {chip && (
                <span className="mt-0.5" data-how-chip="">
                  <StatusDot tone={chip.tone} label={chip.label} detail={chip.detail} className="text-[12px]" />
                </span>
              )}
            </li>
          );
        })}
      </ol>
      {collapsed && (
        <button
          type="button"
          onClick={() => setOpen(false)}
          className="mt-2 text-[12px] font-semibold text-[rgba(0,0,0,0.88)] underline dark:text-[rgba(255,255,255,0.92)]"
        >
          Hide
        </button>
      )}
    </section>
  );
}

export default HowItWorks;
