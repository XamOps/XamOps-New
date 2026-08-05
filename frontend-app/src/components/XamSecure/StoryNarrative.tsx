import React from 'react';
import { Icon } from '@iconify/react';

/**
 * Renders Upwind's threat-story narrative.
 *
 * The `description` field is markdown-ish but carries Upwind's own inline markup, verified
 * against a live story on 2026-08-04:
 *   [br]                                                  -> paragraph break
 *   [header_with_icon headerText="..." icon="..."]         -> section heading
 *   [link text="T1105" href="https://docs.upwind.io/..."]  -> external link (MITRE technique)
 * alongside standard `# Heading`, `**bold**` and `` `code` ``.
 *
 * Everything is parsed into React elements — no dangerouslySetInnerHTML anywhere. The text
 * comes from a third party and describes attacker-controlled strings (commands, filenames,
 * URLs), so injecting it as HTML would be handing an attacker a stored-XSS vector via their
 * own shell commands. Unknown [tags] are dropped rather than shown raw.
 */

/** Upwind icon names -> iconify, with a neutral fallback for ones we haven't seen. */
const ICONS: Record<string, string> = {
  magnifying_glass: 'mdi:magnify',
  code: 'mdi:code-braces',
  globe_internet_exposure: 'mdi:web',
  sensitive_data: 'mdi:shield-account-outline',
  lightbulb: 'mdi:lightbulb-on-outline',
  warning: 'mdi:alert-outline',
  network: 'mdi:lan',
  key: 'mdi:key-variant',
};
const icon = (name?: string) => (name && ICONS[name]) || 'mdi:chevron-right-circle-outline';

/** Pulls attr="value" pairs out of an Upwind tag body. */
const attrs = (raw: string): Record<string, string> => {
  const out: Record<string, string> = {};
  for (const m of raw.matchAll(/(\w+)\s*=\s*"([^"]*)"/g)) out[m[1]] = m[2];
  return out;
};

/**
 * Inline pass: `**bold**`, `` `code` ``, and [link ...]. Split on the markers rather than
 * regex-replacing into HTML, so the output stays a React node tree.
 */
const renderInline = (text: string, keyBase: string): React.ReactNode[] => {
  const nodes: React.ReactNode[] = [];
  // One pattern for all three inline forms; order matters only for overlapping matches.
  const pattern = /\[link\s+([^\]]*)\]|\*\*([^*]+)\*\*|`([^`]+)`/g;
  let last = 0;
  let m: RegExpExecArray | null;
  let i = 0;
  while ((m = pattern.exec(text)) !== null) {
    if (m.index > last) nodes.push(text.slice(last, m.index));
    const key = `${keyBase}-i${i++}`;
    if (m[1] !== undefined) {
      const a = attrs(m[1]);
      const href = a.href ?? '';
      const label = a.text ?? href;
      // Only http(s) — never javascript:/data: from third-party text.
      const safe = /^https?:\/\//i.test(href);
      nodes.push(safe ? (
        <a key={key} href={href} target="_blank" rel="noopener noreferrer"
           className="text-blue-600 hover:underline font-medium">{label}</a>
      ) : <span key={key}>{label}</span>);
    } else if (m[2] !== undefined) {
      nodes.push(<strong key={key} className="font-semibold text-gray-900">{m[2]}</strong>);
    } else if (m[3] !== undefined) {
      nodes.push(
        <code key={key} className="px-1 py-0.5 rounded bg-gray-100 text-[11px] font-mono
                                   text-gray-800 break-all">{m[3]}</code>
      );
    }
    last = m.index + m[0].length;
  }
  if (last < text.length) nodes.push(text.slice(last));
  return nodes;
};

const StoryNarrative: React.FC<{ markdown: string | null }> = ({ markdown }) => {
  if (!markdown || !markdown.trim()) {
    return (
      <p className="text-xs text-gray-400">
        Upwind has not published a narrative for this story.
      </p>
    );
  }

  // [br] is a spacer, not content — treat it as a block separator so it does not end up
  // rendered literally.
  const blocks = markdown
    .replace(/\[br\]/g, '\n\n')
    .split(/\n{2,}/)
    .map((b) => b.trim())
    .filter(Boolean);

  return (
    <div className="space-y-3">
      {blocks.map((block, bi) => {
        // Section heading with an icon, optionally trailed by MITRE technique links.
        const hdr = block.match(/^\[header_with_icon\s+([^\]]*)\]\s*([\s\S]*)$/);
        if (hdr) {
          const a = attrs(hdr[1]);
          const rest = hdr[2].trim();
          return (
            <div key={bi} className="pt-2">
              <p className="flex items-center gap-1.5 text-xs font-bold text-gray-900">
                <Icon icon={icon(a.icon)} width={14} className="flex-shrink-0 text-gray-500" />
                {a.headerText ?? 'Section'}
              </p>
              {rest && (
                <p className="mt-1 flex flex-wrap gap-x-1.5 gap-y-1 text-[11px] leading-relaxed">
                  {renderInline(rest, `h${bi}`)}
                </p>
              )}
            </div>
          );
        }

        // Markdown headings — the narrative uses "# Incident Summary" etc.
        const md = block.match(/^(#{1,4})\s+(.*)$/);
        if (md) {
          return (
            <p key={bi} className="pt-2 text-xs font-bold text-gray-900 uppercase tracking-wide">
              {md[2]}
            </p>
          );
        }

        // Drop any other Upwind tag we don't understand rather than showing it raw.
        const cleaned = block.replace(/\[\/?[a-z_]+[^\]]*\]/gi, '').trim();
        if (!cleaned) return null;
        return (
          <p key={bi} className="text-[11px] leading-relaxed text-gray-700">
            {renderInline(cleaned, `b${bi}`)}
          </p>
        );
      })}
    </div>
  );
};

export default StoryNarrative;
