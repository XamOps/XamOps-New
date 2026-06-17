/**
 * Time format utility functions for MediaOps transcoding.
 * Handles HH:MM:SS ↔ seconds conversion and time input validation.
 */

/**
 * Converts total seconds to "HH:MM:SS" format string.
 * @param seconds Total seconds (non-negative integer or float)
 * @returns Formatted time string in HH:MM:SS
 */
export function secondsToHHMMSS(seconds: number): string {
  if (!Number.isFinite(seconds) || seconds < 0) return '00:00:00';
  const totalSec = Math.round(seconds);
  const h = Math.floor(totalSec / 3600);
  const m = Math.floor((totalSec % 3600) / 60);
  const s = totalSec % 60;
  return `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`;
}

/**
 * Parses "HH:MM:SS" or a numeric seconds string to a total number of seconds.
 * @param time A time string in "HH:MM:SS", "MM:SS", or a numeric string representing seconds
 * @returns Total seconds as a number, or 0 if parsing fails
 */
export function hhmmssToSeconds(time: string): number {
  if (!time || typeof time !== 'string') return 0;
  const trimmed = time.trim();

  // If it's a plain number (possibly decimal), return as seconds
  if (/^\d+(\.\d+)?$/.test(trimmed)) {
    return Math.round(parseFloat(trimmed));
  }

  // Parse colon-separated format
  const parts = trimmed.split(':');
  if (parts.length === 3) {
    const h = parseInt(parts[0], 10) || 0;
    const m = parseInt(parts[1], 10) || 0;
    const s = parseInt(parts[2], 10) || 0;
    return h * 3600 + m * 60 + s;
  }
  if (parts.length === 2) {
    const m = parseInt(parts[0], 10) || 0;
    const s = parseInt(parts[1], 10) || 0;
    return m * 60 + s;
  }

  return 0;
}

/**
 * Validates and formats a time input string.
 * Strips non-numeric/colon characters, enforces HH:MM:SS structure.
 * @param value Raw input value from a text field
 * @returns A cleaned/formatted time string
 */
export function formatTimeInput(value: string): string {
  if (!value) return '';

  // Strip everything except digits and colons
  const cleaned = value.replace(/[^\d:]/g, '');

  // If it's just digits, auto-insert colons for common patterns
  const digitsOnly = cleaned.replace(/:/g, '');
  if (digitsOnly.length <= 6 && !cleaned.includes(':')) {
    // Auto-format: 1 → 1, 12 → 12, 123 → 1:23, 1234 → 12:34, 12345 → 1:23:45, 123456 → 12:34:56
    if (digitsOnly.length <= 2) return digitsOnly;
    if (digitsOnly.length <= 4) {
      const m = digitsOnly.slice(0, -2);
      const s = digitsOnly.slice(-2);
      return `${m}:${s}`;
    }
    const h = digitsOnly.slice(0, -4);
    const m = digitsOnly.slice(-4, -2);
    const s = digitsOnly.slice(-2);
    return `${h}:${m}:${s}`;
  }

  // Validate existing colon-separated format
  const parts = cleaned.split(':');
  if (parts.length === 2) {
    const m = parts[0].slice(0, 2);
    const s = parts[1].slice(0, 2);
    return `${m}:${s}`;
  }
  if (parts.length >= 3) {
    const h = parts[0].slice(0, 2);
    const m = parts[1].slice(0, 2);
    const s = parts[2].slice(0, 2);
    return `${h}:${m}:${s}`;
  }

  return cleaned;
}
