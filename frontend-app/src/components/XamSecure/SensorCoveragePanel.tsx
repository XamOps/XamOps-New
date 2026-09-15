import React, { useState } from 'react';
import { Icon } from '@iconify/react';
import type { CoverageStatus, SensorCoverage, SensorCoverageAccount } from '../../types/xamsecure';

/**
 * Sensor coverage per account — "am I actually protected?", which is the question a customer has
 * immediately after connecting and which a connection badge alone cannot answer.
 *
 * An account can read Connected and be running no sensor at all: the cloud scanner sees
 * configuration, but nothing is watching the workloads. That case is invisible on the onboarding
 * list, so it gets its own panel rather than a column.
 *
 * Coverage is over WORKLOADS, never all assets. A storage bucket or an identity has no sensor to
 * run, so including them would dilute the number towards meaningless — the same account can read
 * 42% across everything and 84% across the things a sensor can protect.
 */

const STATUS_META: Record<CoverageStatus, {
  label: string; icon: string; tone: string; ring: string; advice: string;
}> = {
  FULL: {
    label: 'Protected', icon: 'mdi:shield-check', tone: 'text-emerald-600',
    ring: 'border-emerald-200 bg-emerald-50',
    advice: 'Runtime protection is running across effectively all workloads.',
  },
  PARTIAL: {
    label: 'Partly protected', icon: 'mdi:shield-half-full', tone: 'text-amber-600',
    ring: 'border-amber-200 bg-amber-50',
    advice: 'Some workloads have no runtime sensor. Deploy to the gaps below.',
  },
  MINIMAL: {
    label: 'Barely protected', icon: 'mdi:shield-alert', tone: 'text-orange-600',
    ring: 'border-orange-200 bg-orange-50',
    advice: 'Most workloads are running without a sensor.',
  },
  NO_SENSOR: {
    label: 'No sensor deployed', icon: 'mdi:shield-off', tone: 'text-red-600',
    ring: 'border-red-200 bg-red-50',
    advice: 'This account is connected but nothing is watching its workloads at runtime. '
          + 'Configuration is still scanned; live threat detection is not.',
  },
  NO_WORKLOADS: {
    label: 'No workloads', icon: 'mdi:shield-outline', tone: 'text-gray-400',
    ring: 'border-gray-200 bg-gray-50',
    advice: 'Nothing here needs a runtime sensor yet.',
  },
};

const TYPE_LABEL: Record<string, string> = {
  virtual_machine: 'Virtual machines',
  kubernetes_workload: 'Kubernetes workloads',
  kubernetes_node: 'Kubernetes nodes',
  ecs_workload: 'ECS workloads',
  serverless_function: 'Serverless functions',
  container_cluster: 'Container clusters',
  scaling_group: 'Scaling groups',
  virtual_machine_image: 'Machine images',
};
const typeLabel = (s: string) => TYPE_LABEL[s] ?? s.replace(/_/g, ' ');

interface Props {
  data: SensorCoverage | null;
  isLoading: boolean;
}

const SensorCoveragePanel: React.FC<Props> = ({ data, isLoading }) => {
  const accounts = data?.accounts ?? [];

  if (isLoading && accounts.length === 0) {
    return (
      <div className="rounded-xl border border-gray-200 bg-white p-6">
        <p className="text-xs text-gray-400">Checking runtime coverage…</p>
      </div>
    );
  }
  if (accounts.length === 0) return null;

  // Worst first, and the backend already sorts that way. Surfacing the count here means a reader
  // with many accounts sees the scale of the gap without expanding anything.
  const needsAttention = accounts.filter(
    (a) => a.status === 'NO_SENSOR' || a.status === 'MINIMAL').length;

  return (
    <div className="rounded-xl border border-gray-200 bg-white">
      <div className="flex items-start justify-between gap-3 border-b border-gray-100 px-5 py-4">
        <div>
          <h3 className="text-sm font-semibold text-gray-900">Runtime coverage</h3>
          <p className="mt-0.5 text-[11px] text-gray-500">
            Connecting an account scans its configuration. A sensor is what watches the workloads
            while they run.
          </p>
        </div>
        {needsAttention > 0 && (
          <span className="shrink-0 rounded-full bg-red-50 px-2.5 py-1 text-[10px] font-semibold text-red-600">
            {needsAttention} need{needsAttention === 1 ? 's' : ''} a sensor
          </span>
        )}
      </div>

      <div className="divide-y divide-gray-100">
        {accounts.map((a) => <CoverageRow key={a.cloudAccountId} account={a} />)}
      </div>
    </div>
  );
};

const CoverageRow: React.FC<{ account: SensorCoverageAccount }> = ({ account }) => {
  const [open, setOpen] = useState(false);
  const meta = STATUS_META[account.status] ?? STATUS_META.NO_WORKLOADS;
  const pct = account.coveragePercent;
  const gaps = account.workloadTypes.filter((t) => t.sensor < t.total);

  return (
    <div className="px-5 py-4">
      <div className="flex flex-wrap items-center gap-3">
        <span className={`inline-flex items-center gap-1.5 rounded-lg border px-2.5 py-1 ${meta.ring}`}>
          <Icon icon={meta.icon} width={14} className={meta.tone} />
          <span className={`text-[11px] font-semibold ${meta.tone}`}>{meta.label}</span>
        </span>

        <span className="min-w-0">
          <span className="block truncate text-xs font-medium text-gray-800">
            {account.accountName}
          </span>
          <span className="block font-mono text-[10px] text-gray-400">{account.cloudAccountId}</span>
        </span>

        <span className="ml-auto flex items-center gap-4">
          {/* Null percent means there was nothing to measure. A 0% badge on an account with no
              workloads reads as a failure, so it is not shown as one. */}
          {pct != null && (
            <span className="text-right">
              <span className="block text-base font-semibold leading-none text-gray-900">
                {pct}%
              </span>
              <span className="block text-[10px] leading-none text-gray-400 mt-1">
                {account.sensorProtected}/{account.workloads} workloads
              </span>
            </span>
          )}
          {gaps.length > 0 && (
            <button
              type="button"
              onClick={() => setOpen((v) => !v)}
              aria-expanded={open}
              className="rounded-lg border border-gray-200 px-2 py-1 text-[10px] font-medium
                         text-gray-600 hover:bg-gray-50"
            >
              {open ? 'Hide' : 'Show'} gaps
            </button>
          )}
        </span>
      </div>

      {pct != null && (
        <div className="mt-3 h-1.5 w-full overflow-hidden rounded-full bg-gray-100">
          <div
            className={`h-full rounded-full ${
              account.status === 'FULL' ? 'bg-emerald-500'
                : account.status === 'PARTIAL' ? 'bg-amber-500'
                  : account.status === 'MINIMAL' ? 'bg-orange-500' : 'bg-red-500'}`}
            style={{ width: `${Math.max(pct, account.sensorProtected > 0 ? 2 : 0)}%` }}
          />
        </div>
      )}

      <p className="mt-2 text-[11px] leading-relaxed text-gray-500">{meta.advice}</p>

      {open && gaps.length > 0 && (
        <div className="mt-3 space-y-1.5 rounded-lg bg-gray-50 p-3">
          {gaps.map((t) => (
            <div key={t.subCategory} className="flex items-center gap-3 text-[11px]">
              <span className="min-w-0 flex-1 truncate text-gray-700">
                {typeLabel(t.subCategory)}
              </span>
              <span className="shrink-0 text-gray-500">
                {t.sensor}/{t.total} covered
              </span>
              {/* Unknown is reported separately from unprotected on purpose: one means we were
                  told there is no protection, the other means we were told nothing. */}
              {t.unknown > 0 && (
                <span className="shrink-0 text-gray-400" title="No protection status reported">
                  {t.unknown} unreported
                </span>
              )}
            </div>
          ))}
          {account.nonWorkloadAssets > 0 && (
            <p className="border-t border-gray-200 pt-2 text-[10px] text-gray-400">
              {account.nonWorkloadAssets.toLocaleString()} other asset(s) in this account have no
              runtime sensor to deploy — storage, identities and similar. They are excluded from
              the percentage above.
            </p>
          )}
        </div>
      )}
    </div>
  );
};

export default SensorCoveragePanel;
