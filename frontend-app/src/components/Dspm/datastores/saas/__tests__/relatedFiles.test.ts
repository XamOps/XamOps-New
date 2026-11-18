import { describe, expect, it } from 'vitest';
import { CONTENT_HASH_VERIFIED, relatedFileRows, relatedFilesHeading } from '../relatedFiles';
import { OWNER, copies, copy } from './saasTestData';

describe('relatedFileRows', () => {
  const list = copies([
    copy('copy-1', { fileName: 'offer.docx', ownerEmail: '  PAT@Example.com ' }),
    copy('copy-2', { fileName: 'offer.pdf', ownerEmail: 'sam@example.com', ownerName: 'Sam Lee' }),
    copy('copy-3', { fileName: 'notes.txt', ownerEmail: null, ownerName: 'build-host', driveType: null, os: 'MAC' }),
  ], 2);

  it('keeps the API order', () => {
    expect(relatedFileRows(list, OWNER).map(row => row.copy.key)).toEqual(['copy-1', 'copy-2', 'copy-3']);
  });

  it('marks the copies of the same person, ignoring case and spaces, and explains why', () => {
    expect(relatedFileRows(list, OWNER).map(row => [row.samePerson, row.why])).toEqual([
      [true, 'Same content hash · Same person'],
      [false, 'Same content hash'],
      [false, 'Same content hash'],
    ]);
  });

  it('marks no row without the file owner', () => {
    expect(relatedFileRows(list, null).every(row => !row.samePerson && row.why === 'Same content hash')).toBe(true);
  });

  it('reads one confidence for every row', () => {
    expect(CONTENT_HASH_VERIFIED).toBe('Content hash verified');
  });

  it('gives no rows for no copies', () => {
    expect(relatedFileRows(copies([], 0, false), OWNER)).toEqual([]);
  });
});

describe('relatedFilesHeading', () => {
  it("names the datastore and the file owner's e-mail", () => {
    expect(relatedFilesHeading('Pat Example', OWNER)).toEqual({ name: 'Pat Example', email: OWNER });
  });

  it('has no e-mail without an owner', () => {
    expect(relatedFilesHeading('Pat Example', null)).toEqual({ name: 'Pat Example', email: null });
    expect(relatedFilesHeading('Pat Example', '  ')).toEqual({ name: 'Pat Example', email: null });
  });
});
