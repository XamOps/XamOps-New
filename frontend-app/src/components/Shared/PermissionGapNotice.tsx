/**
 * Inline permission-gap notice for placing next to empty tables or sections.
 *
 * Matches BillOps ticket detail card styling. Sits inline where data would be.
 *
 * Usage:
 *   <PermissionGapNotice services={['ec2']} />          // only EC2-caused gaps
 *   <PermissionGapNotice />                             // any gap on this page
 */

import React from 'react';
import { Icon } from '@iconify/react';
import { useTheme } from '../../contexts/ThemeContext';
import { usePermissionGapContext } from '../../contexts/PermissionGapContext';

interface PermissionGapNoticeProps {
  /** IAM service prefixes this section depends on, e.g. ['ce', 'budgets']. */
  services?: string[];
  /** Override the generated sentence. */
  message?: string;
  className?: string;
}

const PermissionGapNotice: React.FC<PermissionGapNoticeProps> = ({ services, message, className }) => {
  const { theme } = useTheme();
  const { impacted } = usePermissionGapContext();
  const dark = theme === 'dark';

  const relevant = impacted.flatMap(account =>
    account.gaps
      .filter(gap => !services?.length || services.includes(gap.service))
      .map(gap => ({ account, gap }))
  );

  if (relevant.length === 0) return null;

  const actions = [...new Set(relevant.map(({ gap }) => gap.action))];
  const accountLabel = relevant[0].account.accountName || relevant[0].account.accountId;

  return (
    <div
      role="status"
      className={`rounded-lg border px-3 py-2.5 flex items-start gap-2.5 text-sm ${
        dark
          ? 'bg-amber-950/30 border-amber-900/40'
          : 'bg-amber-50 border-amber-200'
      } ${className || ''}`}
    >
      <Icon
        icon="mdi:alert"
        width={16}
        className={`mt-0.5 shrink-0 ${dark ? 'text-amber-400' : 'text-amber-600'}`}
      />
      <div className="min-w-0 flex-1">
        {message ? (
          <p className={dark ? 'text-amber-200' : 'text-amber-900'}>
            {message}
          </p>
        ) : (
          <p className={dark ? 'text-amber-200' : 'text-amber-900'}>
            <span className="font-medium">This section may be incomplete.</span>
            {' '}
            XamOps is not permitted to read{' '}
            {actions.slice(0, 2).map((action, index) => (
              <React.Fragment key={action}>
                {index > 0 && ', '}
                <code className={`text-[11px] font-mono font-semibold ${
                  dark
                    ? 'text-amber-300 bg-amber-900/50'
                    : 'text-amber-800 bg-amber-100'
                } px-1 py-0.5 rounded`}>
                  {action}
                </code>
              </React.Fragment>
            ))}
            {actions.length > 2 && ` and ${actions.length - 2} more`} in {accountLabel}.
          </p>
        )}
      </div>
    </div>
  );
};

export default PermissionGapNotice;
