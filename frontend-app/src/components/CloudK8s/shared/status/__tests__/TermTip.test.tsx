import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import TermTip from '../TermTip';

describe('TermTip (glossary §1)', () => {
  it('is a button whose aria-describedby points at the tooltip', () => {
    render(<TermTip term="in place" />);
    const button = screen.getByRole('button');
    const id = button.getAttribute('aria-describedby');
    expect(id).toBeTruthy();
    const tip = document.getElementById(id!);
    expect(tip).toBeTruthy();
    expect(tip).toHaveAttribute('role', 'tooltip');
  });

  it('shows the title, the exact glossary text and the source line', () => {
    render(<TermTip term="deferred" />);
    const id = screen.getByRole('button').getAttribute('aria-describedby')!;
    const tip = document.getElementById(id)!;
    expect(tip.textContent).toContain("What's this? deferred");
    expect(tip.textContent).toContain('Kubernetes accepted the new size');
    expect(tip.textContent).toContain('Activity: results');
  });

  it('is hidden until focus, then shown, then closed on Escape', () => {
    render(<TermTip term="replicas" />);
    const button = screen.getByRole('button');
    const tip = document.getElementById(button.getAttribute('aria-describedby')!)!;
    expect(tip).toHaveAttribute('hidden');

    fireEvent.focus(button);
    expect(tip).not.toHaveAttribute('hidden');
    expect(button).toHaveAttribute('aria-expanded', 'true');

    fireEvent.keyDown(button, { key: 'Escape' });
    expect(tip).toHaveAttribute('hidden');
  });

  it('opens on hover', () => {
    render(<TermTip term="limit" />);
    const button = screen.getByRole('button');
    const tip = document.getElementById(button.getAttribute('aria-describedby')!)!;
    fireEvent.mouseEnter(button);
    expect(tip).not.toHaveAttribute('hidden');
    fireEvent.mouseLeave(button);
    expect(tip).toHaveAttribute('hidden');
  });

  it('renders plain text for an unknown term', () => {
    render(<TermTip term="not-a-term">plain</TermTip>);
    expect(screen.queryByRole('button')).toBeNull();
    expect(screen.getByText('plain')).toBeTruthy();
  });
});
