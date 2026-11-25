import React from 'react';
import { Icon } from '@iconify/react';
import { Tab, TabList, Tabs } from 'react-aria-components';
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
import { BTN_PRIMARY, BTN_SECONDARY, CARD, MUTED, Notice } from './theme';

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
  /** GCP: review a rolling-update policy edit as a plan. */
  onEditPolicy?: (kind: string, policyEdit: Record<string, unknown>) => void;
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
  onEditPolicy,
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
    <aside aria-label={`${row.name} details`} className={`${CARD} p-5 text-[13px] text-[rgba(0,0,0,0.88)]`}>
      <div className="flex items-start justify-between gap-2">
        <div className="min-w-0">
          <h2 className="truncate text-[15px] font-semibold">{row.name}</h2>
          <div className="mt-1.5">
            <StateBadge row={row} />
          </div>
        </div>
        <button
          type="button"
          onClick={onClose}
          aria-label="Close"
          title="Close"
          className="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg text-[rgba(0,0,0,0.55)] transition-colors hover:bg-[rgba(0,0,0,0.04)] hover:text-[rgba(0,0,0,0.88)] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-300"
        >
          <Icon icon="mdi:close" width={18} aria-hidden="true" />
        </button>
      </div>

      {actions.length > 0 ? (
        <div className="mt-4 flex flex-wrap gap-2">
          {actions.map((a, i) => {
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
                  className={i === 0 && a.kind === 'plan' ? BTN_PRIMARY : BTN_SECONDARY}
                >
                  {a.label}
                </button>
                {reason && (
                  <span id={reasonId} className={`mt-1.5 max-w-xs text-[11px] ${MUTED}`}>
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
        row.blockers.length > 0 && <p className={`mt-3 ${MUTED}`}>{row.blockers[0].text}</p>
      )}
      {row.state === 'LEGACY_UNCONFIRMED' && !(providerCfg?.v2MutationsEnabled ?? false) && (
        <p className={`mt-2 text-[11px] ${MUTED}`}>Adopt and Revert are not available yet for converted groups.</p>
      )}

      {/* The AriaTabGroup underline style, compacted to fit the panel width. */}
      <Tabs selectedKey={tab} onSelectionChange={k => onTab(String(k) as TabId)}>
        <TabList aria-label="Group details" className="-mx-5 mt-4 flex gap-0.5 overflow-x-auto overflow-y-hidden border-b border-gray-200 px-3">
          {TABS.map(t => (
            <Tab
              key={t.id}
              id={t.id}
              className={({ isSelected, isFocusVisible }) =>
                [
                  '-mb-px flex cursor-pointer items-center gap-1.5 whitespace-nowrap border-b-2 px-3 py-2.5 text-[12.5px] font-semibold outline-none transition-all',
                  isSelected
                    ? 'border-indigo-600 text-indigo-700'
                    : 'border-transparent text-gray-500 hover:border-gray-300 hover:text-gray-700',
                  isFocusVisible ? 'ring-2 ring-inset ring-indigo-500' : '',
                ].join(' ')
              }
            >
              {t.label}
            </Tab>
          ))}
        </TabList>
      </Tabs>
      <div role="tabpanel" className="mt-4">
        {detailError && (
          <Notice tone="danger" className="mb-3">
            Couldn't load the group details.
          </Notice>
        )}
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
        {tab === 'config' && <ConfigTab detail={effective} adapter={adapter} current={current} onEditPolicy={onEditPolicy} />}
        {tab === 'progress' && (
          <ProgressTab
            accountId={accountId}
            operationId={operationId}
            adapter={adapter}
            onViewAudit={() => onTab('audit')}
          />
        )}
        {tab === 'history' && <HistoryTab detail={effective} />}
        {tab === 'savings' && <SavingsTab row={row} accountId={accountId} />}
        {tab === 'audit' && <AuditTab operationId={operationId} />}
      </div>
    </aside>
  );
};

export default DetailPanel;
