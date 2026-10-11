import { afterEach, describe, expect, it, vi } from 'vitest';
import { csvCell, csvFileName, downloadCsv, toCsv } from '../csvExport';

describe('csvExport', () => {
  afterEach(() => vi.restoreAllMocks());

  it('quotes cells holding commas, quotes and newlines', () => {
    expect(csvCell('plain')).toBe('plain');
    expect(csvCell('a,b')).toBe('"a,b"');
    expect(csvCell('say "hi"')).toBe('"say ""hi"""');
    expect(csvCell('line 1\nline 2')).toBe('"line 1\nline 2"');
    expect(csvCell('cr\rhere')).toBe('"cr\rhere"');
  });

  it.each([
    ['=SUM(A1:A2)', "'=SUM(A1:A2)"],
    ['+1', "'+1"],
    ['-1', "'-1"],
    ['@cmd', "'@cmd"],
    ['\tlead', "'\tlead"],
    ['\rlead', `"'\rlead"`],
  ])('prefixes a string that starts a formula: %j', (input, out) => {
    expect(csvCell(input)).toBe(out);
  });

  it('writes numbers as numbers, so -5 stays numeric', () => {
    expect(csvCell(-5)).toBe('-5');
    expect(csvCell(0)).toBe('0');
    expect(csvCell(1.25)).toBe('1.25');
    expect(csvCell(Number.NaN)).toBe('');
    expect(csvCell(null)).toBe('');
    expect(csvCell(undefined)).toBe('');
    expect(csvCell(true)).toBe('true');
  });

  it('joins a header and rows with CRLF', () => {
    expect(toCsv(['Name', 'CPU'], [['api', 250], ['=evil', -5]])).toBe("Name,CPU\r\napi,250\r\n'=evil,-5");
  });

  it('names files safely', () => {
    expect(csvFileName('Pods · prod-eks-cluster')).toBe('pods-prod-eks-cluster.csv');
    expect(csvFileName('nodes.csv')).toBe('nodes.csv');
    expect(csvFileName('  ')).toBe('export.csv');
  });

  it('downloads through an object URL and a temporary link', () => {
    vi.useFakeTimers();
    const createObjectURL = vi.fn(() => 'blob:csv');
    const revokeObjectURL = vi.fn();
    Object.assign(URL, { createObjectURL, revokeObjectURL });
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {});

    downloadCsv('Pods', 'a,b');
    expect(createObjectURL).toHaveBeenCalledWith(expect.any(Blob));
    expect(click).toHaveBeenCalledTimes(1);
    expect(document.querySelector('a[download]')).toBeNull();
    vi.runAllTimers();
    expect(revokeObjectURL).toHaveBeenCalledWith('blob:csv');
    vi.useRealTimers();
  });
});
