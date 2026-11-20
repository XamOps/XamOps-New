import React, { useId, useState } from 'react';
import { Icon } from '@iconify/react';
import { riskVisual } from '../datastores/datastoreVisuals';
import { CONSOLE_ONLY_TOOLTIP } from '../datastores/ui/ConsoleOnly';

/**
 * The Matters console's look for the Misconfigurations page, read from its public bundle and CSS:
 * primary text #292a2e, secondary #6b6e76, tertiary #525252, panel outline #dddedd, 3 px corners,
 * 14 px body text and 12 px captions.
 */
export const TEXT_PRIMARY = 'text-[#292a2e]';
export const TEXT_SECONDARY = 'text-[#6b6e76]';
export const PANEL = 'rounded-[3px] border border-[#dddedd] bg-white';
/** The console's card caption: 12 px semibold secondary. */
export const CAPTION = 'text-xs font-semibold text-[#6b6e76]';
/** The console's outlined button. */
export const OUTLINED_BUTTON =
  'inline-flex items-center gap-1.5 rounded-[4px] border border-[#dddedd] bg-white px-3 py-1.5 text-sm font-medium text-[#292a2e] transition-colors hover:bg-[#f9f9f9] focus:outline-none focus-visible:ring-2 focus-visible:ring-[rgba(0,0,0,0.25)]';

export const Dash: React.FC = () => <span className="text-sm text-[#8c8f96]">-</span>;

/** A number in the console's grey 3 px box (Affected Resources, Sensitives). */
export const CountBox: React.FC<{ children: React.ReactNode; title?: string }> = ({ children, title }) => (
  <span
    title={title}
    className={`inline-flex h-6 min-w-[60px] max-w-[100px] items-center justify-center whitespace-nowrap rounded-[3px] bg-[#f2f2f3] px-2.5 text-sm font-medium tabular-nums ${TEXT_PRIMARY}`}
  >
    {children}
  </span>
);

/** The console's dashed-circle "Open" icon. */
const OpenIcon: React.FC = () => (
  <svg viewBox="0 0 16 16" width={16} height={16} aria-hidden="true" className="flex-shrink-0 text-[#0288d1]">
    <circle cx="8" cy="8" r="6.25" fill="none" stroke="currentColor" strokeWidth="1.5" strokeDasharray="2.6 2" />
  </svg>
);

/** The Status cell: the dashed circle and "Open". */
export const OpenStatus: React.FC<{ label: string }> = ({ label }) => (
  <span className={`inline-flex items-center gap-1.5 text-sm ${TEXT_PRIMARY}`}>
    <OpenIcon />
    {label}
  </span>
);

/** The drawer's Status chip: "Open" on the console's light blue. */
export const OpenChip: React.FC<{ label: string }> = ({ label }) => (
  <span className="inline-flex w-fit rounded-[3px] bg-[#e1f5fe] px-2 py-0.5 text-xs font-medium text-[#0288d1]">{label}</span>
);

/** The Severity cell: the chevron icon and the label, as the console draws it. */
export const SeverityCell: React.FC<{ level: string | null; label: string | null }> = ({ level, label }) => {
  if (!level) return <Dash />;
  const visual = riskVisual(level);
  return (
    <span className={`inline-flex items-center gap-1 text-sm ${TEXT_PRIMARY}`}>
      <Icon icon={visual.icon} width={18} className={visual.cls} />
      {label ?? level}
    </span>
  );
};

/** The console's compact "Powered by Upwind" badge under an Upwind finding's name. */
export const UpwindBadge: React.FC = () => (
  <span className="inline-flex flex-shrink-0 items-center rounded border border-[#c6c6c6] bg-[#f4f4f4] px-1 text-[10px] font-medium leading-4 text-[#525252]">
    Powered by Upwind
  </span>
);

/** The console's white bordered "+ N" after the shown chips; the hidden values are its title. */
export const MoreBadge: React.FC<{ hidden: readonly string[] }> = ({ hidden }) => (
  <span
    title={hidden.join(', ')}
    className={`inline-flex h-6 flex-shrink-0 items-center whitespace-nowrap rounded border border-[#c6c6c6] bg-white px-2.5 text-xs tabular-nums ${TEXT_PRIMARY}`}
  >
    + {hidden.length}
  </span>
);

/**
 * An element the API has no data for, in its console place: greyed, focusable, with the "Disabled by
 * admin" tooltip on hover and focus, and nothing it could show. Escape while the tooltip shows hides it
 * and is stopped there. `label` names it for assistive technology.
 */
export const ConsoleOnlyRegion: React.FC<{
  label: string;
  className?: string;
  children?: React.ReactNode;
  /** `inline` for a value inside a line of text. */
  inline?: boolean;
}> = ({ label, className = '', children, inline }) => {
  const tooltipId = useId();
  const [visible, setVisible] = useState(false);
  const Wrapper = inline ? 'span' : 'div';
  return (
    <Wrapper className={`relative ${inline ? 'inline-flex' : 'flex'} ${className}`}>
      <Wrapper
        role="group"
        aria-label={label}
        aria-disabled="true"
        aria-describedby={tooltipId}
        tabIndex={0}
        onMouseEnter={() => setVisible(true)}
        onMouseLeave={() => setVisible(false)}
        onFocus={() => setVisible(true)}
        onBlur={() => setVisible(false)}
        onKeyDown={e => {
          if (e.key === 'Escape' && visible) {
            e.stopPropagation();
            setVisible(false);
          }
        }}
        className={`${inline ? 'inline-flex' : 'flex w-full'} cursor-not-allowed opacity-50 focus:outline-none focus-visible:ring-2 focus-visible:ring-[rgba(0,0,0,0.25)]`}
      >
        {children}
      </Wrapper>
      <span
        id={tooltipId}
        role="tooltip"
        hidden={!visible}
        className="pointer-events-none absolute left-1/2 top-full z-50 mt-1.5 -translate-x-1/2 whitespace-nowrap rounded-md bg-gray-900 px-2 py-1 text-[11px] font-normal text-white shadow-lg"
      >
        {CONSOLE_ONLY_TOOLTIP}
      </span>
    </Wrapper>
  );
};
