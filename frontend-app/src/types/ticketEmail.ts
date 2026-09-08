/**
 * The mail an email-sourced ticket was raised from.
 *
 * Shared by the XamOps and BillOps ticket surfaces, which read the same tickets out of the same
 * database — a ticket ingested from the support mailbox shows up in both.
 */

export interface TicketEmailDetails {
  fromAddress?: string;
  fromName?: string;
  /** Comma-separated, as it appeared on the header. */
  toAddresses?: string;
  ccAddresses?: string;
  /** The support group address the sender mentioned — this is what chose the category. */
  mailboxAddress?: string;
  subject?: string;
  /** When the sender sent it. */
  sentAt?: string;
  /** When the poller read it. */
  receivedAt?: string;
  matchMethod?: 'USER_EMAIL' | 'DOMAIN' | 'UNIDENTIFIED' | string;
  /** True when the sender matched no user and no known domain, so the ticket needs triage. */
  unidentifiedSender?: boolean;
}
