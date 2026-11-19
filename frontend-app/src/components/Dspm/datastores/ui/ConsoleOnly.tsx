import React, { useId, useState } from 'react';
import { Icon } from '@iconify/react';

/**
 * Greyed controls for actions only the Matters console can perform (scan, mark, bulk actions), and for a
 * page entry the API cannot serve yet (the Data Lineage category cards, variant `card`).
 *
 * They are shown so the page reads like the console, but they do nothing and call no API. Each stays
 * focusable with `aria-disabled` rather than `disabled`, so keyboard and screen-reader users can reach it
 * and hear that it is disabled (NFR-4). A small local tooltip, "Disabled by admin", shows on hover and on
 * focus; it does not send users to the Matters console. The shared react-aria tooltip needs a react-aria
 * `Button` trigger, and a disabled one cannot take focus.
 *
 * Event rules, so a control inside a clickable row never opens the row:
 * - a click is cancelled and stopped;
 * - Enter and Space are cancelled and stopped on keydown (which also cancels the click the browser
 *   synthesises for them and the page scroll on Space);
 * - Escape while the tooltip is visible hides it and is stopped, so an enclosing drawer stays open; the
 *   next Escape propagates as usual.
 */

export const CONSOLE_ONLY_TOOLTIP = 'Disabled by admin';

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

type Variant = 'primary' | 'secondary' | 'icon' | 'link' | 'card' | 'menu' | 'segment';

const VARIANT: Record<Variant, string> = {
  primary: 'gap-1.5 rounded-lg bg-[rgba(0,0,0,0.88)] px-3 py-1.5 text-xs font-semibold text-white',
  secondary: 'gap-1.5 rounded-lg border border-[rgba(0,0,0,0.12)] bg-white px-3 py-1.5 text-xs font-semibold text-[rgba(0,0,0,0.7)]',
  icon: 'rounded-md p-1 text-[rgba(0,0,0,0.55)]',
  link: 'gap-1 text-xs font-semibold text-sky-700',
  card: 'w-full flex-col items-start gap-3 rounded-xl border border-[rgba(0,0,0,0.08)] bg-white p-5 text-left',
  // An entry of a popover list (the More filters dimensions).
  menu: 'w-full gap-2 rounded px-2 py-1.5 text-left text-xs text-[rgba(0,0,0,0.65)]',
  // An unpressed option of a segmented switch (the Dashboard's "DAM").
  segment: 'gap-1.5 rounded-md px-2.5 py-1 text-xs font-semibold text-[rgba(0,0,0,0.55)]',
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
  // A card fills its grid cell and stacks its content, so it is a block flex box without `items-center`;
  // a menu entry fills its list row.
  const card = variant === 'card';
  const block = card || variant === 'menu';
  return (
    <span className={block ? 'relative flex w-full' : 'relative inline-flex'}>
      <button
        type="button"
        aria-disabled="true"
        aria-describedby={tooltipId}
        aria-label={label}
        {...handlers}
        className={`${card ? 'flex' : 'inline-flex items-center'} cursor-not-allowed opacity-50 focus:outline-none focus-visible:ring-2 focus-visible:ring-[rgba(0,0,0,0.25)] ${VARIANT[variant]}`}
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
  /** The state the console reports; drawn with a tick. Defaults to unchecked. */
  checked?: boolean;
  placement?: Placement;
}

/** A greyed checkbox for the console's bulk selection and settings, unchecked unless `checked`. */
export const ConsoleOnlyCheckbox: React.FC<ConsoleOnlyCheckboxProps> = ({ label, checked = false, placement = 'top' }) => {
  const { tooltipId, visible, handlers } = useConsoleOnly();
  return (
    <span className="relative inline-flex">
      <span
        role="checkbox"
        aria-checked={checked ? 'true' : 'false'}
        aria-disabled="true"
        aria-label={label}
        aria-describedby={tooltipId}
        tabIndex={0}
        {...handlers}
        className="inline-flex cursor-not-allowed rounded opacity-50 focus:outline-none focus-visible:ring-2 focus-visible:ring-[rgba(0,0,0,0.25)]"
      >
        {checked ? (
          <span className="inline-flex h-3.5 w-3.5 items-center justify-center rounded-[3px] bg-[rgba(0,0,0,0.88)] text-white">
            <Icon icon="mdi:check" width={11} />
          </span>
        ) : (
          <span className="h-3.5 w-3.5 rounded-[3px] border border-[rgba(0,0,0,0.3)] bg-[rgba(0,0,0,0.03)]" />
        )}
      </span>
      <Tooltip id={tooltipId} visible={visible} placement={placement} />
    </span>
  );
};

/** Classes every control below shares: greyed, not-allowed, with a focus ring. */
const GREYED = 'cursor-not-allowed opacity-50 focus:outline-none focus-visible:ring-2 focus-visible:ring-[rgba(0,0,0,0.25)]';

interface ConsoleOnlyMenuItemProps {
  children: React.ReactNode;
  /** Iconify id rendered before the text. */
  icon?: string;
  placement?: Placement;
}

/** A greyed row of a `role="menu"` list (the drawer's Actions menu). */
export const ConsoleOnlyMenuItem: React.FC<ConsoleOnlyMenuItemProps> = ({ children, icon, placement = 'left' }) => {
  const { tooltipId, visible, handlers } = useConsoleOnly();
  return (
    <span className="relative flex">
      <button
        type="button"
        role="menuitem"
        aria-disabled="true"
        aria-describedby={tooltipId}
        {...handlers}
        className={`flex w-full items-center gap-2 px-3 py-2 text-left text-xs text-[rgba(0,0,0,0.75)] ${GREYED}`}
      >
        {icon && <Icon icon={icon} width={14} className="flex-shrink-0" />}
        {children}
      </button>
      <Tooltip id={tooltipId} visible={visible} placement={placement} />
    </span>
  );
};

interface ConsoleOnlySwitchProps {
  /** Accessible name, e.g. "Real-time scanning". */
  label: string;
  /** The state the console reports. */
  checked: boolean;
  placement?: Placement;
}

/** A greyed on/off switch, drawn as `ToggleSwitch` in the state the console reports. */
export const ConsoleOnlySwitch: React.FC<ConsoleOnlySwitchProps> = ({ label, checked, placement = 'top' }) => {
  const { tooltipId, visible, handlers } = useConsoleOnly();
  return (
    <span className="relative inline-flex">
      <button
        type="button"
        role="switch"
        aria-checked={checked}
        aria-disabled="true"
        aria-label={label}
        aria-describedby={tooltipId}
        {...handlers}
        className={`relative inline-flex h-4 w-7 flex-shrink-0 items-center rounded-full ${GREYED} ${
          checked ? 'bg-[rgba(0,0,0,0.88)]' : 'bg-[rgba(0,0,0,0.15)]'
        }`}
      >
        <span className={`inline-block h-3 w-3 rounded-full bg-white shadow ${checked ? 'translate-x-3.5' : 'translate-x-0.5'}`} />
      </button>
      <Tooltip id={tooltipId} visible={visible} placement={placement} />
    </span>
  );
};

interface ConsoleOnlyRadioProps {
  /** Visible text, also the accessible name. */
  label: string;
  checked: boolean;
  placement?: Placement;
}

/** A greyed radio option with its visible label, in the state the console reports. */
export const ConsoleOnlyRadio: React.FC<ConsoleOnlyRadioProps> = ({ label, checked, placement = 'top' }) => {
  const { tooltipId, visible, handlers } = useConsoleOnly();
  return (
    <span className="relative inline-flex">
      <span
        role="radio"
        aria-checked={checked ? 'true' : 'false'}
        aria-disabled="true"
        aria-describedby={tooltipId}
        tabIndex={0}
        {...handlers}
        className={`inline-flex items-center gap-1.5 rounded text-xs text-[rgba(0,0,0,0.75)] ${GREYED}`}
      >
        <span
          aria-hidden="true"
          className={`inline-flex h-3.5 w-3.5 items-center justify-center rounded-full border ${
            checked ? 'border-[rgba(0,0,0,0.88)]' : 'border-[rgba(0,0,0,0.3)]'
          }`}
        >
          {checked && <span className="h-1.5 w-1.5 rounded-full bg-[rgba(0,0,0,0.88)]" />}
        </span>
        {label}
      </span>
      <Tooltip id={tooltipId} visible={visible} placement={placement} />
    </span>
  );
};

interface ConsoleOnlyFieldProps {
  /** The field's visible label and accessible name. */
  label: string;
  /** What the console shows in it; null or empty draws an empty box. */
  value: string | null;
  /** A taller box that wraps (paths, lists). */
  multiline?: boolean;
  placement?: Placement;
}

/** A greyed, read-only input showing the console's value: every Scan Settings and Endpoint configuration input. */
export const ConsoleOnlyField: React.FC<ConsoleOnlyFieldProps> = ({ label, value, multiline = false, placement = 'top' }) => {
  const { tooltipId, visible, handlers } = useConsoleOnly();
  const labelId = useId();
  return (
    <span className="flex flex-col gap-1">
      <span id={labelId} className="text-[11px] font-semibold text-[rgba(0,0,0,0.6)]">{label}</span>
      <span className="relative flex">
        <span
          role="textbox"
          aria-readonly="true"
          aria-disabled="true"
          aria-multiline={multiline ? 'true' : undefined}
          aria-labelledby={labelId}
          aria-describedby={tooltipId}
          tabIndex={0}
          {...handlers}
          className={`block w-full rounded-lg border border-[rgba(0,0,0,0.12)] bg-[rgba(0,0,0,0.03)] px-2.5 py-1.5 text-xs text-[rgba(0,0,0,0.75)] ${GREYED} ${
            multiline ? 'min-h-[4rem] whitespace-pre-wrap break-words' : 'min-h-[1.875rem] truncate'
          }`}
        >
          {value ?? ''}
        </span>
        <Tooltip id={tooltipId} visible={visible} placement={placement} />
      </span>
    </span>
  );
};
