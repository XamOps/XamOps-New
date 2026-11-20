import React, { useCallback, useState } from 'react';
import type { DspmStatus } from '../../../types/dspm';
import type { DatastoreRow } from '../../../types/dspmDatastores';
import { pagesApi } from '../../../hooks/useDspmPages';
import { useLazyResource } from '../../../hooks/useDspmDatastoreDrawer';
import DspmNotReady from '../DspmNotReady';
import { DatastoreDrawerHost } from '../datastores/DatastoreDrawerHost';
import { ErrorNote, LoadingNote } from '../datastores/drawer/TabParts';
import { ConsoleOnlyButton } from '../datastores/ui/ConsoleOnly';
import ActiveSensitiveRegions from './ActiveSensitiveRegions';
import { DashboardSection } from './DashboardParts';
import EnvironmentCards from './EnvironmentCards';
import TopRiskyDatastores from './TopRiskyDatastores';

/**
 * The header's view switch. Classification is the only view with API data; DAM is the console's and
 * greyed (brief 10.1).
 */
const ViewSwitch: React.FC = () => (
  <div role="group" aria-label="Dashboard view" className="inline-flex rounded-lg border border-[rgba(0,0,0,0.1)] bg-[rgba(0,0,0,0.03)] p-0.5">
    <button
      type="button"
      aria-pressed="true"
      className="inline-flex items-center rounded-md bg-white px-2.5 py-1 text-xs font-semibold text-[rgba(0,0,0,0.88)] shadow-sm"
    >
      Classification
    </button>
    <ConsoleOnlyButton variant="segment">DAM</ConsoleOnlyButton>
  </div>
);

const ReadyDashboard: React.FC = () => {
  const dashboard = useLazyResource(pagesApi.dashboard, true);
  // The drawers load their own data; the page keeps only the row that is open.
  const [openRow, setOpenRow] = useState<DatastoreRow | null>(null);
  // Stable, because the drawers put it in a keydown effect's dependencies.
  const close = useCallback(() => setOpenRow(null), []);

  const state = (render: (data: NonNullable<typeof dashboard.data>) => React.ReactNode) => {
    if (dashboard.error) return <ErrorNote error={dashboard.error} onRetry={dashboard.reload} />;
    if (dashboard.data === null) return <LoadingNote />;
    return render(dashboard.data);
  };

  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <h1 className="text-lg font-bold text-[rgba(0,0,0,0.88)]">Dashboard</h1>
        <div className="flex items-center gap-2">
          <ViewSwitch />
          <ConsoleOnlyButton variant="icon" icon="mdi:cog-outline" label="Manage dashboard" placement="left" />
        </div>
      </div>
      <DashboardSection title="Where does sensitive data live" subtitle="Environment breakdown">
        {state(data => (
          <>
            <EnvironmentCards environments={data.environments} />
            <ActiveSensitiveRegions
              regions={data.regions}
              partial={data.environments.some(environment => environment.id === 'cloud' && environment.partial)}
            />
          </>
        ))}
      </DashboardSection>
      <DashboardSection title="Where exactly is the risk concentrated" subtitle="Granular risk detail">
        {state(data => <TopRiskyDatastores topRisky={data.topRisky} onOpen={setOpenRow} />)}
      </DashboardSection>
      {openRow && <DatastoreDrawerHost row={openRow} onClose={close} />}
    </div>
  );
};

interface Props {
  status: DspmStatus;
  onRetry: () => void;
}

/**
 * XamLens Dashboard (design 6.4, brief 10.1): the console's widgets that public API data fills, the
 * environment cards, Active Sensitive Regions (in part, brief 11) and Top Risky Datastores, from one
 * `GET /api/dspm/dashboard`. Every other console widget (Data Coverage, Sensitive Data at Risk, Open
 * Issues, Compliance Control Readiness, the two "by Labels" charts) has no public source and is left out.
 * The DAM view and the gear (Manage dashboard) are the console's and greyed.
 */
const DashboardPage: React.FC<Props> = ({ status, onRetry }) => {
  if (!status.ready) return <DspmNotReady status={status} onRetry={onRetry} />;
  return <ReadyDashboard />;
};

export default DashboardPage;
