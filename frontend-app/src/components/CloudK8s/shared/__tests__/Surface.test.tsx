import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { Surface } from '../Surface';

describe('Surface (DESIGN §3, §8.1)', () => {
  it('hero tier has no border / bg / radius / shadow', () => {
    render(
      <Surface tier="hero" aria-label="hero">
        <span>child</span>
      </Surface>,
    );
    const root = screen.getByLabelText('hero');
    const cls = root.className;
    expect(cls).not.toMatch(/\bborder\b/);
    expect(cls).not.toMatch(/bg-white/);
    expect(cls).not.toMatch(/rounded-/);
    expect(cls).not.toMatch(/shadow-/);
    expect(cls).toContain('px-0');
    expect(cls).toContain('py-0');
  });

  it('grouped tier has border + bg + radius but NO shadow', () => {
    render(
      <Surface tier="grouped" aria-label="grouped">
        <span>child</span>
      </Surface>,
    );
    const cls = screen.getByLabelText('grouped').className;
    expect(cls).toContain('rounded-[8px]');
    expect(cls).toContain('border-[color:var(--chrome-line)]');
    expect(cls).toContain('bg-[color:var(--chrome-card)]');
    expect(cls).not.toMatch(/shadow-/);
  });

  it('table tier reads as the same container as grouped, with NO shadow (v3 removed elevation)', () => {
    render(
      <Surface tier="table" aria-label="table">
        <span>child</span>
      </Surface>,
    );
    const cls = screen.getByLabelText('table').className;
    expect(cls).toContain('rounded-[8px]');
    expect(cls).toContain('border-[color:var(--chrome-line)]');
    expect(cls).toContain('bg-[color:var(--chrome-card)]');
    expect(cls).not.toMatch(/shadow-/);
  });

  it('applies exactly one divider class for divide="y"', () => {
    render(
      <Surface tier="grouped" divide="y" aria-label="divy">
        <span>a</span>
        <span>b</span>
      </Surface>,
    );
    const root = screen.getByLabelText('divy');
    const body = root.querySelector('.divide-y');
    expect(body).not.toBeNull();
    expect(body!.className).toContain('divide-y');
    expect(body!.className).not.toContain('divide-x');
    // only one divide level anywhere in the subtree
    expect(root.querySelectorAll('.divide-x, .divide-y').length).toBe(1);
  });

  it('applies exactly one divider class for divide="x"', () => {
    render(
      <Surface tier="grouped" divide="x" aria-label="divx">
        <span>a</span>
        <span>b</span>
      </Surface>,
    );
    const root = screen.getByLabelText('divx');
    const body = root.querySelector('.divide-x');
    expect(body).not.toBeNull();
    expect(body!.className).not.toContain('divide-y');
    expect(root.querySelectorAll('.divide-x, .divide-y').length).toBe(1);
  });

  it('adds no divider when divide is omitted (default none)', () => {
    render(
      <Surface tier="grouped" aria-label="nodiv">
        <span>a</span>
      </Surface>,
    );
    const root = screen.getByLabelText('nodiv');
    expect(root.querySelectorAll('.divide-x, .divide-y').length).toBe(0);
  });

  it('marks its root data-k8s-kit', () => {
    render(
      <Surface tier="grouped" aria-label="kit">
        <span>a</span>
      </Surface>,
    );
    expect(screen.getByLabelText('kit')).toHaveAttribute('data-k8s-kit', '');
  });

  it('ships dark: variant classes per tier (dark-mode regression guard, NIT-1)', () => {
    const { rerender } = render(
      <Surface tier="grouped" aria-label="dark">
        <span>a</span>
      </Surface>,
    );
    let cls = screen.getByLabelText('dark').className;
    // Chrome now flows through theme-aware --chrome-* tokens (one class, both themes).
    expect(cls).toContain('bg-[color:var(--chrome-card)]');
    expect(cls).toContain('border-[color:var(--chrome-line)]');

    rerender(
      <Surface tier="table" aria-label="dark">
        <span>a</span>
      </Surface>,
    );
    cls = screen.getByLabelText('dark').className;
    expect(cls).toContain('bg-[color:var(--chrome-card)]');
    expect(cls).toContain('border-[color:var(--chrome-line)]');
  });

  it('renders a title and aside header row', () => {
    render(
      <Surface tier="table" title="Namespaces" aside={<span>source</span>} aria-label="hdr">
        <span>row</span>
      </Surface>,
    );
    expect(screen.getByText('Namespaces')).toBeInTheDocument();
    expect(screen.getByText('source')).toBeInTheDocument();
  });

  it('renders as a section by default and a div when asked', () => {
    const { rerender } = render(
      <Surface tier="grouped" aria-label="asel">
        <span>a</span>
      </Surface>,
    );
    expect(screen.getByLabelText('asel').tagName).toBe('SECTION');
    rerender(
      <Surface tier="grouped" as="div" aria-label="asel">
        <span>a</span>
      </Surface>,
    );
    expect(screen.getByLabelText('asel').tagName).toBe('DIV');
  });
});
