/*
 * The XamLens sample mask (build 9), TS twin of xamops-service `SampleMask` (the source of truth).
 * Samples arrive masked from xamops-service; this copy is the display layer's second line and masks the
 * invented demo samples, so demo mode shows what UAT shows. Same rule, on code points:
 * - `*` becomes `•`; a `<<…>>` frame is kept and the rule applies to its inside;
 * - under 4 code points: all `•`; a value already at least half `•` is left as it is;
 * - otherwise at most half stays visible: digit-only values (phones, cards, IPs) keep their last digits,
 *   text keeps its separators, then each word's first character, then up to half of each word.
 * Deterministic and idempotent: masking a masked value returns it unchanged.
 */

export const SAMPLE_MASK = '\u2022';

const SEPARATORS = new Set(Array.from(' \t\n\r.,-_@+/\\:;()[]{}<>|#'));
const SECRET_LABELS = new Set(['SECRETS', 'SECRET', 'IT SECURITY']);
const SECRET_NAME_WORDS = new Set(['KEY', 'KEYS', 'TOKEN', 'TOKENS', 'SECRET', 'SECRETS', 'PASSWORD',
  'PASSWORDS', 'PASSWD', 'PWD', 'CREDENTIAL', 'CREDENTIALS', 'APIKEY']);

const isSeparator = (c: string) => SEPARATORS.has(c);
const isDigit = (c: string) => c.length === 1 && c >= '0' && c <= '9';

function core(s: string[]): string[] {
  const n = s.length;
  if (n < 4) return s.map(() => SAMPLE_MASK);
  let masks = 0;
  let digits = 0;
  let numericOnly = true;
  for (const c of s) {
    if (c === SAMPLE_MASK) masks++;
    else if (isDigit(c)) digits++;
    else if (!isSeparator(c)) numericOnly = false;
  }
  if (masks >= Math.ceil(n / 2)) return [...s];
  const visible = new Array<boolean>(n).fill(false);
  let budget = Math.floor(n / 2);
  if (numericOnly && digits >= 4) {
    let keepDigits = Math.min(4, Math.floor(digits / 2));
    for (let i = n - 1; i >= 0 && keepDigits > 0 && budget > 0; i--) {
      if (isDigit(s[i])) { visible[i] = true; keepDigits--; budget--; }
    }
    for (let i = n - 1; i >= 0 && budget > 0; i--) {
      if (isSeparator(s[i])) { visible[i] = true; budget--; }
    }
  } else {
    for (let i = 0; i < n && budget > 0; i++) {
      if (isSeparator(s[i])) { visible[i] = true; budget--; }
    }
    const words: [number, number][] = [];
    let start = -1;
    for (let i = 0; i <= n; i++) {
      const sep = i === n || isSeparator(s[i]);
      if (sep && start >= 0) { words.push([start, i]); start = -1; }
      else if (!sep && start < 0) start = i;
    }
    for (const [from, to] of words) {
      if (budget === 0) break;
      if (to - from >= 2) { visible[from] = true; budget--; }
    }
    for (const [from, to] of words) {
      const len = to - from;
      let shown = len >= 2 ? 1 : 0;
      for (let i = from + shown; i < to && shown < Math.floor(len / 2) && budget > 0; i++) {
        visible[i] = true; shown++; budget--;
      }
    }
  }
  return s.map((c, i) => (visible[i] ? c : SAMPLE_MASK));
}

function secretCore(s: string[]): string[] {
  const n = s.length;
  if (n < 4) return s.map(() => SAMPLE_MASK);
  const keep = Math.min(4, Math.floor(n / 2));
  return s.map((c, i) => (i < keep ? c : SAMPLE_MASK));
}

function apply(value: string, rule: (s: string[]) => string[]): string {
  const cps = Array.from(value).map(c => (c === '*' ? SAMPLE_MASK : c));
  const n = cps.length;
  if (n >= 4 && cps[0] === '<' && cps[1] === '<' && cps[n - 2] === '>' && cps[n - 1] === '>') {
    return `<<${rule(cps.slice(2, n - 2)).join('')}>>`;
  }
  return rule(cps).join('');
}

/** The half rule. */
export function maskSample(value: string): string {
  return apply(value, core);
}

/** The secret rule: at most the first 4 code points visible, the rest masked. */
export function maskSecretSample(value: string): string {
  return apply(value, secretCore);
}

/** Trim, upper-case, every run of characters other than A-Z / 0-9 to one space, trim. */
const normalise = (text: string) => text.trim().toUpperCase().replace(/[^A-Z0-9]+/g, ' ').trim();

/** Whether an entity's samples use the secret rule: by label (SECRETS, SECRET, IT & Security), else by name word. */
export function isSecretEntity(entityName: string | null | undefined,
  labels: readonly (string | null | undefined)[] | null | undefined): boolean {
  if (labels?.some(label => label != null && SECRET_LABELS.has(normalise(label)))) return true;
  if (entityName == null) return false;
  return normalise(entityName).split(' ').some(word => SECRET_NAME_WORDS.has(word));
}

/** Demo data: the secret rule for a secret-type entity, else the half rule (as xamops-service picks). */
export const maskSampleFor = (entityName: string | null | undefined,
  labels: readonly (string | null | undefined)[] | null | undefined, value: string): string =>
  (isSecretEntity(entityName, labels) ? maskSecretSample(value) : maskSample(value));
