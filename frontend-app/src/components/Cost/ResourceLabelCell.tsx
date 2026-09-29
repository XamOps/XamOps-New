import React, { useState } from 'react';
import LabelBadge from '../Labels/LabelBadge';
import LabelAssignPopover from '../Labels/LabelAssignPopover';
import type { ResourceLabel, ResourceRef } from '../../types/resourceLabels';

/**
 * The label chips and the assign control for one resource, as used in the cost view.
 *
 * <p>Wraps the same {@code LabelBadge} and {@code LabelAssignPopover} the Cloud List uses,
 * against the same endpoints, so a label put on a resource here is the label the Cloud List
 * shows on it and the reverse. Nothing about the storage is cost-specific — see
 * {@code ResourceIdResolver} on the server for how a Cost and Usage Report id and an inventory
 * id are reconciled to one row.
 *
 * <h2>Why the open state lives here rather than in the section</h2>
 * The Cloud List tracks which row's popover is open in page state, which it needs because that
 * table's rows are addressable from elsewhere. Here nothing else needs to know, and a popover
 * closes itself on any outside click — so opening a second row's control closes the first as a
 * side effect of the click that opened it. One boolean per cell, no coordination.
 */
const ResourceLabelCell: React.FC<{
  accountId: string;
  /** The resource to label, in whichever id form the caller holds. */
  resource: ResourceRef;
  /** Labels currently on it. */
  labels: ResourceLabel[];
  /** Every label available to assign. */
  allLabels: ResourceLabel[];
  onChanged: (resourceId: string, updated: ResourceLabel[]) => void;
  onLabelCreated: (label: ResourceLabel) => void;
  onRemove: (label: ResourceLabel) => void;
}> = ({ accountId, resource, labels, allLabels, onChanged, onLabelCreated, onRemove }) => {
  const [open, setOpen] = useState(false);

  return (
    /* Clicks are kept off the row: this sits inside a service group whose rows respond to
       being clicked, and assigning a label should not also toggle anything. */
    <div
      className="flex items-center gap-1 flex-wrap justify-end min-w-0"
      onClick={(e) => e.stopPropagation()}
    >
      {labels.map((label) => (
        <LabelBadge key={label.id} label={label} onRemove={() => onRemove(label)} />
      ))}

      <div className="relative">
        <button
          type="button"
          onClick={() => setOpen((v) => !v)}
          aria-expanded={open}
          className="w-5 h-5 flex items-center justify-center rounded-full border border-[rgba(0,0,0,0.15)] text-[rgba(0,0,0,0.35)] hover:border-[rgba(0,0,0,0.4)] hover:text-[rgba(0,0,0,0.88)] text-xs transition-colors"
          title={labels.length > 0 ? 'Assign another label' : 'Assign a label'}
          aria-label={labels.length > 0 ? 'Assign another label' : 'Assign a label'}
        >
          +
        </button>

        {open && (
          <LabelAssignPopover
            accountId={accountId}
            resource={resource}
            assignedLabels={labels}
            allLabels={allLabels}
            onChanged={onChanged}
            onClose={() => setOpen(false)}
            onLabelCreated={onLabelCreated}
          />
        )}
      </div>
    </div>
  );
};

export default ResourceLabelCell;
