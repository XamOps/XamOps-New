import '@testing-library/jest-dom';

/**
 * jsdom ships `window.CSS` but not `CSS.supports`. Highcharts 12 calls `win.CSS?.supports(...)` at
 * module-init time to feature-detect `color-mix()`, which throws "CSS.supports is not a function"
 * the moment any Highcharts chart is imported under test (the XamSecure Overview area chart). A
 * minimal stub makes the detection return false (the safe branch) instead of crashing. Test-only;
 * never shipped.
 */
if (typeof globalThis !== 'undefined') {
  const cssObj = ((globalThis as { CSS?: Record<string, unknown> }).CSS ??= {} as Record<string, unknown>);
  if (typeof cssObj.supports !== 'function') {
    cssObj.supports = () => false;
  }
}

/**
 * Registers the bundled Iconify icons before any test renders.
 *
 * <p>Without this, tests are the only environment where the bundle is absent: it is imported by
 * `main.tsx`, which tests never load. `@iconify/react` then treats every `<Icon>` as unresolved
 * and queues an HTTP load from api.iconify.design, on a timer.
 *
 * <p>That timer is the problem. It outlives the test that scheduled it, and when it fires after
 * Vitest has torn the jsdom environment down, its `setState` reaches for a `window` that no
 * longer exists:
 *
 * <pre>
 *   ReferenceError: window is not defined
 *     at resolveUpdatePriority (react-dom-client.development.js)
 *     at changeState (@iconify/react/dist/iconify.js)
 *     at Timeout._onTimeout (@iconify/react/dist/iconify.js)
 * </pre>
 *
 * <p>Vitest reports that as an unhandled error and exits non-zero even when every test passed,
 * so the whole suite fails on a race rather than on a defect. It is timing-dependent, which is
 * why it surfaces on slower CI runners far more often than locally.
 *
 * <p>Importing the bundle resolves every icon synchronously from memory, so no load is ever
 * queued and no timer is ever created. It also makes the suite hermetic — tests no longer depend
 * on egress to a third-party host.
 */
import '../icons/bundle';
