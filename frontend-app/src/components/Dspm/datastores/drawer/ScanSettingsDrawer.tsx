import React from 'react';
import type { DatastoreRow } from '../../../../types/dspmDatastores';
import type { DatastoreDatabases, DatastoreScanSettings } from '../../../../types/dspmScans';
import type { datastoreScanApi } from '../../../../hooks/useDspmScans';
import { useLazyResource, type LazyResource } from '../../../../hooks/useDspmDatastoreDrawer';
import { DetailHeader, SettingsCard } from '../../scans/ScanParts';
import {
  ConsoleOnlyButton, ConsoleOnlyCheckbox, ConsoleOnlyField, ConsoleOnlyRadio,
} from '../ui/ConsoleOnly';
import { EmptyState } from '../ui/EmptyState';
import { NestedDrawerShell } from './DrawerShell';
import { ErrorNote, LoadingNote, ResourceView } from './TabParts';
import { parameterRows, scanSettingsSections, showDatabases, type ScanSettingsSection } from './scanSettingsModel';

type ScanApi = ReturnType<typeof datastoreScanApi>;

interface Props {
  row: Pick<DatastoreRow, 'name' | 'category' | 'source'>;
  api: ScanApi;
  /** The Actions button, focused again when this layer closes. */
  returnFocus: HTMLElement | null;
  /** Must be stable (`useCallback`). */
  onClose: () => void;
}

/** One field per path, numbered when there are several; one empty field when there is none. */
const PathFields: React.FC<{ label: string; paths: string[] }> = ({ label, paths }) => {
  if (paths.length === 0) return <ConsoleOnlyField label={label} value={null} />;
  return (
    <>
      {paths.map((path, i) => (
        <ConsoleOnlyField key={`${i}-${path}`} label={paths.length > 1 ? `${label} ${i + 1}` : label} value={path} />
      ))}
    </>
  );
};

const DatabasesSection: React.FC<{ settings: DatastoreScanSettings; databases: LazyResource<DatastoreDatabases> }> = ({
  settings, databases,
}) => (
  <SettingsCard title="Databases To Scan">
    <div role="radiogroup" aria-label="Databases To Scan" className="flex flex-wrap gap-4">
      <ConsoleOnlyRadio label="All databases" checked={settings.allDatabases} />
      <ConsoleOnlyRadio label="Selected" checked={!settings.allDatabases} />
    </div>
    {!settings.allDatabases && settings.selectedDatabases.length > 0 && (
      <p className="text-xs text-[rgba(0,0,0,0.7)]">
        <span className="font-semibold">Selected: </span>{settings.selectedDatabases.join(', ')}
      </p>
    )}
    {databases.error ? <ErrorNote error={databases.error} onRetry={databases.reload} /> : databases.data === null ? <LoadingNote /> : (
      <ul aria-label="Databases" className="space-y-1.5">
        {databases.data.databases.map(name => (
          <li key={name} className="flex items-center gap-2 text-xs text-[rgba(0,0,0,0.75)]">
            <ConsoleOnlyCheckbox
              label={name}
              checked={settings.allDatabases || settings.selectedDatabases.includes(name)}
              placement="right"
            />
            {name}
          </li>
        ))}
      </ul>
    )}
  </SettingsCard>
);

const SECTION: Record<ScanSettingsSection, (settings: DatastoreScanSettings, databases: LazyResource<DatastoreDatabases>) => React.ReactNode> = {
  parameters: settings => (
    <SettingsCard title="Configuration Parameters">
      {parameterRows(settings).map(parameter => (
        <div key={parameter.key} className="space-y-1">
          <ConsoleOnlyField label={parameter.label} value={parameter.value} />
          <p className="text-[11px] text-[rgba(0,0,0,0.5)]">{parameter.description}</p>
        </div>
      ))}
    </SettingsCard>
  ),
  conditions: settings => (
    <SettingsCard title="Define Scan Conditions">
      <PathFields label="Inclusion Prefix Path" paths={settings.inclusionPaths} />
      <PathFields label="Exclusion Pattern" paths={settings.exclusionPaths} />
    </SettingsCard>
  ),
  databases: (settings, databases) => <DatabasesSection settings={settings} databases={databases} />,
  assist: settings => (
    <SettingsCard title="Assist Details">
      <div className="space-y-1.5">
        <p className="text-[11px] font-semibold text-[rgba(0,0,0,0.6)]">
          Does the Matters Dataplane have access to this database server?
        </p>
        <div role="radiogroup" aria-label="Does the Matters Dataplane have access to this database server?" className="flex gap-4">
          <ConsoleOnlyRadio label="Yes" checked={settings.accessible === true} />
          <ConsoleOnlyRadio label="No" checked={settings.accessible === false} />
        </div>
      </div>
      <ConsoleOnlyField label="Export Assist" value={settings.exportAssistName ?? '-'} />
      <ConsoleOnlyField label="Discovery Assist" value={settings.discoveryAssistName ?? '-'} />
    </SettingsCard>
  ),
  connection: settings => (
    <SettingsCard title="Connection Details">
      {settings.host !== null && <ConsoleOnlyField label="Host" value={settings.host} />}
      {settings.port !== null && <ConsoleOnlyField label="Port" value={String(settings.port)} />}
      {settings.serviceName !== null && <ConsoleOnlyField label="Service Name" value={settings.serviceName} />}
    </SettingsCard>
  ),
  credentials: settings => (
    <SettingsCard title="Credentials">
      {settings.credentialsSaved ? (
        <div className="flex items-center justify-between gap-3 rounded-lg border border-[rgba(0,0,0,0.08)] bg-[rgba(0,0,0,0.02)] p-3">
          <p className="text-xs text-[rgba(0,0,0,0.6)]">Your credentials are already saved with us</p>
          <ConsoleOnlyButton variant="secondary" placement="left">Update Credentials</ConsoleOnlyButton>
        </div>
      ) : (
        <>
          <ConsoleOnlyField label="Username" value={null} />
          <ConsoleOnlyField label="Password" value={null} />
        </>
      )}
    </SettingsCard>
  ),
  cost: settings => (
    <SettingsCard title="Scan Cost Estimation">
      <div className="flex items-center justify-between text-xs">
        <span className="text-[rgba(0,0,0,0.55)]">Total job run duration (hrs)</span>
        <span className="font-semibold text-[rgba(0,0,0,0.85)]">
          {(settings.parameters?.timeOutHours ?? 0).toLocaleString('en-US')}
        </span>
      </div>
    </SettingsCard>
  ),
};

/**
 * The "Datastore Scan Configuration" layer opened from Actions → Scan Settings (design 6.9). Read only:
 * every input is console-only and shows the saved value. A section the response does not fill is not
 * drawn; no connection URI, username or password value ever reaches this view (the backend's whitelist).
 */
const ScanSettingsDrawer: React.FC<Props> = ({ row, api, returnFocus, onClose }) => {
  const settings = useLazyResource(api.scanSettings, true);
  const databases = useLazyResource(api.databases, showDatabases(row));
  return (
    <NestedDrawerShell label="Datastore Scan Configuration" returnFocus={returnFocus} onClose={onClose}>
      {({ closeButton, close }) => (
        <>
          <DetailHeader title="Datastore Scan Configuration" subtitle={row.name} closeButton={closeButton} onClose={close} />
          <div className="flex-1 space-y-4 overflow-y-auto px-5 py-4">
            <ResourceView resource={settings}>
              {data => {
                const sections = scanSettingsSections(row, data);
                if (sections.length === 0) return <EmptyState title="No scan settings available" />;
                return sections.map(section => (
                  <React.Fragment key={section}>{SECTION[section](data, databases)}</React.Fragment>
                ));
              }}
            </ResourceView>
          </div>
          <div className="flex justify-end gap-2 border-t border-[rgba(0,0,0,0.06)] px-5 py-3">
            <button
              type="button"
              onClick={close}
              className="rounded-lg border border-[rgba(0,0,0,0.12)] bg-white px-3 py-1.5 text-xs font-semibold text-[rgba(0,0,0,0.7)] transition-colors hover:bg-[rgba(0,0,0,0.04)]"
            >
              Close
            </button>
            <ConsoleOnlyButton variant="primary" placement="top">Save Configuration</ConsoleOnlyButton>
          </div>
        </>
      )}
    </NestedDrawerShell>
  );
};

export default ScanSettingsDrawer;
