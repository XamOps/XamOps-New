import React from 'react';
import { DspmObjectIcon } from '../../DspmIcons';
import { extensionOf } from '../datastoreFormat';
import { CopyButton } from '../ui/CopyButton';
import { EmptyState } from '../ui/EmptyState';
import { GreyTag } from '../saas/SaasParts';

/*
 * Cells and empty states the Endpoint drawer's tables share: Entity View's two expansions and File View.
 */

/** Body cell style of the endpoint tables. */
export const TD = 'px-3 py-2 align-middle text-xs text-[rgba(0,0,0,0.75)]';

/**
 * The file icon by extension (the row's, else the name's), then the name in bold, truncated. Its tooltip
 * is `title` when given (File View and the Files page pass the path), else the name.
 */
export const FileNameCell: React.FC<{ name: string | null; extension?: string | null; title?: string | null }> = ({
  name, extension, title,
}) => (
  <span className="flex min-w-0 items-center gap-2">
    <DspmObjectIcon objectType={null} extension={extension ?? extensionOf(name)} />
    <span className="max-w-[11rem] truncate font-semibold text-[rgba(0,0,0,0.85)]" title={title ?? name ?? undefined}>
      {name ?? '-'}
    </span>
  </span>
);

/** The "New" tag after the name of a file modified in the last 48 hours (`isRecentlyModified`). */
export const NewBadge: React.FC = () => (
  <span
    title="Modified in the last 48 hours"
    className="flex-shrink-0 rounded-full border border-emerald-200 bg-emerald-50 px-1.5 py-px text-[10px] font-semibold text-emerald-700"
  >
    New
  </span>
);

/** The path, truncated with its full text in a `title`, and a copy button; "-" without a path. */
export const PathCell: React.FC<{ path: string | null }> = ({ path }) => (path ? (
  <span className="flex min-w-0 items-center gap-1">
    <span className="max-w-[16rem] truncate" title={path}>{path}</span>
    <CopyButton text={path} label="Copy path" />
  </span>
) : <>-</>);

/** Matters Tags: one grey chip per tag, "-" without any. */
export const TagChips: React.FC<{ tags: string[] }> = ({ tags }) => (tags.length === 0 ? <>-</> : (
  <span className="flex flex-wrap items-center gap-1">
    {tags.map((tag, index) => <GreyTag key={`${index}:${tag}`}>{tag}</GreyTag>)}
  </span>
));

/** Entity View's empty state, seen on a device with no findings (eighth batch); File View uses it too [O3]. */
export const NoEntitiesState: React.FC = () => (
  <EmptyState title="No sensitive entities found" body="Nothing matched the current filters on this agent's last scan." />
);

/** A search that matches nothing, in build 1's words. */
export const NoMatchesState: React.FC = () => <EmptyState title="No matches" body="Try a different search." />;
