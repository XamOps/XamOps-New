import React from 'react';
import clsx from 'clsx';
import { useTheme } from '../../contexts/ThemeContext';
import NavIcon from './NavIcon';

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
  badge,
  isOpen,
  onToggle,
  children,
  direction = 'down',
}) => {
  const { theme } = useTheme();
  const dark = theme === 'dark';

  return (
    <div className="relative">
      {/* Popover above — for footer sections that open upward */}
      {direction === 'up' && (
        <div className={clsx(
          'absolute bottom-full left-0 w-full mb-1 transition-[grid-template-rows,opacity] duration-300 ease-[cubic-bezier(0.4,0,0.2,1)] rounded-xl shadow-md z-50 overflow-hidden grid will-change-[grid-template-rows,opacity]',
          dark
            ? 'border border-[rgba(255,255,255,0.08)] bg-[#1a1a1a]'
            : 'border border-[rgba(0,0,0,0.08)] bg-white',
          isOpen
            ? 'grid-rows-[1fr] opacity-100 p-1.5'
            : 'grid-rows-[0fr] opacity-0 p-0 border-transparent shadow-none pointer-events-none'
        )}>
          <div className="min-h-0 space-y-0.5">
            {children}
          </div>
        </div>
      )}

      {/* Section trigger row */}
      <button
        onClick={onToggle}
        aria-expanded={isOpen}
        className={clsx(
          'w-full flex items-center gap-2.5 rounded-lg transition-all duration-150 px-2.5 h-[34px] justify-between',
          dark
            ? isOpen
              ? 'bg-[rgba(255,255,255,0.1)] text-white font-semibold'
              : 'text-[rgba(255,255,255,0.55)] hover:bg-[rgba(255,255,255,0.07)] hover:text-white font-medium'
            : isOpen
              ? 'bg-white text-[rgba(0,0,0,0.9)] font-semibold shadow-[0_1px_2px_rgba(0,0,0,0.06),0_1px_3px_rgba(0,0,0,0.05)]'
              : 'text-[rgba(0,0,0,0.6)] hover:bg-[rgba(0,0,0,0.05)] hover:text-[rgba(0,0,0,0.9)] font-medium'
        )}
      >
        <div className="flex items-center gap-2.5 overflow-hidden min-w-0">
          <NavIcon icon={icon} className={clsx(
            'w-4 text-center text-[13px] transition-colors duration-150',
            isOpen
              ? (dark ? 'text-indigo-300' : 'text-indigo-500')
              : (dark ? 'text-[rgba(255,255,255,0.4)]' : 'text-[rgba(0,0,0,0.4)]')
          )} />
          <span className="text-[13px] tracking-[-0.01em] truncate">{label}</span>
          {badge && (
            <span className={clsx(
              'flex-shrink-0 text-[9px] font-semibold uppercase tracking-wide px-1.5 py-0.5 rounded-full',
              dark
                ? 'bg-[rgba(255,255,255,0.1)] text-[rgba(255,255,255,0.4)]'
                : 'bg-[rgba(0,0,0,0.06)] text-[rgba(0,0,0,0.35)]'
            )}>
              {badge}
            </span>
          )}
        </div>
        <svg
          className={clsx(
            'w-3.5 h-3.5 flex-shrink-0 transition-transform duration-200',
            dark ? 'text-[rgba(255,255,255,0.3)]' : 'text-[rgba(0,0,0,0.3)]',
            isOpen && direction === 'down' && 'rotate-180'
          )}
          fill="none"
          stroke="currentColor"
          viewBox="0 0 24 24"
        >
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M19 9l-7 7-7-7" />
        </svg>
      </button>

      {/* Inline children — for main nav sections that open downward */}
      {direction === 'down' && (
        <div
          className={clsx(
            'grid transition-[grid-template-rows,opacity,margin] duration-300 ease-[cubic-bezier(0.4,0,0.2,1)] overflow-hidden',
            isOpen
              ? 'grid-rows-[1fr] opacity-100 mt-0.5'
              : 'grid-rows-[0fr] opacity-0 mt-0'
          )}
        >
          <div className={clsx(
            'min-h-0 space-y-0.5 ml-3 pl-3 border-l',
            dark ? 'border-[rgba(255,255,255,0.08)]' : 'border-[rgba(0,0,0,0.08)]'
          )}>
            {children}
          </div>
        </div>
      )}
    </div>
  );
};

export default NavSection;
