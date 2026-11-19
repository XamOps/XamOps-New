import React, { useRef } from 'react';
import { Icon } from '@iconify/react';
import type { RemediationItem } from '../../../types/dspmPages';
import { DspmProviderIcon } from '../DspmIcons';
import { formatListDate } from '../datastores/datastoreFormat';
import { DrawerShell } from '../datastores/drawer/DrawerShell';
import { ConsoleOnlyButton } from '../datastores/ui/ConsoleOnly';
import { CopyButton } from '../datastores/ui/CopyButton';
import { ActionLabel, InitiatorCell, StatusChip } from './RemediationTable';
import { TONE_CLASSES, isWebLink, statusTone } from './remediationModel';

/** The console's text when the open run is no longer in the loaded page. */
export const RUN_NOT_FOUND_TEXT = 'Remediation not found in your current view. It may belong to a different project, '
  + 'be older than the loaded window, or be outside the current filters.';

const Field: React.FC<{ label: string; children: React.ReactNode }> = ({ label, children }) => (
  <div className="grid grid-cols-[8rem_1fr] items-start gap-3 text-xs">
    <span className="text-[rgba(0,0,0,0.45)]">{label}</span>
    <span className="min-w-0 text-[rgba(0,0,0,0.8)]">{children}</span>
  </div>
);

/** The console's write buttons for the run's status, greyed: the drawer sends nothing. */
const Footer: React.FC<{ status: string | null }> = ({ status }) => {
  if (status === 'WAITING_APPROVAL') {
    return (
      <>
        <ConsoleOnlyButton variant="secondary" placement="top">Reject</ConsoleOnlyButton>
        <ConsoleOnlyButton variant="primary" placement="top">Approve &amp; Run</ConsoleOnlyButton>
      </>
    );
  }
  if (status === 'SCHEDULED') return <ConsoleOnlyButton variant="secondary" icon="mdi:close-circle-outline" placement="top">Cancel scheduled run</ConsoleOnlyButton>;
  if (status === 'FAILED') return <ConsoleOnlyButton variant="primary" icon="mdi:refresh" placement="top">Retry action</ConsoleOnlyButton>;
  return null;
};

interface Props {
  /** The row the drawer was opened with; its fields are all the drawer shows (no extra request). */
  item: RemediationItem;
  /** True once a refresh of the list no longer holds the row. */
  missing: boolean;
  onClose: () => void;
}

/**
 * "Run detail" (FR-REM-6, design 6.7): the run's action, object, datastore and status, its Path (a link
 * only for http and https URLs) and the Details. Reject, Approve & Run, Cancel scheduled run and Retry
 * action are the console's and greyed, by status.
 */
const RemediationRunDrawer: React.FC<Props> = ({ item, missing, onClose }) => {
  const closeButton = useRef<HTMLButtonElement>(null);
  const tone = TONE_CLASSES[statusTone(item.status)];
  const approvers = item.approvers.join(', ');
  return (
    <DrawerShell label="Run detail" onClose={onClose} initialFocus={closeButton} widthClass="lg:w-[36rem]">
      <div className="flex items-start gap-3 border-b border-[rgba(0,0,0,0.06)] px-5 pb-3 pt-4">
        <div className="min-w-0 flex-1">
          <h2 className="text-base"><ActionLabel item={item} /></h2>
          {item.id && <p className="mt-0.5 truncate text-[11px] text-[rgba(0,0,0,0.45)]">Run {item.id}</p>}
        </div>
        <button
          ref={closeButton}
          type="button"
          onClick={onClose}
          aria-label="Close"
          className="flex h-7 w-7 flex-shrink-0 items-center justify-center rounded-md border border-[rgba(0,0,0,0.12)] text-[rgba(0,0,0,0.5)] transition-colors hover:bg-[rgba(0,0,0,0.04)] hover:text-[rgba(0,0,0,0.85)]"
        >
          <Icon icon="mdi:close" width={14} />
        </button>
      </div>
      {missing ? (
        <div className="flex flex-1 flex-col items-center gap-3 px-8 py-16 text-center">
          <Icon icon="mdi:file-search-outline" width={24} className="text-[rgba(0,0,0,0.3)]" />
          <p className="text-xs text-[rgba(0,0,0,0.55)]">{RUN_NOT_FOUND_TEXT}</p>
        </div>
      ) : (
        <>
          <div className="flex-1 space-y-4 overflow-y-auto px-5 py-4">
            <div className="flex overflow-hidden rounded-xl border border-[rgba(0,0,0,0.08)] bg-white">
              <span aria-hidden="true" data-tone={statusTone(item.status)} className={`w-1 flex-shrink-0 ${tone.bar}`} />
              <div className="min-w-0 flex-1 space-y-3 p-4">
                <div className="flex items-start gap-2.5">
                  <span className="mt-0.5"><DspmProviderIcon provider={item.datastoreType} size={20} /></span>
                  <div className="min-w-0 flex-1">
                    <p className="truncate text-sm font-semibold text-[rgba(0,0,0,0.85)]" title={item.objectName ?? undefined}>
                      {item.objectName ?? '-'}
                    </p>
                    <p className="truncate text-[11px] text-[rgba(0,0,0,0.45)]">
                      {[item.datastoreLabel ?? item.datastoreType, item.sourceType].filter(Boolean).join(' · ') || '-'}
                    </p>
                  </div>
                  <StatusChip item={item} />
                </div>
                {item.objectPath && (
                  <div className="space-y-1 border-t border-[rgba(0,0,0,0.06)] pt-3">
                    <p className="text-[11px] text-[rgba(0,0,0,0.45)]">Path</p>
                    <div className="flex min-w-0 items-center gap-2 text-xs">
                      {isWebLink(item.objectPath) ? (
                        <a
                          href={item.objectPath}
                          target="_blank"
                          rel="noopener noreferrer"
                          title={item.objectPath}
                          className="min-w-0 truncate text-sky-700 hover:underline"
                        >
                          {item.objectPath}
                        </a>
                      ) : (
                        <span className="min-w-0 truncate text-[rgba(0,0,0,0.8)]" title={item.objectPath}>{item.objectPath}</span>
                      )}
                      <CopyButton text={item.objectPath} label="Copy path" />
                    </div>
                  </div>
                )}
              </div>
            </div>
            <div className="space-y-3 rounded-xl border border-[rgba(0,0,0,0.08)] bg-white p-4">
              <p className="text-[11px] font-semibold uppercase tracking-wide text-[rgba(0,0,0,0.45)]">Details</p>
              <Field label="Initiated by"><InitiatorCell item={item} /></Field>
              <Field label="Trigger">{item.triggerLabel ?? item.triggerType ?? '-'}</Field>
              <Field label="Initiated at">{item.createdAt === null ? '-' : formatListDate(item.createdAt)}</Field>
              {approvers && <Field label="Approver">{approvers}</Field>}
              {item.scheduledAt !== null && <Field label="Scheduled for">{formatListDate(item.scheduledAt)}</Field>}
            </div>
          </div>
          {(item.status === 'WAITING_APPROVAL' || item.status === 'SCHEDULED' || item.status === 'FAILED') && (
            <div className="flex items-center justify-end gap-3 border-t border-[rgba(0,0,0,0.06)] px-5 py-3">
              <Footer status={item.status} />
            </div>
          )}
        </>
      )}
    </DrawerShell>
  );
};

export default RemediationRunDrawer;
