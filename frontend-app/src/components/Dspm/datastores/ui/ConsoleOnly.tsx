import React, { useId, useState } from 'react';
import { Icon } from '@iconify/react';

/**
 * Greyed controls for actions only the Matters console can perform (scan, mark, export, bulk actions).
 *
 * They are shown so the page reads like the console, but they do nothing and call no API. Each stays
 * focusable with `aria-disabled` rather than `disabled`, so keyboard and screen-reader users can reach it
 * and hear why it is unavailable (NFR-4). A small local tooltip says so on hover and on focus; the shared
 * react-aria tooltip needs a react-aria `Button` trigger, and a disabled one cannot take focus.
 *
 * Event rules, so a control inside a clickable row never opens the row:
 * - a click is cancelled and stopped;
 * - Enter and Space are cancelled and stopped on keydown (which also cancels the click the browser
 *   synthesises for them and the page scroll on Space);
 * - Escape while the tooltip is visible hides it and is stopped, so an enclosing drawer stays open; the
 *   next Escape propagates as usual.
 */

export const CONSOLE_ONLY_TOOLTIP = 'Available in the Matters console';

type Placement = 'top' | 'bottom' | 'left' | 'right';

const PLACEMENT: Record<Placement, string> = {
  top: 'bottom-full left-1/2 mb-1.5 -translate-x-1/2',
  bottom: 'top-full left-1/2 mt-1.5 -translate-x-1/2',
  left: 'right-full top-1/2 mr-1.5 -translate-y-1/2',
  right: 'left-full top-1/2 ml-1.5 -translate-y-1/2',
};

const isActivationKey = (key: string) => key === 'Enter' || key === ' ' || key === 'Spacebar';

function useConsoleOnly() {
  const tooltipId = useId();
  const [visible, setVisible] = useState(false);

  const handlers = {
    onClick: (e: React.MouseEvent) => {
      e.preventDefault();
      e.stopPropagation();
    },
    onKeyDown: (e: React.KeyboardEvent) => {
      if (isActivationKey(e.key)) {
        e.preventDefault();
        e.stopPropagation();
        return;
      }
      if (e.key === 'Escape' && visible) {
        e.stopPropagation();
        setVisible(false);
      }
    },
    onKeyUp: (e: React.KeyboardEvent) => {
      // Browsers activate a button on Space keyup; cancelled here as well as on keydown.
      if (isActivationKey(e.key)) {
        e.preventDefault();
        e.stopPropagation();
      }
    },
    onMouseEnter: () => setVisible(true),
    onMouseLeave: () => setVisible(false),
    onFocus: () => setVisible(true),
    onBlur: () => setVisible(false),
  };

  return { tooltipId, visible, handlers };
}

const Tooltip: React.FC<{ id: string; visible: boolean; placement: Placement }> = ({ id, visible, placement }) => (
  <span
    id={id}
    role="tooltip"
    hidden={!visible}
    className={`pointer-events-none absolute z-50 whitespace-nowrap rounded-md bg-gray-900 px-2 py-1 text-[11px] font-normal normal-case tracking-normal text-white shadow-lg ${PLACEMENT[placement]}`}
  >
    {CONSOLE_ONLY_TOOLTIP}
  </span>
);

type Variant = 'primary' | 'secondary' | 'icon';

const VARIANT: Record<Variant, string> = {
  primary: 'gap-1.5 rounded-lg bg-[rgba(0,0,0,0.88)] px-3 py-1.5 text-xs font-semibold text-white',
  secondary: 'gap-1.5 rounded-lg border border-[rgba(0,0,0,0.12)] bg-white px-3 py-1.5 text-xs font-semibold text-[rgba(0,0,0,0.7)]',
  icon: 'rounded-md p-1 text-[rgba(0,0,0,0.55)]',
};

interface ConsoleOnlyButtonProps {
  /** Visible text. Omit for an icon-only control and pass `label`. */
  children?: React.ReactNode;
  variant?: Variant;
  /** Iconify id rendered before the text. */
  icon?: string;
  /** Accessible name; required when there is no visible text (⋮, ⋯). */
  label?: string;
  /** Where the tooltip opens. Inside tables use `left` or `right`, whose scroll container clips above and below. */
  placement?: Placement;
}

export const ConsoleOnlyButton: React.FC<ConsoleOnlyButtonProps> = ({
  children, variant = 'secondary', icon, label, placement = 'bottom',
}) => {
  const { tooltipId, visible, handlers } = useConsoleOnly();
  return (
    <span className="relative inline-flex">
      <button
        type="button"
        aria-disabled="true"
        aria-describedby={tooltipId}
        aria-label={label}
        {...handlers}
        className={`inline-flex cursor-not-allowed items-center opacity-50 focus:outline-none focus-visible:ring-2 focus-visible:ring-[rgba(0,0,0,0.25)] ${VARIANT[variant]}`}
      >
        {icon && <Icon icon={icon} width={variant === 'icon' ? 16 : 14} />}
        {children}
      </button>
      <Tooltip id={tooltipId} visible={visible} placement={placement} />
    </span>
  );
};

interface ConsoleOnlyCheckboxProps {
  /** Accessible name, e.g. "Select all rows". */
  label: string;
  placement?: Placement;
}

/** An unchecked, greyed checkbox for the console's bulk selection. */
export const ConsoleOnlyCheckbox: React.FC<ConsoleOnlyCheckboxProps> = ({ label, placement = 'top' }) => {
  const { tooltipId, visible, handlers } = useConsoleOnly();
  return (
    <span className="relative inline-flex">
      <span
        role="checkbox"
        aria-checked="false"
        aria-disabled="true"
        aria-label={label}
        aria-describedby={tooltipId}
        tabIndex={0}
        {...handlers}
        className="inline-flex cursor-not-allowed rounded opacity-50 focus:outline-none focus-visible:ring-2 focus-visible:ring-[rgba(0,0,0,0.25)]"
      >
        <span className="h-3.5 w-3.5 rounded-[3px] border border-[rgba(0,0,0,0.3)] bg-[rgba(0,0,0,0.03)]" />
      </span>
      <Tooltip id={tooltipId} visible={visible} placement={placement} />
    </span>
  );
};
