/**
 * Plain-English log search: turns "errors from api on i-0abc in the last hour containing connection reset" into the
 * log query language (severity:>=error service:api instance:i-0abc "connection reset") and a time range. Runs in the
 * browser with fixed rules: no text leaves the page, and the result is an ordinary query the server validates like
 * any other. Every recognised phrase is listed back so the user sees how it was read.
 */
import { RANGE_PRESETS, rangeMs, type RangePreset } from './format';

export interface Translation {
  query: string;
  range?: RangePreset;
  /** How each part was read, in words: "errors and worse", "service api", "last 1 hour". */
  understood: string[];
}

const STOP = new Set(['show', 'me', 'all', 'the', 'logs', 'log', 'lines', 'line', 'any', 'find', 'list', 'get', 'give', 'that', 'which', 'are',
  'is', 'were', 'was', 'a', 'an', 'and', 'please', 'recent', 'recently', 'entries', 'entry', 'messages', 'message', 'what', 'happened', 'there',
  'some', 'my', 'our', 'of', 'for', 'to', 'by', 'every', 'events', 'where', 'who', 'did', 'do', 'has', 'have', 'happening', 'going', 'during']);

const UNITS: [RegExp, number][] = [[/^(m|min|mins|minute|minutes)$/, 60_000], [/^(h|hr|hrs|hour|hours)$/, 3_600_000], [/^(d|day|days)$/, 86_400_000], [/^(w|wk|week|weeks)$/, 7 * 86_400_000]];

/** The smallest preset that covers the asked duration (so nothing asked for is cut off), capped at 30 days. */
export function presetFor(ms: number): RangePreset {
  return RANGE_PRESETS.find((p) => rangeMs(p) >= ms) ?? '30d';
}

const FIELD_WORDS: Record<string, string> = { service: 'service', app: 'service', application: 'service', container: 'container', region: 'region', source: 'source' };
const INSTANCE = /^i-[0-9a-f]{8,17}$/;
const REGION = /^[a-z]{2}(-gov)?-[a-z]+-\d$/;
const NAME = /^[A-Za-z0-9][\w.-]{0,63}$/;
const BREAK = new Set(['from', 'in', 'on', 'of', 'for', 'last', 'past', 'not', 'without', 'excluding', 'except', 'containing', 'with', 'matching',
  'mentioning', 'saying', 'about', 'and', 'or', 'since', 'today', 'host', 'instance', 'service', 'container', 'region']);

const quote = (s: string) => (/[\s:()"]/.test(s) ? `"${s.replace(/"/g, '')}"` : s);

export function translate(input: string): Translation {
  const understood: string[] = [];
  const terms: string[] = [];
  const text: string[] = [];
  let range: RangePreset | undefined;
  let severity: string | undefined;

  // Quoted phrases are taken as they are.
  const phrases: string[] = [];
  const rest = input.replace(/"([^"]+)"/g, (_, p: string) => { phrases.push(p.trim()); return ' '; });
  const words = rest.toLowerCase().replace(/[?!,;]+/g, ' ').split(/\s+/).filter(Boolean);
  const original = rest.replace(/[?!,;]+/g, ' ').split(/\s+/).filter(Boolean);

  for (let i = 0; i < words.length; i++) {
    const w = words[i];
    const next = words[i + 1];
    // time: "last 2 hours", "past day", "last hour", "today", "this week"
    if ((w === 'last' || w === 'past' || w === 'previous') && next) {
      const n = Number(next);
      const unitWord = Number.isFinite(n) && n > 0 ? words[i + 2] : next;
      const unit = UNITS.find(([re]) => unitWord && re.test(unitWord));
      if (unit) {
        const count = Number.isFinite(n) && n > 0 ? n : 1;
        range = presetFor(count * unit[1]);
        understood.push(`last ${count} ${unitWord.replace(/s$/, '')}${count === 1 ? '' : 's'}${rangeMs(range) > count * unit[1] ? ` (shown: ${range})` : ''}`);
        i += Number.isFinite(n) && n > 0 ? 2 : 1;
        continue;
      }
    }
    if (w === 'today') { range = '24h'; understood.push('last 24 hours'); continue; }
    if (w === 'this' && next && /^weeks?$/.test(next)) { range = '7d'; understood.push('last 7 days'); i++; continue; }
    // severity
    if (/^(fatals?|crash(es|ed)?|panics?|panicked)$/.test(w)) { severity = 'fatal'; continue; }
    if (/^(errors?|failures?|failed|failing)$/.test(w) && severity !== 'fatal') { severity = '>=error'; continue; }
    if (/^(warnings?|warns?)$/.test(w) && !severity) { severity = '>=warn'; continue; }
    // explicit fields: "service api", "api service", "container web", "region us-east-1"
    if (FIELD_WORDS[w] && next && NAME.test(original[i + 1] ?? '') && !BREAK.has(next) && !STOP.has(next)) {
      terms.push(`${FIELD_WORDS[w]}:${original[i + 1]}`);
      understood.push(`${FIELD_WORDS[w]} ${original[i + 1]}`);
      i++;
      continue;
    }
    if (next && FIELD_WORDS[next] && NAME.test(original[i]) && !STOP.has(w) && !BREAK.has(w)) {
      terms.push(`${FIELD_WORDS[next]}:${original[i]}`);
      understood.push(`${FIELD_WORDS[next]} ${original[i]}`);
      i++;
      continue;
    }
    if (INSTANCE.test(w)) { terms.push(`instance:${w}`); understood.push(`instance ${w}`); continue; }
    if (REGION.test(w)) { terms.push(`region:${w}`); understood.push(`region ${w}`); continue; }
    // "from api", "in checkout": a service, unless it is a host id or region (handled above on the next word)
    if ((w === 'from' || w === 'in') && next && !BREAK.has(next) && !STOP.has(next) && !INSTANCE.test(next) && !REGION.test(next)
      && NAME.test(original[i + 1]) && !/^(errors?|warnings?|fatals?)$/.test(next) && !UNITS.some(([re]) => re.test(next))) {
      terms.push(`service:${original[i + 1]}`);
      understood.push(`service ${original[i + 1]}`);
      i++;
      continue;
    }
    // negation: "not health", "excluding healthcheck", "without debug"
    if ((w === 'not' || w === 'without' || w === 'excluding' || w === 'except') && next && !STOP.has(next)) {
      terms.push(`-${quote(original[i + 1])}`);
      understood.push(`not "${original[i + 1]}"`);
      i++;
      continue;
    }
    // "containing connection reset": the words up to the next keyword are one phrase
    if (['containing', 'matching', 'mentioning', 'saying', 'about', 'with'].includes(w)) {
      const phrase: string[] = [];
      let j = i + 1;
      while (j < words.length && !BREAK.has(words[j])) { phrase.push(original[j]); j++; }
      if (phrase.length) { phrases.push(phrase.join(' ')); i = j - 1; }
      continue;
    }
    if (STOP.has(w) || BREAK.has(w)) continue;
    text.push(original[i]);
  }
  if (severity) {
    terms.unshift(`severity:${severity}`);
    understood.unshift(severity === 'fatal' ? 'fatal only' : severity === '>=error' ? 'errors and worse' : 'warnings and worse');
  }
  phrases.forEach((p) => { if (p) { terms.push(`"${p.replace(/"/g, '')}"`); understood.push(`containing "${p}"`); } });
  text.forEach((t) => { terms.push(quote(t)); understood.push(`word "${t}"`); });
  return { query: terms.join(' '), range, understood };
}
