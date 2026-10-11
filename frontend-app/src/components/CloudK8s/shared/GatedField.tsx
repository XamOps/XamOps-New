/**
 * Draft fields committed by a separate Save; reads `editable`
 * (docs/cloudk8s/ui-revamp-design.md §6.5, §11.1, §14).
 *
 * Blocked renderings, never `disabled` or `isDisabled`:
 * - `text` / `number`: the caller's RAC field with `isReadOnly` (the value can't be typed over) and
 *   `inputProps` on its `Input` (`aria-disabled="true"`, `aria-describedby`); no stepper buttons.
 * - `checkbox` / `switch`: a replica with the matching role, `aria-checked` and `aria-disabled`.
 * - `select` / `slider`: RAC 1.16 has no read-only mode for either, so a focusable `role="group"`
 *   labelled "<label>, <value>" holds an `aria-hidden` replica and the value as visible text.
 *   `readOnlyValue` is required for both (a missing one is a type error).
 */
import { useId, type KeyboardEvent, type MouseEvent, type ReactElement } from 'react';
import { Icon } from '@iconify/react';
import type { Capability } from '../../../types/k8sAutomation';
import { LockIcon, ReasonText, type ReasonPlacement } from './ReasonText';
import { SwitchTrack } from './GatedSwitch';

interface BaseProps {
  capability: Capability;
  label: string;
  reasonPlacement?: ReasonPlacement;
  className?: string;
}

export interface TextFieldRenderProps {
  isReadOnly: boolean;
  showSteppers: boolean;
  inputProps: { 'aria-disabled'?: 'true'; 'aria-describedby'?: string };
}

export type GatedFieldProps = BaseProps & (
  | { type: 'text' | 'number'; children: (props: TextFieldRenderProps) => ReactElement }
  | { type: 'checkbox' | 'switch'; isSelected: boolean; children: ReactElement }
  | { type: 'select'; readOnlyValue: string; children: ReactElement }
  | { type: 'slider'; readOnlyValue: string; value: number; minValue: number; maxValue: number; children: ReactElement }
);

const focusRing = 'outline-none focus-visible:ring-2 focus-visible:ring-[rgba(0,0,0,0.88)] focus-visible:ring-offset-2';

const swallow = (event: MouseEvent | KeyboardEvent) => {
  if ('key' in event && event.key !== ' ' && event.key !== 'Enter') return;
  event.preventDefault();
};

function CheckboxBox({ isSelected }: { isSelected: boolean }) {
  return (
    <span aria-hidden="true"
      className="inline-flex h-4 w-4 shrink-0 items-center justify-center rounded border border-[rgba(0,0,0,0.3)] bg-[rgba(0,0,0,0.06)]">
      {isSelected && <Icon icon="mdi:check" width={12} className="text-[rgba(0,0,0,0.6)]" />}
    </span>
  );
}

export function GatedField(props: GatedFieldProps): ReactElement {
  const reasonId = useId();
  const { capability, label, reasonPlacement = 'inline', className = '' } = props;

  if (capability.editable) {
    switch (props.type) {
      case 'text':
      case 'number':
        return props.children({ isReadOnly: false, showSteppers: true, inputProps: {} });
      default:
        return props.children;
    }
  }

  const reason = (
    <ReasonText id={reasonId} capability={capability} label={label} placement={reasonPlacement} />
  );
  const describedBy = { 'aria-disabled': 'true' as const, 'aria-describedby': reasonId };

  let control: ReactElement;
  switch (props.type) {
    case 'text':
    case 'number':
      control = props.children({ isReadOnly: true, showSteppers: false, inputProps: describedBy });
      break;
    case 'checkbox':
    case 'switch':
      control = (
        <button
          type="button"
          role={props.type}
          aria-checked={props.isSelected}
          aria-label={label}
          {...describedBy}
          onClick={swallow}
          onKeyDown={swallow}
          className={`inline-flex cursor-not-allowed items-center gap-2 rounded-md bg-transparent p-0 text-[13px] text-[rgba(0,0,0,0.6)] ${focusRing}`}
        >
          {props.type === 'switch'
            ? <SwitchTrack isSelected={props.isSelected} blocked />
            : <CheckboxBox isSelected={props.isSelected} />}
          <span aria-hidden="true">{label}</span>
        </button>
      );
      break;
    case 'select':
      control = (
        <div role="group" tabIndex={0} aria-label={`${label}, ${props.readOnlyValue}`} {...describedBy}
          className={`inline-flex cursor-not-allowed rounded-md ${focusRing}`}>
          <span aria-hidden="true"
            className="inline-flex min-w-[160px] items-center justify-between gap-2 rounded-md border border-[rgba(0,0,0,0.3)] bg-[rgba(0,0,0,0.04)] px-2.5 py-1.5 text-[13px] text-[rgba(0,0,0,0.6)]">
            <span>{props.readOnlyValue}</span>
            <Icon icon="mdi:chevron-down" width={16} />
          </span>
        </div>
      );
      break;
    case 'slider': {
      const span = props.maxValue - props.minValue;
      const pct = span > 0 ? Math.min(100, Math.max(0, ((props.value - props.minValue) / span) * 100)) : 0;
      control = (
        <div role="group" tabIndex={0} aria-label={`${label}, ${props.readOnlyValue}`} {...describedBy}
          className={`inline-flex cursor-not-allowed items-center gap-3 rounded-md ${focusRing}`}>
          <span aria-hidden="true" className="relative inline-block h-1.5 w-40 rounded-full bg-[rgba(0,0,0,0.1)]" data-slider-replica="">
            <span className="absolute inset-y-0 left-0 rounded-full bg-[rgba(0,0,0,0.3)]" style={{ width: `${pct}%` }} />
            <span className="absolute top-1/2 h-3.5 w-3.5 -translate-x-1/2 -translate-y-1/2 rounded-full border border-[rgba(0,0,0,0.3)] bg-white"
              style={{ left: `${pct}%` }} />
          </span>
          <span aria-hidden="true" className="text-[13px] tabular-nums text-[rgba(0,0,0,0.6)]">{props.readOnlyValue}</span>
        </div>
      );
      break;
    }
  }

  return (
    <span className={`inline-flex flex-col items-start ${className}`} data-gated="blocked">
      <span className="inline-flex items-center gap-1.5">
        {control}
        {reasonPlacement === 'inline' ? <LockIcon /> : reason}
      </span>
      {reasonPlacement === 'inline' && reason}
    </span>
  );
}

export default GatedField;
