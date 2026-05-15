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
  // Determine if this is a React route (no .html extension) or an HTML page
  const isReactRoute = !href.includes('.html');

  const className = clsx(
    'nav-item flex items-center rounded-md transition-colors duration-150 ease-in-out px-3 py-2',
    isActive
      ? theme === 'dark'
        ? 'bg-indigo-600/20 text-indigo-400 font-semibold'
        : 'bg-indigo-50 text-indigo-700 font-semibold'
      : theme === 'dark'
        ? 'text-slate-400 hover:bg-slate-800 hover:text-slate-200'
        : 'text-gray-600 hover:bg-gray-100 hover:text-gray-900'
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
