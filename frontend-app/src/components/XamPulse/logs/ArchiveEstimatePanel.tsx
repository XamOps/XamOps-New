import React from 'react';
import { useMutation } from '@tanstack/react-query';
import { xampulseApi } from '../../../api/xampulseApi';
import type { XpScope } from '../../../types/xampulse';
import { Card, ErrorState } from '../ui/ui';
import { formatBytes, formatUsd } from '../../../utils/xampulse/format';

/** Archive search: estimate only for now. The cost is shown first, and running the search is disabled. */
export const ArchiveEstimatePanel: React.FC<{ scope: XpScope; query: string }> = ({ scope, query }) => {
  const est = useMutation({ mutationFn: () => xampulseApi.archiveEstimate({ scope, query }) });
  return (
    <Card title="Search the archive" id="xp-arch">
      <p className="xp-muted" style={{ marginTop: 0, maxWidth: '70ch' }}>Older logs live in your own S3 bucket. Searching them reads that bucket, which has a small AWS cost on your bill. Check the estimate first.</p>
      <div className="xp-row">
        <button type="button" className="xp-btn" onClick={() => est.mutate()} disabled={est.isPending}>{est.isPending ? 'Estimating' : 'Estimate cost for this range and query'}</button>
        <button type="button" className="xp-btn xp-btn--primary" disabled title="Running archive searches arrives in a later release">Run archive search</button>
        <span className="xp-muted">Running archive searches arrives in a later release.</span>
      </div>
      {est.error && <div style={{ marginTop: 12 }}><ErrorState error={est.error} compact /></div>}
      {est.data && (
        <div className="xp-banner xp-banner--ok" style={{ marginTop: 12 }} role="status">
          Scanning about <strong>{formatBytes(est.data.bytes)}</strong> in <strong>{est.data.requests.toLocaleString()}</strong> requests would cost about <strong>{formatUsd(est.data.usd)}</strong> in your AWS account.
          <div className="xp-muted" style={{ fontSize: 12 }}>Storage classes: {est.data.storageClasses.join(', ') || 'standard'}. This is an estimate.</div>
        </div>
      )}
    </Card>
  );
};
