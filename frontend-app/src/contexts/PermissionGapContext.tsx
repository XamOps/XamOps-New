/**
 * Shares one permission-health lookup across everything that needs to explain
 * missing data — the page-level banner and any inline notices a page drops next
 * to an empty table. Without this each consumer would issue its own request on
 * every navigation.
 */

import React, { createContext, ReactNode, useContext } from 'react';
import usePermissionGaps from '../hooks/usePermissionGaps';
import { AccountPermissionStatus } from '../types/permissions';

interface PermissionGapContextValue {
  impacted: AccountPermissionStatus[];
  isLoading: boolean;
  feature: string | null;
  refresh: () => void;
  recheck: (accountId: string) => Promise<string | null>;
}

const PermissionGapContext = createContext<PermissionGapContextValue | undefined>(undefined);

export const PermissionGapProvider: React.FC<{ children: ReactNode }> = ({ children }) => {
  const value = usePermissionGaps();
  return <PermissionGapContext.Provider value={value}>{children}</PermissionGapContext.Provider>;
};

/**
 * Safe outside the provider — returns an empty result rather than throwing, so a
 * page that renders in isolation (tests, storybook) is not broken by it.
 */
export const usePermissionGapContext = (): PermissionGapContextValue => {
  const context = useContext(PermissionGapContext);
  if (!context) {
    return {
      impacted: [],
      isLoading: false,
      feature: null,
      refresh: () => undefined,
      recheck: async () => null,
    };
  }
  return context;
};

export default PermissionGapProvider;
