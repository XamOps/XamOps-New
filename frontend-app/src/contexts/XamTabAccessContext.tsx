/**
 * XamOps page access for the signed-in ROLE_XAMOPS user, shared by the Sidebar and the URL guard
 * (components/Shared/XamTabRouteGuard).
 *
 * One request to /api/xamops/team/profile feeds both:
 *   - allowedTabs: the user's own list (empty = unrestricted)
 *   - hiddenTabs / grantedTabs: platform-wide rules from the BillOps admin User Manager → XamOps Page
 *     Visibility, minus the rules this user is an exception to
 *   - mattersDspmEnabled: the XamLens add-on flag
 *
 * The profile is fetched on load and again on navigation (at most every REFRESH_INTERVAL_MS), so an
 * admin's change reaches users on their next page load or navigation without a hard refresh. The
 * previous answer stays in place while a refetch runs, so nothing flickers.
 *
 * Everyone other than ROLE_XAMOPS is unaffected: `loaded` is true straight away and the gate allows
 * everything, the same as before this context existed.
 */
import React, { createContext, useContext, useEffect, useMemo, useRef, useState } from 'react';
import { useLocation } from 'react-router-dom';
import { useAuth } from './AuthContext';
import { makeXamTabGate } from '../constants/xamopsTabs';

const PROFILE_URL = '/api/xamops/team/profile';
const REFRESH_INTERVAL_MS = 30_000;

interface XamProfileAccess {
  allowedTabs: string[];
  hiddenTabs: string[];
  grantedTabs: string[];
  dspmEnabled: boolean;
}

export interface XamTabAccessValue {
  isXamops: boolean;
  /** False only while a ROLE_XAMOPS user's first profile request is in flight. */
  loaded: boolean;
  /** The user's own list; undefined until the first profile answer. */
  allowedTabs: string[] | undefined;
  /** Combined per-user + platform-wide gate. Allows everything for non-XamOps users. */
  gate: ReturnType<typeof makeXamTabGate>;
  dspmEnabled: boolean;
}

const asStringList = (value: unknown): string[] =>
  Array.isArray(value) ? value.filter((v): v is string => typeof v === 'string') : [];

/** Used when there is no provider (and for non-XamOps users): no restriction at all. */
const UNRESTRICTED: XamTabAccessValue = {
  isXamops: false,
  loaded: true,
  allowedTabs: undefined,
  gate: makeXamTabGate(null),
  dspmEnabled: false,
};

const XamTabAccessContext = createContext<XamTabAccessValue>(UNRESTRICTED);

export const XamTabAccessProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const { hasAnyRole } = useAuth();
  const { pathname } = useLocation();
  const isXamops = hasAnyRole('ROLE_XAMOPS');

  const [access, setAccess] = useState<XamProfileAccess | undefined>(undefined);
  const hasAccessRef = useRef(false);
  const lastFetchRef = useRef(0);
  const inFlightRef = useRef(false);
  const mountedRef = useRef(true);

  useEffect(() => {
    mountedRef.current = true;
    return () => { mountedRef.current = false; };
  }, []);

  // Not cancelled on navigation: a redirect right after sign-in would otherwise throw away the
  // first answer and start over. Only an unmount discards a late response.
  useEffect(() => {
    if (!isXamops || inFlightRef.current) return;
    const now = Date.now();
    if (hasAccessRef.current && now - lastFetchRef.current < REFRESH_INTERVAL_MS) return;

    inFlightRef.current = true;
    lastFetchRef.current = now;

    fetch(PROFILE_URL, { credentials: 'include' })
      .then(r => (r.ok ? r.json() : null))
      .then(data => {
        if (!mountedRef.current) return;
        hasAccessRef.current = true;
        setAccess({
          allowedTabs: asStringList(data?.allowedTabs),
          hiddenTabs: asStringList(data?.hiddenTabs),
          grantedTabs: asStringList(data?.grantedTabs),
          dspmEnabled: Boolean(data?.mattersDspmEnabled),
        });
      })
      .catch(() => {
        // Same fallback the Sidebar always had: an unreadable profile means no restriction.
        // A previous good answer is kept rather than thrown away on a transient failure.
        if (!mountedRef.current) return;
        hasAccessRef.current = true;
        setAccess(prev => prev ?? { allowedTabs: [], hiddenTabs: [], grantedTabs: [], dspmEnabled: false });
      })
      .finally(() => {
        inFlightRef.current = false;
      });
  }, [isXamops, pathname]);

  const value = useMemo<XamTabAccessValue>(() => {
    if (!isXamops) return UNRESTRICTED;
    return {
      isXamops: true,
      loaded: access !== undefined,
      allowedTabs: access?.allowedTabs,
      // Unrestricted while loading; consumers check `loaded` first (Sidebar skeleton, route guard loader).
      gate: makeXamTabGate(access?.allowedTabs, access ? { hidden: access.hiddenTabs, granted: access.grantedTabs } : null),
      dspmEnabled: access?.dspmEnabled ?? false,
    };
  }, [isXamops, access]);

  return <XamTabAccessContext.Provider value={value}>{children}</XamTabAccessContext.Provider>;
};

export const useXamTabAccess = (): XamTabAccessValue => useContext(XamTabAccessContext);
