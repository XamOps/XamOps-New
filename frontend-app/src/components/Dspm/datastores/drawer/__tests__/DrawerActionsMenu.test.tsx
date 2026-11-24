import { afterEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import React, { useRef } from 'react';
vi.mock('../../../../../utils/apiClient', () => ({ default: { get: vi.fn() } }));
import apiClient from '../../../../../utils/apiClient';
import type { DatastoreRow } from '../../../../../types/dspmDatastores';
import { DrawerShell } from '../DrawerShell';
import DrawerActionsMenu from '../DrawerActionsMenu';
import { scanSettingsEligible } from '../scanSettingsModel';
import { CONSOLE_ONLY_TOOLTIP } from '../../ui/ConsoleOnly';

const get = apiClient.get as unknown as ReturnType<typeof vi.fn>;

type RowShape = Pick<DatastoreRow, 'category' | 'source'>;

const row = (provider: string, category: DatastoreRow['category'] = 'cloud'): RowShape => ({
  category,
  source: { provider, providerLabel: provider, accountLabel: 'example-account', region: null },
});

const Harness: React.FC<{ row: RowShape; onScanSettings: () => void }> = ({ row: shown, onScanSettings }) => {
  const button = useRef<HTMLButtonElement>(null);
  const close = useRef<HTMLButtonElement>(null);
  return (
    <>
      <button ref={close} type="button">Close drawer</button>
      <DrawerActionsMenu row={shown} buttonRef={button} onScanSettings={onScanSettings} />
    </>
  );
};

function renderMenu(shown: RowShape) {
  const onClose = vi.fn();
  const onScanSettings = vi.fn();
  const initialFocus = { current: null };
  render(
    <DrawerShell label="Example datastore" onClose={onClose} initialFocus={initialFocus}>
      <Harness row={shown} onScanSettings={onScanSettings} />
    </DrawerShell>,
  );
  const trigger = screen.getByRole('button', { name: 'Actions' });
  return { onClose, onScanSettings, trigger };
}

const itemNames = () => within(screen.getByRole('menu')).getAllByRole('menuitem').map(item => item.textContent);

afterEach(() => {
  vi.clearAllMocks();
});

describe('scanSettingsEligible (AC15)', () => {
  it.each([
    ['S3', 'cloud'], ['RDS', 'cloud'], ['DYNAMODB', 'cloud'], ['EBS', 'cloud'], ['GCS', 'cloud'],
    ['SELF_HOSTED_POSTGRES', 'on_prem'], ['RDS_ORACLE', 'cloud'], ['DOCUMENTDB', 'cloud'], ['MSK_KAFKA', 'cloud'],
    ['DATABRICKS_SQL', 'cloud'], ['CLOUD_SQL', 'cloud'], ['SNOWFLAKE', 'cloud'],
  ] as const)('admits %s', (provider, category) => {
    expect(scanSettingsEligible(row(provider, category))).toBe(true);
  });

  it.each(['AZURE_BLOB', 'BIGQUERY'])('leaves out %s', provider => {
    expect(scanSettingsEligible(row(provider))).toBe(false);
  });
});

describe('DrawerActionsMenu', () => {
  it('is an enabled menu button that opens the console items, Scan Settings enabled (AC15)', async () => {
    const user = userEvent.setup();
    const { trigger, onScanSettings } = renderMenu(row('S3'));
    expect(trigger).not.toHaveAttribute('aria-disabled');
    expect(trigger).toHaveAttribute('aria-haspopup', 'menu');
    expect(trigger).toHaveAttribute('aria-expanded', 'false');
    await user.click(trigger);
    expect(trigger).toHaveAttribute('aria-expanded', 'true');
    expect(itemNames()).toEqual(['Sync Datastore', 'Download Sensitives', 'Scan Settings', 'Create Jira Ticket']);
    const scanSettings = screen.getByRole('menuitem', { name: 'Scan Settings' });
    expect(scanSettings).not.toHaveAttribute('aria-disabled');
    expect(scanSettings).toHaveFocus();
    await user.click(scanSettings);
    expect(onScanSettings).toHaveBeenCalledTimes(1);
    expect(screen.queryByRole('menu')).toBeNull();
  });

  it.each([
    ['RDS', 'cloud'], ['DYNAMODB', 'cloud'], ['EBS', 'cloud'], ['GCS', 'cloud'], ['SELF_HOSTED_POSTGRES', 'on_prem'],
  ] as const)('lists Scan Settings for %s', async (provider, category) => {
    const user = userEvent.setup();
    const { trigger } = renderMenu(row(provider, category));
    await user.click(trigger);
    expect(itemNames()).toContain('Scan Settings');
  });

  it.each(['AZURE_BLOB', 'BIGQUERY'])('does not list Scan Settings for %s', async provider => {
    const user = userEvent.setup();
    const { trigger } = renderMenu(row(provider));
    await user.click(trigger);
    expect(itemNames()).toEqual(['Sync Datastore', 'Download Sensitives', 'Create Jira Ticket']);
    // No enabled item: the first item takes focus.
    expect(screen.getByRole('menuitem', { name: 'Sync Datastore' })).toHaveFocus();
  });

  it('greys the other items with the shared tooltip, and a click sends nothing and keeps the menu (AC21)', async () => {
    const user = userEvent.setup();
    const { trigger, onScanSettings } = renderMenu(row('S3'));
    await user.click(trigger);
    for (const name of ['Sync Datastore', 'Download Sensitives', 'Create Jira Ticket']) {
      const item = screen.getByRole('menuitem', { name });
      expect(item).toHaveAttribute('aria-disabled', 'true');
      const tooltip = document.getElementById(item.getAttribute('aria-describedby') ?? '') as HTMLElement;
      fireEvent.mouseEnter(item);
      expect(tooltip).toBeVisible();
      expect(tooltip).toHaveTextContent(CONSOLE_ONLY_TOOLTIP);
      await user.click(item);
      expect(screen.getByRole('menu')).toBeInTheDocument();
    }
    expect(onScanSettings).not.toHaveBeenCalled();
    expect(get).not.toHaveBeenCalled();
  });

  it('moves between items with ArrowDown and ArrowUp, wrapping at the ends', async () => {
    const user = userEvent.setup();
    const { trigger } = renderMenu(row('S3'));
    await user.click(trigger);
    expect(screen.getByRole('menuitem', { name: 'Scan Settings' })).toHaveFocus();
    await user.keyboard('{ArrowDown}');
    expect(screen.getByRole('menuitem', { name: 'Create Jira Ticket' })).toHaveFocus();
    await user.keyboard('{ArrowDown}');
    expect(screen.getByRole('menuitem', { name: 'Sync Datastore' })).toHaveFocus();
    await user.keyboard('{ArrowUp}');
    expect(screen.getByRole('menuitem', { name: 'Create Jira Ticket' })).toHaveFocus();
  });

  it('closes on Escape and returns focus to the trigger, leaving the drawer open', async () => {
    const user = userEvent.setup();
    const { trigger, onClose } = renderMenu(row('S3'));
    await user.click(trigger);
    await user.keyboard('{Escape}');
    expect(screen.queryByRole('menu')).toBeNull();
    expect(trigger).toHaveFocus();
    expect(trigger).toHaveAttribute('aria-expanded', 'false');
    expect(onClose).not.toHaveBeenCalled();
    await user.keyboard('{Escape}');
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('closes through the drawer\'s Escape stack when focus has left the menu', async () => {
    const user = userEvent.setup();
    const { trigger, onClose } = renderMenu(row('S3'));
    await user.click(trigger);
    act(() => (document.activeElement as HTMLElement).blur());
    fireEvent.keyDown(window, { key: 'Escape' });
    expect(screen.queryByRole('menu')).toBeNull();
    expect(onClose).not.toHaveBeenCalled();
  });

  it('closes on a click outside the menu', async () => {
    const user = userEvent.setup();
    const { trigger } = renderMenu(row('S3'));
    await user.click(trigger);
    await user.click(screen.getByRole('button', { name: 'Close drawer' }));
    expect(screen.queryByRole('menu')).toBeNull();
  });

  it('toggles closed on a second click of the trigger', async () => {
    const user = userEvent.setup();
    const { trigger } = renderMenu(row('S3'));
    await user.click(trigger);
    await user.click(trigger);
    expect(screen.queryByRole('menu')).toBeNull();
  });
});
