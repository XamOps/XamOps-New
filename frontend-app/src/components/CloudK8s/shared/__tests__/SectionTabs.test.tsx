import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { SectionTabs } from '../SectionTabs';

type Key = 'nodes' | 'karpenter' | 'utilization';
const ITEMS = [
  { id: 'nodes' as const, label: 'Nodes' },
  { id: 'karpenter' as const, label: 'Karpenter' },
  { id: 'utilization' as const, label: 'Utilization' },
];

function Harness({ variant = 'sub' as 'primary' | 'sub', onChange = vi.fn() }) {
  const [selected, setSelected] = useState<Key>('nodes');
  return (
    <SectionTabs<Key>
      variant={variant}
      label="Nodes views"
      items={ITEMS}
      selectedKey={selected}
      onSelectionChange={(key) => { onChange(key); setSelected(key); }}
      renderPanel={(key) => <p>{`${key} content`}</p>}
    />
  );
}

describe('SectionTabs', () => {
  it.each(['primary', 'sub'] as const)('%s: a labelled tablist of tabs with one selected panel', (variant) => {
    render(<Harness variant={variant} />);
    const list = screen.getByRole('tablist', { name: 'Nodes views' });
    const tabs = screen.getAllByRole('tab');
    expect(list).toContainElement(tabs[0]);
    expect(tabs.map((t) => t.textContent)).toEqual(['Nodes', 'Karpenter', 'Utilization']);
    expect(tabs[0]).toHaveAttribute('aria-selected', 'true');
    expect(tabs[1]).toHaveAttribute('aria-selected', 'false');

    const panel = screen.getByRole('tabpanel');
    expect(panel).toHaveTextContent('nodes content');
    expect(panel).toHaveAttribute('aria-labelledby', tabs[0].id);
    expect(tabs[0]).toHaveAttribute('aria-controls', panel.id);
    expect(screen.queryByText('karpenter content')).toBeNull();
  });

  it('moves with arrow keys, Home and End, and selects as it moves', async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    render(<Harness onChange={onChange} />);
    const tabs = screen.getAllByRole('tab');

    await user.tab();
    expect(tabs[0]).toHaveFocus();
    expect(tabs[1]).toHaveAttribute('tabindex', '-1');
    await user.keyboard('{ArrowRight}');
    expect(tabs[1]).toHaveFocus();
    expect(tabs[1]).toHaveAttribute('aria-selected', 'true');
    expect(onChange).toHaveBeenLastCalledWith('karpenter');
    expect(screen.getByRole('tabpanel')).toHaveTextContent('karpenter content');
    expect(screen.getByRole('tabpanel')).toHaveAttribute('aria-labelledby', tabs[1].id);

    await user.keyboard('{End}');
    expect(tabs[2]).toHaveFocus();
    expect(onChange).toHaveBeenLastCalledWith('utilization');
    await user.keyboard('{Home}');
    expect(tabs[0]).toHaveFocus();
    await user.keyboard('{ArrowLeft}');
    expect(tabs[2]).toHaveFocus();
  });

  it('selects on click', async () => {
    const user = userEvent.setup();
    render(<Harness />);
    await user.click(screen.getByRole('tab', { name: 'Utilization' }));
    expect(screen.getByRole('tab', { name: 'Utilization' })).toHaveAttribute('aria-selected', 'true');
    expect(screen.getByRole('tabpanel')).toHaveTextContent('utilization content');
  });
});
