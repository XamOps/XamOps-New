import { describe, expect, it } from 'vitest';
import type { DocumentClassList } from '../../../../types/dspmPages';
import {
  CATEGORY_DIMENSION, DOCUMENT_CLASS_COLOURS, NEUTRAL_COLOUR, documentClassCategoryOptions, documentClassColour,
  filterDocumentClasses,
} from '../documentClasses';

const LIST: DocumentClassList = {
  categories: ['Finance Papers', 'People Files', 'Empty Group'],
  rows: [
    { name: 'Supplier Invoices', category: 'Finance Papers' },
    { name: 'Expense Sheets', category: 'Finance Papers' },
    { name: 'Offer Letters', category: 'People Files' },
    { name: 'INVOICE Archive', category: 'People Files' },
  ],
};

const include = (...values: string[]) => ({ [CATEGORY_DIMENSION]: { mode: 'include' as const, values } });

describe('documentClassCategoryOptions', () => {
  it('lists the categories in route order with their class counts, an empty one with 0', () => {
    expect(documentClassCategoryOptions(LIST)).toEqual([
      { value: 'Finance Papers', label: 'Finance Papers', count: 2 },
      { value: 'People Files', label: 'People Files', count: 2 },
      { value: 'Empty Group', label: 'Empty Group', count: 0 },
    ]);
  });
});

describe('filterDocumentClasses', () => {
  it('keeps every row in route order without a search or filter', () => {
    expect(filterDocumentClasses(LIST.rows, '', {})).toEqual(LIST.rows);
  });

  it('matches the trimmed search anywhere in the name, ignoring case', () => {
    expect(filterDocumentClasses(LIST.rows, '  invoice ', {}).map(r => r.name)).toEqual(['Supplier Invoices', 'INVOICE Archive']);
  });

  it('keeps only the chosen categories', () => {
    expect(filterDocumentClasses(LIST.rows, '', include('People Files')).map(r => r.name)).toEqual(['Offer Letters', 'INVOICE Archive']);
    expect(filterDocumentClasses(LIST.rows, '', include('Finance Papers', 'People Files'))).toHaveLength(4);
  });

  it('applies the search and the category together', () => {
    expect(filterDocumentClasses(LIST.rows, 'invoice', include('Finance Papers')).map(r => r.name)).toEqual(['Supplier Invoices']);
  });

  it('treats an empty selection as no filter', () => {
    expect(filterDocumentClasses(LIST.rows, '', include())).toHaveLength(4);
  });
});

describe('documentClassColour', () => {
  it('picks the colour by the category index and wraps after the eleventh', () => {
    const categories = Array.from({ length: 13 }, (_, i) => `Group ${i}`);
    expect(DOCUMENT_CLASS_COLOURS).toHaveLength(11);
    expect(documentClassColour('Group 0', categories)).toBe('bg-sky-500');
    expect(documentClassColour('Group 10', categories)).toBe('bg-cyan-600');
    expect(documentClassColour('Group 11', categories)).toBe('bg-sky-500');
    expect(new Set(categories.slice(0, 11).map(c => documentClassColour(c, categories))).size).toBe(11);
  });

  it('gives a category the list does not name the neutral colour', () => {
    expect(documentClassColour('Unknown', LIST.categories)).toBe(NEUTRAL_COLOUR);
  });
});
