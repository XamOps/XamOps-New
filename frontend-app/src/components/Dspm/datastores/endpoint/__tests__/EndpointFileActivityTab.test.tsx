import { describe, expect, it, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import type { LazyResource } from '../../../../../hooks/useDspmDatastoreDrawer';
import type { EndpointFileActivity, EndpointFileActivityEntry } from '../../../../../types/dspmFiles';
import EndpointFileActivityTab from '../EndpointFileActivityTab';

// ─── synthetic data (shapes only; no captured values) ────────────────────────────────────────────

/** Epoch seconds of a local time, so the expectations hold in every time zone. */
const local = (y: number, mo: number, d: number, h = 0, mi = 0) => new Date(y, mo - 1, d, h, mi).getTime() / 1000;

const entry = (over: Partial<EndpointFileActivityEntry> = {}): EndpointFileActivityEntry => ({
  action: 'TAG_APPLIED', actionLabel: 'Tag applied', selectedTag: 'Confidential', previousTag: null,
  performedBy: 'admin@example.com', occurredAt: local(2026, 10, 2, 14, 5), keywordsWritten: true, mipLabelWritten: true,
  ...over,
});

const resource = (data: EndpointFileActivity | null): LazyResource<EndpointFileActivity> =>
  ({ data, error: null, loading: data === null, reload: vi.fn() });

/** Each entry as [icon, label, tags, note, meta]; a missing part reads null. */
function entries() {
  return within(screen.getByRole('list', { name: 'File activity' })).getAllByRole('listitem').map(item => {
    const lines = [...item.querySelectorAll('p')];
    const line = (tint: string) => lines.find(p => p.className.includes(tint))?.textContent ?? null;
    return [
      item.querySelector('[data-icon]')?.getAttribute('data-icon') ?? null,
      item.querySelector('.font-semibold')?.textContent ?? null,
      item.querySelector('.font-semibold')?.nextElementSibling?.textContent ?? null,
      line('0.6)'),
      line('0.45)'),
    ];
  });
}

describe('EndpointFileActivityTab', () => {
  it("lists the entries in API order with each action's icon, label, tags and note, then who and when", () => {
    render(<EndpointFileActivityTab activity={resource({
      entries: [
        entry(),
        entry({
          action: 'TAG_CHANGED', actionLabel: 'Tag changed', previousTag: 'Internal', selectedTag: 'Confidential',
          mipLabelWritten: false, occurredAt: local(2026, 10, 3, 9, 30),
        }),
        entry({ action: 'TAG_REMOVED', actionLabel: 'Tag removed', previousTag: 'Internal', selectedTag: null, keywordsWritten: false, mipLabelWritten: false }),
        entry({ action: 'TAG_SKIPPED', actionLabel: 'Tag skipped', mipLabelWritten: false }),
        entry({ action: 'USER_OVERRIDE', actionLabel: 'User override', selectedTag: 'Public', mipLabelWritten: false }),
        entry({ action: 'TAG_SYNCED', actionLabel: 'TAG_SYNCED', selectedTag: null, keywordsWritten: false, mipLabelWritten: false, performedBy: null, occurredAt: null }),
      ],
    })} />);
    expect(entries()).toEqual([
      ['mdi:tag-outline', 'Tag applied', 'Confidential', 'Written to Keywords and the MIP label', 'admin@example.com - 02 Oct 2026, 02:05 PM'],
      ['mdi:swap-horizontal', 'Tag changed', 'Internal -> Confidential', 'Written to Keywords only', 'admin@example.com - 03 Oct 2026, 09:30 AM'],
      ['mdi:minus-circle-outline', 'Tag removed', 'Internal', null, 'admin@example.com - 02 Oct 2026, 02:05 PM'],
      ['mdi:cancel', 'Tag skipped', 'Confidential', 'Skipped: the file already has a MIP label', 'admin@example.com - 02 Oct 2026, 02:05 PM'],
      ['mdi:account-outline', 'User override', 'Public', 'Written to Keywords only', 'admin@example.com - 02 Oct 2026, 02:05 PM'],
      ['mdi:tag-outline', 'TAG_SYNCED', null, null, null],
    ]);
  });

  it('draws the arrow only with both tags, else the selected tag alone, and the time alone without a performer', () => {
    const changed = { action: 'TAG_CHANGED', actionLabel: 'Tag changed', mipLabelWritten: false } as const;
    render(<EndpointFileActivityTab activity={resource({
      entries: [
        entry({ ...changed, previousTag: null, selectedTag: 'Confidential', performedBy: null }),
        entry({ ...changed, previousTag: 'Internal', selectedTag: null }),
      ],
    })} />);
    expect(entries()).toEqual([
      ['mdi:swap-horizontal', 'Tag changed', 'Confidential', 'Written to Keywords only', '02 Oct 2026, 02:05 PM'],
      ['mdi:swap-horizontal', 'Tag changed', null, 'Written to Keywords only', 'admin@example.com - 02 Oct 2026, 02:05 PM'],
    ]);
  });

  it('reads "No file activity yet" without entries', () => {
    render(<EndpointFileActivityTab activity={resource({ entries: [] })} />);
    expect(screen.getByText('No file activity yet')).toBeInTheDocument();
    expect(screen.getByText('Tag changes for this file will appear here.')).toBeInTheDocument();
    expect(screen.queryByRole('list')).toBeNull();
  });

  it('reads "Loading…" until the log arrives', () => {
    render(<EndpointFileActivityTab activity={resource(null)} />);
    expect(screen.getByText('Loading…')).toBeInTheDocument();
  });
});
