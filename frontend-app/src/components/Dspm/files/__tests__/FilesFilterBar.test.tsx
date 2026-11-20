import { useState } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { DatastoreFilterOptions } from '../../../../types/dspmDatastores';
import type { DatastoreFilterState } from '../../datastores/datastoreFilters';
import { CONSOLE_ONLY_TOOLTIP } from '../../datastores/ui/ConsoleOnly';
import FilesFilterBar from '../FilesFilterBar';
import { ENDPOINT_FILTERS, SAAS_FILTERS, type FilesFilterConfig } from '../filesFilterConfig';

const opt = (value: string, label = value) => ({ value, label, count: null });

const APP_TYPES = [
  ['ONE_DRIVE', 'OneDrive'], ['SHARE_POINT', 'SharePoint'], ['OUTLOOK', 'Outlook'], ['GOOGLE_WORKSPACE', 'Google Drive'],
  ['GMAIL', 'Gmail'], ['JIRA', 'Jira'], ['GITLAB', 'Gitlab'], ['CONFLUENCE', 'Confluence'], ['SLACK', 'Slack'],
];

const SAAS_OPTIONS: DatastoreFilterOptions = {
  dimensions: [
    { key: 'sensitives', hasMore: false, options: [
      opt('PII', 'Personal'), opt('PCI', 'Financial'), opt('PHI', 'Health'), opt('SECRETS', 'IT & Security'),
    ] },
    { key: 'category', hasMore: false, options: [opt('financial', 'Financial'), opt('legal', 'Legal')] },
    { key: 'appType', hasMore: false, options: APP_TYPES.map(([value, label]) => opt(value, label)) },
    { key: 'documentClass', hasMore: false, options: [opt('invoice', 'Invoice'), opt('contract', 'Contract')] },
    { key: 'owner', hasMore: false, options: [opt('64b7f0c2a1d3e4f5a6b7c8d9', 'owner@example.com')] },
    { key: 'internalUser', hasMore: false, options: [opt('colleague@example.com')] },
    { key: 'externalUser', hasMore: false, options: [opt('partner@example.org')] },
    { key: 'sharing', hasMore: false, options: [
      opt('internal', 'Shared Internally'), opt('external', 'Shared Externally'), opt('public', 'Shared Publicly'),
    ] },
  ],
};

const ENDPOINT_OPTIONS: DatastoreFilterOptions = {
  dimensions: [
    { key: 'category', hasMore: false, options: [opt('financial')] },
    { key: 'sensitives', hasMore: false, options: [opt('PII', 'Personal')] },
    { key: 'documentClass', hasMore: false, options: [opt('payroll')] },
    { key: 'dataElement', hasMore: false, options: [opt('EMAIL ADDRESS', 'Email address')] },
    { key: 'tag', hasMore: false, options: [opt('confidential')] },
    { key: 'uid', hasMore: false, options: [opt('501')] },
    { key: 'hostname', hasMore: false, options: [opt('laptop-01')] },
    { key: 'lastModified', hasMore: false, options: [opt('48h', 'Last 48 hours'), opt('7d', 'Last 7 days'), opt('30d', 'Last 30 days')] },
  ],
};

function Harness({ config, options, onChange }: {
  config: FilesFilterConfig; options: DatastoreFilterOptions; onChange?: (next: DatastoreFilterState) => void;
}) {
  const [filters, setFilters] = useState<DatastoreFilterState>({});
  const [search, setSearch] = useState('');
  return (
    <FilesFilterBar
      config={config}
      searchInput={search}
      onSearchInput={setSearch}
      options={options}
      optionsLoading={false}
      optionsError={false}
      filters={filters}
      labelMemory={new Map()}
      onFiltersChange={next => { setFilters(next); onChange?.(next); }}
    />
  );
}

const dropdown = (name: string) => screen.getByRole('button', { name: new RegExp(`^${name}`) });
const leftList = () => within(screen.getByRole('list', { name: 'Filters' })).getAllByRole('button').map(b => b.textContent?.trim());

describe('FilesFilterBar', () => {
  it('draws "Search Files", the funnel and the SaaS quick dropdowns in order', () => {
    render(<Harness config={SAAS_FILTERS} options={SAAS_OPTIONS} />);
    const search = screen.getByPlaceholderText('Search Files');
    expect(search).toHaveAttribute('maxLength', '200');
    const buttons = screen.getAllByRole('button').map(b => b.textContent?.trim());
    expect(buttons).toEqual(['Sensitives', 'Document Class', 'Sharing Mode', 'More filters']);
  });

  it('lists Sensitives as category chips with Select all and Exclude disabled by admin', async () => {
    const onChange = vi.fn();
    const user = userEvent.setup();
    render(<Harness config={SAAS_FILTERS} options={SAAS_OPTIONS} onChange={onChange} />);
    await user.click(dropdown('Sensitives'));
    const dialog = screen.getByRole('dialog', { name: 'Sensitives' });
    const names = within(dialog).getAllByRole('checkbox').map(box => box.closest('label')?.textContent?.trim());
    expect(names).toEqual(['Select all', 'Personal', 'Financial', 'Health', 'IT & Security']);
    expect(within(dialog).getByText('Personal').className).toContain('bg-sky-50');
    expect(within(dialog).getByText('IT & Security').className).toContain('bg-orange-50');
    const mode = within(dialog).getByRole('combobox', { name: 'Include or exclude' });
    const exclude = within(mode).getByRole('option', { name: 'Exclude' });
    expect(exclude).toBeDisabled();
    expect(exclude).toHaveAttribute('title', CONSOLE_ONLY_TOOLTIP);
    expect(CONSOLE_ONLY_TOOLTIP).toBe('Disabled by admin');
    await user.click(within(dialog).getByRole('checkbox', { name: 'Select all' }));
    expect(onChange).toHaveBeenLastCalledWith({ sensitives: { mode: 'include', values: ['PII', 'PCI', 'PHI', 'SECRETS'] } });
  });

  it('makes Document Class a single choice with a search, a new choice replacing the previous one', async () => {
    const onChange = vi.fn();
    const user = userEvent.setup();
    render(<Harness config={SAAS_FILTERS} options={SAAS_OPTIONS} onChange={onChange} />);
    await user.click(dropdown('Document Class'));
    const dialog = screen.getByRole('dialog', { name: 'Document Class' });
    expect(within(dialog).getByPlaceholderText('Search')).toBeInTheDocument();
    expect(within(dialog).queryByRole('checkbox')).toBeNull();
    expect(within(dialog).queryByRole('combobox')).toBeNull();
    await user.click(within(dialog).getByRole('option', { name: 'Invoice' }));
    expect(onChange).toHaveBeenLastCalledWith({ documentClass: { mode: 'include', values: ['invoice'] } });
    await user.click(within(dialog).getByRole('option', { name: 'Contract' }));
    expect(onChange).toHaveBeenLastCalledWith({ documentClass: { mode: 'include', values: ['contract'] } });
    expect(dropdown('Document Class')).toHaveTextContent('1');
  });

  it('starts Sharing Mode on Any Access, which clears a choice', async () => {
    const onChange = vi.fn();
    const user = userEvent.setup();
    render(<Harness config={SAAS_FILTERS} options={SAAS_OPTIONS} onChange={onChange} />);
    await user.click(dropdown('Sharing Mode'));
    const dialog = screen.getByRole('dialog', { name: 'Sharing Mode' });
    expect(within(dialog).getAllByRole('option').map(o => o.textContent)).toEqual(
      ['Any Access', 'Shared Internally', 'Shared Externally', 'Shared Publicly']);
    expect(within(dialog).getByRole('option', { name: 'Any Access' })).toHaveAttribute('aria-selected', 'true');
    await user.click(within(dialog).getByRole('option', { name: 'Shared Externally' }));
    expect(onChange).toHaveBeenLastCalledWith({ sharing: { mode: 'include', values: ['external'] } });
    await user.click(within(dialog).getByRole('option', { name: 'Any Access' }));
    expect(onChange).toHaveBeenLastCalledWith({});
  });

  it('titles More filters and lists the SaaS dimensions in the console order, App Type with nine options', async () => {
    const onChange = vi.fn();
    const user = userEvent.setup();
    render(<Harness config={SAAS_FILTERS} options={SAAS_OPTIONS} onChange={onChange} />);
    await user.click(screen.getByRole('button', { name: 'More filters' }));
    const panel = screen.getByRole('dialog', { name: 'More filters' });
    expect(within(panel).getByRole('heading', { name: 'More filters' })).toBeInTheDocument();
    expect(leftList()).toEqual([
      'Sensitives', 'Category', 'App Type', 'Document Class', 'Owners', 'Internal Users', 'External Users', 'Sharing Mode',
    ]);
    await user.click(within(panel).getByRole('button', { name: 'App Type' }));
    const boxes = within(panel).getAllByRole('checkbox').slice(1);
    expect(boxes.map(box => box.closest('label')?.textContent?.trim())).toEqual(APP_TYPES.map(([, label]) => label));
    expect(within(panel).getByRole('option', { name: 'Exclude' })).toBeDisabled();
    await user.click(within(panel).getByRole('checkbox', { name: 'Gmail' }));
    expect(onChange).toHaveBeenLastCalledWith({ appType: { mode: 'include', values: ['GMAIL'] } });
    await user.click(within(panel).getByRole('button', { name: 'Owners' }));
    expect(within(panel).getByRole('checkbox', { name: 'owner@example.com' })).toBeEnabled();
    await user.click(within(panel).getByRole('button', { name: 'Sharing Mode' }));
    expect(within(panel).getByRole('option', { name: 'Any Access' })).toHaveAttribute('aria-selected', 'true');
  });

  it('lists the Endpoint dimensions in their order, Last Modified as a single choice without Any', async () => {
    const onChange = vi.fn();
    const user = userEvent.setup();
    render(<Harness config={ENDPOINT_FILTERS} options={ENDPOINT_OPTIONS} onChange={onChange} />);
    expect(screen.getAllByRole('button').map(b => b.textContent?.trim()))
      .toEqual(['Sensitives', 'Document Class', 'Data Elements', 'More filters']);
    await user.click(screen.getByRole('button', { name: 'More filters' }));
    expect(leftList()).toEqual([
      'Category', 'Sensitives', 'Document Class', 'Data Elements', 'Matters Tags', 'UID', 'Host Name', 'Last Modified',
    ]);
    const panel = screen.getByRole('dialog', { name: 'More filters' });
    await user.click(within(panel).getByRole('button', { name: 'Document Class' }));
    expect(within(panel).getByRole('checkbox', { name: 'payroll' })).toBeInTheDocument();
    await user.click(within(panel).getByRole('button', { name: 'Last Modified' }));
    expect(within(panel).getAllByRole('option').map(o => o.textContent))
      .toEqual(['Last 48 hours', 'Last 7 days', 'Last 30 days']);
    await user.click(within(panel).getByRole('option', { name: 'Last 7 days' }));
    expect(onChange).toHaveBeenLastCalledWith({ lastModified: { mode: 'include', values: ['7d'] } });
  });

  it('leaves out a More filters dimension without options or a selection', async () => {
    const user = userEvent.setup();
    const options: DatastoreFilterOptions = {
      dimensions: SAAS_OPTIONS.dimensions.map(d => (d.key === 'externalUser' ? { ...d, options: [] } : d)),
    };
    render(<Harness config={SAAS_FILTERS} options={options} />);
    await user.click(screen.getByRole('button', { name: 'More filters' }));
    expect(leftList()).not.toContain('External Users');
  });
});
