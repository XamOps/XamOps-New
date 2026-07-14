import React, { useState } from 'react';
import type { AttackSurfaceSummary, IssueSummary, IssueDetail } from '../../types/xamsecure';
import { SEVERITY_ORDER } from '../../types/xamsecure';
import SeverityChip from './SeverityChip';
import IssueDrawer from './IssueDrawer';

interface Props {
  data: AttackSurfaceSummary | null;
  isLoading: boolean;
  loadDetail: (check: string) => Promise<IssueDetail>;
}

const TYPE_LABEL: Record<string, string> = {
  AWS_EC2_SECURITY_GROUP: 'Security Groups',
  AWS_EC2_SUBNET: 'Subnets',
  AWS_EC2_NETWORK_ACL: 'Network ACLs',
  AWS_S3_BUCKET: 'S3 Buckets',
  AWS_EC2_INSTANCE: 'EC2 Instances',
  AWS_ROUTE53_DNS_RECORD: 'DNS Records',
  AWS_SECRETS_MANAGER_SECRET: 'Secrets',
  AWS_SNS_TOPIC: 'SNS Topics',
  AWS_ALB: 'Application Load Balancers',
  AWS_CLASSIC_LOAD_BALANCER: 'Classic Load Balancers',
  AWS_EFS: 'EFS File Systems',
  AWS_CLOUD_TRAIL: 'CloudTrail',
  AWS_SSM_MANAGED_INSTANCE: 'SSM Managed Instances',
  other: 'Other',
};
const typeLabel = (t: string) => TYPE_LABEL[t] ?? t.replace(/^AWS_/, '').replace(/_/g, ' ');

/** Singular "resource kind" label for the findings table (matches Upwind's column). */
const KIND_LABEL: Record<string, string> = {
  AWS_S3_BUCKET: 'AWS S3 Bucket',
  AWS_EC2_INSTANCE: 'AWS EC2 Instance',
  AWS_ALB: 'AWS Application Load Balancer',
  AWS_CLASSIC_LOAD_BALANCER: 'AWS Classic Load Balancer',
  AWS_EFS: 'AWS EFS',
  AWS_CLOUD_TRAIL: 'AWS CloudTrail',
  AWS_SSM_MANAGED_INSTANCE: 'AWS SSM Managed Instance',
  AWS_SECRETS_MANAGER_SECRET: 'AWS Secrets Manager Secret',
  AWS_SNS_TOPIC: 'AWS SNS Topic',
};
const kindLabel = (t: string | null | undefined) =>
  !t ? '—' : (KIND_LABEL[t] ?? t.replace(/_/g, ' '));

const IMPACT_ORDER = ['Public exposure', 'Authenticated exposure', 'Other exposure'];

const AttackSurfaceTab: React.FC<Props> = ({ data, isLoading, loadDetail }) => {
  const [view, setView] = useState<'dashboard' | 'findings'>('dashboard');
  const [selected, setSelected] = useState<IssueSummary | null>(null);
  const [detail, setDetail] = useState<IssueDetail | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);

  const findings = data?.findings ?? [];
  const exposedByType = data?.exposedByType ?? [];
  const severityCounts = data?.severityCounts ?? {};

  const openFinding = async (f: IssueSummary) => {
    setSelected(f);
    setDetail(null);
    setDetailLoading(true);
    try { setDetail(await loadDetail(f.check)); }
    catch (err) { console.error('[XamSecure] attack-surface detail failed:', err); }
    finally { setDetailLoading(false); }
  };

  if (isLoading && !data) {
    return <div className="text-center py-16 text-sm text-gray-400">Loading attack surface...</div>;
  }

  return (
    <div className="space-y-4">
      <div className="flex items-center gap-2">
        <ViewTab label="Dashboard" active={view === 'dashboard'} onClick={() => setView('dashboard')} />
        <ViewTab label="Findings" active={view === 'findings'} onClick={() => setView('findings')} />
      </div>

      {view === 'dashboard' ? (
        <div className="space-y-4">
          {/* Headline stats */}
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
            <Stat value={data?.exposedResourceCount ?? 0} label="Exposed resources" />
            <Stat value={findings.length} label="External exposure findings" />
          </div>

          <div className="grid grid-cols-1 lg:grid-cols-3 gap-4">
            {/* Exposed resources breakdown */}
            <Panel title={`Exposed resources breakdown (${data?.exposedResourceCount ?? 0})`}>
              <div className="space-y-1.5">
                {exposedByType.map((t) => (
                  <div key={t.type} className="flex items-center justify-between py-1.5 border-b border-gray-50 last:border-0">
                    <span className="text-xs text-gray-700">{typeLabel(t.type)}</span>
                    <span className="text-xs font-bold text-gray-900">{t.count}</span>
                  </div>
                ))}
                {exposedByType.length === 0 && <p className="py-4 text-center text-xs text-gray-400">No exposed resources.</p>}
              </div>
            </Panel>

            {/* Findings by severity */}
            <Panel title="Attack surface findings summary">
              <table className="w-full text-xs">
                <thead>
                  <tr className="text-left text-[10px] uppercase text-gray-400 border-b border-gray-100">
                    <th className="py-2">Severity</th>
                    <th className="py-2 text-right">Findings</th>
                  </tr>
                </thead>
                <tbody>
                  {SEVERITY_ORDER.map((sev) => (
                    <tr key={sev} className="border-b border-gray-50 last:border-0">
                      <td className="py-2.5"><SeverityChip severity={sev} /></td>
                      <td className="py-2.5 text-right font-bold text-gray-900">{severityCounts[sev] ?? 0}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </Panel>

            {/* Top external exposure findings */}
            <Panel title="Top external exposure findings" action={
              <button onClick={() => setView('findings')} className="text-[11px] font-semibold text-gray-500 hover:text-gray-900">All findings</button>
            }>
              <div className="space-y-0.5">
                {findings.slice(0, 6).map((f) => (
                  <div key={f.check} className="flex items-center justify-between py-2 border-b border-gray-50 last:border-0 cursor-pointer hover:bg-gray-50 -mx-2 px-2 rounded"
                       onClick={() => openFinding(f)}>
                    <span className="flex items-center gap-2 min-w-0">
                      <SeverityChip severity={f.severity} />
                      <span className="text-xs text-gray-800 truncate">{f.title}</span>
                    </span>
                    <span className="text-xs font-bold text-gray-700 pl-2">{f.resourceCount}</span>
                  </div>
                ))}
                {findings.length === 0 && <p className="py-4 text-center text-xs text-gray-400">No exposure findings.</p>}
              </div>
            </Panel>
          </div>
        </div>
      ) : (
        <FindingsByImpact findings={findings} onOpen={openFinding} />
      )}

      {selected && (
        <IssueDrawer title={selected.title} detail={detail} isLoading={detailLoading}
                     onClose={() => { setSelected(null); setDetail(null); }} />
      )}
    </div>
  );
};

/** Findings grouped by ASM impact — mirrors Upwind's Attack surface → Findings layout. */
const FindingsByImpact: React.FC<{ findings: IssueSummary[]; onOpen: (f: IssueSummary) => void }> =
  ({ findings, onOpen }) => {
    const groups = new Map<string, IssueSummary[]>();
    findings.forEach((f) => {
      const k = f.impact ?? 'Other exposure';
      if (!groups.has(k)) groups.set(k, []);
      groups.get(k)!.push(f);
    });
    const orderedImpacts = [
      ...IMPACT_ORDER.filter((i) => groups.has(i)),
      ...[...groups.keys()].filter((i) => !IMPACT_ORDER.includes(i)),
    ];
    const impactCount = orderedImpacts.length;

    const fmtAgo = (iso: string | null) => {
      if (!iso) return '—';
      const d = new Date(iso);
      const days = Math.floor((Date.now() - d.getTime()) / 86400000);
      const rel = days <= 0 ? 'today' : days === 1 ? '1 day ago' : days < 7 ? `${days} days ago`
        : days < 14 ? '1 week ago' : `${Math.floor(days / 7)} weeks ago`;
      return `${rel}\n${d.toLocaleDateString(undefined, { month: 'short', day: '2-digit', year: 'numeric' })}`;
    };

    if (findings.length === 0) {
      return (
        <div className="bg-white border border-gray-200 rounded-xl shadow-sm py-12 text-center text-gray-400 text-sm">
          No external exposure findings.
        </div>
      );
    }

    return (
      <div className="space-y-4">
        <p className="text-xs text-gray-500">
          Showing {impactCount} {impactCount === 1 ? 'impact' : 'impacts'} · {findings.length} findings
        </p>
        {orderedImpacts.map((impact) => {
          const rows = groups.get(impact)!;
          return (
            <div key={impact} className="bg-white border border-gray-200 rounded-xl shadow-sm overflow-hidden">
              <div className="flex items-center gap-2 px-5 py-3 border-b border-gray-200">
                <span className="w-1.5 h-4 rounded-sm bg-gray-800" />
                <p className="text-xs font-bold text-gray-800">{impact}</p>
                <span className="text-[10px] font-semibold text-gray-400">
                  {rows.length} {rows.length === 1 ? 'Finding' : 'Findings'}
                </span>
              </div>
              <div className="overflow-x-auto">
                <table className="w-full text-xs">
                  <thead>
                    <tr className="text-left text-[10px] uppercase text-gray-400 border-b border-gray-100">
                      <th className="px-5 py-2.5">Finding</th>
                      <th className="px-5 py-2.5">Resource kind</th>
                      <th className="px-5 py-2.5">Impacted resources</th>
                      <th className="px-5 py-2.5">First seen</th>
                      <th className="px-5 py-2.5">Last ASM execution</th>
                    </tr>
                  </thead>
                  <tbody>
                    {rows.map((f) => (
                      <tr key={f.check} className="border-b border-gray-50 last:border-0 hover:bg-gray-50 cursor-pointer"
                          onClick={() => onOpen(f)}>
                        <td className="px-5 py-3">
                          <span className="flex items-center gap-2 min-w-0">
                            <SeverityChip severity={f.severity} />
                            <span className="font-medium text-gray-800 max-w-xs truncate">{f.title}</span>
                          </span>
                        </td>
                        <td className="px-5 py-3 text-gray-600">{kindLabel(f.resourceKind)}</td>
                        <td className="px-5 py-3 font-semibold text-gray-700">{f.resourceCount}</td>
                        <td className="px-5 py-3 whitespace-pre-line text-gray-500 text-[11px]">{fmtAgo(f.firstSeen)}</td>
                        <td className="px-5 py-3 whitespace-pre-line text-gray-500 text-[11px]">{fmtAgo(f.lastEvaluation)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>
          );
        })}
      </div>
    );
  };

const Stat: React.FC<{ value: number; label: string }> = ({ value, label }) => (
  <div className="bg-white border border-gray-200 rounded-xl shadow-sm p-6 text-center">
    <p className="text-4xl font-extrabold text-gray-900">{value}</p>
    <p className="mt-1 text-xs font-semibold text-gray-500">{label}</p>
  </div>
);

const Panel: React.FC<{ title: string; action?: React.ReactNode; children: React.ReactNode }> = ({ title, action, children }) => (
  <div className="bg-white border border-gray-200 rounded-xl shadow-sm flex flex-col">
    <div className="flex items-center justify-between px-5 py-3 border-b border-gray-200">
      <p className="text-xs font-semibold text-gray-500 uppercase tracking-wide">{title}</p>
      {action}
    </div>
    <div className="p-5 flex-1">{children}</div>
  </div>
);

const ViewTab: React.FC<{ label: string; active: boolean; onClick: () => void }> = ({ label, active, onClick }) => (
  <button onClick={onClick}
          className={`px-3 py-1.5 text-xs font-semibold rounded-lg transition-colors ${
            active ? 'bg-gray-900 text-white' : 'text-gray-500 hover:bg-gray-100'}`}>
    {label}
  </button>
);

export default AttackSurfaceTab;
