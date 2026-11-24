import React, { useId } from 'react';

/*
 * Small pieces the SaaS drawer and the SaaS file drawer share: the grey tag and the titled card.
 */

/**
 * A grey tag or chip: "Internal", "Same person", "Private", a document class, a shared-with address.
 * With `truncate` the text is cut at the chip's width and shown in full in `title`.
 */
export const GreyTag: React.FC<{ children: string; truncate?: boolean }> = ({ children, truncate }) => (
  <span
    title={truncate ? children : undefined}
    className={`inline-block rounded border border-[rgba(0,0,0,0.08)] bg-[rgba(0,0,0,0.05)] px-1.5 py-0.5 text-[10px] font-semibold text-[rgba(0,0,0,0.6)] ${
      truncate ? 'max-w-full truncate align-middle' : 'whitespace-nowrap'
    }`}
  >
    {children}
  </span>
);

/** A titled card, as the datastore drawer's Information cards. */
export const SaasCard: React.FC<{ title: string; children: React.ReactNode }> = ({ title, children }) => {
  const headingId = useId();
  return (
    <section aria-labelledby={headingId} className="rounded-xl border border-[rgba(0,0,0,0.08)] p-4">
      <h4 id={headingId} className="mb-3 border-b border-[rgba(0,0,0,0.06)] pb-2 text-[11px] font-bold text-[rgba(0,0,0,0.75)]">
        {title}
      </h4>
      {children}
    </section>
  );
};
