/**
 * Central HTML sanitization helpers.
 *
 * Any value that reaches `dangerouslySetInnerHTML` or `element.innerHTML` must
 * pass through one of these functions first. Rich text authored by users
 * (ticket replies, internal notes, CloudMap node tooltips) is stored as raw
 * HTML, so the render path is the only place we can guarantee it is inert.
 *
 * Pick the narrowest helper that still renders correctly:
 *   - `sanitizeRichText`  — full formatting: the Quill editor output surface.
 *   - `sanitizeInlineHtml` — inline emphasis + line breaks only, no links/media.
 *   - `htmlToPlainText`   — strip all markup, keep the text.
 *   - `escapeHtml`        — interpolate untrusted text into an HTML string.
 */
import DOMPurify from 'dompurify';

/**
 * Tags the ticket editor can produce (bold/italic/underline/strike, blockquote,
 * code-block, ordered + bullet lists) plus what users commonly paste: links,
 * inline images, headings and simple tables.
 */
const RICH_TEXT_TAGS = [
  'a', 'b', 'blockquote', 'br', 'code', 'div', 'em', 'figure', 'figcaption',
  'h1', 'h2', 'h3', 'h4', 'h5', 'h6', 'hr', 'i', 'img', 'li', 'ol', 'p', 'pre',
  's', 'small', 'span', 'strike', 'strong', 'sub', 'sup',
  'table', 'tbody', 'td', 'tfoot', 'th', 'thead', 'tr', 'u', 'ul',
];

/**
 * `class` is required because Quill encodes indent level and code highlighting
 * as `ql-*` classes. `style` is deliberately absent — the editor does not emit
 * it, and allowing it widens the CSS-injection surface for no visible gain.
 */
const RICH_TEXT_ATTR = [
  'alt', 'class', 'colspan', 'data-list', 'dir', 'height', 'href', 'rel',
  'rowspan', 'span', 'src', 'start', 'target', 'title', 'type', 'width',
];

/** Tooltips and other one-line surfaces: emphasis and breaks, nothing loadable. */
const INLINE_TAGS = ['b', 'br', 'code', 'em', 'i', 'small', 'span', 'strong', 'sub', 'sup', 'u'];
const INLINE_ATTR = ['class'];

let hooksInstalled = false;

/**
 * Force every surviving link to open safely: no reverse tabnabbing, and no
 * referrer leak to whatever host a user pasted.
 */
function installHooks(): void {
  if (hooksInstalled) return;
  DOMPurify.addHook('afterSanitizeAttributes', (node) => {
    if (node instanceof Element && node.tagName === 'A' && node.hasAttribute('href')) {
      node.setAttribute('target', '_blank');
      node.setAttribute('rel', 'noopener noreferrer');
    }
  });
  hooksInstalled = true;
}

/** True when DOMPurify has a DOM to work with. */
function canSanitize(): boolean {
  return typeof window !== 'undefined' && typeof window.document !== 'undefined';
}

/**
 * Escape text for safe interpolation into an HTML string.
 *
 * Use this when building markup by hand (chart tooltips, imperative widgets)
 * instead of sanitizing the assembled string — escaping the holes keeps the
 * surrounding markup exactly as written.
 */
export function escapeHtml(value: unknown): string {
  if (value === null || value === undefined) return '';
  return String(value)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

/**
 * Sanitize user-authored rich text for rendering.
 *
 * Scripts, event handlers, `javascript:` URLs, iframes, forms and inline styles
 * are removed; formatting, links and inline images survive.
 */
export function sanitizeRichText(html: string | null | undefined): string {
  if (!html) return '';
  if (!canSanitize()) return escapeHtml(html);
  installHooks();
  const clean = DOMPurify.sanitize(html, {
    ALLOWED_TAGS: RICH_TEXT_TAGS,
    ALLOWED_ATTR: RICH_TEXT_ATTR,
    ALLOW_DATA_ATTR: false,
  });
  return trimEmptyEdgeBlocks(clean);
}

/**
 * Removes empty leading/trailing blocks the rich-text editor leaves behind — a trailing
 * `<p><br></p>` or `<p></p>` (and the same at the start). These carry no content but render as
 * blank lines, which makes a one-word chat bubble look needlessly tall. Only strips blocks that
 * are truly empty (nothing but whitespace or a single `<br>`); any text, image, or other element
 * stops the trim so real content is never touched.
 */
function trimEmptyEdgeBlocks(html: string): string {
  if (!html || !canSanitize()) return html;
  const doc = new DOMParser().parseFromString(html, 'text/html');
  const body = doc.body;
  if (!body) return html;

  const isEmptyBlock = (node: ChildNode): boolean => {
    if (node.nodeType === Node.TEXT_NODE) {
      return (node.textContent ?? '').trim() === '';
    }
    if (node.nodeType !== Node.ELEMENT_NODE) return false;
    const el = node as HTMLElement;
    if (!/^(P|DIV)$/.test(el.tagName)) return false;
    // Empty if there's no text and no meaningful children (only <br> / whitespace).
    if ((el.textContent ?? '').trim() !== '') return false;
    return !el.querySelector('img, a, table, ul, ol, pre, blockquote');
  };

  while (body.lastChild && isEmptyBlock(body.lastChild)) {
    body.removeChild(body.lastChild);
  }
  while (body.firstChild && isEmptyBlock(body.firstChild)) {
    body.removeChild(body.firstChild);
  }
  return body.innerHTML;
}

/**
 * Sanitize untrusted HTML down to inline emphasis and line breaks.
 * Anything that can load a resource or navigate is dropped entirely.
 */
export function sanitizeInlineHtml(html: string | null | undefined): string {
  if (!html) return '';
  if (!canSanitize()) return escapeHtml(html);
  installHooks();
  return DOMPurify.sanitize(html, {
    ALLOWED_TAGS: INLINE_TAGS,
    ALLOWED_ATTR: INLINE_ATTR,
    ALLOW_DATA_ATTR: false,
  });
}

/**
 * Extract the plain text from an HTML string.
 *
 * Parses through `DOMParser`, which builds an inert document: unlike
 * `document.createElement('div').innerHTML = html`, it will not fire
 * `<img onerror>` or fetch remote resources while reading the text back.
 */
export function htmlToPlainText(html: string | null | undefined): string {
  if (!html) return '';
  if (!canSanitize()) return String(html);
  const doc = new DOMParser().parseFromString(html, 'text/html');
  return doc.body?.textContent ?? '';
}
