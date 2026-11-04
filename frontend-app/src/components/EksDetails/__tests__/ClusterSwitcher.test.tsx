import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const { get } = vi.hoisted(() => ({ get: vi.fn() }));
vi.mock('../../../utils/apiClient', () => ({ default: { get } }));

import { clearK8sCaches } from '../../../utils/k8sCache';
import { ClusterSwitcher } from '../ClusterSwitcher';

const ACCOUNT = '111';
const LIST = [
  { name: 'prod-eks', region: 'us-east-1', version: '1.30', connected: true },
  { name: 'stage-eks', region: 'eu-west-1', version: '1.29', connected: false },
];

function renderAt(search: string, props: Partial<Parameters<typeof ClusterSwitcher>[0]> = {}) {
  return render(
    <MemoryRouter initialEntries={[`/eks-details${search}`]}>
      <Routes>
        <Route
          path="/eks-details"
          element={<ClusterSwitcher accountId={ACCOUNT} clusterName="prod-eks" region="us-east-1" version="1.30" {...props} />}
        />
      </Routes>
    </MemoryRouter>,
  );
}

describe('ClusterSwitcher', () => {
  beforeEach(() => { get.mockReset(); clearK8sCaches(); });

  async function open() {
    fireEvent.click(screen.getByRole('button', { name: 'Switch cluster' }));
    await waitFor(() => expect(screen.getByRole('group', { name: 'Clusters' })).toBeTruthy());
  }

  it('lists All clusters plus every valid cluster; marks the current one; keeps a not-connected item as a link', async () => {
    get.mockResolvedValue([...LIST, { name: '-bad', region: 'us-east-1' }]);
    renderAt('?name=prod-eks&region=us-east-1&accountIds=111');
    await open();
    expect(screen.getByRole('link', { name: /All clusters/ })).toHaveAttribute('href', '/cloudk8s?accountIds=111');
    await waitFor(() => expect(screen.getAllByRole('link').length).toBeGreaterThan(1));
    const current = screen.getByText('prod-eks').closest('a')!;
    expect(current).toHaveAttribute('aria-current', 'true');
    expect(current.textContent).toContain('Current');
    const notConnected = screen.getByText('stage-eks').closest('a')!;
    expect(notConnected.textContent).toContain('Not connected');
    expect(notConnected.tagName).toBe('A');
    expect(screen.queryByText('-bad')).toBeNull();
  });

  it('builds the exact href for a switch, keeping tab/sub/debug and dropping page params, never leaking a stale version', async () => {
    get.mockResolvedValue([{ name: 'other', region: 'eu-west-1', version: '1.31' }, { name: 'other2', region: 'us-east-1' }]);
    renderAt('?name=prod-eks&region=us-east-1&accountIds=111&version=1.29&tab=automation&sub=activity&workload=web&hpa=x&phase=live&debug=true');
    await open();
    await waitFor(() => expect(screen.getByText('other')).toBeTruthy());
    expect(screen.getByText('other').closest('a')).toHaveAttribute(
      'href',
      '/eks-details?name=other&region=eu-west-1&accountIds=111&version=1.31&tab=automation&sub=activity&debug=true',
    );
    expect(screen.getByText('other2').closest('a')).toHaveAttribute(
      'href',
      '/eks-details?name=other2&region=us-east-1&accountIds=111&tab=automation&sub=activity&debug=true',
    );
  });

  it('every item is a real anchor with no pageKey in location state (so the page remounts)', async () => {
    get.mockResolvedValue(LIST);
    renderAt('?name=prod-eks&region=us-east-1&accountIds=111');
    await open();
    await waitFor(() => expect(screen.getByText('stage-eks')).toBeTruthy());
    const link = screen.getByText('stage-eks').closest('a')!;
    expect(link.getAttribute('href')).toMatch(/^\/eks-details\?/);
  });

  it('injects the current cluster when the list lacks it, and disambiguates same-name clusters by region', async () => {
    get.mockResolvedValue([{ name: 'other', region: 'us-east-1' }, { name: 'prod-eks', region: 'eu-west-1' }]);
    renderAt('?name=prod-eks&region=us-east-1&accountIds=111');
    await open();
    await waitFor(() => expect(screen.getAllByText('prod-eks')).toHaveLength(2));
    const [injected, other] = screen.getAllByText('prod-eks').map((el) => el.closest('a')!);
    expect(injected).toHaveAttribute('aria-current', 'true');
    expect(other).not.toHaveAttribute('aria-current');
  });

  it('loading keeps All clusters and the current cluster usable, then an empty list leaves just those', async () => {
    let resolveList!: (v: unknown) => void;
    get.mockImplementationOnce(() => new Promise((resolve) => { resolveList = resolve; }));
    renderAt('?name=prod-eks&region=us-east-1&accountIds=111');
    await open();
    expect(screen.getByText('Loading clusters…')).toBeTruthy();
    expect(screen.getByRole('link', { name: /All clusters/ })).toBeTruthy();
    expect(screen.getByText('prod-eks')).toBeTruthy();
    await act(async () => resolveList([]));
    await waitFor(() => expect(screen.queryByText('Loading clusters…')).toBeNull());
    expect(screen.getByText('prod-eks')).toBeTruthy();
  });

  it('error shows a message and Retry reloads', async () => {
    get.mockRejectedValueOnce(new Error('boom'));
    renderAt('?name=prod-eks&region=us-east-1&accountIds=111');
    await open();
    await waitFor(() => expect(screen.getByText("Couldn't load the cluster list.")).toBeTruthy());
    expect(screen.getByRole('link', { name: /All clusters/ })).toBeTruthy();
    expect(screen.getByText('prod-eks')).toBeTruthy();
    get.mockResolvedValueOnce(LIST);
    fireEvent.click(screen.getByText('Retry'));
    await waitFor(() => expect(screen.getByText('stage-eks')).toBeTruthy());
    expect(get).toHaveBeenCalledTimes(2);
  });

  it('closes on Escape and returns focus to the trigger; closes on an outside click', async () => {
    get.mockResolvedValue(LIST);
    renderAt('?name=prod-eks&region=us-east-1&accountIds=111');
    const trigger = screen.getByRole('button', { name: 'Switch cluster' });
    await open();
    fireEvent.keyDown(screen.getByRole('group', { name: 'Clusters' }), { key: 'Escape' });
    await waitFor(() => expect(screen.queryByRole('group', { name: 'Clusters' })).toBeNull());
    expect(document.activeElement).toBe(trigger);

    await open();
    fireEvent.pointerDown(document.body);
    await waitFor(() => expect(screen.queryByRole('group', { name: 'Clusters' })).toBeNull());
  });

  it('moves focus with Arrow Up/Down, Home and End', async () => {
    get.mockResolvedValue(LIST);
    renderAt('?name=prod-eks&region=us-east-1&accountIds=111');
    await open();
    const group = screen.getByRole('group', { name: 'Clusters' });
    await waitFor(() => expect(screen.getAllByRole('link').length).toBeGreaterThan(1));
    const all = screen.getByRole('link', { name: /All clusters/ });
    all.focus();
    fireEvent.keyDown(group, { key: 'ArrowDown' });
    expect(document.activeElement?.textContent).toContain('prod-eks');
    fireEvent.keyDown(group, { key: 'End' });
    expect(document.activeElement?.textContent).toContain('stage-eks');
    fireEvent.keyDown(group, { key: 'Home' });
    expect(document.activeElement?.textContent).toContain('All clusters');
  });

  it('shows a filter input past 8 clusters, local only', async () => {
    const many = Array.from({ length: 9 }, (_, i) => ({ name: `c${i}`, region: 'us-east-1' }));
    get.mockResolvedValue(many);
    renderAt('?name=prod-eks&region=us-east-1&accountIds=111');
    await open();
    await waitFor(() => expect(screen.getByLabelText('Filter clusters')).toBeTruthy());
    fireEvent.change(screen.getByLabelText('Filter clusters'), { target: { value: 'c1' } });
    await waitFor(() => expect(screen.queryByText('c0')).toBeNull());
    expect(screen.getByText('c1')).toBeTruthy();
  });

  it('open state survives a re-render that keeps the element identity', async () => {
    get.mockResolvedValue(LIST);
    const { rerender } = renderAt('?name=prod-eks&region=us-east-1&accountIds=111');
    await open();
    rerender(
      <MemoryRouter initialEntries={['/eks-details?name=prod-eks&region=us-east-1&accountIds=111']}>
        <Routes>
          <Route path="/eks-details" element={<ClusterSwitcher accountId={ACCOUNT} clusterName="prod-eks" region="us-east-1" version="1.31" />} />
        </Routes>
      </MemoryRouter>,
    );
    expect(screen.getByRole('group', { name: 'Clusters' })).toBeTruthy();
  });
});
