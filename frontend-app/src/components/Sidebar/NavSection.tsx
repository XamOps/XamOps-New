/**
 * NavSection Component
 *
 * Collapsible navigation section with dropdown menu
 * Converted from: _sidebar.html (sidebar sections with dropdowns)
 */

import React from 'react';
import clsx from 'clsx';

interface NavSectionProps {
  icon: string;
  label: string;
  gradientClass?: string;
  isOpen: boolean;
  onToggle: () => void;
  children: React.ReactNode;
  direction?: 'up' | 'down';
}

const NavSection: React.FC<NavSectionProps> = ({
  icon,
  label,
  gradientClass = 'from-green-500 to-teal-600',
  isOpen,
  onToggle,
  children,
  direction = 'down',
}) => {
  return (
    <div className="relative">
      {/* Dropdown Content - Above for 'up' direction (Absolute positioning to prevent shift) */}
      <div className={clsx(
        'absolute bottom-full left-0 w-full mb-2 transition-[grid-template-rows,opacity,transform] duration-200 ease-[cubic-bezier(0.2,1,0.2,1)] bg-white border border-gray-200 rounded-xl shadow-2xl z-50 overflow-hidden grid will-change-[grid-template-rows,opacity,transform]',
        isOpen && direction === 'up'
          ? 'grid-rows-[1fr] opacity-100 p-2 scale-100'
          : 'grid-rows-[0fr] opacity-0 p-0 border-transparent shadow-none scale-95 pointer-events-none'
      )}>
        <div className="min-h-0 space-y-1">
          {children}
        </div>
      </div>

      {/* Section Header */}
      <button
        onClick={onToggle}
        className={clsx(
          'sidebar-section w-full flex items-center justify-between px-4 py-3 rounded-lg transition-all duration-200',
          isOpen
            ? `bg-gradient-to-r ${gradientClass} text-white shadow-md`
            : 'text-gray-700 hover:bg-gray-100'
        )}
      >
        <div className="flex items-center">
          <i className={clsx(icon, 'w-5 text-center mr-3')} />
          <span className="font-medium">{label}</span>
        </div>
        <svg
          className={clsx(
            'w-5 h-5 transition-transform duration-200',
            isOpen && 'transform rotate-180'
          )}
          fill="none"
          stroke="currentColor"
          viewBox="0 0 24 24"
        >
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M19 9l-7 7-7-7" />
        </svg>
      </button>

      {/* Dropdown Content - Below for 'down' direction (Standard flow) */}
      <div className={clsx(
        'grid transition-[grid-template-rows,opacity,margin] duration-200 ease-[cubic-bezier(0.2,1,0.2,1)] overflow-hidden will-change-[grid-template-rows,opacity]',
        isOpen && direction === 'down'
          ? 'grid-rows-[1fr] opacity-100 mt-2'
          : 'grid-rows-[0fr] opacity-0 mt-0'
      )}>
        <div className="min-h-0 ml-4 space-y-1 border-l-2 border-gray-200 pl-4">
          {children}
        </div>
      </div>
    </div>
  );
};

export default NavSection;
