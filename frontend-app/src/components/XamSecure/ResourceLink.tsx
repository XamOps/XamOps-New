import React from 'react';
import { Icon } from '@iconify/react';

interface Props {
  resourceId: string | null | undefined;
  label: string | null | undefined;
  onOpen: (resourceId: string) => void;
  /** Secondary line under the name, e.g. the resource type. */
  sub?: string | null;
}

/**
 * The cross-tab entry point. Every table that names a resource uses this so the
 * affordance reads identically everywhere, and so opening the resource never collides
 * with the row's own click target.
 *
 * Falls back to plain text when there is no resource id to join on, rather than
 * rendering a control that would do nothing.
 */
const ResourceLink: React.FC<Props> = ({ resourceId, label, onOpen, sub }) => {
  const text = label ?? resourceId ?? '—';
  return (
    <>
      {resourceId ? (
        <button
          type="button"
          onClick={(e) => { e.stopPropagation(); onOpen(resourceId); }}
          title="View everything known about this resource"
          className="group inline-flex items-center gap-1 text-left font-medium text-gray-700 hover:text-gray-900 focus:outline-none focus-visible:ring-2 focus-visible:ring-gray-900 focus-visible:ring-offset-1 rounded"
        >
          <span className="underline decoration-gray-300 decoration-dotted underline-offset-2 group-hover:decoration-gray-500 max-w-[220px] truncate">
            {text}
          </span>
          <Icon icon="mdi:arrow-top-right" width={11}
                className="flex-shrink-0 text-gray-400 group-hover:text-gray-600" />
        </button>
      ) : (
        <span className="font-medium text-gray-700">{text}</span>
      )}
      {sub && <span className="block text-[10px] text-gray-400">{sub}</span>}
    </>
  );
};

export default ResourceLink;
