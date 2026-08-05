import React from 'react';
import { Icon } from '@iconify/react';
import type { CveResourceFinding, CveGroupRow } from '../../types/xamsecure';
import SeverityChip from './SeverityChip';
import DetailPanel, { PanelBlock, PanelRow, type PanelSection } from './DetailPanel';

interface Props {
  cve: CveGroupRow;
  /** The affected-resource row this panel was opened from. */
  finding: CveResourceFinding;
  onClose: () => void;
  onOpenResource?: (resourceId: string) => void;
}

const fmt = (iso: string | null | undefined) => (iso ? new Date(iso).toLocaleString() : '—');

/**
 * Vulnerability-finding panel, following Upwind's grouped nav for the same entity:
 *   Finding  -> Finding details, Runtime exploitability, Fix
 *   Image    -> Image overview
 *   Resource -> Overview
 *
 * Runtime exploitability is the section worth having. It is built from the fields the sync
 * used to discard (package.in_use, internet_exposure.ingress.*, exploitable_functions_in_use),
 * which is why this panel could not have existed before those were persisted.
 *
 * Sections Upwind has and we cannot source — Finding management, Pipeline, Layers,
 * Continuous integration/delivery — are listed as unavailable with a reason rather than
 * hidden, so the gap is visible instead of implied away.
 */
const VulnFindingDrawer: React.FC<Props> = ({ cve, finding, onClose, onOpenResource }) => {
  const reach = finding.functionsInUse
    ? { label: 'Vulnerable functions executing', tone: 'bg-red-500 text-white',
        detail: 'Upwind observed the vulnerable code paths themselves running.' }
    : finding.activeCommunication
      ? { label: 'Active internet traffic', tone: 'bg-red-100 text-red-700',
          detail: 'Live inbound traffic observed on the hosting resource.' }
      : finding.internetExposed
        ? { label: 'Internet exposed', tone: 'bg-orange-100 text-orange-700',
            detail: 'Reachable from the internet, no live traffic observed yet.' }
        : { label: 'Internal only', tone: 'bg-gray-100 text-gray-600',
            detail: 'No internet exposure observed for the hosting resource.' };

  const sections: PanelSection[] = [
    {
      id: 'details', label: 'Finding details', icon: 'mdi:format-list-bulleted', group: 'Finding',
      render: () => (
        <>
          <PanelBlock>
            <PanelRow label="CVE" value={<span className="font-mono">{cve.cveId}</span>} />
            <PanelRow label="Name" value={cve.name} />
            <PanelRow label="Severity" value={<SeverityChip severity={cve.severity} />} />
            <PanelRow label="CVSS score" value={finding.cvssScore ?? cve.cvssScore} />
            <PanelRow label="EPSS" value={cve.epssScore} />
            <PanelRow label="Known exploit" value={
              cve.exploitable
                ? <span className="font-semibold text-red-600">Yes</span>
                : <span className="text-gray-500">Not recorded</span>} />
            <PanelRow label="First seen" value={fmt(finding.firstSeenTime)} />
            <PanelRow label="Last scan" value={fmt(finding.lastScanTime)} />
          </PanelBlock>
        </>
      ),
    },
    {
      id: 'runtime', label: 'Runtime exploitability', icon: 'mdi:play-speed', group: 'Finding',
      render: () => (
        <>
          <PanelBlock>
            <div className="mb-3 rounded-lg border border-gray-200 p-3">
              <span className={`inline-block rounded px-2 py-0.5 text-[10px] font-bold ${reach.tone}`}>
                {reach.label}
              </span>
              <p className="mt-2 text-[11px] text-gray-600">{reach.detail}</p>
            </div>
            <PanelRow label="Package loaded at runtime" value={<YesNo v={finding.inUse} />} />
            <PanelRow label="Internet exposed" value={<YesNo v={finding.internetExposed} />} />
            <PanelRow label="Active communication" value={<YesNo v={finding.activeCommunication} />} />
            <PanelRow label="Vulnerable functions executing" value={<YesNo v={finding.functionsInUse} />} />
          </PanelBlock>
          <p className="flex items-start gap-1.5 text-[10px] text-gray-400">
            <Icon icon="mdi:information-outline" width={12} className="mt-0.5 flex-shrink-0" />
            These come from Upwind's sensor. A vulnerability that is installed but never loaded,
            or on a resource nothing can reach, is a far lower priority than the counts alone suggest.
          </p>
        </>
      ),
    },
    {
      id: 'fix', label: 'Fix', icon: 'mdi:wrench-outline', group: 'Finding',
      render: () => (
        <PanelBlock>
          {finding.fixAvailable ? (
            <>
              <PanelRow label="Fix available" value={
                <span className="font-semibold text-emerald-600">Yes</span>} />
              <PanelRow label="Fixed in version" value={
                finding.fixedInVersion
                  ? <span className="font-mono">{finding.fixedInVersion}</span> : null} />
              <PanelRow label="Current version" value={
                finding.packageVersion
                  ? <span className="font-mono">{finding.packageVersion}</span> : null} />
              <PanelRow label="Package" value={finding.packageName} />
            </>
          ) : (
            <p className="text-xs text-gray-500">
              No fixed version published for this package yet. Mitigation has to come from
              reducing exposure — see Runtime exploitability.
            </p>
          )}
        </PanelBlock>
      ),
    },
    {
      id: 'finding-mgmt', label: 'Finding management', icon: 'mdi:playlist-edit', group: 'Finding',
      available: false,
      unavailableReason: 'Snooze, ticketing and status overrides are not exposed by the Upwind API.',
      render: () => null,
    },
    {
      id: 'pipeline', label: 'Pipeline', icon: 'mdi:source-branch', group: 'Finding',
      available: false,
      unavailableReason: 'Requires Upwind\'s Code/CI integration, which has no API endpoint.',
      render: () => null,
    },
    {
      id: 'image', label: 'Image overview', icon: 'mdi:layers-outline', group: 'Image',
      render: () => (
        <PanelBlock>
          {finding.image || finding.imageTag ? (
            <>
              <PanelRow label="Image" value={finding.image} />
              <PanelRow label="Tag" value={
                finding.imageTag ? <span className="font-mono">{finding.imageTag}</span> : null} />
              <PanelRow label="Package" value={finding.packageName} />
              <PanelRow label="Package version" value={
                finding.packageVersion
                  ? <span className="font-mono">{finding.packageVersion}</span> : null} />
              <PanelRow label="Affected packages" value={finding.packageCount} />
            </>
          ) : (
            <p className="text-xs text-gray-500">
              This finding is not attached to a container image — it was found directly on the host.
            </p>
          )}
        </PanelBlock>
      ),
    },
    {
      id: 'layers', label: 'Layers', icon: 'mdi:layers-triple-outline', group: 'Image',
      available: false,
      unavailableReason:
        'package.introduced_by_layers is in the API payload but not yet persisted, so layer '
        + 'attribution cannot be shown.',
      render: () => null,
    },
    {
      id: 'resource', label: 'Overview', icon: 'mdi:server', group: 'Resource',
      render: () => (
        <>
          <PanelBlock action={onOpenResource && finding.resourceId ? (
            <button type="button" onClick={() => onOpenResource(finding.resourceId!)}
                    className="inline-flex items-center gap-1 rounded-lg border border-gray-200 px-2.5 py-1
                               text-[10px] font-bold text-gray-700 hover:border-gray-400
                               focus:outline-none focus-visible:ring-2 focus-visible:ring-gray-900">
              Open resource <Icon icon="mdi:arrow-top-right" width={11} />
            </button>
          ) : undefined}>
            <PanelRow label="Name" value={finding.resourceName} />
            <PanelRow label="Type" value={finding.resourceType} />
            <PanelRow label="Region" value={finding.region} />
            <PanelRow label="Cloud" value={finding.cloudProvider} />
            {finding.namespace && <PanelRow label="Namespace" value={finding.namespace} />}
            {finding.clusterName && <PanelRow label="Cluster" value={finding.clusterName} />}
            {finding.resourceId && (
              <PanelRow label="Resource id" value={finding.resourceId} mono />
            )}
          </PanelBlock>
        </>
      ),
    },
  ];

  return (
    <DetailPanel
      title={
        <span className="flex flex-wrap items-baseline gap-1.5">
          <span className="font-mono">{cve.cveId}</span>
          <span className="text-gray-400">|</span>
          <span>{cve.name}</span>
          {finding.resourceName && (
            <>
              <span className="text-gray-400">/</span>
              <span className="text-gray-600">{finding.resourceName}</span>
            </>
          )}
        </span>
      }
      subtitle={
        <>
          <SeverityChip severity={cve.severity} />
          <span className={`rounded px-2 py-0.5 text-[10px] font-bold ${reach.tone}`}>{reach.label}</span>
          {finding.fixAvailable && (
            <span className="text-[10px] font-semibold text-emerald-600">
              Fix {finding.fixedInVersion ?? 'available'}
            </span>
          )}
        </>
      }
      sections={sections}
      onClose={onClose}
    />
  );
};

const YesNo: React.FC<{ v: boolean | null | undefined }> = ({ v }) =>
  v == null ? <span className="text-gray-400">Not reported</span>
    : v ? <span className="font-semibold text-red-600">Yes</span>
      : <span className="text-gray-600">No</span>;

export default VulnFindingDrawer;
