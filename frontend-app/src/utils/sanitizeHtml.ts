import DOMPurify from 'dompurify';

/**
 * Sanitizes rich-text HTML (ticket replies, diagram tooltips, etc.) before it is
 * injected via dangerouslySetInnerHTML. Strips scripts, event handlers, and
 * javascript: URLs while keeping normal formatting markup intact.
 */
export function sanitizeHtml(html: string): string {
  return DOMPurify.sanitize(html, { USE_PROFILES: { html: true } });
}
