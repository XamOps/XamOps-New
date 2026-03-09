/**
 * Accessible Tooltip primitive built on react-aria-components.
 * Replaces CSS group-hover patterns — works for keyboard + screen readers.
 *
 * Usage:
 *   <TooltipWrapper tip="Actual savings from running Spot">
 *     <button>Hover or focus me</button>
 *   </TooltipWrapper>
 *
 *   // With custom delay:
 *   <TooltipWrapper tip="Help text" delay={0}>
 *     <i className="fas fa-info-circle" />
 *   </TooltipWrapper>
 */

import React from 'react';
import {
  TooltipTrigger,
  Tooltip,
  Button,
  OverlayArrow,
} from 'react-aria-components';

interface TooltipWrapperProps {
  /** Tooltip content */
  tip: React.ReactNode;
  children: React.ReactElement;
  /** Delay in ms before showing (default 400) */
  delay?: number;
  /** Placement of tooltip (default "top") */
  placement?: 'top' | 'bottom' | 'left' | 'right';
}

export function TooltipWrapper({
  tip,
  children,
  delay = 400,
  placement = 'top',
}: TooltipWrapperProps) {
  return (
    <TooltipTrigger delay={delay}>
      {/* Clone the child to pass trigger props; wrap non-button elements in a span */}
      {React.isValidElement(children) ? children : <span>{children}</span>}
      <Tooltip
        placement={placement}
        className="z-[200] max-w-xs px-3 py-2 bg-gray-900 text-white text-xs rounded-lg shadow-xl"
      >
        <OverlayArrow>
          <svg width={8} height={8} viewBox="0 0 8 8" className="fill-gray-900">
            <path d="M0 0 L4 4 L8 0" />
          </svg>
        </OverlayArrow>
        {tip}
      </Tooltip>
    </TooltipTrigger>
  );
}

/**
 * InfoTooltip — a small info icon with an attached tooltip.
 * Replaces the common pattern of:
 *   <div className="group relative ml-2">
 *     <i className="fas fa-info-circle …" />
 *     <div className="hidden group-hover:block absolute …">…</div>
 *   </div>
 *
 * Usage:
 *   <InfoTooltip>Monthly savings if all on-demand instances were converted.</InfoTooltip>
 */
interface InfoTooltipProps {
  children: React.ReactNode;
  placement?: 'top' | 'bottom' | 'left' | 'right';
  iconClass?: string;
}

export function InfoTooltip({
  children,
  placement = 'top',
  iconClass = 'text-gray-400 hover:text-gray-600',
}: InfoTooltipProps) {
  return (
    <TooltipTrigger delay={200}>
      <Button
        aria-label="More information"
        className={`cursor-help bg-transparent border-none p-0 outline-none focus-visible:ring-2 focus-visible:ring-indigo-500 rounded ${iconClass}`}
      >
        <i className="fas fa-info-circle" />
      </Button>
      <Tooltip
        placement={placement}
        className="z-[200] max-w-[260px] px-3 py-2.5 bg-gray-900 text-white text-xs rounded-lg shadow-xl leading-relaxed"
      >
        <OverlayArrow>
          <svg width={8} height={8} viewBox="0 0 8 8" className="fill-gray-900">
            <path d="M0 0 L4 4 L8 0" />
          </svg>
        </OverlayArrow>
        {children}
      </Tooltip>
    </TooltipTrigger>
  );
}
