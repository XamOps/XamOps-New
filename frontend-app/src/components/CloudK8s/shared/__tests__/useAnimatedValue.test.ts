import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useAnimatedValue } from '../useAnimatedValue';

function mockMatchMedia(matches: boolean) {
  window.matchMedia = vi.fn().mockImplementation((query: string) => ({
    matches,
    media: query,
    onchange: null,
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
    addListener: vi.fn(),
    removeListener: vi.fn(),
    dispatchEvent: vi.fn(),
  })) as unknown as typeof window.matchMedia;
}

describe('useAnimatedValue (DESIGN §4.2)', () => {
  const originalMatchMedia = window.matchMedia;
  const originalRaf = window.requestAnimationFrame;
  const originalCancel = window.cancelAnimationFrame;

  beforeEach(() => {
    vi.useFakeTimers();
    // Drive the rAF loop manually off fake timers so we can step the animation.
    window.requestAnimationFrame = ((cb: FrameRequestCallback) =>
      setTimeout(() => cb(performance.now()), 16) as unknown as number) as typeof window.requestAnimationFrame;
    window.cancelAnimationFrame = ((id: number) => clearTimeout(id)) as typeof window.cancelAnimationFrame;
  });

  afterEach(() => {
    vi.clearAllTimers();
    vi.useRealTimers();
    window.matchMedia = originalMatchMedia;
    window.requestAnimationFrame = originalRaf;
    window.cancelAnimationFrame = originalCancel;
  });

  it('counts toward the target when motion is allowed', () => {
    mockMatchMedia(false);
    const { result, rerender } = renderHook(({ t }) => useAnimatedValue(t), {
      initialProps: { t: 0 },
    });
    expect(result.current).toBe(0);

    rerender({ t: 100 });
    // After enough frames (> 900ms) the value reaches the target.
    act(() => {
      vi.advanceTimersByTime(1000);
    });
    expect(result.current).toBe(100);
  });

  it('no-ops when the target is unchanged', () => {
    mockMatchMedia(false);
    const raf = vi.spyOn(window, 'requestAnimationFrame');
    const { result, rerender } = renderHook(({ t }) => useAnimatedValue(t), {
      initialProps: { t: 42 },
    });
    expect(result.current).toBe(42);

    rerender({ t: 42 });
    act(() => {
      vi.advanceTimersByTime(1000);
    });
    expect(result.current).toBe(42);
    expect(raf).not.toHaveBeenCalled();
  });

  it('snaps to the target with no rAF under reduced motion', () => {
    mockMatchMedia(true);
    const raf = vi.spyOn(window, 'requestAnimationFrame');
    const { result, rerender } = renderHook(({ t }) => useAnimatedValue(t), {
      initialProps: { t: 0 },
    });

    rerender({ t: 250 });
    expect(result.current).toBe(250);
    expect(raf).not.toHaveBeenCalled();
  });
});
