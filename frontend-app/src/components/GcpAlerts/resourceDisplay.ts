/**
 * Turning GCP resource names into something a person can read.
 *
 * A single Artifact Registry finding arrives as roughly 200 characters:
 *
 *   //artifactregistry.googleapis.com/projects/digital-production-473108/locations/asia/
 *   repositories/gcr-registry-asia-dev/dockerImages/metadome-dp-server-dev@sha256:55b824…
 *
 * The old table concatenated that onto the description in a small grey font, wrapped over two
 * lines, for every one of 2,124 rows. The information is necessary but almost none of those
 * characters carry meaning for the reader: the repository and the image name do, and the digest
 * matters only as something to copy.
 */

export interface ResourceParts {
  /** The short label to show — usually the image or resource name. */
  label: string;
  /** Where it lives, shown as secondary text. Empty when there is nothing useful to say. */
  context: string;
  /** The version-specific tail (a digest or tag), abbreviated for display. */
  version: string;
  /** The untouched original, for copying and for the title attribute. */
  full: string;
}

/** The GCP service host a resource name refers to, e.g. `artifactregistry.googleapis.com`. */
const serviceHost = (resource: string): string | null => {
  const match = resource.match(/^\/\/([^/]+)\//);
  return match ? match[1] : null;
};

const shortenDigest = (version: string): string => {
  const sha = version.match(/^sha256:([0-9a-f]{8})/i);
  return sha ? `sha256:${sha[1]}…` : version;
};

/**
 * Split a resource name into a label, its context, and its version.
 *
 * Deliberately tolerant: anything unrecognised falls back to showing the last path segment as the
 * label with the rest as context, which is still far better than the raw string and never throws.
 */
export const describeResource = (resource: string): ResourceParts => {
  const full = resource || '';
  if (!full) {
    return { label: 'Unknown resource', context: '', version: '', full };
  }

  // Split off the version marker first, so it never ends up inside the label.
  let body = full;
  let version = '';
  const digestAt = body.indexOf('@');
  if (digestAt > 0) {
    version = shortenDigest(body.slice(digestAt + 1));
    body = body.slice(0, digestAt);
  } else {
    const lastSlash = body.lastIndexOf('/');
    const tagAt = body.indexOf(':', lastSlash + 1);
    if (tagAt > 0) {
      version = body.slice(tagAt + 1);
      body = body.slice(0, tagAt);
    }
  }

  const withoutHost = body.replace(/^\/\/[^/]+\//, '');
  const segments = withoutHost.split('/').filter(Boolean);
  const label = segments.length > 0 ? segments[segments.length - 1] : body;

  // GCP resource paths are type/name pairs: projects/P/locations/L/repositories/R/dockerImages/N.
  // The useful context is the named values, not the type keywords, so pick the value that follows
  // each collection keyword and drop the project, which is already selected in the sidebar.
  const context: string[] = [];
  for (let i = 0; i + 1 < segments.length; i += 2) {
    const collection = segments[i];
    const value = segments[i + 1];
    if (collection === 'projects') continue;
    if (value === label) continue;
    context.push(value);
  }

  return {
    label,
    context: context.join(' / '),
    version,
    full,
  };
};

/**
 * A GCP console URL for a resource, or null when one cannot be built reliably.
 *
 * Returning null rather than guessing is the point. A link that lands on the wrong page costs more
 * trust than no link, and the alert row already offers a Security Command Center link as its
 * general-purpose action.
 */
export const resourceConsoleLink = (resource: string, projectId: string | null): string | null => {
  if (!resource || !projectId) return null;

  if (serviceHost(resource) === 'artifactregistry.googleapis.com') {
    // projects/P/locations/L/repositories/R/dockerImages/IMAGE[@sha256:…]
    const match = resource.match(
      /projects\/([^/]+)\/locations\/([^/]+)\/repositories\/([^/]+)\/dockerImages\/([^@:]+)/
    );
    if (match) {
      const [, project, location, repository, image] = match;
      return `https://console.cloud.google.com/artifacts/docker/${encodeURIComponent(project)}/${encodeURIComponent(location)}/${encodeURIComponent(repository)}/${encodeURIComponent(image)}?project=${encodeURIComponent(project)}`;
    }
  }

  return null;
};

/** Relative age, for the "data as of" line. */
export const formatRelativeTime = (isoTimestamp: string | null): string => {
  if (!isoTimestamp) return 'unknown';

  const then = new Date(isoTimestamp).getTime();
  if (Number.isNaN(then)) return 'unknown';

  const diffSeconds = Math.round((Date.now() - then) / 1000);
  // Clock skew between the browser and the server can make a fresh timestamp look like the future.
  // "in 4 seconds" would read as a bug, so anything near-now is just "just now".
  if (diffSeconds < 60) return 'just now';
  if (diffSeconds < 3600) {
    const minutes = Math.floor(diffSeconds / 60);
    return `${minutes} minute${minutes === 1 ? '' : 's'} ago`;
  }
  if (diffSeconds < 86400) {
    const hours = Math.floor(diffSeconds / 3600);
    return `${hours} hour${hours === 1 ? '' : 's'} ago`;
  }
  const days = Math.floor(diffSeconds / 86400);
  return `${days} day${days === 1 ? '' : 's'} ago`;
};

/** Absolute local timestamp, shown alongside the relative one so the exact value is available. */
export const formatAbsoluteTime = (isoTimestamp: string | null): string => {
  if (!isoTimestamp) return 'Unknown';
  const date = new Date(isoTimestamp);
  if (Number.isNaN(date.getTime())) return 'Unknown';
  return date.toLocaleString(undefined, {
    year: 'numeric', month: 'short', day: 'numeric',
    hour: '2-digit', minute: '2-digit',
  });
};
