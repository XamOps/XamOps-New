import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useCallback, useState } from 'react';
import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

vi.mock('../../../../../utils/apiClient', () => ({ default: { get: vi.fn() } }));

import apiClient from '../../../../../utils/apiClient';
import type { EndpointFileLineage, EndpointFileOverview } from '../../../../../types/dspmFiles';
import { categoryAccent } from '../../../dspmFormat';
import FileDrawerHost from '../../../files/FileDrawerHost';
import { NO_RELATED_FILES_TEXT } from '../../saas/lineageModel';
import { formatLineageTime } from '../../saas/saasFormat';
import { copies, lane, lineage, local } from '../../saas/__tests__/saasTestData';
import EndpointFileDrawer, { type EndpointFileDrawerProps } from '../EndpointFileDrawer';
import { BUILT_EVENT_NOTE } from '../EndpointFileLineageTab';

const get = apiClient.get as unknown as ReturnType<typeof vi.fn>;

// ─── synthetic data (shapes only; no captured values) ────────────────────────────────────────────

const FILE_ID = '0e1f2a3b-4c5d-4e6f-8a7b-9c0d1e2f3a4b';
const FILE_NAME = 'contacts.json';
const DEVICE = 'build-laptop-07';
const PATH = '/Users/priya/Documents/contacts.json';
const NOT_FOUND = 'This file could not be found. It may have been removed.';
const UPSTREAM = 'Matters is not responding right now. Try again shortly.';

const MODIFIED = local(2026, 9, 3, 9, 15);
const CREATED = local(2026, 8, 4, 18, 40);
const ACCESSED = local(2026, 9, 4, 11, 5);
const SCANNED = local(2026, 9, 5, 8, 30);
const FIRST_SEEN = local(2026, 8, 5, 10, 0);

/** Design 9.2's overview (AC15) with invented values: category and class "other", three records. */
function fileOverview(over: Partial<EndpointFileOverview> = {}): EndpointFileOverview {
  return {
    name: FILE_NAME, extension: 'json', owner: 'priya', categoryLabel: 'Other', documentClassLabel: 'other',
    sensitiveRecords: 3, path: PATH, sizeBytes: 412, device: DEVICE, modifiedAt: MODIFIED, fileCreatedAt: CREATED,
    accessedAt: ACCESSED, tags: [],
    sensitives: [
      { dataElement: 'EMAIL ADDRESS', categoryLabel: 'Personal', count: 2 },
      { dataElement: 'PERSON NAME', categoryLabel: 'Personal', count: 1 },
    ],
    ...over,
  };
}

/** The file itself, its only hash copy. */
const SELF_LANE = lane('copy-0', {
  fileName: FILE_NAME, self: true, driveType: null, os: 'MAC', provider: null, ownerName: DEVICE, ownerEmail: null,
  channelLabel: 'Endpoint', firstSeenAt: FIRST_SEEN,
});
const GMAIL_LANE = lane('copy-1', { fileName: FILE_NAME, driveType: 'GMAIL', channelLabel: 'Gmail', firstSeenAt: local(2026, 8, 6, 9, 0) });

const assetLineage = (over: Partial<EndpointFileLineage> = {}): EndpointFileLineage => ({
  asset: { title: FILE_NAME, device: DEVICE, osUser: 'tester', firstSeenAt: FIRST_SEEN, events: [] },
  copies: lineage({ lanes: [SELF_LANE] }),
  ...over,
});

type Answer = (url: URL) => unknown;
let answers: Record<string, Answer>;

const httpError = (status: number, code: string) =>
  new Error(`HTTP ${status}: ${JSON.stringify({ code, message: 'synthetic failure' })}`);
const fail = (status: number, code: string): Answer => () => { throw httpError(status, code); };

const FILE_PATH = /^\/api\/dspm\/endpoint-files\/([0-9a-f-]{36})\/([a-z]+)$/;
const requestsTo = (endpoint: string) =>
  get.mock.calls.map(([raw]) => new URL(String(raw), 'http://localhost').pathname)
    .filter(path => FILE_PATH.exec(path)?.[2] === endpoint);

beforeEach(() => {
  answers = {
    overview: () => fileOverview(),
    activity: () => ({ entries: [] }),
    lineage: () => assetLineage(),
    copies: () => copies([], 1),
  };
  get.mockImplementation(async (raw: string) => {
    const url = new URL(raw, 'http://localhost');
    const match = FILE_PATH.exec(url.pathname);
    const answer = match && match[1] === FILE_ID ? answers[match[2]] : undefined;
    if (!answer) throw new Error(`HTTP 404: unexpected ${raw}`);
    return answer(url);
  });
});

afterEach(() => {
  vi.useRealTimers();
  vi.clearAllMocks();
});

// ─── harness: the drawer opened from a "row" of a page, inside the Files page's host ────────────

function Harness(props: Partial<EndpointFileDrawerProps>) {
  const [opener, setOpener] = useState<HTMLButtonElement | null>(null);
  const [open, setOpen] = useState(true);
  const close = useCallback(() => setOpen(false), []);
  return (
    <>
      <button ref={setOpener} type="button">file row</button>
      {open && opener && (
        <FileDrawerHost>
          <EndpointFileDrawer
            fileId={FILE_ID}
            fileName={FILE_NAME}
            extension="json"
            modifiedAt={MODIFIED}
            scannedAt={SCANNED}
            initialTab="sensitives"
            returnFocus={opener}
            onClose={close}
            {...props}
          />
        </FileDrawerHost>
      )}
    </>
  );
}

async function renderDrawer(props: Partial<EndpointFileDrawerProps> = {}) {
  render(<Harness {...props} />);
  return screen.findByRole('dialog', { name: props.fileName ?? FILE_NAME });
}

const panelOf = (drawer: HTMLElement) => within(drawer).getByRole('tabpanel');
const openTab = (user: ReturnType<typeof userEvent.setup>, drawer: HTMLElement, name: string) =>
  user.click(within(drawer).getByRole('tab', { name }));
const stripValue = (drawer: HTMLElement, label: string) => within(drawer).getByText(label).nextElementSibling as HTMLElement;
const bodyRows = (table: HTMLElement) =>
  (within(table).getAllByRole('row').slice(1) as HTMLTableRowElement[]).map(row => [...row.cells].map(cell => cell.textContent));
const detailRows = (panel: HTMLElement) =>
  [...panel.querySelectorAll('dt')].map(dt => [dt.textContent, dt.nextElementSibling?.textContent]);
const laneKeys = (lanes: HTMLElement) => [...lanes.querySelectorAll('[data-lane]')].map(el => el.getAttribute('data-lane'));

// ─── tests ───────────────────────────────────────────────────────────────────────────────────────

describe('EndpointFileDrawer header and card', () => {
  it('shows the name, the upper-cased extension, no Take Action, the coloured card and the six tabs', async () => {
    const drawer = await renderDrawer();
    await waitFor(() => expect(stripValue(drawer, 'Owner')).toHaveTextContent(/^priya$/));
    expect(within(drawer).getByRole('heading', { level: 2, name: FILE_NAME })).toBeInTheDocument();
    expect(within(drawer).getByText('JSON')).toBeInTheDocument();
    expect(within(drawer).queryByRole('button', { name: /Take Action/ })).toBeNull();
    expect(within(drawer).queryByText('New')).toBeNull();

    const category = within(stripValue(drawer, 'Category')).getByText('Other');
    expect(category.querySelector('span')).toHaveClass('bg-[rgb(255,100,103)]');
    expect(within(stripValue(drawer, 'Document Class')).getByText('other')).toHaveClass('border-l-[rgb(255,100,103)]');
    expect(stripValue(drawer, 'Sensitive Records')).toHaveTextContent(/^3$/);

    expect(within(drawer).getAllByRole('tab').map(tab => tab.textContent)).toEqual([
      'Sensitives', 'Details', 'File Activity', 'Lineage', 'File Copies', 'Related Files',
    ]);
    expect(within(drawer).getByRole('tab', { name: 'Sensitives' })).toHaveAttribute('aria-selected', 'true');
  });

  it('colours Category and Document Class by the category', async () => {
    answers.overview = () => fileOverview({ categoryLabel: 'IT & Technical', documentClassLabel: 'system documentation' });
    const drawer = await renderDrawer();
    const category = await within(drawer).findByText('IT & Technical');
    expect(category.querySelector('span')).toHaveClass('bg-[rgb(81,162,255)]');
    expect(within(stripValue(drawer, 'Document Class')).getByText('system documentation'))
      .toHaveClass('border-l-[rgb(81,162,255)]');
  });

  it('reads "New" after the name of a file modified in the last 48 hours', async () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date(2026, 10, 17, 12, 0));
    const drawer = await renderDrawer({ modifiedAt: Date.now() / 1000 - 6 * 3600 });
    const badge = within(drawer).getByText('New');
    expect(badge).toHaveAttribute('title', 'Modified in the last 48 hours');
    expect(within(drawer).getByRole('heading', { level: 2 }).nextElementSibling).toBe(badge);
  });
});

describe('EndpointFileDrawer Sensitives and Details', () => {
  it('lists "Entities | Sensitives" in API order, each entity in its label colour, without a search or Columns', async () => {
    const drawer = await renderDrawer();
    const panel = panelOf(drawer);
    await within(panel).findByText('EMAIL ADDRESS');
    const table = within(panel).getByRole('table');
    expect(within(table).getAllByRole('columnheader').map(th => th.textContent)).toEqual(['Entities', 'Sensitives']);
    expect(bodyRows(table)).toEqual([['EMAIL ADDRESS', '2Personal'], ['PERSON NAME', '1Personal']]);
    expect(within(table).getByText('EMAIL ADDRESS').previousElementSibling).toHaveClass(categoryAccent('Personal').bar);
    expect(within(panel).queryByRole('searchbox')).toBeNull();
    expect(within(panel).queryByRole('button', { name: /^Columns/ })).toBeNull();
  });

  it('reads the empty text when the last scan matched nothing', async () => {
    answers.overview = () => fileOverview({ sensitives: [], sensitiveRecords: 0 });
    const drawer = await renderDrawer();
    const panel = panelOf(drawer);
    expect(await within(panel).findByText('No sensitive data found')).toBeInTheDocument();
    expect(within(panel).getByText('The last scan of this file matched no data elements.')).toBeInTheDocument();
  });

  it('lists the Details rows with date and time, Last Scanned from the row, and copies the path', async () => {
    const user = userEvent.setup();
    const drawer = await renderDrawer();
    await openTab(user, drawer, 'Details');
    const panel = panelOf(drawer);
    await within(panel).findByText('File Path');
    expect(detailRows(panel)).toEqual([
      ['File Path', PATH],
      ['File Size', '412 Bytes'],
      ['File Created', '04 Aug 2026, 06:40 PM'],
      ['Last Accessed', '04 Sep 2026, 11:05 AM'],
      ['Last Modified', '03 Sep 2026, 09:15 AM'],
      ['Last Scanned', '05 Sep 2026, 08:30 AM'],
    ]);
    await user.click(within(panel).getByRole('button', { name: 'Copy path' }));
    expect(await navigator.clipboard.readText()).toBe(PATH);
  });

  it('adds Matters Tags only when the file has tags, and reads "-" for missing values', async () => {
    const user = userEvent.setup();
    answers.overview = () => fileOverview({
      path: null, sizeBytes: null, fileCreatedAt: null, accessedAt: null, modifiedAt: null, tags: ['confidential', 'internal'],
    });
    const drawer = await renderDrawer({ scannedAt: null });
    await openTab(user, drawer, 'Details');
    const panel = panelOf(drawer);
    await within(panel).findByText('Matters Tags');
    expect(detailRows(panel)).toEqual([
      ['File Path', '-'], ['File Size', '-'], ['File Created', '-'], ['Last Accessed', '-'], ['Last Modified', '-'],
      ['Last Scanned', '-'], ['Matters Tags', 'confidential, internal'],
    ]);
    expect(within(panel).queryByRole('button', { name: 'Copy path' })).toBeNull();
  });
});

describe('EndpointFileDrawer File Activity', () => {
  it('asks for the log only when its tab is first shown, and reads its empty text', async () => {
    const user = userEvent.setup();
    const drawer = await renderDrawer();
    await within(drawer).findByText('EMAIL ADDRESS');
    expect(requestsTo('activity')).toHaveLength(0);
    await openTab(user, drawer, 'File Activity');
    expect(await within(panelOf(drawer)).findByText('No file activity yet')).toBeInTheDocument();
    expect(within(panelOf(drawer)).getByText('Tag changes for this file will appear here.')).toBeInTheDocument();
    await openTab(user, drawer, 'Details');
    await openTab(user, drawer, 'File Activity');
    expect(requestsTo('activity')).toHaveLength(1);
  });

  it('lists a MIP entry with its label, tag and note', async () => {
    const user = userEvent.setup();
    answers.activity = () => ({
      entries: [{
        action: 'TAG_APPLIED', actionLabel: 'Tag applied', selectedTag: 'Confidential', previousTag: null,
        performedBy: 'admin@example.com', occurredAt: local(2026, 10, 2, 14, 5), keywordsWritten: true, mipLabelWritten: false,
      }],
    });
    const drawer = await renderDrawer();
    await openTab(user, drawer, 'File Activity');
    const item = await within(panelOf(drawer)).findByRole('listitem');
    expect(item).toHaveTextContent('Tag appliedConfidentialWritten to Keywords onlyadmin@example.com - 02 Oct 2026, 02:05 PM');
  });
});

describe('EndpointFileDrawer Lineage', () => {
  it('draws the asset lane first with a file icon and its File Created card, the ⓘ and the footer', async () => {
    const user = userEvent.setup();
    const drawer = await renderDrawer({ initialTab: 'lineage' });
    expect(within(drawer).getByRole('tab', { name: 'Lineage' })).toHaveAttribute('aria-selected', 'true');
    const panel = panelOf(drawer);
    const lanes = await within(panel).findByRole('group', { name: 'Lineage lanes' });
    expect(within(panel).getByRole('heading', { name: `Lineage for ${FILE_NAME}` })).toBeInTheDocument();
    expect(within(panel).getByText(`Recorded activity on ${DEVICE}.`)).toBeInTheDocument();
    expect(within(panel).getByPlaceholderText('Search an event')).toBeInTheDocument();
    expect(within(panel).getByRole('button', { name: /^Related files: Medium and above/ })).toHaveAttribute('aria-disabled', 'true');

    expect(laneKeys(lanes)).toEqual(['endpoint-asset']);
    const own = lanes.querySelector('[data-lane="endpoint-asset"]') as HTMLElement;
    expect(own).toHaveAttribute('data-own', 'true');
    expect(own.querySelector('[data-icon="mdi:file-outline"]')).not.toBeNull();
    expect(own).toHaveTextContent(DEVICE);
    const card = within(lanes).getByRole('button', { name: /^File Created/ });
    expect(card).toHaveTextContent(`File CreatedActor: priya · ${formatLineageTime(CREATED)}`);
    expect(card.querySelector('[data-tone="purple"]')).not.toBeNull();
    expect(within(panel).getByText(NO_RELATED_FILES_TEXT)).toBeInTheDocument();

    // The ⓘ is the shared react-aria InfoTooltip, which opens on keyboard focus. React-aria ignores a lone
    // Shift when it tracks the input modality, so after the clicks of earlier tests a Tab marks the keyboard.
    const info = within(panel).getByRole('button', { name: 'More information' });
    await user.keyboard('{Tab}');
    act(() => info.focus());
    expect(await screen.findByRole('tooltip')).toHaveTextContent(BUILT_EVENT_NOTE);
  });

  it("draws the other copies' lanes after it, without the ⓘ or the footer", async () => {
    answers.lineage = () => assetLineage({ copies: lineage({ lanes: [SELF_LANE, GMAIL_LANE] }) });
    const drawer = await renderDrawer({ initialTab: 'lineage' });
    const panel = panelOf(drawer);
    const lanes = await within(panel).findByRole('group', { name: 'Lineage lanes' });
    expect(laneKeys(lanes)).toEqual(['endpoint-asset', 'copy-1']);
    expect(lanes.querySelector('[data-lane="copy-1"] [data-icon="logos:google-gmail"]')).not.toBeNull();
    expect(within(lanes).getAllByRole('button', { name: /^(File Created|First Seen)/ })).toHaveLength(2);
    expect(within(panel).queryByText(NO_RELATED_FILES_TEXT)).toBeNull();
    expect(within(panel).queryByRole('button', { name: 'More information' })).toBeNull();
  });

  it('draws the own lane from the overview without a lineage asset, with File Created by the owner', async () => {
    answers.lineage = () => ({ asset: null, copies: lineage({ lanes: [SELF_LANE, GMAIL_LANE] }) });
    const drawer = await renderDrawer({ initialTab: 'lineage' });
    const panel = panelOf(drawer);
    const lanes = await within(panel).findByRole('group', { name: 'Lineage lanes' });
    expect(within(panel).getByText('Trace where this file came from.')).toBeInTheDocument();
    expect(laneKeys(lanes)).toEqual(['endpoint-asset', 'copy-1']);
    const own = lanes.querySelector('[data-lane="endpoint-asset"]') as HTMLElement;
    expect(own).toHaveAttribute('data-own', 'true');
    expect(own).toHaveTextContent(FILE_NAME);
    expect(own).toHaveTextContent('priya');
    expect(within(lanes).getByRole('button', { name: /^File Created/ }))
      .toHaveTextContent(`File CreatedActor: priya · ${formatLineageTime(CREATED)}`);
    expect(within(panel).queryByText(NO_RELATED_FILES_TEXT)).toBeNull();
  });

  it('draws the own lane alone with the ⓘ when there is no asset and the copies are unavailable', async () => {
    answers.lineage = () => ({ asset: null, copies: lineage({ available: false }) });
    const drawer = await renderDrawer({ initialTab: 'lineage' });
    const panel = panelOf(drawer);
    const lanes = await within(panel).findByRole('group', { name: 'Lineage lanes' });
    expect(laneKeys(lanes)).toEqual(['endpoint-asset']);
    expect(within(lanes).getByRole('button', { name: /^File Created/ })).toBeInTheDocument();
    expect(within(panel).getByRole('button', { name: 'More information' })).toBeInTheDocument();
    expect(within(panel).getByText(NO_RELATED_FILES_TEXT)).toBeInTheDocument();
    expect(within(panel).queryByText('No lineage recorded for this file.')).toBeNull();
  });

  it('reads "No lineage recorded for this file." without an asset, a creation time or another lane', async () => {
    answers.overview = () => fileOverview({ fileCreatedAt: null });
    answers.lineage = () => ({ asset: null, copies: lineage({ lanes: [SELF_LANE] }) });
    const drawer = await renderDrawer({ initialTab: 'lineage' });
    expect(await within(panelOf(drawer)).findByText('No lineage recorded for this file.')).toBeInTheDocument();
    expect(within(panelOf(drawer)).queryByRole('group', { name: 'Lineage lanes' })).toBeNull();
  });

  it('waits for the overview before drawing the lanes', async () => {
    let release: () => void = () => undefined;
    answers.overview = () => new Promise(resolve => { release = () => resolve(fileOverview()); });
    const drawer = await renderDrawer({ initialTab: 'lineage' });
    const panel = panelOf(drawer);
    await waitFor(() => expect(requestsTo('lineage')).toHaveLength(1));
    await act(async () => { await Promise.resolve(); });
    expect(within(panel).getByText('Loading…')).toBeInTheDocument();
    expect(within(panel).queryByRole('group', { name: 'Lineage lanes' })).toBeNull();
    release();
    expect(await within(panel).findByRole('button', { name: /^File Created/ })).toBeInTheDocument();
  });

  it('shows a failed lineage with Retry, which asks again', async () => {
    const user = userEvent.setup();
    let failing = true;
    answers.lineage = () => {
      if (failing) throw httpError(502, 'UPSTREAM_UNAVAILABLE');
      return assetLineage();
    };
    const drawer = await renderDrawer({ initialTab: 'lineage' });
    const alert = await within(panelOf(drawer)).findByRole('alert');
    expect(alert).toHaveTextContent(UPSTREAM);
    failing = false;
    await user.click(within(alert).getByRole('button', { name: 'Retry' }));
    expect(await within(panelOf(drawer)).findByRole('button', { name: /^File Created/ })).toBeInTheDocument();
    expect(requestsTo('lineage')).toHaveLength(2);
  });

  it('closes the event popover, then full screen, then the drawer on Escape, and focuses the opener', async () => {
    const user = userEvent.setup();
    const drawer = await renderDrawer({ initialTab: 'lineage' });
    await within(panelOf(drawer)).findByRole('button', { name: /^File Created/ });
    await user.click(within(panelOf(drawer)).getByRole('button', { name: 'Full screen' }));
    const frame = await screen.findByRole('dialog', { name: `Lineage for ${FILE_NAME}` });
    await user.click(within(frame).getByRole('button', { name: /^File Created/ }));
    expect(await screen.findByRole('dialog', { name: 'Event details' })).toBeInTheDocument();

    await user.keyboard('{Escape}');
    expect(screen.queryByRole('dialog', { name: 'Event details' })).toBeNull();
    expect(screen.getByRole('dialog', { name: `Lineage for ${FILE_NAME}` })).toBeInTheDocument();
    await user.keyboard('{Escape}');
    expect(screen.queryByRole('dialog', { name: `Lineage for ${FILE_NAME}` })).toBeNull();
    expect(screen.getByRole('dialog', { name: FILE_NAME })).toBeInTheDocument();
    await user.keyboard('{Escape}');
    expect(screen.queryByRole('dialog', { name: FILE_NAME })).toBeNull();
    expect(screen.getByRole('button', { name: 'file row' })).toHaveFocus();
  });
});

describe('EndpointFileDrawer File Copies and Related Files', () => {
  it('reads Copies Found 0, Locations 1 and the empty text for a file with only itself, in one request with Related Files', async () => {
    const user = userEvent.setup();
    const drawer = await renderDrawer();
    await openTab(user, drawer, 'File Copies');
    const panel = panelOf(drawer);
    expect(await within(panel).findByText('No other files with this content were found.')).toBeInTheDocument();
    expect(within(panel).getByText('Copies Found').nextElementSibling).toHaveTextContent(/^0$/);
    expect(within(panel).getByText('Locations').nextElementSibling).toHaveTextContent(/^1$/);

    await openTab(user, drawer, 'Related Files');
    const related = panelOf(drawer);
    const heading = within(related).getByText(/^Related files for/).closest('p') as HTMLElement;
    expect(heading).toHaveTextContent(/^Related files for priya · device build-laptop-07 \(matched by device\)$/);
    expect(heading.querySelector('[data-icon="mdi:account-outline"]')).not.toBeNull();
    expect(within(heading).getByText('priya').tagName).toBe('STRONG');
    expect(within(related).getByText(
      "Endpoint events carry no user today; endpoint activity is matched by the person's devices.",
    )).toBeInTheDocument();
    expect(await within(related).findByText('No related files found in other sources.')).toBeInTheDocument();
    expect(requestsTo('copies')).toHaveLength(1);
  });

  it('reads only "No copies found for this file." when lineage is off for it', async () => {
    const user = userEvent.setup();
    answers.copies = () => copies([], 0, false);
    const drawer = await renderDrawer();
    await openTab(user, drawer, 'File Copies');
    const panel = panelOf(drawer);
    expect(await within(panel).findByText('No copies found for this file.')).toBeInTheDocument();
    expect(within(panel).queryByText('Copies Found')).toBeNull();
    expect(within(panel).queryByRole('table')).toBeNull();
  });

  it('leaves the person out of the Related Files header while the overview is missing', async () => {
    const user = userEvent.setup();
    answers.overview = fail(404, 'ASSET_NOT_FOUND');
    const drawer = await renderDrawer();
    await openTab(user, drawer, 'Related Files');
    const related = panelOf(drawer);
    expect(within(related).getByText(/^Related files/).closest('p')).toHaveTextContent(/^Related files \(matched by device\)$/);
  });
});

describe('EndpointFileDrawer failures', () => {
  it('reads the not-found text on Sensitives and Details for a removed file, "-" on the card, and still loads Lineage', async () => {
    const user = userEvent.setup();
    answers.overview = fail(404, 'ASSET_NOT_FOUND');
    const drawer = await renderDrawer();
    const alert = await within(panelOf(drawer)).findByRole('alert');
    expect(alert).toHaveTextContent(NOT_FOUND);
    expect(within(alert).queryByRole('button', { name: 'Retry' })).toBeNull();
    expect(['Owner', 'Category', 'Document Class', 'Sensitive Records'].map(label => stripValue(drawer, label).textContent))
      .toEqual(['-', '-', '-', '-']);
    await openTab(user, drawer, 'Details');
    expect(await within(panelOf(drawer)).findByText(NOT_FOUND)).toBeInTheDocument();
    await openTab(user, drawer, 'Lineage');
    // Without the overview there is no owner or creation time: First Seen, by the asset's OS user.
    expect(await within(panelOf(drawer)).findByRole('button', { name: /^First Seen/ })).toHaveTextContent('Actor: tester');
  });

  it('offers Retry on a failed overview, which loads it again', async () => {
    const user = userEvent.setup();
    let failing = true;
    answers.overview = () => {
      if (failing) throw httpError(502, 'UPSTREAM_UNAVAILABLE');
      return fileOverview();
    };
    const drawer = await renderDrawer();
    const alert = await within(panelOf(drawer)).findByRole('alert');
    expect(alert).toHaveTextContent(UPSTREAM);
    failing = false;
    await user.click(within(alert).getByRole('button', { name: 'Retry' }));
    expect(await within(drawer).findByText('EMAIL ADDRESS')).toBeInTheDocument();
    expect(stripValue(drawer, 'Owner')).toHaveTextContent(/^priya$/);
    expect(requestsTo('overview')).toHaveLength(2);
  });
});
