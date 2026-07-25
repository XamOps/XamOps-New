import React, { useMemo, useState } from 'react';
import type { AttackSurfaceSummary, IssueSummary } from '../../types/xamsecure';
import SeverityChip from './SeverityChip';

interface Props {
  data: AttackSurfaceSummary | null;
}

const TYPE_LABEL: Record<string, string> = {
  AWS_S3_BUCKET: 'S3 Buckets',
  AWS_EC2_INSTANCE: 'EC2 Instances',
  AWS_ALB: 'Load Balancers',
  AWS_CLASSIC_LOAD_BALANCER: 'Classic LBs',
  AWS_EFS: 'EFS',
  AWS_SSM_MANAGED_INSTANCE: 'SSM Instances',
  AWS_SECRETS_MANAGER_SECRET: 'Secrets',
  AWS_SNS_TOPIC: 'SNS Topics',
  AWS_CLOUD_TRAIL: 'CloudTrail',
  AWS_ROUTE53_DNS_RECORD: 'DNS Records',
  other: 'Other',
};
const typeLabel = (t: string) => TYPE_LABEL[t] ?? t.replace(/^AWS_/, '').replace(/_/g, ' ');

const SEV_RANK = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW'];
const SEV_COLOR: Record<string, string> = {
  CRITICAL: '#dc2626', HIGH: '#f97316', MEDIUM: '#eab308', LOW: '#10b981',
};

/**
 * Attack-surface exposure map — the internet-exposed slice of the inventory, drawn as a
 * hub-and-spoke graph (account hub → exposed resource groups) from REAL attack-surface data.
 * This is NOT Upwind's runtime traffic map (traffic-by-process/ports/protocol/chatty resources
 * come from sensor telemetry that the API blocks with 401); we only draw what we can back with
 * data we actually have: which resource kinds are exposed, how many, and at what severity.
 */
const ExposureMap: React.FC<Props> = ({ data }) => {
  const exposedByType = data?.exposedByType ?? [];
  const findings = data?.findings ?? [];
  const total = data?.exposedResourceCount ?? 0;

  // Highest severity seen per resource kind (from the exposure findings).
  const sevByType = useMemo(() => {
    const m: Record<string, string> = {};
    findings.forEach((f) => {
      const k = f.resourceKind ?? 'other';
      const cur = m[k];
      if (!cur || SEV_RANK.indexOf((f.severity ?? 'LOW').toUpperCase()) < SEV_RANK.indexOf(cur)) {
        m[k] = (f.severity ?? 'LOW').toUpperCase();
      }
    });
    return m;
  }, [findings]);

  // Cap nodes so the ring stays readable; overflow folded into "Other".
  const nodes = useMemo(() => {
    const top = exposedByType.slice(0, 9);
    const restCount = exposedByType.slice(9).reduce((a, t) => a + t.count, 0);
    const list = top.map((t) => ({ type: t.type, count: t.count }));
    if (restCount > 0) list.push({ type: 'other', count: restCount });
    return list;
  }, [exposedByType]);

  const [selected, setSelected] = useState<string | null>(null);

  // Radial layout: hub in the centre, Internet + resource-kind nodes evenly around a circle.
  const W = 520, H = 420, cx = 260, cy = 210, R = 150;
  const peripheral = nodes.length;

  const selectedFindings: IssueSummary[] = useMemo(
    () => (selected ? findings.filter((f) => (f.resourceKind ?? 'other') === selected) : []),
    [selected, findings]);

  if (exposedByType.length === 0) {
    return (
      <div className="bg-white border border-gray-200 rounded-xl shadow-sm py-16 text-center text-sm text-gray-400">
        No internet-exposed resources to map.
      </div>
    );
  }

  return (
    <div className="grid grid-cols-1 lg:grid-cols-3 gap-4">
      <div className="lg:col-span-2 bg-white border border-gray-200 rounded-xl shadow-sm p-2">
        <svg viewBox={`0 0 ${W} ${H}`} className="w-full" style={{ maxHeight: 460 }}>
          {/* edges */}
          <line x1={cx} y1={cy} x2={cx} y2={60} stroke="#cbd5e1" strokeWidth={1.5} />
          {nodes.map((_n, i) => {
            const ang = (-90 + (360 / (peripheral + 1)) * (i + 1)) * (Math.PI / 180);
            const x = cx + R * Math.cos(ang);
            const y = cy + R * Math.sin(ang);
            return <line key={`e${i}`} x1={cx} y1={cy} x2={x} y2={y} stroke="#e2e8f0" strokeWidth={1.5} />;
          })}

          {/* Internet node (top) */}
          <g>
            <circle cx={cx} cy={44} r={26} fill="#eff6ff" stroke="#3b82f6" strokeWidth={1.5} />
            <text x={cx} y={44} textAnchor="middle" dominantBaseline="central" fontSize={20}>🌐</text>
            <text x={cx} y={80} textAnchor="middle" fontSize={10} fontWeight={600} fill="#475569">Internet</text>
          </g>

          {/* Cloud hub (centre) */}
          <g>
            <circle cx={cx} cy={cy} r={34} fill="#111827" />
            <text x={cx} y={cy - 3} textAnchor="middle" fontSize={11} fontWeight={700} fill="#fff">Cloud</text>
            <text x={cx} y={cy + 11} textAnchor="middle" fontSize={9} fill="#cbd5e1">{total} exposed</text>
          </g>

          {/* Resource-kind nodes */}
          {nodes.map((n, i) => {
            const ang = (-90 + (360 / (peripheral + 1)) * (i + 1)) * (Math.PI / 180);
            const x = cx + R * Math.cos(ang);
            const y = cy + R * Math.sin(ang);
            const sev = sevByType[n.type] ?? 'LOW';
            const color = SEV_COLOR[sev] ?? '#64748b';
            const active = selected === n.type;
            return (
              <g key={n.type} style={{ cursor: 'pointer' }} onClick={() => setSelected(active ? null : n.type)}>
                <circle cx={x} cy={y} r={active ? 24 : 20} fill="#fff" stroke={color}
                        strokeWidth={active ? 3 : 2} />
                <text x={x} y={y} textAnchor="middle" dominantBaseline="central" fontSize={11} fontWeight={700}
                      fill={color}>{n.count}</text>
                <text x={x} y={y + (y > cy ? 34 : -30)} textAnchor="middle" fontSize={9} fontWeight={600}
                      fill="#475569">{typeLabel(n.type)}</text>
              </g>
            );
          })}
        </svg>
        <p className="px-4 pb-2 text-[10px] text-gray-400">
          Internet-exposed resources by kind (real attack-surface data). Node colour = highest severity;
          click a node for its findings. Live traffic/connections require Upwind runtime-sensor access.
        </p>
      </div>

      {/* Detail panel */}
      <div className="bg-white border border-gray-200 rounded-xl shadow-sm p-5">
        {!selected ? (
          <div className="text-xs text-gray-400 py-8 text-center">Select a node to see its exposure findings.</div>
        ) : (
          <>
            <p className="text-xs font-semibold text-gray-500 uppercase tracking-wide mb-1">{typeLabel(selected)}</p>
            <p className="text-2xl font-bold text-gray-900 mb-3">
              {nodes.find((n) => n.type === selected)?.count ?? 0}
              <span className="text-xs font-normal text-gray-500"> exposed</span>
            </p>
            <div className="space-y-1.5">
              {selectedFindings.map((f) => (
                <div key={f.check} className="flex items-center justify-between py-1.5 border-b border-gray-50 last:border-0">
                  <span className="flex items-center gap-2 min-w-0">
                    <SeverityChip severity={f.severity} />
                    <span className="text-xs text-gray-700 truncate">{f.title}</span>
                  </span>
                  <span className="text-xs font-bold text-gray-700 pl-2">{f.resourceCount}</span>
                </div>
              ))}
              {selectedFindings.length === 0 && (
                <p className="text-xs text-gray-400 py-4 text-center">No findings for this kind.</p>
              )}
            </div>
          </>
        )}
      </div>
    </div>
  );
};

export default ExposureMap;
