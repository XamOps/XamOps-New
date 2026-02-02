/**
 * NavSection Component
 *
 * Collapsible navigation section with dropdown menu
 * Converted from: _sidebar.html (sidebar sections with dropdowns)
 */

import React from 'react';
import clsx from 'clsx';

interface NavSectionProps {
  id: string;
  icon: string;
  label: string;
  gradientClass?: string;
  isOpen: boolean;
  onToggle: () => void;
  children: React.ReactNode;
  direction?: 'up' | 'down';
}

const NavSection: React.FC<NavSectionProps> = ({
  id,
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
      {isOpen && direction === 'up' && (
        <div className="absolute bottom-full left-0 w-full mb-2 space-y-1 bg-white border border-gray-200 rounded-xl shadow-2xl p-2 z-50">
          {children}
        </div>
      )}

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
      {isOpen && direction === 'down' && (
        <div className="ml-4 mt-2 space-y-1 border-l-2 border-gray-200 pl-4">
          {children}
        </div>
      )}
    </div>
  );
};

export default NavSection;
