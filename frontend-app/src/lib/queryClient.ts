import { QueryClient } from '@tanstack/react-query';
import { createSyncStoragePersister } from '@tanstack/query-sync-storage-persister';
import { persistQueryClient } from '@tanstack/react-query-persist-client';

export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 60 * 1000,          // 60s — data is fresh, no background refetch
      gcTime: 5 * 60 * 1000,         // 5min — keep in memory after unmount
      retry: 1,
      refetchOnWindowFocus: true,
      refetchOnReconnect: true,
    },
  },
});

// Persist React Query cache to localStorage so it survives page reloads.
// Max age 24h — after that, stale cache is discarded and fresh data is fetched.
const persister = createSyncStoragePersister({
  storage: window.localStorage,
  key: 'xamops-rq-cache',
  throttleTime: 1000,
});

persistQueryClient({
  queryClient,
  persister,
  maxAge: 24 * 60 * 60 * 1000,
});
