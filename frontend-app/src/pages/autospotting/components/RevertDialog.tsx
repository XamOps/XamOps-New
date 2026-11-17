import React from 'react';
import type { GroupRow } from '../../../api/autospottingApi';
import type { ProviderAdapter } from '../adapters/types';
import AsDialog from './AsDialog';
import { usePlanFlow } from './usePlanFlow';
import { SWITCH_UNREADABLE_TEXT, warningText } from './reasons';

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
            <span id={reasonId} className="mr-auto text-xs text-gray-600">
              {!plan ? 'The plan is loading.' : !allAcked ? 'Tick every acknowledgment.' : 'Confirming.'}
            </span>
          )}
          <button
            type="button"
            onClick={onClose}
            disabled={flow.confirming}
            className="rounded border border-gray-400 px-3 py-1 text-sm text-black disabled:opacity-50"
          >
            Cancel
          </button>
          {stale ? (
            <button
              type="button"
              onClick={() => void flow.refreshPlan()}
              className="rounded border border-black bg-black px-3 py-1 text-sm text-white"
            >
              Refresh plan
            </button>
          ) : (
            <button
              type="button"
              disabled={!canConfirm}
              aria-describedby={!canConfirm ? reasonId : undefined}
              onClick={() => void onConfirm()}
              className="rounded border border-black bg-black px-3 py-1 text-sm text-white disabled:border-gray-300 disabled:bg-gray-200 disabled:text-gray-500"
            >
              {plan?.dryRun ? 'Run dry run' : 'Revert'}
            </button>
          )}
        </>
      }
    >
      {flow.creating && !plan && (
        <p role="status" className="text-sm text-gray-600">
          Building the plan
        </p>
      )}

      {switchUnreadable && (
        <p role="alert" className="mb-3 text-sm font-medium text-black">
          {SWITCH_UNREADABLE_TEXT}
        </p>
      )}
      {needsPermission && (
        <div role="alert" className="mb-3 rounded border-2 border-black p-3 text-sm text-black">
          <p>{guidance.text}</p>
          {guidance.href && (
            <a href={guidance.href} className="mt-2 inline-block rounded border border-black px-3 py-1">
              {guidance.label}
            </a>
          )}
        </div>
      )}
      {flow.createError && !needsPermission && !switchUnreadable ? (
        <p role="alert" className="mb-3 text-sm font-medium text-black">
          {flow.createErrorText}
        </p>
      ) : null}

      {flow.outcome && (
        <div role="alert" className="mb-3 rounded border-2 border-black p-3 text-sm text-black">
          {flow.outcome.kind === 'stale' && <p className="font-bold">{flow.outcome.title}</p>}
          <p>{flow.outcome.message}</p>
          {flow.outcome.kind === 'busy' && onViewProgress && (
            <button type="button" onClick={onViewProgress} className="mt-2 rounded border border-black px-3 py-1">
              Open Progress
            </button>
          )}
        </div>
      )}

      {plan && body && (
        <div className="text-sm text-black">
          {inexact ? (
            <p className="mb-3 font-bold">Exact restore is not possible</p>
          ) : (
            <p className="mb-3">Revert restores the saved original configuration exactly.</p>
          )}
          {body.diff.length > 0 && (
            <table className="mb-3 w-full text-left">
              <caption className="sr-only">Current and restored configuration</caption>
              <thead>
                <tr className="border-b border-gray-300 text-gray-600">
                  <th scope="col" className="py-1 pr-2 font-medium">
                    Field
                  </th>
                  <th scope="col" className="py-1 pr-2 font-medium">
                    Current
                  </th>
                  <th scope="col" className="py-1 font-medium">
                    After revert
                  </th>
                </tr>
              </thead>
              <tbody>
                {body.diff.map(d => (
                  <tr key={d.field} className="border-b border-gray-100">
                    <td className="py-1 pr-2">{d.field}</td>
                    <td className="py-1 pr-2">{d.from ?? '--'}</td>
                    <td className="py-1">{d.to ?? '--'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
          {warnings.map(w => (
            <label key={w.id} className="mb-1 flex items-start gap-2">
              <input
                type="checkbox"
                checked={flow.acked.includes(w.id)}
                onChange={() => flow.toggleAck(w.id)}
                className="mt-1"
              />
              <span>{w.text}</span>
            </label>
          ))}
          <label className="mt-3 block">
            <span className="block text-gray-600">Reason (required for platform admins)</span>
            <input
              type="text"
              value={flow.reason}
              maxLength={500}
              onChange={e => flow.setReason(e.target.value)}
              className="mt-1 w-full rounded border border-gray-400 px-2 py-1"
            />
          </label>
        </div>
      )}
    </AsDialog>
  );
};

export default RevertDialog;
