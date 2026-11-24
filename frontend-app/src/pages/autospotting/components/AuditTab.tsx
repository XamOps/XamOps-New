import React from 'react';

/** The audit query API is not part of this release; the tab says so rather than showing an empty log. */
export const AuditTab: React.FC<{ operationId?: string | null }> = ({ operationId }) => (
  <div className="text-sm text-gray-600">
    <p className="text-black">Audit trail is not available yet.</p>
    {operationId && <p className="mt-1">{`Operation ${operationId}`}</p>}
  </div>
);

export default AuditTab;
