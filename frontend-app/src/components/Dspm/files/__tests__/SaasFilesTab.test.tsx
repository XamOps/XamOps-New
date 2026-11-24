import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';

vi.mock('../../../../utils/apiClient', () => ({ default: { get: vi.fn() } }));

import apiClient from '../../../../utils/apiClient';
import type { DatastoreFilterOptions } from '../../../../types/dspmDatastores';
import type { SaasFileListPage, SaasFileListRow } from '../../../../types/dspmFiles';
import SaasFilesTab from '../SaasFilesTab';
import { OWNER, copies, driveOverview, lineage, overview } from '../../datastores/saas/__tests__/saasTestData';

const get = apiClient.get as unknown as ReturnType<typeof vi.fn>;

// ─── synthetic data (shapes only; no captured values) ────────────────────────────────────────────

/** Epoch seconds of a local time, so the expectations hold in every time zone. */
const local = (y: number, mo: number, d: number, h = 0, mi = 0) => new Date(y, mo - 1, d, h, mi).getTime() / 1000;

const idOf = (n: number) => n.toString(16).padStart(24, '0');
const PRIVATE = [{ code: 'PRIVATE', label: 'Private' }];

function row(n: number, over: Partial<SaasFileListRow> = {}): SaasFileListRow {
  return {
    id: idOf(n), name: `statement-${n}.pdf`, extension: 'pdf', source: 'GMAIL', sourceLabel: 'Gmail',
    categoryLabel: 'Financial', documentClassLabel: 'Invoice', sharingModes: PRIVATE, owner: OWNER,
    lastModifiedAt: local(2026, 9, 10, 17, 56), ...over,
  };
}

const DRIVE_ROW = row(1, {
  name: 'Offer Letter', extension: 'docx', source: 'GOOGLE_WORKSPACE', sourceLabel: 'Google Drive',
  categoryLabel: 'IT & Technical', documentClassLabel: 'System Documentation',
  sharingModes: [{ code: 'EXTERNAL', label: 'Externally Shared' }, { code: 'INTERNAL', label: 'Internally Shared' }],
});
const PUBLIC_ROW = row(2, {
  name: 'Brochure', sharingModes: [
    { code: 'PUBLIC', label: 'Anyone with the link' }, { code: 'INTERNAL', label: 'Internally Shared' },
  ],
});
const GMAIL_ROW = row(3, { name: 'Re: quarterly numbers', extension: 'Re: quarterly numbers', categoryLabel: 'Other', owner: null });
const INERT_ROW = row(4, { id: 'not-a-hex-id', name: 'Orphan' });

/** Pages of ten rows: cursor null → rows 1..10 (next c2), c2 → 11..20 (next c3), c3 → 21..30 (last). */
function pageFor(url: URL): SaasFileListPage {
  const cursor = url.searchParams.get('cursor');
  const limit = Number(url.searchParams.get('limit'));
  const index = cursor === null ? 0 : cursor === 'c2' ? 1 : 2;
  const rows = Array.from({ length: limit }, (_, i) => row(100 + index * limit + i, { name: `page-${index + 1}-row-${i + 1}` }));
  return { rows, limit, nextCursor: index === 0 ? 'c2' : index === 1 ? 'c3' : null };
}

const OPTIONS: DatastoreFilterOptions = {
  dimensions: [
    { key: 'sensitives', hasMore: false, options: [
      { value: 'PII', label: 'Personal', count: null }, { value: 'PCI', label: 'Financial', count: null },
      { value: 'PHI', label: 'Health', count: null }, { value: 'SECRETS', label: 'IT & Security', count: null },
    ] },
    { key: 'category', hasMore: false, options: [
      { value: 'financial', label: 'Financial', count: null }, { value: 'legal', label: 'Legal', count: null },
    ] },
    { key: 'appType', hasMore: false, options: [{ value: 'GMAIL', label: 'Gmail', count: null }] },
    { key: 'documentClass', hasMore: false, options: [
      { value: 'invoice', label: 'Invoice', count: null }, { value: 'contract', label: 'Contract', count: null },
    ] },
    { key: 'owner', hasMore: false, options: [
      { value: 'abcdefabcdefabcdefabcdef', label: OWNER, count: null },
      { value: '0123456789abcdef01234567', label: 'sam@example.com', count: null },
    ] },
    { key: 'internalUser', hasMore: false, options: [{ value: 'sam@example.com', label: 'sam@example.com', count: null }] },
    { key: 'externalUser', hasMore: false, options: [{ value: 'guest@example.org', label: 'guest@example.org', count: null }] },
    { key: 'sharing', hasMore: false, options: [
      { value: 'internal', label: 'Shared Internally', count: null },
      { value: 'external', label: 'Shared Externally', count: null },
      { value: 'public', label: 'Shared Publicly', count: null },
    ] },
  ],
};

/** The legal category's classes only. */
const LEGAL_OPTIONS: DatastoreFilterOptions = {
  dimensions: OPTIONS.dimensions.map(d => (d.key === 'documentClass'
    ? { ...d, options: [{ value: 'contract', label: 'Contract', count: null }] }
    : d)),
};

type Answer = (url: URL) => unknown;
let listAnswer: Answer;
let optionsAnswer: Answer;
let fileAnswers: Record<string, Answer>;

const httpError = (status: number, code: string) =>
  new Error(`HTTP ${status}: ${JSON.stringify({ code, message: 'synthetic failure' })}`);

const LIST_PATH = '/api/dspm/files/saas';
const OPTIONS_PATH = '/api/dspm/files/saas/filter-options';
const FILE_PATH = /^\/api\/dspm\/saas-files\/([0-9a-f]{24})\/([a-z]+)$/;

const urls = () => get.mock.calls.map(([raw]) => new URL(String(raw), 'http://localhost'));
const listRequests = () => urls().filter(url => url.pathname === LIST_PATH);
const lastOf = <T,>(list: T[]): T => list[list.length - 1];
const lastList = () => lastOf(listRequests());
const optionRequests = () => urls().filter(url => url.pathname === OPTIONS_PATH);

beforeEach(() => {
  listAnswer = () => ({ rows: [DRIVE_ROW, PUBLIC_ROW, GMAIL_ROW, INERT_ROW], limit: 10, nextCursor: null });
  optionsAnswer = () => OPTIONS;
  fileAnswers = {
    overview: () => driveOverview({
      name: 'Offer Letter',
      sharing: {
        internalEmails: ['sam@example.com'], externalEmails: ['guest@example.org'], internalGroups: [], externalGroups: [],
        privateShare: false, publicShare: false, orgWide: false, externalShare: true,
      },
    }),
    activity: () => ({ entries: [] }),
    lineage: () => lineage(),
    copies: () => copies([], 0),
  };
  get.mockImplementation(async (raw: string) => {
    const url = new URL(raw, 'http://localhost');
    if (url.pathname === LIST_PATH) return listAnswer(url);
    if (url.pathname === OPTIONS_PATH) return optionsAnswer(url);
    const match = FILE_PATH.exec(url.pathname);
    if (match && fileAnswers[match[2]]) return fileAnswers[match[2]](url);
    throw new Error(`HTTP 404: unexpected ${raw}`);
  });
});

afterEach(() => {
  vi.clearAllMocks();
});

const LocationProbe = () => {
  const location = useLocation();
  return <div data-testid="location">{location.pathname + location.search}</div>;
};

function renderTab(url = '/xamlens/files/saas') {
  return render(
    <MemoryRouter initialEntries={[url]}>
      <Routes>
        <Route path="/xamlens/files/:filesTab" element={<SaasFilesTab />} />
      </Routes>
      <LocationProbe />
    </MemoryRouter>,
  );
}

/** The text a sighted user reads: hidden hover bubbles left out. */
function visibleText(el: Element): string {
  const clone = el.cloneNode(true) as Element;
  clone.querySelectorAll('[role="tooltip"]').forEach(tip => tip.remove());
  return clone.textContent ?? '';
}

const tableRow = (name: string) => screen.getByText(name, { selector: 'span[title]' }).closest('tr') as HTMLTableRowElement;
const footer = () => screen.getByRole('navigation', { name: 'Pagination' }).parentElement!.parentElement as HTMLElement;
const nextButton = () => screen.getByRole('button', { name: 'Next page' });
const previousButton = () => screen.getByRole('button', { name: 'Previous page' });

// ─── tests ───────────────────────────────────────────────────────────────────────────────────────

describe('SaasFilesTab list', () => {
  it('asks for the first page with limit 10 and no cursor, and the options once', async () => {
    renderTab();
    await screen.findByText('Offer Letter');
    expect(listRequests().map(url => url.search)).toEqual(['?limit=10']);
    expect(optionRequests().map(url => url.search)).toEqual(['']);
  });

  it('draws the cells: icon and source, category colours, Sharing Mode, owner and the date', async () => {
    const user = userEvent.setup();
    renderTab();
    await screen.findByText('Offer Letter');
    const headers = within(screen.getByRole('table')).getAllByRole('columnheader').map(th => th.textContent);
    expect(headers).toEqual(['File Name', '', 'Category', 'Document Class', 'Sharing Mode', 'Owner', 'Last Modified']);

    const drive = tableRow('Offer Letter');
    expect(drive.cells[0]).toHaveTextContent('Google Drive');
    expect(drive.cells[0].querySelector('[data-icon="mdi:file-word-outline"]')).not.toBeNull();
    expect(drive.cells[2].querySelector('.bg-\\[rgb\\(81\\,162\\,255\\)\\]')).not.toBeNull();
    expect(within(drive.cells[3]).getByText('System Documentation')).toHaveClass('border-l-[rgb(81,162,255)]');
    expect(visibleText(drive.cells[4])).toBe('Externally Shared+ 1');
    expect(drive.cells[4].querySelector('[data-icon="mdi:play-outline"]')).not.toBeNull();
    expect(drive.cells[6]).toHaveTextContent('10 Sep 2026, 05:56 PM');

    const badge = within(drive.cells[4]).getByText('+ 1');
    expect(badge).toHaveAttribute('tabindex', '0');
    const bubble = document.getElementById(badge.getAttribute('aria-describedby') ?? '') as HTMLElement;
    expect(bubble).toHaveAttribute('role', 'tooltip');
    expect(bubble).not.toBeVisible();
    await user.hover(badge);
    expect(bubble).toBeVisible();
    expect(bubble).toHaveTextContent(/^Internally Shared$/);
    await user.unhover(badge);
    const icon = drive.cells[4].querySelector('[data-icon="mdi:play-outline"]')!.parentElement as HTMLElement;
    expect(document.getElementById(icon.getAttribute('aria-describedby') ?? ''))
      .toHaveTextContent(/^Externally Shared, Internally Shared$/);

    expect(visibleText(tableRow('Brochure').cells[4])).toBe('Anyone with the link+ 1');
    const gmail = tableRow('Re: quarterly numbers');
    expect(visibleText(gmail.cells[4])).toBe('Private');
    expect(gmail.cells[0].querySelector('[data-icon="mdi:file-outline"]')).not.toBeNull();
    expect(gmail.cells[0]).toHaveTextContent('Gmail');
    expect(gmail.cells[2].querySelector('.bg-\\[rgb\\(255\\,100\\,103\\)\\]')).not.toBeNull();
    expect(gmail.cells[5]).toHaveTextContent('-');
  });

  it('draws the mail icon for an Outlook row without an extension', async () => {
    listAnswer = () => ({ rows: [row(9, { name: 'Re: plans', extension: null, source: 'OUTLOOK', sourceLabel: 'Outlook' })], limit: 10, nextCursor: null });
    renderTab();
    await screen.findByText('Re: plans');
    expect(tableRow('Re: plans').cells[0].querySelector('[data-icon="mdi:email-outline"]')).not.toBeNull();
  });

  it('shows "Columns 6" and hides a column', async () => {
    const user = userEvent.setup();
    renderTab();
    await screen.findByText('Offer Letter');
    await user.click(screen.getByRole('button', { name: 'Columns 6' }));
    await user.click(screen.getByRole('checkbox', { name: 'Owner' }));
    expect(screen.getByRole('button', { name: 'Columns 5' })).toBeInTheDocument();
    expect(within(screen.getByRole('table')).queryByRole('columnheader', { name: 'Owner' })).toBeNull();
  });

  it('gives a Drive row the lineage icon, which opens the drawer on Lineage, and a Gmail row none', async () => {
    const user = userEvent.setup();
    renderTab();
    await screen.findByText('Offer Letter');
    expect(within(tableRow('Re: quarterly numbers')).queryByRole('button', { name: /View lineage/ })).toBeNull();
    const button = within(tableRow('Offer Letter')).getByRole('button', { name: 'View lineage for Offer Letter' });
    expect(button).toHaveAttribute('title', 'View lineage');
    await user.click(button);
    const drawer = await screen.findByRole('dialog', { name: 'Offer Letter' });
    expect(within(drawer).getByRole('tab', { name: 'Lineage' })).toHaveAttribute('aria-selected', 'true');
    await waitFor(() => expect(urls().some(url => url.pathname === `/api/dspm/saas-files/${idOf(1)}/lineage`)).toBe(true));
  });

  it('opens the SaaS file drawer from a row, without a datastore name, and Escape returns focus to the row', async () => {
    const user = userEvent.setup();
    renderTab();
    await screen.findByText('Offer Letter');
    const drive = tableRow('Offer Letter');
    await user.click(drive);
    const drawer = await screen.findByRole('dialog', { name: 'Offer Letter' });
    expect(within(drawer).getByRole('tab', { name: 'Sensitives' })).toHaveAttribute('aria-selected', 'true');
    // Drive tabs before and after the overview: the row's source decides them.
    expect(within(drawer).getByRole('tab', { name: 'File Copies' })).toBeInTheDocument();

    const sharing = await within(drawer).findByRole('region', { name: 'Sharing' });
    await within(sharing).findByText('guest@example.org');
    expect(within(sharing).getAllByText(/Emails$/).map(el => el.textContent)).toEqual(['External Emails', 'Internal Emails']);
    expect(within(sharing).getByText('External Emails')).toHaveClass('text-rose-600');
    expect(within(sharing).queryByText('Shared externally')).toBeNull();

    await user.click(within(drawer).getByRole('tab', { name: 'Related Files' }));
    expect(within(drawer).getByText(/^Related files/).closest('p'))
      .toHaveTextContent(/^Related files for pat@example\.com \(matched by file owner\)$/);

    await user.keyboard('{Escape}');
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Offer Letter' })).toBeNull());
    expect(tableRow('Offer Letter')).toHaveFocus();
  });

  it('opens the drawer with Enter on a focused row, and leaves a row without a hex id inert', async () => {
    const user = userEvent.setup();
    fileAnswers.overview = () => overview({ name: 'Brochure' });
    renderTab();
    await screen.findByText('Offer Letter');
    expect(tableRow('Orphan')).not.toHaveAttribute('tabindex');
    await user.click(tableRow('Orphan'));
    expect(screen.queryByRole('dialog')).toBeNull();
    const brochure = tableRow('Brochure');
    brochure.focus();
    await user.keyboard('{Enter}');
    expect(await screen.findByRole('dialog', { name: 'Brochure' })).toBeInTheDocument();
  });
});

describe('SaasFilesTab paging', () => {
  beforeEach(() => {
    listAnswer = pageFor;
  });

  it('sends the previous nextCursor on Next and replays page 1 without a cursor on Previous', async () => {
    const user = userEvent.setup();
    renderTab();
    await screen.findByText('page-1-row-1');
    expect(footer()).toHaveTextContent('Showing 1 - 10');
    expect(previousButton()).toBeDisabled();
    expect(nextButton()).toBeEnabled();
    await user.click(nextButton());
    await screen.findByText('page-2-row-1');
    expect(lastList().searchParams.get('cursor')).toBe('c2');
    expect(footer()).toHaveTextContent('Showing 11 - 20');
    await user.click(nextButton());
    await screen.findByText('page-3-row-1');
    expect(footer()).toHaveTextContent('Showing 21 - 30');
    expect(nextButton()).toBeDisabled();
    await user.click(previousButton());
    await screen.findByText('page-2-row-1');
    await user.click(previousButton());
    await screen.findByText('page-1-row-1');
    expect(lastList().search).toBe('?limit=10');
  });

  it('keeps page 2 on screen when page 3 fails, then sends page 3 again on Next', async () => {
    const user = userEvent.setup();
    let failThird = true;
    listAnswer = url => {
      if (url.searchParams.get('cursor') === 'c3' && failThird) throw httpError(502, 'UPSTREAM_UNAVAILABLE');
      return pageFor(url);
    };
    renderTab();
    await screen.findByText('page-1-row-1');
    await user.click(nextButton());
    await screen.findByText('page-2-row-1');
    await user.click(nextButton());
    expect(await screen.findByRole('alert')).toBeInTheDocument();
    expect(footer()).toHaveTextContent('Showing 11 - 20');
    expect(screen.getByText('page-2-row-1')).toBeInTheDocument();
    failThird = false;
    await user.click(nextButton());
    await screen.findByText('page-3-row-1');
    expect(lastList().searchParams.get('cursor')).toBe('c3');
    expect(footer()).toHaveTextContent('Showing 21 - 30');
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('goes back to page 1 without a cursor when Previous follows a failed page 3', async () => {
    const user = userEvent.setup();
    listAnswer = url => {
      if (url.searchParams.get('cursor') === 'c3') throw httpError(502, 'UPSTREAM_UNAVAILABLE');
      return pageFor(url);
    };
    renderTab();
    await screen.findByText('page-1-row-1');
    await user.click(nextButton());
    await screen.findByText('page-2-row-1');
    await user.click(nextButton());
    await screen.findByRole('alert');
    await user.click(previousButton());
    await screen.findByText('page-1-row-1');
    expect(lastList().search).toBe('?limit=10');
    expect(footer()).toHaveTextContent('Showing 1 - 10');
  });

  it('starts again at page 1 on a search, with the table reading "Loading files…"', async () => {
    const user = userEvent.setup();
    renderTab();
    await screen.findByText('page-1-row-1');
    await user.click(nextButton());
    await screen.findByText('page-2-row-1');
    let release: () => void = () => undefined;
    listAnswer = url => new Promise(resolve => { release = () => resolve(pageFor(url)); });
    await user.type(screen.getByPlaceholderText('Search Files'), 'offer');
    expect(await screen.findByText('Loading files…')).toBeInTheDocument();
    expect(lastList().searchParams.get('q')).toBe('offer');
    expect(lastList().searchParams.has('cursor')).toBe(false);
    release();
    await screen.findByText('page-1-row-1');
  });

  it('keeps the footer and keyboard focus on Rows Per Page across a change, and writes pageSize to the URL', async () => {
    renderTab();
    await screen.findByText('page-1-row-1');
    const select = screen.getByRole('combobox', { name: 'Rows Per Page' });
    select.focus();
    fireEvent.change(select, { target: { value: '25' } });
    await screen.findByText('page-1-row-25');
    expect(lastList().search).toBe('?limit=25');
    expect(screen.getByRole('combobox', { name: 'Rows Per Page' })).toBe(select);
    expect(select).toHaveFocus();
    expect(screen.getByTestId('location')).toHaveTextContent('/xamlens/files/saas?pageSize=25');
    fireEvent.change(select, { target: { value: '10' } });
    await waitFor(() => expect(lastList().search).toBe('?limit=10'));
    expect(screen.getByTestId('location')).toHaveTextContent(/^\/xamlens\/files\/saas$/);
  });

  it('reads pageSize from the URL on mount, and ignores a size it does not offer', async () => {
    const first = renderTab('/xamlens/files/saas?pageSize=25');
    await screen.findByText('page-1-row-25');
    expect(listRequests()[0].search).toBe('?limit=25');
    expect(screen.getByRole('combobox', { name: 'Rows Per Page' })).toHaveValue('25');
    first.unmount();
    get.mockClear();
    renderTab('/xamlens/files/saas?pageSize=7');
    await screen.findByText('page-1-row-10');
    expect(listRequests()[0].search).toBe('?limit=10');
  });
});

describe('SaasFilesTab filters', () => {
  const openDropdown = (user: ReturnType<typeof userEvent.setup>, name: string) =>
    user.click(screen.getByRole('button', { name: new RegExp(`^${name}`) }));

  it('sends sensitives, documentClass and sharing, and no sharing for Any Access', async () => {
    const user = userEvent.setup();
    renderTab();
    await screen.findByText('Offer Letter');
    await openDropdown(user, 'Sensitives');
    await user.click(screen.getByRole('checkbox', { name: 'Personal' }));
    await waitFor(() => expect(lastList().searchParams.get('sensitives')).toBe('PII'));
    expect(lastList().searchParams.has('cursor')).toBe(false);
    await openDropdown(user, 'Document Class');
    await user.click(screen.getByRole('option', { name: 'Invoice' }));
    await waitFor(() => expect(lastList().searchParams.get('documentClass')).toBe('invoice'));
    await openDropdown(user, 'Sharing Mode');
    await user.click(screen.getByRole('option', { name: 'Shared Externally' }));
    await waitFor(() => expect(lastList().searchParams.get('sharing')).toBe('external'));
    await user.click(screen.getByRole('option', { name: 'Any Access' }));
    await waitFor(() => expect(lastList().searchParams.has('sharing')).toBe(false));
    expect(lastList().searchParams.get('sensitives')).toBe('PII');
  });

  it('narrows Document Class after a Category is chosen, keeping the options on screen meanwhile', async () => {
    const user = userEvent.setup();
    optionsAnswer = url => (url.searchParams.get('category') === 'legal' ? LEGAL_OPTIONS : OPTIONS);
    renderTab();
    await screen.findByText('Offer Letter');
    await user.click(screen.getByRole('button', { name: 'More filters' }));
    const panel = screen.getByRole('dialog', { name: 'More filters' });
    await user.click(within(panel).getByRole('button', { name: 'Category' }));
    await user.click(within(panel).getByRole('checkbox', { name: 'Legal' }));
    await waitFor(() => expect(lastOf(optionRequests()).searchParams.get('category')).toBe('legal'));
    expect(lastList().searchParams.get('category')).toBe('legal');
    await user.click(within(panel).getByRole('button', { name: 'Document Class' }));
    await waitFor(() => expect(within(panel).getAllByRole('option').map(o => o.textContent)).toEqual(['Contract']));
  });

  it('lists each owner once by e-mail and sends its id, and App Type as appType', async () => {
    const user = userEvent.setup();
    renderTab();
    await screen.findByText('Offer Letter');
    await user.click(screen.getByRole('button', { name: 'More filters' }));
    const panel = screen.getByRole('dialog', { name: 'More filters' });
    await user.click(within(panel).getByRole('button', { name: 'Owners' }));
    expect(within(panel).getAllByRole('checkbox', { name: OWNER })).toHaveLength(1);
    await user.click(within(panel).getByRole('checkbox', { name: OWNER }));
    await waitFor(() => expect(lastList().searchParams.get('owner')).toBe('abcdefabcdefabcdefabcdef'));
    await user.click(within(panel).getByRole('button', { name: 'App Type' }));
    await user.click(within(panel).getByRole('checkbox', { name: 'Gmail' }));
    await waitFor(() => expect(lastList().searchParams.get('appType')).toBe('GMAIL'));
  });

  it('keeps the table usable when the options fail', async () => {
    const user = userEvent.setup();
    optionsAnswer = () => { throw httpError(502, 'UPSTREAM_UNAVAILABLE'); };
    renderTab();
    await screen.findByText('Offer Letter');
    await openDropdown(user, 'Sensitives');
    expect(screen.getByText('Could not load options')).toBeInTheDocument();
    expect(screen.queryByRole('alert')).toBeNull();
  });
});

describe('SaasFilesTab empty and error states', () => {
  it('reads "No files yet" without a search or filter', async () => {
    listAnswer = () => ({ rows: [], limit: 10, nextCursor: null });
    renderTab();
    expect(await screen.findByText('No files yet')).toBeInTheDocument();
    expect(screen.getByText('Files appear here once a scan has found them.')).toBeInTheDocument();
    expect(footer()).not.toHaveTextContent('Showing');
  });

  it('reads "No files match these filters" with a search', async () => {
    const user = userEvent.setup();
    listAnswer = () => ({ rows: [], limit: 10, nextCursor: null });
    renderTab();
    await screen.findByText('No files yet');
    await user.type(screen.getByPlaceholderText('Search Files'), 'zzz');
    expect(await screen.findByText('No files match these filters')).toBeInTheDocument();
    expect(screen.getByText('Try clearing the search or filters.')).toBeInTheDocument();
  });

  it('shows the error banner and retries the same page', async () => {
    const user = userEvent.setup();
    let fail = true;
    listAnswer = () => {
      if (fail) throw httpError(502, 'UPSTREAM_UNAVAILABLE');
      return { rows: [DRIVE_ROW], limit: 10, nextCursor: null };
    };
    renderTab();
    const alert = await screen.findByRole('alert');
    fail = false;
    await user.click(within(alert).getByRole('button', { name: 'Retry' }));
    await screen.findByText('Offer Letter');
    expect(listRequests().map(url => url.search)).toEqual(['?limit=10', '?limit=10']);
    expect(screen.queryByRole('alert')).toBeNull();
  });
});
