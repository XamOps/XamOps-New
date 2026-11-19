/**
 * AutoSpotting shared shell (design-ux-ops.md §2). One provider-neutral page: KPI strip, filters, the
 * group table and the detail panel. The provider comes from the selected account; everything that
 * differs lives in the provider adapter. Built on the v2 API only (through `autospottingApi`).
 *
 * Ships behind `autospotting.ui.shell-enabled` (see AutoSpottingRoute); with it false the legacy
 * pages render as before.
 */
import React, { useCallback, useMemo, useState } from 'react';
import { useParams, useSearchParams } from 'react-router-dom';
import { useAccounts } from '../../contexts/AccountContext';
import { useAutoSpottingConfig } from '../../hooks/autospotting/useAutoSpottingConfig';
import { useSpotGroups } from '../../hooks/autospotting/useSpotGroups';
import { useSpotGroup } from '../../hooks/autospotting/useSpotGroup';
import { useLedgerSummary } from '../../hooks/autospotting/useLedgerSavings';
import { useStackCapabilities } from '../../hooks/autospotting/useStackCapabilities';
import StackUpdateModal from '../../components/AccountManager/StackUpdateModal';
import UpdateStackBanner from './components/UpdateStackBanner';
import RegionCoverageList from './components/RegionCoverageList';
import { useRegionCoverage } from '../../hooks/autospotting/useRegionCoverage';
import { AutoSpottingApiError, disableLegacyGroup, type GroupRow } from '../../api/autospottingApi';
import { awsAdapter } from './adapters/awsAdapter';
import { gcpAdapter } from './adapters/gcpAdapter';
import type { ProviderAdapter } from './adapters/types';
import KpiStrip from './components/KpiStrip';
import Filters, { EMPTY_FILTERS, type FilterValues } from './components/Filters';
import GroupTable, { rowKey } from './components/GroupTable';
import DetailPanel, { type PanelAction, type TabId } from './components/DetailPanel';
import ViewSwitch, { VIEWS, type ViewId } from './components/ViewSwitch';
import AnalyticsView from './views/AnalyticsView';
import ActivityView from './views/ActivityView';
import CommitmentsView from './views/CommitmentsView';
import PlanDialog from './components/PlanDialog';
import RevertDialog from './components/RevertDialog';
import {
  EmptyPanel,
  LoadErrorPanel,
  LoadingPanel,
  PartialBanner,
  PermissionPanel,
  isPermissionFailure,
} from './components/StatePanels';
import { reasonForError } from './components/reasons';

const ADAPTERS: Record<string, ProviderAdapter> = { AWS: awsAdapter, GCP: gcpAdapter };

type DialogState =
  | { type: 'plan'; kind: string; row: GroupRow; policyEdit?: Record<string, unknown> }
  | { type: 'revert'; row: GroupRow }
  | null;

/** `preselect` opens the shell on a cloud (`/gcp-autospotting`): the selected account if it is one, else the first. */
const AutoSpottingPage: React.FC<{ preselect?: 'AWS' | 'GCP' }> = ({ preselect }) => {
  const { accounts, selectedAccountIds } = useAccounts();
  const [params, setParams] = useSearchParams();
  const { groupId: routeGroupId } = useParams<{ groupId?: string }>();

  const selected = accounts.find(a => a.id === selectedAccountIds[0]);
  const account =
    preselect && selected?.provider !== preselect ? accounts.find(a => a.provider === preselect) ?? selected : selected;
  const adapter = ADAPTERS[account?.provider ?? 'AWS'];
  const accountId = account?.dbId ?? null;

  const { data: config } = useAutoSpottingConfig();
  const groups = useSpotGroups(adapter ? accountId : null);
  // The open month's ledger accrual; "Not tracked" while it can't be read (shadow figure, not final).
  const ledgerSummary = useLedgerSummary(adapter ? accountId : null);
  // The CloudFormation stack is AWS only.
  const stackCapabilities = useStackCapabilities(adapter && account?.provider !== 'GCP' ? accountId : null);
  const regionCoverage = useRegionCoverage(adapter && account?.provider === 'AWS' ? accountId : null);
  const [updatingStack, setUpdatingStack] = useState(false);
  const accruingNotFinal = ledgerSummary.data ? Number(ledgerSummary.data.accruingNotFinal.amount) : null;

  const filters: FilterValues = {
    region: params.get('region') ?? '',
    state: params.get('state') ?? '',
    eligibility: params.get('eligibility') ?? '',
    q: params.get('q') ?? '',
  };
  const setFilters = (next: FilterValues) => {
    const p = new URLSearchParams(params);
    (Object.keys(EMPTY_FILTERS) as Array<keyof FilterValues>).forEach(k => {
      if (next[k]) p.set(k, next[k]);
      else p.delete(k);
    });
    setParams(p, { replace: true });
  };

  const requestedView = params.get('view');
  const view: ViewId = VIEWS.some(v => v.id === requestedView) ? (requestedView as ViewId) : 'groups';
  const setView = (next: ViewId) => {
    const p = new URLSearchParams(params);
    if (next === 'groups') p.delete('view');
    else p.set('view', next);
    setParams(p, { replace: true });
  };
  const [tab, setTab] = useState<TabId>('config');
  const [dialog, setDialog] = useState<DialogState>(null);
  const [activeOp, setActiveOp] = useState<{ key: string; operationId: string } | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);

  const items = groups.data?.items ?? [];
  const failed = groups.data?.failedLocations ?? [];
  const partial = groups.data?.partial ?? false;

  const rows = useMemo(() => {
    const q = filters.q.trim().toLowerCase();
    return items.filter(r => {
      if (filters.region && r.location !== filters.region) return false;
      if (filters.state && r.state !== filters.state) return false;
      if (filters.eligibility === 'eligible' && !r.enableAllowed) return false;
      if (filters.eligibility === 'ineligible' && r.enableAllowed) return false;
      if (q && !r.name.toLowerCase().includes(q)) return false;
      return true;
    });
  }, [items, filters.region, filters.state, filters.eligibility, filters.q]);

  const locations = useMemo(() => Array.from(new Set(items.map(r => r.location))).sort(), [items]);

  const selectedParam = params.get('group');
  const selectedRow =
    items.find(r => (routeGroupId && r.id === routeGroupId) || (selectedParam && (rowKey(r) === selectedParam || r.groupKey === selectedParam))) ?? null;
  const detailQuery = useSpotGroup(accountId, selectedRow?.id ?? null);

  const select = useCallback(
    (row: GroupRow) => {
      const p = new URLSearchParams(params);
      p.set('group', rowKey(row));
      setParams(p, { replace: true });
      setTab('config');
    },
    [params, setParams],
  );
  const closePanel = () => {
    const p = new URLSearchParams(params);
    p.delete('group');
    setParams(p, { replace: true });
  };

  const onAction = async (action: PanelAction) => {
    if (!selectedRow) return;
    setActionError(null);
    if (action.kind === 'plan' && action.planKind) {
      setDialog({ type: 'plan', kind: action.planKind, row: selectedRow });
    } else if (action.kind === 'revert') {
      setDialog({ type: 'revert', row: selectedRow });
    } else if (account && adapter?.provider === 'GCP') {
      setActionError('Turn off Spot for this group from the legacy GCP AutoSpotting page.');
    } else if (account) {
      // The Phase A disable for a legacy group; used only while the cloud's v2 mutations are off.
      try {
        await disableLegacyGroup(account.id, selectedRow.location, selectedRow.name);
        await groups.refetch();
      } catch (e) {
        setActionError(e instanceof AutoSpottingApiError ? reasonForError(e) : 'Request failed. Try again.');
      }
    }
  };

  const onConfirmed = (operationId: string) => {
    if (dialog) setActiveOp({ key: rowKey(dialog.row), operationId });
    setDialog(null);
    setTab('progress');
    void groups.refetch();
  };

  if (!adapter) {
    return (
      <div className="p-6 text-sm text-gray-600">
        {`AutoSpotting for ${account?.provider ?? 'this cloud'} is not available in this view yet.`}
      </div>
    );
  }

  const providerCfg = config?.providers?.[adapter.provider];
  const error = groups.error;
  const permissionError =
    (error instanceof AutoSpottingApiError && (error.status === 403 || error.code === 'PERMISSION')) ||
    isPermissionFailure(items, failed);

  let body: React.ReactNode;
  if (accountId === null) {
    body = (
      <p className="text-sm text-gray-600">
        {adapter.provider === 'GCP'
          ? 'Select a GCP project to see its Managed Instance Groups.'
          : 'Select an AWS account to see its Auto Scaling Groups.'}
      </p>
    );
  } else if (groups.isLoading) {
    body = <LoadingPanel />;
  } else if (permissionError) {
    body = <PermissionPanel guidance={adapter.permissionGuidance('PERMISSION')} groupNoun={adapter.groupNoun} />;
  } else if (error && !groups.data) {
    body = <LoadErrorPanel error={error} onRetry={() => void groups.refetch()} />;
  } else if (items.length === 0 && !partial) {
    body = <EmptyPanel groupNoun={adapter.groupNoun} />;
  } else {
    body = (
      <div className="flex flex-col gap-4 lg:flex-row">
        <div className="min-w-0 flex-1">
          {rows.length === 0 ? (
            <p className="text-sm text-gray-600">No groups match these filters.</p>
          ) : (
            <GroupTable
              rows={rows}
              adapter={adapter}
              selectedKey={selectedRow ? rowKey(selectedRow) : null}
              onSelect={select}
            />
          )}
        </div>
        {selectedRow && (
          <div className="lg:w-[28rem]">
            <DetailPanel
              row={selectedRow}
              detail={detailQuery.data}
              detailError={detailQuery.isError}
              adapter={adapter}
              config={config}
              accountId={accountId}
              activeOperationId={activeOp && activeOp.key === rowKey(selectedRow) ? activeOp.operationId : null}
              tab={tab}
              onTab={setTab}
              onAction={a => void onAction(a)}
              onEditPolicy={(kind, policyEdit) => setDialog({ type: 'plan', kind, row: selectedRow, policyEdit })}
              onClose={closePanel}
            />
          </div>
        )}
      </div>
    );
  }

  return (
    <div className="space-y-4 p-4 text-black">
      <header className="flex items-center justify-between">
        <div className="flex items-center gap-2">
          <img src={adapter.icon} alt="" className="h-6 w-6" />
          <h1 className="text-xl font-semibold">AutoSpotting</h1>
          {(providerCfg?.beta ?? true) && (
            <span className="rounded border border-gray-400 px-1.5 py-0.5 text-xs text-gray-600">Beta</span>
          )}
        </div>
        <div className="flex items-center gap-3 text-sm text-gray-600">
          {account && <span>{account.name}</span>}
          <button
            type="button"
            onClick={() => void groups.refresh()}
            disabled={accountId === null}
            className="rounded border border-black px-3 py-1 text-black disabled:border-gray-300 disabled:text-gray-400"
          >
            Refresh
          </button>
        </div>
      </header>

      {config?.dryRun && (
        <p className="rounded border border-gray-400 bg-gray-100 px-3 py-1 text-sm text-gray-600">Dry run</p>
      )}
      <UpdateStackBanner
        capabilities={stackCapabilities.data}
        onUpdate={() => setUpdatingStack(true)}
        consoleHref="/account-manager"
      />
      <RegionCoverageList coverage={regionCoverage.data} />
      {updatingStack && account && (
        <StackUpdateModal accountId={account.id} accountName={account.name} onClose={() => setUpdatingStack(false)} />
      )}

      <ViewSwitch view={view} onChange={setView} />
      {view === 'analytics' && <AnalyticsView accountId={accountId} />}
      {view === 'activity' && <ActivityView accountId={accountId} />}
      {view === 'commitments' && <CommitmentsView accountId={accountId} />}
      {view === 'groups' && (
        <>
        {accountId !== null && !permissionError && (!error || groups.data) && !groups.isLoading && (
          <KpiStrip rows={items} partial={partial} accruingNotFinal={accruingNotFinal} />
        )}
        {items.length > 0 && (
          <Filters values={filters} locations={locations} locationLabel={adapter.locationLabel} onChange={setFilters} />
        )}
        {actionError && (
          <p role="alert" className="text-sm font-medium text-black">
            {actionError}
          </p>
        )}
        {body}
        {partial && (
          <PartialBanner failed={failed} locationLabel={adapter.locationLabel} onRetry={() => void groups.refresh()} />
        )}

        </>
      )}
      {dialog?.type === 'plan' && (
        <PlanDialog
          accountId={accountId}
          row={dialog.row}
          kind={dialog.kind}
          policyEdit={dialog.policyEdit}
          adapter={adapter}
          dryRun={config?.dryRun}
          onClose={() => setDialog(null)}
          onConfirmed={onConfirmed}
          onViewProgress={() => {
            setDialog(null);
            setTab('progress');
          }}
        />
      )}
      {dialog?.type === 'revert' && (
        <RevertDialog
          accountId={accountId}
          row={dialog.row}
          adapter={adapter}
          onClose={() => setDialog(null)}
          onConfirmed={onConfirmed}
          onViewProgress={() => {
            setDialog(null);
            setTab('progress');
          }}
        />
      )}
    </div>
  );
};

export default AutoSpottingPage;
