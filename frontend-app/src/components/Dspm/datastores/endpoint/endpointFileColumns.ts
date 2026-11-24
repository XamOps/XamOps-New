/**
 * The Endpoint File View's columns (design 6.7, ninth batch): the one place for the nine columns of
 * "Columns 9", in the console's order. File Name is always shown. The unlabelled lineage-icon column
 * after File Name is not one of them: it is neither counted nor listed in the menu.
 */

export type EndpointFileColumnId =
  | 'fileName' | 'path' | 'user' | 'size' | 'sensitives' | 'category' | 'documentClass' | 'tags' | 'modified';

export const ENDPOINT_FILE_COLUMNS: ReadonlyArray<{ id: EndpointFileColumnId; label: string }> = [
  { id: 'fileName', label: 'File Name' },
  { id: 'path', label: 'File Path' },
  { id: 'user', label: 'Users' },
  { id: 'size', label: 'File Size' },
  { id: 'sensitives', label: 'Sensitives' },
  { id: 'category', label: 'Category' },
  { id: 'documentClass', label: 'Document Class' },
  { id: 'tags', label: 'Matters Tags' },
  { id: 'modified', label: 'Last Modified' },
];

/** The dot of the Category chip ("It & Technical"): blue, as in the console. */
export const CATEGORY_DOT = 'bg-sky-500';

/** The dot of the Document Class chip ("System Documentation"): pink, as in the console. */
export const DOCUMENT_CLASS_DOT = 'bg-rose-400';
