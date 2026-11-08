import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type {
  DatastoreFilePage, DatastoreOccurrence, DatastoreOccurrencePage, DatastoreOverview, DatastoreScanHistory,
  DatastoreSensitives,
} from '../../../../../types/dspmDatastores';

vi.mock('../../../../../utils/apiClient', () => ({ default: { get: vi.fn() } }));

import apiClient from '../../../../../utils/apiClient';
import DatastoreDrawer, { type DrawerRow } from '../DatastoreDrawer';
import { formatConsoleDate } from '../../datastoreFormat';

const get = apiClient.get as unknown as ReturnType<typeof vi.fn>;

// ─── synthetic data (shapes only; no fixture values) ────────────────────────────────────────────

function drawerRow(over: Partial<DrawerRow> = {}): DrawerRow {
  return {
    id: 'cloud-asset-1', sourceId: '0123456789abcdef01234567', name: 'orders-archive', category: 'cloud',
    subtype: 'S3', subtypeLabel: 'S3',
    risk: { score: 8.44, level: 'CRITICAL', levelLabel: 'Critical' },
    sensitives: { recordCount: 1_559, typeLabels: ['Personal'] },
    source: { provider: 'S3', providerLabel: 'S3', accountLabel: 'example-prod', region: 'eu-west-1' },
    scan: { status: 'CLASSIFIED', statusLabel: 'Classified', displayAt: null },
    agent: null,
    ...over,
  };
}

const CLOUD_ROW = drawerRow();
const ON_PREM_ROW = drawerRow({
  id: 'onprem-asset-1', sourceId: 'fedcba9876543210fedcba98', name: 'ledger_db', category: 'on_prem',
  subtype: 'SELF_HOSTED_POSTGRES', subtypeLabel: 'PostgreSQL',
  source: { provider: 'SELF_HOSTED_POSTGRES', providerLabel: 'PostgreSQL (self-hosted)', accountLabel: 'ledger_db', region: 'self-hosted' },
});
const AZURE_ROW = drawerRow({
  id: 'azure-asset-1', sourceId: 'abcdefabcdefabcdefabcdef', name: 'archive-container', subtype: 'AZURE_BLOB',
  subtypeLabel: 'Azure Blob',
  source: { provider: 'AZURE_BLOB', providerLabel: 'Azure Blob', accountLabel: 'example-subscription', region: 'northeurope' },
  scan: { status: 'NOT_SCANNED', statusLabel: 'Not Scanned', displayAt: null },
});

const S3_OVERVIEW: DatastoreOverview = {
  consoleUrl: 'https://s3.console.aws.amazon.com/s3/buckets/orders-archive',
  scanningStatus: 'SCANNING_OUTPUT_PROCESSED',
  scanningStatusLabel: 'Scanning Output Processed',
  canDiscoverSchema: false,
  fileSummary: {
    totalFiles: 4_401,
    types: [
      { key: 'pdf_files', label: 'PDF', count: 120 },
      { key: 'autocad', label: 'Autocad', count: 0 },
    ],
  },
  sensitiveRecords: {
    total: 1_559,
    groups: [{ code: 'PII', label: 'Personal', entities: [{ name: 'EMAIL ADDRESS', count: 1_200 }, { name: 'PERSON NAME', count: 359 }] }],
  },
  properties: [
    { key: 'region', label: 'Region', text: 'eu-west-1', bytes: null },
    { key: 'datastore_size', label: 'Datastore Size', text: null, bytes: 15_454_389_580 },
    { key: 'arn', label: 'ARN', text: 'arn:aws:s3:::orders-archive', bytes: null },
  ],
  securityConfigurations: [
    { key: 'bucket_versioning', label: 'Bucket Versioning', value: 'Enabled' },
    { key: 'default_encryption', label: 'Default Encryption', value: 'Sse S3' },
    { key: 'public_accessibility', label: 'Public Accessibility', value: 'Disabled' },
  ],
};

/** The 17 file types at zero, in the backend's order for a datastore that was never scanned. */
const ZERO_FILE_TYPES: Array<[string, string]> = [
  ['autocad', 'Autocad'], ['code_files', 'Code'], ['compressed_files', 'Compressed'], ['contact_files', 'Contact'],
  ['databases_files', 'Databases'], ['documents', 'Documents'], ['email', 'Email'], ['image_files', 'Image'],
  ['log_files', 'Log'], ['mailboxes', 'Mailboxes'], ['unknown_files', 'Other'], ['parquet_files', 'Parquet'],
  ['pdf_files', 'PDF'], ['presentations', 'Presentations'], ['system_generated_files', 'System Generated'],
  ['web_markup', 'Web Markup'], ['xlsx_files', 'XLSX'],
];

const AZURE_NOT_SCANNED: DatastoreOverview = {
  consoleUrl: 'https://portal.azure.com/#resource/example',
  scanningStatus: null,
  scanningStatusLabel: null,
  canDiscoverSchema: false,
  fileSummary: { totalFiles: 0, types: ZERO_FILE_TYPES.map(([key, label]) => ({ key, label, count: 0 })) },
  sensitiveRecords: { total: null, groups: [] },
  properties: [
    { key: 'region', label: 'Region', text: 'northeurope', bytes: null },
    { key: 'datastore_size', label: 'Datastore Size', text: null, bytes: 0 },
    { key: 'subscription_id', label: 'Subscription ID', text: '00000000-0000-4000-8000-000000000000', bytes: null },
    { key: 'resource_group', label: 'Resource Group', text: 'example-rg', bytes: null },
    { key: 'storage_account', label: 'Storage Account', text: 'examplestorage', bytes: null },
  ],
  securityConfigurations: [],
};

const POSTGRES_OVERVIEW: DatastoreOverview = {
  consoleUrl: null,
  scanningStatus: 'SCANNING_OUTPUT_PROCESSED',
  scanningStatusLabel: 'Scanning Output Processed',
  canDiscoverSchema: true,
  fileSummary: null,
  sensitiveRecords: {
    total: 2_645,
    groups: [
      { code: 'SECRETS', label: 'IT & Security', entities: [{ name: 'SHA KEY', count: 2_640 }] },
      { code: 'PII', label: 'Personal', entities: [{ name: 'PERSON NAME', count: 5 }] },
    ],
  },
  properties: [
    { key: 'host', label: 'Host', text: 'db.internal.example', bytes: null },
    { key: 'port', label: 'Port', text: '5432', bytes: null },
  ],
  securityConfigurations: null,
};

const BLANK_ENTITY = { name: '', count: 0, categoryLabel: null, blank: true };

const FILE_SENSITIVES: DatastoreSensitives = {
  database: false,
  entities: [
    { name: 'EMAIL ADDRESS', count: 1_200, categoryLabel: 'Personal', blank: false },
    { name: 'PERSON NAME', count: 359, categoryLabel: 'Personal', blank: false },
    BLANK_ENTITY,
  ],
  databases: [],
};

const DB_SENSITIVES: DatastoreSensitives = {
  database: true,
  entities: [
    { name: 'SHA KEY', count: 2_640, categoryLabel: 'IT & Security', blank: false },
    { name: 'PERSON NAME', count: 5, categoryLabel: 'Personal', blank: false },
    BLANK_ENTITY,
  ],
  databases: [
    {
      id: 'db-ledger', name: 'ledger',
      tables: [
        {
          name: 'payments', schemaName: 'public', count: 2_642, labels: ['IT & Security', 'Personal'], expandable: true,
          findings: [
            { columnName: 'token_digest', entityType: 'SHA KEY', labels: ['IT & Security', 'Personal'], count: 2_640, falsePositive: false },
            { columnName: 'memo_text', entityType: 'PERSON NAME', labels: ['Personal'], count: 2, falsePositive: true },
          ],
        },
        { name: 'audit_log', schemaName: 'public', count: 0, labels: [], expandable: false, findings: [] },
      ],
    },
    {
      id: 'db-reporting', name: 'reporting',
      tables: [{
        name: 'daily_totals', schemaName: 'analytics', count: 3, labels: ['Personal'], expandable: true,
        findings: [{ columnName: 'owner_name', entityType: 'PERSON NAME', labels: ['Personal'], count: 3, falsePositive: false }],
      }],
    },
  ],
};

function occurrence(over: Partial<DatastoreOccurrence>): DatastoreOccurrence {
  return {
    fileName: null, url: null, databaseName: null, schemaName: null, tableName: null, columnName: null,
    falsePositive: false, samples: [], ...over,
  };
}

const FILE_OCCURRENCES = [
  occurrence({
    fileName: 'customers.csv', url: 's3://orders-archive/exports/customers.csv',
    samples: ['<<Jane Doe>>', 'pat@example.com', 'sam@example.com', 'lee@example.com', 'kim@example.com'],
  }),
  occurrence({ fileName: 'notes.txt', url: 's3://orders-archive/notes.txt', falsePositive: true }),
];

const DB_OCCURRENCES = [
  occurrence({ databaseName: 'ledger', schemaName: 'public', tableName: 'payments', columnName: 'token_digest', samples: ['ab12cd34'] }),
];

function occurrencePage(url: URL, rows = FILE_OCCURRENCES, total = rows.length): DatastoreOccurrencePage {
  return {
    entityName: url.searchParams.get('entityName') ?? '',
    total,
    skip: Number(url.searchParams.get('skip')),
    limit: Number(url.searchParams.get('limit')),
    rows,
  };
}

function filePage(url: URL, total = 2, truncated = false): DatastoreFilePage {
  const page = Number(url.searchParams.get('page'));
  const pageSize = Number(url.searchParams.get('pageSize'));
  return {
    rows: [
      { path: 'exports/customers.csv', name: 'customers.csv', count: 12, labels: ['Personal'], entities: [{ name: 'EMAIL ADDRESS', label: 'Personal' }] },
      { path: 'exports/empty.csv', name: 'empty.csv', count: 0, labels: ['Personal'], entities: [{ name: '', label: null }] },
    ],
    page, pageSize, total, totalPages: Math.ceil(total / pageSize), truncated,
  };
}

const START = new Date(2026, 8, 11, 15, 50).getTime() / 1000;

const HISTORY: DatastoreScanHistory = {
  runs: [
    {
      executionNumber: 3, startTime: START, sensitiveRecords: 12_345, sensitivityLevel: 'CRITICAL_SENSITIVITY',
      severityLabel: 'Critical', triggeredBy: 'Scheduler', status: 'SCAN_STOPPED', statusLabel: 'Stopped',
    },
    {
      executionNumber: 2, startTime: START - 86_400, sensitiveRecords: 0, sensitivityLevel: 'NOT_SENSITIVE',
      severityLabel: null, triggeredBy: 'Pat Example', status: 'COMPLETED', statusLabel: 'Completed',
    },
  ],
};

// ─── mocked API ──────────────────────────────────────────────────────────────────────────────────

type Answer = (url: URL) => unknown;

const DRAWER_PATH = /^\/api\/dspm\/datastores\/([0-9a-f]{24})\/(.+)$/;

let answers: Record<string, Answer>;

const fail = (status: number, body: object): Answer => () => {
  throw new Error(`HTTP ${status}: ${JSON.stringify(body)}`);
};
const pending: Answer = () => new Promise(() => {});

const calls = () => get.mock.calls.map(([raw]) => new URL(String(raw), 'http://localhost'));
const requestsTo = (endpoint: string) => calls().filter(url => DRAWER_PATH.exec(url.pathname)?.[2] === endpoint);
function last<T>(items: T[]): T {
  return items[items.length - 1];
}

beforeEach(() => {
  answers = {
    overview: () => S3_OVERVIEW,
    'scan-status': () => ({ progress: 99.6 }),
    sensitives: () => FILE_SENSITIVES,
    'sensitives/files': url => filePage(url),
    'sensitives/occurrences': url => occurrencePage(url),
    'scan-history': () => HISTORY,
  };
  get.mockImplementation(async (raw: string) => {
    const url = new URL(raw, 'http://localhost');
    const answer = answers[DRAWER_PATH.exec(url.pathname)?.[2] ?? ''];
    if (!answer) throw new Error(`HTTP 404: unexpected ${raw}`);
    return answer(url);
  });
});

afterEach(() => {
  vi.clearAllMocks();
});

function renderDrawer(row: DrawerRow = CLOUD_ROW) {
  const onClose = vi.fn();
  render(<DatastoreDrawer row={row} onClose={onClose} />);
  return { onClose, drawer: screen.getByRole('dialog', { name: row.name ?? '' }) };
}

const scanChipElement = () => document.querySelector('[data-scan-chip]') as HTMLElement;
const scanChipDot = () => scanChipElement().querySelector('[data-dot]')?.getAttribute('data-dot');
const headers = (table: HTMLElement) => within(table).getAllByRole('columnheader').map(cell => cell.textContent);

/** Opens File View once the Sensitive tab has loaded. */
async function openFileView(user: ReturnType<typeof userEvent.setup>, drawer: HTMLElement) {
  await user.click(await within(drawer).findByRole('button', { name: 'File View' }));
  await within(drawer).findByText('customers.csv');
}

// ─── tests ───────────────────────────────────────────────────────────────────────────────────────

describe('DatastoreDrawer', () => {
  it('loads the overview, scan status and sensitives in parallel on open and focuses the close button', () => {
    answers.overview = pending;
    answers['scan-status'] = pending;
    answers.sensitives = pending;
    const { drawer } = renderDrawer();

    expect(calls().map(url => url.pathname)).toEqual([
      '/api/dspm/datastores/0123456789abcdef01234567/overview',
      '/api/dspm/datastores/0123456789abcdef01234567/scan-status',
      '/api/dspm/datastores/0123456789abcdef01234567/sensitives',
    ]);
    expect(within(drawer).getByRole('button', { name: 'Close' })).toHaveFocus();
    expect(within(drawer).getByText('Loading…')).toBeInTheDocument();
    expect(drawer).toHaveAttribute('aria-modal', 'true');
    expect(drawer).toHaveAttribute('tabindex', '-1');
    expect(drawer.parentElement).toBe(document.body);
  });

  it('renders the header from the row and the overview, with every console action greyed', async () => {
    const { drawer } = renderDrawer();
    const link = await within(drawer).findByRole('link', { name: /Go to AWS/ });
    expect(link).toHaveAttribute('href', S3_OVERVIEW.consoleUrl);
    expect(link).toHaveAttribute('target', '_blank');
    expect(link).toHaveAttribute('rel', 'noopener noreferrer');
    expect(within(drawer).getByRole('heading', { name: 'orders-archive' })).toBeInTheDocument();
    expect(within(drawer).getByText('S3')).toBeInTheDocument();
    expect(within(drawer).getByText('example-prod')).toBeInTheDocument();
    for (const name of ['Start Scan', 'Actions']) {
      const control = within(drawer).getByRole('button', { name });
      expect(control).toHaveAttribute('aria-disabled', 'true');
      expect(document.getElementById(control.getAttribute('aria-describedby') ?? ''))
        .toHaveTextContent('Available in the Matters console');
    }
  });

  it.each([
    ['cloud', CLOUD_ROW, S3_OVERVIEW],
    ['on-prem', ON_PREM_ROW, POSTGRES_OVERVIEW],
  ])('lists only the tabs that have a body (%s), Sensitive first and selected', async (_kind, row, overview) => {
    answers.overview = () => overview;
    answers.sensitives = () => (row === ON_PREM_ROW ? DB_SENSITIVES : FILE_SENSITIVES);
    const { drawer } = renderDrawer(row);
    await within(drawer).findByRole('table');
    expect(within(drawer).getAllByRole('tab').map(tab => tab.textContent)).toEqual(['Sensitive', 'Information', 'Scan History']);
    expect(within(drawer).getByRole('tab', { name: 'Sensitive' })).toHaveAttribute('aria-selected', 'true');
  });

  it('requests Scan History only on first activation and keeps it', async () => {
    const user = userEvent.setup();
    const { drawer } = renderDrawer();
    await within(drawer).findByText('EMAIL ADDRESS');
    expect(requestsTo('scan-history')).toHaveLength(0);

    await user.click(within(drawer).getByRole('tab', { name: 'Scan History' }));
    expect(await within(drawer).findByText('Scheduler')).toBeInTheDocument();
    await user.click(within(drawer).getByRole('tab', { name: 'Sensitive' }));
    await user.click(within(drawer).getByRole('tab', { name: 'Scan History' }));
    expect(within(drawer).getByText('Scheduler')).toBeInTheDocument();

    expect(requestsTo('scan-history')).toHaveLength(1);
    expect(requestsTo('overview')).toHaveLength(1);
    expect(requestsTo('sensitives')).toHaveLength(1);
  });

  it('shows the scan runs with console dates, separators, severity and a "Columns 6" chooser', async () => {
    const user = userEvent.setup();
    const { drawer } = renderDrawer();
    await user.click(within(drawer).getByRole('tab', { name: 'Scan History' }));
    await within(drawer).findByText('Scheduler');

    const table = within(drawer).getByRole('table');
    expect(headers(table)).toEqual(['Scan Number', 'Date & Time', 'Sensitive Records', 'Severity', 'Triggered By', 'Scan Status']);
    expect(within(table).getByRole('button', { name: 'More information' })).toBeInTheDocument();
    const [newest, older] = within(table).getAllByRole('row').slice(1) as HTMLTableRowElement[];
    expect([...newest.cells].map(cell => cell.textContent))
      .toEqual(['3', formatConsoleDate(START), '12,345', 'Critical', 'Scheduler', 'Stopped']);
    expect(formatConsoleDate(START)).toBe('11 Sep 2026, 03:50 PM');
    expect([...older.cells].map(cell => cell.textContent)[3]).toBe('-');
    expect(within(drawer).getByRole('button', { name: /^Columns/ })).toHaveTextContent('6');
    expect(within(drawer).getByText('Showing 1 - 2 of 2')).toBeInTheDocument();
  });

  it('shows the Scan History empty state', async () => {
    const user = userEvent.setup();
    answers['scan-history'] = () => ({ runs: [] });
    const { drawer } = renderDrawer();
    await user.click(within(drawer).getByRole('tab', { name: 'Scan History' }));
    expect(await within(drawer).findByText('No scan history yet')).toBeInTheDocument();
    expect(within(drawer).getByText('Runs appear here once this datastore has completed a scan.')).toBeInTheDocument();
  });

  it('keeps Sensitive working when Scan History fails, and retries Scan History', async () => {
    const user = userEvent.setup();
    answers['scan-history'] = fail(502, { code: 'UPSTREAM_UNAVAILABLE', message: 'down' });
    const { drawer } = renderDrawer();
    await within(drawer).findByText('EMAIL ADDRESS');

    await user.click(within(drawer).getByRole('tab', { name: 'Scan History' }));
    expect(await within(drawer).findByRole('alert')).toHaveTextContent('Matters is not responding right now. Try again shortly.');
    await user.click(within(drawer).getByRole('tab', { name: 'Sensitive' }));
    expect(within(drawer).getByText('EMAIL ADDRESS')).toBeInTheDocument();
    expect(within(drawer).queryByRole('alert')).toBeNull();

    await user.click(within(drawer).getByRole('tab', { name: 'Scan History' }));
    expect(requestsTo('scan-history')).toHaveLength(1);
    answers['scan-history'] = () => HISTORY;
    await user.click(within(drawer).getByRole('button', { name: 'Retry' }));
    expect(await within(drawer).findByText('Scheduler')).toBeInTheDocument();
    expect(requestsTo('scan-history')).toHaveLength(2);
  });

  it('shows the PLATFORM-scope copy without Retry and the rate-limit copy with Retry', async () => {
    const user = userEvent.setup();
    answers.sensitives = fail(403, { code: 'FORBIDDEN_SCOPE', message: 'scope' });
    answers['scan-history'] = fail(429, { code: 'RATE_LIMITED', message: 'slow down', retryAfterSeconds: 30 });
    const { drawer } = renderDrawer();

    const scope = await within(drawer).findByRole('alert');
    expect(scope).toHaveTextContent('This Matters key needs the PLATFORM scope.');
    expect(within(scope).queryByRole('button', { name: 'Retry' })).toBeNull();
    // The other resources are unaffected: the header still shows the overview's console link.
    expect(await within(drawer).findByRole('link', { name: /Go to AWS/ })).toBeInTheDocument();

    await user.click(within(drawer).getByRole('tab', { name: 'Scan History' }));
    const limited = await within(drawer).findByRole('alert');
    expect(limited).toHaveTextContent('Too many requests. Try again in 30 seconds.');
    expect(within(limited).getByRole('button', { name: 'Retry' })).toBeInTheDocument();
  });

  describe('Scan Status chip', () => {
    it.each([
      ['SCAN_STOPPED is blue without a percentage', 'SCAN_STOPPED', 'Scan Stopped', 100, 'Scan Stopped', 'blue'],
      ['SCANNING_OUTPUT_PROCESSED is green with the floored progress', 'SCANNING_OUTPUT_PROCESSED', 'Scanning Output Processed', 99.6, 'Scanning Output Processed 99%', 'green'],
      ['another status is neutral, with the progress', 'SCANNING_FILES', 'Scanning Files', 42.9, 'Scanning Files 42%', 'neutral'],
    ])('%s', async (_name, status, label, progress, text, dot) => {
      answers.overview = () => ({ ...S3_OVERVIEW, scanningStatus: status, scanningStatusLabel: label });
      answers['scan-status'] = () => ({ progress });
      const { drawer } = renderDrawer();
      await within(drawer).findByRole('link', { name: /Go to AWS/ });
      await waitFor(() => expect(scanChipElement()).toHaveTextContent(text));
      expect(scanChipElement().textContent).toBe(text);
      expect(scanChipDot()).toBe(dot);
    });

    it.each([
      ['CLASSIFIED', 'Classified', 'green'],
      ['SCANNING', 'Scanning', 'blue'],
      ['NOT_SCANNED', 'Not Scanned', 'red'],
      ['FAILED', 'Failed', 'red'],
      ['STOPPED', 'Stopped', 'red'],
      ['QUEUED', 'Queued', 'neutral'],
    ])('falls back to the row label for a null status (%s)', async (status, label, dot) => {
      answers.overview = () => ({ ...S3_OVERVIEW, scanningStatus: null, scanningStatusLabel: null });
      const { drawer } = renderDrawer(drawerRow({ scan: { status, statusLabel: label, displayAt: null } }));
      await within(drawer).findByRole('link', { name: /Go to AWS/ });
      await act(async () => {});
      expect(scanChipElement().textContent).toBe(label);
      expect(scanChipDot()).toBe(dot);
    });

    it('falls back to the row when the overview fails, which Information reports', async () => {
      const user = userEvent.setup();
      answers.overview = fail(502, { code: 'BAD_UPSTREAM_RESPONSE', message: 'bad' });
      const { drawer } = renderDrawer(drawerRow({ scan: { status: 'NOT_SCANNED', statusLabel: 'Not Scanned', displayAt: null } }));
      await within(drawer).findByText('EMAIL ADDRESS');
      await act(async () => {});
      expect(scanChipElement().textContent).toBe('Not Scanned');
      expect(scanChipDot()).toBe('red');
      expect(within(drawer).queryByRole('link')).toBeNull();
      expect(within(drawer).getByRole('heading', { name: 'orders-archive' })).toBeInTheDocument();

      await user.click(within(drawer).getByRole('tab', { name: 'Information' }));
      expect(within(drawer).getByRole('alert')).toHaveTextContent('Matters is not responding right now. Try again shortly.');
    });

    it('drops only the percentage when the scan-status request fails', async () => {
      answers['scan-status'] = fail(502, { code: 'UPSTREAM_UNAVAILABLE', message: 'down' });
      const { drawer } = renderDrawer();
      await within(drawer).findByRole('link', { name: /Go to AWS/ });
      await act(async () => {});
      expect(scanChipElement().textContent).toBe('Scanning Output Processed');
      expect(scanChipDot()).toBe('green');
      expect(within(drawer).queryByRole('alert')).toBeNull();
    });
  });

  describe('Sensitive tab', () => {
    it('lists entities with the blank one last as "-" and its count, without chevron or chip', async () => {
      const user = userEvent.setup();
      const { drawer } = renderDrawer();
      const table = await within(drawer).findByRole('table');
      expect(headers(table)).toEqual(['Entities', 'Sensitives']);
      const rows = within(table).getAllByRole('row').slice(1) as HTMLTableRowElement[];
      expect(rows).toHaveLength(3);
      expect(rows[0]).toHaveTextContent('EMAIL ADDRESS');
      expect(rows[0].cells[1]).toHaveTextContent('1.2K');
      expect(within(rows[0]).getByText('Personal')).toBeInTheDocument();
      const blank = rows[2];
      expect(blank.cells[0].textContent).toBe('-');
      expect(blank.cells[1].textContent).toBe('0');
      expect(within(blank).queryByRole('button')).toBeNull();
      await user.click(blank);
      expect(requestsTo('sensitives/occurrences')).toHaveLength(0);
      expect(within(drawer).getByText('Showing 1 - 3 of 3')).toBeInTheDocument();
    });

    it('expands one entity at a time', async () => {
      const user = userEvent.setup();
      const { drawer } = renderDrawer();
      await user.click(await within(drawer).findByRole('button', { name: 'Expand EMAIL ADDRESS' }));
      await within(drawer).findByText('<<Jane Doe>>');
      await user.click(within(drawer).getByRole('button', { name: 'Expand PERSON NAME' }));
      expect(within(drawer).getByRole('button', { name: 'Collapse PERSON NAME' })).toBeInTheDocument();
      expect(within(drawer).getByRole('button', { name: 'Expand EMAIL ADDRESS' })).toBeInTheDocument();
      expect(last(requestsTo('sensitives/occurrences')).searchParams.get('entityName')).toBe('PERSON NAME');
    });

    it('sends showFalsePositives with every occurrence request and renders samples raw', async () => {
      const user = userEvent.setup();
      const { drawer } = renderDrawer();
      await user.click(await within(drawer).findByRole('button', { name: 'Expand EMAIL ADDRESS' }));

      expect(await within(drawer).findByText('<<Jane Doe>>')).toBeInTheDocument();
      expect(within(drawer).getByText('+4')).toBeInTheDocument();
      const first = last(requestsTo('sensitives/occurrences'));
      expect(first.searchParams.get('entityName')).toBe('EMAIL ADDRESS');
      expect(first.searchParams.get('skip')).toBe('0');
      expect(first.searchParams.get('limit')).toBe('10');
      expect(first.searchParams.get('showFalsePositives')).toBe('false');

      const nested = within(drawer).getAllByRole('table')[1];
      expect(headers(nested)).toEqual(['File Name', 'File Path', 'Sample Data', 'False Positive']);
      expect(within(nested).getByText('s3://orders-archive/exports/customers.csv'))
        .toHaveAttribute('title', 's3://orders-archive/exports/customers.csv');
      expect(within(nested).getAllByRole('button', { name: 'Copy path' })).toHaveLength(2);
      expect(within(nested).getByText('False positive')).toBeInTheDocument();
      const marks = within(nested).getAllByRole('button', { name: 'Mark' });
      expect(marks).toHaveLength(2);
      for (const mark of marks) expect(mark).toHaveAttribute('aria-disabled', 'true');
      expect(within(drawer).getByText('Showing 1 - 2 of 2')).toBeInTheDocument();

      await user.click(within(drawer).getByRole('switch', { name: 'Show False Positives' }));
      await waitFor(() => expect(last(requestsTo('sensitives/occurrences')).searchParams.get('showFalsePositives')).toBe('true'));
      expect(last(requestsTo('sensitives/occurrences')).searchParams.get('skip')).toBe('0');
      expect(within(drawer).getByRole('button', { name: 'Collapse EMAIL ADDRESS' })).toBeInTheDocument();
    });

    it('returns an occurrence page past the end to page 1', async () => {
      const user = userEvent.setup();
      const ten = Array.from({ length: 10 }, (_, i) => occurrence({ fileName: `part-${i}.csv`, url: `s3://orders-archive/part-${i}.csv` }));
      answers['sensitives/occurrences'] = url => (url.searchParams.get('skip') === '0'
        ? occurrencePage(url, ten, 25)
        : occurrencePage(url, [], 5));
      const { drawer } = renderDrawer();
      await user.click(await within(drawer).findByRole('button', { name: 'Expand EMAIL ADDRESS' }));
      await within(drawer).findByText('part-0.csv');

      await user.click(within(drawer).getByRole('button', { name: '3' }));
      await waitFor(() => expect(requestsTo('sensitives/occurrences').map(u => u.searchParams.get('skip'))).toEqual(['0', '20', '0']));
    });

    it('uses the database occurrence columns when only the overview says the schema can be discovered', async () => {
      const user = userEvent.setup();
      answers.overview = () => ({ ...S3_OVERVIEW, canDiscoverSchema: true });
      answers['sensitives/occurrences'] = url => occurrencePage(url, DB_OCCURRENCES);
      const { drawer } = renderDrawer();
      expect(await within(drawer).findByRole('button', { name: 'Table View' })).toBeInTheDocument();
      expect(within(drawer).queryByRole('button', { name: 'File View' })).toBeNull();

      await user.click(within(drawer).getByRole('button', { name: 'Expand EMAIL ADDRESS' }));
      await within(drawer).findByText('token_digest');
      const nested = within(drawer).getAllByRole('table')[1];
      expect(headers(nested)).toEqual(['Database', 'Schema', 'Table', 'Column', 'Sample Data', 'False Positive']);
      expect(within(nested).getAllByRole('row')[1].textContent).toContain('ledgerpublicpaymentstoken_digestab12cd34');
    });

    it('pages File View on the server, with "Columns 6", "-" placeholders and an empty entity chip', async () => {
      const user = userEvent.setup();
      const { drawer } = renderDrawer();
      await openFileView(user, drawer);

      const request = last(requestsTo('sensitives/files'));
      expect(request.searchParams.get('page')).toBe('1');
      expect(request.searchParams.get('pageSize')).toBe('10');
      expect(request.searchParams.has('q')).toBe(false);
      expect(within(drawer).getByRole('button', { name: /^Columns/ })).toHaveTextContent('6');
      const table = within(drawer).getByRole('table');
      expect(headers(table)).toEqual(['File', 'File Path', 'Sensitives', 'Category', 'Document Class', 'Entities']);
      const empty = within(table).getAllByRole('row')[2] as HTMLTableRowElement;
      expect([...empty.cells].map(cell => cell.textContent)).toEqual(['empty.csv', 'exports/empty.csv', '0Personal', '-', '-', '']);
      expect(within(empty).getByLabelText('Unnamed entity')).toBeInTheDocument();
      expect(within(drawer).queryByText(/Paging covers/)).toBeNull();
    });

    it('notes how far File View paging reaches when there are more files than that', async () => {
      const user = userEvent.setup();
      answers['sensitives/files'] = url => filePage(url, 20_000, true);
      const { drawer } = renderDrawer();
      await openFileView(user, drawer);
      expect(within(drawer).getByText('Paging covers about the first 10,000 files. Search by path to find others.')).toBeInTheDocument();
      expect(within(drawer).getByText('Showing the first 50,000 files.')).toBeInTheDocument();
    });

    it('searches File View by path after a pause, from page 1', async () => {
      const user = userEvent.setup();
      answers['sensitives/files'] = url => filePage(url, 30);
      const { drawer } = renderDrawer();
      await openFileView(user, drawer);
      await user.click(within(drawer).getByRole('button', { name: '2' }));
      await waitFor(() => expect(last(requestsTo('sensitives/files')).searchParams.get('page')).toBe('2'));

      const search = within(drawer).getByRole('searchbox', { name: 'Search file paths' });
      expect(search).toHaveAttribute('maxlength', '200');
      fireEvent.change(search, { target: { value: ' exports\t' } });
      await waitFor(() => expect(last(requestsTo('sensitives/files')).searchParams.get('q')).toBe('exports'));
      expect(last(requestsTo('sensitives/files')).searchParams.get('page')).toBe('1');
    });

    it('shows Table View for a database: Database select, findings and the false-positive filter', async () => {
      const user = userEvent.setup();
      answers.overview = () => POSTGRES_OVERVIEW;
      answers.sensitives = () => DB_SENSITIVES;
      const { drawer } = renderDrawer(ON_PREM_ROW);
      await within(drawer).findByText('SHA KEY');
      fireEvent.change(within(drawer).getByRole('searchbox', { name: 'Search entities' }), { target: { value: 'sha' } });

      await user.click(within(drawer).getByRole('button', { name: 'Table View' }));
      expect(within(drawer).getByRole('searchbox', { name: 'Search tables' })).toHaveValue('');
      const select = within(drawer).getByRole('combobox', { name: 'Database' }) as HTMLSelectElement;
      expect([...select.options].map(option => option.textContent)).toEqual(['ledger', 'reporting']);
      expect(select).toHaveValue('0');

      const table = within(drawer).getByRole('table');
      expect(headers(table)).toEqual(['Table', 'Sensitives']);
      expect(within(table).getByText('payments')).toBeInTheDocument();
      expect(within(table).getAllByText('public')).toHaveLength(2);
      expect(within(table).queryByRole('button', { name: 'Expand audit_log' })).toBeNull();

      await user.click(within(table).getByRole('button', { name: 'Expand payments' }));
      const findings = within(drawer).getAllByRole('table')[1];
      expect(headers(findings)).toEqual(['Column', 'Entity', 'Sensitives', 'False Positive']);
      expect(within(findings).getByText('token_digest')).toBeInTheDocument();
      expect(within(findings).queryByText('memo_text')).toBeNull();
      expect(within(findings).getByText('+1')).toBeInTheDocument();
      expect(within(findings).getByRole('button', { name: 'Edit entity' })).toHaveAttribute('aria-disabled', 'true');
      expect(within(findings).getByRole('button', { name: 'Mark' })).toHaveAttribute('aria-disabled', 'true');

      await user.click(within(drawer).getByRole('switch', { name: 'Show False Positives' }));
      expect(within(drawer).getByText('memo_text')).toBeInTheDocument();

      const tableSearch = within(drawer).getByRole('searchbox', { name: 'Search tables' });
      fireEvent.change(tableSearch, { target: { value: 'audit' } });
      expect(within(drawer).queryByText('payments')).toBeNull();
      expect(within(drawer).getByText('audit_log')).toBeInTheDocument();
      fireEvent.change(tableSearch, { target: { value: '' } });

      await user.selectOptions(select, '1');
      expect(within(drawer).getByText('daily_totals')).toBeInTheDocument();
      expect(requestsTo('sensitives/occurrences')).toHaveLength(0);
    });

    it('shows the empty state when there is nothing to show', async () => {
      answers.sensitives = () => ({ database: false, entities: [], databases: [] });
      const { drawer } = renderDrawer();
      const table = await within(drawer).findByRole('table');
      expect(headers(table)).toEqual(['Entities', 'Sensitives']);
      expect(within(table).getByText('Scan this datastore to see the results here.')).toBeInTheDocument();
      expect(within(drawer).getByText('Rows Per Page')).toBeInTheDocument();
      expect(within(drawer).getByDisplayValue('10')).toBeInTheDocument();
      expect(within(drawer).getByRole('button', { name: 'Previous page' })).toBeDisabled();
      expect(within(drawer).getByRole('button', { name: 'Next page' })).toBeDisabled();
    });
  });

  describe('Information tab', () => {
    it('shows the not-scanned state of an Azure container', async () => {
      const user = userEvent.setup();
      answers.overview = () => AZURE_NOT_SCANNED;
      answers.sensitives = () => ({ database: false, entities: [], databases: [] });
      const { drawer } = renderDrawer(AZURE_ROW);
      await user.click(within(drawer).getByRole('tab', { name: 'Information' }));

      const sensitives = await within(drawer).findByRole('region', { name: 'Sensitives' });
      expect(within(sensitives).getByText('Total Sensitive Records').nextElementSibling?.textContent).toBe('-');
      expect(within(sensitives).getByText('No sensitive records found')).toBeInTheDocument();

      const files = within(drawer).getByRole('region', { name: 'File Summary' });
      expect(within(files).getByText('Total Files').nextElementSibling?.textContent).toBe('0');
      const chips = [...files.querySelectorAll('[data-file-type]')];
      expect(chips).toHaveLength(17);
      expect(chips.every(chip => chip.getAttribute('data-empty') === 'true')).toBe(true);
      expect(chips[0]).toHaveTextContent('Autocad 0');
      expect(chips[16]).toHaveTextContent('XLSX 0');

      const properties = within(drawer).getByRole('region', { name: 'Properties' });
      expect([...properties.querySelectorAll('dt')].map(dt => dt.textContent))
        .toEqual(['Region', 'Datastore Size', 'Subscription ID', 'Resource Group', 'Storage Account']);
      expect(within(properties).getByText('0 Bytes')).toBeInTheDocument();
      expect(within(drawer).queryByRole('region', { name: 'Security Configurations' })).toBeNull();

      expect(scanChipElement().textContent).toBe('Not Scanned');
      expect(scanChipDot()).toBe('red');
      expect(within(drawer).getByRole('link', { name: /Go to console/ })).toBeInTheDocument();
    });

    it('colours security settings green only for "Enabled" and keeps the DTO order of properties', async () => {
      const user = userEvent.setup();
      const { drawer } = renderDrawer();
      await user.click(within(drawer).getByRole('tab', { name: 'Information' }));

      const security = await within(drawer).findByRole('region', { name: 'Security Configurations' });
      const dot = (value: string) => within(security).getByText(value).querySelector('[data-dot]')?.getAttribute('data-dot');
      expect(dot('Enabled')).toBe('green');
      expect(dot('Sse S3')).toBe('red');
      expect(dot('Disabled')).toBe('red');

      const properties = within(drawer).getByRole('region', { name: 'Properties' });
      expect([...properties.querySelectorAll('dt')].map(dt => dt.textContent)).toEqual(['Region', 'Datastore Size', 'ARN']);
      expect(within(properties).getByText('14.39 GB')).toBeInTheDocument();
      expect(within(properties).getByText('arn:aws:s3:::orders-archive')).toHaveClass('break-all');

      const files = within(drawer).getByRole('region', { name: 'File Summary' });
      expect(within(files).getByText('Total Files').nextElementSibling?.textContent).toBe('4.4K');
      const sensitives = within(drawer).getByRole('region', { name: 'Sensitives' });
      expect(within(sensitives).getByText('Total Sensitive Records').nextElementSibling?.textContent).toBe('1.56K');
      expect(within(sensitives).getByText('EMAIL ADDRESS').closest('span.inline-flex.items-center')).toHaveTextContent('EMAIL ADDRESS 1.2K');
    });

    it('shows only Sensitives and Properties for a database', async () => {
      const user = userEvent.setup();
      answers.overview = () => POSTGRES_OVERVIEW;
      answers.sensitives = () => DB_SENSITIVES;
      const { drawer } = renderDrawer(ON_PREM_ROW);
      await user.click(within(drawer).getByRole('tab', { name: 'Information' }));
      await within(drawer).findByRole('region', { name: 'Sensitives' });
      expect(within(drawer).getAllByRole('region').map(region => region.querySelector('h4')?.textContent))
        .toEqual(['Sensitives', 'Properties']);
      const properties = within(drawer).getByRole('region', { name: 'Properties' });
      expect([...properties.querySelectorAll('dt')].map(dt => dt.textContent)).toEqual(['Host', 'Port']);
      expect(within(properties).getByText('5432')).toBeInTheDocument();
    });
  });

  describe('Escape', () => {
    it('hides the Start Scan tooltip first and closes the drawer on the next Escape', async () => {
      const user = userEvent.setup();
      const { drawer, onClose } = renderDrawer();
      await within(drawer).findByText('EMAIL ADDRESS');
      const startScan = within(drawer).getByRole('button', { name: 'Start Scan' });
      act(() => startScan.focus());
      const tooltip = document.getElementById(startScan.getAttribute('aria-describedby') ?? '') as HTMLElement;
      expect(tooltip).toBeVisible();

      await user.keyboard('{Escape}');
      expect(tooltip).not.toBeVisible();
      expect(onClose).not.toHaveBeenCalled();

      await user.keyboard('{Escape}');
      expect(onClose).toHaveBeenCalledTimes(1);
    });

    it('closes the drawer on Escape fired on the body after File View reached its last page', async () => {
      const user = userEvent.setup();
      answers['sensitives/files'] = url => filePage(url, 15);
      const { drawer, onClose } = renderDrawer();
      await openFileView(user, drawer);

      await user.click(within(drawer).getByRole('button', { name: 'Next page' }));
      await waitFor(() => expect(last(requestsTo('sensitives/files')).searchParams.get('page')).toBe('2'));
      expect(within(drawer).getByRole('button', { name: 'Next page' })).toBeDisabled();

      fireEvent.keyDown(document.body, { key: 'Escape' });
      expect(onClose).toHaveBeenCalledTimes(1);
    });

    it('closes an open File View column menu before the drawer', async () => {
      const user = userEvent.setup();
      const { drawer, onClose } = renderDrawer();
      await openFileView(user, drawer);
      await user.click(within(drawer).getByRole('button', { name: /^Columns/ }));
      expect(within(drawer).getByText('Always shown')).toBeInTheDocument();

      await user.keyboard('{Escape}');
      expect(within(drawer).queryByText('Always shown')).toBeNull();
      expect(onClose).not.toHaveBeenCalled();

      await user.keyboard('{Escape}');
      expect(onClose).toHaveBeenCalledTimes(1);
    });

    it('closes the column menu first when focus moved to the drawer itself, and refocuses its button (R5)', async () => {
      const user = userEvent.setup();
      const { drawer, onClose } = renderDrawer();
      await openFileView(user, drawer);
      const columns = within(drawer).getByRole('button', { name: /^Columns/ });
      await user.click(columns);
      expect(within(drawer).getByText('Always shown')).toBeInTheDocument();

      act(() => drawer.focus());
      expect(drawer).toHaveFocus();
      fireEvent.keyDown(drawer, { key: 'Escape' });

      expect(within(drawer).queryByText('Always shown')).toBeNull();
      expect(columns).toHaveFocus();
      expect(onClose).not.toHaveBeenCalled();
      expect(screen.getByRole('dialog', { name: 'orders-archive' })).toBeInTheDocument();
    });

    it('closes on the overlay and the close button', async () => {
      const user = userEvent.setup();
      const { drawer, onClose } = renderDrawer();
      await within(drawer).findByText('EMAIL ADDRESS');
      await user.click(within(drawer).getByRole('button', { name: 'Close' }));
      expect(onClose).toHaveBeenCalledTimes(1);
      await user.click(drawer.previousElementSibling as HTMLElement);
      expect(onClose).toHaveBeenCalledTimes(2);
    });
  });
});
