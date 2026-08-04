import { useCallback, useState } from 'react';
import type { ResourceDetail } from '../../types/xamsecure';
import ResourceDrawer from './ResourceDrawer';

type Loader = ((resourceId: string) => Promise<ResourceDetail>) | undefined;

/**
 * Shared wiring for the cross-domain resource drawer. Each tab gets the same open
 * handler and the same rendered drawer, so behavior can't drift between tabs.
 *
 * Returns `open` for ResourceLink, and `drawer` to render at the end of the tab.
 */
export function useResourceDrawer(loadResourceDetail: Loader) {
  const [resourceId, setResourceId] = useState<string | null>(null);
  const [detail, setDetail] = useState<ResourceDetail | null>(null);
  const [isLoading, setIsLoading] = useState(false);

  const open = useCallback(async (id: string) => {
    if (!loadResourceDetail) return;
    setResourceId(id);
    setDetail(null);
    setIsLoading(true);
    try {
      setDetail(await loadResourceDetail(id));
    } catch {
      setDetail(null);
    } finally {
      setIsLoading(false);
    }
  }, [loadResourceDetail]);

  const close = useCallback(() => { setResourceId(null); setDetail(null); }, []);

  const drawer = resourceId ? (
    <ResourceDrawer resourceId={resourceId} detail={detail} isLoading={isLoading} onClose={close} />
  ) : null;

  return { open, drawer, enabled: Boolean(loadResourceDetail) };
}
