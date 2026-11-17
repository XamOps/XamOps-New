import React, { useState } from 'react';
import type { Operation } from '../../../api/autospottingApi';
import type { GuidanceLink } from '../adapters/types';

const CopyButton: React.FC<{ value: string; label: string }> = ({ value, label }) => {
  const [done, setDone] = useState(false);
  return (
    <button
      type="button"
      aria-label={`Copy ${label}`}
      className="ml-2 rounded border border-gray-400 px-2 py-0.5 text-xs text-gray-600"
      onClick={() => {
        void navigator.clipboard?.writeText(value);
        setDone(true);
      }}
    >
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
    <section aria-label="Error details" className="rounded border-2 border-black bg-white p-4 text-sm text-black">
      <h3 className="font-bold">{title}</h3>
      {step && <p className="mt-1">{`Step: ${step}`}</p>}
      {code && <p className="mt-1">{`Code: ${code}`}</p>}
      {local && !Number.isNaN(local.getTime()) && (
        <p className="mt-1 text-gray-600">{`${local.toISOString().replace('T', ' ').slice(0, 19)} UTC (${local.toLocaleString()} local)`}</p>
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
          <button type="button" onClick={onViewAudit} className="rounded border border-black px-3 py-1">
            View audit trail
          </button>
        )}
        {guidance?.href && (
          <a href={guidance.href} className="rounded border border-black px-3 py-1">
            {guidance.label}
          </a>
        )}
      </div>
    </section>
  );
};

export default ErrorPanel;
