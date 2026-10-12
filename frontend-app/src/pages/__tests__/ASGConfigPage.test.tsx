import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { TopbarProvider, useTopbar } from '../../contexts/TopbarContext';

const api = vi.hoisted(() => ({ get: vi.fn(), put: vi.fn() }));
vi.mock('../../utils/apiClient', () => ({ default: api }));

import ASGConfigPage from '../ASGConfigPage';

const ACCOUNT = '123456789012';
const ASG_QUERY = `region=us-east-1&asgName=web`;

/** Renders whatever the page puts in the topbar, where the Save button lives. */
function TopbarProbe() {
  const { actions } = useTopbar();
  return <div data-testid="topbar-actions">{actions}</div>;
}

function renderPage() {
  return render(
    <MemoryRouter initialEntries={[`/asg-config?asgName=web&region=us-east-1&accountIds=${ACCOUNT}`]}>
      <TopbarProvider>
        <TopbarProbe />
        <ASGConfigPage />
      </TopbarProvider>
    </MemoryRouter>,
  );
}

const featureStatus = { aws: { conversionsEnabled: false }, gcp: { conversionsEnabled: false } };

function stubGets(load: (url: string) => Promise<unknown>) {
  api.get.mockImplementation((url: string) =>
    url.startsWith('/api/autospotting/feature-status') ? Promise.resolve(featureStatus) : load(url));
}

const saveButton = () => screen.findByRole('button', { name: /Save Configuration/ });

describe('ASGConfigPage Save (PA-BUG-01)', () => {
  beforeEach(() => {
    api.get.mockReset();
    api.put.mockReset();
    api.put.mockResolvedValue({});
  });

  it('the first Save sends the loaded config, with apply=false', async () => {
    stubGets(async (url) => {
      if (url.includes('/instance-types')) return { allowedInstanceTypes: ['m5.large', 'c5.large'] };
      if (url.includes('/spot-config')) return { configMode: 'count', onDemandCount: 2 };
      throw new Error(`unexpected GET ${url}`);
    });

    renderPage();
    await screen.findByDisplayValue('2'); // the loaded on-demand count is on screen
    await waitFor(() => expect(screen.getByRole('button', { name: /Save Configuration/ })).toBeEnabled());

    fireEvent.click(await saveButton());

    await waitFor(() => expect(api.put).toHaveBeenCalledTimes(2));
    expect(api.put).toHaveBeenNthCalledWith(1,
      `/api/autospotting/asgs/${ACCOUNT}/instance-types?${ASG_QUERY}&apply=false`,
      { instanceTypes: ['m5.large', 'c5.large'] });
    expect(api.put).toHaveBeenNthCalledWith(2,
      `/api/autospotting/asgs/${ACCOUNT}/spot-config?${ASG_QUERY}&apply=false`,
      { onDemandCount: 2 });
  });

  it('Save sends edits made after the load, not the values of an earlier render', async () => {
    stubGets(async (url) => {
      if (url.includes('/instance-types')) return { allowedInstanceTypes: ['m5.large'] };
      if (url.includes('/spot-config')) return { configMode: 'count', onDemandCount: 2 };
      throw new Error(`unexpected GET ${url}`);
    });

    renderPage();
    fireEvent.change(await screen.findByDisplayValue('2'), { target: { value: '3' } });
    await waitFor(() => expect(screen.getByRole('button', { name: /Save Configuration/ })).toBeEnabled());

    fireEvent.click(await saveButton());

    await waitFor(() => expect(api.put).toHaveBeenCalledTimes(2));
    expect(api.put).toHaveBeenNthCalledWith(2,
      `/api/autospotting/asgs/${ACCOUNT}/spot-config?${ASG_QUERY}&apply=false`,
      { onDemandCount: 3 });
  });

  it('Save is disabled while the GETs are pending', async () => {
    stubGets(() => new Promise(() => {})); // never settles

    renderPage();

    expect(await saveButton()).toBeDisabled();
  });

  it('Save stays disabled after a GET rejects, so defaults are never posted over the stored config', async () => {
    stubGets(async (url) => {
      if (url.includes('/instance-types')) throw new Error('Access denied');
      return { configMode: 'count', onDemandCount: 2 };
    });

    renderPage();
    await screen.findByText('Access denied');

    const button = await saveButton();
    expect(button).toBeDisabled();
    fireEvent.click(button);
    expect(api.put).not.toHaveBeenCalled();
  });
});
