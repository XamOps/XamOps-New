import React from 'react';
import clsx from 'clsx';

interface Props {
  /** Whether the group is expanded. */
  isOpen: boolean;
  /** Dark theme, for the connecting rule colour. */
  dark: boolean;
  /**
   * Nesting depth. 2 is a list inside another SubNav (the CloudK8s cluster subtabs), with a
   * tighter indent so its labels still fit the sidebar; 3 is one step deeper with the same nested
   * pattern (a CloudK8s group's subtabs, e.g. Automation › Workload autoscaler › Workloads), its
   * rule under the group label's text. Defaults to 1, which renders exactly as every SubNav did
   * before the prop existed.
   */
  level?: 1 | 2 | 3;
  children: React.ReactNode;
}

/**
 * The indented sub-item list under a nav entry, expanding and collapsing smoothly.
 *
 * Exists because the XamSecure and SOC 2 groups previously rendered their children behind
 * a plain `condition && (...)`, so the list mounted and unmounted outright and appeared
 * instantly while every other nav group eased open. The timing here is copied from
 * NavSection deliberately, so a nested group and a top-level section move identically.
 *
 * HOW THE HEIGHT ANIMATES
 * CSS cannot transition to or from `height: auto`, so the outer element is a grid and the
 * transition runs on `grid-template-rows` between `0fr` and `1fr`. The inner wrapper needs
 * `min-h-0`, otherwise its content establishes a minimum height and the collapsed state
 * never reaches zero.
 *
 * Children stay mounted so there is something to animate. While collapsed the group is
 * `aria-hidden` and takes no pointer events, so it is neither announced nor clickable.
 */
const SubNav: React.FC<Props> = ({ isOpen, dark, level = 1, children }) => (
  <div
    aria-hidden={!isOpen}
    className={clsx(
      'grid overflow-hidden transition-[grid-template-rows,opacity,margin] duration-300 ease-[cubic-bezier(0.4,0,0.2,1)]',
      isOpen ? 'grid-rows-[1fr] opacity-100 mt-0.5' : 'grid-rows-[0fr] opacity-0 mt-0'
    )}
  >
    <div
      className={clsx(
        'min-h-0 space-y-0.5',
        level === 3 ? 'ml-2.5 pl-2' : level === 2 ? 'ml-2 pl-2' : 'ml-3 pl-3',
        'border-l',
        !isOpen && 'pointer-events-none',
        dark ? 'border-[rgba(255,255,255,0.08)]' : 'border-[rgba(0,0,0,0.08)]'
      )}
    >
      {children}
    </div>
  </div>
);

export default SubNav;
