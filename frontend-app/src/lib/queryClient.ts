import { QueryClient } from '@tanstack/react-query';

export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 5 * 60 * 1000,     // 5 min — fresh data, no background refetch
      gcTime: 24 * 60 * 60 * 1000,  // 24h — keep in memory after unmount
      retry: 1,
      refetchOnWindowFocus: false,   // prevents reload loop when switching tabs
      refetchOnReconnect: false,     // prevents reload on reconnect
    },
  },
});
