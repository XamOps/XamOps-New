import React from 'react';
import { Icon } from '@iconify/react';

/** The audit query API is not part of this release; the tab says so rather than showing an empty log. */
export const AuditTab: React.FC<{ operationId?: string | null }> = ({ operationId }) => (
  <div className="rounded-lg border border-[rgba(0,0,0,0.08)] bg-[rgba(0,0,0,0.02)] p-4 text-center text-[13px] text-[rgba(0,0,0,0.55)]">
    <Icon icon="mdi:file-document-outline" width={20} aria-hidden="true" className="mx-auto mb-1.5 text-[rgba(0,0,0,0.45)]" />
    <p className="font-semibold text-[rgba(0,0,0,0.88)]">Audit trail is not available yet.</p>
    {operationId && <p className="mt-1 font-mono text-[12px]">{`Operation ${operationId}`}</p>}
  </div>
);

export default AuditTab;
