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
  gradientClass = 'from-[#4169e1] to-[#5e5ce6]',
  isActive = false,
  onClick,
}) => {
  const { theme } = useTheme();
  // Determine if this is a React route (no .html extension) or an HTML page
  const isReactRoute = !href.includes('.html');

  const className = clsx(
    'nav-item flex items-center rounded-lg transition-all duration-300 ease-in-out px-3 py-2.5 outline-none focus-visible:ring-2 focus-visible:ring-indigo-500 focus-visible:ring-offset-1 dark:focus-visible:ring-offset-slate-900',
    isActive
      ? `bg-gradient-to-r ${gradientClass} text-white shadow-lg shadow-blue-500/25 ring-1 ring-white/10`
      : theme === 'dark'
        ? 'text-slate-400 hover:bg-slate-800 hover:text-indigo-400'
        : 'text-gray-700 hover:bg-gray-100'
  );

  const content = (
    <>
      <i className={clsx(icon, 'w-5 text-center mr-3')} />
      <span className="text-[13px] font-medium truncate whitespace-nowrap overflow-hidden transition-all duration-300">
        {label}
      </span>
    </>
  );

  // Use React Router Link for React routes, regular anchor for HTML pages
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
