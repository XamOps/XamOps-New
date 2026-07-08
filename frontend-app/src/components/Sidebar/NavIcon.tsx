import React from 'react';
import { Icon } from '@iconify/react';
import clsx from 'clsx';
import { resolveLucide } from './iconMap';

interface NavIconProps {
  /** FontAwesome class string, e.g. "fas fa-server". */
  icon: string;
  /** Styling classes (color, sizing helpers, transitions). */
  className?: string;
}

/**
 * Renders a sidebar icon as a Lucide (outline) SVG when a mapping exists,
 * otherwise falls back to the original FontAwesome glyph so nothing renders
 * blank while the icon set is migrated.
 */
const NavIcon: React.FC<NavIconProps> = ({ icon, className }) => {
  const lucide = resolveLucide(icon);

  if (lucide) {
    return (
      <Icon
        icon={lucide}
        width={16}
        height={16}
        className={clsx('flex-shrink-0', className)}
      />
    );
  }

  return <i className={clsx(icon, className)} aria-hidden="true" />;
};

export default NavIcon;
