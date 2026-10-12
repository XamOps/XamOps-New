/**
 * The per-workload lifecycle indicator (design-delta §4.3; glossary §5): five short bars, each in
 * its stage's tone, with one label under them in the current stage's tone. The compact table form.
 * The stage states come from the pure `deriveLifecycle`, so what shows is derived from the inputs
 * and testable without the DOM.
 */
import { Icon } from '@iconify/react';
import { LIFECYCLE_STAGES, type StageState, type LifecycleStage, highlightStage } from './lifecycle';
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

export interface StepIndicatorProps {
  states: StageState[];
  /** The one-line summary shown under the bars; defaults to the highlighted stage's label. */
  label?: string;
  className?: string;
}

export function StepIndicator({ states, label, className = '' }: StepIndicatorProps) {
  const highlight = highlightStage(states);
  const current = states[highlight];
  const currentTone = current ? toneClasses(current.tone) : toneClasses('off');
  const text = label ?? (current ? STAGE_LABEL[current.stage] : '');

  return (
    <span className={`inline-flex flex-col gap-1 ${className}`} data-step-indicator="">
      <span className="flex items-center gap-0.5" role="img" aria-label={`Lifecycle: ${text}`}>
        {LIFECYCLE_STAGES.map((stage, i) => {
          const state = states[i];
          const t = toneClasses(state ? state.tone : 'off');
          const emphasis = i === highlight ? 'h-1.5' : 'h-1';
          return <span key={stage} className={`${emphasis} w-5 rounded-full ${t.border} border-t-4`} aria-hidden="true" />;
        })}
      </span>
      <span className={`inline-flex items-center gap-1 text-[12px] font-semibold ${currentTone.text}`}>
        {current && <Icon icon={STAGE_ICON[current.stage]} width={13} aria-hidden="true" className="shrink-0" />}
        <span>{text}</span>
      </span>
    </span>
  );
}

export default StepIndicator;
