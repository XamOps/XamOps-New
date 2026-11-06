import React, { useRef, useState } from 'react';
import LabelBadge from '../Labels/LabelBadge';
import LabelSharePopover from './LabelSharePopover';
import type { LabelShare, ResourceLabel } from '../../types/resourceLabels';

/**
 * The label chips and the label control for one resource or one service in the cost view.
 *
 * <p>Each chip shows its share when it takes less than the whole cost ("prod 40%"), and a note
 * says how much is left unallocated. The "+" opens {@link LabelSharePopover}, which edits every
 * label on the item and its share together.
 *
 * <p>The open state lives here: nothing else needs to know, and the popover closes itself on any
 * outside click, so opening a second row's control closes the first.
 */
const LabelShareCell: React.FC<{
  kind: 'resource' | 'service';
  /** What is being labelled, for the popover header. */
  subject: string;
  /** Labels on it now, each with its share. */
  labels: ResourceLabel[];
  /** Every label available to add. */
  allLabels: ResourceLabel[];
  /** Writes the full set of labels and shares. Throws on failure. */
  onSave: (shares: LabelShare[], next: ResourceLabel[]) => Promise<void>;
  onLabelCreated: (label: ResourceLabel) => void;
  /** The × on a chip. */
  onRemove: (label: ResourceLabel) => void;
  /** Right-align the chips, for a cell at the end of a row. */
  align?: 'start' | 'end';
  /** Where this item's audit trail is read from, for the popover's History view. */
  history?: { accountId: string; type: 'RESOURCE' | 'SERVICE'; id: string };
}> = ({ kind, subject, labels, allLabels, onSave, onLabelCreated, onRemove, align = 'end', history }) => {
  const [open, setOpen] = useState(false);
  const button = useRef<HTMLButtonElement>(null);

  const anyPartial = labels.some((l) => l.percent != null && l.percent < 100);
  const given = labels.reduce((acc, l) => acc + (l.percent ?? 100), 0);
  const unallocated = Math.round((100 - given) * 100) / 100;

  return (
    /* Clicks are kept off the row, which opens a panel when clicked. */
    <div
      className={`flex items-center gap-1 flex-wrap min-w-0 ${align === 'end' ? 'justify-end' : 'justify-start'}`}
      onClick={(e) => e.stopPropagation()}
    >
      {labels.map((label) => (
        <LabelBadge key={label.id} label={label} showPercent onRemove={() => onRemove(label)} />
      ))}
      {anyPartial && unallocated > 0 && (
        <span
          className="text-[10.5px] text-[rgba(0,0,0,0.45)] tabular-nums whitespace-nowrap"
          title={`${unallocated}% of this ${kind}'s cost has no label`}
        >
          {unallocated}% unallocated
        </span>
      )}

      <button
        ref={button}
        type="button"
        onClick={() => setOpen((v) => !v)}
        aria-expanded={open}
        aria-haspopup="dialog"
        className="no-print w-5 h-5 flex items-center justify-center rounded-full border border-[rgba(0,0,0,0.15)] text-[rgba(0,0,0,0.35)] hover:border-[rgba(0,0,0,0.4)] hover:text-[rgba(0,0,0,0.88)] text-xs transition-colors"
        title={labels.length > 0 ? 'Change labels and shares' : `Label this ${kind}`}
        aria-label={labels.length > 0 ? 'Change labels and shares' : `Label this ${kind}`}
      >
        +
      </button>

      {open && button.current && (
        <LabelSharePopover
          anchor={button.current}
          kind={kind}
          subject={subject}
          labels={labels}
          allLabels={allLabels}
          onSave={onSave}
          onLabelCreated={onLabelCreated}
          onClose={() => setOpen(false)}
          history={history}
        />
      )}
    </div>
  );
};

export default LabelShareCell;
