import React, { useId, useState } from 'react';
import { Icon } from '@iconify/react';
import type { SaasIntegration, SaasIntegrationList, SaasScanConfiguration as SaasConfig, SaasScanScope } from '../../../types/dspmScans';
import { scanUrls } from '../../../hooks/useDspmScans';
import { useServerList } from '../../../hooks/useServerList';
import { DspmProviderIcon } from '../DspmIcons';
import { formatListDate } from '../datastores/datastoreFormat';
import { driveIconKey } from '../datastores/datastoreVisuals';
import { DrawerTabList } from '../datastores/drawer/DrawerTabList';
import { ErrorNote, LoadingNote } from '../datastores/drawer/TabParts';
import { ConsoleOnlyField, ConsoleOnlyRadio, ConsoleOnlySwitch } from '../datastores/ui/ConsoleOnly';
import { EmptyState } from '../datastores/ui/EmptyState';
import { SAAS_PROVIDERS, SLACK_DRIVE, type SaasProviderDef } from './saasProviders';
import { SettingsCard } from './ScanParts';
import SaasUsersTable from './SaasUsersTable';
import SlackChannelsTable from './SlackChannelsTable';

/** The provider list: each app with its integrations, in the console's order, then first-seen order. */
function providerGroups(integrations: SaasIntegration[]): Array<{ provider: SaasProviderDef; integrations: SaasIntegration[] }> {
  return SAAS_PROVIDERS.map(provider => ({
    provider,
    integrations: integrations.filter(integration => integration.provider === provider.id),
  }));
}

const ProviderList: React.FC<{
  integrations: SaasIntegration[];
  selected: string | null;
  onSelect: (id: string) => void;
}> = ({ integrations, selected, onSelect }) => (
  <ul aria-label="SaaS apps" className="w-56 flex-shrink-0 space-y-1">
    {providerGroups(integrations).map(({ provider, integrations: connected }) => (
      connected.length === 0 ? (
        <li key={provider.id} className="flex items-center gap-2 rounded-lg px-3 py-2 opacity-60">
          <Icon icon={provider.icon} width={16} className="flex-shrink-0 text-[rgba(0,0,0,0.5)]" />
          <span className="min-w-0">
            <span className="block text-xs font-semibold text-[rgba(0,0,0,0.75)]">{provider.label}</span>
            <span className="block text-[11px] text-[rgba(0,0,0,0.45)]">Not connected</span>
          </span>
        </li>
      ) : connected.map(integration => (
        <li key={integration.id}>
          <button
            type="button"
            aria-pressed={integration.id === selected}
            onClick={() => onSelect(integration.id)}
            className={`flex w-full items-center gap-2 rounded-lg px-3 py-2 text-left transition-colors focus:outline-none focus-visible:ring-2 focus-visible:ring-[rgba(0,0,0,0.25)] ${
              integration.id === selected ? 'bg-[rgba(0,0,0,0.06)]' : 'hover:bg-[rgba(0,0,0,0.03)]'
            }`}
          >
            <Icon icon={provider.icon} width={16} className="flex-shrink-0 text-[rgba(0,0,0,0.65)]" />
            <span className="min-w-0">
              <span className="block text-xs font-semibold text-[rgba(0,0,0,0.85)]">{provider.label}</span>
              {integration.accountLabel && (
                <span className="block truncate text-[11px] text-[rgba(0,0,0,0.45)]" title={integration.accountLabel}>
                  {integration.accountLabel}
                </span>
              )}
            </span>
          </button>
        </li>
      ))
    ))}
  </ul>
);

const SwitchRow: React.FC<{ label: string; description: string; checked: boolean }> = ({ label, description, checked }) => (
  <div className="flex items-start justify-between gap-3 rounded-lg bg-[rgba(0,0,0,0.02)] p-3">
    <div>
      <p className="text-xs font-semibold text-[rgba(0,0,0,0.8)]">{label}</p>
      <p className="text-[11px] text-[rgba(0,0,0,0.5)]">{description}</p>
    </div>
    <ConsoleOnlySwitch label={label} checked={checked} placement="left" />
  </div>
);

/** The active drive's Scope Selection: the greyed radios, then the count or the users / channels table. */
const ScopeSelection: React.FC<{ integrationId: string; scope: SaasScanScope }> = ({ integrationId, scope }) => {
  const allUsers = scope.scopeType === 'ALL_USERS';
  const count = scope.totalActiveUsers;
  return (
    <SettingsCard title="Scope Selection">
      <div role="radiogroup" aria-label="Scope Selection" className="flex flex-wrap gap-4">
        <ConsoleOnlyRadio label="All users" checked={allUsers} />
        <ConsoleOnlyRadio label="Selected users" checked={!allUsers} />
      </div>
      {allUsers && count !== null && (
        <p className="text-xs text-[rgba(0,0,0,0.6)]">
          {count.toLocaleString('en-US')} {count === 1 ? 'user' : 'users'} will be scanned.
        </p>
      )}
      {!allUsers && (scope.driveType === SLACK_DRIVE
        ? <SlackChannelsTable integrationId={integrationId} />
        : scope.driveType !== null && (
          <SaasUsersTable integrationId={integrationId} driveType={scope.driveType} domains={scope.supportedDomains} />
        ))}
    </SettingsCard>
  );
};

/** One integration's configuration (S9): header, Scan Type, the drive tabs and the active drive's scope. */
const IntegrationPanel: React.FC<{ integration: SaasIntegration; provider: SaasProviderDef }> = ({ integration, provider }) => {
  const config = useServerList<SaasConfig>(scanUrls.saasConfiguration(integration.id));
  const ids = useId();
  const [drive, setDrive] = useState<string | null>(null);

  if (config.error) return <ErrorNote error={config.error} onRetry={config.reload} subject="item" />;
  const data = config.data;
  if (data === null) return <LoadingNote />;

  const tabs = data.scopes.map((scope, i) => ({ id: scope.driveType ?? `scope-${i}`, label: scope.driveLabel ?? scope.driveType ?? '-', scope }));
  const active = tabs.find(tab => tab.id === drive) ?? tabs[0];
  const lastSaved = data.lastSavedBy !== null && data.lastSavedAt !== null
    ? `Last saved by ${data.lastSavedBy} · ${formatListDate(data.lastSavedAt)}`
    : null;

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="flex items-center gap-3">
          <Icon icon={provider.icon} width={24} className="flex-shrink-0 text-[rgba(0,0,0,0.65)]" />
          <div className="min-w-0">
            <h3 className="text-sm font-semibold text-[rgba(0,0,0,0.88)]">{provider.label}</h3>
            {integration.accountLabel && <p className="truncate text-[11px] text-[rgba(0,0,0,0.5)]">{integration.accountLabel}</p>}
          </div>
        </div>
        {lastSaved && <p className="text-[11px] text-[rgba(0,0,0,0.5)]">{lastSaved}</p>}
      </div>

      <SettingsCard title="Scan Type" description="Choose how this app is scanned.">
        <SwitchRow label="Real-time scanning" description="Continuously monitor and update for new changes." checked={data.realtimeEnabled} />
        <SwitchRow label="Backfill existing data" description="Scan all existing data for comprehensive coverage." checked={data.backfillingEnabled} />
        {/* The console shows the coverage under Backfill only while backfilling is on. */}
        {data.backfillingEnabled && (
          <div className="max-w-xs">
            <ConsoleOnlyField label="Sampling Coverage" value={data.samplingPercentage !== null ? `${data.samplingPercentage}%` : null} />
          </div>
        )}
      </SettingsCard>

      {!data.provisioningComplete && (
        <p role="status" className="flex items-start gap-2 rounded-lg border border-sky-100 bg-sky-50 px-3 py-2 text-xs text-sky-800">
          <Icon icon="mdi:information-outline" width={14} className="mt-0.5 flex-shrink-0" />
          Discovery is still in progress...
        </p>
      )}

      {active && (
        <div className="space-y-4">
          <DrawerTabList tabs={tabs} active={active.id} onSelect={setDrive} label="Drives" idPrefix={ids} />
          <div role="tabpanel" id={`${ids}-panel`} aria-labelledby={`${ids}-tab-${active.id}`} className="space-y-4">
            <div className="flex items-center gap-2 text-xs text-[rgba(0,0,0,0.6)]">
              <DspmProviderIcon provider={driveIconKey(active.scope.driveType)} size={16} />
              {active.label}
            </div>
            <ScopeSelection key={active.id} integrationId={integration.id} scope={active.scope} />
          </div>
        </div>
      )}
    </div>
  );
};

/**
 * Scan Configurations › SaaS (FR-SM-3; S8, S9): the apps on the left, connected ones (from the SaaS
 * datastore rows, GAP-73) selectable and the rest "Not connected"; the selected integration's settings
 * on the right, all greyed.
 */
const SaasScanConfiguration: React.FC = () => {
  const list = useServerList<SaasIntegrationList>(scanUrls.saasIntegrations());
  const [chosen, setChosen] = useState<string | null>(null);

  if (list.error) return <ErrorNote error={list.error} onRetry={list.reload} subject="item" />;
  if (list.data === null) return <LoadingNote />;

  const integrations = list.data.integrations;
  const ordered = providerGroups(integrations).flatMap(group => group.integrations.map(integration => ({ integration, provider: group.provider })));
  const selected = ordered.find(entry => entry.integration.id === chosen) ?? ordered[0] ?? null;

  return (
    <div className="space-y-3">
      {list.data.partial && <p className="text-[11px] text-[rgba(0,0,0,0.5)]">Based on the first 1,000 SaaS datastores.</p>}
      <div className="flex gap-5">
        <ProviderList integrations={integrations} selected={selected?.integration.id ?? null} onSelect={setChosen} />
        <div className="min-w-0 flex-1">
          {selected
            ? <IntegrationPanel key={selected.integration.id} integration={selected.integration} provider={selected.provider} />
            : <EmptyState title="No SaaS integrations connected" />}
        </div>
      </div>
    </div>
  );
};

export default SaasScanConfiguration;
