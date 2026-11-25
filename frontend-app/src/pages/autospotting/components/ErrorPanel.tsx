import React, { useState } from 'react';
import type { Operation } from '../../../api/autospottingApi';
import type { GuidanceLink } from '../adapters/types';
import { Icon } from '@iconify/react';
import { BTN_PRIMARY, BTN_SECONDARY, BTN_SMALL } from './theme';

const CopyButton: React.FC<{ value: string; label: string }> = ({ value, label }) => {
  const [done, setDone] = useState(false);
  return (
    <button
      type="button"
      aria-label={`Copy ${label}`}
      className={`ml-2 ${BTN_SMALL}`}
      onClick={() => {
        void navigator.clipboard?.writeText(value);
        setDone(true);
      }}
    >
      <Icon icon={done ? 'mdi:check' : 'mdi:content-copy'} width={11} aria-hidden="true" />
      {done ? 'Copied' : 'Copy'}
    </button>
  );
};

interface ErrorPanelProps {
  title: string;
  /** Plain-text step name. */
  step?: string | null;
  code?: string | null;
  when?: string | null;
  requestId?: string | null;
  operation?: Pick<Operation, 'operationId'> | null;
  guidance?: GuidanceLink | null;
  onViewAudit?: () => void;
  children?: React.ReactNode;
}

/**
 * Shown for FAILED groups and operations and for PAUSED(DRIFT) (design-ux-ops.md §4.5). Shows the
 * stable code, never a raw provider message.
 */
export const ErrorPanel: React.FC<ErrorPanelProps> = ({
  title,
  step,
  code,
  when,
  requestId,
  operation,
  guidance,
  onViewAudit,
  children,
}) => {
  const local = when ? new Date(when) : null;
  return (
    <section aria-label="Error details" className="rounded-xl border border-red-200 bg-red-50 p-4 text-[13px] text-red-900">
      <h3 className="flex items-center gap-1.5 font-bold">
        <Icon icon="mdi:alert-circle-outline" width={16} aria-hidden="true" className="text-red-700" />
        {title}
      </h3>
      {step && <p className="mt-1">{`Step: ${step}`}</p>}
      {code && <p className="mt-1">{`Code: ${code}`}</p>}
      {local && !Number.isNaN(local.getTime()) && (
        <p className="mt-1 text-red-800">{`${local.toISOString().replace('T', ' ').slice(0, 19)} UTC (${local.toLocaleString()} local)`}</p>
      )}
      {requestId && (
        <p className="mt-1">
          {`Request id ${requestId}`}
          <CopyButton value={requestId} label="request id" />
        </p>
      )}
      {operation?.operationId && (
        <p className="mt-1">
          {`Operation id ${operation.operationId}`}
          <CopyButton value={operation.operationId} label="operation id" />
        </p>
      )}
      {children}
      <div className="mt-3 flex flex-wrap gap-2">
        {onViewAudit && (
          <button type="button" onClick={onViewAudit} className={BTN_SECONDARY}>
            View audit trail
          </button>
        )}
        {guidance?.href && (
          <a href={guidance.href} className={BTN_PRIMARY}>
            {guidance.label}
          </a>
        )}
      </div>
    </section>
  );
};

export default ErrorPanel;
