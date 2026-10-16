/**
 * The per-workload lifecycle indicator (design-delta §4.3; glossary §5). The compact table form is
 * five short bars, each in its stage's tone, with one label under them in the current stage's tone.
 * The full form (the workload detail) gives each stage its bar, icon, label and one detail line, and
 * the current stage a tinted band. The stage states come from the pure `deriveLifecycle`, so what
 * shows is derived from the inputs and testable without the DOM.
 */
import { Icon } from '@iconify/react';
import { LIFECYCLE_STAGES, type StageState, type LifecycleStage, type StageStatus, highlightStage } from './lifecycle';
import { toneClasses } from './toneClasses';

const STAGE_LABEL: Record<LifecycleStage, string> = {
  measuring: 'Measuring',
  suggestion: 'Suggestion ready',
  applying: 'Applying',
  applied: 'Applied',
  watching: 'Watching',
};

const STAGE_ICON: Record<LifecycleStage, string> = {
  measuring: 'mdi:chart-timeline-variant',
  suggestion: 'mdi:lightbulb-on-outline',
  applying: 'mdi:gesture-tap-button',
  applied: 'mdi:check-circle',
  watching: 'mdi:eye-outline',
};

/** The full form's line when the caller gives no detail for a stage. */
const STATUS_WORD: Record<StageStatus, string> = {
  done: 'Done',
  current: 'Now',
  problem: 'Needs attention',
  todo: 'Not yet',
};

export interface StepIndicatorProps {
  states: StageState[];
  /** The one-line summary shown under the bars; defaults to the highlighted stage's label. */
  label?: string;
  /** 'compact' (tables, the default) or 'full' (the workload detail). */
  variant?: 'compact' | 'full';
  /** The full form's detail line per stage (glossary §5); a stage without one shows its status word. */
  details?: Partial<Record<LifecycleStage, string>>;
  className?: string;
}

export function StepIndicator({ states, label, variant = 'compact', details, className = '' }: StepIndicatorProps) {
  const highlight = highlightStage(states);
  const current = states[highlight];
  const currentTone = current ? toneClasses(current.tone) : toneClasses('off');
  const text = label ?? (current ? STAGE_LABEL[current.stage] : '');

  if (variant === 'full') {
    return (
      <ol className={`grid gap-2 sm:grid-cols-5 ${className}`} aria-label={`Lifecycle: ${text}`} data-step-indicator="full" data-k8s-kit="">
        {LIFECYCLE_STAGES.map((stage, i) => {
          const state = states[i];
          const t = toneClasses(state ? state.tone : 'off');
          const highlighted = i === highlight;
          return (
            <li
              key={stage}
              className={`flex flex-col gap-1 rounded-md border-t-4 px-2 py-2 ${t.border} ${highlighted ? t.band : ''}`}
              aria-current={highlighted ? 'step' : undefined}
              data-stage={stage}
              data-stage-status={state?.status ?? 'todo'}
            >
              <span className={`flex items-center gap-1 text-[12px] font-semibold ${t.text}`}>
                <Icon icon={STAGE_ICON[stage]} width={14} aria-hidden="true" className="shrink-0" />
                <span>{STAGE_LABEL[stage]}</span>
              </span>
              <span className="text-[11px] leading-snug text-[rgba(0,0,0,0.6)] dark:text-[rgba(255,255,255,0.6)]">
                {details?.[stage] ?? STATUS_WORD[state?.status ?? 'todo']}
              </span>
            </li>
          );
        })}
      </ol>
    );
  }

  return (
    <span className={`inline-flex flex-col gap-1 ${className}`} data-step-indicator="" data-k8s-kit="">
      <span className="flex items-center gap-0.5" role="img" aria-label={`Lifecycle: ${text}`}>
        {LIFECYCLE_STAGES.map((stage, i) => {
          const state = states[i];
          const t = toneClasses(state ? state.tone : 'off');
          const emphasis = i === highlight ? 'h-1.5' : 'h-1';
          return <span key={stage} className={`${emphasis} w-5 rounded-full ${t.border} border-t-4`} aria-hidden="true" />;
        })}
      </span>
      <span className={`inline-flex items-center gap-1 text-[12px] font-semibold ${currentTone.text}`}>
        {(current || text) && (
          <Icon icon={current ? STAGE_ICON[current.stage] : 'mdi:minus-circle-outline'} width={13} aria-hidden="true" className="shrink-0" />
        )}
        <span>{text}</span>
      </span>
    </span>
  );
}

export default StepIndicator;
