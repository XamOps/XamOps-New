import { describe, expect, it } from 'vitest';
import { SHARING_ICON, documentCategoryAccent } from '../filesFormat';

describe('documentCategoryAccent', () => {
  it.each([
    ['IT & Technical', 'rgb(81,162,255)'],
    ['it & technical', 'rgb(81,162,255)'],
    ['It & Technical', 'rgb(81,162,255)'],
    ['Financial', 'rgb(0,188,125)'],
    ['financial', 'rgb(0,188,125)'],
    ['Legal', 'rgb(245,124,201)'],
    ['Marketing & Sales', 'rgb(235,161,88)'],
    ['Identity & Personal', 'rgb(253,199,0)'],
    ['Personal', 'rgb(253,199,0)'],
    ['Government ID', 'rgb(45,212,191)'],
    ['Human Resources (HR) Documents', 'rgb(154,132,250)'],
  ])('colours %s as %s', (label, rgb) => {
    expect(documentCategoryAccent(label)).toEqual({ dot: `bg-[${rgb}]`, bar: `border-l-[${rgb}]` });
  });

  it.each(['Other', 'other', 'Media R&D', null])('gives %s the default red', label => {
    expect(documentCategoryAccent(label)).toEqual({ dot: 'bg-[rgb(255,100,103)]', bar: 'border-l-[rgb(255,100,103)]' });
  });

  it('uses the grey play icon for sharing', () => {
    expect(SHARING_ICON).toBe('mdi:play-outline');
  });
});
