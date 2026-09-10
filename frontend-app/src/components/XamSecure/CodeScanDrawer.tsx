import React, { useEffect, useState } from 'react';
import { Icon } from '@iconify/react';
import type { CodeScan, CodeScanFindings, CodeCve } from '../../types/xamsecure';
import SeverityChip from './SeverityChip';
import DetailPanel, { PanelBlock, PanelRow, type PanelSection } from './DetailPanel';
import { packageMark, osMark, ResourceIcon } from '../../utils/resourceBranding';

interface Props {
  scan: CodeScan;
  load: (fingerprint: string) => Promise<CodeScanFindings | null>;
  onClose: () => void;
}

/**
 * One CI scan in full.
 *
 * Ordered by what a reviewer decides on. "Introduced" comes first because it is the only bucket
 * that attributes a CVE to this change; "unchanged" is pre-existing debt that this build did not
 * make worse, and burying it keeps the two from being read as one number.
 */
const CodeScanDrawer: React.FC<Props> = ({ scan, load, onClose }) => {
  const [findings, setFindings] = useState<CodeScanFindings | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    load(scan.fingerprint!)
      .then((f) => { if (!cancelled) setFindings(f); })
      .catch((err) => {
        console.error('[XamSecure] code scan findings failed:', err);
        if (!cancelled) setFindings(null);
      })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [scan.fingerprint, load]);

  const counts = findings?.counts;

  const cveList = (rows: CodeCve[], emptyNote: string) => (
    rows.length === 0
      ? <p className="text-xs text-gray-500">{emptyNote}</p>
      : (
        <div className="space-y-2">
          {rows.map((c) => (
            <div key={`${c.cveId}-${c.packageName}`}
                 className="border border-gray-200 rounded-lg p-3">
              <div className="flex items-start justify-between gap-3">
                <div className="min-w-0">
                  <span className="flex items-center gap-2">
                    <SeverityChip severity={c.severity} />
                    <span className="font-mono text-[11px] font-semibold text-gray-800">
                      {c.cveId}
                    </span>
                    {c.score && <span className="text-[10px] text-gray-500">CVSS {c.score}</span>}
                    {c.exploitable && (
                      <span title="Known exploitable">
                        <Icon icon="mdi:bug" width={12} className="text-red-600" />
                      </span>
                    )}
                  </span>
                  {c.name && <p className="mt-1 text-xs font-medium text-gray-700">{c.name}</p>}
                </div>
                {/* The fix version is the actionable field, and it is one our runtime
                    vulnerability rows do not carry. */}
                {c.fixAvailable && c.fixedInVersion && (
                  <span className="flex-shrink-0 rounded-md bg-emerald-50 px-2 py-1 text-[10px] font-semibold text-emerald-700">
                    Fix: {c.fixedInVersion}
                  </span>
                )}
              </div>
              {c.packageName && (
                <p className="mt-1.5 flex items-center gap-1.5 text-[11px] text-gray-500">
                  <ResourceIcon mark={packageMark(c.packageName)} size={12} />
                  <span className="font-mono">{c.packageName}</span>
                  {c.packageVersion && <span className="font-mono">{c.packageVersion}</span>}
                </p>
              )}
              {c.description && (
                <p className="mt-1.5 text-[11px] text-gray-600 leading-relaxed">{c.description}</p>
              )}
            </div>
          ))}
        </div>
      )
  );

  const total = counts
    ? counts.introduced + counts.highImpact + counts.noChange + counts.resolved
    : 0;

  const sections: PanelSection[] = [
    {
      id: 'overview',
      label: 'Scan overview',
      icon: 'mdi:text-box-outline',
      group: 'Scan',
      render: () => (
        <>
          <PanelBlock>
            {/* The same sentence Upwind opens with, from fields we hold. A reviewer wants the
                shape of the change before a table of CVEs. */}
            <p className="text-xs leading-relaxed text-gray-700">
              {scan.initiator ? <strong className="font-semibold">{scan.initiator}</strong> : 'Someone'}
              {' pushed '}
              {scan.commitSha ? (
                <span className="font-mono text-[11px]">{scan.commitSha.slice(0, 7)}</span>
              ) : 'a commit'}
              {scan.branch ? <> to <strong className="font-semibold">{scan.branch}</strong></> : null}
              {scan.pullRequestUrl ? (
                <>
                  {' in '}
                  <a href={scan.pullRequestUrl} target="_blank" rel="noreferrer noopener"
                     className="text-gray-800 underline decoration-dotted">
                    {scan.pullRequestId ? `PR #${scan.pullRequestId}` : 'a pull request'}
                  </a>
                </>
              ) : null}
              {'. '}
              {counts ? (
                <>
                  Compared against the default branch, reporting{' '}
                  <strong className="font-semibold">{total}</strong>{' '}
                  {total === 1 ? 'CVE' : 'CVEs'} —{' '}
                  <strong className="font-semibold">{counts.introduced}</strong> introduced by this
                  build and <strong className="font-semibold">{counts.noChange}</strong> already
                  present.
                </>
              ) : 'No comparison reported for this scan.'}
            </p>
          </PanelBlock>

          {/* Upwind's "Shift left graph": where this scan sits in the pipeline. Their terminal
              node is a deploy recommendation, which the API does not carry — not on the scan
              event, not on the findings payload. Ours ends on the measured outcome instead of a
              verdict we would have to invent. */}
          <PanelBlock title="Shift left path">
            <div className="flex items-center gap-1 overflow-x-auto py-2">
              <GraphNode icon="mdi:source-repository" label={scan.repository ?? '—'}
                         sub="Repository" tone="blue" />
              <GraphEdge />
              <GraphNode icon="mdi:source-branch" label={scan.branch ?? '—'}
                         sub="Branch" tone="blue" />
              <GraphEdge />
              <GraphNode icon="mdi:source-commit"
                         label={scan.commitSha ? scan.commitSha.slice(0, 7) : '—'}
                         sub="Commit" tone="blue" mono />
              <GraphEdge />
              {counts && counts.introduced > 0 ? (
                <GraphNode icon="mdi:alert-circle-outline"
                           label={`${counts.introduced} introduced`}
                           sub="This build" tone="red" />
              ) : (
                <GraphNode icon="mdi:check-circle-outline" label="Nothing introduced"
                           sub="This build" tone="green" />
              )}
            </div>
          </PanelBlock>

          <PanelBlock title="Comparison and diff">
            <PanelRow label="Compared against" value="Default branch" />
            <PanelRow label="New CVEs" value={counts ? (
              counts.introduced > 0
                ? <span className="font-semibold text-red-600">{counts.introduced}</span>
                : <span className="text-gray-600">None</span>
            ) : null} />
            <PanelRow label="Resolved CVEs" value={counts ? (
              counts.resolved > 0
                ? <span className="font-semibold text-emerald-600">{counts.resolved}</span>
                : <span className="text-gray-600">None</span>
            ) : null} />
            <PanelRow label="Pre-existing" value={counts?.noChange} />
            <PanelRow label="High impact" value={counts?.highImpact} />
          </PanelBlock>
        </>
      ),
    },
    {
      id: 'introduced',
      label: 'Introduced by this build',
      icon: 'mdi:alert-plus-outline',
      group: 'Findings',
      count: counts?.introduced,
      render: () => (
        <PanelBlock>
          {cveList(findings?.cves.introduced ?? [],
            'This build introduced no new CVEs relative to its baseline.')}
        </PanelBlock>
      ),
    },
    {
      id: 'highImpact',
      label: 'High impact',
      icon: 'mdi:fire',
      group: 'Findings',
      count: counts?.highImpact,
      render: () => (
        <PanelBlock>
          {cveList(findings?.cves.highImpact ?? [], 'None flagged as high impact.')}
        </PanelBlock>
      ),
    },
    {
      id: 'noChange',
      label: 'Pre-existing',
      icon: 'mdi:equal',
      group: 'Findings',
      count: counts?.noChange,
      render: () => (
        <PanelBlock>
          <p className="mb-3 text-[11px] text-gray-500">
            Present before this build and unchanged by it. Worth fixing, but not attributable to
            this commit.
          </p>
          {cveList(findings?.cves.noChange ?? [], 'No pre-existing CVEs on this image.')}
        </PanelBlock>
      ),
    },
    {
      id: 'resolved',
      label: 'Resolved',
      icon: 'mdi:check-circle-outline',
      group: 'Findings',
      count: counts?.resolved,
      render: () => (
        <PanelBlock>
          {cveList(findings?.cves.resolved ?? [], 'This build resolved no CVEs.')}
        </PanelBlock>
      ),
    },
    {
      id: 'build',
      label: 'Build',
      icon: 'mdi:source-commit',
      group: 'Scan',
      render: () => (
        <PanelBlock>
          <PanelRow label="Repository" value={scan.repository} />
          <PanelRow label="Branch" value={scan.branch} />
          <PanelRow label="Main branch" value={scan.mainBranchName} />
          <PanelRow label="Commit" value={scan.commitSha} mono />
          <PanelRow label="Initiator" value={scan.initiator} />
          <PanelRow label="Source" value={scan.source} />
          <PanelRow label="Scan type" value={scan.type} />
          <PanelRow label="Image" value={findings?.imageName ?? scan.image} />
          <PanelRow label="Image OS" value={(findings?.imageOs ?? scan.imageOs) ? (
            <span className="flex items-center gap-1.5">
              <ResourceIcon mark={osMark(findings?.imageOs ?? scan.imageOs)} />
              <span>{osMark(findings?.imageOs ?? scan.imageOs).label}</span>
            </span>
          ) : null} />
          <PanelRow label="Pull request" value={scan.pullRequestUrl ? (
            <a href={scan.pullRequestUrl} target="_blank" rel="noreferrer noopener"
               className="inline-flex items-center gap-1 text-gray-700 underline decoration-dotted">
              {scan.pullRequestId ? `#${scan.pullRequestId}` : scan.pullRequestUrl}
              <Icon icon="mdi:arrow-top-right" width={11} />
            </a>
          ) : null} />
          <PanelRow label="Scanned" value={
            scan.createTime ? new Date(scan.createTime).toLocaleString() : null} />
          <PanelRow label="Fingerprint" value={scan.fingerprint} mono />
        </PanelBlock>
      ),
    },
  ];

  const subtitle = (
    <>
      <span className="font-mono text-[10px] text-gray-500">
        {scan.commitSha ? scan.commitSha.slice(0, 7) : '—'}
      </span>
      <span className="text-[10px] text-gray-500">{scan.branch}</span>
      {counts && counts.introduced > 0 && (
        <span className="rounded-full bg-red-100 px-2 py-0.5 text-[10px] font-bold text-red-700">
          {counts.introduced} introduced
        </span>
      )}
      {counts && counts.introduced === 0 && !loading && (
        <span className="rounded-full bg-emerald-100 px-2 py-0.5 text-[10px] font-bold text-emerald-700">
          Nothing introduced
        </span>
      )}
    </>
  );

  return (
    <DetailPanel
      title={scan.repository ?? 'CI scan'}
      subtitle={subtitle}
      onClose={onClose}
      isLoading={loading}
      sections={sections}
      emptyState={!loading && !findings ? (
        <p className="text-xs text-gray-500 max-w-sm text-center">
          No findings are available for this scan. This can mean the scan has expired rather
          than that it was clean.
        </p>
      ) : undefined}
    />
  );
};

const NODE_TONE: Record<string, string> = {
  blue: 'border-blue-200 bg-blue-50 text-blue-700',
  red: 'border-red-200 bg-red-50 text-red-700',
  green: 'border-emerald-200 bg-emerald-50 text-emerald-700',
};

/** One stage of the shift-left path. */
const GraphNode: React.FC<{
  icon: string; label: string; sub: string; tone: 'blue' | 'red' | 'green'; mono?: boolean;
}> = ({ icon, label, sub, tone, mono }) => (
  <div className="flex flex-col items-center gap-1 flex-shrink-0">
    <div className={`flex h-10 w-10 items-center justify-center rounded-full border ${NODE_TONE[tone]}`}>
      <Icon icon={icon} width={18} />
    </div>
    <span className={`max-w-[110px] truncate text-[10px] font-semibold text-gray-800 ${
      mono ? 'font-mono' : ''}`} title={label}>
      {label}
    </span>
    <span className="text-[9px] uppercase tracking-wide text-gray-400">{sub}</span>
  </div>
);

const GraphEdge: React.FC = () => (
  <div className="mb-5 h-px w-6 flex-shrink-0 bg-gray-300 sm:w-10" aria-hidden="true" />
);

export default CodeScanDrawer;
