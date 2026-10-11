import { useState } from 'react';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { XamTabPermissionToggle } from '../XamTabPermissionToggle';
import { toggleAllowedTab } from '../../../constants/xamopsTabs';

const PERMISSION = 'cloudops-cloudk8s-automation';
const PARENT = 'cloudops-cloudk8s';
const HINT = 'Lets this member change automation settings. Recommendations are visible without it.';
const UNRESTRICTED = 'Unrestricted members can change CloudK8s automation.';

/** The picker's CloudK8s row as both pages render it: the tab checkbox, then the nested toggle. */
function Picker({ initial }: { initial: string[] }) {
  const [tabs, setTabs] = useState<string[]>(initial);
  return (
    <div>
      <label>
        <input type="checkbox" checked={tabs.includes(PARENT)} onChange={() => setTabs(toggleAllowedTab(tabs, PARENT))} />
        CloudK8s
      </label>
      <XamTabPermissionToggle parentKey={PARENT} allowedTabs={tabs} onChange={setTabs} />
      <output data-testid="tabs">{tabs.join(',')}</output>
    </div>
  );
}

describe('XamTabPermissionToggle (D2)', () => {
  it('renders nothing under a tab without permissions', () => {
    const { container } = render(<XamTabPermissionToggle parentKey="finops-cost" allowedTabs={['finops-cost']} onChange={vi.fn()} />);
    expect(container).toBeEmptyDOMElement();
  });

  it('tells an unrestricted member they can already change automation', () => {
    render(<XamTabPermissionToggle parentKey={PARENT} allowedTabs={[]} onChange={vi.fn()} />);
    expect(screen.getByText(UNRESTRICTED)).toBeInTheDocument();
    expect(screen.queryByRole('checkbox')).toBeNull();
  });

  it('hides the checkbox while CloudK8s is unchecked for a restricted member', () => {
    const { container } = render(<XamTabPermissionToggle parentKey={PARENT} allowedTabs={['finops-cost']} onChange={vi.fn()} />);
    expect(container).toBeEmptyDOMElement();
  });

  it('shows the checkbox under a checked CloudK8s, described by its hint', () => {
    render(<XamTabPermissionToggle parentKey={PARENT} allowedTabs={[PARENT]} onChange={vi.fn()} />);
    const box = screen.getByRole('checkbox', { name: 'Allow automation changes' });
    expect(box).not.toBeChecked();
    const hintId = box.getAttribute('aria-describedby');
    expect(hintId).toBeTruthy();
    expect(document.getElementById(hintId!)).toHaveTextContent(HINT);
    expect(screen.queryByText(UNRESTRICTED)).toBeNull();
  });

  it('grants and revokes the permission, and unchecking CloudK8s removes it', async () => {
    const user = userEvent.setup();
    render(<Picker initial={[PARENT, 'finops-cost']} />);

    await user.click(screen.getByRole('checkbox', { name: 'Allow automation changes' }));
    expect(screen.getByTestId('tabs')).toHaveTextContent(`${PARENT},finops-cost,${PERMISSION}`);
    expect(screen.getByRole('checkbox', { name: 'Allow automation changes' })).toBeChecked();

    await user.click(screen.getByRole('checkbox', { name: 'CloudK8s' }));
    expect(screen.getByTestId('tabs')).toHaveTextContent('finops-cost');
    expect(screen.getByTestId('tabs')).not.toHaveTextContent(PERMISSION);
    expect(screen.queryByRole('checkbox', { name: 'Allow automation changes' })).toBeNull();

    // Checking CloudK8s again doesn't bring the permission back on its own.
    await user.click(screen.getByRole('checkbox', { name: 'CloudK8s' }));
    expect(screen.getByRole('checkbox', { name: 'Allow automation changes' })).not.toBeChecked();
  });
});
