import React, { Suspense, lazy } from 'react';
import { Navigate, useParams } from 'react-router-dom';
import '../styles/xampulse.css';
import { VALID_XAMPULSE_TABS, type XamPulseTab } from '../constants/xampulseTabs';
import { ViewContextProvider } from '../hooks/xampulse/useViewContext';
import { ChartSyncProvider } from '../components/XamPulse/charts/ChartSync';
import { XamPulseShell } from '../components/XamPulse/shell/XamPulseShell';
import { LoadingGrid } from '../components/XamPulse/ui/ui';

const TABS: Record<XamPulseTab, React.LazyExoticComponent<React.ComponentType>> = {
  overview: lazy(() => import('./xampulse/OverviewTab')),
  hosts: lazy(() => import('./xampulse/HostsTab')),
  containers: lazy(() => import('./xampulse/ContainersTab')),
  logs: lazy(() => import('./xampulse/LogsTab')),
  metrics: lazy(() => import('./xampulse/MetricsTab')),
  alerts: lazy(() => import('./xampulse/AlertsTab')),
  insights: lazy(() => import('./xampulse/InsightsTab')),
  setup: lazy(() => import('./xampulse/SetupTab')),
};
const HostPage = lazy(() => import('./xampulse/HostPage'));
const ContainerPage = lazy(() => import('./xampulse/ContainerPage'));

/**
 * XamPulse (Observability 1.0). Routes: /xampulse/:tab, /xampulse/hosts/:instanceId (Host 360) and
 * /xampulse/containers/:instanceId/:name (Container 360).
 * Independent of the Beta Observability page.
 */
const XamPulsePage: React.FC = () => {
  const { tab, instanceId, name } = useParams<{ tab?: string; instanceId?: string; name?: string }>();
  const effective = instanceId ? (name ? 'containers' : 'hosts') : tab;
  if (!effective || !VALID_XAMPULSE_TABS.has(effective)) {
    return <Navigate to="/xampulse/overview" replace />;
  }
  const active = effective as XamPulseTab;
  // A host deep link opens the full Host 360 page; the Hosts tab stays highlighted in the shell.
  const Tab = instanceId ? (name ? ContainerPage : HostPage) : TABS[active];
  return (
    <ViewContextProvider>
      <ChartSyncProvider>
        <XamPulseShell tab={instanceId && !name ? 'host' : active}>
          <Suspense fallback={<LoadingGrid count={4} height={120} />}>
            <Tab />
          </Suspense>
        </XamPulseShell>
      </ChartSyncProvider>
    </ViewContextProvider>
  );
};

export default XamPulsePage;
