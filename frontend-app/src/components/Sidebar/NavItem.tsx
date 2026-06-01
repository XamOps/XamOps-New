import React from 'react';
import { Link } from 'react-router-dom';
import clsx from 'clsx';
import { useTheme } from '../../contexts/ThemeContext';

interface NavItemProps {
  href: string;
  icon: string;
  label: string;
  gradientClass?: string;
  isActive?: boolean;
  onClick?: (e: React.MouseEvent<HTMLAnchorElement>) => void;
}

const NavItem: React.FC<NavItemProps> = ({
  href,
  icon,
  label,
  isActive = false,
  onClick,
}) => {
  const { theme } = useTheme();
  const isReactRoute = !href.includes('.html');

  const className = clsx(
    'nav-item flex items-center gap-2 rounded-md transition-colors duration-150 ease-in-out px-2 h-[33px]',
    theme === 'dark'
      ? isActive
        ? 'bg-[rgba(255,255,255,0.12)] text-white font-semibold'
        : 'text-[rgba(255,255,255,0.5)] hover:bg-[rgba(255,255,255,0.08)] hover:text-white font-normal'
      : isActive
        ? 'bg-[rgba(0,0,0,0.08)] text-[rgba(0,0,0,0.88)] font-semibold'
        : 'text-[rgba(0,0,0,0.5)] hover:bg-[rgba(0,0,0,0.06)] hover:text-[rgba(0,0,0,0.88)] font-normal'
  );

  const content = (
    <>
      <i className={clsx(icon, 'w-4 text-center text-[13px] flex-shrink-0')} />
      <span className="text-[13px] truncate whitespace-nowrap overflow-hidden">
        {label}
      </span>
    </>
  );

  if (isReactRoute) {
    return (
      <Link to={href} className={className}>
        {content}
      </Link>
    );
  }

  return (
    <a href={href} onClick={onClick} className={className}>
      {content}
    </a>
  );
};

export default NavItem;
