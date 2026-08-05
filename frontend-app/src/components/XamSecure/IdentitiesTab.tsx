import React, { useMemo, useState } from 'react';
import { Icon } from '@iconify/react';
import type { IdentitiesSummary, ResourceDetail } from '../../types/xamsecure';
import { SEVERITY_ORDER } from '../../types/xamsecure';
import SeverityChip from './SeverityChip';
import Pager, { usePaged } from './Pager';
import ResourceLink from './ResourceLink';
import FilterChips, { SEVERITY_TINT } from './FilterChips';
import { useResourceDrawer } from './useResourceDrawer';

interface Props {
  data: IdentitiesSummary | null;
  isLoading: boolean;
  loadResourceDetail?: (resourceId: string) => Promise<ResourceDetail>;
}

const TYPE_LABEL: Record<string, string> = {
  AWS_IAM_USER: 'IAM Users',
  AWS_IAM_ROLE: 'IAM Roles',
  AWS_IAM_POLICY: 'IAM Policies',
  AWS_IAM_GROUP: 'IAM Groups',
  AWS_IAM_OIDC_PROVIDER: 'Identity Providers',
  OTHER: 'Other',
};
const typeLabel = (t: string) => TYPE_LABEL[t] ?? t.replace(/^AWS_/, '').replace(/_/g, ' ');

const fmt = (iso: string | null) => (iso ? new Date(iso).toLocaleDateString() : '—');

/**
 * Identities — the full roster from Upwind's inventory catalog (IAM users, roles, groups and
 * policies, plus Kubernetes service accounts, roles and bindings), annotated with failing
 * configuration checks and Upwind's own high_privilege_risk flag.
 *
 * What is still absent is Upwind's CIEM analysis: entitlement/least-privilege
 * recommendations, the human vs non-human split, and the cross-account role-trust graph.
 * Seven candidate identity endpoints all return 404, and access-management/* is Upwind
 * console user management rather than cloud identities.
 */
const IdentitiesTab: React.FC<Props> = ({ data, isLoading, loadResourceDetail }) => {
  const [severity, setSeverity] = useState<string | null>(null);
  const { open, drawer } = useResourceDrawer(loadResourceDetail);
  const privileged = data?.privileged ?? [];

  const all = data?.identities ?? [];
  const filtered = useMemo(
    () => (severity === null
      ? all
      : all.filter((i) => (i.severity ?? '').toUpperCase() === severity)),
    [all, severity]);

  const { page, setPage, pageItems: rows, totalPages, total } = usePaged(filtered, 25);

  const severityGroups = useMemo(() => {
    const counts = data?.severityCounts ?? {};
    return SEVERITY_ORDER
      .filter((s) => (counts[s] ?? 0) > 0)
      .map((s) => ({ value: s, label: s.charAt(0) + s.slice(1).toLowerCase(), count: counts[s] }));
  }, [data]);

  if (isLoading && all.length === 0) {
    return <div className="text-center py-16 text-sm text-gray-400">Loading identities...</div>;
  }

  return (
    <div className="space-y-4">
      <div className="grid grid-cols-2 sm:grid-cols-4 gap-4">
        <Stat value={data?.total ?? 0} label="Identities" />
        <Stat value={data?.privilegedTotal ?? 0} label="Elevated privilege" accent="text-purple-600" />
        <Stat value={data?.withFindings ?? 0} label="With failing checks" accent="text-orange-600" />
        <Stat value={data?.severityCounts?.CRITICAL ?? 0} label="Critical" accent="text-red-600" />
      </div>

      {privileged.length > 0 && (
        <div className="bg-white border border-gray-200 rounded-xl shadow-sm overflow-hidden">
          <div className="px-5 py-3 border-b border-gray-200">
            <p className="text-xs font-semibold text-gray-500 uppercase tracking-wide">
              Elevated privilege ({privileged.length})
            </p>
            <p className="text-[10px] text-gray-400 mt-0.5">
              Assets Upwind flagged as holding elevated or high-level privileges. Listed whether
              or not they currently fail a check.
            </p>
          </div>
          <div className="overflow-x-auto">
            <table className="w-full text-xs">
              <thead>
                <tr className="text-left text-[10px] uppercase text-gray-400 border-b border-gray-100">
                  <th className="px-5 py-2.5">Asset</th>
                  <th className="px-5 py-2.5">Type</th>
                  <th className="px-5 py-2.5">Region</th>
                  <th className="px-5 py-2.5">Exposure</th>
                  <th className="px-5 py-2.5">Sensitive data</th>
                  <th className="px-5 py-2.5">Findings</th>
                </tr>
              </thead>
              <tbody>
                {privileged.slice(0, 25).map((p) => (
                  <tr key={p.resourceId ?? p.name} className="border-b border-gray-50 hover:bg-gray-50">
                    <td className="px-5 py-3 text-gray-800">
                      <ResourceLink resourceId={p.resourceId} label={p.name} onOpen={open} />
                    </td>
                    <td className="px-5 py-3 text-gray-600">
                      {p.resourceType ?? p.category ?? '—'}
                    </td>
                    <td className="px-5 py-3 text-gray-500">{p.region ?? '—'}</td>
                    <td className="px-5 py-3">
                      {p.internetExposureType ? (
                        <span className="px-2 py-0.5 rounded-full text-[10px] font-bold bg-red-100 text-red-700">
                          {p.internetExposureType}
                        </span>
                      ) : (
                        <span className="text-[10px] text-gray-300">—</span>
                      )}
                    </td>
                    <td className="px-5 py-3">
                      {p.sensitivePii ? (
                        <span className="px-2 py-0.5 rounded-full text-[10px] font-bold bg-purple-100 text-purple-700">
                          PII
                        </span>
                      ) : (
                        <span className="text-[10px] text-gray-300">—</span>
                      )}
                    </td>
                    <td className="px-5 py-3">
                      {p.hasFindings ? (
                        <span className="text-orange-600 font-semibold">Failing checks</span>
                      ) : (
                        <span className="text-emerald-600">Clean</span>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {privileged.length > 25 && (
            <p className="px-5 py-2.5 text-[10px] text-gray-400 border-t border-gray-100">
              Showing the first 25 of {privileged.length}.
            </p>
          )}
        </div>
      )}

      {(data?.byType ?? []).length > 0 && (
        <div className="bg-white border border-gray-200 rounded-xl shadow-sm p-5">
          <p className="text-xs font-semibold text-gray-500 uppercase tracking-wide mb-4">
            Identities by type
          </p>
          <div className="space-y-2.5">
            {(data?.byType ?? []).map((t) => {
              const max = Math.max(1, ...(data?.byType ?? []).map((x) => x.count));
              return (
                <div key={t.type} className="flex items-center gap-3">
                  <span className="w-40 text-[11px] text-gray-600 truncate" title={t.type}>
                    {typeLabel(t.type)}
                  </span>
                  <div className="flex-1 h-2.5 bg-gray-100 rounded-full overflow-hidden">
                    <div className="h-full rounded-full bg-gray-900"
                         style={{ width: `${(t.count / max) * 100}%` }} />
                  </div>
                  <span className="w-8 text-right text-xs font-semibold text-gray-700">{t.count}</span>
                </div>
              );
            })}
          </div>
        </div>
      )}

      <div className="bg-white border border-gray-200 rounded-xl shadow-sm overflow-hidden">
        <div className="flex items-start justify-between gap-3 px-5 py-3 border-b border-gray-200">
          <div>
            <p className="text-xs font-semibold text-gray-500 uppercase tracking-wide">Identities</p>
            <p className="text-[10px] text-gray-400 mt-0.5">
              Every identity in Upwind's inventory. Those failing a check sort first.
            </p>
          </div>
        </div>
        {severityGroups.length > 1 && (
          <div className="px-5 py-3 border-b border-gray-100">
            <FilterChips
              options={severityGroups}
              active={severity}
              allCount={all.length}
              tint={(v) => SEVERITY_TINT[v]}
              onChange={(v) => { setSeverity(v); setPage(0); }}
            />
          </div>
        )}
        <div className="overflow-x-auto">
          <table className="w-full text-xs">
            <thead>
              <tr className="text-left text-[10px] uppercase text-gray-400 border-b border-gray-100">
                <th className="px-5 py-2.5">Severity</th>
                <th className="px-5 py-2.5">Identity</th>
                <th className="px-5 py-2.5">Type</th>
                <th className="px-5 py-2.5">Privilege</th>
                <th className="px-5 py-2.5">Failing checks</th>
                <th className="px-5 py-2.5">First seen</th>
                <th className="px-5 py-2.5">Last evaluation</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((i) => (
                <tr key={i.resourceId ?? i.name} className="border-b border-gray-50 hover:bg-gray-50">
                  <td className="px-5 py-3">
                    {i.severity ? <SeverityChip severity={i.severity} />
                                : <span className="text-[10px] text-gray-300">—</span>}
                  </td>
                  <td className="px-5 py-3 text-gray-800">
                    <ResourceLink resourceId={i.resourceId} label={i.name} onOpen={open} />
                  </td>
                  <td className="px-5 py-3 text-gray-600">{typeLabel(i.resourceType ?? 'OTHER')}</td>
                  <td className="px-5 py-3">
                    {i.elevatedPrivilege ? (
                      <span className="px-2 py-0.5 rounded-full text-[10px] font-bold bg-purple-100 text-purple-700">
                        Elevated
                      </span>
                    ) : (
                      <span className="text-[10px] text-gray-300">—</span>
                    )}
                  </td>
                  <td className="px-5 py-3">
                    {i.findingCount === 0 ? (
                      <span className="text-emerald-600">Clean</span>
                    ) : (
                      <span className="font-semibold text-gray-800">{i.findingCount}</span>
                    )}
                    {i.checks.length > 0 && (
                      <span className="block text-[10px] text-gray-400 max-w-md truncate"
                            title={i.checks.join(' · ')}>
                        {i.checks[0]}
                        {i.checks.length > 1 ? ` +${i.checks.length - 1} more` : ''}
                      </span>
                    )}
                  </td>
                  <td className="px-5 py-3 whitespace-nowrap text-gray-500">{fmt(i.firstSeen)}</td>
                  <td className="px-5 py-3 whitespace-nowrap text-gray-500">{fmt(i.lastSeen)}</td>
                </tr>
              ))}
              {rows.length === 0 && (
                <tr>
                  <td colSpan={7} className="px-5 py-12 text-center text-gray-400">
                    {severity !== null
                      ? `No ${severity.toLowerCase()}-severity identities. Clear the filter to see all.`
                      : 'No identities discovered for the selected account.'}
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
        <Pager page={page} totalPages={totalPages} total={total} unit="identities" onPage={setPage} />
      </div>

      <p className="flex items-start gap-1.5 text-[10px] text-gray-400">
        <Icon icon="mdi:information-outline" width={12} className="flex-shrink-0 mt-0.5" />
        Roster and privilege flags come from Upwind's inventory catalog. Upwind's CIEM analysis
        — entitlement and least-privilege recommendations, the human vs non-human split, and
        the cross-account role-trust graph — has no API endpoint and is not shown.
      </p>
      {drawer}
    </div>
  );
};

const Stat: React.FC<{ value: number; label: string; accent?: string }> = ({ value, label, accent }) => (
  <div className="bg-white border border-gray-200 rounded-xl shadow-sm p-5">
    <p className={`text-2xl font-bold ${accent ?? 'text-gray-900'}`}>{value.toLocaleString()}</p>
    <p className="text-xs text-gray-500 mt-1">{label}</p>
  </div>
);

export default IdentitiesTab;
