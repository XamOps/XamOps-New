import { useCallback, useMemo } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';

/**
 * Drop-in replacement for useState when managing active tab.
 * Syncs with the `?tab=` URL search param so:
 *  - Clicking a tab updates the URL (shareable, bookmarkable)
 *  - Loading ?tab=X activates that tab immediately
 *  - Browser back/forward navigates between tabs correctly
 */
export function useTabState<T extends string>(defaultTab: T): [T, (tab: T) => void] {
  const location = useLocation();
  const navigate = useNavigate();

  const activeTab = useMemo<T>(() => {
    const param = new URLSearchParams(location.search).get('tab') as T | null;
    return param ?? defaultTab;
  }, [location.search, defaultTab]);

  const setActiveTab = useCallback(
    (tab: T) => {
      const params = new URLSearchParams(location.search);
      params.set('tab', tab);
      navigate({ search: params.toString() }, { replace: true });
    },
    [navigate, location.search]
  );

  return [activeTab, setActiveTab];
}
