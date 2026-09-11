/**
 * Minimal HCL tokeniser for read-only display.
 *
 * WHY NOT A LIBRARY
 * The app ships no code editor, and the options are heavy for the job: Monaco is
 * megabytes and exists to *edit*, while generated Terraform here is deliberately
 * one-way — the canvas is the source of truth, the HCL is an artifact. Read-only
 * display needs tokens and colours, not an editor, a language server, or a
 * worker. Prism and highlight.js would also both need a network install, which
 * this environment cannot reliably do.
 *
 * Generated HCL is also a far narrower language than hand-written HCL: blocks,
 * `key = value`, scalars, lists, maps, and unquoted references. That is small
 * enough to tokenise honestly in ~100 lines.
 *
 * If editing is ever wanted, this gets replaced by a real editor rather than
 * grown — a half-editor is worse than either end of that choice.
 */

export type HclTokenKind =
  | 'plain'
  | 'comment'
  | 'blockType'   // resource, variable, output, provider, terraform
  | 'label'       // the "aws_vpc" / "main" strings after a block type
  | 'attribute'   // key on the left of =
  | 'string'
  | 'number'
  | 'boolean'
  | 'reference'   // aws_vpc.main.id, var.region — the whole point of the output
  | 'punctuation';

export interface HclToken {
  kind: HclTokenKind;
  text: string;
}

/** Block keywords the emitter can produce. */
const BLOCK_TYPES = new Set([
  'resource', 'variable', 'output', 'provider', 'terraform', 'data',
  'module', 'locals', 'required_providers',
]);

/**
 * Nested block names that appear bare before a brace. Listed so they are not
 * mistaken for attributes, which are followed by `=`.
 */
const NESTED_BLOCKS = new Set(['ingress', 'egress', 'tags', 'filter', 'root_block_device', 'metadata_options']);

const IDENTIFIER = /^[A-Za-z_][A-Za-z0-9_-]*/;
const NUMBER = /^-?\d+(\.\d+)?/;

/**
 * Tokenise one line.
 *
 * Line-scoped on purpose: generated HCL has no multi-line strings or block
 * comments, so there is no state to carry between lines, and per-line output maps
 * directly onto rendering rows.
 */
export function tokenizeHclLine(line: string): HclToken[] {
  const tokens: HclToken[] = [];
  let i = 0;

  const push = (kind: HclTokenKind, text: string) => {
    if (!text) return;
    // Merge runs of plain text so the DOM stays small on large files.
    const last = tokens[tokens.length - 1];
    if (last && last.kind === kind && kind === 'plain') last.text += text;
    else tokens.push({ kind, text });
  };

  // Leading whitespace, kept verbatim so indentation survives.
  const indent = line.match(/^\s*/)?.[0] ?? '';
  if (indent) push('plain', indent);
  i = indent.length;

  if (line.slice(i).startsWith('#') || line.slice(i).startsWith('//')) {
    push('comment', line.slice(i));
    return tokens;
  }

  let atLineStart = true;

  while (i < line.length) {
    const rest = line.slice(i);
    const ch = rest[0];

    // Strings, with escapes respected so a quote inside a value cannot end it early.
    if (ch === '"') {
      let j = 1;
      while (j < rest.length) {
        if (rest[j] === '\\') { j += 2; continue; }
        if (rest[j] === '"') { j++; break; }
        j++;
      }
      push('string', rest.slice(0, j));
      i += j;
      atLineStart = false;
      continue;
    }

    if (/\s/.test(ch)) {
      push('plain', ch);
      i += 1;
      continue;
    }

    if (/[{}[\],=]/.test(ch)) {
      push('punctuation', ch);
      i += 1;
      if (ch === '=') atLineStart = false;
      continue;
    }

    const numberMatch = rest.match(NUMBER);
    if (numberMatch && !/[A-Za-z_]/.test(line[i - 1] ?? '')) {
      push('number', numberMatch[0]);
      i += numberMatch[0].length;
      continue;
    }

    const idMatch = rest.match(IDENTIFIER);
    if (idMatch) {
      const word = idMatch[0];
      const after = rest.slice(word.length);
      const nextNonSpace = after.replace(/^\s*/, '')[0];

      if (word === 'true' || word === 'false' || word === 'null') {
        push('boolean', word);
      } else if (atLineStart && BLOCK_TYPES.has(word)) {
        push('blockType', word);
      } else if (nextNonSpace === '.') {
        // A dotted path: aws_vpc.main.id, var.region. Consume the whole chain so
        // it reads as one reference rather than several identifiers.
        const chain = rest.match(/^[A-Za-z_][A-Za-z0-9_-]*(\.[A-Za-z_*][A-Za-z0-9_-]*|\[\d+\])+/);
        const text = chain ? chain[0] : word;
        push('reference', text);
        i += text.length;
        atLineStart = false;
        continue;
      } else if (nextNonSpace === '=') {
        push('attribute', word);
      } else if (atLineStart && (nextNonSpace === '{' || NESTED_BLOCKS.has(word))) {
        push('blockType', word);
      } else if (!atLineStart) {
        // A bare word in value position, e.g. `type = string`.
        push('reference', word);
      } else {
        push('plain', word);
      }

      i += word.length;
      atLineStart = false;
      continue;
    }

    push('plain', ch);
    i += 1;
  }

  return tokens;
}

/**
 * A block label is a quoted string directly after a block keyword. Promoting it
 * from `string` makes `resource "aws_vpc" "main"` scannable, which is how people
 * actually navigate a Terraform file.
 */
export function tokenizeHcl(source: string): HclToken[][] {
  return source.split('\n').map((line) => {
    const tokens = tokenizeHclLine(line);
    const blockIndex = tokens.findIndex((t) => t.kind === 'blockType');
    if (blockIndex === -1) return tokens;
    for (let k = blockIndex + 1; k < tokens.length; k++) {
      const token = tokens[k];
      if (token.kind === 'string') token.kind = 'label';
      else if (token.kind === 'punctuation' && token.text === '{') break;
      else if (token.kind !== 'plain') break;
    }
    return tokens;
  });
}

/** Token colours per theme, tuned for contrast on the canvas surfaces. */
export const HCL_COLORS: Record<'light' | 'dark', Record<HclTokenKind, string>> = {
  light: {
    plain: '#0f172a',
    comment: '#7c8798',
    blockType: '#a626a4',
    label: '#0a7ea4',
    attribute: '#0f172a',
    string: '#0a7d3f',
    number: '#b4661e',
    boolean: '#b4661e',
    reference: '#1a56c4',
    punctuation: '#64748b',
  },
  dark: {
    plain: '#e9eef8',
    comment: '#6b7c99',
    blockType: '#d98ae6',
    label: '#5ecfe8',
    attribute: '#c8d4e8',
    string: '#7fdba0',
    number: '#f0b072',
    boolean: '#f0b072',
    reference: '#8ab4ff',
    punctuation: '#7d8aa3',
  },
};
