import React from 'react';
import { Icon } from '@iconify/react';

/** The empty-table illustration with a title and an optional hint. */
export const EmptyState: React.FC<{ title: string; body?: string }> = ({ title, body }) => (
  <div className="px-4 py-12 text-center">
    <Icon icon="mdi:database-search-outline" width={32} className="mx-auto text-[rgba(0,0,0,0.15)]" />
    <p className="mt-2 text-xs font-semibold text-[rgba(0,0,0,0.6)]">{title}</p>
    {body && <p className="mt-1 text-xs text-[rgba(0,0,0,0.4)]">{body}</p>}
  </div>
);
