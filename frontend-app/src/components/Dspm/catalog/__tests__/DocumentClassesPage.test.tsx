import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { DspmStatus } from '../../../../types/dspm';
import type { DocumentClassList } from '../../../../types/dspmPages';

vi.mock('../../../../utils/apiClient', () => ({ default: { get: vi.fn() } }));

import apiClient from '../../../../utils/apiClient';
import DocumentClassesPage from '../DocumentClassesPage';
import { CONSOLE_ONLY_TOOLTIP } from '../../datastores/ui/ConsoleOnly';

const get = apiClient.get as unknown as ReturnType<typeof vi.fn>;

const READY: DspmStatus = {
  ready: true, entitled: true, configured: true, reason: null, message: null,
  projectName: 'Example workspace', lastVerifiedAt: null,
};
const NOT_READY: DspmStatus = {
  ready: false, entitled: true, configured: false, reason: 'NOT_CONFIGURED', message: null,
  projectName: null, lastVerifiedAt: null,
};

// 11 invented categories of 3 classes each; one class name holds "Invoice".
const LETTERS = 'ABCDEFGHIJK'.split('');
const CATEGORIES = LETTERS.map(letter => `Group ${letter}`);
const LIST: DocumentClassList = {
  categories: CATEGORIES,
  rows: CATEGORIES.flatMap((category, i) => [1, 2, 3].map(n => ({
    name: i === 1 && n === 2 ? 'Supplier Invoices' : `Class ${LETTERS[i]}${n}`,
    category,
  }))),
};

let answer: () => Promise<unknown>;

beforeEach(() => {
  answer = async () => LIST;
  get.mockImplementation(async (url: string) => {
    if (url === '/api/dspm/document-classes') return answer();
    throw new Error(`HTTP 404: unexpected ${url}`);
  });
});

afterEach(() => vi.clearAllMocks());

const renderPage = (status: DspmStatus = READY) => render(<DocumentClassesPage status={status} onRetry={() => {}} />);
const bodyRows = () => screen.getAllByRole('row').slice(1);

describe('DocumentClassesPage', () => {
  it('lists the 33 classes 10 at a time, with the console header and a greyed Add', async () => {
    renderPage();
    expect(screen.getByRole('heading', { level: 1, name: 'Document Classes' })).toBeInTheDocument();
    expect(await screen.findByText('Class A1')).toBeInTheDocument();
    expect(bodyRows()).toHaveLength(10);
    expect(screen.getByText('Showing 1 - 10 of 33')).toBeInTheDocument();
    expect(get).toHaveBeenCalledWith('/api/dspm/document-classes');
    expect(screen.getAllByRole('columnheader').map(h => h.textContent)).toEqual(['Document Classes', 'Category']);
  });

  it('greys Add with the console-only tooltip, and a click sends nothing', async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByText('Class A1');
    const add = screen.getByRole('button', { name: 'Add' });
    expect(add).toHaveAttribute('aria-disabled', 'true');
    await user.hover(add);
    expect(screen.getByRole('tooltip')).toHaveTextContent(CONSOLE_ONLY_TOOLTIP);
    const calls = get.mock.calls.length;
    await user.click(add);
    expect(get.mock.calls.length).toBe(calls);
  });

  it('pages through the list', async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByText('Class A1');
    await user.click(screen.getByRole('button', { name: '4' }));
    expect(screen.getByText('Showing 31 - 33 of 33')).toBeInTheDocument();
    expect(bodyRows()).toHaveLength(3);
    expect(screen.getByText('Class K3')).toBeInTheDocument();
  });

  it('searches the class names: "invoice" leaves Supplier Invoices', async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByText('Class A1');
    await user.type(screen.getByRole('searchbox', { name: 'Search' }), 'invoice');
    expect(bodyRows()).toHaveLength(1);
    expect(within(bodyRows()[0]).getByText('Supplier Invoices')).toBeInTheDocument();
    expect(within(bodyRows()[0]).getByText('Group B')).toBeInTheDocument();
    expect(screen.getByText('Showing 1 - 1 of 1')).toBeInTheDocument();
  });

  it('shows the empty state when nothing matches', async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByText('Class A1');
    await user.type(screen.getByRole('searchbox', { name: 'Search' }), 'nothing like this');
    expect(screen.getByText('No document classes found')).toBeInTheDocument();
  });

  it('narrows to a category with the Category filter, whose options are the categories in route order', async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByText('Class A1');
    await user.click(screen.getByRole('button', { name: /^Category/ }));
    const dialog = screen.getByRole('dialog', { name: 'Category' });
    const options = within(dialog).getAllByRole('checkbox').slice(1);   // after "Select all"
    expect(options).toHaveLength(11);
    expect(options.map(o => o.closest('label')!.textContent)).toEqual(CATEGORIES.map(c => `${c} 3`));
    await user.click(within(dialog).getByRole('checkbox', { name: /Group C/ }));
    expect(bodyRows()).toHaveLength(3);
    expect(bodyRows().map(r => within(r).getAllByRole('cell')[0].textContent)).toEqual(['Class C1', 'Class C2', 'Class C3']);
    expect(screen.getByText('Showing 1 - 3 of 3')).toBeInTheDocument();
  });

  it('offers exactly Document Classes (always shown) and Category in the Columns chooser', async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByText('Class A1');
    await user.click(screen.getByRole('button', { name: /^Columns/ }));
    const boxes = screen.getAllByRole('checkbox');
    expect(boxes.map(b => b.closest('label')!.textContent)).toEqual(['Document Classes Always shown', 'Category']);
    expect(boxes[0]).toBeDisabled();
    await user.click(boxes[1]);
    expect(screen.getAllByRole('columnheader').map(h => h.textContent)).toEqual(['Document Classes']);
    expect(screen.queryByText('Group A')).toBeNull();
  });

  it('colours the class chip bar and the category dot alike, one colour per category', async () => {
    renderPage();
    await screen.findByText('Class A1');
    const [first] = bodyRows();
    const bar = first.querySelector('[data-colour]')!;
    expect(bar).toHaveClass('bg-sky-500');
    expect(within(first).getByText('Group A').querySelector('span')).toHaveClass('bg-sky-500');
    const fourth = bodyRows()[3];   // Group B
    expect(fourth.querySelector('[data-colour]')).toHaveClass('bg-violet-500');
  });

  it('shows the error with Retry, which asks again', async () => {
    const user = userEvent.setup();
    answer = async () => { throw new Error('HTTP 502: {"code":"UPSTREAM_UNAVAILABLE","message":"down"}'); };
    renderPage();
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Matters is not responding right now');
    answer = async () => LIST;
    await user.click(within(alert).getByRole('button', { name: 'Retry' }));
    expect(await screen.findByText('Class A1')).toBeInTheDocument();
  });

  it('explains a workspace that is not ready and asks for nothing', () => {
    renderPage(NOT_READY);
    expect(screen.queryByRole('heading', { level: 1, name: 'Document Classes' })).toBeNull();
    expect(get).not.toHaveBeenCalled();
  });
});
