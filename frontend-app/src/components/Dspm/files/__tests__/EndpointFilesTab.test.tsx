import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';

vi.mock('../../../../utils/apiClient', () => ({ default: { get: vi.fn() } }));

import apiClient from '../../../../utils/apiClient';
import type { DatastoreFilterOptions } from '../../../../types/dspmDatastores';
import type { EndpointFilePage, EndpointFileRow } from '../../../../types/dspmDrawers';
import type { EndpointFileOverview } from '../../../../types/dspmFiles';
import EndpointFilesTab from '../EndpointFilesTab';

const get = apiClient.get as unknown as ReturnType<typeof vi.fn>;

// ─── synthetic data (shapes only; no captured values) ────────────────────────────────────────────

/** Epoch seconds of a local time, so the expectations hold in every time zone. */
const local = (y: number, mo: number, d: number, h = 0, mi = 0) => new Date(y, mo - 1, d, h, mi).getTime() / 1000;

const uuidOf = (n: number) => `00000000-0000-4000-8000-${n.toString(16).padStart(12, '0')}`;

function fileRow(n: number, over: Partial<EndpointFileRow> = {}): EndpointFileRow {
  return {
    id: uuidOf(n), name: `file-${n}.txt`, extension: 'txt', path: `/Users/tester/Documents/file-${n}.txt`, user: 'tester',
    sizeBytes: 2_048, sensitiveCount: 1, labels: ['Personal'], categoryLabel: 'It & Technical',
    documentClassLabel: 'System Documentation', tags: ['internal'], modifiedAt: local(2026, 8, 20, 9, 0),
    scannedAt: local(2026, 8, 21, 9, 0), ...over,
  };
}

/** Design 9.2's row (AC12) with invented values, and Last Modified with a time (C14). */
const CONTACTS = fileRow(1, {
  name: 'contacts.json', extension: 'json', path: '/Users/priya/Documents/contacts.json', user: 'priya', sizeBytes: 412,
  sensitiveCount: 3, labels: ['Personal'], categoryLabel: 'Other', documentClassLabel: 'Other', tags: [],
  modifiedAt: local(2026, 9, 1, 17, 30),
});
/** A size of 0, no user and no time (the backend sends null for a non-positive one). */
const EMPTY_FILE = fileRow(2, { name: 'empty.log', extension: 'log', user: null, sizeBytes: 0, modifiedAt: null });

/** `total` files: row 1 is CONTACTS, row 2 EMPTY_FILE, the others `page-<p>-row-<r>.txt`. */
function pageOf(url: URL, total = 27): EndpointFilePage {
  const skip = Number(url.searchParams.get('skip'));
  const limit = Number(url.searchParams.get('limit'));
  const rows = Array.from({ length: Math.max(0, Math.min(limit, total - skip)) }, (_, i) => {
    const n = skip + i + 1;
    if (n === 1) return CONTACTS;
    if (n === 2) return EMPTY_FILE;
    return fileRow(n, { name: `page-${Math.floor((n - 1) / limit) + 1}-row-${((n - 1) % limit) + 1}.txt` });
  });
  return { rows, skip, limit, total };
}

const opt = (value: string, label = value) => ({ value, label, count: null });

const OPTIONS: DatastoreFilterOptions = {
  dimensions: [
    { key: 'category', hasMore: false, options: [opt('it & technical', 'It & Technical'), opt('legal, finance', 'Legal, Finance')] },
    { key: 'sensitives', hasMore: false, options: [
      opt('PII', 'Personal'), opt('PCI', 'Financial'), opt('PHI', 'Health'), opt('SECRETS', 'IT & Security'),
    ] },
    { key: 'documentClass', hasMore: false, options: [opt('system documentation', 'System Documentation'), opt('payroll', 'Payroll')] },
    { key: 'dataElement', hasMore: false, options: [opt('EMAIL ADDRESS', 'Email address'), opt('PERSON NAME', 'Person name')] },
    { key: 'tag', hasMore: false, options: [opt('confidential')] },
    { key: 'uid', hasMore: false, options: [opt('501')] },
    { key: 'hostname', hasMore: false, options: [opt('build-laptop-07.local')] },
    { key: 'lastModified', hasMore: false, options: [opt('48h', 'Last 48 hours'), opt('7d', 'Last 7 days'), opt('30d', 'Last 30 days')] },
  ],
};

/** The opened file's overview, named after the row it was opened from. */
function overviewOf(url: URL): EndpointFileOverview {
  const name = url.pathname.includes(uuidOf(2)) ? 'empty.log' : 'contacts.json';
  return {
    name, extension: name.split('.')[1], owner: 'priya', categoryLabel: 'Other', documentClassLabel: 'other',
    sensitiveRecords: 3, path: `/Users/priya/Documents/${name}`, sizeBytes: 412, device: 'build-laptop-07',
    modifiedAt: local(2026, 9, 1, 17, 30), fileCreatedAt: null, accessedAt: null, tags: [], sensitives: [],
  };
}

type Answer = (url: URL) => unknown;
let listAnswer: Answer;
let optionsAnswer: Answer;

const FILE_ANSWERS: Record<string, Answer> = {
  overview: overviewOf,
  activity: () => ({ entries: [] }),
  lineage: () => ({ asset: null, copies: { available: true, coverageNote: null, lanes: [], events: [] } }),
  copies: () => ({ available: true, copies: [], locations: 1 }),
};

const httpError = (status: number, code: string) =>
  new Error(`HTTP ${status}: ${JSON.stringify({ code, message: 'synthetic failure' })}`);

const LIST_PATH = '/api/dspm/files/endpoint';
const OPTIONS_PATH = '/api/dspm/files/endpoint/filter-options';
const FILE_PATH = /^\/api\/dspm\/endpoint-files\/([0-9a-f-]{36})\/([a-z]+)$/;

const urls = () => get.mock.calls.map(([raw]) => new URL(String(raw), 'http://localhost'));
const listRequests = () => urls().filter(url => url.pathname === LIST_PATH);
const lastList = () => listRequests()[listRequests().length - 1];
const optionRequests = () => urls().filter(url => url.pathname === OPTIONS_PATH);
const skips = () => listRequests().map(url => url.searchParams.get('skip'));

beforeEach(() => {
  listAnswer = url => pageOf(url);
  optionsAnswer = () => OPTIONS;
  get.mockImplementation(async (raw: string) => {
    const url = new URL(raw, 'http://localhost');
    if (url.pathname === LIST_PATH) return listAnswer(url);
    if (url.pathname === OPTIONS_PATH) return optionsAnswer(url);
    const match = FILE_PATH.exec(url.pathname);
    if (match && FILE_ANSWERS[match[2]]) return FILE_ANSWERS[match[2]](url);
    throw new Error(`HTTP 404: unexpected ${raw}`);
  });
});

afterEach(() => {
  vi.useRealTimers();
  vi.clearAllMocks();
});

const LocationProbe = () => {
  const location = useLocation();
  return <div data-testid="location">{location.pathname + location.search}</div>;
};

function renderTab(url = '/xamlens/files/endpoint') {
  return render(
    <MemoryRouter initialEntries={[url]}>
      <Routes>
        <Route path="/xamlens/files/:filesTab" element={<EndpointFilesTab />} />
      </Routes>
      <LocationProbe />
    </MemoryRouter>,
  );
}

const table = () => screen.getByRole('table');
const tableRow = (name: string) =>
  within(table()).getByText(name, { selector: 'span[title]' }).closest('tr') as HTMLTableRowElement;
const pager = () => screen.getByRole('navigation', { name: 'Pagination' });
const pagerButton = (name: string) => within(pager()).getByRole('button', { name });
const footer = () => pager().parentElement!.parentElement as HTMLElement;
const currentPage = () => footer().querySelector('[aria-current="page"]')?.textContent;
const location = () => screen.getByTestId('location');
const dropdown = (name: string) => screen.getByRole('button', { name: new RegExp(`^${name}`) });

/** A list answer that waits for `release`. */
function deferList(answer: (url: URL) => EndpointFilePage = url => pageOf(url)) {
  const pending: Array<() => void> = [];
  listAnswer = url => new Promise(resolve => { pending.push(() => resolve(answer(url))); });
  return () => pending.splice(0).forEach(release => release());
}

// ─── tests ───────────────────────────────────────────────────────────────────────────────────────

describe('EndpointFilesTab list', () => {
  it('asks for skip 0 and limit 10 first, and the options once', async () => {
    renderTab();
    await screen.findByText('contacts.json');
    expect(listRequests().map(url => url.search)).toEqual(['?skip=0&limit=10']);
    expect(optionRequests().map(url => url.search)).toEqual(['']);
  });

  it('draws the cells: the path tooltip, date and time, "-" and "0 Bytes", two sticky cells and an empty lineage header', async () => {
    renderTab();
    await screen.findByText('contacts.json');
    const headers = within(table()).getAllByRole('columnheader');
    expect(headers.map(th => th.textContent)).toEqual([
      'File Name', '', 'File Path', 'Users', 'File Size', 'Sensitives', 'Category', 'Document Class', 'Matters Tags',
      'Last Modified',
    ]);
    expect(headers[1]).toHaveAttribute('aria-label', 'Lineage');
    expect(headers[0]).toHaveClass('sticky', 'left-0', 'z-10');
    expect(headers[1]).toHaveClass('sticky', 'left-56', 'z-10');

    const cells = Array.from(tableRow('contacts.json').cells);
    expect(cells[0]).toHaveClass('sticky', 'left-0', 'bg-white');
    expect(cells[1]).toHaveClass('sticky', 'left-56', 'bg-white');
    expect(cells[2]).not.toHaveClass('sticky');
    expect(within(cells[0]).getByText('contacts.json')).toHaveAttribute('title', '/Users/priya/Documents/contacts.json');
    expect(cells.slice(2).map(cell => cell.textContent)).toEqual([
      '/Users/priya/Documents/contacts.json', 'priya', '412 Bytes', '3Personal', 'Other', 'Other', '-', '01 Sep 2026, 05:30 PM',
    ]);

    const empty = Array.from(tableRow('empty.log').cells);
    expect(empty[3]).toHaveTextContent(/^-$/);
    expect(empty[4]).toHaveTextContent(/^0 Bytes$/);
    expect(empty[9]).toHaveTextContent(/^-$/);
  });

  it('shows "Columns 9" with File Name always shown, and hides a column', async () => {
    const user = userEvent.setup();
    renderTab();
    await screen.findByText('contacts.json');
    await user.click(screen.getByRole('button', { name: 'Columns 9' }));
    expect(screen.getByRole('checkbox', { name: /File Name/ })).toBeDisabled();
    await user.click(screen.getByRole('checkbox', { name: /Users/ }));
    expect(screen.getByRole('button', { name: 'Columns 8' })).toBeInTheDocument();
    expect(within(table()).queryByRole('columnheader', { name: 'Users' })).toBeNull();
  });
});

describe('EndpointFilesTab file drawer', () => {
  const fileRequests = (endpoint: string) =>
    urls().filter(url => FILE_PATH.exec(url.pathname)?.[2] === endpoint).map(url => FILE_PATH.exec(url.pathname)?.[1]);

  it('opens the Endpoint file drawer from a row on Sensitives, and Escape returns focus to the row', async () => {
    const user = userEvent.setup();
    renderTab();
    await screen.findByText('contacts.json');
    const row = tableRow('contacts.json');
    expect(row).toHaveAttribute('tabindex', '0');
    await user.click(row);
    const drawer = await screen.findByRole('dialog', { name: 'contacts.json' });
    expect(within(drawer).getByRole('tab', { name: 'Sensitives' })).toHaveAttribute('aria-selected', 'true');
    await waitFor(() => expect(fileRequests('overview')).toEqual([uuidOf(1)]));
    await user.keyboard('{Escape}');
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'contacts.json' })).toBeNull());
    expect(tableRow('contacts.json')).toHaveFocus();
  });

  it('opens it on Lineage from the lineage icon, and on Sensitives with Enter on a focused row', async () => {
    const user = userEvent.setup();
    renderTab();
    await screen.findByText('contacts.json');
    const button = within(tableRow('contacts.json')).getByRole('button', { name: 'View lineage for contacts.json' });
    expect(button).toHaveAttribute('title', 'View lineage');
    await user.click(button);
    const drawer = await screen.findByRole('dialog', { name: 'contacts.json' });
    expect(within(drawer).getByRole('tab', { name: 'Lineage' })).toHaveAttribute('aria-selected', 'true');
    await waitFor(() => expect(fileRequests('lineage')).toEqual([uuidOf(1)]));
    await user.click(within(drawer).getByRole('button', { name: 'Close' }));
    expect(screen.queryByRole('dialog', { name: 'contacts.json' })).toBeNull();
    expect(tableRow('contacts.json')).toHaveFocus();

    tableRow('empty.log').focus();
    await user.keyboard('{Enter}');
    const next = await screen.findByRole('dialog', { name: 'empty.log' });
    expect(within(next).getByRole('tab', { name: 'Sensitives' })).toHaveAttribute('aria-selected', 'true');
  });

  it('leaves a row without a UUID id inert, with no lineage icon', async () => {
    const user = userEvent.setup();
    listAnswer = () => ({
      rows: [fileRow(1, { id: 'not-a-uuid', name: 'orphan.txt' }), fileRow(2, { id: null, name: 'no-id.txt' })],
      skip: 0, limit: 10, total: 2,
    });
    renderTab();
    await screen.findByText('orphan.txt');
    for (const name of ['orphan.txt', 'no-id.txt']) {
      const row = tableRow(name);
      expect(row).not.toHaveAttribute('tabindex');
      expect(within(row).queryByRole('button', { name: /lineage/i })).toBeNull();
      await user.click(row);
    }
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(fileRequests('overview')).toEqual([]);
  });
});

describe('EndpointFilesTab paging', () => {
  it('reads "Showing 1 - 10 of 27" with pages 1, 2 and 3; page 2 sends skip=10 and is kept in the URL', async () => {
    const user = userEvent.setup();
    renderTab();
    await screen.findByText('contacts.json');
    expect(footer()).toHaveTextContent('Showing 1 - 10 of 27');
    expect(within(pager()).getAllByRole('button').map(b => b.getAttribute('aria-label') ?? b.textContent))
      .toEqual(['Previous page', '1', '2', '3', 'Next page']);
    expect(currentPage()).toBe('1');

    await user.click(pagerButton('2'));
    await screen.findByText('page-2-row-1.txt');
    expect(lastList().search).toBe('?skip=10&limit=10');
    expect(footer()).toHaveTextContent('Showing 11 - 20 of 27');
    expect(currentPage()).toBe('2');
    expect(location()).toHaveTextContent('/xamlens/files/endpoint?page=2');

    await user.click(pagerButton('1'));
    await screen.findByText('contacts.json');
    expect(location()).toHaveTextContent(/^\/xamlens\/files\/endpoint$/);
  });

  it('keeps page 2 on screen when page 3 fails; "›" and "3" send skip=20 again, and its success reads 21 - 27', async () => {
    const user = userEvent.setup();
    let failThird = true;
    listAnswer = url => {
      if (url.searchParams.get('skip') === '20' && failThird) throw httpError(502, 'UPSTREAM_UNAVAILABLE');
      return pageOf(url);
    };
    renderTab();
    await screen.findByText('contacts.json');
    await user.click(pagerButton('2'));
    await screen.findByText('page-2-row-1.txt');
    await user.click(pagerButton('3'));
    expect(await screen.findByRole('alert')).toBeInTheDocument();
    expect(footer()).toHaveTextContent('Showing 11 - 20 of 27');
    expect(currentPage()).toBe('2');
    expect(screen.getByText('page-2-row-1.txt')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Next page' }));
    await waitFor(() => expect(skips().filter(skip => skip === '20')).toHaveLength(2));
    expect(currentPage()).toBe('2');

    failThird = false;
    await user.click(pagerButton('3'));
    await screen.findByText('page-3-row-1.txt');
    expect(skips()).toEqual(['0', '10', '20', '20', '20']);
    expect(footer()).toHaveTextContent('Showing 21 - 27 of 27');
    expect(currentPage()).toBe('3');
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('reads ?page=2 on mount, and starts at page 1 for a page it cannot read', async () => {
    const first = renderTab('/xamlens/files/endpoint?page=2');
    await screen.findByText('page-2-row-1.txt');
    expect(listRequests().map(url => url.search)).toEqual(['?skip=10&limit=10']);
    expect(footer()).toHaveTextContent('Showing 11 - 20 of 27');
    expect(location()).toHaveTextContent('/xamlens/files/endpoint?page=2');
    first.unmount();
    get.mockClear();
    renderTab('/xamlens/files/endpoint?page=0');
    await screen.findByText('contacts.json');
    expect(listRequests().map(url => url.search)).toEqual(['?skip=0&limit=10']);
    await waitFor(() => expect(location()).toHaveTextContent(/^\/xamlens\/files\/endpoint$/));
  });

  it('asks for page 1 when the total shrank under the page, keeping the page on screen and no range past the total', async () => {
    const user = userEvent.setup();
    renderTab();
    await screen.findByText('contacts.json');
    const release = deferList(url => (url.searchParams.get('skip') === '20'
      ? { rows: [], skip: 20, limit: 10, total: 15 }
      : pageOf(url, 15)));
    await user.click(pagerButton('3'));
    await waitFor(() => expect(skips()).toEqual(['0', '20']));
    release();
    await waitFor(() => expect(skips()).toEqual(['0', '20', '0']));
    // The answer for page 1 is still on its way: the rows and range on screen are the first page's.
    expect(footer()).toHaveTextContent('Showing 1 - 10 of 27');
    expect(screen.getByText('contacts.json')).toBeInTheDocument();
    expect(screen.queryByText(/Showing 21/)).toBeNull();
    release();
    await waitFor(() => expect(footer()).toHaveTextContent('Showing 1 - 10 of 15'));
    expect(currentPage()).toBe('1');
    expect(screen.queryByRole('alert')).toBeNull();
    expect(location()).toHaveTextContent(/^\/xamlens\/files\/endpoint$/);
  });

  it('clears the table to "Loading files…" on a filter change and asks for skip=0', async () => {
    const user = userEvent.setup();
    renderTab();
    await screen.findByText('contacts.json');
    await user.click(pagerButton('2'));
    await screen.findByText('page-2-row-1.txt');
    const release = deferList();
    await user.click(dropdown('Sensitives'));
    await user.click(screen.getByRole('checkbox', { name: 'Personal' }));
    expect(await screen.findByText('Loading files…')).toBeInTheDocument();
    expect(lastList().searchParams.get('skip')).toBe('0');
    expect(lastList().searchParams.get('sensitives')).toBe('PII');
    expect(footer()).not.toHaveTextContent('Showing');
    expect(location()).toHaveTextContent(/^\/xamlens\/files\/endpoint$/);
    release();
    await screen.findByText('contacts.json');
  });

  it('keeps the footer and keyboard focus on Rows Per Page, with no range until the first page arrives', async () => {
    renderTab();
    await screen.findByText('contacts.json');
    const release = deferList();
    const select = screen.getByRole('combobox', { name: 'Rows Per Page' });
    select.focus();
    fireEvent.change(select, { target: { value: '25' } });
    await waitFor(() => expect(lastList().search).toBe('?skip=0&limit=25'));
    expect(footer()).not.toHaveTextContent('Showing');
    expect(screen.getByRole('combobox', { name: 'Rows Per Page' })).toBe(select);
    expect(select).toHaveFocus();
    expect(location()).toHaveTextContent('/xamlens/files/endpoint?pageSize=25');
    release();
    await waitFor(() => expect(footer()).toHaveTextContent('Showing 1 - 25 of 27'));
    expect(select).toHaveFocus();
  });
});

describe('EndpointFilesTab filters', () => {
  it('sends sensitives, documentClass with several values and dataElement by value under its label', async () => {
    const user = userEvent.setup();
    renderTab();
    await screen.findByText('contacts.json');
    await user.click(dropdown('Sensitives'));
    await user.click(screen.getByRole('checkbox', { name: 'Personal' }));
    await waitFor(() => expect(lastList().searchParams.get('sensitives')).toBe('PII'));
    await user.click(dropdown('Document Class'));
    await user.click(screen.getByRole('checkbox', { name: 'System Documentation' }));
    await user.click(screen.getByRole('checkbox', { name: 'Payroll' }));
    await waitFor(() => expect(lastList().searchParams.get('documentClass')).toBe('system documentation,payroll'));
    await user.click(dropdown('Data Elements'));
    expect(screen.getByRole('checkbox', { name: 'Person name' })).toBeInTheDocument();
    await user.click(screen.getByRole('checkbox', { name: 'Email address' }));
    await waitFor(() => expect(lastList().searchParams.get('dataElement')).toBe('EMAIL ADDRESS'));
    expect(lastList().searchParams.get('skip')).toBe('0');
  });

  it('sends tag, uid, hostname and lastModified from More filters, never user, and disables a comma option', async () => {
    const user = userEvent.setup();
    renderTab();
    await screen.findByText('contacts.json');
    await user.click(screen.getByRole('button', { name: 'More filters' }));
    const panel = screen.getByRole('dialog', { name: 'More filters' });
    await user.click(within(panel).getByRole('button', { name: 'Category' }));
    expect(within(panel).getByRole('checkbox', { name: 'Legal, Finance' })).toBeDisabled();
    await user.click(within(panel).getByRole('button', { name: 'Matters Tags' }));
    await user.click(within(panel).getByRole('checkbox', { name: 'confidential' }));
    await waitFor(() => expect(lastList().searchParams.get('tag')).toBe('confidential'));
    await user.click(within(panel).getByRole('button', { name: 'UID' }));
    await user.click(within(panel).getByRole('checkbox', { name: '501' }));
    await waitFor(() => expect(lastList().searchParams.get('uid')).toBe('501'));
    await user.click(within(panel).getByRole('button', { name: 'Host Name' }));
    await user.click(within(panel).getByRole('checkbox', { name: 'build-laptop-07.local' }));
    await waitFor(() => expect(lastList().searchParams.get('hostname')).toBe('build-laptop-07.local'));
    await user.click(within(panel).getByRole('button', { name: 'Last Modified' }));
    await user.click(within(panel).getByRole('option', { name: 'Last 7 days' }));
    await waitFor(() => expect(lastList().searchParams.get('lastModified')).toBe('7d'));
    expect(lastList().searchParams.get('tag')).toBe('confidential');
    expect(listRequests().every(url => !url.searchParams.has('user'))).toBe(true);
  });
});

describe('EndpointFilesTab "New" badge', () => {
  it('follows the name of a file modified 47 hours ago, and not of one modified 49 hours ago', async () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date(2026, 10, 17, 12, 0));
    const now = Date.now() / 1000;
    listAnswer = () => ({
      rows: [
        fileRow(1, { name: 'recent.txt', modifiedAt: now - 47 * 3600 }),
        fileRow(2, { name: 'older.txt', modifiedAt: now - 49 * 3600 }),
      ],
      skip: 0, limit: 10, total: 2,
    });
    renderTab();
    await screen.findByText('recent.txt');
    const badge = within(tableRow('recent.txt').cells[0]).getByText('New');
    expect(badge).toHaveAttribute('title', 'Modified in the last 48 hours');
    expect(within(tableRow('older.txt').cells[0]).queryByText('New')).toBeNull();
  });
});

describe('EndpointFilesTab empty and error states', () => {
  it('reads "No files yet" without a search or filter, with no range', async () => {
    listAnswer = () => ({ rows: [], skip: 0, limit: 10, total: 0 });
    renderTab();
    expect(await screen.findByText('No files yet')).toBeInTheDocument();
    expect(screen.getByText('Files appear here once a scan has found them.')).toBeInTheDocument();
    expect(footer()).not.toHaveTextContent('Showing');
  });

  it('reads "No files match these filters" with a search', async () => {
    const user = userEvent.setup();
    listAnswer = () => ({ rows: [], skip: 0, limit: 10, total: 0 });
    renderTab();
    await screen.findByText('No files yet');
    await user.type(screen.getByPlaceholderText('Search Files'), 'zzz');
    expect(await screen.findByText('No files match these filters')).toBeInTheDocument();
    expect(screen.getByText('Try clearing the search or filters.')).toBeInTheDocument();
    expect(lastList().searchParams.get('q')).toBe('zzz');
  });

  it('shows the error banner and retries the same page', async () => {
    const user = userEvent.setup();
    let fail = true;
    listAnswer = url => {
      if (fail) throw httpError(502, 'UPSTREAM_UNAVAILABLE');
      return pageOf(url);
    };
    renderTab();
    const alert = await screen.findByRole('alert');
    expect(footer()).not.toHaveTextContent('Showing');
    fail = false;
    await user.click(within(alert).getByRole('button', { name: 'Retry' }));
    await screen.findByText('contacts.json');
    expect(listRequests().map(url => url.search)).toEqual(['?skip=0&limit=10', '?skip=0&limit=10']);
    expect(screen.queryByRole('alert')).toBeNull();
  });
});
