/**
 * The app's "This page isn't available" screen, rendered in place of a page the current user may not
 * open. Used by XamTabRouteGuard and by the XamLens (DSPM) admin route.
 */
import React from 'react';
import { Link } from 'react-router-dom';
import { Icon } from '@iconify/react';

const PageUnavailable: React.FC = () => (
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

export default PageUnavailable;
