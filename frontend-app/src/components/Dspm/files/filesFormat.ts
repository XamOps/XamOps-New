/**
 * Files page formatting (pure). Every class is a full literal, as Tailwind's scanner requires.
 */

export interface DocumentCategoryAccent {
  /** A background class, for a chip's dot. */
  dot: string;
  /** A left-border colour class, for `BarChip`. */
  bar: string;
}

const accent = (dot: string, bar: string): DocumentCategoryAccent => ({ dot, bar });

const IT_TECHNICAL = accent('bg-[rgb(81,162,255)]', 'border-l-[rgb(81,162,255)]');
const FINANCIAL = accent('bg-[rgb(0,188,125)]', 'border-l-[rgb(0,188,125)]');
const LEGAL = accent('bg-[rgb(245,124,201)]', 'border-l-[rgb(245,124,201)]');
const PERSONAL = accent('bg-[rgb(253,199,0)]', 'border-l-[rgb(253,199,0)]');
/** Every other category, Other included. */
const DEFAULT_ACCENT = accent('bg-[rgb(255,100,103)]', 'border-l-[rgb(255,100,103)]');

/** The console's document-category colour map, keyed by the lower-cased label. */
const CATEGORY_ACCENTS: Readonly<Record<string, DocumentCategoryAccent>> = {
  'it & technical': IT_TECHNICAL,
  financial: FINANCIAL,
  legal: LEGAL,
  'marketing & sales': accent('bg-[rgb(235,161,88)]', 'border-l-[rgb(235,161,88)]'),
  'identity & personal': PERSONAL,
  personal: PERSONAL,
  'telecom r&d': accent('bg-[rgb(237,106,255)]', 'border-l-[rgb(237,106,255)]'),
  'telecom regulatory': accent('bg-[rgb(166,132,255)]', 'border-l-[rgb(166,132,255)]'),
  'government id': accent('bg-[rgb(45,212,191)]', 'border-l-[rgb(45,212,191)]'),
  'corporate financial documents': accent('bg-[rgb(34,211,238)]', 'border-l-[rgb(34,211,238)]'),
  'corporate identity documents': accent('bg-[rgb(163,230,53)]', 'border-l-[rgb(163,230,53)]'),
  'technical documents': accent('bg-[rgb(99,102,241)]', 'border-l-[rgb(99,102,241)]'),
  'marketing & sales documents': accent('bg-[rgb(255,137,4)]', 'border-l-[rgb(255,137,4)]'),
  'human resources (hr) documents': accent('bg-[rgb(154,132,250)]', 'border-l-[rgb(154,132,250)]'),
  'administrative documents': accent('bg-[rgb(88,161,235)]', 'border-l-[rgb(88,161,235)]'),
  'operational documents': accent('bg-[rgb(224,203,16)]', 'border-l-[rgb(224,203,16)]'),
  'strategic documents': accent('bg-[rgb(83,210,174)]', 'border-l-[rgb(83,210,174)]'),
  'research & development documents': accent('bg-[rgb(161,175,242)]', 'border-l-[rgb(161,175,242)]'),
  'communication & media documents': accent('bg-[rgb(132,211,221)]', 'border-l-[rgb(132,211,221)]'),
  'academic documents': accent('bg-[rgb(193,176,215)]', 'border-l-[rgb(193,176,215)]'),
  'computer screenshots': accent('bg-[rgb(171,212,170)]', 'border-l-[rgb(171,212,170)]'),
  'audit & compliance documents': accent('bg-[rgb(214,209,145)]', 'border-l-[rgb(214,209,145)]'),
};

/**
 * A document category's colour, matched case-insensitively ("IT & Technical" and "it & technical" are
 * blue); any other label, Other and null included, is the default red. Distinct from `dspmFormat.ts`'s
 * `categoryAccent`, which colours the sensitive categories.
 */
export function documentCategoryAccent(label: string | null): DocumentCategoryAccent {
  if (label === null) return DEFAULT_ACCENT;
  return CATEGORY_ACCENTS[label.trim().toLowerCase()] ?? DEFAULT_ACCENT;
}

/** The grey share icon (▷) of the Sharing Mode cell. */
export const SHARING_ICON = 'mdi:play-outline';
