import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useCallback, useRef, useState } from 'react';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
vi.mock('../../../../../utils/apiClient', () => ({ default: { get: vi.fn() } }));
import apiClient from '../../../../../utils/apiClient';
import type { SaasFileOverview } from '../../../../../types/dspmDrawers';
import { DrawerShell } from '../../drawer/DrawerShell';
import { CONSOLE_ONLY_TOOLTIP } from '../../ui/ConsoleOnly';
import SaasFileDrawer, { type SaasFileDrawerProps } from '../SaasFileDrawer';
import { GMAIL_LINEAGE_NOTE, NO_RELATED_FILES_TEXT } from '../lineageModel';
import { OWNER, copies, copy, driveOverview, lane, lineage, local, overview } from './saasTestData';

const get = apiClient.get as unknown as ReturnType<typeof vi.fn>;

// ─── synthetic data (shapes only; no captured values) ────────────────────────────────────────────

const FILE_ID = 'aaaabbbbccccddddeeeeffff';
const GMAIL_NAME = 'travel-itinerary.pdf';
const DRIVE_NAME = 'Offer Letter - Pat Example';
const NOT_FOUND = 'This file could not be found. It may have been removed.';
const UPSTREAM = 'Matters is not responding right now. Try again shortly.';
const COVERAGE = 'Only classified copies are compared.';

/** The Drive DOCX shape: the PDF in Gmail, the opened DOCX (self) and two more Drive copies. */
const DRIVE_LANES = [
  lane('copy-0', { fileName: 'offer-letter.pdf', driveType: 'GMAIL', channelLabel: 'Gmail', firstSeenAt: local(2026, 9, 12, 9, 15) }),
  lane('copy-1', { fileName: DRIVE_NAME, self: true, firstSeenAt: local(2026, 9, 12, 9, 40) }),
  lane('copy-2', { fileName: 'offer-letter-copy.docx', firstSeenAt: local(2026, 9, 12, 9, 41) }),
  lane('copy-3', { fileName: 'offer-letter-signed.pdf', firstSeenAt: local(2026, 9, 12, 9, 42) }),
];

/** The copies other than the opened file: two locations (the Gmail mailbox and the Drive). */
const DRIVE_COPIES = copies([
  copy('copy-0', { fileName: 'offer-letter.pdf', driveType: 'GMAIL', sourceLabel: 'Gmail', locationLabel: 'SaaS · Gmail' }),
  copy('copy-2', { fileName: 'offer-letter-copy.docx', extension: 'docx' }),
  copy('copy-3', { fileName: 'offer-letter-signed.pdf', ownerName: 'Sam Lee', ownerEmail: 'sam@example.com', userOrDevice: 'sam@example.com' }),
], 2);

type Answer = (url: URL) => unknown;
let answers: Record<string, Answer>;

/** The error `apiClient` throws for a failed request. */
const httpError = (status: number, code: string) =>
  new Error(`HTTP ${status}: ${JSON.stringify({ code, message: 'synthetic failure' })}`);
const fail = (status: number, code: string): Answer => () => {
  throw httpError(status, code);
};
const pending: Answer = () => new Promise(() => {});

const FILE_PATH = /^\/api\/dspm\/saas-files\/([0-9a-f]{24})\/([a-z]+)$/;
const requestsTo = (endpoint: string) =>
  get.mock.calls.map(([raw]) => new URL(String(raw), 'http://localhost').pathname)
    .filter(path => FILE_PATH.exec(path)?.[2] === endpoint);

beforeEach(() => {
  answers = {
    overview: () => overview(),
    activity: () => ({ entries: [] }),
    lineage: () => lineage(),
    copies: () => copies([], 0),
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
  vi.clearAllMocks();
});

// ─── harness: the file drawer over a root drawer, opened from a "row" ────────────────────────────

type HarnessProps = Partial<SaasFileDrawerProps> & { onRootClose: () => void };

function Harness({ onRootClose, ...props }: HarnessProps) {
  const rootClose = useRef<HTMLButtonElement>(null);
  const [opener, setOpener] = useState<HTMLButtonElement | null>(null);
  const [open, setOpen] = useState(true);
  const close = useCallback(() => setOpen(false), []);
  return (
    <DrawerShell label="Pat Example" onClose={onRootClose} initialFocus={rootClose}>
      <button ref={rootClose} type="button">Close datastore</button>
      <button ref={setOpener} type="button">file row</button>
      {open && opener && (
        <SaasFileDrawer
          fileId={FILE_ID}
          fileName={GMAIL_NAME}
          datastoreName="Pat Example"
          driveType="GMAIL"
          initialTab="sensitives"
          returnFocus={opener}
          onClose={close}
          {...props}
        />
      )}
    </DrawerShell>
  );
}

async function renderFileDrawer(props: Partial<SaasFileDrawerProps> = {}) {
  const onRootClose = vi.fn();
  const view = render(<Harness onRootClose={onRootClose} {...props} />);
  const drawer = await screen.findByRole('dialog', { name: props.fileName ?? GMAIL_NAME });
  return { ...view, onRootClose, drawer };
}

const renderDrive = (props: Partial<SaasFileDrawerProps> = {}) => {
  answers.overview = () => driveOverview();
  return renderFileDrawer({ driveType: 'GOOGLE_WORKSPACE', fileName: DRIVE_NAME, ...props });
};

const tabNames = (drawer: HTMLElement) => within(drawer).getAllByRole('tab').map(tab => tab.textContent);
const panelOf = (drawer: HTMLElement) => within(drawer).getByRole('tabpanel');
const openTab = (user: ReturnType<typeof userEvent.setup>, drawer: HTMLElement, name: string) =>
  user.click(within(drawer).getByRole('tab', { name }));
const bodyRows = (table: HTMLElement) =>
  (within(table).getAllByRole('row').slice(1) as HTMLTableRowElement[]).map(row => [...row.cells].map(cell => cell.textContent));

/** A greyed console-only control: aria-disabled, with the shared tooltip on hover. */
function expectGreyed(control: HTMLElement) {
  expect(control).toHaveAttribute('aria-disabled', 'true');
  const tooltip = document.getElementById(control.getAttribute('aria-describedby') ?? '') as HTMLElement;
  fireEvent.mouseEnter(control);
  expect(tooltip).toBeVisible();
  expect(tooltip).toHaveTextContent(CONSOLE_ONLY_TOOLTIP);
  fireEvent.mouseLeave(control);
}

/** The value cell under an InfoStrip label. */
const stripValue = (drawer: HTMLElement, label: string) =>
  within(drawer).getByText(label, { selector: 'div' }).nextElementSibling as HTMLElement;

// ─── tests ───────────────────────────────────────────────────────────────────────────────────────

describe('SaasFileDrawer header, card and Sharing', () => {
  it('shows the name, the file type and the greyed Take Action, with focus on the close X', async () => {
    const { drawer } = await renderFileDrawer();
    expect(await within(drawer).findByText('PDF', { selector: 'p' })).toBeInTheDocument();
    expect(within(drawer).getByRole('heading', { level: 2, name: GMAIL_NAME })).toBeInTheDocument();
    expect(within(drawer).getByRole('button', { name: 'Close' })).toHaveFocus();
    expectGreyed(within(drawer).getByRole('button', { name: 'Take Action' }));
    expect(requestsTo('overview')).toHaveLength(1);
    expect(requestsTo('lineage')).toHaveLength(0);
  });

  it('draws an envelope for an Outlook message', async () => {
    answers.overview = () => overview({ name: 'Re: travel plans', fileExtension: 'msg', fileTypeLabel: 'Outlook Message' });
    const { drawer } = await renderFileDrawer({ fileName: 'Re: travel plans' });
    expect(await within(drawer).findByText('Outlook Message')).toBeInTheDocument();
    expect(drawer.querySelector('[data-icon="mdi:email-outline"]')).not.toBeNull();
  });

  it('shows the card with the Drive logo for GOOGLE_WORKSPACE, and Sharing with the single tag "Private"', async () => {
    const { drawer } = await renderDrive();
    const account = await within(drawer).findByText('example-workspace');
    expect(account.parentElement?.querySelector('[data-icon="logos:google-drive"]')).not.toBeNull();
    expect(stripValue(drawer, 'Owner')).toHaveTextContent(OWNER);
    expect(stripValue(drawer, 'Category')).toHaveTextContent('Legal');
    const documentClass = within(stripValue(drawer, 'Document Class')).getByText('General Contracts & Agreements');
    expect(documentClass).toHaveAttribute('title', 'General Contracts & Agreements');
    expect(within(drawer).getByRole('region', { name: 'Sharing' })).toHaveTextContent(/^SharingPrivate$/);
  });

  it("lists a Gmail file's internal e-mails and the Gmail mark", async () => {
    const { drawer } = await renderFileDrawer();
    const account = await within(drawer).findByText('example-workspace');
    expect(account.parentElement?.querySelector('[data-icon="logos:google-gmail"]')).not.toBeNull();
    const sharing = within(drawer).getByRole('region', { name: 'Sharing' });
    expect(within(sharing).getByText('Internal Emails')).toBeInTheDocument();
    expect(within(sharing).getByText('sam@example.com')).toBeInTheDocument();
    expect(within(sharing).getByText('lee@example.com')).toBeInTheDocument();
    expect(within(sharing).queryByText('Private')).toBeNull();
  });

  it('shows every list and flag in order, or "-" without any', async () => {
    const all: SaasFileOverview['sharing'] = {
      internalEmails: ['sam@example.com'], externalEmails: ['guest@example.org'], internalGroups: ['finance@example.com'],
      externalGroups: ['partners@example.org'], privateShare: true, publicShare: true, orgWide: true, externalShare: true,
    };
    answers.overview = () => overview({ sharing: all });
    const first = await renderFileDrawer();
    await within(first.drawer).findByText('guest@example.org');
    expect(within(first.drawer).getByRole('region', { name: 'Sharing' })).toHaveTextContent(
      /^SharingInternal Emailssam@example\.comExternal Emailsguest@example\.orgInternal Groupsfinance@example\.comExternal Groupspartners@example\.orgPrivatePublicOrg-wideShared externally$/,
    );
    first.unmount();

    answers.overview = () => overview({
      sharing: { ...all, internalEmails: [], externalEmails: [], internalGroups: [], externalGroups: [], privateShare: false, publicShare: false, orgWide: false, externalShare: false },
    });
    const second = await renderFileDrawer();
    await within(second.drawer).findByText('example-workspace');
    expect(within(second.drawer).getByRole('region', { name: 'Sharing' })).toHaveTextContent(/^Sharing-$/);
  });

  it('lists the Gmail five and the Drive six tabs before the overview resolves', async () => {
    answers.overview = pending;
    const gmail = await renderFileDrawer();
    expect(tabNames(gmail.drawer)).toEqual(['Sensitives', 'Details', 'File Activity', 'Lineage', 'Related Files']);
    expect(within(gmail.drawer).getByRole('tab', { name: 'Sensitives' })).toHaveAttribute('aria-selected', 'true');
    expect(within(gmail.drawer).getAllByText('Loading…')).toHaveLength(2);   // the card and the tab
    gmail.unmount();

    const drive = await renderFileDrawer({ driveType: 'GOOGLE_WORKSPACE', fileName: DRIVE_NAME });
    expect(tabNames(drive.drawer)).toEqual(['Sensitives', 'Details', 'File Activity', 'Lineage', 'File Copies', 'Related Files']);
  });
});

describe('SaasFileDrawer tabs', () => {
  it('lists the Sensitives with "Columns 2", a search and the footer', async () => {
    const user = userEvent.setup();
    const { drawer } = await renderFileDrawer();
    const table = await within(drawer).findByRole('table');
    expect(within(table).getAllByRole('columnheader').map(th => th.textContent)).toEqual(['Data Element', 'Sensitives']);
    expect(bodyRows(table)).toEqual([['PERSON NAME', '6Personal'], ['EMAIL ADDRESS', '4Personal']]);
    expect(within(drawer).getByRole('button', { name: 'Columns 2' })).toBeInTheDocument();
    expect(within(drawer).getByText('Showing 1 - 2 of 2')).toBeInTheDocument();

    await user.type(within(drawer).getByRole('searchbox', { name: 'Search' }), 'email');
    expect(bodyRows(table)).toEqual([['EMAIL ADDRESS', '4Personal']]);
    await user.clear(within(drawer).getByRole('searchbox', { name: 'Search' }));
    await user.type(within(drawer).getByRole('searchbox', { name: 'Search' }), 'nothing');
    expect(within(table).getByText('No matches')).toBeInTheDocument();
  });

  it('says so when the file has no sensitive data', async () => {
    answers.overview = () => overview({ sensitives: [] });
    const { drawer } = await renderFileDrawer();
    expect(await within(drawer).findByText('No sensitive data found.')).toBeInTheDocument();
  });

  it('shows the Details rows, the text metadata only', async () => {
    const user = userEvent.setup();
    const { drawer } = await renderFileDrawer();
    await within(drawer).findByText('PERSON NAME');
    await openTab(user, drawer, 'Details');
    const panel = panelOf(drawer);
    expect([...panel.querySelectorAll('dt')].map(dt => dt.textContent)).toEqual([
      'Last Modified', 'File Size', 'Sensitive Records', 'Policy Violations', 'Structured', 'Id', 'Threadid', 'Mailfoldertype',
    ]);
    const values = [...panel.querySelectorAll('dd')].map(dd => dd.textContent);
    expect(values[0]).toMatch(/^10 Sep 2026, 12:54 PM.+ ago by pat@example\.com$/);
    expect(values.slice(1)).toEqual(['0 Bytes', '10', '0', 'Yes', 'msg-0001', 'thread-0001', 'Sent']);
    expect(panel).not.toHaveTextContent(/Headers|Labelids|Sizeestimate|Internaldate/);
  });

  it('shows File Activity empty, loaded on its first activation', async () => {
    const user = userEvent.setup();
    const { drawer } = await renderFileDrawer();
    await within(drawer).findByText('PERSON NAME');
    expect(requestsTo('activity')).toHaveLength(0);
    await openTab(user, drawer, 'File Activity');
    expect(await within(drawer).findByText('No file activity yet')).toBeInTheDocument();
    expect(within(drawer).getByText('Actions taken on this file will appear here.')).toBeInTheDocument();
    await openTab(user, drawer, 'Sensitives');
    await openTab(user, drawer, 'File Activity');
    expect(requestsTo('activity')).toHaveLength(1);
  });

  it('lists the File Activity entries', async () => {
    answers.activity = () => ({
      entries: [
        { title: 'Label Applied', status: 'Completed', actor: 'sam@example.com', occurredAt: local(2026, 10, 2, 9, 30) },
        { title: 'Access Revoked', status: 'Failed', actor: null, occurredAt: null },
      ],
    });
    const { drawer } = await renderFileDrawer({ initialTab: 'activity' });
    const list = await within(drawer).findByRole('list', { name: 'File activity' });
    expect(within(list).getAllByRole('listitem').map(item => item.textContent)).toEqual([
      'Label AppliedCompletedsam@example.com02 Oct 2026, 09:30 AM',
      'Access RevokedFailed--',
    ]);
  });
});

describe('SaasFileDrawer when the overview fails', () => {
  it('reads a removed file in the file wording, without Retry, while the other tabs still load', async () => {
    const user = userEvent.setup();
    answers.overview = fail(404, 'ASSET_NOT_FOUND');
    answers.copies = () => DRIVE_COPIES;
    const { drawer } = await renderFileDrawer({ driveType: 'GOOGLE_WORKSPACE', fileName: DRIVE_NAME });

    expect(await within(drawer).findByText(NOT_FOUND)).toBeInTheDocument();
    expect(within(drawer).queryByRole('button', { name: 'Retry' })).toBeNull();
    expect(within(drawer).getByRole('heading', { level: 2, name: DRIVE_NAME })).toBeInTheDocument();
    expect(within(drawer).queryByText('DOCX', { selector: 'p' })).toBeNull();
    for (const label of ['Source/Account', 'Owner', 'Category', 'Document Class']) {
      expect(stripValue(drawer, label)).toHaveTextContent(/^-$/);
    }
    expect(within(drawer).getByRole('region', { name: 'Sharing' })).toHaveTextContent(/^Sharing-$/);
    expectGreyed(within(drawer).getByRole('button', { name: 'Take Action' }));

    for (const tab of ['Details', 'Lineage']) {
      await openTab(user, drawer, tab);
      expect(within(panelOf(drawer)).getByText(NOT_FOUND)).toBeInTheDocument();
      expect(within(panelOf(drawer)).queryByRole('button', { name: 'Retry' })).toBeNull();
    }

    await openTab(user, drawer, 'File Activity');
    expect(await within(drawer).findByText('No file activity yet')).toBeInTheDocument();
    await openTab(user, drawer, 'File Copies');
    expect(await within(drawer).findByText('offer-letter-copy.docx')).toBeInTheDocument();
    await openTab(user, drawer, 'Related Files');
    const panel = panelOf(drawer);
    expect(within(panel).getByText(/^Related files for/).closest('p')).toHaveTextContent(/^Related files for Pat Example \(matched by file owner\)$/);
    expect(within(panel).queryByText('Same person')).toBeNull();
    expect(requestsTo('overview')).toHaveLength(1);
  });

  it('offers Retry on an upstream failure, which loads the overview again', async () => {
    const user = userEvent.setup();
    let attempt = 0;
    answers.overview = () => {
      attempt += 1;
      if (attempt === 1) throw httpError(502, 'UPSTREAM_UNAVAILABLE');
      return overview();
    };
    const { drawer } = await renderFileDrawer();
    expect(await within(drawer).findByText(UPSTREAM)).toBeInTheDocument();
    await user.click(within(drawer).getByRole('button', { name: 'Retry' }));
    expect(await within(drawer).findByText('PERSON NAME')).toBeInTheDocument();
    expect(within(drawer).getByText('example-workspace')).toBeInTheDocument();
    expect(requestsTo('overview')).toHaveLength(2);
  });
});

describe('SaasFileDrawer Lineage on a Gmail file', () => {
  it('replicates the console: title, subtitle, note, greyed dropdown, the Gmail lane, the popover and the footer', async () => {
    const user = userEvent.setup();
    const { drawer } = await renderFileDrawer({ initialTab: 'lineage' });
    const panel = panelOf(drawer);
    expect(await within(panel).findByRole('heading', { name: `Lineage for ${GMAIL_NAME}` })).toBeInTheDocument();
    expect(within(panel).getByText('Trace where this attachment came from.')).toBeInTheDocument();
    expect(within(panel).getByRole('note')).toHaveTextContent(GMAIL_LINEAGE_NOTE);
    expect(within(panel).getByRole('note').textContent).toBe(GMAIL_LINEAGE_NOTE);
    expectGreyed(within(panel).getByRole('button', { name: 'Related files: Medium and above' }));
    expect(within(panel).getByRole('searchbox', { name: 'Search an event' })).toBeInTheDocument();
    expect(within(panel).getByRole('button', { name: 'Full screen' })).toBeInTheDocument();
    expect(within(panel).queryByRole('button', { name: 'Zoom in' })).toBeNull();
    expect(within(panel).queryByRole('button', { name: 'More information' })).toBeNull();

    const lanes = await within(panel).findByRole('group', { name: 'Lineage lanes' });
    expect([...lanes.querySelectorAll('[data-lane]')].map(el => el.getAttribute('data-lane'))).toEqual(['gmail-origin']);
    const laneRow = lanes.querySelector('[data-lane="gmail-origin"]') as HTMLElement;
    expect(laneRow.querySelector('[data-icon="logos:google-gmail"]')).not.toBeNull();
    expect(within(laneRow).getByText(GMAIL_NAME)).toBeInTheDocument();
    expect(within(laneRow).getByText(OWNER)).toBeInTheDocument();
    expect(within(laneRow).getByRole('button', { name: 'Copy file name' })).toBeInTheDocument();

    const card = within(lanes).getByRole('button', { name: /^Attached to Email/ });
    expect(card).toHaveTextContent('Attached to Emailpat · 10 Sept 2026, 12:54');
    await user.click(card);
    const popover = await screen.findByRole('dialog', { name: 'Event details' });
    const rows = [...popover.querySelectorAll('dl > div')].map(row => row.textContent);
    expect(rows).toEqual(['ActorpatInternal', `Email${OWNER}`, `File${GMAIL_NAME}`, 'ChannelGmail']);
    expectGreyed(within(popover).getByRole('button', { name: 'View full details →' }));

    expect(within(panel).getByText(NO_RELATED_FILES_TEXT)).toBeInTheDocument();
    expect(requestsTo('lineage')).toHaveLength(1);
  });

  it('draws the Drive copy lanes with their First Seen events after the Gmail lane, and hides the footer', async () => {
    answers.lineage = () => lineage({
      lanes: [
        lane('copy-0', { fileName: GMAIL_NAME, self: true, driveType: 'GMAIL', channelLabel: 'Gmail', firstSeenAt: local(2026, 9, 10, 12, 0) }),
        lane('copy-1', { fileName: 'itinerary-copy.pdf', firstSeenAt: local(2026, 9, 12, 9, 15) }),
        lane('copy-2', { fileName: 'itinerary-final.pdf', ownerEmail: 'sam@example.com', firstSeenAt: local(2026, 9, 13, 8, 5) }),
      ],
    });
    const { drawer } = await renderFileDrawer({ initialTab: 'lineage' });
    const panel = panelOf(drawer);
    const lanes = await within(panel).findByRole('group', { name: 'Lineage lanes' });
    await waitFor(() => expect(lanes.querySelectorAll('[data-lane]')).toHaveLength(3));
    expect([...lanes.querySelectorAll('[data-lane]')].map(el => el.getAttribute('data-lane'))).toEqual(['gmail-origin', 'copy-1', 'copy-2']);
    const copyLane = lanes.querySelector('[data-lane="copy-1"]') as HTMLElement;
    expect(copyLane.querySelector('[data-icon="logos:google-drive"]')).not.toBeNull();
    const firstSeen = within(lanes).getAllByRole('button', { name: /^First Seen/ }).map(button => button.textContent);
    expect(firstSeen).toEqual(['First Seenpat · 12 Sept 2026, 09:15', 'First Seensam · 13 Sept 2026, 08:05']);
    // The own copy's First Seen is not drawn on the Gmail lane (A33).
    expect([...lanes.querySelectorAll('[data-event-lane="gmail-origin"]')].map(card => card.textContent))
      .toEqual(['Attached to Emailpat · 10 Sept 2026, 12:54']);
    expect(within(panel).queryByText(NO_RELATED_FILES_TEXT)).toBeNull();
  });

  it('keeps the Gmail lane and note on a lineage failure, with Retry and no footer (review fix R6)', async () => {
    const user = userEvent.setup();
    let attempt = 0;
    answers.lineage = () => {
      attempt += 1;
      if (attempt === 1) throw httpError(502, 'BAD_UPSTREAM_RESPONSE');
      return lineage();
    };
    const { drawer } = await renderFileDrawer({ initialTab: 'lineage' });
    const panel = panelOf(drawer);
    expect(await within(panel).findByText(UPSTREAM)).toBeInTheDocument();
    expect(within(panel).getByRole('note')).toHaveTextContent(GMAIL_LINEAGE_NOTE);
    const lanes = within(panel).getByRole('group', { name: 'Lineage lanes' });
    expect(within(lanes).getByRole('button', { name: /^Attached to Email/ })).toBeInTheDocument();
    expect(within(panel).queryByText(NO_RELATED_FILES_TEXT)).toBeNull();

    await user.click(within(panel).getByRole('button', { name: 'Retry' }));
    expect(await within(panel).findByText(NO_RELATED_FILES_TEXT)).toBeInTheDocument();
    expect(within(panel).queryByText(UPSTREAM)).toBeNull();
    expect(requestsTo('lineage')).toHaveLength(2);
  });

  it('filters the events by the search, keeping the lanes and the footer', async () => {
    const user = userEvent.setup();
    const { drawer } = await renderFileDrawer({ initialTab: 'lineage' });
    const panel = panelOf(drawer);
    await within(panel).findByText(NO_RELATED_FILES_TEXT);
    await user.type(within(panel).getByRole('searchbox', { name: 'Search an event' }), 'downloaded');
    expect(within(panel).queryByRole('button', { name: /^Attached to Email/ })).toBeNull();
    expect(within(panel).getByText('No events match your search.')).toBeInTheDocument();
    expect(panel.querySelectorAll('[data-lane]')).toHaveLength(1);
    expect(within(panel).getByText(NO_RELATED_FILES_TEXT)).toBeInTheDocument();
  });
});

describe('SaasFileDrawer Lineage on a Drive file', () => {
  beforeEach(() => {
    answers.lineage = () => lineage({ coverageNote: COVERAGE, lanes: DRIVE_LANES });
  });

  it('draws the copy lanes in API order with the own lane tinted, the zoom and the coverage note', async () => {
    const user = userEvent.setup();
    const { drawer } = await renderDrive({ initialTab: 'lineage' });
    const panel = panelOf(drawer);
    const lanes = await within(panel).findByRole('group', { name: 'Lineage lanes' });
    await waitFor(() => expect(lanes.querySelectorAll('[data-lane]')).toHaveLength(4));
    expect(within(panel).getByRole('heading', { name: `Lineage for ${DRIVE_NAME}` })).toBeInTheDocument();
    expect(within(panel).getByText('Trace where this file came from.')).toBeInTheDocument();
    expect(within(panel).queryByRole('note')).toBeNull();

    const laneRows = [...lanes.querySelectorAll<HTMLElement>('[data-lane]')];
    expect(laneRows.map(el => el.getAttribute('data-lane'))).toEqual(['copy-0', 'copy-1', 'copy-2', 'copy-3']);
    expect(laneRows.map(el => el.classList.contains('bg-rose-50'))).toEqual([false, true, false, false]);
    expect(laneRows[0].querySelector('[data-icon="logos:google-gmail"]')).not.toBeNull();
    expect(within(lanes).getAllByRole('button', { name: /^First Seen/ })).toHaveLength(4);
    expect(within(panel).queryByText(NO_RELATED_FILES_TEXT)).toBeNull();

    expect(within(panel).getByRole('button', { name: 'Zoom in' })).toBeInTheDocument();
    expect(within(panel).getByText('100%')).toBeInTheDocument();

    // The ⓘ is the shared react-aria InfoTooltip, which opens on keyboard focus.
    const info = within(panel).getByRole('button', { name: 'More information' });
    await user.keyboard('{Shift}');
    act(() => info.focus());
    expect(await screen.findByRole('tooltip')).toHaveTextContent(COVERAGE);
  });

  it('draws the popover outside the scaled drawing and closes it on a zoom click and on a scroll', async () => {
    const user = userEvent.setup();
    const { drawer } = await renderDrive({ initialTab: 'lineage' });
    const panel = panelOf(drawer);
    const lanes = await within(panel).findByRole('group', { name: 'Lineage lanes' });
    const card = (await within(lanes).findAllByRole('button', { name: /^First Seen/ }))[0];

    await user.click(card);
    const popover = await screen.findByRole('dialog', { name: 'Event details' });
    expect([...popover.querySelectorAll('dl > div')].map(row => row.textContent)).toEqual([
      'Actorpat', `Email${OWNER}`, 'Fileoffer-letter.pdf', 'ChannelGmail',
    ]);
    const scaled = [...panel.querySelectorAll<HTMLElement>('div')].find(el => el.style.transform.includes('scale('));
    expect(scaled).toBeDefined();
    expect(scaled!.contains(card)).toBe(true);
    expect(scaled!.contains(popover)).toBe(false);

    await user.click(within(panel).getByRole('button', { name: 'Zoom out' }));
    expect(screen.queryByRole('dialog', { name: 'Event details' })).toBeNull();
    expect(within(panel).getByText('90%')).toBeInTheDocument();
    expect(scaled!.style.transform).toBe('scale(0.9)');

    await user.click(within(lanes).getAllByRole('button', { name: /^First Seen/ })[1]);
    expect(await screen.findByRole('dialog', { name: 'Event details' })).toBeInTheDocument();
    fireEvent.scroll(lanes);
    expect(screen.queryByRole('dialog', { name: 'Event details' })).toBeNull();
  });

  it('closes the popover, then full screen, then the file drawer on Escape', async () => {
    const user = userEvent.setup();
    const { drawer, onRootClose } = await renderDrive({ initialTab: 'lineage' });
    const panel = panelOf(drawer);
    await within(panel).findAllByRole('button', { name: /^First Seen/ });

    await user.click(within(panel).getByRole('button', { name: 'Full screen' }));
    const frame = await screen.findByRole('dialog', { name: `Lineage for ${DRIVE_NAME}` });
    expect(within(frame).getByRole('button', { name: 'Exit full screen' })).toHaveFocus();
    await user.click(within(frame).getAllByRole('button', { name: /^First Seen/ })[2]);
    expect(await screen.findByRole('dialog', { name: 'Event details' })).toBeInTheDocument();

    await user.keyboard('{Escape}');
    expect(screen.queryByRole('dialog', { name: 'Event details' })).toBeNull();
    expect(screen.getByRole('dialog', { name: `Lineage for ${DRIVE_NAME}` })).toBeInTheDocument();

    await user.keyboard('{Escape}');
    expect(screen.queryByRole('dialog', { name: `Lineage for ${DRIVE_NAME}` })).toBeNull();
    expect(screen.getByRole('dialog', { name: DRIVE_NAME })).toBeInTheDocument();
    await waitFor(() => expect(within(panelOf(drawer)).getByRole('button', { name: 'Full screen' })).toHaveFocus());

    await user.keyboard('{Escape}');
    expect(screen.queryByRole('dialog', { name: DRIVE_NAME })).toBeNull();
    expect(screen.getByRole('button', { name: 'file row' })).toHaveFocus();
    expect(onRootClose).not.toHaveBeenCalled();
  });

  it('shows "No lineage recorded for this file." and the footer when lineage is unavailable', async () => {
    answers.lineage = () => lineage({ available: false });
    const { drawer } = await renderDrive({ initialTab: 'lineage' });
    const panel = panelOf(drawer);
    expect(await within(panel).findByText('No lineage recorded for this file.')).toBeInTheDocument();
    expect(within(panel).getByText(NO_RELATED_FILES_TEXT)).toBeInTheDocument();
    expect(within(panel).queryByRole('group', { name: 'Lineage lanes' })).toBeNull();
  });

  it('shows the error with Retry in place of the lanes on a lineage failure', async () => {
    answers.lineage = fail(502, 'UPSTREAM_UNAVAILABLE');
    const { drawer } = await renderDrive({ initialTab: 'lineage' });
    const panel = panelOf(drawer);
    expect(await within(panel).findByText(UPSTREAM)).toBeInTheDocument();
    expect(within(panel).getByRole('button', { name: 'Retry' })).toBeInTheDocument();
    expect(within(panel).queryByRole('group', { name: 'Lineage lanes' })).toBeNull();
    expect(within(panel).queryByText(NO_RELATED_FILES_TEXT)).toBeNull();
  });
});

describe('SaasFileDrawer File Copies and Related Files', () => {
  it('has no File Copies tab on a Gmail file', async () => {
    const { drawer } = await renderFileDrawer();
    expect(within(drawer).queryByRole('tab', { name: 'File Copies' })).toBeNull();
  });

  it('shows the cards, rows, search, the greyed "+ More filters" and "Columns 4"', async () => {
    const user = userEvent.setup();
    answers.copies = () => DRIVE_COPIES;
    const { drawer } = await renderDrive();
    await openTab(user, drawer, 'File Copies');
    const panel = panelOf(drawer);
    const table = await within(panel).findByRole('table');
    expect(within(panel).getByText('Copies Found').nextElementSibling).toHaveTextContent(/^3$/);
    expect(within(panel).getByText('Locations').nextElementSibling).toHaveTextContent(/^2$/);
    expect(within(table).getAllByRole('columnheader').map(th => th.textContent)).toEqual(['File', 'File Path', 'Datastore', 'User/Device Name']);
    expect(bodyRows(table)).toEqual([
      ['offer-letter.pdfPDF', '-', 'Pat ExampleSaaS · Gmail', OWNER],
      ['offer-letter-copy.docxDOCX', '-', 'Pat ExampleSaaS · Google Drive', OWNER],
      ['offer-letter-signed.pdfPDF', '-', 'Sam LeeSaaS · Google Drive', 'sam@example.com'],
    ]);
    const datastoreCells = (within(table).getAllByRole('row').slice(1) as HTMLTableRowElement[]).map(row => row.cells[2]);
    expect(datastoreCells[0].querySelector('[data-icon="logos:google-gmail"]')).not.toBeNull();
    expect(datastoreCells[1].querySelector('[data-icon="logos:google-drive"]')).not.toBeNull();
    expectGreyed(within(panel).getByRole('button', { name: 'More filters' }));
    expect(within(panel).getByRole('button', { name: 'Columns 4' })).toBeInTheDocument();
    expect(within(panel).getByText('Showing 1 - 3 of 3')).toBeInTheDocument();

    await user.type(within(panel).getByRole('searchbox', { name: 'Search' }), 'sam lee');
    expect(bodyRows(table).map(row => row[0])).toEqual(['offer-letter-signed.pdfPDF']);
    await user.clear(within(panel).getByRole('searchbox', { name: 'Search' }));
    await user.type(within(panel).getByRole('searchbox', { name: 'Search' }), 'COPY.DOCX');
    expect(bodyRows(table).map(row => row[0])).toEqual(['offer-letter-copy.docxDOCX']);
    await user.clear(within(panel).getByRole('searchbox', { name: 'Search' }));
    await user.type(within(panel).getByRole('searchbox', { name: 'Search' }), 'nothing-like-this');
    expect(within(table).getByText('No matches')).toBeInTheDocument();
  });

  it.each([
    ['no copies', copies([], 0)],
    ['lineage unavailable', copies([], 0, false)],
  ])('reads 0 and "No copies found" with %s', async (_case, answer) => {
    const user = userEvent.setup();
    answers.copies = () => answer;
    const { drawer } = await renderDrive();
    await openTab(user, drawer, 'File Copies');
    const panel = panelOf(drawer);
    expect(await within(panel).findByText('No copies found')).toBeInTheDocument();
    expect(within(panel).getByText('Copies Found').nextElementSibling).toHaveTextContent(/^0$/);
    expect(within(panel).getByText('Locations').nextElementSibling).toHaveTextContent(/^0$/);
  });

  it('shares one copies request between File Copies and Related Files', async () => {
    const user = userEvent.setup();
    answers.copies = () => DRIVE_COPIES;
    const { drawer } = await renderDrive();
    await within(drawer).findByText('PERSON NAME');
    expect(requestsTo('copies')).toHaveLength(0);
    await openTab(user, drawer, 'File Copies');
    await within(panelOf(drawer)).findByText('offer-letter-copy.docx');
    await openTab(user, drawer, 'Related Files');
    expect(await within(panelOf(drawer)).findAllByText('Content hash verified')).toHaveLength(3);
    await openTab(user, drawer, 'File Copies');
    expect(requestsTo('copies')).toHaveLength(1);
  });

  it('lists the related files with the owner heading, "Same person" and Why', async () => {
    const user = userEvent.setup();
    answers.copies = () => copies([
      copy('copy-1', { fileName: 'itinerary-copy.pdf' }),
      copy('copy-2', { fileName: 'itinerary-final.pdf', ownerName: 'Sam Lee', ownerEmail: 'sam@example.com' }),
    ], 1);
    const { drawer } = await renderFileDrawer();
    await within(drawer).findByText('PERSON NAME');
    await openTab(user, drawer, 'Related Files');
    const panel = panelOf(drawer);
    const table = await within(panel).findByRole('table');
    expect(within(panel).getByText(/^Related files for/).closest('p')).toHaveTextContent(
      /^Related files for Pat Example — pat@example\.com \(matched by file owner\)$/,
    );
    expect(within(panel).getByText('Pat Example', { selector: 'strong' })).toBeInTheDocument();
    expect(within(table).getAllByRole('columnheader').map(th => th.textContent)).toEqual(['Source', 'File', 'Where', 'Confidence', 'Why']);
    expect(bodyRows(table)).toEqual([
      ['Google Drive', 'itinerary-copy.pdf', `Pat Example${OWNER}Same person`, 'Content hash verified', 'Same content hash · Same person'],
      ['Google Drive', 'itinerary-final.pdf', 'Sam Leesam@example.com', 'Content hash verified', 'Same content hash'],
    ]);
    const firstRow = within(table).getAllByRole('row')[1] as HTMLTableRowElement;
    expect(firstRow.cells[0].querySelector('[data-icon="logos:google-drive"]')).not.toBeNull();
    expect(within(firstRow.cells[4]).getByText('Same content hash · Same person')).toHaveAttribute('title', 'Same content hash · Same person');
  });

  it('says so when there is no related file', async () => {
    const user = userEvent.setup();
    const { drawer } = await renderFileDrawer();
    await within(drawer).findByText('PERSON NAME');
    await openTab(user, drawer, 'Related Files');
    expect(await within(panelOf(drawer)).findByText('No related files found in other sources.')).toBeInTheDocument();
  });
});
