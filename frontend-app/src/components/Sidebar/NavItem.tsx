import React from 'react';
import { Link } from 'react-router-dom';
import clsx from 'clsx';
import { useTheme } from '../../contexts/ThemeContext';
import NavIcon from './NavIcon';

interface NavItemProps {
  href: string;
  icon: string;
  label: string;
  gradientClass?: string;
  isActive?: boolean;
  onClick?: (e: React.MouseEvent<HTMLAnchorElement>) => void;
  /** Optional hover tooltip describing what the destination contains. */
  tooltip?: string;
  /**
   * Small pill after the label, e.g. "Soon" or "Beta".
   *
   * Mirrors the prop {@link NavSection} already accepts — the top-level Observability, SRE and MediaOps
   * groups carry these, but until now a sub-item could not, so a half-built child had to be either
   * hidden outright or shown as if it were finished. Styled identically so a badged section and a badged
   * sub-item read as the same kind of statement.
   */
  badge?: string;
}

const NavItem: React.FC<NavItemProps> = ({
  href,
  icon,
  label,
  isActive = false,
  onClick,
  tooltip,
  badge,
}) => {
  const { theme } = useTheme();
  const isReactRoute = !href.includes('.html');

  const className = clsx(
    'nav-item flex items-center gap-2.5 rounded-lg transition-all duration-150 ease-in-out px-2.5 h-[34px]',
    theme === 'dark'
      ? isActive
        ? 'bg-[rgba(255,255,255,0.1)] text-white font-semibold'
        : 'text-[rgba(255,255,255,0.55)] hover:bg-[rgba(255,255,255,0.07)] hover:text-white font-medium'
      : isActive
        ? 'bg-white text-[rgba(0,0,0,0.9)] font-semibold shadow-[0_1px_2px_rgba(0,0,0,0.06),0_1px_3px_rgba(0,0,0,0.05)]'
        : 'text-[rgba(0,0,0,0.6)] hover:bg-[rgba(0,0,0,0.05)] hover:text-[rgba(0,0,0,0.9)] font-medium'
  );

  const iconClass = clsx(
    'w-4 text-center text-[13px] transition-colors duration-150',
    isActive
      ? (theme === 'dark' ? 'text-indigo-300' : 'text-indigo-500')
      : (theme === 'dark' ? 'text-[rgba(255,255,255,0.4)]' : 'text-[rgba(0,0,0,0.4)]')
  );

  const content = (
    <>
      <NavIcon icon={icon} className={iconClass} />
      <span className="text-[13px] tracking-[-0.01em] truncate whitespace-nowrap overflow-hidden">
        {label}
      </span>
      {badge && (
        <span className={clsx(
          'ml-auto flex-shrink-0 text-[9px] font-semibold uppercase tracking-wide px-1.5 py-0.5 rounded-full',
          theme === 'dark'
            ? 'bg-[rgba(255,255,255,0.1)] text-[rgba(255,255,255,0.4)]'
            : 'bg-[rgba(0,0,0,0.06)] text-[rgba(0,0,0,0.35)]'
        )}>
          {badge}
        </span>
      )}
    </>
  );

  if (isReactRoute) {
    return (
      <Link to={href} className={className} title={tooltip}>
        {content}
      </Link>
    );
  }

  return (
    <a href={href} onClick={onClick} className={className} title={tooltip}>
      {content}
    </a>
  );
};

export default NavItem;
