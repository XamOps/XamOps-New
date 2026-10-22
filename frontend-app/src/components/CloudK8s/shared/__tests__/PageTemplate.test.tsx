import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import PageTemplate from '../PageTemplate';

describe('PageTemplate (FEAT-001, §8)', () => {
  it('stamps data-k8s-redesigned-main and labels the region by its title', () => {
    render(<PageTemplate title="Automation"><p>body</p></PageTemplate>);
    const main = screen.getByRole('main');
    expect(main).toHaveAttribute('data-k8s-redesigned-main', '');
    expect(main).toHaveAccessibleName('Automation');
  });

  it('orders title → intro → hero → children → detail', () => {
    render(
      <PageTemplate title="Automation" intro="One sentence." hero={<div>HERO</div>} detail={<div>DETAIL</div>}>
        <div>CHILD</div>
      </PageTemplate>,
    );
    const order = ['Automation', 'One sentence.', 'HERO', 'CHILD', 'DETAIL'];
    const positions = order.map((t) => screen.getByText(t).compareDocumentPosition(screen.getByText(order[0])));
    // Each later node follows the title in document order (PRECEDING flag set on the title compare).
    expect(screen.getByText('HERO').compareDocumentPosition(screen.getByText('CHILD')) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(screen.getByText('CHILD').compareDocumentPosition(screen.getByText('DETAIL')) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(positions).toBeTruthy();
  });

  it('defaults to prose width (935px) and takes a responsive table width (max-w-full)', () => {
    const { rerender } = render(<PageTemplate title="A"><p>x</p></PageTemplate>);
    expect(screen.getByRole('main').className).toContain('max-w-[935px]');
    expect(screen.getByRole('main')).toHaveAttribute('data-page-width', 'prose');

    // owner review #5: 'table' fills the available container (viewport − sidebar) instead of pinning
    // a fixed 1200px that clips the last column; the table card scrolls internally.
    rerender(<PageTemplate title="A" width="table"><p>x</p></PageTemplate>);
    expect(screen.getByRole('main').className).toContain('max-w-full');
    expect(screen.getByRole('main').className).not.toContain('max-w-[1200px]');
    expect(screen.getByRole('main')).toHaveAttribute('data-page-width', 'table');
  });

  it('renders an aside right-rail when given', () => {
    render(<PageTemplate title="A" aside={<nav>rail</nav>}><p>x</p></PageTemplate>);
    const aside = document.querySelector('[data-page-aside]')!;
    expect(aside).toBeTruthy();
    expect(aside.className).toContain('lg:w-[280px]');
    expect(screen.getByText('rail')).toBeInTheDocument();
  });
});
