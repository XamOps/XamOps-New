import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { EndpointScanConfiguration as EndpointConfig } from '../../../../types/dspmScans';
vi.mock('../../../../utils/apiClient', () => ({ default: { get: vi.fn() } }));
import apiClient from '../../../../utils/apiClient';
import EndpointScanConfiguration from '../EndpointScanConfiguration';
import { endpointConfig, expectConsoleOnly } from './scanTestData';

const get = apiClient.get as unknown as ReturnType<typeof vi.fn>;
let answer: () => EndpointConfig;

beforeEach(() => {
  answer = () => endpointConfig();
  get.mockImplementation(async (raw: string) => {
    if (raw === '/api/dspm/scan-management/endpoint-configuration') return answer();
    throw new Error(`HTTP 404: unexpected ${raw}`);
  });
});

afterEach(() => {
  vi.clearAllMocks();
});

describe('EndpointScanConfiguration', () => {
  it('shows Cloud scan location, 00:00 – 00:00, 2 include paths, 0 exclude paths and 23 of 41 file types, all greyed (AC11)', async () => {
    const user = userEvent.setup();
    render(<EndpointScanConfiguration />);
    const cloud = await screen.findByRole('radio', { name: 'Cloud' });
    expect(cloud).toHaveAttribute('aria-checked', 'true');
    expect(screen.getByRole('radio', { name: 'Local' })).toHaveAttribute('aria-checked', 'false');
    expect(screen.getByRole('textbox', { name: 'Start time' })).toHaveTextContent('00:00');
    expect(screen.getByRole('textbox', { name: 'End time' })).toHaveTextContent('00:00');
    expect(screen.getByRole('textbox', { name: 'Include path 1' })).toHaveTextContent('/Users');
    expect(screen.getByRole('textbox', { name: 'Include path 2' })).toHaveTextContent('C:\\Users');
    expect(screen.queryByRole('textbox', { name: /^Exclude path/ })).toBeNull();
    expect(screen.getByText('Nothing excluded')).toBeInTheDocument();
    expect(screen.getByText('23 of 41 selected')).toBeInTheDocument();
    const boxes = screen.getAllByRole('checkbox');
    expect(boxes).toHaveLength(41);
    expect(boxes.filter(box => box.getAttribute('aria-checked') === 'true')).toHaveLength(23);
    expect(screen.getByRole('checkbox', { name: 'type01' })).toHaveAttribute('aria-checked', 'true');
    expect(screen.getByRole('checkbox', { name: 'type41' })).toHaveAttribute('aria-checked', 'false');

    const controls = [
      cloud, screen.getByRole('radio', { name: 'Local' }),
      ...screen.getAllByRole('textbox'),
      boxes[0], boxes[40],
    ];
    for (const control of controls) expectConsoleOnly(control);
    const before = get.mock.calls.length;
    for (const control of controls) await user.click(control);
    expect(get.mock.calls.length).toBe(before);
    expect(cloud).toHaveAttribute('aria-checked', 'true');
  });

  it('says "No endpoint agents configured" without a configuration', async () => {
    answer = () => endpointConfig({ configured: false });
    render(<EndpointScanConfiguration />);
    expect(await screen.findByText('No endpoint agents configured')).toBeInTheDocument();
    expect(screen.queryByText('Scan Location')).toBeNull();
  });

  it('shows the selected types alone when the full list is missing, and empty times as empty fields', async () => {
    answer = () => endpointConfig({
      allFileTypes: [], fileTypes: ['pdf', 'csv'], reconciliationStart: null, reconciliationEnd: null, includePaths: [],
      scanMode: 'local',
    });
    render(<EndpointScanConfiguration />);
    expect(await screen.findByText('2 selected')).toBeInTheDocument();
    expect(screen.getAllByRole('checkbox')).toHaveLength(2);
    expect(screen.getByRole('textbox', { name: 'Start time' })).toHaveTextContent('');
    expect(screen.getByText('No include paths')).toBeInTheDocument();
    const location = screen.getByRole('radiogroup', { name: 'Scan Location' });
    expect(within(location).getByRole('radio', { name: 'Local' })).toHaveAttribute('aria-checked', 'true');
  });
});
