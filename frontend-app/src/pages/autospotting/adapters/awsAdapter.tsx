import React from 'react';
import { AWS_SERVICE_ICONS } from '../../../utils/awsServiceIcons';
import type { ProviderAdapter } from './types';

const NOT_READ = 'Shown when you review a plan';

const str = (v: unknown): string | null => {
  if (v === null || v === undefined || v === '') return null;
  if (Array.isArray(v)) return v.length ? v.join(', ') : null;
  if (typeof v === 'object') return JSON.stringify(v);
  return String(v);
};

const launchTemplateText = (lt: unknown): string | null => {
  if (!lt || typeof lt !== 'object') return str(lt);
  const o = lt as Record<string, unknown>;
  const name = str(o.name ?? o.id ?? o.launchTemplateName ?? o.launchTemplateId);
  const version = str(o.version ?? o.resolvedVersion);
  if (!name) return null;
  return version ? `${name} (version ${version})` : name;
};

const distributionText = (d: unknown): string | null => {
  if (!d || typeof d !== 'object') return null;
  const o = d as Record<string, unknown>;
  const base = o.onDemandBaseCapacity;
  const pct = o.onDemandPercentageAboveBaseCapacity;
  const strategy = str(o.spotAllocationStrategy);
  const parts = [
    base !== undefined && base !== null ? `On-Demand base ${base}` : null,
    pct !== undefined && pct !== null ? `${pct}% On-Demand above base` : null,
    strategy ? `Spot strategy ${strategy}` : null,
  ].filter(Boolean);
  return parts.length ? parts.join(', ') : null;
};

const Field: React.FC<{ label: string; value: string | null }> = ({ label, value }) => (
  <div className="flex justify-between gap-4 border-b border-[rgba(0,0,0,0.06)] py-2 text-[13px]">
    <dt className="text-[rgba(0,0,0,0.55)]">{label}</dt>
    <dd className="text-right font-medium text-[rgba(0,0,0,0.88)]">{value ?? NOT_READ}</dd>
  </div>
);

export const awsAdapter: ProviderAdapter = {
  provider: 'AWS',
  icon: AWS_SERVICE_ICONS['Auto Scaling Group'],
  groupNoun: 'Auto Scaling Group',
  locationLabel: 'Region',
  columns: [{ id: 'instanceType', header: 'Instance type', render: row => row.instanceType ?? '--' }],

  ConfigFields: ({ group, current }) => {
    const c = current ?? {};
    return (
      <dl>
        <Field label="Launch template and version" value={launchTemplateText(c.launchTemplate)} />
        <Field label="Distribution" value={distributionText(c.instancesDistribution)} />
        <Field label="Instance type overrides" value={str(c.overrides)} />
        <Field
          label="Capacity Rebalancing"
          value={c.capacityRebalance === undefined ? null : c.capacityRebalance ? 'On' : 'Off'}
        />
        <Field label="Instance refresh status" value={group.currentOperation ? group.currentOperation.status : 'None running'} />
        <Field label="Stack template version" value={null} />
      </dl>
    );
  },

  PlanTargetFields: ({ body }) => {
    const t = body.target ?? {};
    return (
      <dl>
        <Field label="Launch template and version" value={launchTemplateText(t.launchTemplate)} />
        <Field label="Distribution" value={distributionText(t.instancesDistribution)} />
        <Field label="Instance type overrides" value={str(t.overrides)} />
        <Field
          label="Capacity Rebalancing"
          value={t.capacityRebalance === undefined ? null : t.capacityRebalance ? 'On' : 'Off'}
        />
      </dl>
    );
  },

  permissionGuidance: code => {
    if (code === 'REVERT_NEEDS_PERMISSION') {
      return {
        label: 'Update stack',
        text: 'Revert needs a permission the XamOps stack in this account does not have yet. Update the stack, then try again.',
        href: '/account-manager',
      };
    }
    return {
      label: 'Update stack',
      text: "XamOps can't read Auto Scaling in this account. Update the XamOps stack to grant access.",
      href: '/account-manager',
    };
  },
};
