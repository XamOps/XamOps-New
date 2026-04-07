import React from 'react';
import clsx from 'clsx';

import { useTheme } from '../../contexts/ThemeContext';

interface NavSectionProps {
  icon: string;
  label: string;
  gradientClass?: string;
  badge?: string;
  isOpen: boolean;
  onToggle: () => void;
  children: React.ReactNode;
  direction?: 'up' | 'down';
}

const NavSection: React.FC<NavSectionProps> = ({
  icon,
  label,
  gradientClass = 'from-[#704fe6] to-[#9171f8]',
  badge,
  isOpen,
  onToggle,
  children,
  direction = 'down',
}) => {
  const { theme } = useTheme();

  return (
    <div className="relative">
      {/* Dropdown Content - Above for 'up' direction (Absolute positioning to prevent shift) */}
      <div className={clsx(
        'absolute bottom-full left-0 w-full mb-2 transition-[grid-template-rows,opacity,transform] duration-200 ease-[cubic-bezier(0.2,1,0.2,1)] rounded-xl shadow-2xl z-50 overflow-hidden grid will-change-[grid-template-rows,opacity,transform]',
        theme === 'dark' ? 'bg-slate-900 border border-slate-800' : 'bg-white border border-gray-200',
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
        aria-expanded={isOpen}
        aria-controls={`navsection-${label.replace(/\s+/g, '-').toLowerCase()}`}
        className={clsx(
          'sidebar-section w-full flex items-center rounded-lg transition-all duration-300 ease-in-out px-3 py-2.5 justify-between',
          isOpen
            ? `bg-gradient-to-r ${gradientClass} text-white shadow-lg shadow-purple-500/25 ring-1 ring-white/10`
            : theme === 'dark'
              ? 'text-slate-400 hover:bg-slate-800 hover:text-emerald-400'
              : 'text-gray-700 hover:bg-gray-100'
        )}
      >
        <div className="flex items-center overflow-hidden gap-2">
          <i className={clsx(icon, 'w-5 text-center mr-1')} />
          <span className="text-[13px] font-medium truncate">{label}</span>
          {badge && (
            <span className="flex-shrink-0 text-[9px] font-bold uppercase tracking-wide px-1.5 py-0.5 rounded-full bg-amber-400/20 text-amber-300 border border-amber-400/30">
              {badge}
            </span>
          )}
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
      <div
        id={`navsection-${label.replace(/\s+/g, '-').toLowerCase()}`}
        role="region"
        aria-label={label}
        className={clsx(
          'grid transition-[grid-template-rows,opacity,margin] duration-200 ease-[cubic-bezier(0.2,1,0.2,1)] overflow-hidden will-change-[grid-template-rows,opacity]',
          isOpen && direction === 'down'
            ? 'grid-rows-[1fr] opacity-100 mt-2'
            : 'grid-rows-[0fr] opacity-0 mt-0'
        )}
      >
        <div className={clsx(
          'min-h-0 space-y-1 transition-all duration-300 ml-4 border-l-2 pl-4',
          theme === 'dark' ? 'border-slate-800' : 'border-gray-200'
        )}>
          {children}
        </div>
      </div>
    </div>
  );
};

export default NavSection;
