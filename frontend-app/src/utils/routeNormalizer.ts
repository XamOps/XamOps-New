/**
 * Collapses a concrete pathname into the route pattern it matched.
 *
 * Page-time aggregates group by route, so the raw pathname is the wrong key: every SRE
 * investigation would become its own "page" and the top-pages report would be a list of
 * ids. `/sre/investigations/8f21` and `/sre/investigations/4c09` are the same page visited
 * twice.
 *
 * React Router could supply the matched pattern directly via `useMatches()`, but that hook
 * requires a data router. `App.tsx` uses `<BrowserRouter>` with a `<Routes>` tree, so the
 * match data is not exposed and the pattern has to be reconstructed here. Lifting the app to
 * `createBrowserRouter` would remove this file at the cost of restructuring ~150 route
 * declarations, which is not a trade worth making for telemetry.
 *
 * Tab routes such as `/xamsecure/:tab` are deliberately preserved as distinct pages: the tabs
 * there are genuinely separate screens and are addressable, so collapsing them would discard
 * the more useful breakdown.
 */

/**
 * Patterns with path parameters, longest first.
 *
 * Order matters — `/soc2-program/frameworks/:frameworkId/controls/:controlId` has to be tested
 * before `/soc2-program/frameworks/:frameworkId`, or the nested route would be truncated to
 * its parent and control-level visits would be misattributed.
 */
const PARAM_PATTERNS: string[] = [
  '/soc2-program/frameworks/:frameworkId/controls/:controlId',
  '/soc2-program/frameworks/:frameworkId',
  '/soc2-program/policies/:policyId',
  '/soc2-program/risks/:riskId',
  '/soc2-program/vendors/:vendorId',
  '/soc2-program/:tab',
  '/sre/investigations/:id',
  '/xamsecure/:tab',
  '/xamlens/:tab',
];

/**
 * Splat routes, which own every path beneath them.
 *
 * `/observability/*` renders one page that manages its own internal navigation, so the whole
 * subtree reports as a single route rather than inventing a page per sub-view.
 */
const SPLAT_PREFIXES: string[] = ['/observability'];

/** Values that look like identifiers rather than route segments. */
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const NUMERIC = /^\d+$/;
const LONG_HEX = /^[0-9a-f]{16,}$/i;

/** AWS-style identifiers, which appear in resource deep links. */
const CLOUD_ID = /^(i|vol|sg|subnet|vpc|ami|snap|eni|arn)-[0-9a-z]+$/i;

function looksLikeId(segment: string): boolean {
  return (
    UUID.test(segment) ||
    NUMERIC.test(segment) ||
    LONG_HEX.test(segment) ||
    CLOUD_ID.test(segment)
  );
}

/** Splits a path into non-empty segments, ignoring query and hash. */
function segmentsOf(path: string): string[] {
  const clean = path.split('?')[0].split('#')[0];
  return clean.split('/').filter(Boolean);
}

/**
 * Tests a concrete path against a pattern of the same segment count, treating `:param`
 * segments as wildcards.
 */
function matchesPattern(pathSegments: string[], pattern: string): boolean {
  const patternSegments = segmentsOf(pattern);
  if (patternSegments.length !== pathSegments.length) return false;
  return patternSegments.every((seg, i) =>
    seg.startsWith(':') ? pathSegments[i].length > 0 : seg === pathSegments[i],
  );
}

/**
 * Returns the route pattern for a pathname.
 *
 * Falls back to collapsing id-shaped segments to `:id`, so a route added to the app after
 * this file was last touched still aggregates sensibly instead of fragmenting per record.
 */
export function normalizeRoute(pathname: string): string {
  if (!pathname || pathname === '/') return '/';

  const pathSegments = segmentsOf(pathname);
  if (pathSegments.length === 0) return '/';

  for (const prefix of SPLAT_PREFIXES) {
    if (pathname === prefix || pathname.startsWith(`${prefix}/`)) {
      return prefix;
    }
  }

  for (const pattern of PARAM_PATTERNS) {
    if (matchesPattern(pathSegments, pattern)) {
      return pattern;
    }
  }

  const collapsed = pathSegments.map((seg) => (looksLikeId(seg) ? ':id' : seg));
  return `/${collapsed.join('/')}`;
}

/**
 * Whether a route should be tracked at all.
 *
 * Login and the loader preview are excluded: neither is a product page, and the login screen
 * would otherwise top the time-on-page chart for anyone who leaves a tab sitting on it.
 */
export function isTrackableRoute(pathname: string): boolean {
  if (!pathname) return false;
  const ignored = ['/', '/loader-preview', '/signup/complete', '/trial-expired', '/feedback'];
  return !ignored.includes(pathname.split('?')[0]);
}
