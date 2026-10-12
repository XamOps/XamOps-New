/**
 * The write-gate contract, end to end on the frontend: the real JSON the backend serialises (written
 * by `VerticalWriteGateContractTest` from the real DTOs into `api/__fixtures__`) goes through the
 * real fetch client, adapter, hook and tab, down to the Apply button.
 *
 * The caller is the one from UAT on 2026-10-12: `user_tab_access` rows with `cloudops-cloudk8s` but
 * not `cloudops-cloudk8s-automation`. If either side renames a field, one of the two tests fails.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import EksAutomationTab from '../EksAutomationTab';
import contract from '../../../../api/__fixtures__/verticalWriteGateContract.json';

vi.mock('../../../../utils/apiClient', () => ({
  default: { fetch: vi.fn() },
}));

import apiClient from '../../../../utils/apiClient';

const mockedFetch = apiClient.fetch as unknown as ReturnType<typeof vi.fn>;

type Json = Record<string, unknown>;
const realRead = contract.read as unknown as Json;
const realRefusal = contract.apply as unknown as Json;

const TAB_GRANT_REASON = "View only: you don't have permission to change automation.";
const TAB_GRANT_NEXT = 'Ask your XamOps administrator to allow automation changes for your user.';

const json = (body: unknown, status: number) => new Response(JSON.stringify(body), { status });

/** Serves endpoint 1 from `reads` in order (the last one repeats) and endpoint 3 with `apply`. */
const serve = (reads: Json[], apply?: { body: Json; status: number }) => {
  let readCount = 0;
  mockedFetch.mockImplementation(async (url: string, init?: RequestInit) => {
    if ((init?.method ?? 'GET') === 'POST') {
      if (!apply) throw new Error(`unexpected POST ${url}`);
      return json(apply.body, apply.status);
    }
    const body = reads[Math.min(readCount, reads.length - 1)];
    readCount += 1;
    return json(body, 200);
  });
};

const posts = () => mockedFetch.mock.calls.filter(([, init]) => (init as RequestInit | undefined)?.method === 'POST');

const renderTab = () =>
  render(<EksAutomationTab clusterName="xamops-test-1" accountId="102227938259" region="ap-south-1" />);

describe('write-gate contract (backend fixture → adapter → Apply)', () => {
  beforeEach(() => {
    mockedFetch.mockReset();
  });

  it('the fixture is the backend contract, not a hand-written payload', () => {
    expect(contract.applyStatus).toBe(403);
    expect(realRead.gateReasons).toEqual(['NO_USER_PERMISSION']);
    expect(realRead.gateDetails).toEqual({ NO_USER_PERMISSION: 'TAB_GRANT' });
    expect(realRead.writeGatesChecked).toBe(true);
    expect(realRefusal).toMatchObject({ code: 'K8S_NO_USER_PERMISSION', detail: 'TAB_GRANT' });
  });

  it('greys Apply for the TAB_GRANT caller with the glossary reason and next step, and sends nothing', async () => {
    serve([realRead]);
    renderTab();

    const button = await screen.findByRole('button', { name: 'Apply overprovisioned-api' });
    expect(button).toBeDisabled();
    expect(button).toHaveAttribute('title', TAB_GRANT_REASON);
    expect(screen.getByText(TAB_GRANT_REASON)).toBeInTheDocument();
    expect(screen.getByText(TAB_GRANT_NEXT)).toBeInTheDocument();
    fireEvent.click(button);
    expect(posts()).toHaveLength(0);
  });

  it('greys Apply when endpoint 1 is a build that never checked the caller', async () => {
    // Exactly what the pre-S6 backend sent for the same caller: no write gate, no flag.
    const older: Json = { ...realRead, gateReasons: [] };
    delete older.gateDetails;
    delete older.writeGatesChecked;
    serve([older]);
    renderTab();

    const button = await screen.findByRole('button', { name: 'Apply overprovisioned-api' });
    expect(button).toBeDisabled();
    expect(button).toHaveAttribute('title', "Couldn't check whether you can change automation.");
  });

  it('a 403 on a stale page is shown in words, greys Apply and re-reads the gates', async () => {
    // The page was loaded while the caller could still apply; by the click they could not.
    const permittedThen = { ...realRead, gateReasons: [], gateDetails: {} };
    serve([permittedThen, realRead], { body: realRefusal, status: 403 });
    renderTab();

    const button = await screen.findByRole('button', { name: 'Apply overprovisioned-api' });
    expect(button).toBeEnabled();
    fireEvent.click(button); // "Test run only" is on by default, so this submits a dry run

    expect(await screen.findByRole('alert')).toHaveTextContent(`${TAB_GRANT_REASON} ${TAB_GRANT_NEXT}`);
    await waitFor(() => expect(screen.getByRole('button', { name: 'Apply overprovisioned-api' })).toBeDisabled());
    expect(screen.getByRole('button', { name: 'Apply overprovisioned-api' })).toHaveAttribute('title', TAB_GRANT_REASON);
    expect(posts()).toHaveLength(1);
    expect(JSON.parse(String((posts()[0][1] as RequestInit).body))).toMatchObject({ dryRun: true });
    await waitFor(() =>
      expect(mockedFetch.mock.calls.filter(([, init]) => (init as RequestInit | undefined)?.method !== 'POST')).toHaveLength(2),
    );
  });
});
