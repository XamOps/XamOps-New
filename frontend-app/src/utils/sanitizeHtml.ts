import DOMPurify from 'dompurify';

// Links in ticket replies may legitimately carry target="_blank", which is not
// in DOMPurify's default attribute allowlist. Keep it, but force a safe rel so
// the opened page can never reach back through window.opener.
DOMPurify.addHook('afterSanitizeAttributes', (node) => {
  if (node instanceof Element && node.hasAttribute('target')) {
    node.setAttribute('rel', 'noopener noreferrer');
  }
});

/**
 * Sanitizes rich-text HTML (ticket replies, diagram tooltips, etc.) before it is
 * injected via dangerouslySetInnerHTML. Strips scripts, event handlers, and
 * javascript: URLs while keeping normal formatting markup intact.
 *
 * Form controls are forbidden outright: they survive the default html profile
 * and would let an authored reply render a credential-phishing form that posts
 * to an external endpoint.
 */
export function sanitizeHtml(html: string): string {
  return DOMPurify.sanitize(html, {
    USE_PROFILES: { html: true },
    ADD_ATTR: ['target'],
    FORBID_TAGS: ['form', 'input', 'button', 'select', 'textarea', 'option'],
  });
}
