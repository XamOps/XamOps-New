/**
 * Size format utility functions for MediaOps transcoding.
 * Converts bytes to human-readable format and provides range formatting.
 */

/**
 * Converts bytes to a human-readable string (e.g., "1.5 GB").
 * Uses binary units (1024-based).
 * @param bytes Number of bytes
 * @returns Human-readable size string
 */
export function formatBytes(bytes: number): string {
  if (!bytes || !Number.isFinite(bytes) || bytes === 0) return '0 B';
  if (bytes < 0) return '0 B';

  const k = 1024;
  const sizes = ['B', 'KB', 'MB', 'GB', 'TB', 'PB'];
  const i = Math.floor(Math.log(bytes) / Math.log(k));
  const index = Math.min(i, sizes.length - 1);
  const value = bytes / Math.pow(k, index);

  return `${parseFloat(value.toFixed(1))} ${sizes[index]}`;
}

/**
 * Formats a size estimation range into a human-readable string.
 * Output example: "~150 MB (120–180 MB)"
 *
 * @param lower Lower bound in bytes
 * @param upper Upper bound in bytes
 * @returns Formatted size range string
 */
export function formatSizeRange(lower: number, upper: number): string {
  if (!Number.isFinite(lower) || !Number.isFinite(upper) || lower < 0 || upper < 0) {
    return '—';
  }

  const estimated = Math.round((lower + upper) / 2);
  const estimatedStr = formatBytes(estimated);
  const lowerStr = formatBytes(lower);
  const upperStr = formatBytes(upper);

  return `~${estimatedStr} (${lowerStr}–${upperStr})`;
}
