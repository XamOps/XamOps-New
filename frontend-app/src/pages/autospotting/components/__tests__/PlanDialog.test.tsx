import { describe, it, expect, afterEach, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { installFetch, last, planDto, renderWithProviders, row } from '../../__tests__/helpers';
import { awsAdapter } from '../../adapters/awsAdapter';
import PlanDialog from '../PlanDialog';
import RevertDialog from '../RevertDialog';

afterEach(() => vi.unstubAllGlobals());

const est = (profile: string, savings: number | null, blocked = false) => ({
  profile,
  monthlySavings: blocked ? 0 : savings,
  priceSources: blocked ? [] : [{ source: 'AWS Price List', version: 'v7', asOf: '2026-11-01' }],
  label: 'Estimate',
});

const bodyWith = (over: Record<string, unknown> = {}) => ({
  current: { desired: 4 },
  target: {},
  diff: [{ field: 'onDemandBaseCapacity', from: '4', to: '1' }],
  onDemandFloor: { baseCapacity: 1, onDemandCount: 2 },
  feeBasis: { contractId: null, reason: 'NO_CONTRACT' },
  revertPath: { program: 'REVERT', method: 'RESTORE_SNAPSHOT', exact: true },
  rollout: { method: 'REFRESH', instancesToReplace: 4, surge: 1, durationRange: '10-20 min', headroom: 'ok' },
  estimates: [est('conservative', 300), est('balanced', 450), est('aggressive', 600)],
  ...over,
});

const setup = (
  plan = planDto({ body: bodyWith() }),
  opts: { confirm?: Array<{ status?: number; body: unknown } | 'network-error'>; r?: ReturnType<typeof row>; dryRun?: boolean } = {},
) => {
  const fx = installFetch([
    { method: 'POST', match: /\/plans$/, responses: [{ status: 201, body: plan }] },
    {
      method: 'POST',
      match: /\/confirm/,
      responses: opts.confirm ?? [{ status: 202, body: { operationId: 'op-9', status: 'QUEUED' } }],
    },
  ]);
  const onConfirmed = vi.fn();
  const onClose = vi.fn();
  const view = renderWithProviders(
    <PlanDialog
      accountId={7}
      row={opts.r ?? row()}
      kind="ENABLE"
      adapter={awsAdapter}
      dryRun={opts.dryRun}
      onClose={onClose}
      onConfirmed={onConfirmed}
      retryDelayMs={0}
    />,
  );
  return { ...fx, ...view, onConfirmed, onClose };
};

describe('PlanDialog', () => {
  it('shows what changes, the estimate with its label and price sources, and the revert path', async () => {
    setup();
    expect(await screen.findByText('What changes')).toBeInTheDocument();
    expect(screen.getByText('onDemandBaseCapacity')).toBeInTheDocument();
    expect(screen.getAllByText('Estimate').length).toBeGreaterThan(0);
    expect(screen.getAllByText(/AWS Price List, v7, as of 2026-11-01/).length).toBeGreaterThan(0);
    expect(screen.getByText('Original configuration will be saved.')).toBeInTheDocument();
    expect(screen.getByText('Revert restores the saved original configuration.')).toBeInTheDocument();
    expect(screen.getByText(/On-Demand floor: 1 base/)).toBeInTheDocument();
  });

  it('preselects Conservative when it is available', async () => {
    const { calls } = setup();
    await screen.findByText('What changes');
    expect(screen.getByRole('radio', { name: /Conservative/ })).toBeChecked();
    expect((calls.find(c => c.method === 'POST')!.body as Record<string, unknown>).profileId).toBe('conservative');
  });

  it('GR-09 at desired 2: Conservative and Balanced are disabled with the reason and nothing is preselected', async () => {
    const plan = planDto({
      body: bodyWith({
        current: { desired: 2 },
        estimates: [est('conservative', 0, true), est('balanced', 0, true), est('aggressive', 120)],
      }),
    });
    const { calls } = setup(plan);
    const user = userEvent.setup();
    await screen.findByText('What changes');
    const conservative = screen.getByRole('radio', { name: /Conservative/ });
    const balanced = screen.getByRole('radio', { name: /Balanced/ });
    const aggressive = screen.getByRole('radio', { name: /Aggressive/ });
    expect(conservative).toBeDisabled();
    expect(balanced).toBeDisabled();
    expect(aggressive).toBeEnabled();
    expect(screen.getAllByText(/At the current size of 2, this profile would keep every instance On-Demand/).length).toBeGreaterThanOrEqual(2);
    expect(conservative).not.toBeChecked();
    expect(balanced).not.toBeChecked();
    expect(aggressive).not.toBeChecked();
    expect(screen.getByRole('button', { name: 'Confirm' })).toBeDisabled();
    expect(screen.getByText('Choose a profile.')).toBeInTheDocument();

    // A new profile click creates a superseding plan and enables Confirm.
    await user.click(aggressive);
    await waitFor(() => expect(calls.filter(c => c.method === 'POST' && /\/plans$/.test(c.url))).toHaveLength(2));
    expect((last(calls.filter(c => /\/plans$/.test(c.url)))!.body as Record<string, unknown>).profileId).toBe('aggressive');
    await waitFor(() => expect(screen.getByRole('button', { name: 'Confirm' })).toBeEnabled());
  });

  it('shows "No AutoSpotting fee applies" for NO_CONTRACT', async () => {
    setup(planDto({ body: bodyWith({ feeBasis: { contractId: null, reason: 'NO_CONTRACT' } }) }));
    expect(await screen.findByText('No AutoSpotting fee applies to this group.')).toBeInTheDocument();
  });

  it('shows "No AutoSpotting fee applies" for NO_FEE_ACCOUNT', async () => {
    setup(planDto({ body: bodyWith({ feeBasis: { contractId: null, reason: 'NO_FEE_ACCOUNT' } }) }));
    expect(await screen.findByText('No AutoSpotting fee applies to this group.')).toBeInTheDocument();
  });

  it('shows the share, cap and the contract-in-force sentence when a contract applies', async () => {
    setup(planDto({ body: bodyWith({ feeBasis: { contractId: 'c-1', sharePct: 20, monthlyCap: 500 } }) }));
    expect(await screen.findByText(/20% share, monthly cap USD 500/)).toBeInTheDocument();
    expect(screen.getByText(/The fee follows your AutoSpotting contract in force each month/)).toBeInTheDocument();
  });

  it('keeps Confirm disabled until every warning is ticked, reachable by keyboard', async () => {
    const r = row({ warnings: [{ id: 'w1', code: 'W1', text: 'Instances will be replaced.' }, { id: 'w2', code: 'W2', text: 'Capacity may dip.' }] });
    setup(planDto({ body: bodyWith(), warningIds: ['w1', 'w2'] }), { r });
    const user = userEvent.setup();
    await screen.findByText('Instances will be replaced.');
    const confirm = screen.getByRole('button', { name: 'Confirm' });
    expect(confirm).toBeDisabled();
    const first = screen.getByRole('checkbox', { name: 'Instances will be replaced.' });
    first.focus();
    await user.keyboard(' ');
    expect(first).toBeChecked();
    expect(confirm).toBeDisabled();
    await user.click(screen.getByRole('checkbox', { name: 'Capacity may dip.' }));
    expect(confirm).toBeEnabled();
  });

  it('requires the 100% Spot opt-in before Confirm', async () => {
    setup(planDto({ body: bodyWith(), requiresFullSpotOptin: true }));
    const user = userEvent.setup();
    await screen.findByText('What changes');
    expect(screen.getByRole('button', { name: 'Confirm' })).toBeDisabled();
    await user.click(screen.getByRole('checkbox', { name: /100% Spot/ }));
    expect(screen.getByRole('button', { name: 'Confirm' })).toBeEnabled();
  });

  it('confirms with the plan version and acks, then reports the operation id (202)', async () => {
    const r = row({ warnings: [{ id: 'w1', code: 'W1', text: 'Instances will be replaced.' }] });
    const { calls, onConfirmed } = setup(planDto({ version: 3, body: bodyWith(), warningIds: ['w1'] }), { r });
    const user = userEvent.setup();
    await user.click(await screen.findByRole('checkbox', { name: 'Instances will be replaced.' }));
    await user.click(screen.getByRole('button', { name: 'Confirm' }));
    await waitFor(() => expect(onConfirmed).toHaveBeenCalledWith('op-9'));
    const confirm = calls.find(c => /\/confirm/.test(c.url))!;
    expect(confirm.body).toMatchObject({ planVersion: 3, acknowledgedWarningIds: ['w1'], fullSpotOptIn: false });
    expect(confirm.headers['Idempotency-Key']).toBeTruthy();
    expect(confirm.headers['X-XamOps-Intent']).toBe('autospotting');
  });

  it('retries a lost confirm with the same Idempotency-Key', async () => {
    const { calls, onConfirmed } = setup(planDto({ body: bodyWith() }), {
      confirm: ['network-error', { status: 202, body: { operationId: 'op-3', status: 'QUEUED' } }],
    });
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'Confirm' }));
    await waitFor(() => expect(onConfirmed).toHaveBeenCalledWith('op-3'));
    const keys = calls.filter(c => /\/confirm/.test(c.url)).map(c => c.headers['Idempotency-Key']);
    expect(keys).toHaveLength(2);
    expect(keys[0]).toBe(keys[1]);
  });

  it('409 PLAN_STALE: "The group changed" and a Refresh plan that re-plans with the same profile', async () => {
    const { calls } = setup(planDto({ body: bodyWith() }), {
      confirm: [{ status: 409, body: { code: 'PLAN_STALE', detail: 'x' } }],
    });
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'Confirm' }));
    expect(await screen.findByText('The group changed')).toBeInTheDocument();
    expect(screen.getByText('The group changed after this plan was made.')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Refresh plan' }));
    await waitFor(() => expect(calls.filter(c => /\/plans$/.test(c.url))).toHaveLength(2));
    expect((last(calls.filter(c => /\/plans$/.test(c.url)))!.body as Record<string, unknown>).profileId).toBe('conservative');
  });

  it('422 ACK_REQUIRED shows the acknowledgment message', async () => {
    setup(planDto({ body: bodyWith() }), { confirm: [{ status: 422, body: { code: 'ACK_REQUIRED' } }] });
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'Confirm' }));
    expect(await screen.findByText('Tick every acknowledgment before confirming.')).toBeInTheDocument();
  });

  it('403 AS_FORBIDDEN_ROLE shows the view-only message', async () => {
    setup(planDto({ body: bodyWith() }), { confirm: [{ status: 403, body: { code: 'AS_FORBIDDEN_ROLE' } }] });
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'Confirm' }));
    expect(await screen.findByText('Your role can view this plan but not confirm it.')).toBeInTheDocument();
  });

  it('a switch refusal names the switch and offers no retry', async () => {
    setup(planDto({ body: bodyWith() }), { confirm: [{ status: 409, body: { code: 'AS_SWITCH_ENGAGED', switch: 'GLOBAL' } }] });
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'Confirm' }));
    expect(await screen.findByText('AutoSpotting changes are paused for GLOBAL.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Retry/ })).toBeNull();
  });

  it('shows the Dry run banner and "Run dry run" on a dry-run plan', async () => {
    setup(planDto({ body: bodyWith(), dryRun: true }));
    expect(await screen.findByText('Dry run')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Run dry run' })).toBeInTheDocument();
  });

  it('swaps Confirm for Refresh plan once the plan has expired', async () => {
    setup(planDto({ body: bodyWith(), expiresAt: new Date(Date.now() - 1000).toISOString() }));
    expect(await screen.findByRole('button', { name: 'Refresh plan' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Confirm' })).toBeNull();
  });

  it('renders an unpriced estimate as Not priced, never 0', async () => {
    setup(planDto({ body: bodyWith({ estimates: [est('conservative', null), est('balanced', null), est('aggressive', null)] }) }));
    await screen.findByText('What changes');
    expect(screen.getAllByText('Not priced').length).toBeGreaterThanOrEqual(3);
    expect(screen.queryByText(/USD 0/)).toBeNull();
  });

  it('is an accessible dialog: Esc closes it and Tab stays inside', async () => {
    const { onClose } = setup();
    const user = userEvent.setup();
    const dialog = await screen.findByRole('dialog');
    expect(dialog).toHaveAttribute('aria-modal', 'true');
    expect(dialog).toHaveAttribute('aria-labelledby');
    expect(dialog).toHaveAttribute('aria-describedby');
    for (let i = 0; i < 25; i++) {
      await user.tab();
      expect(dialog.contains(document.activeElement)).toBe(true);
    }
    await user.keyboard('{Escape}');
    expect(onClose).toHaveBeenCalled();
  });
});

describe('RevertDialog', () => {
  const setupRevert = (
    createResponse: { status?: number; body: unknown },
    r = row({ state: 'ACTIVE', managed: true }),
  ) => {
    const fx = installFetch([
      { method: 'POST', match: /\/plans$/, responses: [createResponse] },
      { method: 'POST', match: /\/confirm/, responses: [{ status: 202, body: { operationId: 'op-r', status: 'QUEUED' } }] },
    ]);
    const onConfirmed = vi.fn();
    renderWithProviders(
      <RevertDialog accountId={7} row={r} adapter={awsAdapter} onClose={() => {}} onConfirmed={onConfirmed} retryDelayMs={0} />,
    );
    return { ...fx, onConfirmed };
  };

  it('leads with "Exact restore is not possible" when the snapshot is inexact', async () => {
    setupRevert({ status: 201, body: planDto({ kind: 'REVERT', body: bodyWith({ revertPath: { exact: false } }) }) });
    expect(await screen.findByText('Exact restore is not possible')).toBeInTheDocument();
  });

  it('says the restore is exact when it is', async () => {
    setupRevert({ status: 201, body: planDto({ kind: 'REVERT', body: bodyWith() }) });
    expect(await screen.findByText('Revert restores the saved original configuration exactly.')).toBeInTheDocument();
  });

  it('REVERT_NEEDS_PERMISSION shows the remediation with an Update stack link', async () => {
    setupRevert({ status: 409, body: { code: 'REVERT_NEEDS_PERMISSION', detail: 'x' } });
    expect(await screen.findByRole('link', { name: 'Update stack' })).toBeInTheDocument();
    expect(screen.getByText(/Revert needs a permission/)).toBeInTheDocument();
  });

  it('AS_SWITCH_UNREADABLE shows the held-revert text', async () => {
    setupRevert({ status: 503, body: { code: 'AS_SWITCH_UNREADABLE' } });
    expect(
      await screen.findByText("AutoSpotting can't read its safety switches right now; revert is held until it can"),
    ).toBeInTheDocument();
  });

  it('REVERT_FROM_FAILED needs its acknowledgment before Revert enables', async () => {
    const { onConfirmed, calls } = setupRevert({
      status: 201,
      body: planDto({ kind: 'REVERT', body: bodyWith(), warningIds: ['REVERT_FROM_FAILED'] }),
    }, row({ state: 'FAILED', managed: true }));
    const user = userEvent.setup();
    const ack = await screen.findByRole('checkbox', { name: /failed state/ });
    expect(screen.getByRole('button', { name: 'Revert' })).toBeDisabled();
    await user.click(ack);
    await user.click(screen.getByRole('button', { name: 'Revert' }));
    await waitFor(() => expect(onConfirmed).toHaveBeenCalledWith('op-r'));
    expect(calls.find(c => /\/confirm/.test(c.url))!.body).toMatchObject({ acknowledgedWarningIds: ['REVERT_FROM_FAILED'] });
  });
});
