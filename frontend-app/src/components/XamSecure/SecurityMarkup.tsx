import React from 'react';

/**
 * Renders Upwind's remediation / finding-details markup cleanly. Upwind mixes:
 *  - fenced code blocks ```lang ... ```
 *  - custom bracket tags: [step_card title="…" stepNumber=N] … [/step_card],
 *    [switch_view_button title="GUI" …], [view …] … [/view]
 *  - light markdown: ### headings, **bold**, [text](url) links, - lists
 * Instead of dumping the raw text, we parse it into readable blocks.
 */

const attr = (attrs: string, name: string): string | null => {
  const q = attrs.match(new RegExp(`${name}="([^"]*)"`));
  if (q) return q[1];
  const u = attrs.match(new RegExp(`${name}=([^\\s\\]]+)`));
  return u ? u[1] : null;
};

/** Inline markdown: **bold** and [text](url). */
const renderInline = (text: string): React.ReactNode[] => {
  const nodes: React.ReactNode[] = [];
  const regex = /\*\*(.+?)\*\*|\[([^\]]+)\]\((https?:\/\/[^)]+)\)/g;
  let last = 0;
  let m: RegExpExecArray | null;
  let k = 0;
  while ((m = regex.exec(text)) !== null) {
    if (m.index > last) nodes.push(text.slice(last, m.index));
    if (m[1] !== undefined) {
      nodes.push(<strong key={k++} className="font-semibold text-gray-900">{m[1]}</strong>);
    } else {
      nodes.push(
        <a key={k++} href={m[3]} target="_blank" rel="noopener noreferrer"
           className="text-blue-600 hover:underline break-all">{m[2]}</a>);
    }
    last = regex.lastIndex;
  }
  if (last < text.length) nodes.push(text.slice(last));
  return nodes;
};

interface Block {
  type: 'code' | 'text';
  content: string;
  lang?: string;
}

/** Split into code-fence blocks and text blocks; normalize Upwind bracket tags in text. */
const parseBlocks = (raw: string): Block[] => {
  const blocks: Block[] = [];
  const fence = /```(\w*)\n?([\s\S]*?)```/g;
  let last = 0;
  let m: RegExpExecArray | null;
  const pushText = (t: string) => {
    if (t.trim()) blocks.push({ type: 'text', content: normalizeTags(t) });
  };
  while ((m = fence.exec(raw)) !== null) {
    if (m.index > last) pushText(raw.slice(last, m.index));
    blocks.push({ type: 'code', content: m[2].replace(/\s+$/, ''), lang: m[1] || undefined });
    last = fence.lastIndex;
  }
  if (last < raw.length) pushText(raw.slice(last));
  return blocks;
};

/** Convert Upwind bracket tags to line markers; strip the rest. */
const normalizeTags = (text: string): string =>
  text
    .replace(/\[step_card([^\]]*)\]/g, (_x, a: string) => {
      const n = attr(a, 'stepNumber');
      const t = attr(a, 'title');
      return `\n@@STEP@@${n ? `${n}. ` : ''}${t ?? ''}\n`;
    })
    .replace(/\[switch_view_button([^\]]*)\]/g, (_x, a: string) => `\n@@VIEW@@${attr(a, 'title') ?? ''}\n`)
    .replace(/\[\/?(step_card|view|switch_view_button)[^\]]*\]/g, '')
    .replace(/\[[^\]]*\]/g, '') // any remaining bracket tags
    .replace(/\n{3,}/g, '\n\n')
    .trim();

const TextBlock: React.FC<{ content: string }> = ({ content }) => {
  const lines = content.split('\n');
  const out: React.ReactNode[] = [];
  let list: string[] = [];
  const flushList = (key: string) => {
    if (list.length) {
      out.push(
        <ul key={key} className="list-disc pl-5 space-y-0.5">
          {list.map((li, i) => <li key={i}>{renderInline(li)}</li>)}
        </ul>);
      list = [];
    }
  };
  lines.forEach((raw, i) => {
    const line = raw.trimEnd();
    if (!line.trim()) { flushList(`l${i}`); return; }
    if (line.startsWith('@@STEP@@')) {
      flushList(`l${i}`);
      out.push(
        <p key={i} className="mt-2 text-xs font-semibold text-gray-900">{line.replace('@@STEP@@', '')}</p>);
    } else if (line.startsWith('@@VIEW@@')) {
      flushList(`l${i}`);
      out.push(
        <span key={i} className="inline-block mt-1 mb-0.5 px-2 py-0.5 rounded bg-gray-100 text-[10px] font-semibold text-gray-600 uppercase tracking-wide">
          {line.replace('@@VIEW@@', '')}
        </span>);
    } else if (/^#{1,6}\s/.test(line)) {
      flushList(`l${i}`);
      out.push(<p key={i} className="mt-2 text-xs font-bold text-gray-900">{renderInline(line.replace(/^#{1,6}\s/, ''))}</p>);
    } else if (/^[-*]\s+/.test(line)) {
      list.push(line.replace(/^[-*]\s+/, ''));
    } else {
      flushList(`l${i}`);
      out.push(<p key={i}>{renderInline(line)}</p>);
    }
  });
  flushList('last');
  return <div className="space-y-1.5 break-words">{out}</div>;
};

const SecurityMarkup: React.FC<{ text: string | null | undefined; fallback?: string }> = ({ text, fallback }) => {
  if (!text || !text.trim()) {
    return <p className="text-xs text-gray-400">{fallback ?? 'None provided.'}</p>;
  }
  const blocks = parseBlocks(text);
  return (
    <div className="text-xs leading-relaxed text-gray-700 space-y-2 min-w-0 break-words">
      {blocks.map((b, i) =>
        b.type === 'code' ? (
          <pre key={i} className="bg-gray-900 text-gray-100 rounded-lg p-3 max-w-full overflow-x-auto whitespace-pre-wrap break-words text-[11px] leading-relaxed">
            <code>{b.content}</code>
          </pre>
        ) : (
          <TextBlock key={i} content={b.content} />
        ))}
    </div>
  );
};

export default SecurityMarkup;
