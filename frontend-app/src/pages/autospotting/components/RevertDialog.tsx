import React from 'react';
import type { GroupRow } from '../../../api/autospottingApi';
import type { ProviderAdapter } from '../adapters/types';
import AsDialog from './AsDialog';
import { usePlanFlow } from './usePlanFlow';
import { SWITCH_UNREADABLE_TEXT, warningText } from './reasons';
import { Icon } from '@iconify/react';
import { BTN_DANGER, BTN_PRIMARY, BTN_SECONDARY, CHECKBOX, FIELD_LABEL, INPUT, MUTED, Notice } from './theme';
import { BuildingPlan, DiffTable, Outcome } from './dialogParts';

interface RevertDialogProps {
  accountId: number | null;
  row: GroupRow;
  adapter: ProviderAdapter;
  onClose: () => void;
  onConfirmed: (operationId: string) => void;
  onViewProgress?: () => void;
  retryDelayMs?: number;
}

/** Revert hides Profile and Estimate; it leads with exactness (design-ux-ops.md §3.4). */
export const RevertDialog: React.FC<RevertDialogProps> = ({
  accountId,
  row,
  adapter,
  onClose,
  onConfirmed,
  onViewProgress,
  retryDelayMs,
}) => {
  const flow = usePlanFlow({ accountId, row, kind: 'REVERT', usesProfile: false, retryDelayMs });
  const { plan, body } = flow;
  const inexact = body?.revertPath?.exact === false;
  const warnings = flow.warningIds.map(id => ({ id, text: warningText(id, row.warnings.find(w => w.id === id)?.text) }));
  const allAcked = warnings.every(w => flow.acked.includes(w.id));
  const canConfirm = !!plan && plan.status === 'OPEN' && allAcked && !flow.confirming;
  const needsPermission = flow.createErrorCode === 'REVERT_NEEDS_PERMISSION';
  const switchUnreadable = flow.createErrorCode === 'AS_SWITCH_UNREADABLE';
  const guidance = adapter.permissionGuidance('REVERT_NEEDS_PERMISSION');
  const reasonId = 'as-revert-reason';
  const stale = flow.outcome?.kind === 'stale';

  const onConfirm = async () => {
    const opId = await flow.confirm();
    if (opId) onConfirmed(opId);
  };

  return (
    <AsDialog
      title={`Revert: ${row.name}`}
      summary="Return this group to its original configuration."
      onClose={onClose}
      busy={flow.confirming}
      footer={
        <>
          {!canConfirm && (
            <span id={reasonId} className={`mr-auto text-[12px] ${MUTED}`}>
              {!plan ? 'The plan is loading.' : !allAcked ? 'Tick every acknowledgment.' : 'Confirming.'}
            </span>
          )}
          <button
            type="button"
            onClick={onClose}
            disabled={flow.confirming}
            className={BTN_SECONDARY}
          >
            Cancel
          </button>
          {stale ? (
            <button
              type="button"
              onClick={() => void flow.refreshPlan()}
              className={BTN_PRIMARY}
            >
              Refresh plan
            </button>
          ) : (
            <button
              type="button"
              disabled={!canConfirm}
              aria-describedby={!canConfirm ? reasonId : undefined}
              onClick={() => void onConfirm()}
              className={plan?.dryRun ? BTN_PRIMARY : BTN_DANGER}
            >
              {plan?.dryRun ? 'Run dry run' : 'Revert'}
            </button>
          )}
        </>
      }
    >
      {flow.creating && !plan && <BuildingPlan />}

      {switchUnreadable && (
        <Notice tone="danger" role="alert" className="mb-4">
          {SWITCH_UNREADABLE_TEXT}
        </Notice>
      )}
      {needsPermission && (
        <Notice tone="warning" icon="mdi:shield-lock-outline" role="alert" className="mb-4">
          <p>{guidance.text}</p>
          {guidance.href && (
            <a href={guidance.href} className={`mt-2 ${BTN_PRIMARY}`}>
              {guidance.label}
            </a>
          )}
        </Notice>
      )}
      {flow.createError && !needsPermission && !switchUnreadable ? (
        <Notice tone="danger" role="alert" className="mb-4">
          {flow.createErrorText}
        </Notice>
      ) : null}

      {flow.outcome && <Outcome outcome={flow.outcome} onViewProgress={onViewProgress} />}

      {plan && body && (
        <div className="text-[13px] text-[rgba(0,0,0,0.88)]">
          {inexact ? (
            <Notice tone="warning" className="mb-4">
              <p className="font-bold">Exact restore is not possible</p>
            </Notice>
          ) : (
            <p className="mb-4 flex items-center gap-1.5">
              <Icon icon="mdi:check-circle-outline" width={15} aria-hidden="true" className="text-emerald-700" />
              <span>Revert restores the saved original configuration exactly.</span>
            </p>
          )}
          {body.diff.length > 0 && (
            <DiffTable caption="Current and restored configuration" targetHeader="After revert" diff={body.diff} className="mb-4" />
          )}
          {warnings.length > 0 && (
            <div className="mb-4 space-y-2 rounded-xl border border-amber-200 bg-amber-50 p-3">
              {warnings.map(w => (
                <label key={w.id} className="flex cursor-pointer items-start gap-2 text-amber-900">
                  <input
                    type="checkbox"
                    checked={flow.acked.includes(w.id)}
                    onChange={() => flow.toggleAck(w.id)}
                    className={CHECKBOX}
                  />
                  <span>{w.text}</span>
                </label>
              ))}
            </div>
          )}
          <label className="block">
            <span className={`block ${FIELD_LABEL}`}>Reason (required for platform admins)</span>
            <input
              type="text"
              value={flow.reason}
              maxLength={500}
              onChange={e => flow.setReason(e.target.value)}
              className={`mt-1.5 w-full ${INPUT}`}
            />
          </label>
        </div>
      )}
    </AsDialog>
  );
};

export default RevertDialog;
