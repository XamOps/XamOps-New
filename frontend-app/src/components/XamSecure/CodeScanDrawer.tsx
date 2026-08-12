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

  const sections: PanelSection[] = [
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
          {cveList(findings?.cves.highImpact ?? [], 'Upwind flagged none as high impact.')}
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
          Upwind returned no findings for this scan. Its route answers 200 for unknown
          fingerprints, so this may mean the scan has expired rather than that it was clean.
        </p>
      ) : undefined}
    />
  );
};

export default CodeScanDrawer;
