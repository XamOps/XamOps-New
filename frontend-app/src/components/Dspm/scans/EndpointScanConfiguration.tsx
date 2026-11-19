import React from 'react';
import type { EndpointScanConfiguration as EndpointConfig } from '../../../types/dspmScans';
import { scanUrls } from '../../../hooks/useDspmScans';
import { useServerList } from '../../../hooks/useServerList';
import { ErrorNote, LoadingNote } from '../datastores/drawer/TabParts';
import { ConsoleOnlyCheckbox, ConsoleOnlyField, ConsoleOnlyRadio } from '../datastores/ui/ConsoleOnly';
import { EmptyState } from '../datastores/ui/EmptyState';
import { SettingsCard } from './ScanParts';

/** One greyed field per path, or the console's empty text. */
const PathFields: React.FC<{ paths: string[]; name: string; emptyText: string }> = ({ paths, name, emptyText }) => {
  if (paths.length === 0) return <p className="text-xs text-[rgba(0,0,0,0.45)]">{emptyText}</p>;
  return (
    <div className="grid gap-2 sm:grid-cols-2">
      {paths.map((path, i) => <ConsoleOnlyField key={`${i}:${path}`} label={`${name} ${i + 1}`} value={path} />)}
    </div>
  );
};

/** File Types: "<selected> of <all> selected" and a greyed checkbox per type, ticked when it is scanned. */
const FileTypes: React.FC<{ selected: string[]; all: string[] }> = ({ selected, all }) => {
  const chosen = new Set(selected);
  // Without the full list, the selected types are all there is to show.
  const types = all.length > 0 ? all : selected;
  const summary = all.length > 0
    ? `${all.filter(type => chosen.has(type)).length} of ${all.length} selected`
    : `${selected.length} selected`;
  return (
    <div className="space-y-3">
      <p className="text-xs font-semibold text-[rgba(0,0,0,0.65)]">{summary}</p>
      {types.length > 0 && (
        <ul className="grid grid-cols-2 gap-2 sm:grid-cols-4">
          {types.map(type => (
            <li key={type} className="flex items-center gap-2 text-xs text-[rgba(0,0,0,0.75)]">
              <ConsoleOnlyCheckbox label={type} checked={chosen.has(type)} />
              <span className="truncate" title={type}>{type}</span>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
};

/**
 * Scan Configurations › Endpoint (FR-SM-4; S12): the endpoint agents' scan settings as the console's
 * cards, every input greyed. The texts are the console's.
 */
const EndpointScanConfiguration: React.FC = () => {
  const config = useServerList<EndpointConfig>(scanUrls.endpointConfiguration());
  if (config.error) return <ErrorNote error={config.error} onRetry={config.reload} subject="item" />;
  const data = config.data;
  if (data === null) return <LoadingNote />;
  if (!data.configured) {
    return (
      <EmptyState
        title="No endpoint agents configured"
        body="Deploy the endpoint agent to configure how managed devices are scanned."
      />
    );
  }
  return (
    <div className="space-y-4">
      <SettingsCard title="Scan Location" description="Choose where the scan is executed.">
        <div role="radiogroup" aria-label="Scan Location" className="flex flex-wrap gap-4">
          <ConsoleOnlyRadio label="Cloud" checked={data.scanMode === 'cloud'} />
          <ConsoleOnlyRadio label="Local" checked={data.scanMode === 'local'} />
        </div>
      </SettingsCard>
      <SettingsCard title="Scan Reconciliation Timeframe" description="Re-scans files modified in the last 24 hours.">
        <div className="grid max-w-md grid-cols-2 gap-3">
          <ConsoleOnlyField label="Start time" value={data.reconciliationStart} />
          <ConsoleOnlyField label="End time" value={data.reconciliationEnd} />
        </div>
      </SettingsCard>
      <SettingsCard title="Include Paths" description="Folders to scan on each device.">
        <PathFields paths={data.includePaths} name="Include path" emptyText="No include paths" />
      </SettingsCard>
      <SettingsCard title="Exclude Paths" description="Folders to skip.">
        <PathFields paths={data.excludePaths} name="Exclude path" emptyText="Nothing excluded" />
      </SettingsCard>
      <SettingsCard title="File Types" description="Choose which file formats are scanned on endpoint devices.">
        <FileTypes selected={data.fileTypes} all={data.allFileTypes} />
      </SettingsCard>
    </div>
  );
};

export default EndpointScanConfiguration;
