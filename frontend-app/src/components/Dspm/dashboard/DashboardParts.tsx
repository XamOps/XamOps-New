import React, { useId } from 'react';
import { Icon } from '@iconify/react';

/** A widget's info icon; the console's tooltip text is its title and accessible name. */
export const InfoIcon: React.FC<{ text: string }> = ({ text }) => (
  <span role="img" aria-label={text} title={text} className="inline-flex text-[rgba(0,0,0,0.4)]">
    <Icon icon="mdi:information-outline" width={14} />
  </span>
);

/** One of the page's sections: a heading, its muted subtitle, then the widgets. */
export const DashboardSection: React.FC<{ title: string; subtitle: string; children: React.ReactNode }> = ({
  title, subtitle, children,
}) => {
  const headingId = useId();
  return (
    <section aria-labelledby={headingId} className="space-y-3">
      <div>
        <h2 id={headingId} className="text-sm font-bold text-[rgba(0,0,0,0.88)]">{title}</h2>
        <p className="text-xs text-[rgba(0,0,0,0.5)]">{subtitle}</p>
      </div>
      {children}
    </section>
  );
};
