/**
 * EksDiagnosticsPanel
 *
 * Shows which dashboard fields the backend is actually returning.
 * Triggered by adding ?debug=true to the EKS details URL.
 * Use this to determine if data is missing at the backend (field absent/empty)
 * vs missing at the frontend (field present but not rendered).
 */

import React, { useState } from 'react';

interface FieldCheck {
  label: string;
  group: string;
  present: boolean;
  count?: number | string;
  sample?: string;
}

function check(label: string, group: string, value: any): FieldCheck {
  if (Array.isArray(value)) {
    return { label, group, present: value.length > 0, count: value.length, sample: value.length > 0 ? JSON.stringify(value[0]).slice(0, 80) + '…' : undefined };
  }
  if (value !== null && value !== undefined && typeof value === 'object') {
    const keys = Object.keys(value);
    return { label, group, present: keys.length > 0, count: `${keys.length} keys`, sample: keys.slice(0, 5).join(', ') };
  }
  if (typeof value === 'number') {
    return { label, group, present: value > 0, count: value };
  }
  return { label, group, present: value !== null && value !== undefined && value !== '' };
}

const EksDiagnosticsPanel: React.FC<{ dashboard: any }> = ({ dashboard: d }) => {
  const [expanded, setExpanded] = useState(true);
  const [rawField, setRawField] = useState<string | null>(null);

  if (!d) return null;

  const fields: FieldCheck[] = [
    // ── Cluster Overview ───────────────────────────────────────────
    check('clusterOverview.totalNodes',     'Cluster',  d?.clusterOverview?.totalNodes),
    check('clusterOverview.runningPods',    'Cluster',  d?.clusterOverview?.runningPods),
    check('clusterOverview.cpuUsagePercent','Cluster',  d?.clusterOverview?.cpuUsagePercent),
    check('clusterOverview.memoryUsagePercent','Cluster', d?.clusterOverview?.memoryUsagePercent),

    // ── Nodes ──────────────────────────────────────────────────────
    check('nodeMetrics.nodes[]',            'Nodes',    d?.nodeMetrics?.nodes),
    check('nodeMetrics.nodes[0].ready',     'Nodes',    d?.nodeMetrics?.nodes?.[0]?.ready),
    check('nodeMetrics.nodes[0].conditions[]','Nodes',  d?.nodeMetrics?.nodes?.[0]?.conditions),
    check('nodeMetrics.nodes[0].cpuUsagePercent','Nodes', d?.nodeMetrics?.nodes?.[0]?.cpuUsagePercent),
    check('nodeMetrics.nodes[0].cpuRequestsPercent','Nodes', d?.nodeMetrics?.nodes?.[0]?.cpuRequestsPercent),
    check('resourceUtilization.nodes[]',    'Nodes',    d?.resourceUtilization?.nodes),

    // ── Pods ───────────────────────────────────────────────────────
    check('k8sPods[]',                      'Pods',     d?.k8sPods),
    check('k8sPods[0].phase',               'Pods',     d?.k8sPods?.[0]?.phase),
    check('k8sPods[0].cpuUsage',            'Pods',     d?.k8sPods?.[0]?.cpuUsage),
    check('workloadHealth.pods[]',          'Pods',     d?.workloadHealth?.pods),
    check('podResourceMetrics (graph data)','Pods',     d?.podResourceMetrics),
    check('podResourceMetrics.totalCpuCapacity','Pods', d?.podResourceMetrics?.totalCpuCapacity),
    check('podResourceMetrics.totalCpuRequests','Pods', d?.podResourceMetrics?.totalCpuRequests),
    check('topCpuPods[]',                   'Pods',     d?.topCpuPods),
    check('topMemoryPods[]',                'Pods',     d?.topMemoryPods),

    // ── Workloads ──────────────────────────────────────────────────
    check('k8sDeployments[]',               'Workloads', d?.k8sDeployments),
    check('k8sDaemonSets[]',                'Workloads', d?.k8sDaemonSets),
    check('k8sStatefulSets[]',              'Workloads', d?.k8sStatefulSets),
    check('workloadHealth.deployments[]',   'Workloads', d?.workloadHealth?.deployments),

    // ── Cost ───────────────────────────────────────────────────────
    check('costMetrics.dailyCost',          'Cost',     d?.costMetrics?.dailyCost),
    check('costMetrics.nodeCosts[]',        'Cost',     d?.costMetrics?.nodeCosts),
    check('costMetrics.namespaceCosts[]',   'Cost',     d?.costMetrics?.namespaceCosts),
    check('costMetrics.workloadCosts[]',    'Cost',     d?.costMetrics?.workloadCosts),
    check('namespaceUtilization[]',         'Cost',     d?.namespaceUtilization),

    // ── Events ─────────────────────────────────────────────────────
    check('k8sEvents[]',                    'Events',   d?.k8sEvents),
    check('k8sEvents[0].involvedObject.name','Events',  d?.k8sEvents?.[0]?.involvedObject?.name),
    check('k8sEvents[0].reason',            'Events',   d?.k8sEvents?.[0]?.reason),
    check('k8sEvents[0].message',           'Events',   d?.k8sEvents?.[0]?.message),

    // ── Security ───────────────────────────────────────────────────
    check('securityMetrics.alerts[]',       'Security', d?.securityMetrics?.alerts),
    check('kubescape (compliance)',          'Security', d?.kubescape),
    check('trivy (image scan)',              'Security', d?.trivy),

    // ── Karpenter ──────────────────────────────────────────────────
    check('karpenter (dedicated object)',   'Karpenter', d?.karpenter),
    check('karpenter.healthy',              'Karpenter', d?.karpenter?.healthy),
    check('karpenter.nodeClaims[]',         'Karpenter', d?.karpenter?.nodeClaims),
    check('karpenter.nodePools[]',          'Karpenter', d?.karpenter?.nodePools),

    // ── Performance ────────────────────────────────────────────────
    check('containerMetrics.containers[]',  'Perf',     d?.containerMetrics?.containers),
  ];

  const groups = [...new Set(fields.map(f => f.group))];
  const missingCount = fields.filter(f => !f.present).length;
  const presentCount = fields.filter(f => f.present).length;

  const getRawValue = (label: string) => {
    const key = label.split('[')[0].split('.');
    let val: any = d;
    for (const k of key) {
      if (val === undefined || val === null) break;
      val = val[k];
    }
    return JSON.stringify(val, null, 2);
  };

  return (
    <div className="border-2 border-dashed border-amber-400 rounded-xl overflow-hidden bg-white">
      {/* Header */}
      <div
        className="bg-amber-50 px-5 py-3 flex items-center justify-between cursor-pointer select-none"
        onClick={() => setExpanded(e => !e)}
      >
        <div className="flex items-center gap-3">
          <i className="fas fa-stethoscope text-amber-600"></i>
          <span className="font-bold text-amber-800 text-sm">Data Diagnostics</span>
          <span className="text-[11px] text-amber-700 bg-amber-100 border border-amber-200 rounded-full px-2 py-0.5">
            debug mode — remove ?debug=true to hide
          </span>
        </div>
        <div className="flex items-center gap-4 text-[12px]">
          <span className="text-green-700 font-semibold">{presentCount} present</span>
          <span className="text-red-700 font-semibold">{missingCount} missing</span>
          <i className={`fas fa-chevron-${expanded ? 'up' : 'down'} text-amber-600`}></i>
        </div>
      </div>

      {expanded && (
        <div className="p-5 space-y-5">
          <p className="text-[12px] text-[rgba(0,0,0,0.5)]">
            Each row shows whether the backend is actually returning that field in the dashboard API response.
            <strong className="text-[rgba(0,0,0,0.88)]"> Red = backend not sending it</strong> (backend/agent fix needed).
            <strong className="text-[rgba(0,0,0,0.88)]"> Green = data present</strong> (frontend should be rendering it).
          </p>

          {groups.map(group => {
            const groupFields = fields.filter(f => f.group === group);
            const groupMissing = groupFields.filter(f => !f.present).length;
            return (
              <div key={group}>
                <div className="flex items-center gap-2 mb-2">
                  <span className="text-[11px] font-bold text-[rgba(0,0,0,0.5)] uppercase tracking-wider">{group}</span>
                  {groupMissing > 0 && (
                    <span className="text-[10px] font-semibold text-red-600 bg-red-50 border border-red-200 rounded-full px-2 py-0.5">
                      {groupMissing} missing
                    </span>
                  )}
                </div>
                <div className="grid grid-cols-1 md:grid-cols-2 gap-1.5">
                  {groupFields.map(f => (
                    <div
                      key={f.label}
                      className={`flex items-start gap-2 p-2.5 rounded-lg border cursor-pointer hover:opacity-80 transition-opacity ${f.present
                        ? 'bg-[#dafbe1] border-[rgba(74,194,107,0.4)]'
                        : 'bg-[#ffebe9] border-[rgba(255,129,130,0.4)]'
                      }`}
                      onClick={() => setRawField(rawField === f.label ? null : f.label)}
                      title="Click to inspect raw value"
                    >
                      <i className={`fas ${f.present ? 'fa-check-circle text-[#1a7f37]' : 'fa-times-circle text-[#cf222e]'} text-sm flex-shrink-0 mt-0.5`}></i>
                      <div className="flex-1 min-w-0">
                        <div className="text-[11px] font-mono font-semibold text-[rgba(0,0,0,0.88)]">{f.label}</div>
                        {f.count !== undefined && (
                          <div className="text-[10px] text-[rgba(0,0,0,0.5)] mt-0.5">
                            {typeof f.count === 'number' ? f.count : f.count}
                          </div>
                        )}
                        {rawField === f.label && (
                          <pre className="text-[10px] text-[rgba(0,0,0,0.88)] mt-2 bg-white/80 rounded p-2 overflow-auto max-h-32 border border-[rgba(0,0,0,0.12)] whitespace-pre-wrap break-all">
                            {getRawValue(f.label)}
                          </pre>
                        )}
                      </div>
                    </div>
                  ))}
                </div>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
};

export default EksDiagnosticsPanel;
