/**
 * Accessible Disclosure (collapsible) primitive built on react-aria-components.
 * Replaces manual open/close state with grid animation.
 * Provides: button role with aria-expanded, aria-controls,
 * Space/Enter toggle, proper ARIA tree structure.
 *
 * Usage:
 *   <AriaDisclosure title="Advanced Settings" defaultExpanded>
 *     <p>Content here</p>
 *   </AriaDisclosure>
 */

import React from 'react';
import { Disclosure, DisclosurePanel, Button, Heading } from 'react-aria-components';

interface AriaDisclosureProps {
  title: React.ReactNode;
  children: React.ReactNode;
  defaultExpanded?: boolean;
  className?: string;
  titleClassName?: string;
}

export function AriaDisclosure({
  title,
  children,
  defaultExpanded = false,
  className = '',
  titleClassName = '',
}: AriaDisclosureProps) {
  return (
    <Disclosure defaultExpanded={defaultExpanded} className={`group ${className}`}>
      {({ isExpanded }) => (
        <>
          <Heading>
            <Button
              slot="trigger"
              className={[
                'flex items-center justify-between w-full px-4 py-3 text-left',
                'text-sm font-semibold text-gray-900 bg-gray-50 rounded-lg border border-gray-200',
                'hover:bg-gray-100 transition-colors outline-none',
                'focus-visible:ring-2 focus-visible:ring-indigo-500',
                titleClassName,
              ].join(' ')}
            >
              {title}
              <i
                className={`fas fa-chevron-down text-gray-400 transition-transform duration-200 ${
                  isExpanded ? 'rotate-180' : ''
                }`}
              />
            </Button>
          </Heading>
          <DisclosurePanel>
            <div className="pt-2">{children}</div>
          </DisclosurePanel>
        </>
      )}
    </Disclosure>
  );
}
