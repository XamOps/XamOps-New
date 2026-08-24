import { describe, it, expect } from 'vitest';
import { sanitizeRichText, sanitizeInlineHtml, htmlToPlainText, escapeHtml } from './sanitizeHtml';

describe('sanitizeHtml', () => {
  describe('sanitizeRichText', () => {
    it('keeps the formatting the ticket editor produces', () => {
      const html =
        '<p><strong>bold</strong> <em>italic</em> <u>underline</u> <s>strike</s></p>' +
        '<blockquote>quoted</blockquote><pre class="ql-syntax">code()</pre>' +
        '<ol><li>one</li></ol><ul><li>two</li></ul>';
      expect(sanitizeRichText(html)).toBe(html);
    });

    it('keeps pasted inline images, including data URIs', () => {
      const out = sanitizeRichText('<img src="data:image/png;base64,iVBORw0KGgo=" alt="shot">');
      expect(out).toContain('src="data:image/png;base64,iVBORw0KGgo="');
      expect(out).toContain('alt="shot"');
    });

    it('drops script elements', () => {
      expect(sanitizeRichText('<p>hi</p><script>alert(1)</script>')).toBe('<p>hi</p>');
    });

    it('drops event handler attributes', () => {
      const out = sanitizeRichText('<img src="x" onerror="alert(1)">');
      expect(out).not.toContain('onerror');
      expect(out).not.toContain('alert');
    });

    it('drops javascript: URLs but keeps the link text', () => {
      const out = sanitizeRichText('<a href="javascript:alert(1)">click</a>');
      expect(out).not.toContain('javascript:');
      expect(out).toContain('click');
    });

    it('forces links to open without leaking the referrer', () => {
      const out = sanitizeRichText('<a href="https://example.com">x</a>');
      expect(out).toContain('rel="noopener noreferrer"');
      expect(out).toContain('target="_blank"');
    });

    it('drops iframes, forms, svg and inline styles', () => {
      expect(sanitizeRichText('<iframe src="https://evil.test"></iframe>')).toBe('');
      expect(sanitizeRichText('<form action="/x"><input name="y"></form>')).toBe('');
      expect(sanitizeRichText('<svg><script>alert(1)</script></svg>')).not.toContain('script');
      expect(sanitizeRichText('<p style="position:fixed">x</p>')).toBe('<p>x</p>');
    });

    it('returns an empty string for empty input', () => {
      expect(sanitizeRichText('')).toBe('');
      expect(sanitizeRichText(null)).toBe('');
      expect(sanitizeRichText(undefined)).toBe('');
    });
  });

  describe('sanitizeInlineHtml', () => {
    it('keeps inline emphasis and line breaks', () => {
      expect(sanitizeInlineHtml('<b>bold</b><br/>next')).toBe('<b>bold</b><br>next');
    });

    it('strips links and images but keeps their text', () => {
      expect(sanitizeInlineHtml('<a href="https://example.com">text</a>')).toBe('text');
      expect(sanitizeInlineHtml('<img src="x" onerror="alert(1)">')).toBe('');
    });

    it('drops scripts', () => {
      expect(sanitizeInlineHtml('ok<script>alert(1)</script>')).toBe('ok');
    });
  });

  describe('htmlToPlainText', () => {
    it('returns the text content without markup', () => {
      expect(htmlToPlainText('<p>hello <strong>world</strong></p>')).toBe('hello world');
    });

    it('does not carry markup through from an injection attempt', () => {
      const text = htmlToPlainText('<img src=x onerror=alert(1)>caption');
      expect(text).toBe('caption');
      expect(text).not.toContain('alert');
    });

    it('handles empty input', () => {
      expect(htmlToPlainText('')).toBe('');
      expect(htmlToPlainText(null)).toBe('');
    });
  });

  describe('escapeHtml', () => {
    it('escapes the characters that break out of text and attribute context', () => {
      expect(escapeHtml('<img src=x onerror="alert(1)">')).toBe(
        '&lt;img src=x onerror=&quot;alert(1)&quot;&gt;'
      );
      expect(escapeHtml("it's & so")).toBe('it&#39;s &amp; so');
    });

    it('stringifies non-string input and blanks out null/undefined', () => {
      expect(escapeHtml(42)).toBe('42');
      expect(escapeHtml(null)).toBe('');
      expect(escapeHtml(undefined)).toBe('');
    });
  });
});
