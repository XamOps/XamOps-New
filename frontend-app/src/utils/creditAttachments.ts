/**
 * Credit request attachment helpers
 *
 * Supporting documents live in S3. The list APIs only carry metadata (name, type, size), so a
 * fresh short-lived link is minted per click via
 * GET /api/billops/credits/{id}/attachments/{index}/url?disposition=inline|attachment.
 * That keeps expiring URLs out of the server-side and localStorage caches.
 */

import { apiFetch } from './apiClient';

export type AttachmentKind = 'pdf' | 'image' | 'sheet' | 'doc' | 'archive' | 'file';

const fetchAttachmentUrl = async (
  requestId: number,
  index: number,
  disposition: 'inline' | 'attachment'
): Promise<string> => {
  const response = await apiFetch(
    `/api/billops/credits/${requestId}/attachments/${index}/url?disposition=${disposition}`,
    { credentials: 'include' }
  );

  if (!response.ok) {
    throw new Error(
      response.status === 403
        ? 'You are not allowed to open this document.'
        : 'This document is no longer available.'
    );
  }

  const data = await response.json();
  if (!data?.url) {
    throw new Error('This document is no longer available.');
  }
  return data.url as string;
};

/**
 * Opens the document in a new tab. The tab is opened synchronously so the click is not lost to
 * the popup blocker while the link is being minted.
 */
export const viewCreditAttachment = async (requestId: number, index: number): Promise<void> => {
  const tab = window.open('', '_blank');
  if (tab) tab.opener = null;

  try {
    const url = await fetchAttachmentUrl(requestId, index, 'inline');
    if (tab) {
      tab.location.href = url;
    } else {
      window.open(url, '_blank', 'noopener');
    }
  } catch (error) {
    tab?.close();
    throw error;
  }
};

/**
 * Downloads the document. S3 returns it with a Content-Disposition of attachment, so the current
 * page is never navigated away from.
 */
export const downloadCreditAttachment = async (
  requestId: number,
  index: number,
  fileName?: string
): Promise<void> => {
  const url = await fetchAttachmentUrl(requestId, index, 'attachment');
  const link = document.createElement('a');
  link.href = url;
  link.download = fileName || '';
  link.rel = 'noopener';
  document.body.appendChild(link);
  link.click();
  link.remove();
};

/**
 * Coarse file classification, so each page can map it to its own icon set.
 */
export const attachmentKind = (name: string, type?: string | null): AttachmentKind => {
  const value = `${type || ''} ${name || ''}`.toLowerCase();
  if (value.includes('pdf')) return 'pdf';
  if (/(image|\.png|\.jpe?g|\.gif|\.webp|\.svg|\.heic)/.test(value)) return 'image';
  if (/(spreadsheet|excel|csv|\.xlsx?|\.csv)/.test(value)) return 'sheet';
  if (/(word|document|\.docx?|\.txt|\.rtf)/.test(value)) return 'doc';
  if (/(zip|compressed|\.zip|\.rar|\.7z|\.tar|\.gz)/.test(value)) return 'archive';
  return 'file';
};

export const formatFileSize = (bytes?: number | null): string => {
  if (bytes === null || bytes === undefined || Number.isNaN(bytes)) return '';
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
};

/** Rejects files that are empty or over the backend's 50MB per-file limit. */
export const MAX_ATTACHMENT_BYTES = 50 * 1024 * 1024;
