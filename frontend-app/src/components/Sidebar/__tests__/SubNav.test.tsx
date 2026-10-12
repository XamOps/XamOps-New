import { render } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import SubNav from '../SubNav';

const inner = (container: HTMLElement) => container.firstElementChild!.firstElementChild as HTMLElement;

describe('SubNav', () => {
  it('renders the default level exactly as before the level prop existed', () => {
    const open = render(<SubNav isOpen dark={false}><a href="/x">x</a></SubNav>);
    expect(inner(open.container).className).toBe('min-h-0 space-y-0.5 ml-3 pl-3 border-l border-[rgba(0,0,0,0.08)]');
    expect(open.container.firstElementChild).toHaveAttribute('aria-hidden', 'false');
    open.unmount();

    const closedDark = render(<SubNav isOpen={false} dark><a href="/x">x</a></SubNav>);
    expect(inner(closedDark.container).className)
      .toBe('min-h-0 space-y-0.5 ml-3 pl-3 border-l pointer-events-none border-[rgba(255,255,255,0.08)]');
    expect(closedDark.container.firstElementChild).toHaveAttribute('aria-hidden', 'true');
  });

  it('indents a second level less, so nested labels still fit', () => {
    const { container } = render(<SubNav isOpen dark={false} level={2}><a href="/x">x</a></SubNav>);
    expect(inner(container).className).toBe('min-h-0 space-y-0.5 ml-2 pl-2 border-l border-[rgba(0,0,0,0.08)]');
  });
});
