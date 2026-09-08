/**
 * The mail an email-sourced ticket was raised from.
 *
 * Rendered above the original request on tickets whose `source` is EMAIL. An agent answering one
 * of these needs facts a web ticket never has: who else is on the thread and will expect to stay
 * on it, which support address was mentioned (that is what chose the category), and whether the
 * sender was recognised at all — an unidentified sender means the ticket is parked under the
 * intake client until somebody assigns it to the right customer.
 */

import React from 'react';
import { Icon } from '@iconify/react';
import { formatIST } from '../../utils/timeUtils';
import type { TicketEmailDetails } from '../../types/ticketEmail';

export type { TicketEmailDetails };

interface Props {
  details: TicketEmailDetails;
  /** Only admins see the triage warning — it is internal routing state, not customer-facing. */
  isAdmin?: boolean;
}

const Row: React.FC<{ label: string; children: React.ReactNode }> = ({ label, children }) => (
  <div className="flex gap-3 text-[11px] leading-relaxed">
    <span className="w-20 flex-shrink-0 font-semibold uppercase tracking-widest text-[rgba(0,0,0,0.35)]">
      {label}
    </span>
    <span className="flex-1 min-w-0 break-words text-[rgba(0,0,0,0.75)]">{children}</span>
  </div>
);

const TicketEmailDetails: React.FC<Props> = ({ details, isAdmin = false }) => {
  const sender = details.fromName
    ? `${details.fromName} <${details.fromAddress ?? ''}>`
    : details.fromAddress ?? 'Unknown sender';

  // The backend sends `unidentifiedSender`; fall back to the raw match method so the panel still
  // warns correctly if only that field is present.
  const needsTriage = details.unidentifiedSender ?? details.matchMethod === 'UNIDENTIFIED';

  return (
    <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm overflow-hidden">
      <div className="flex items-center gap-2 px-5 py-3 bg-[rgba(0,0,0,0.02)] border-b border-[rgba(0,0,0,0.06)]">
        <Icon icon="mdi:email-outline" width={16} className="text-indigo-600" />
        <span className="text-[10px] font-semibold uppercase tracking-widest text-[rgba(0,0,0,0.5)]">
          Received by email
        </span>
        {details.mailboxAddress && (
          <span className="ml-auto text-[10px] font-mono font-semibold text-indigo-600 truncate">
            {details.mailboxAddress}
          </span>
        )}
      </div>

      <div className="px-5 py-4 space-y-2">
        <Row label="From">{sender}</Row>
        {details.toAddresses && <Row label="To">{details.toAddresses}</Row>}
        {details.ccAddresses && <Row label="Cc">{details.ccAddresses}</Row>}
        {details.subject && <Row label="Subject">{details.subject}</Row>}
        {details.sentAt && <Row label="Sent">{formatIST(details.sentAt)}</Row>}
        {details.receivedAt && <Row label="Received">{formatIST(details.receivedAt)}</Row>}
      </div>

      {needsTriage && isAdmin && (
        <div className="flex items-start gap-2 px-5 py-3 bg-amber-50 border-t border-amber-200">
          <Icon icon="mdi:alert-outline" width={16} className="text-amber-600 flex-shrink-0 mt-px" />
          <div className="text-[11px] text-amber-900 leading-relaxed">
            <span className="font-semibold">Sender not recognised.</span>{' '}
            This ticket is held under the intake client because the address matched no user and no
            known customer domain. Reassign it to the right client.
          </div>
        </div>
      )}
    </div>
  );
};

export default TicketEmailDetails;
