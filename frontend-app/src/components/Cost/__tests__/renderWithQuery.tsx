import React from 'react';
import { render } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

/**
 * Renders a Cost card under its own QueryClient.
 *
 * <p>Every component in this directory reads through `useCurInsight`, which is `useQuery`, so each
 * one needs a provider. Specified here rather than per file so the three assertions that are about
 * cache identity rather than about rendering measure this test's behaviour: `useCurInsight` sets a
 * 30-minute `staleTime`, and a client shared between tests would serve the previous test's payload
 * to the next one.
 *
 * <p>`retry: false` matches `useCurInsight`'s own setting — a CUR read that fails will fail again.
 * Nothing shortens `gcTime`, deliberately: the assertions about reopening a service being free
 * depend on an inactive query staying cached, which is the behaviour the app has. A fresh client
 * per test is what keeps one test's cache out of the next one's, so the default is safe here.
 *
 * <p>Two things worth knowing when testing `ServiceResourcesDrawer` specifically: it renders
 * through `createPortal` into `document.body`, so queries must go through `screen` rather than the
 * returned container; and its label controls call `useLabels` / `useResourceLabelMap`, which are
 * react-query hooks of their own — pass `labels={false}` or mock `hooks/useResourceLabels`.
 */
export const renderWithQuery = (ui: React.ReactElement) => {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  const view = render(<QueryClientProvider client={client}>{ui}</QueryClientProvider>);
  return {
    ...view,
    /**
     * The client itself, for the assertions that are about which cache entries a component
     * touches — the connectivity tab must not read or write the Cloud Map page's own topology
     * entry, and only the client can say whether it did.
     */
    client,
    /**
     * Re-renders with the same client, which is what the three cache-identity assertions need: a
     * prop change has to be measured against the cache the first render populated. The default
     * `rerender` replaces the root and would take the provider with it.
     */
    rerender: (next: React.ReactElement) =>
      view.rerender(<QueryClientProvider client={client}>{next}</QueryClientProvider>),
  };
};
