import React, { useState, useRef } from 'react';
import { Link } from 'react-router-dom';
import { Icon } from '@iconify/react';
import { gooeyToast } from 'goey-toast';
import type { ASG, SpotCosts } from '../../hooks/useSpotAutomationData';
import { InfoTooltip } from '../ui';
import { Switch } from 'react-aria-components';
import { awsToggleState } from './toggleState';

interface SpotAutomationASGTableProps {
  asgs: ASG[];
  costs: SpotCosts;
  totalInstances: number;
  isLoading: boolean;
  selectedAccountId: string | null;
  onToggleASG: (asg: ASG) => Promise<void>;
  onSyncASG: (asg: ASG) => Promise<{ backfilled: number; message: string }>;
  onRefresh: () => void;
  /** New Spot conversions are paused: Enable is disabled, Disable stays available. */
  conversionsPaused?: boolean;
  /** User-facing message from the last failed fetch, or null. */
  error?: string | null;
  /** Retry handler for the error state. Defaults to onRefresh. */
  onRetry?: () => void;
}

const fmt = (v: number) => new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD', minimumFractionDigits: 2 }).format(v);
const fmtHr = (v: number) => new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD', minimumFractionDigits: 4 }).format(v);

const COL = 'px-4 py-3.5 text-[12px]';
const HEAD = 'px-4 py-3 text-[11px] font-semibold uppercase tracking-[0.07em] text-[rgba(0,0,0,0.45)] bg-[rgba(0,0,0,0.02)]';

const SpotAutomationASGTable: React.FC<SpotAutomationASGTableProps> = ({
  asgs,
  costs,
  totalInstances,
  isLoading,
  selectedAccountId,
  onToggleASG,
  onSyncASG,
  onRefresh,
  conversionsPaused = false,
  error = null,
  onRetry,
}) => {
  const retry = onRetry ?? onRefresh;
  const [togglingASGs, setTogglingASGs] = useState<Set<string>>(new Set());
  const [syncingASGs, setSyncingASGs]   = useState<Set<string>>(new Set());
  const postToggleLock = useRef<Map<string, ReturnType<typeof setTimeout>>>(new Map());

  const handleToggle = async (asg: ASG) => {
    if (awsToggleState(asg, conversionsPaused).mode !== 'switch') return;
    const key = `${asg.name}-${asg.region}`;
    if (togglingASGs.has(key) || postToggleLock.current.has(key)) return;
    setTogglingASGs((prev) => new Set(prev).add(key));
    try {
      await onToggleASG(asg);
      const timer = setTimeout(() => { postToggleLock.current.delete(key); }, 3000);
      postToggleLock.current.set(key, timer);
    } finally {
      setTogglingASGs((prev) => { const n = new Set(prev); n.delete(key); return n; });
    }
  };

  const handleSync = async (asg: ASG) => {
    const key = `${asg.name}-${asg.region}`;
    if (syncingASGs.has(key)) return;
    setSyncingASGs((prev) => new Set(prev).add(key));
    try {
      const result = await onSyncASG(asg);
      gooeyToast.success(`Sync complete for ${asg.name}`, { description: result.message });
    } catch {
      gooeyToast.error(`Sync failed for ${asg.name}`, { description: 'Check backend logs.' });
    } finally {
      setSyncingASGs((prev) => { const n = new Set(prev); n.delete(key); return n; });
    }
  };

  const enabledCount  = asgs.filter((a) => a.isEnabled).length;
  const disabledCount = asgs.filter((a) => !a.isEnabled).length;

  return (
    <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm overflow-hidden">
      {/* Table header */}
      <div className="px-5 py-4 border-b border-[rgba(0,0,0,0.06)] flex items-center justify-between">
        <div>
          <h3 className="text-[14px] font-bold text-[rgba(0,0,0,0.88)]">Auto Scaling Groups</h3>
          <p className="text-[12px] text-[rgba(0,0,0,0.45)] mt-0.5">
            Real-time cost data from <span className="font-semibold">{new Set(asgs.map((a) => a.region)).size}</span>{' '}
            region{new Set(asgs.map((a) => a.region)).size !== 1 ? 's' : ''}
          </p>
        </div>
        <button
          onClick={onRefresh}
          disabled={isLoading}
          className="flex items-center gap-1.5 px-3 py-2 text-[12px] font-semibold text-[rgba(0,0,0,0.55)] hover:text-[rgba(0,0,0,0.88)] hover:bg-[rgba(0,0,0,0.03)] rounded-lg transition-colors disabled:opacity-40"
        >
          <Icon icon="mdi:refresh" width={14} className={isLoading ? 'animate-spin' : ''} />
          {isLoading ? 'Loading...' : 'Refresh Data'}
        </button>
      </div>

      {!isLoading && error && asgs.length > 0 && (
        <div role="alert" className="px-5 py-2.5 border-b border-[rgba(0,0,0,0.06)] bg-[rgba(0,0,0,0.03)] flex items-center gap-3 text-[12px] text-[rgba(0,0,0,0.7)]">
          <Icon icon="mdi:alert-circle-outline" width={14} className="flex-shrink-0 text-[rgba(0,0,0,0.55)]" aria-hidden="true" />
          <span className="flex-1 min-w-0">
            <span className="font-semibold">Couldn't refresh. Showing the last loaded data.</span>{' '}
            <span className="text-[rgba(0,0,0,0.55)]">{error}</span>
          </span>
          <button
            onClick={retry}
            className="flex items-center gap-1.5 px-3 py-1.5 bg-[rgba(0,0,0,0.88)] hover:bg-black text-white text-[12px] font-semibold rounded-md transition-colors flex-shrink-0"
          >
            <Icon icon="mdi:refresh" width={13} />
            Retry
          </button>
        </div>
      )}

      <div className="overflow-x-auto">
        <table className="w-full text-left border-collapse">
          <thead>
            <tr className="border-b border-[rgba(0,0,0,0.06)]">
              <th className={HEAD}>ASG Name</th>
              <th className={HEAD}>Region</th>
              <th className={HEAD}>Instance Type</th>
              <th className={HEAD}>Instances</th>
              <th className={HEAD}>Spot/OD</th>
              <th className={HEAD}>Hourly Cost</th>
              <th className={HEAD}>Monthly Cost</th>
              <th className={HEAD}>
                <div className="flex items-center gap-1">
                  Actual Savings/mo
                  <InfoTooltip placement="top">Monthly savings you're already getting from Spot instances currently running in this ASG.</InfoTooltip>
                </div>
              </th>
              <th className={HEAD}>
                <div className="flex items-center gap-1">
                  Potential Savings/mo
                  <InfoTooltip placement="top">Estimated monthly savings if all on-demand instances in this ASG were converted to Spot.</InfoTooltip>
                </div>
              </th>
              <th className={`${HEAD} text-center`}>AutoSpotting</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-[rgba(0,0,0,0.04)]">
            {isLoading && (
              <tr>
                <td colSpan={10} className="px-6 py-16 text-center">
                  <div className="flex flex-col items-center gap-3">
                    <Icon icon="mdi:loading" width={40} className="animate-spin text-indigo-500" />
                    <p className="text-[13px] font-semibold text-[rgba(0,0,0,0.55)]">Fetching real-time cost data...</p>
                  </div>
                </td>
              </tr>
            )}

            {!isLoading && error && asgs.length === 0 && (
              <tr>
                <td colSpan={10} className="px-6 py-16 text-center">
                  <div role="alert" className="flex flex-col items-center">
                    <div className="w-12 h-12 bg-[rgba(0,0,0,0.04)] rounded-full flex items-center justify-center mx-auto mb-3">
                      <Icon icon="mdi:alert-circle-outline" width={24} color="rgba(0,0,0,0.55)" aria-hidden="true" />
                    </div>
                    <p className="text-[14px] font-bold text-[rgba(0,0,0,0.88)] mb-1">Couldn't load Auto Scaling groups</p>
                    <p className="text-[12px] text-[rgba(0,0,0,0.55)] mb-3 max-w-md">{error}</p>
                    <button
                      onClick={retry}
                      className="flex items-center gap-2 px-5 py-2.5 bg-[rgba(0,0,0,0.88)] hover:bg-black text-white text-[13px] font-semibold rounded-md transition-colors mx-auto"
                    >
                      <Icon icon="mdi:refresh" width={14} />
                      Retry
                    </button>
                  </div>
                </td>
              </tr>
            )}

            {!isLoading && !error && asgs.length === 0 && (
              <tr>
                <td colSpan={10} className="px-6 py-16 text-center">
                  <div className="w-12 h-12 bg-amber-50 rounded-full flex items-center justify-center mx-auto mb-3">
                    <Icon icon="mdi:alert-outline" width={24} color="#D97706" />
                  </div>
                  <p className="text-[14px] font-bold text-[rgba(0,0,0,0.88)] mb-3">No Auto Scaling Groups Found</p>
                  <button
                    onClick={onRefresh}
                    className="flex items-center gap-2 px-5 py-2.5 bg-[rgba(0,0,0,0.88)] hover:bg-black text-white text-[13px] font-semibold rounded-md transition-colors mx-auto"
                  >
                    <Icon icon="mdi:refresh" width={14} />
                    Retry
                  </button>
                </td>
              </tr>
            )}

            {!isLoading && asgs.map((asg) => {
              const asgKey    = `${asg.name}-${asg.region}`;
              const isToggling = togglingASGs.has(asgKey);
              const isSyncing  = syncingASGs.has(asgKey);
              const toggle     = awsToggleState(asg, conversionsPaused);
              const reasonId   = `asg-toggle-reason-${asgKey.replace(/[^A-Za-z0-9_-]/g, '-')}`;

              return (
                <tr key={asgKey} className="hover:bg-[rgba(0,0,0,0.01)] transition-colors">
                  <td className={COL}>
                    <Link
                      to={`/asg-detail?asgName=${encodeURIComponent(asg.name)}&region=${asg.region}&accountIds=${selectedAccountId}`}
                      className="flex items-center gap-2 hover:text-indigo-600 transition-colors group"
                    >
                      <Icon icon="mdi:layers-outline" width={15} color="rgba(0,0,0,0.3)" className="group-hover:text-indigo-500 flex-shrink-0" />
                      <div>
                        <div className="font-semibold text-[rgba(0,0,0,0.88)] group-hover:underline">{asg.name}</div>
                        {asg.eksManaged && (
                          <span className="text-[9px] font-bold bg-blue-50 text-blue-700 px-1.5 py-0.5 rounded-full border border-blue-100 mt-0.5 inline-block">EKS</span>
                        )}
                      </div>
                      <Icon icon="mdi:open-in-new" width={10} color="rgba(0,0,0,0.2)" className="opacity-0 group-hover:opacity-100 transition-opacity ml-auto" />
                    </Link>
                  </td>
                  <td className={COL}>
                    <span className="inline-flex items-center gap-1 px-2.5 py-1 rounded-full text-[11px] font-semibold bg-blue-50 text-blue-700 border border-blue-100">
                      <Icon icon="mdi:map-marker-outline" width={11} />
                      {asg.region}
                    </span>
                  </td>
                  <td className={`${COL} font-mono text-[11px] text-[rgba(0,0,0,0.65)]`}>{asg.instanceTypes || 'N/A'}</td>
                  <td className={COL}>
                    <span className="inline-flex items-center gap-1 px-2.5 py-1 rounded-full text-[11px] font-semibold bg-[rgba(0,0,0,0.04)] text-[rgba(0,0,0,0.65)]">
                      <Icon icon="mdi:server-outline" width={11} />
                      {asg.instanceCount}
                    </span>
                  </td>
                  <td className={COL}>
                    <span className="text-[12px] font-semibold">
                      <span className="text-emerald-600">{asg.spotCount}</span>
                      <span className="text-[rgba(0,0,0,0.25)]">/</span>
                      <span className="text-orange-600">{asg.onDemandCount}</span>
                    </span>
                  </td>
                  <td className={`${COL} font-semibold text-[rgba(0,0,0,0.88)]`}>{fmtHr(asg.currentCost || 0)}</td>
                  <td className={`${COL} font-semibold text-[rgba(0,0,0,0.88)]`}>{fmt((asg.currentCost || 0) * 730)}</td>
                  <td className={COL}>
                    {asg.savings && asg.savings > 0
                      ? <span className="text-[12px] font-bold text-emerald-600">{fmt(asg.savings * 730)}</span>
                      : <span className="text-[rgba(0,0,0,0.25)]">—</span>}
                  </td>
                  <td className={COL}>
                    {asg.eksManaged ? (
                      <span className="text-[11px] font-semibold text-[rgba(0,0,0,0.3)]">N/A</span>
                    ) : asg.potentialSavings && asg.potentialSavings > 0 ? (
                      <span className="text-[12px] font-bold text-indigo-600">{fmt(asg.potentialSavings * 730)}</span>
                    ) : (
                      <span className="text-[rgba(0,0,0,0.25)]">—</span>
                    )}
                  </td>
                  <td className={`${COL} text-center`}>
                    {toggle.mode === 'locked' ? (
                      <div className="relative group/eks inline-block">
                        <div className="flex items-center gap-1.5 text-[11px] font-semibold text-[rgba(0,0,0,0.3)] bg-[rgba(0,0,0,0.03)] border border-[rgba(0,0,0,0.08)] rounded-lg px-2.5 py-1.5 cursor-not-allowed select-none">
                          <Icon icon="mdi:lock-outline" width={11} />
                          EKS Managed
                        </div>
                        <div className="absolute bottom-full mb-2 left-1/2 -translate-x-1/2 w-64 bg-[rgba(0,0,0,0.88)] text-white text-[10px] rounded-lg px-3 py-2 shadow-lg hidden group-hover/eks:block z-10 pointer-events-none leading-relaxed">
                          This is an <strong className="text-white">EKS-managed node group</strong>. AutoSpotting cannot convert it — EKS controls its lifecycle. To use Spot, create a dedicated Spot node group in the EKS console.
                        </div>
                      </div>
                    ) : toggle.mode === 'disabled' ? (
                      <div className="relative group/paused inline-block">
                        <Switch
                          isSelected={asg.isEnabled}
                          isDisabled
                          aria-label={`AutoSpotting for ${asg.name}`}
                          aria-describedby={reasonId}
                          className="flex items-center gap-2 cursor-not-allowed outline-none"
                        >
                          <div className="relative w-11 h-6 rounded-full bg-[rgba(0,0,0,0.08)] opacity-60">
                            <div className="absolute top-0.5 left-0.5 w-5 h-5 bg-white rounded-full shadow" />
                          </div>
                          <span className="text-[11px] font-bold text-[rgba(0,0,0,0.3)]">Paused</span>
                        </Switch>
                        <span id={reasonId} className="sr-only">{toggle.reason}</span>
                        {/* Right-aligned: this is the last column, and a centered card is cut off by the table's overflow. */}
                        <div aria-hidden="true" className="absolute bottom-full mb-2 right-0 w-64 bg-[rgba(0,0,0,0.88)] text-white text-[10px] rounded-lg px-3 py-2 shadow-lg hidden group-hover/paused:block z-10 pointer-events-none leading-relaxed">
                          {toggle.reason}
                        </div>
                      </div>
                    ) : (
                      <div className="flex flex-col items-center gap-2">
                        <Switch
                          isSelected={asg.isEnabled}
                          onChange={() => handleToggle(asg)}
                          isDisabled={isToggling}
                          aria-label={`AutoSpotting for ${asg.name}`}
                          className="flex items-center gap-2 cursor-pointer outline-none group"
                        >
                          {({ isSelected, isFocusVisible }) => (
                            <>
                              <div className={[
                                'relative w-11 h-6 rounded-full transition-colors duration-200',
                                isSelected ? 'bg-emerald-500' : 'bg-[rgba(0,0,0,0.15)]',
                                isToggling ? 'opacity-50' : '',
                                isFocusVisible ? 'ring-2 ring-emerald-400 ring-offset-1' : '',
                              ].join(' ')}>
                                <div className={[
                                  'absolute top-0.5 w-5 h-5 bg-white rounded-full shadow transition-all duration-200',
                                  isSelected ? 'left-[calc(100%-1.375rem)]' : 'left-0.5',
                                ].join(' ')} />
                              </div>
                              <span className={`text-[11px] font-bold ${isSelected ? 'text-emerald-600' : 'text-[rgba(0,0,0,0.4)]'}`}>
                                {isSelected ? 'Enabled' : 'Disabled'}
                              </span>
                            </>
                          )}
                        </Switch>

                        {asg.isEnabled && (
                          <div className="flex items-center gap-1">
                            <button
                              onClick={() => handleSync(asg)}
                              disabled={isSyncing}
                              className="flex items-center gap-1 text-[11px] font-semibold text-indigo-500 hover:text-indigo-700 disabled:opacity-40 px-2 py-1 rounded hover:bg-indigo-50 transition-colors"
                            >
                              <Icon icon={isSyncing ? 'mdi:loading' : 'mdi:sync'} width={11} className={isSyncing ? 'animate-spin' : ''} />
                              {isSyncing ? 'Syncing...' : 'Sync'}
                            </button>
                            <Link
                              to={`/asg-config?asgName=${encodeURIComponent(asg.name)}&region=${asg.region}&accountIds=${selectedAccountId}`}
                              className="flex items-center gap-1 text-[11px] font-semibold text-indigo-500 hover:text-indigo-700 px-2 py-1 rounded hover:bg-indigo-50 transition-colors"
                            >
                              <Icon icon="mdi:cog-outline" width={11} />
                              Config
                            </Link>
                          </div>
                        )}
                      </div>
                    )}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>

      {asgs.length > 0 && (
        <div className="px-5 py-3.5 border-t border-[rgba(0,0,0,0.06)] flex items-center justify-between">
          <p className="text-[12px] text-[rgba(0,0,0,0.55)]">
            <span className="font-bold text-[rgba(0,0,0,0.88)]">{asgs.length}</span> ASG{asgs.length !== 1 ? 's' : ''} •{' '}
            <span className="font-bold text-[rgba(0,0,0,0.88)]">{totalInstances}</span> instance{totalInstances !== 1 ? 's' : ''} •{' '}
            <span className="font-bold text-emerald-600">{fmtHr(costs.hourlyActualSavings)}/hr saved</span>
          </p>
          <div className="flex items-center gap-4">
            <div className="flex items-center gap-2">
              <div className="w-3 h-3 bg-emerald-500 rounded-full" />
              <span className="text-[12px] text-[rgba(0,0,0,0.55)]"><span className="font-bold text-[rgba(0,0,0,0.88)]">{enabledCount}</span> Enabled</span>
            </div>
            <div className="flex items-center gap-2">
              <div className="w-3 h-3 bg-[rgba(0,0,0,0.12)] rounded-full" />
              <span className="text-[12px] text-[rgba(0,0,0,0.55)]"><span className="font-bold text-[rgba(0,0,0,0.88)]">{disabledCount}</span> Disabled</span>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};

export default SpotAutomationASGTable;
