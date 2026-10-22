/**
 * Provider-aware route resolution for dashboard links.
 *
 * Several dashboard widgets (KPI cards, summary cards) are shared across the AWS,
 * GCP and Azure dashboards. They declare their "View more" / "View Details" target
 * using the AWS route, which meant that on /gcp-dashboard a card such as Rightsizing
 * linked to /rightsizing — the AWS Rightsizing page — instead of /gcp-rightsizing.
 *
 * Declare the AWS route in the component, then resolve it through `resolveProviderLink`
 * with the active provider. Routes with no provider-specific variant (e.g. /tickets,
 * /aiops, /flex-compute, /cicd-pipelines) are deliberately absent from the table and
 * fall through unchanged.
 *
 * Keep the table in sync with the routes registered in App.tsx.
 */

export type RouteProvider = 'AWS' | 'GCP' | 'Azure';

/** AWS route → provider variant. Only list a provider when the route actually exists. */
const PROVIDER_ROUTE_MAP: Record<string, Partial<Record<RouteProvider, string>>> = {
  '/dashboard':       { GCP: '/gcp-dashboard',         Azure: '/azure-dashboard' },
  '/cost':            { GCP: '/gcp-cost',              Azure: '/azure-cost' },
  '/finops':          { GCP: '/gcp-finops',            Azure: '/azure-finops' },
  '/waste':           { GCP: '/gcp-waste',             Azure: '/azure-waste' },
  '/rightsizing':     { GCP: '/gcp-rightsizing',       Azure: '/azure-rightsizing' },
  '/reservation':     { GCP: '/gcp-reservations',      Azure: '/azure-reservations' },
  '/ebs-rightsizing': { GCP: '/gcp-disk-rightsizing',  Azure: '/azure-disk-rightsizing' },
  '/cloudsitter':     { GCP: '/gcp-cloudsitter',       Azure: '/azure-cloudsitter' },
  '/cloudlist':       { GCP: '/gcp-cloudlist',         Azure: '/azure-cloudlist' },
  '/cloudk8s':        { GCP: '/gcp-cloudk8s',          Azure: '/azure-cloudk8s' },
  '/cloudmap':        { GCP: '/gcp-cloudmap' },
  '/security':        { GCP: '/gcp-security',          Azure: '/azure-security' },
  '/war':             { GCP: '/gcp-war',               Azure: '/azure-war' },
  '/performance':     { GCP: '/gcp-performance',       Azure: '/azure-performance' },
  '/alerts':          { GCP: '/gcp-alerts',            Azure: '/azure-alerts' },
  '/dataops':         { GCP: '/gcp-dataops',           Azure: '/azure-dataops' },
  // Azure has no AutoSpotting page (/azure-autospotting only redirects to the
  // dashboard), and its AutoSpotting KPI cards are hidden, so it is left unmapped.
  '/spot-automation':  { GCP: '/gcp-autospotting' },
  '/autospotting':     { GCP: '/gcp-autospotting' },
};

/**
 * Swap an AWS route for the active provider's equivalent.
 * Preserves any query string / hash, and returns the input untouched when the
 * provider is AWS or no variant exists.
 */
export const resolveProviderLink = (link: string, provider?: RouteProvider | string | null): string => {
  if (!link || !provider || provider === 'AWS') return link;

  const splitAt = link.search(/[?#]/);
  const path = splitAt === -1 ? link : link.slice(0, splitAt);
  const suffix = splitAt === -1 ? '' : link.slice(splitAt);

  const mapped = PROVIDER_ROUTE_MAP[path]?.[provider as RouteProvider];
  return mapped ? `${mapped}${suffix}` : link;
};

export default resolveProviderLink;
