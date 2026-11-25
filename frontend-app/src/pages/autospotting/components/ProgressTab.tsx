import React, { useEffect, useRef, useState } from 'react';
import { useOperation } from '../../../hooks/autospotting/useOperation';
import type { Operation } from '../../../api/autospottingApi';
import type { ProviderAdapter } from '../adapters/types';
import ErrorPanel from './ErrorPanel';
import { BTN_SECONDARY, MUTED, Notice } from './theme';

const WAIT_TEXT: Record<string, string> = {
  CLOUD_PROGRESS: 'Waiting for AWS',
  SWITCH: 'Paused by an AutoSpotting switch',
  FOREIGN_CHANGE: 'Waiting for another change on this group to finish',
  BACKOFF: 'Retrying after a temporary error',
  STUCK: 'Taking longer than expected. XamOps on-call has been notified',
};

interface Failure {
  instance?: string;
  zone?: string;
  code?: string;
  retrying?: boolean;
}

interface ProgressDoc {
  replaced?: number;
  total?: number;
  percent?: number;
  failed?: number;
  failures?: Failure[];
  spot?: number;
  onDemand?: number;
  desired?: number;
  inService?: number;
  step?: number;
  totalSteps?: number;
  stepName?: string;
  expectedRange?: string;
}

export function parseProgress(text: string | null | undefined): ProgressDoc {
  if (!text) return {};
  try {
    const v = JSON.parse(text);
    return v && typeof v === 'object' ? (v as ProgressDoc) : {};
  } catch {
    return {};
  }
}

const NO_CHANGE_MS = 15 * 60 * 1000;

/** Plain text for the phase and outcome of an operation (design-ux-ops.md §4.4). */
export function operationHeadline(op: Operation): string {
  if (op.phase === 'COMPENSATING' && op.status !== 'SUCCEEDED') {
    if (op.kind === 'ENABLE') return 'Apply failed. Restoring the original configuration.';
    if (op.kind === 'FALLBACK_EXIT') return 'Return to Spot failed. Staying on On-Demand.';
    return 'Change failed. Restoring the previous configuration.';
  }
  if (op.status === 'CANCELLED' && op.errorCode === 'PREEMPTED_BY_FALLBACK') {
    return 'Your change was saved. Spot capacity is unavailable, so the group is running On-Demand for now. Your new configuration is applied when the group returns to Spot.';
  }
  if (op.status === 'CANCELLED') return 'Change stopped.';
  if (op.status === 'SUCCEEDED') return 'Completed';
  if (op.status === 'FAILED') return 'Failed';
  if (op.status === 'WAITING') return 'Waiting';
  if (op.status === 'QUEUED') return 'Queued';
  return 'Running';
}

interface ProgressTabProps {
  accountId: number | null;
  operationId: string | null;
  adapter: ProviderAdapter;
  onViewAudit?: () => void;
}

export const ProgressTab: React.FC<ProgressTabProps> = ({ accountId, operationId, adapter, onViewAudit }) => {
  const { data: op, isError, terminal } = useOperation(accountId, operationId);
  const lastChange = useRef<{ progress: string | null; at: number }>({ progress: null, at: Date.now() });
  const [now, setNow] = useState(() => Date.now());
  const announced = useRef<string | null>(null);
  const [announcement, setAnnouncement] = useState('');

  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), 60_000);
    return () => clearInterval(t);
  }, []);

  useEffect(() => {
    if (!op) return;
    if (lastChange.current.progress !== op.progress) {
      lastChange.current = { progress: op.progress, at: Date.now() };
    }
    // Announce a change of status once, not on every poll.
    if (announced.current !== op.status) {
      announced.current = op.status;
      setAnnouncement(`Operation ${op.status.toLowerCase()}`);
    }
  }, [op]);

  if (!operationId) {
    return <p className={MUTED}>No operation is running for this group.</p>;
  }
  if (!op) {
    return isError ? (
      <Notice tone="warning" icon="mdi:lan-disconnect" role="alert">
        Reconnecting
      </Notice>
    ) : (
      <p role="status" className={`flex items-center gap-2 ${MUTED}`}>
        <span aria-hidden="true" className="h-2 w-2 animate-pulse rounded-full bg-indigo-500" />
        Loading progress
      </p>
    );
  }

  const p = parseProgress(op.progress);
  const percent = Math.max(0, Math.min(100, Math.round(p.percent ?? (p.total ? ((p.replaced ?? 0) / p.total) * 100 : 0))));
  const stale = !terminal && now - lastChange.current.at >= NO_CHANGE_MS;
  const failures = p.failures ?? [];

  return (
    <div className="space-y-3 text-[13px]">
      <div aria-live="polite" className="sr-only">
        {announcement}
      </div>
      <div className="flex items-center gap-2">
        <span className="font-semibold">{operationHeadline(op)}</span>
        {op.dryRun && (
          <span className="rounded border border-amber-200 bg-amber-50 px-1.5 py-0.5 text-[10px] font-semibold uppercase tracking-wide text-amber-800">
            Dry run
          </span>
        )}
      </div>

      <div>
        <div
          role="progressbar"
          aria-valuenow={percent}
          aria-valuemin={0}
          aria-valuemax={100}
          aria-label="Operation progress"
          className="h-2 w-full overflow-hidden rounded-full bg-[rgba(0,0,0,0.06)]"
        >
          <div
            className={`h-2 rounded-full transition-all ${op.status === 'FAILED' ? 'bg-red-600' : op.status === 'SUCCEEDED' ? 'bg-emerald-600' : 'bg-indigo-600'}`}
            style={{ width: `${percent}%` }}
          />
        </div>
        {p.total !== undefined && (
          <p className={`mt-1.5 text-[12px] ${MUTED}`}>{`${p.replaced ?? 0} of ${p.total} instances replaced`}</p>
        )}
        {isError && !terminal && <p className={`mt-1 text-[12px] ${MUTED}`}>Reconnecting</p>}
      </div>

      {(p.step !== undefined || p.stepName) && (
        <p>
          {p.step !== undefined && p.totalSteps ? `Step ${p.step} of ${p.totalSteps}  ` : ''}
          {p.stepName ?? ''}
          {p.expectedRange ? `   Expected ${p.expectedRange}` : ''}
        </p>
      )}

      {(p.desired !== undefined || p.inService !== undefined) && (
        <p>
          {[
            p.spot !== undefined ? `Spot ${p.spot}` : null,
            p.onDemand !== undefined ? `On-Demand ${p.onDemand}` : null,
            p.desired !== undefined ? `Desired ${p.desired}` : null,
            p.inService !== undefined ? `In service ${p.inService}` : null,
          ]
            .filter(Boolean)
            .join('   ')}
        </p>
      )}

      {op.status === 'WAITING' && op.waitReason && (
        <p className={op.waitReason === 'STUCK' ? 'font-bold text-amber-800' : ''}>{WAIT_TEXT[op.waitReason] ?? 'Waiting'}</p>
      )}
      {stale && (
        <p className={MUTED}>No change in 15 minutes. This can be normal for large groups.</p>
      )}

      {failures.length > 0 && (
        <div className="border-t border-[rgba(0,0,0,0.06)] pt-3">
          <p className="font-medium">{`Failures (${p.failed ?? failures.length})`}</p>
          <ul>
            {failures.map((f, i) => (
              <li key={i} className={MUTED}>
                {[f.zone, f.code, f.retrying ? `retried by ${adapter.provider}` : null].filter(Boolean).join('  ')}
              </li>
            ))}
          </ul>
        </div>
      )}

      {op.status === 'FAILED' && (
        <ErrorPanel
          title="Needs attention"
          step={`Step ${op.step}`}
          code={op.errorCode}
          when={op.finishedAt}
          operation={op}
          guidance={op.errorCode === 'PERMISSION' ? adapter.permissionGuidance('PERMISSION') : null}
          onViewAudit={onViewAudit}
        />
      )}
      {op.status !== 'FAILED' && onViewAudit && (
        <button type="button" onClick={onViewAudit} className={BTN_SECONDARY}>
          View audit trail
        </button>
      )}
    </div>
  );
};

export default ProgressTab;
