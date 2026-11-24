/**
 * Blocks direct URL access to XamOps pages hidden by a platform-wide rule
 * (BillOps admin User Manager → XamOps Page Visibility).
 *
 * Only platform-wide HIDE rules block here. A page left out of a user's own tab list stays
 * reachable by URL, exactly as before, so links in emails and notifications keep working for
 * restricted members. Non-XamOps users and routes outside the catalog pass straight through.
 */
import React from 'react';
import { useLocation } from 'react-router-dom';
import { useXamTabAccess } from '../../contexts/XamTabAccessContext';
import { xamTabKeyForPath } from '../../constants/xamopsTabs';
import XamOpsLoader from './XamOpsLoader';
import PageUnavailable from './PageUnavailable';

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

  return <PageUnavailable />;
};

export default XamTabRouteGuard;
