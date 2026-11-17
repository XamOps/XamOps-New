import React from 'react';
import type { AutoSpottingConfig, GroupDetail, GroupRow } from '../../../api/autospottingApi';
import type { ProviderAdapter } from '../adapters/types';
import StateBadge from './StateBadge';
import ConfigTab from './ConfigTab';
import ProgressTab from './ProgressTab';
import HistoryTab from './HistoryTab';
import SavingsTab from './SavingsTab';
import AuditTab from './AuditTab';
import ErrorPanel from './ErrorPanel';
import { PAUSED_REASON } from './reasons';

export type TabId = 'config' | 'progress' | 'history' | 'savings' | 'audit';
const TABS: Array<{ id: TabId; label: string }> = [
  { id: 'config', label: 'Config' },
  { id: 'progress', label: 'Progress' },
  { id: 'history', label: 'History' },
  { id: 'savings', label: 'Savings' },
  { id: 'audit', label: 'Audit' },
];

export type ActionKind = 'plan' | 'revert' | 'legacyDisable';
export interface PanelAction {
  label: string;
  kind: ActionKind;
  planKind?: string;
  /** Conversion actions are held while conversions are paused or a switch is engaged. */
  conversion: boolean;
}

/** The actions each state offers (design-ux-ops.md §4.2). Short labels only. */
export function actionsFor(
  row: GroupRow,
  opts: { v2MutationsEnabled: boolean; drift: boolean },
): PanelAction[] {
  if (row.externalSpot) return [];
  const revert: PanelAction = { label: 'Revert', kind: 'revert', conversion: false };
  switch (row.state) {
    case 'UNMANAGED':
      return row.enableAllowed ? [{ label: 'Enable', kind: 'plan', planKind: 'ENABLE', conversion: true }] : [];
    case 'PLANNED':
      return [{ label: 'Review plan', kind: 'plan', planKind: 'ENABLE', conversion: true }];
    case 'APPLYING':
    case 'DEGRADED':
    case 'FALLBACK':
    case 'FAILED':
      return [revert];
    case 'ACTIVE':
      return [{ label: 'Change profile', kind: 'plan', planKind: 'PROFILE_CHANGE', conversion: true }, revert];
    case 'PAUSED':
      return opts.drift
        ? [
            { label: 'Re-apply', kind: 'plan', planKind: 'CONFIG_APPLY', conversion: true },
            { label: 'Accept as baseline', kind: 'plan', planKind: 'ACCEPT_BASELINE', conversion: true },
            revert,
          ]
        : [revert];
    case 'REVERTED':
      return [{ label: 'Enable', kind: 'plan', planKind: 'ENABLE', conversion: true }];
    case 'LEGACY_UNCONFIRMED':
      return opts.v2MutationsEnabled
        ? [{ label: 'Adopt', kind: 'plan', planKind: 'ADOPT', conversion: true }, revert]
        : [{ label: 'Disable', kind: 'legacyDisable', conversion: false }];
    default:
      return [];
  }
}

/** Why an action is disabled, from the config (paused conversions, engaged switches, provider not ready). */
export function disabledReason(
  action: PanelAction,
  config: AutoSpottingConfig | undefined,
  provider: string,
): string | null {
  if (action.kind === 'legacyDisable') return null;
  const p = config?.providers?.[provider];
  if (!p || !p.enabled) return `AutoSpotting changes for ${provider} aren't available yet.`;
  if (action.conversion) {
    if (!p.conversionsEnabled) return PAUSED_REASON;
    const engaged = config?.engagedSwitches?.[0];
    if (engaged) return `AutoSpotting changes are paused for ${engaged}.`;
  }
  return null;
}

interface DetailPanelProps {
  row: GroupRow;
  detail: GroupDetail | undefined;
  detailError?: boolean;
  adapter: ProviderAdapter;
  config: AutoSpottingConfig | undefined;
  accountId: number | null;
  /** The operation just confirmed from a dialog, if any. */
  activeOperationId: string | null;
  tab: TabId;
  onTab: (tab: TabId) => void;
  onAction: (action: PanelAction) => void;
  onClose: () => void;
  /** The current plan's `current` block, when a plan has been read. */
  current?: Record<string, unknown> | null;
}

export const DetailPanel: React.FC<DetailPanelProps> = ({
  row,
  detail,
  detailError,
  adapter,
  config,
  accountId,
  activeOperationId,
  tab,
  onTab,
  onAction,
  onClose,
  current,
}) => {
  const providerCfg = config?.providers?.[adapter.provider];
  const effective: GroupDetail = detail ?? {
    group: row,
    configMode: null,
    driftStatus: null,
    lastErrorCode: null,
    transitions: [],
    currentOperation: null,
  };
  const drift = row.state === 'PAUSED' && (effective.driftStatus === 'DRIFT' || effective.lastErrorCode === 'DRIFT');
  const actions = actionsFor(row, { v2MutationsEnabled: providerCfg?.v2MutationsEnabled ?? false, drift });
  const operationId = activeOperationId ?? effective.currentOperation?.operationId ?? null;
  const showError = row.state === 'FAILED' || drift;

  return (
    <aside aria-label={`${row.name} details`} className="rounded border border-gray-300 bg-white p-4 text-black">
      <div className="flex items-start justify-between gap-2">
        <div>
          <h2 className="text-base font-semibold">{row.name}</h2>
          <div className="mt-1">
            <StateBadge row={row} />
          </div>
        </div>
        <button type="button" onClick={onClose} className="rounded border border-gray-400 px-2 py-1 text-xs text-gray-600">
          Close
        </button>
      </div>

      {actions.length > 0 ? (
        <div className="mt-3 flex flex-wrap gap-2">
          {actions.map(a => {
            const reason = disabledReason(a, config, adapter.provider);
            const reasonId = `as-action-${a.label.replace(/\s+/g, '-').toLowerCase()}-reason`;
            return (
              <div key={a.label} className="flex flex-col">
                <button
                  type="button"
                  disabled={reason !== null}
                  aria-describedby={reason ? reasonId : undefined}
                  title={reason ?? undefined}
                  onClick={() => onAction(a)}
                  className="rounded border border-black px-3 py-1 text-sm text-black enabled:hover:bg-black enabled:hover:text-white disabled:border-gray-300 disabled:text-gray-400"
                >
                  {a.label}
                </button>
                {reason && (
                  <span id={reasonId} className="mt-1 max-w-xs text-xs text-gray-600">
                    {reason}
                  </span>
                )}
              </div>
            );
          })}
        </div>
      ) : (
        row.state === 'UNMANAGED' &&
        !row.enableAllowed &&
        row.blockers.length > 0 && <p className="mt-3 text-sm text-gray-600">{row.blockers[0].text}</p>
      )}
      {row.state === 'LEGACY_UNCONFIRMED' && !(providerCfg?.v2MutationsEnabled ?? false) && (
        <p className="mt-2 text-xs text-gray-600">Adopt and Revert are not available yet for converted groups.</p>
      )}

      <div role="tablist" aria-label="Group details" className="mt-4 flex gap-1 border-b border-gray-300">
        {TABS.map(t => (
          <button
            key={t.id}
            role="tab"
            type="button"
            aria-selected={tab === t.id}
            onClick={() => onTab(t.id)}
            className={`px-3 py-1.5 text-sm ${tab === t.id ? 'border-b-2 border-black font-semibold text-black' : 'text-gray-600'}`}
          >
            {t.label}
          </button>
        ))}
      </div>

      <div role="tabpanel" className="mt-3">
        {detailError && <p className="mb-2 text-sm text-gray-600">Couldn't load the group details.</p>}
        {showError && tab !== 'progress' && (
          <div className="mb-3">
            <ErrorPanel
              title={drift ? 'Configuration changed outside XamOps' : 'Needs attention'}
              code={effective.lastErrorCode}
              guidance={effective.lastErrorCode === 'PERMISSION' ? adapter.permissionGuidance('PERMISSION') : null}
              onViewAudit={() => onTab('audit')}
            />
          </div>
        )}
        {tab === 'config' && <ConfigTab detail={effective} adapter={adapter} current={current} />}
        {tab === 'progress' && (
          <ProgressTab
            accountId={accountId}
            operationId={operationId}
            adapter={adapter}
            onViewAudit={() => onTab('audit')}
          />
        )}
        {tab === 'history' && <HistoryTab detail={effective} />}
        {tab === 'savings' && <SavingsTab row={row} />}
        {tab === 'audit' && <AuditTab operationId={operationId} />}
      </div>
    </aside>
  );
};

export default DetailPanel;
