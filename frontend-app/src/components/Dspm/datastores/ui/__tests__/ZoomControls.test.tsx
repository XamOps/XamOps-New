import { useState } from 'react';
import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ZoomControls } from '../ZoomControls';
import { ZOOM_MAX, ZOOM_MIN, ZOOM_STEP, zoomStep } from '../zoom';

function Harness({ initial }: { initial: number }) {
  const [zoom, setZoom] = useState(initial);
  return <ZoomControls zoom={zoom} onZoom={setZoom} />;
}

describe('ZoomControls', () => {
  it('reads "+", the percent, then "−", as the console has it', () => {
    const { container } = render(<Harness initial={0.55} />);
    expect(screen.getAllByRole('button').map(b => b.getAttribute('aria-label'))).toEqual(['Zoom in', 'Zoom out']);
    expect(container).toHaveTextContent(/^\+55%−$/);
  });

  it('disables Zoom out at 20% and Zoom in at 150%', async () => {
    const user = userEvent.setup();
    render(<Harness initial={0.7} />);
    const zoomIn = screen.getByRole('button', { name: 'Zoom in' });
    const zoomOut = screen.getByRole('button', { name: 'Zoom out' });

    for (let i = 0; i < 5; i += 1) await user.click(zoomOut);
    expect(screen.getByText('20%')).toBeInTheDocument();
    expect(zoomOut).toBeDisabled();
    expect(zoomIn).toBeEnabled();

    for (let i = 0; i < 13; i += 1) await user.click(zoomIn);
    expect(screen.getByText('150%')).toBeInTheDocument();
    expect(zoomIn).toBeDisabled();
    expect(zoomOut).toBeEnabled();
  });
});

describe('zoomStep', () => {
  it('has the shared bounds and step', () => {
    expect([ZOOM_MIN, ZOOM_MAX, ZOOM_STEP]).toEqual([0.2, 1.5, 0.1]);
  });

  it('steps by 0.1, rounded to two decimals', () => {
    expect(zoomStep(0.7, 1)).toBe(0.8);
    expect(zoomStep(0.7, -1)).toBe(0.6);
    expect(zoomStep(0.55, 1)).toBe(0.65);
    expect(zoomStep(0.55, -1)).toBe(0.45);
    let zoom = 0.2;
    for (let i = 0; i < 13; i += 1) zoom = zoomStep(zoom, 1);
    expect(zoom).toBe(1.5);
  });

  it('clamps to 20% and 150%', () => {
    expect(zoomStep(0.25, -1)).toBe(0.2);
    expect(zoomStep(0.2, -1)).toBe(0.2);
    expect(zoomStep(1.45, 1)).toBe(1.5);
    expect(zoomStep(1.5, 1)).toBe(1.5);
  });
});
