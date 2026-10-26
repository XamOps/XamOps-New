import '@testing-library/jest-dom';
import { vi } from 'vitest';
import type { ComponentProps } from 'react';
import { createElement } from 'react';

/**
 * Global default `fetch` mock — the root cause of the CI job failure.
 *
 * The CloudK8s Automation components (OverviewPanel, Activity, VPA, HPA, node and workload pages)
 * are mounted for real by MANY test files, not one. On mount their data hooks (useAutomationRead
 * and the vertical/activity/usage feeds) call the app's api client — apiClient.default.fetch /
 * the named apiFetch in src/utils/apiClient.ts — which calls the native `fetch` with RAW RELATIVE
 * paths like "/api/eks/automation/vertical/{cluster}". Under jsdom that reaches node's `fetch`,
 * which rejects a relative URL with "TypeError: Failed to parse URL from /api/…" / "Invalid URL".
 * Nothing awaits these fire-and-forget reads, so vitest sees them as UNHANDLED rejections and
 * fails the whole job even though all ~2341 tests pass.
 *
 * A prior fix mocked `apiClient.fetch` in ONE file (EksDetailsPage.test.tsx); that only silenced
 * that file, so CI — which runs the full suite in a different parallel order — still failed on the
 * other files. The durable fix is global: install a benign default `fetch` here in the shared
 * setup so NO unmocked relative-URL fetch can ever reject unhandled, in ANY test file.
 *
 * The default resolves to an untyped 404 — exactly the "backend without this read" answer every
 * Automation page already handles gracefully — so it hides no real failure. Tests that need a
 * specific response still override `fetch` (or mock the api client) themselves, and those
 * overrides win: this assignment only sets the baseline; a per-test `vi.stubGlobal('fetch')`,
 * `vi.spyOn`, or direct `window.fetch = …` replaces it for that test.
 */
const benignNotFound = (): Response =>
  ({
    status: 404,
    ok: false,
    headers: new Headers(),
    text: async () => '',
    json: async () => ({}),
    clone() {
      return this as Response;
    },
  }) as unknown as Response;

/**
 * Install the baseline with a PLAIN assignment, not `vi.stubGlobal`.
 *
 * `vi.stubGlobal('fetch', …)` records the pre-stub `fetch` as the thing `vi.unstubAllGlobals()`
 * restores. Four files (e.g. loginAsAccountDashboard.test.tsx) assign `window.fetch` directly in
 * `vi.hoisted()` and call `vi.unstubAllGlobals()` in `afterEach`; if this baseline were a global
 * stub, that `unstubAllGlobals` would tear it down AND clobber their own hoisted stub between
 * tests. A plain assignment is invisible to `vi.unstubAllGlobals()`, so those files keep the exact
 * fetch lifecycle they had before this fix, while every other file still gets the benign default.
 */
globalThis.fetch = (async () => benignNotFound()) as typeof fetch;

/**
 * Synchronous `@iconify/react` stub — the SECOND unhandled error that fails the CI job.
 *
 * The real `Icon` resolves an icon asynchronously: when the bundle has not registered it yet it
 * schedules a `setTimeout` load and later calls `dispatchSetState` to swap the icon in. In jsdom
 * that timer can fire AFTER the test's environment has been torn down, so the `setState` reaches
 * for a `window` that no longer exists:
 *
 *   ReferenceError: window is not defined
 *     at resolveUpdatePriority (react-dom-client.development.js)
 *     at changeState (@iconify/react/dist/iconify.js)
 *     at Timeout._onTimeout (@iconify/react/dist/iconify.js)
 *
 * vitest reports that dangling post-teardown timer as an unhandled error and fails the job even
 * though every test passes. It is timing- and order-dependent, which is why it surfaces on CI far
 * more than locally. Importing the bundle (below) removes it for icons that ARE bundled, but any
 * test that renders an icon the bundle lacks can still schedule the timer.
 *
 * Mocking the module makes `Icon` render SYNCHRONOUSLY with no timer and no async state update, so
 * nothing can fire after teardown. The stub renders a single `<svg>` and forwards `aria-hidden`
 * (defaulting to "true", matching Iconify's own default for an icon with no label) plus a
 * `data-icon` id, so the DOM assertions keep passing: colourAudit §2.7 check #3 and the status
 * component tests look for `svg[aria-hidden="true"]` inside each toned surface, and nothing relied
 * on the real icon's path geometry. `addCollection` (the only other import, used by the generated
 * icon bundle) becomes a harmless no-op.
 */
vi.mock('@iconify/react', () => {
  type IconProps = {
    icon?: unknown;
    'aria-hidden'?: boolean | string;
    'aria-label'?: string;
    title?: string;
  } & ComponentProps<'svg'>;

  const Icon = ({ icon, title, ...rest }: IconProps) => {
    const hasLabel = rest['aria-label'] != null || title != null;
    const ariaHidden = rest['aria-hidden'] ?? (hasLabel ? undefined : true);
    const iconId = typeof icon === 'string' ? icon : undefined;
    return createElement('svg', {
      ...rest,
      'aria-hidden': ariaHidden,
      'data-icon': iconId,
    });
  };

  return {
    Icon,
    InlineIcon: Icon,
    addCollection: () => {},
    addIcon: () => {},
    loadIcons: () => {},
    loadIcon: async () => null,
  };
});

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
 * Registers the bundled Iconify icons, matching what `main.tsx` does at app start.
 *
 * <p>The `@iconify/react` mock above now renders every `<Icon>` synchronously, so this import is
 * no longer what prevents the post-teardown "window is not defined" timer race (the mock is) —
 * with the module mocked, `addCollection` here is a no-op. It is kept so the test environment
 * still mirrors the real registration step and stays hermetic (no egress to api.iconify.design),
 * and so removing the mock in the future would not silently reintroduce the race.
 */
import '../icons/bundle';
