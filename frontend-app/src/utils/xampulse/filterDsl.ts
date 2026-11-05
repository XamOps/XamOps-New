/**
 * Client-side parser for the XamPulse log filter DSL. Same grammar as FilterDslParser.java; both are
 * exercised by the shared fixture dsl-cases.json. The parser only validates and normalises. The server
 * stays authoritative; this exists for instant inline errors and chips.
 */
export const DSL_MAX_LENGTH = 2000;
export const DSL_MAX_DEPTH = 8;
export const DSL_MAX_TERMS = 40;
export const DSL_MAX_VALUE_LENGTH = 256;

export const DSL_FIELDS = ['service', 'instance', 'severity', 'source', 'region', 'container', 'trace_id'] as const;

export class DslError extends Error {}

type TokType = 'LPAREN' | 'RPAREN' | 'WORD' | 'QUOTED' | 'FIELD' | 'AND' | 'OR' | 'NOT';
interface Tok { type: TokType; text: string }

type Ast =
  | { k: 'all' }
  | { k: 'text'; v: string }
  | { k: 'not'; c: Ast }
  | { k: 'and'; c: Ast[] }
  | { k: 'or'; c: Ast[] }
  | { k: 'field'; name: string; cmp: string; values: string[] };

const FIELD_NAME = /^[A-Za-z_][A-Za-z0-9_.-]*$/;
const ATTR_KEY = /^[A-Za-z0-9_.-]{1,64}$/;
const SEVERITIES = ['TRACE', 'DEBUG', 'INFO', 'WARN', 'ERROR', 'FATAL'];

const safe = (s: string): string => {
  const cleaned = s.replace(/[^A-Za-z0-9_.:*-]/g, '?');
  return cleaned.length > 40 ? `${cleaned.slice(0, 40)}...` : cleaned;
};
const isSpace = (c: string): boolean => /\s/.test(c);

function fieldCanonical(name: string): string | null {
  switch (name.toLowerCase()) {
    case 'service': return 'service';
    case 'instance': case 'instance_id': case 'host': return 'instance';
    case 'severity': case 'level': return 'severity';
    case 'source': return 'source';
    case 'region': return 'region';
    case 'container': return 'container';
    case 'trace_id': return 'trace_id';
    default: return null;
  }
}

function readQuoted(s: string, start: number): [string, number] {
  let out = '';
  let i = start + 1;
  while (i < s.length) {
    const c = s[i];
    if (c === '\\' && i + 1 < s.length) { out += s[i + 1]; i += 2; }
    else if (c === '"') return [out, i + 1];
    else { out += c; i += 1; }
  }
  throw new DslError('Unterminated quote in filter.');
}

function splitField(word: string, next: string, out: Tok[]): void {
  const colon = word.indexOf(':');
  if (colon <= 0 || !FIELD_NAME.test(word.slice(0, colon))) { out.push({ type: 'WORD', text: word }); return; }
  const name = word.slice(0, colon);
  const rest = word.slice(colon + 1);
  if (rest === '') {
    if (next === '"' || next === '(') out.push({ type: 'FIELD', text: name });
    else out.push({ type: 'WORD', text: word });
    return;
  }
  out.push({ type: 'FIELD', text: name });
  out.push({ type: 'WORD', text: rest });
}

function tokenize(s: string): Tok[] {
  const out: Tok[] = [];
  let i = 0;
  const n = s.length;
  while (i < n) {
    const c = s[i];
    if (isSpace(c)) i += 1;
    else if (c === '(') { out.push({ type: 'LPAREN', text: '(' }); i += 1; }
    else if (c === ')') { out.push({ type: 'RPAREN', text: ')' }); i += 1; }
    else if (c === '"') { const [text, end] = readQuoted(s, i); out.push({ type: 'QUOTED', text }); i = end; }
    else if (c === '-' && i + 1 < n && !isSpace(s[i + 1])) { out.push({ type: 'NOT', text: '-' }); i += 1; }
    else {
      const start = i;
      while (i < n) {
        const d = s[i];
        if (isSpace(d) || d === '(' || d === ')' || d === '"') break;
        i += 1;
      }
      const word = s.slice(start, i);
      if (word === 'AND') out.push({ type: 'AND', text: word });
      else if (word === 'OR') out.push({ type: 'OR', text: word });
      else if (word === 'NOT') out.push({ type: 'NOT', text: word });
      else splitField(word, i < n ? s[i] : ' ', out);
    }
  }
  return out;
}

class Parser {
  private pos = 0;
  private terms = 0;
  constructor(private readonly toks: Tok[]) {}

  done(): boolean { return this.pos >= this.toks.length; }
  rest(): Tok { return this.toks[this.pos]; }
  private peek(t: TokType): boolean { return this.pos < this.toks.length && this.toks[this.pos].type === t; }
  private checkDepth(d: number): void {
    if (d > DSL_MAX_DEPTH) throw new DslError(`Filter is nested too deeply (max ${DSL_MAX_DEPTH}).`);
  }
  private countTerm(): void {
    this.terms += 1;
    if (this.terms > DSL_MAX_TERMS) throw new DslError(`Too many terms in filter (max ${DSL_MAX_TERMS}).`);
  }
  private checkValue(v: string): string {
    if (v === '') throw new DslError('Empty value in filter.');
    if (v.length > DSL_MAX_VALUE_LENGTH) throw new DslError('A filter value is too long.');
    return v;
  }

  parseOr(depth: number): Ast {
    this.checkDepth(depth);
    const parts: Ast[] = [this.parseAnd(depth)];
    while (this.peek('OR')) { this.pos += 1; parts.push(this.parseAnd(depth)); }
    return parts.length === 1 ? parts[0] : { k: 'or', c: parts };
  }
  private parseAnd(depth: number): Ast {
    const parts: Ast[] = [this.parseUnary(depth)];
    while (this.pos < this.toks.length && !this.peek('OR') && !this.peek('RPAREN')) {
      if (this.peek('AND')) this.pos += 1;
      parts.push(this.parseUnary(depth));
    }
    return parts.length === 1 ? parts[0] : { k: 'and', c: parts };
  }
  private parseUnary(depth: number): Ast {
    this.checkDepth(depth);
    if (this.peek('NOT')) { this.pos += 1; return { k: 'not', c: this.parseUnary(depth + 1) }; }
    return this.parsePrimary(depth);
  }
  private parsePrimary(depth: number): Ast {
    if (this.pos >= this.toks.length) throw new DslError('The filter ends unexpectedly.');
    const t = this.toks[this.pos];
    switch (t.type) {
      case 'LPAREN': {
        this.pos += 1;
        const inner = this.parseOr(depth + 1);
        if (!this.peek('RPAREN')) throw new DslError("Missing ')' in filter.");
        this.pos += 1;
        return inner;
      }
      case 'FIELD':
        this.pos += 1;
        return this.parseFieldTerm(t.text);
      case 'WORD':
      case 'QUOTED':
        this.pos += 1;
        this.countTerm();
        return { k: 'text', v: this.checkValue(t.text) };
      default:
        throw new DslError(`Unexpected '${safe(t.text)}' in filter.`);
    }
  }
  private parseFieldTerm(rawName: string): Ast {
    this.countTerm();
    let name: string;
    if (rawName.toLowerCase().startsWith('attr.')) {
      const key = rawName.slice(5);
      if (!ATTR_KEY.test(key)) throw new DslError('Invalid attribute name in filter.');
      name = `attr.${key}`;
    } else {
      const canonical = fieldCanonical(rawName);
      if (!canonical) throw new DslError(`Unknown filter field '${safe(rawName)}'.`);
      name = canonical;
    }
    if (this.pos >= this.toks.length) throw new DslError(`Missing value for '${safe(rawName)}'.`);
    let values: string[] = [];
    let cmp = '=';
    const t = this.toks[this.pos];
    if (t.type === 'LPAREN') {
      this.pos += 1;
      for (;;) {
        if (this.pos >= this.toks.length) throw new DslError("Missing ')' in filter.");
        const v = this.toks[this.pos];
        if (v.type !== 'WORD' && v.type !== 'QUOTED') throw new DslError(`Expected a value in the list for '${safe(rawName)}'.`);
        values.push(this.checkValue(v.text));
        this.pos += 1;
        if (this.peek('OR')) this.pos += 1; else break;
      }
      if (!this.peek('RPAREN')) throw new DslError("Missing ')' in filter.");
      this.pos += 1;
      if (values.length > 20) throw new DslError('Too many values in list.');
    } else if (t.type === 'WORD' || t.type === 'QUOTED') {
      this.pos += 1;
      let v = t.text;
      if (t.type === 'WORD') {
        for (const op of ['>=', '<=', '>', '<']) {
          if (v.startsWith(op)) { cmp = op; v = v.slice(op.length); break; }
        }
      }
      values.push(this.checkValue(v));
    } else {
      throw new DslError(`Missing value for '${safe(rawName)}'.`);
    }
    if (name === 'severity') {
      values = values.map(normaliseSeverity);
    } else if (cmp !== '=') {
      throw new DslError('Comparison operators are only valid for severity.');
    }
    return { k: 'field', name, cmp, values };
  }
}

function normaliseSeverity(v: string): string {
  let u = v.toUpperCase();
  if (u === 'WARNING') u = 'WARN';
  else if (u === 'ERR') u = 'ERROR';
  else if (u === 'CRITICAL') u = 'FATAL';
  if (!SEVERITIES.includes(u)) throw new DslError(`Unknown severity '${safe(v)}'.`);
  return u;
}

function quoteIfNeeded(v: string): string {
  const plain = v.length > 0 && !/[\s()"]/.test(v);
  return plain ? v : `"${v.replace(/\\/g, '\\\\').replace(/"/g, '\\"')}"`;
}

function wrap(a: Ast): string {
  const s = render(a);
  return a.k === 'and' || a.k === 'or' ? `(${s})` : s;
}

function render(a: Ast): string {
  switch (a.k) {
    case 'all': return '';
    case 'text': return quoteIfNeeded(a.v);
    case 'not': return `NOT ${wrap(a.c)}`;
    case 'and': return a.c.map(wrap).join(' AND ');
    case 'or': return a.c.map(wrap).join(' OR ');
    case 'field': {
      const cmp = a.cmp === '=' ? '' : a.cmp;
      if (a.values.length === 1) return `${a.name}:${cmp}${quoteIfNeeded(a.values[0])}`;
      return `${a.name}:(${a.values.map(quoteIfNeeded).join(' OR ')})`;
    }
  }
}

export type DslResult = { ok: true; normalised: string } | { ok: false; error: string };

/** Parses a query. Blank input is valid and matches everything. */
export function parseFilter(input: string): DslResult {
  if (input.trim() === '') return { ok: true, normalised: '' };
  try {
    if (input.length > DSL_MAX_LENGTH) throw new DslError(`The filter is too long (max ${DSL_MAX_LENGTH} characters).`);
    const parser = new Parser(tokenize(input));
    const ast = parser.parseOr(0);
    if (!parser.done()) throw new DslError(`Unexpected '${safe(parser.rest().text)}' in filter.`);
    return { ok: true, normalised: render(ast) };
  } catch (e) {
    return { ok: false, error: e instanceof Error ? e.message : 'Invalid filter.' };
  }
}

/** Appends `field:value` (or `-field:value` when excluding) to an existing query. */
export function addFacetTerm(query: string, field: string, value: string, exclude: boolean): string {
  const term = `${exclude ? '-' : ''}${field}:${quoteIfNeeded(value)}`;
  const q = query.trim();
  if (q === '') return term;
  return /\bOR\b/.test(q) ? `(${q}) ${term}` : `${q} ${term}`;
}
