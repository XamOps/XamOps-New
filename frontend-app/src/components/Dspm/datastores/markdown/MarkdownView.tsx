import React, { useMemo } from 'react';
import { marked, type MarkedToken, type Token, type Tokens } from 'marked';
import { CodeBlock } from './CodeBlock';

/*
 * Markdown from Matters (misconfiguration descriptions and remediations), rendered without any inner
 * HTML (design 4.9 and 6.10). `marked.lexer` turns the text into tokens and each token becomes a React
 * element, so every string reaches the page as React text and cannot create markup:
 * - raw HTML (`<script>`, `<img onerror>`, `<bucket_name>`) shows as the text it is;
 * - a link is a link only for an absolute http or https URL, and opens in a new tab;
 * - an image shows its alt text and is never loaded.
 */

const NAMED_ENTITIES: Readonly<Record<string, string>> = { amp: '&', lt: '<', gt: '>', quot: '"' };
const ENTITY = /&(?:(amp|lt|gt|quot)|#(\d+)|#[xX]([0-9a-fA-F]+));/g;

/**
 * marked 17 leaves entities in `text` tokens as written (`escaped: false`), so prose decodes them here:
 * `&amp; &lt; &gt; &quot;`, decimal `&#N;` (which covers `&#39;`) and hex `&#xN;`. A numeric reference
 * outside 1..0x10FFFF or in the surrogate range stays literal, so `String.fromCodePoint` cannot throw,
 * and so do named entities beyond these four (`&copy;`). React escapes the result.
 */
function decodeEntities(text: string): string {
  return text.replace(ENTITY, (match, name: string | undefined, decimal: string | undefined, hex: string | undefined) => {
    if (name !== undefined) return NAMED_ENTITIES[name];
    const code = decimal !== undefined ? Number.parseInt(decimal, 10) : Number.parseInt(hex ?? '', 16);
    const valid = code >= 1 && code <= 0x10ffff && !(code >= 0xd800 && code <= 0xdfff);
    return valid ? String.fromCodePoint(code) : match;
  });
}

/** The URL a link may point to: an absolute http or https URL, else null (relative, `#anchor`, `javascript:`). */
function webHref(href: string): string | null {
  try {
    const url = new URL(href);
    return url.protocol === 'http:' || url.protocol === 'https:' ? url.href : null;
  } catch {
    return null;   // no base to resolve against: relative paths, anchors and malformed hrefs
  }
}

function renderTokens(tokens: Token[] | undefined, path: string): React.ReactNode[] {
  return (tokens ?? []).map((token, index) => renderToken(token, `${path}.${index}`));
}

function renderToken(token: Token, key: string): React.ReactNode {
  const known = token as MarkedToken;
  switch (known.type) {
    case 'heading':
      return known.depth <= 2
        ? <h4 key={key} className="text-xs font-bold text-[rgba(0,0,0,0.85)]">{renderTokens(known.tokens, key)}</h4>
        : <h5 key={key} className="text-[11px] font-bold text-[rgba(0,0,0,0.8)]">{renderTokens(known.tokens, key)}</h5>;
    case 'paragraph':
      return <p key={key}>{renderTokens(known.tokens, key)}</p>;
    case 'text':
      // A block-level text token (a tight list item) carries its inline tokens; a leaf carries the text.
      return (
        <React.Fragment key={key}>
          {known.tokens ? renderTokens(known.tokens, key) : decodeEntities(known.text)}
        </React.Fragment>
      );
    case 'list': {
      const items = known.items.map((item, index) => <li key={index}>{renderTokens(item.tokens, `${key}.${index}`)}</li>);
      return known.ordered
        ? <ol key={key} start={typeof known.start === 'number' ? known.start : undefined} className="list-decimal space-y-1 pl-5">{items}</ol>
        : <ul key={key} className="list-disc space-y-1 pl-5">{items}</ul>;
    }
    case 'list_item':
      return <li key={key}>{renderTokens(known.tokens, key)}</li>;
    case 'blockquote':
      return (
        <blockquote key={key} className="space-y-2 border-l-2 border-[rgba(0,0,0,0.12)] pl-3 text-[rgba(0,0,0,0.6)]">
          {renderTokens(known.tokens, key)}
        </blockquote>
      );
    case 'hr':
      return <hr key={key} className="border-[rgba(0,0,0,0.1)]" />;
    case 'space':
    case 'def':        // reference definitions: their links already carry the resolved href
    case 'checkbox':   // task-list markers
      return null;
    case 'table':
      return (
        <div key={key} className="overflow-x-auto">
          <table className="min-w-full text-left text-[11px]">
            <thead>
              <tr className="border-b border-[rgba(0,0,0,0.1)]">
                {known.header.map((cell, index) => (
                  <th key={index} scope="col" className="px-2 py-1 font-semibold">{renderTokens(cell.tokens, `${key}.h${index}`)}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {known.rows.map((row, rowIndex) => (
                <tr key={rowIndex} className="border-b border-[rgba(0,0,0,0.05)] last:border-b-0">
                  {row.map((cell, index) => (
                    <td key={index} className="px-2 py-1">{renderTokens(cell.tokens, `${key}.${rowIndex}.${index}`)}</td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      );
    case 'code':
      // Code keeps entities literal, as CommonMark does.
      return <CodeBlock key={key} language={known.lang || 'Text'} code={known.text} />;
    case 'codespan':
      return <code key={key} className="rounded bg-[rgba(0,0,0,0.05)] px-1 font-mono text-[11px]">{known.text}</code>;
    case 'strong':
      return <strong key={key} className="font-semibold">{renderTokens(known.tokens, key)}</strong>;
    case 'em':
      return <em key={key}>{renderTokens(known.tokens, key)}</em>;
    case 'del':
      return <del key={key}>{renderTokens(known.tokens, key)}</del>;
    case 'br':
      return <br key={key} />;
    case 'escape':
      return <React.Fragment key={key}>{known.text}</React.Fragment>;   // already the bare character
    case 'link': {
      const href = webHref(known.href);
      const children = renderTokens(known.tokens, key);
      return href === null
        ? <React.Fragment key={key}>{children}</React.Fragment>
        : (
          <a key={key} href={href} target="_blank" rel="noopener noreferrer" className="text-sky-700 underline hover:text-sky-900">
            {children}
          </a>
        );
    }
    case 'image':
      // The alt text, decoded like any prose; the image is never loaded.
      return <React.Fragment key={key}>{decodeEntities(known.text)}</React.Fragment>;
    case 'html':
      return known.block
        ? <p key={key} className="whitespace-pre-wrap">{known.raw.trimEnd()}</p>
        : <React.Fragment key={key}>{known.raw}</React.Fragment>;
    default: {
      // Token types marked may add later, or extensions: their text when it is a string, else nothing.
      const text = (token as Tokens.Generic).text;
      return typeof text === 'string' ? <React.Fragment key={key}>{text}</React.Fragment> : null;
    }
  }
}

/** A markdown text from Matters as React elements; nothing for an empty or null text. */
export const MarkdownView: React.FC<{ markdown: string | null }> = ({ markdown }) => {
  const tokens = useMemo(() => (markdown ? marked.lexer(markdown, { gfm: true }) : []), [markdown]);
  if (tokens.length === 0) return null;
  return (
    <div className="space-y-2 break-words text-xs leading-relaxed text-[rgba(0,0,0,0.75)]">
      {renderTokens(tokens, 'md')}
    </div>
  );
};
