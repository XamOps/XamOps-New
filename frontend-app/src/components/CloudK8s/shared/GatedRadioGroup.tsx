/**
 * A draft radio group whose options can carry their own capabilities, such as Mode where only Auto
 * is blocked (docs/cloudk8s/ui-revamp-design.md §6.5, §11.1, §14).
 *
 * WAI-ARIA radio group: `role="radiogroup"` with `aria-labelledby`, options `role="radio"` with
 * `aria-checked`, one tab stop on the checked option. Arrow keys move focus through every option,
 * blocked ones included, and check the focused option only when it is editable; Space does the
 * same. A blocked option carries `aria-disabled="true"`, the lock icon and `aria-describedby` to its
 * own reason. Custom because RAC's `RadioGroup` can block one option only with `isDisabled`, which
 * takes it out of focus and so hides its reason.
 */
import { useId, useRef, type KeyboardEvent } from 'react';
import type { Capability } from '../../../types/k8sAutomation';
import { LockIcon, ReasonText, type ReasonPlacement } from './ReasonText';

export interface GatedRadioOption<V extends string> {
  value: V;
  label: string;
  description?: string;
  capability?: Capability;
}

export interface GatedRadioGroupProps<V extends string> {
  label: string;
  value: V;
  onChange: (value: V) => void;
  /** Blocks every option when not editable. */
  capability: Capability;
  options: Array<GatedRadioOption<V>>;
  reasonPlacement?: ReasonPlacement;
  className?: string;
}

const focusRing = 'outline-none focus-visible:ring-2 focus-visible:ring-[rgba(0,0,0,0.88)] focus-visible:ring-offset-2';

export function GatedRadioGroup<V extends string>({
  label, value, onChange, capability, options, reasonPlacement = 'inline', className = '',
}: GatedRadioGroupProps<V>) {
  const baseId = useId();
  const labelId = `${baseId}-label`;
  const groupReasonId = `${baseId}-reason`;
  const refs = useRef<Array<HTMLDivElement | null>>([]);
  const groupBlocked = !capability.editable;

  const optionBlocked = (o: GatedRadioOption<V>) => groupBlocked || (o.capability ? !o.capability.editable : false);
  const optionReasonId = (index: number, o: GatedRadioOption<V>) =>
    o.capability && !o.capability.editable ? `${baseId}-opt-${index}` : groupReasonId;

  const checkedIndex = options.findIndex((o) => o.value === value);
  const tabStop = checkedIndex >= 0 ? checkedIndex : 0;

  const select = (index: number) => {
    const option = options[index];
    if (option && !optionBlocked(option) && option.value !== value) onChange(option.value);
  };

  const onKeyDown = (event: KeyboardEvent<HTMLDivElement>, index: number) => {
    let next: number | null = null;
    if (event.key === 'ArrowRight' || event.key === 'ArrowDown') next = (index + 1) % options.length;
    else if (event.key === 'ArrowLeft' || event.key === 'ArrowUp') next = (index - 1 + options.length) % options.length;
    else if (event.key === 'Home') next = 0;
    else if (event.key === 'End') next = options.length - 1;
    if (next !== null) {
      event.preventDefault();
      refs.current[next]?.focus();
      select(next);
      return;
    }
    if (event.key === ' ' || event.key === 'Enter') {
      event.preventDefault();
      select(index);
    }
  };

  return (
    <div className={`flex flex-col gap-1 ${className}`}>
      <span id={labelId} className="text-[12px] font-semibold text-[rgba(0,0,0,0.88)]">{label}</span>
      <div
        role="radiogroup"
        aria-labelledby={labelId}
        aria-disabled={groupBlocked ? 'true' : undefined}
        aria-describedby={groupBlocked ? groupReasonId : undefined}
        className="flex flex-wrap gap-2"
      >
        {options.map((option, index) => {
          const checked = option.value === value;
          const blocked = optionBlocked(option);
          return (
            <div
              key={option.value}
              ref={(el) => { refs.current[index] = el; }}
              role="radio"
              aria-checked={checked}
              aria-disabled={blocked ? 'true' : undefined}
              aria-describedby={blocked ? optionReasonId(index, option) : undefined}
              tabIndex={index === tabStop ? 0 : -1}
              onClick={() => select(index)}
              onKeyDown={(event) => onKeyDown(event, index)}
              className={`inline-flex min-w-[96px] items-start gap-2 rounded-md border px-3 py-2 text-[13px] ${focusRing} ${
                blocked
                  ? 'cursor-not-allowed border-[rgba(0,0,0,0.3)] bg-[rgba(0,0,0,0.04)] text-[rgba(0,0,0,0.6)]'
                  : checked
                    ? 'cursor-pointer border-[rgba(0,0,0,0.88)] bg-white font-semibold text-[rgba(0,0,0,0.88)]'
                    : 'cursor-pointer border-[rgba(0,0,0,0.2)] bg-white text-[rgba(0,0,0,0.88)] hover:border-[rgba(0,0,0,0.45)]'
              }`}
            >
              <span aria-hidden="true"
                className={`mt-0.5 inline-flex h-3.5 w-3.5 shrink-0 items-center justify-center rounded-full border ${
                  blocked ? 'border-[rgba(0,0,0,0.3)]' : 'border-[rgba(0,0,0,0.55)]'
                }`}>
                {checked && <span className={`h-1.5 w-1.5 rounded-full ${blocked ? 'bg-[rgba(0,0,0,0.45)]' : 'bg-[rgba(0,0,0,0.88)]'}`} />}
              </span>
              <span className="flex flex-col">
                <span className="inline-flex items-center gap-1">
                  {option.label}
                  {blocked && <LockIcon />}
                </span>
                {option.description && <span className="text-[12px] font-normal text-[rgba(0,0,0,0.6)]">{option.description}</span>}
              </span>
            </div>
          );
        })}
      </div>
      {groupBlocked && (
        <ReasonText id={groupReasonId} capability={capability} label={label} placement={reasonPlacement} />
      )}
      {options.map((option, index) =>
        option.capability && !option.capability.editable ? (
          <ReasonText key={option.value} id={`${baseId}-opt-${index}`} capability={option.capability}
            label={option.label} placement={reasonPlacement} />
        ) : null,
      )}
    </div>
  );
}

export default GatedRadioGroup;
