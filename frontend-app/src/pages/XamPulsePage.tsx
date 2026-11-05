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

/**
 * XamPulse (Observability 1.0). Routes: /xampulse/:tab and /xampulse/hosts/:instanceId (Resource 360 deep link).
 * Independent of the Beta Observability page.
 */
const XamPulsePage: React.FC = () => {
  const { tab, instanceId } = useParams<{ tab?: string; instanceId?: string }>();
  const effective = instanceId ? 'hosts' : tab;
  if (!effective || !VALID_XAMPULSE_TABS.has(effective)) {
    return <Navigate to="/xampulse/overview" replace />;
  }
  const active = effective as XamPulseTab;
  const Tab = TABS[active];
  return (
    <ViewContextProvider>
      <ChartSyncProvider>
        <XamPulseShell tab={instanceId ? 'host' : active}>
          <Suspense fallback={<LoadingGrid count={4} height={120} />}>
            <Tab />
          </Suspense>
        </XamPulseShell>
      </ChartSyncProvider>
    </ViewContextProvider>
  );
};

export default XamPulsePage;
