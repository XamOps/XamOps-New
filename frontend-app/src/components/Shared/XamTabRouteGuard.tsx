/**
 * Blocks direct URL access to XamOps pages hidden by a platform-wide rule
 * (BillOps admin User Manager → XamOps Page Visibility).
 *
 * Only platform-wide HIDE rules block here. A page left out of a user's own tab list stays
 * reachable by URL, exactly as before, so links in emails and notifications keep working for
 * restricted members. Non-XamOps users and routes outside the catalog pass straight through.
 */
import React from 'react';
import { Link, useLocation } from 'react-router-dom';
import { Icon } from '@iconify/react';
import { useXamTabAccess } from '../../contexts/XamTabAccessContext';
import { xamTabKeyForPath } from '../../constants/xamopsTabs';
import XamOpsLoader from './XamOpsLoader';

const XamTabRouteGuard: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const { pathname } = useLocation();
  const { isXamops, loaded, gate } = useXamTabAccess();

  if (!isXamops) return <>{children}</>;

  const tabKey = xamTabKeyForPath(pathname);
  if (!tabKey) return <>{children}</>;

  // Until the rules are known, a hidden page must not render even briefly: it would fire its API
  // calls and flash content the user should not see.
  if (!loaded) {
    return (
      <div className="flex items-center justify-center w-full min-h-[calc(100dvh-160px)]">
        <XamOpsLoader size={56} delay={300} />
      </div>
    );
  }

  if (!gate.isGloballyHidden(tabKey)) return <>{children}</>;

  return (
    <div className="flex items-center justify-center w-full min-h-[calc(100dvh-160px)] px-6">
      <div className="max-w-md text-center flex flex-col items-center gap-3">
        <div className="w-14 h-14 rounded-xl bg-[rgba(0,0,0,0.04)] border border-[rgba(0,0,0,0.08)] flex items-center justify-center">
          <Icon icon="mdi:eye-off-outline" width={26} color="rgba(0,0,0,0.35)" />
        </div>
        <h1 className="text-base font-bold text-[rgba(0,0,0,0.88)]">This page isn't available</h1>
        <p className="text-sm text-[rgba(0,0,0,0.5)]">
          It has been turned off for your account. Contact your administrator if you need access.
        </p>
        <Link
          to="/unified-dashboard"
          className="mt-2 inline-flex items-center gap-2 px-4 py-2 bg-[rgba(0,0,0,0.88)] hover:bg-black text-white text-xs font-semibold rounded-md transition-colors"
        >
          <Icon icon="mdi:view-dashboard-outline" width={14} />
          Go to dashboard
        </Link>
      </div>
    </div>
  );
};

export default XamTabRouteGuard;
