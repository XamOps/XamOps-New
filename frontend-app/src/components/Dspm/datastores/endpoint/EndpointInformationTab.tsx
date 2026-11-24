import React from 'react';
import { Icon } from '@iconify/react';
import type { EndpointOverview, EndpointSummary } from '../../../../types/dspmDrawers';
import type { LazyResource } from '../../../../hooks/useDspmDatastoreDrawer';
import { DspmObjectIcon } from '../../DspmIcons';
import { abbreviate } from '../datastoreFormat';
import { agentStatusVisual } from '../datastoreVisuals';
import { ResourceView } from '../drawer/TabParts';
import { SaasCard } from '../saas/SaasParts';
import { CategoryChip } from '../ui/Chips';
import { sentenceCase, utilizationText } from './endpointFormat';

/** A headline figure: its label, then the value in large type. */
const Figure: React.FC<{ label: string; value: string }> = ({ label, value }) => (
  <dl>
    <dt className="text-[10px] text-[rgba(0,0,0,0.45)]">{label}</dt>
    <dd className="mt-1 text-2xl font-bold tabular-nums text-[rgba(0,0,0,0.88)]">{value}</dd>
  </dl>
);

/** A name and its count on one line. */
const CountRow: React.FC<{ name: string; count: number }> = ({ name, count }) => (
  <li className="flex items-center justify-between gap-3 text-xs">
    <span className="min-w-0 truncate text-[rgba(0,0,0,0.7)]" title={name}>{name}</span>
    <span className="flex-shrink-0 font-semibold tabular-nums text-[rgba(0,0,0,0.8)]" title={count.toLocaleString('en-US')}>
      {abbreviate(count)}
    </span>
  </li>
);

/** File Summary, Sensitives and File Categories: everything the summary request answers. */
const SummaryCards: React.FC<{ summary: EndpointSummary }> = ({ summary }) => (
  <>
    <SaasCard title="File Summary">
      <Figure label="Total Files" value={summary.totalFiles === null ? '-' : abbreviate(summary.totalFiles)} />
      {summary.extensions.length > 0 && (
        <div className="mt-3 flex flex-wrap gap-1.5">
          {summary.extensions.map(extension => (
            <span
              key={extension.extension}
              data-extension={extension.extension}
              className="inline-flex items-center gap-1.5 rounded-lg border border-[rgba(0,0,0,0.1)] bg-white px-2 py-1 text-[11px] text-[rgba(0,0,0,0.7)]"
            >
              <DspmObjectIcon objectType={null} extension={extension.extension} />
              {extension.label ?? extension.extension}{' '}
              <span className="font-semibold tabular-nums">{extension.count.toLocaleString('en-US')}</span>
            </span>
          ))}
        </div>
      )}
    </SaasCard>

    <SaasCard title="Sensitives">
      <Figure label="Total Sensitive Records" value={summary.totalSensitives === null ? '-' : abbreviate(summary.totalSensitives)} />
      {summary.labelGroups.length === 0 ? (
        <p className="mt-2 text-xs text-[rgba(0,0,0,0.45)]">No sensitive records found</p>
      ) : (
        summary.labelGroups.map((group, index) => (
          <div key={`${index}:${group.label ?? ''}`} className="mt-3">
            <CategoryChip label={group.label} />
            <ul className="mt-2 space-y-1.5">
              {group.entities.map((entity, row) => (
                <CountRow key={`${row}:${entity.name ?? ''}`} name={entity.name ? sentenceCase(entity.name) : '-'} count={entity.count} />
              ))}
            </ul>
          </div>
        ))
      )}
    </SaasCard>

    {summary.fileCategories.length > 0 && (
      <SaasCard title="File Categories">
        {summary.fileCategories.map((group, index) => (
          <div key={`${index}:${group.label ?? ''}`} className={index === 0 ? '' : 'mt-3'}>
            {/* Document categories are another vocabulary than the sensitive ones: neutral grey (A23). */}
            <CategoryChip label={group.label} neutral />
            <ul className="mt-2 space-y-1.5">
              {group.documentClasses.map((documentClass, row) => (
                <CountRow key={`${row}:${documentClass.name ?? ''}`} name={documentClass.name ?? '-'} count={documentClass.count} />
              ))}
            </ul>
          </div>
        ))}
      </SaasCard>
    )}
  </>
);

const Property: React.FC<{ label: string; children: React.ReactNode }> = ({ label, children }) => (
  <div className="flex items-start justify-between gap-3">
    <dt className="flex-shrink-0 text-[11px] text-[rgba(0,0,0,0.45)]">{label}</dt>
    <dd className="min-w-0 break-all text-right text-xs font-semibold text-[rgba(0,0,0,0.8)]">{children}</dd>
  </div>
);

const text = (value: string | null) => value ?? '-';

/** Properties, in the console's order; a missing value reads "-". */
const PropertiesCard: React.FC<{ overview: EndpointOverview }> = ({ overview }) => {
  const status = agentStatusVisual(overview.agentOnline ? 'ONLINE' : 'OFFLINE');
  return (
    <SaasCard title="Properties">
      <dl className="space-y-2.5">
        <Property label="Severity">{text(overview.severityLabel)}</Property>
        <Property label="Agent Status">
          {overview.agentStatusLabel ? (
            <span className="inline-flex items-center gap-1">
              <Icon icon={status.icon} width={14} className={status.cls} />
              {overview.agentStatusLabel}
            </span>
          ) : '-'}
        </Property>
        <Property label="Users">{overview.users.length === 0 ? '-' : overview.users.join(', ')}</Property>
        <Property label="Hostname">{text(overview.hostname)}</Property>
        <Property label="Visible IP">{text(overview.visibleIp)}</Property>
        <Property label="MAC Address">{text(overview.macAddress)}</Property>
        <Property label="Device Type">{text(overview.deviceTypeLabel)}</Property>
        <Property label="OS Version">{text(overview.osVersion)}</Property>
        <Property label="Architecture">{text(overview.architecture)}</Property>
        <Property label="Memory">{text(overview.memory)}</Property>
        <Property label="CPU Utilization">{utilizationText(overview.cpuUsage, overview.agentOnline)}</Property>
        <Property label="Memory Utilization">{utilizationText(overview.memoryUsage, overview.agentOnline)}</Property>
        <Property label="Agent Version">{text(overview.agentVersion)}</Property>
      </dl>
    </SaasCard>
  );
};

interface Props {
  /** Loaded on open; it also feeds the strip. */
  overview: LazyResource<EndpointOverview>;
  /** Loaded on the first visit to this tab. */
  summary: LazyResource<EndpointSummary>;
}

/**
 * The Endpoint drawer's Information tab (FR-EP-9), in two columns: File Summary, Sensitives and File
 * Categories follow the summary request, Properties follows the overview request. Each side shows its
 * own "Loading…" and its own error with Retry, so one failing request never hides the other side.
 */
const EndpointInformationTab: React.FC<Props> = ({ overview, summary }) => (
  <div className="grid grid-cols-1 items-start gap-4 md:grid-cols-[1fr_minmax(260px,340px)]">
    <div className="min-w-0 space-y-4">
      <ResourceView resource={summary}>{data => <SummaryCards summary={data} />}</ResourceView>
    </div>
    <div className="min-w-0 space-y-4">
      <ResourceView resource={overview}>{data => <PropertiesCard overview={data} />}</ResourceView>
    </div>
  </div>
);

export default EndpointInformationTab;
