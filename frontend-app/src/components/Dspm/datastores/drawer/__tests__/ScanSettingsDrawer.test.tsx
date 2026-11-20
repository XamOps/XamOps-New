import { afterEach, describe, expect, it, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { DatastoreRow } from '../../../../../types/dspmDatastores';
import type { DatastoreDatabases, DatastoreScanSettings } from '../../../../../types/dspmScans';
import { DrawerShell } from '../DrawerShell';
import ScanSettingsDrawer from '../ScanSettingsDrawer';
import { parameterRows, scanSettingsSections, showDatabases } from '../scanSettingsModel';
import { CONSOLE_ONLY_TOOLTIP } from '../../ui/ConsoleOnly';

// ─── synthetic answers (shapes only; no fixture values) ─────────────────────────────────────────

type RowShape = Pick<DatastoreRow, 'name' | 'category' | 'source'>;

const row = (name: string, provider: string, category: DatastoreRow['category'] = 'cloud'): RowShape => ({
  name, category, source: { provider, providerLabel: provider, accountLabel: 'example-account', region: null },
});

const EMPTY: DatastoreScanSettings = {
  parameters: null, inclusionPaths: [], exclusionPaths: [], allDatabases: false, selectedDatabases: [],
  accessible: null, exportAssistName: null, discoveryAssistName: null, host: null, port: null, serviceName: null,
  credentialsSaved: false,
};

const NO_PARAMETERS = {
  numFoldersLeafNode: null, parallelism: null, timeOutHours: null, rowSamplingLimit: null, samplingPercentageLimit: null,
};

/** An RDS-shaped answer: sampling limits, a connection, an export assist and saved credentials. */
const RDS_SETTINGS: DatastoreScanSettings = {
  ...EMPTY,
  parameters: { ...NO_PARAMETERS, rowSamplingLimit: 5_000, samplingPercentageLimit: 10 },
  accessible: false,
  exportAssistName: 'example-export-assist',
  host: 'orders.example.internal',
  port: 5432,
  credentialsSaved: true,
};

/** An on-prem-shaped answer: three parameters, both assists, a service name, no saved credentials. */
const ON_PREM_SETTINGS: DatastoreScanSettings = {
  ...EMPTY,
  parameters: { ...NO_PARAMETERS, parallelism: 150, rowSamplingLimit: 3_000, samplingPercentageLimit: 20 },
  accessible: true,
  exportAssistName: 'example-onprem-export',
  discoveryAssistName: 'example-onprem-discovery',
  host: 'ledger.example.internal',
  port: 1521,
  serviceName: 'EXAMPLEPDB',
};

/** An S3-shaped answer: file parameters and path conditions only. */
const S3_SETTINGS: DatastoreScanSettings = {
  ...EMPTY,
  parameters: { ...NO_PARAMETERS, numFoldersLeafNode: 50, parallelism: 100, timeOutHours: 2 },
  inclusionPaths: ['s3://example-bucket/exports/', 's3://example-bucket/reports/'],
  exclusionPaths: ['**.tmp'],
};

const RDS_ROW = row('orders-replica', 'RDS');
const ON_PREM_ROW = row('ledger_db', 'SELF_HOSTED_POSTGRES', 'on_prem');
const S3_ROW = row('example-bucket', 'S3');
const SNOWFLAKE_ROW = row('analytics-warehouse', 'SNOWFLAKE');

function renderSettings(shown: RowShape, settings: DatastoreScanSettings, databases?: DatastoreDatabases) {
  const api = {
    syncStatus: vi.fn(),
    scanSettings: vi.fn(() => Promise.resolve(settings)),
    databases: vi.fn(() => Promise.resolve(databases ?? { databases: [] })),
  };
  const onClose = vi.fn();
  const opener = document.createElement('button');
  document.body.appendChild(opener);
  render(
    <DrawerShell label="Datastore" onClose={vi.fn()} initialFocus={{ current: null }}>
      <ScanSettingsDrawer row={shown} api={api} returnFocus={opener} onClose={onClose} />
    </DrawerShell>,
  );
  const layer = screen.getByRole('dialog', { name: 'Datastore Scan Configuration' });
  return { api, onClose, opener, layer };
}

const sectionTitles = (layer: HTMLElement) => within(layer).queryAllByRole('heading', { level: 3 }).map(h => h.textContent);
const field = (layer: HTMLElement, name: string) => within(layer).getByRole('textbox', { name });

function expectGreyed(control: HTMLElement) {
  expect(control).toHaveAttribute('aria-disabled', 'true');
  const tooltip = document.getElementById(control.getAttribute('aria-describedby') ?? '');
  expect(tooltip).toHaveTextContent(CONSOLE_ONLY_TOOLTIP);
}

afterEach(() => {
  vi.clearAllMocks();
});

// ─── tests ───────────────────────────────────────────────────────────────────────────────────────

describe('scanSettingsModel', () => {
  it('lists the filled parameters in the console order, with "%" on the percentage', () => {
    expect(parameterRows(RDS_SETTINGS).map(p => [p.label, p.value])).toEqual([
      ['Row-Wise Sampling Limit (rows)', '5,000'],
      ['Sampling Percentage', '10%'],
    ]);
    expect(parameterRows(EMPTY)).toEqual([]);
  });

  it('picks the sections the answer fills (design 6.9)', () => {
    expect(scanSettingsSections(RDS_ROW, RDS_SETTINGS)).toEqual(['parameters', 'assist', 'connection', 'credentials']);
    expect(scanSettingsSections(S3_ROW, S3_SETTINGS)).toEqual(['parameters', 'conditions', 'cost']);
    expect(scanSettingsSections(row('vol-1', 'EBS'), EMPTY)).toEqual(['conditions']);
    expect(scanSettingsSections(SNOWFLAKE_ROW, EMPTY)).toEqual(['databases']);
    expect(scanSettingsSections(row('table', 'DYNAMODB'), EMPTY)).toEqual([]);
  });

  it('shows databases only for Snowflake and Databricks', () => {
    expect(showDatabases(SNOWFLAKE_ROW)).toBe(true);
    expect(showDatabases(row('lake', 'DATABRICKS_UNITY'))).toBe(true);
    expect(showDatabases(RDS_ROW)).toBe(false);
    expect(showDatabases(S3_ROW)).toBe(false);
  });
});

describe('ScanSettingsDrawer (AC16)', () => {
  it('draws an RDS answer: parameters, assists, connection and saved credentials, every input greyed', async () => {
    const { api, layer } = renderSettings(RDS_ROW, RDS_SETTINGS);
    expect(within(layer).getByRole('heading', { name: 'Datastore Scan Configuration' })).toBeInTheDocument();
    expect(within(layer).getByText('orders-replica')).toBeInTheDocument();
    expect(await within(layer).findByText('Configuration Parameters')).toBeInTheDocument();
    expect(sectionTitles(layer)).toEqual(['Configuration Parameters', 'Assist Details', 'Connection Details', 'Credentials']);
    expect(field(layer, 'Row-Wise Sampling Limit (rows)')).toHaveTextContent('5,000');
    expect(field(layer, 'Sampling Percentage')).toHaveTextContent('10%');
    expect(within(layer).queryByRole('textbox', { name: 'Maximum Parallelism' })).toBeNull();
    expect(within(layer).getByText(/portion of your data that will be sampled/)).toBeInTheDocument();
    expect(within(layer).getByRole('radio', { name: 'No' })).toHaveAttribute('aria-checked', 'true');
    expect(within(layer).getByRole('radio', { name: 'Yes' })).toHaveAttribute('aria-checked', 'false');
    expect(field(layer, 'Export Assist')).toHaveTextContent('example-export-assist');
    expect(field(layer, 'Discovery Assist')).toHaveTextContent('-');
    expect(field(layer, 'Host')).toHaveTextContent('orders.example.internal');
    expect(field(layer, 'Port')).toHaveTextContent('5432');
    expect(within(layer).queryByRole('textbox', { name: 'Service Name' })).toBeNull();
    expect(within(layer).getByText('Your credentials are already saved with us')).toBeInTheDocument();
    expect(within(layer).queryByRole('textbox', { name: 'Username' })).toBeNull();
    for (const control of [
      ...within(layer).getAllByRole('textbox'), ...within(layer).getAllByRole('radio'),
      within(layer).getByRole('button', { name: 'Update Credentials' }),
      within(layer).getByRole('button', { name: 'Save Configuration' }),
    ]) expectGreyed(control);
    expect(api.databases).not.toHaveBeenCalled();
    // Never a URI, a user name or a password.
    expect(layer.textContent).not.toMatch(/:\/\//);
    expect(layer.textContent).not.toMatch(/Connection URI|password/i);
  });

  it('draws an on-prem answer: three parameters, both assists, a service name and empty credential fields', async () => {
    const { layer } = renderSettings(ON_PREM_ROW, ON_PREM_SETTINGS);
    await within(layer).findByText('Configuration Parameters');
    expect(sectionTitles(layer)).toEqual(['Configuration Parameters', 'Assist Details', 'Connection Details', 'Credentials']);
    expect(['Maximum Parallelism', 'Row-Wise Sampling Limit (rows)', 'Sampling Percentage']
      .map(name => field(layer, name).textContent)).toEqual(['150', '3,000', '20%']);
    expect(within(layer).getByRole('radio', { name: 'Yes' })).toHaveAttribute('aria-checked', 'true');
    expect(field(layer, 'Discovery Assist')).toHaveTextContent('example-onprem-discovery');
    expect(field(layer, 'Service Name')).toHaveTextContent('EXAMPLEPDB');
    expect(field(layer, 'Username')).toHaveTextContent('');
    expect(field(layer, 'Password')).toHaveTextContent('');
    expect(within(layer).queryByRole('button', { name: 'Update Credentials' })).toBeNull();
    expect(layer.textContent).not.toMatch(/:\/\//);
  });

  it('draws an S3 answer: paths, one field each, and the scan duration', async () => {
    const { layer } = renderSettings(S3_ROW, S3_SETTINGS);
    await within(layer).findByText('Define Scan Conditions');
    expect(sectionTitles(layer)).toEqual(['Configuration Parameters', 'Define Scan Conditions', 'Scan Cost Estimation']);
    expect(field(layer, 'Inclusion Prefix Path 1')).toHaveTextContent('s3://example-bucket/exports/');
    expect(field(layer, 'Inclusion Prefix Path 2')).toHaveTextContent('s3://example-bucket/reports/');
    expect(field(layer, 'Exclusion Pattern')).toHaveTextContent('**.tmp');
    expect(within(layer).getByText('Total job run duration (hrs)').nextSibling).toHaveTextContent('2');
  });

  it('draws one empty path field each for an object store without paths', async () => {
    const { layer } = renderSettings(row('vol-1', 'EBS'), EMPTY);
    await within(layer).findByText('Define Scan Conditions');
    expect(field(layer, 'Inclusion Prefix Path')).toHaveTextContent('');
    expect(field(layer, 'Exclusion Pattern')).toHaveTextContent('');
  });

  it('shows the empty state when no section is filled', async () => {
    const { layer } = renderSettings(row('table', 'DYNAMODB'), EMPTY);
    expect(await within(layer).findByText('No scan settings available')).toBeInTheDocument();
    expect(sectionTitles(layer)).toEqual([]);
  });

  it('lists a Snowflake datastore\'s databases as greyed checkboxes, ticking the selected ones', async () => {
    const { api, layer } = renderSettings(
      SNOWFLAKE_ROW,
      { ...EMPTY, selectedDatabases: ['SALES'] },
      { databases: ['SALES', 'MARKETING'] },
    );
    await within(layer).findByText('Databases To Scan');
    expect(api.databases).toHaveBeenCalledTimes(1);
    expect(within(layer).getByRole('radio', { name: 'Selected' })).toHaveAttribute('aria-checked', 'true');
    expect(within(layer).getByRole('radio', { name: 'All databases' })).toHaveAttribute('aria-checked', 'false');
    expect(within(layer).getByText('Selected:').parentElement).toHaveTextContent('Selected: SALES');
    const sales = await within(layer).findByRole('checkbox', { name: 'SALES' });
    expect(sales).toHaveAttribute('aria-checked', 'true');
    expect(within(layer).getByRole('checkbox', { name: 'MARKETING' })).toHaveAttribute('aria-checked', 'false');
    expectGreyed(sales);
  });

  it('shows the databases request\'s error in its section only', async () => {
    const api = {
      syncStatus: vi.fn(),
      scanSettings: vi.fn(() => Promise.resolve({ ...EMPTY, allDatabases: true })),
      databases: vi.fn(() => Promise.reject(new Error('HTTP 503: {"code":"UPSTREAM_UNAVAILABLE","message":"down"}'))),
    };
    render(
      <DrawerShell label="Datastore" onClose={vi.fn()} initialFocus={{ current: null }}>
        <ScanSettingsDrawer row={SNOWFLAKE_ROW} api={api} returnFocus={null} onClose={vi.fn()} />
      </DrawerShell>,
    );
    const layer = screen.getByRole('dialog', { name: 'Datastore Scan Configuration' });
    expect(await within(layer).findByRole('alert')).toHaveTextContent('Matters is not responding right now.');
    expect(within(layer).getByRole('radio', { name: 'All databases' })).toHaveAttribute('aria-checked', 'true');
  });

  it('sends nothing on Save Configuration and closes from the footer, returning focus', async () => {
    const user = userEvent.setup();
    const { api, layer, onClose, opener } = renderSettings(RDS_ROW, RDS_SETTINGS);
    await within(layer).findByText('Configuration Parameters');
    await user.click(within(layer).getByRole('button', { name: 'Save Configuration' }));
    await user.click(within(layer).getByRole('button', { name: 'Update Credentials' }));
    expect(api.scanSettings).toHaveBeenCalledTimes(1);
    expect(onClose).not.toHaveBeenCalled();
    const buttons = within(layer).getAllByRole('button', { name: 'Close' });
    await user.click(buttons[buttons.length - 1]);
    expect(onClose).toHaveBeenCalledTimes(1);
    expect(opener).toHaveFocus();
  });
});
