/**
 * AlertsSection
 *
 * The card each alert category sits in. Besides the rows, it says which of three things is true
 * when there are none: the check ran and found nothing, the check could not run, or the current
 * severity filter hides what there is. A single empty table could not tell these apart.
 */

import React from 'react';
import { Link } from 'react-router-dom';
import { Icon } from '@iconify/react';
import type { AlertLink, AlertSeverity } from '../../types/alerts';

const ACTION_CLASS =
  'inline-flex items-center gap-1.5 px-2.5 py-1.5 rounded-md border border-[rgba(0,0,0,0.15)] bg-white text-[12px] font-medium text-[rgba(0,0,0,0.75)] hover:bg-[rgba(0,0,0,0.03)] hover:border-[rgba(0,0,0,0.3)] transition-colors whitespace-nowrap';

/** A row's action: quiet, so a column of them does not outweigh the data they sit beside. */
export const AlertActionLink: React.FC<{ link: AlertLink }> = ({ link }) =>
  link.external ? (
    <a href={link.href} target="_blank" rel="noopener noreferrer" className={ACTION_CLASS}>
      <span>{link.label}</span>
      <Icon icon="mdi:open-in-new" width={12} className="opacity-60" />
    </a>
  ) : (
    <Link to={link.href} className={ACTION_CLASS}>
      <span>{link.label}</span>
      <Icon icon="mdi:arrow-right" width={12} className="opacity-60" />
    </Link>
  );

export const SEVERITY_STYLES: Record<AlertSeverity, { badge: string; bar: string; chip: string }> = {
  CRITICAL: {
    badge: 'bg-red-50 text-red-700 border-red-200',
    bar: 'bg-red-500',
    chip: 'bg-red-50 border-red-200 text-red-800 hover:border-red-400',
  },
  HIGH: {
    badge: 'bg-orange-50 text-orange-700 border-orange-200',
    bar: 'bg-orange-500',
    chip: 'bg-orange-50 border-orange-200 text-orange-800 hover:border-orange-400',
  },
  MEDIUM: {
    badge: 'bg-amber-50 text-amber-800 border-amber-200',
    bar: 'bg-amber-400',
    chip: 'bg-amber-50 border-amber-200 text-amber-900 hover:border-amber-400',
  },
  LOW: {
    badge: 'bg-blue-50 text-blue-700 border-blue-200',
    bar: 'bg-blue-400',
    chip: 'bg-blue-50 border-blue-200 text-blue-800 hover:border-blue-400',
  },
  INFORMATIONAL: {
    badge: 'bg-[rgba(0,0,0,0.04)] text-[rgba(0,0,0,0.6)] border-[rgba(0,0,0,0.1)]',
    bar: 'bg-[rgba(0,0,0,0.2)]',
    chip: 'bg-[rgba(0,0,0,0.03)] border-[rgba(0,0,0,0.1)] text-[rgba(0,0,0,0.7)] hover:border-[rgba(0,0,0,0.3)]',
  },
};

export const SeverityBadge: React.FC<{ severity: AlertSeverity }> = ({ severity }) => (
  <span className={`inline-flex items-center px-2 py-0.5 rounded-md border text-[11px] font-semibold whitespace-nowrap ${SEVERITY_STYLES[severity].badge}`}>
    {severity.charAt(0) + severity.slice(1).toLowerCase()}
  </span>
);

interface AlertsSectionProps {
  icon: string;
  title: string;
  description: string;
  /** Rows in this category before the severity filter. */
  total: number;
  /** Rows left after the severity filter. */
  visible: number;
  isLoading: boolean;
  /** A check behind this section did not run. */
  incomplete: boolean;
  /** Said when the check ran and found nothing, e.g. "No cost spikes over $1 in the last 14 days." */
  emptyText: string;
  children: React.ReactNode;
}

const AlertsSection: React.FC<AlertsSectionProps> = ({
  icon,
  title,
  description,
  total,
  visible,
  isLoading,
  incomplete,
  emptyText,
  children,
}) => (
  <section className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm overflow-hidden">
    <header className="px-5 py-4 border-b border-[rgba(0,0,0,0.06)] flex items-center gap-3">
      <div className="w-9 h-9 rounded-lg bg-indigo-50 border border-indigo-100 flex items-center justify-center shrink-0">
        <Icon icon={icon} width={16} className="text-indigo-600" />
      </div>
      <div className="min-w-0 flex-1">
        <h2 className="text-[15px] font-semibold text-[rgba(0,0,0,0.88)] flex items-center gap-2">
          {title}
          {!isLoading && (
            <span className="text-[11px] font-semibold text-[rgba(0,0,0,0.5)] bg-[rgba(0,0,0,0.05)] rounded-full px-2 py-0.5">
              {total}
            </span>
          )}
        </h2>
        <p className="text-[11px] text-[rgba(0,0,0,0.45)]">{description}</p>
      </div>
    </header>

    {isLoading ? (
      <div className="p-5 space-y-3">
        {[0, 1, 2].map((i) => (
          <div key={i} className="h-10 bg-[rgba(0,0,0,0.04)] rounded animate-pulse" />
        ))}
      </div>
    ) : visible > 0 ? (
      <>
        {incomplete && (
          <p className="px-5 py-2 text-[12px] text-amber-800 bg-amber-50 border-b border-amber-100">
            Some checks in this section didn't run, so there may be more — see the error above.
          </p>
        )}
        {children}
      </>
    ) : (
      <div className="px-5 py-6 flex items-center gap-3">
        {incomplete ? (
          <>
            <Icon icon="mdi:help-circle-outline" width={18} className="text-amber-500 shrink-0" />
            <p className="text-[13px] text-[rgba(0,0,0,0.6)]">Couldn't check this — see the error above.</p>
          </>
        ) : total > 0 ? (
          <>
            <Icon icon="mdi:filter-outline" width={18} className="text-[rgba(0,0,0,0.35)] shrink-0" />
            <p className="text-[13px] text-[rgba(0,0,0,0.6)]">
              Nothing at this severity. {total} {total === 1 ? 'alert is' : 'alerts are'} hidden by the filter.
            </p>
          </>
        ) : (
          <>
            <Icon icon="mdi:check-circle-outline" width={18} className="text-emerald-500 shrink-0" />
            <p className="text-[13px] text-[rgba(0,0,0,0.6)]">{emptyText}</p>
          </>
        )}
      </div>
    )}
  </section>
);

export default AlertsSection;
