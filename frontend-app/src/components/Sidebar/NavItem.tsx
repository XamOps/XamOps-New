/**
 * NavItem Component
 *
 * Individual navigation item with active state and icon
 * Converted from: _sidebar.html (navigation links)
 *
 * Uses React Router Link for React routes, regular anchor tags for HTML pages
 */

import React from 'react';
import { Link } from 'react-router-dom';
import clsx from 'clsx';

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
  gradientClass = 'from-indigo-500 to-purple-600',
  isActive = false,
  onClick,
}) => {
  // Determine if this is a React route (no .html extension) or an HTML page
  const isReactRoute = !href.includes('.html');

  const className = clsx(
    'nav-item flex items-center px-4 py-3 rounded-lg transition-all duration-200',
    isActive
      ? `bg-gradient-to-r ${gradientClass} text-white shadow-md`
      : 'text-gray-700 hover:bg-gray-100'
  );

  const content = (
    <>
      <i className={clsx(icon, 'w-5 text-center mr-3')} />
      <span className="font-medium">{label}</span>
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
