import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import StatusDot from '../StatusDot';

describe('StatusDot (FEAT-001)', () => {
  it('carries data-tone, data-k8s-kit, a mandatory aria-hidden svg and a visible word', () => {
    render(<StatusDot tone="good" label="Running" />);
    const root = screen.getByText('Running').closest('[data-tone]')!;
    expect(root).toHaveAttribute('data-tone', 'good');
    expect(root).toHaveAttribute('data-k8s-kit', '');
    // colourAudit check #3: svg[aria-hidden] + non-empty text in the same element.
    expect(root.querySelector('svg[aria-hidden="true"]')).toBeTruthy();
    expect((root.textContent ?? '').trim().length).toBeGreaterThan(0);
  });

  it('tints the dot in the tone and shows no pill', () => {
    render(<StatusDot tone="bad" label="Failed" />);
    const root = screen.getByText('Failed').closest('[data-tone]')!;
    expect(root.querySelector('svg')?.getAttribute('class')).toContain('text-status-bad');
    // No pill / chip background.
    expect(root.className).not.toMatch(/rounded-full/);
  });
});
