/**
 * Permission-gap alert matching BillOps ticket detail styling and TicketCallButton UX.
 *
 * Displays inline at the top of affected pages (not fixed bottom) so it integrates
 * cleanly with existing content. Minimal, professional design with clear action buttons.
 */

import React, { useMemo, useState } from 'react';
import { Icon } from '@iconify/react';
import { useLocation } from 'react-router-dom';
import { useTheme } from '../../contexts/ThemeContext';
import { usePermissionGapContext } from '../../contexts/PermissionGapContext';
import { AccountPermissionStatus } from '../../types/permissions';

const DISMISS_KEY = 'permissionGapDismissed';

/**
 * Routes whose data does not come through the XamOps read-only role, so a role or IAM gap says
 * nothing about whether they can load.
 *
 * This banner is mounted once in App.tsx and therefore renders on every route, which made it
 * claim "Account unreachable … No data can be loaded for this account" on top of pages that were
 * loading perfectly well. XamSecure reads synced Upwind data out of our own database; it never
 * calls STS or an AWS data API for the selected account, so the role being broken has no bearing
 * on it. Showing the alert there is a false alarm that undermines the alert everywhere else.
 *
 * Add a prefix here only when a page genuinely has no dependency on the role.
 */
const ROLE_INDEPENDENT_PREFIXES = ['/xamsecure'];

/** Dismissals are keyed by what is actually wrong, so a NEW gap re-surfaces. */
const signatureOf = (impacted: AccountPermissionStatus[]): string =>
  impacted
    .map(account => `${account.accountId}:${account.missingActions.join('|')}`)
    .sort()
    .join(';');

const readDismissed = (): string[] => {
  try {
    return JSON.parse(sessionStorage.getItem(DISMISS_KEY) || '[]');
  } catch {
    return [];
  }
};

const PermissionGapBanner: React.FC = () => {
  const { theme } = useTheme();
  const { impacted, recheck } = usePermissionGapContext();
  const { pathname } = useLocation();
  const [expanded, setExpanded] = useState(false);
  const [dismissed, setDismissed] = useState<string[]>(readDismissed);
  const [notice, setNotice] = useState<string | null>(null);
  const [rechecking, setRechecking] = useState(false);

  const dark = theme === 'dark';
  const signature = useMemo(() => signatureOf(impacted), [impacted]);
  const isDismissed = signature.length > 0 && dismissed.includes(signature);
  const roleIndependent = useMemo(
    () => ROLE_INDEPENDENT_PREFIXES.some(
      p => pathname === p || pathname.startsWith(`${p}/`)),
    [pathname]);

  if (impacted.length === 0 || isDismissed || roleIndependent) return null;

  const unreachable = impacted.some(account => account.health === 'UNREACHABLE');
  const primary = impacted[0];
  const totalActions = impacted.reduce((sum, account) => sum + account.missingActions.length, 0);

  const dismiss = () => {
    const next = [...dismissed, signature].slice(-20);
    sessionStorage.setItem(DISMISS_KEY, JSON.stringify(next));
    setDismissed(next);
  };

  const onRecheck = async () => {
    setRechecking(true);
    setNotice(null);
    const message = await recheck(primary.accountId);
    setNotice(message ?? 'Could not start the re-check. Please try again.');
    setRechecking(false);
  };

  // Color scheme: red (role unreachable) vs amber (missing permission)
  const isUnreachable = unreachable;
  const bgColor = isUnreachable
    ? dark ? 'bg-red-950/30' : 'bg-red-50'
    : dark ? 'bg-amber-950/30' : 'bg-amber-50';
  const borderColor = isUnreachable
    ? dark ? 'border-red-900/40' : 'border-red-200'
    : dark ? 'border-amber-900/40' : 'border-amber-200';
  const textColor = isUnreachable
    ? dark ? 'text-red-200' : 'text-red-900'
    : dark ? 'text-amber-200' : 'text-amber-900';
  const iconColor = isUnreachable
    ? dark ? 'text-red-400' : 'text-red-600'
    : dark ? 'text-amber-400' : 'text-amber-600';

  return (
    <div
      role="status"
      aria-live="polite"
      className={`mx-4 mt-3 rounded-lg border px-3 py-2.5 flex items-start gap-2.5 ${bgColor} ${borderColor}`}
    >
      <Icon
        icon={isUnreachable ? 'mdi:alert-circle' : 'mdi:alert'}
        width={16}
        className={`mt-0.5 shrink-0 ${iconColor}`}
      />

      <div className="min-w-0 flex-1 text-sm">
        <p className={`leading-snug ${textColor}`}>
          <span className="font-semibold">
            {isUnreachable ? 'Account unreachable.' : 'Incomplete data.'}
          </span>
          {' '}
          {primary.summary}
        </p>

        {impacted.length > 1 && (
          <p className={`mt-0.5 text-xs ${textColor} opacity-85`}>
            {impacted.length - 1} other selected account{impacted.length > 2 ? 's' : ''} also affected.
          </p>
        )}

        {notice && (
          <p className={`mt-1 text-xs ${textColor}`}>
            {notice}
          </p>
        )}

        <div className="mt-2 flex flex-wrap items-center gap-2">
          <button
            type="button"
            onClick={() => setExpanded(v => !v)}
            className={`inline-flex items-center gap-1 rounded-md border px-2 py-1 text-[11px] font-medium transition-colors ${
              dark
                ? 'border-slate-700 hover:bg-slate-800 text-slate-300'
                : 'border-gray-300 hover:bg-gray-100 text-gray-700'
            }`}
          >
            <Icon icon={expanded ? 'mdi:chevron-up' : 'mdi:chevron-down'} width={12} />
            {expanded ? 'Hide' : `Show ${totalActions} action${totalActions === 1 ? '' : 's'}`}
          </button>

          <button
            type="button"
            onClick={onRecheck}
            disabled={rechecking}
            className={`inline-flex items-center gap-1 rounded-md border px-2 py-1 text-[11px] font-medium transition-colors disabled:opacity-60 ${
              dark
                ? 'border-emerald-700/50 hover:bg-emerald-900/30 text-emerald-300'
                : 'border-emerald-200 hover:bg-emerald-50 text-emerald-700'
            }`}
          >
            {rechecking ? (
              <>
                <Icon icon="mdi:loading" width={12} className="animate-spin" />
                Re-checking…
              </>
            ) : (
              <>
                <Icon icon="mdi:refresh" width={12} />
                Re-check
              </>
            )}
          </button>

          <button
            type="button"
            onClick={dismiss}
            className={`inline-flex items-center gap-1 rounded-md px-2 py-1 text-[11px] transition-opacity hover:opacity-70 ${textColor}`}
            title="Dismiss"
          >
            <Icon icon="mdi:close" width={12} />
          </button>
        </div>

        {expanded && (
          <div className={`mt-2.5 space-y-2 rounded border-t ${dark ? 'border-slate-700 pt-2.5' : 'border-gray-200 pt-2.5'}`}>
            {impacted.map(account => (
              <div key={account.accountId} className="text-[11px]">
                <div className={`font-medium mb-1 ${textColor}`}>
                  {account.accountName || account.accountId}
                </div>
                <div className="space-y-1">
                  {account.gaps.slice(0, 3).map(gap => (
                    <div
                      key={gap.action}
                      className={`flex items-center gap-1.5 pl-2 py-1 rounded ${
                        dark ? 'bg-slate-800/50' : 'bg-white/50'
                      }`}
                    >
                      <code className={`text-[10px] font-mono ${dark ? 'text-slate-300' : 'text-gray-600'}`}>
                        {gap.action}
                      </code>
                      {gap.regions?.length > 0 && (
                        <span className={`text-[10px] ${dark ? 'text-slate-500' : 'text-gray-500'}`}>
                          {gap.regions.slice(0, 2).join(', ')}
                        </span>
                      )}
                    </div>
                  ))}
                  {account.gaps.length > 3 && (
                    <div className={`text-[10px] pl-2 ${dark ? 'text-slate-500' : 'text-gray-500'}`}>
                      +{account.gaps.length - 3} more
                    </div>
                  )}
                </div>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
};

export default PermissionGapBanner;
