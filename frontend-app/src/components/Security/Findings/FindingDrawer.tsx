import React, { useCallback, useEffect, useId, useRef, useState } from 'react';
import ReactDOM from 'react-dom';
import { Icon } from '@iconify/react';
import type { SecurityFinding } from '../../../types/security';
import { EscapeStack, useEscapeStack } from '../../Dspm/datastores/drawer/EscapeStack';
import { LevelLabel } from '../../Dspm/datastores/ui/Chips';
import AffectedResources from './AffectedResources';
import FindingDetails from './FindingDetails';
import { CategoryIcon, NeutralChip } from './FindingChips';
import ResourceDrawer from './ResourceDrawer';
import { frameworkLabel, type SecurityMisconfiguration } from './findingsModel';

type FindingSection = 'details' | 'affected';

const SECTIONS: ReadonlyArray<{ id: FindingSection; label: string }> = [
  { id: 'details', label: 'Details' },
  { id: 'affected', label: 'Affected Resources' },
];

interface Props {
  row: SecurityMisconfiguration;
  /** Every finding on the page, for the resource drawer's other misconfigurations. */
  allFindings: readonly SecurityFinding[];
  /** The AWS account, for the CLI fixes and the resource details. */
  accountId: string | null;
  /** Should be stable: the drawer puts it in a keydown effect's dependencies. */
  onClose: () => void;
  onResolve: (finding: SecurityFinding) => void;
  resolvingKeys: readonly string[];
}

/**
 * One misconfiguration over the right 55% of the page, as the XamLens Misconfiguration drawer: the
 * header (the category's AWS mark, the full title, the severity and framework chips, the close X), then
 * a vertical nav with Details and Affected Resources beside the section. No request: the row carries
 * everything both sections show.
 *
 * A resource clicked in Affected Resources opens its own drawer over this one (`ResourceDrawer`).
 *
 * Portalled into `document.body` beside a dimming overlay whose click closes it. Escape is read on
 * `window`: it closes the topmost layer on the drawer's `EscapeStack` (the resource drawer, an open filter
 * or Columns menu), else the drawer. The close X takes focus on open; on close, focus goes back to what
 * had it.
 */
const FindingDrawer: React.FC<Props> = ({ row, allFindings, accountId, onClose, onResolve, resolvingKeys }) => {
  const [section, setSection] = useState<FindingSection>('details');
  const [resource, setResource] = useState<{ finding: SecurityFinding; opener: HTMLElement } | null>(null);
  const aside = useRef<HTMLElement>(null);
  const closeButton = useRef<HTMLButtonElement>(null);
  const escapeStack = useEscapeStack(aside);
  const ids = useId();
  const framework = frameworkLabel(row);
  const openResource = useCallback((finding: SecurityFinding, opener: HTMLElement) => setResource({ finding, opener }), []);
  // Stable: the resource drawer registers it on this drawer's Escape stack.
  const closeResource = useCallback(() => setResource(null), []);

  useEffect(() => {
    const opener = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    closeButton.current?.focus();
    return () => {
      if (opener?.isConnected) opener.focus();
    };
  }, []);

  useEffect(() => {
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key !== 'Escape') return;
      if (!escapeStack.closeTopmost()) onClose();
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [escapeStack, onClose]);

  return ReactDOM.createPortal(
    <>
      <div className="fixed inset-0 z-40 bg-[rgba(0,0,0,0.25)]" onClick={onClose} aria-hidden="true" />
      <aside
        ref={aside}
        role="dialog"
        aria-modal="true"
        aria-label={row.title || 'Misconfiguration'}
        tabIndex={-1}
        className="fixed inset-y-0 right-0 z-50 flex w-full flex-col bg-white shadow-2xl focus:outline-none lg:w-[55vw]"
      >
        <EscapeStack stack={escapeStack}>
          <div className="flex items-start gap-3 border-b border-[rgba(0,0,0,0.06)] px-5 pb-3 pt-4">
            {row.category !== null && <span className="mt-0.5"><CategoryIcon category={row.category} size={22} /></span>}
            <div className="min-w-0 flex-1 space-y-2">
              <h2 className="break-words text-base font-bold text-[rgba(0,0,0,0.88)]">{row.title || '-'}</h2>
              <div className="flex flex-wrap items-center gap-2">
                <span className="inline-flex rounded-full border border-[rgba(0,0,0,0.1)] bg-[rgba(0,0,0,0.02)] px-2 py-0.5">
                  <LevelLabel level={row.severity} label={row.severityLabel} />
                </span>
                {framework !== null && <NeutralChip label={framework} />}
              </div>
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
          <div className="flex min-h-0 flex-1">
            <nav className="w-44 flex-shrink-0 px-3 py-4">
              <div
                role="tablist"
                aria-label="Misconfiguration details"
                aria-orientation="vertical"
                className="flex flex-col gap-0.5 border-l border-[rgba(0,0,0,0.08)]"
              >
                {SECTIONS.map(tab => (
                  <button
                    key={tab.id}
                    id={`${ids}-tab-${tab.id}`}
                    type="button"
                    role="tab"
                    aria-selected={tab.id === section}
                    aria-controls={`${ids}-panel`}
                    onClick={() => setSection(tab.id)}
                    className={`-ml-px border-l-2 px-3 py-2 text-left text-xs font-semibold transition-colors ${
                      tab.id === section
                        ? 'border-[rgba(0,0,0,0.88)] bg-[rgba(0,0,0,0.03)] text-[rgba(0,0,0,0.88)]'
                        : 'border-transparent text-[rgba(0,0,0,0.45)] hover:text-[rgba(0,0,0,0.7)]'
                    }`}
                  >
                    {tab.label}
                  </button>
                ))}
              </div>
            </nav>
            <div
              role="tabpanel"
              id={`${ids}-panel`}
              aria-labelledby={`${ids}-tab-${section}`}
              className="min-w-0 flex-1 overflow-y-auto px-5 py-4"
            >
              {section === 'details'
                ? <FindingDetails row={row} accountId={accountId} />
                : (
                  <AffectedResources
                    row={row}
                    onOpenResource={openResource}
                    onResolve={onResolve}
                    resolvingKeys={resolvingKeys}
                  />
                )}
            </div>
          </div>
          {resource && (
            <ResourceDrawer
              resource={resource.finding}
              findings={allFindings}
              fromKey={row.key}
              accountId={accountId}
              returnFocus={resource.opener}
              onClose={closeResource}
              onResolve={onResolve}
              resolvingKeys={resolvingKeys}
            />
          )}
        </EscapeStack>
      </aside>
    </>,
    document.body,
  );
};

export default FindingDrawer;
