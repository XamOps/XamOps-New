import React from 'react';
import type { GroupDetail } from '../../../api/autospottingApi';
import type { ProviderAdapter } from '../adapters/types';
import GcpConfigTab from './GcpConfigTab';

export const ConfigTab: React.FC<{
  detail: GroupDetail;
  adapter: ProviderAdapter;
  current?: Record<string, unknown> | null;
  /** GCP: open the plan dialog for an edited rolling-update policy. */
  onEditPolicy?: (kind: string, policyEdit: Record<string, unknown>) => void;
}> = ({ detail, adapter, current, onEditPolicy }) => {
  const g = detail.group;
  return (
    <div className="space-y-4 text-sm text-black">
      <dl>
        <div className="flex justify-between border-b border-gray-100 py-1.5">
          <dt className="text-gray-500">Capacity (min / desired / max)</dt>
          <dd>{`${g.min ?? '--'} / ${g.desired ?? '--'} / ${g.max ?? '--'}`}</dd>
        </div>
        <div className="flex justify-between border-b border-gray-100 py-1.5">
          <dt className="text-gray-500">Configuration mode</dt>
          <dd>{detail.configMode ?? 'Not set'}</dd>
        </div>
        {detail.driftStatus && (
          <div className="flex justify-between border-b border-gray-100 py-1.5">
            <dt className="text-gray-500">Drift</dt>
            <dd>{detail.driftStatus}</dd>
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
