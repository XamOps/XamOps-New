import React from 'react';
import type { GroupDetail } from '../../../api/autospottingApi';
import type { ProviderAdapter } from '../adapters/types';
import GcpConfigTab from './GcpConfigTab';
import { MUTED } from './theme';

export const ConfigTab: React.FC<{
  detail: GroupDetail;
  adapter: ProviderAdapter;
  current?: Record<string, unknown> | null;
  /** GCP: open the plan dialog for an edited rolling-update policy. */
  onEditPolicy?: (kind: string, policyEdit: Record<string, unknown>) => void;
}> = ({ detail, adapter, current, onEditPolicy }) => {
  const g = detail.group;
  return (
    <div className="space-y-4 text-[13px]">
      <dl>
        <div className="flex justify-between gap-4 border-b border-[rgba(0,0,0,0.06)] py-2">
          <dt className={MUTED}>Capacity (min / desired / max)</dt>
          <dd className="text-right font-medium">{`${g.min ?? '--'} / ${g.desired ?? '--'} / ${g.max ?? '--'}`}</dd>
        </div>
        <div className="flex justify-between gap-4 border-b border-[rgba(0,0,0,0.06)] py-2">
          <dt className={MUTED}>Configuration mode</dt>
          <dd className="text-right font-medium">{detail.configMode ?? 'Not set'}</dd>
        </div>
        {detail.driftStatus && (
          <div className="flex justify-between gap-4 border-b border-[rgba(0,0,0,0.06)] py-2">
            <dt className={MUTED}>Drift</dt>
            <dd className="text-right font-medium">{detail.driftStatus}</dd>
          </div>
        )}
      </dl>
      <adapter.ConfigFields group={detail} current={current} />
      {adapter.provider === 'GCP' && onEditPolicy && (
        <GcpConfigTab detail={detail} current={current} onReview={onEditPolicy} />
      )}
    </div>
  );
};

export default ConfigTab;
