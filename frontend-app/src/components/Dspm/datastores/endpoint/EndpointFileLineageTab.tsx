import React, { useCallback, useMemo, useRef, useState } from 'react';
import { Icon } from '@iconify/react';
import type { EndpointFileLineage, EndpointFileOverview } from '../../../../types/dspmFiles';
import type { LazyResource } from '../../../../hooks/useDspmDatastoreDrawer';
import { InfoTooltip } from '../../../ui/Tooltip';
import { MAX_SEARCH } from '../datastoreFilters';
import { ErrorNote, LoadingNote } from '../drawer/TabParts';
import LineageCanvas from '../saas/LineageCanvas';
import {
  NO_RELATED_FILES_TEXT, RELATED_FILES_CONFIDENCE, showsNoRelatedFiles, visibleLanes,
} from '../saas/lineageModel';
import { ConsoleOnlyButton } from '../ui/ConsoleOnly';
import { EmptyState } from '../ui/EmptyState';
import { FullscreenFrame } from '../ui/FullscreenFrame';
import { SearchInput } from '../ui/SearchInput';
import { buildEndpointLineageLanes, onlyBuiltEvent } from './endpointLineageModel';

/** The title's ⓘ while the only event drawn is the built one. */
export const BUILT_EVENT_NOTE =
  'Lineage nodes will appear as activities are performed on the file after the integration is enabled.';

interface Props {
  /** The drawer's title: "Lineage for <name>". */
  name: string;
  /** The file's overview: its owner, device and creation time feed the own lane's built event. */
  overview: LazyResource<EndpointFileOverview>;
  lineage: LazyResource<EndpointFileLineage>;
}

/**
 * The Endpoint file's Lineage tab (plan C19), in the frame of build 2's SaaS Lineage tab: the own lane from
 * the lineage asset with its recorded activity, then the lanes of the file's hash copies. It waits for the
 * lineage and for the overview to answer or fail, so the built event does not change under the user; a
 * failed overview leaves the owner and the creation time out. The search and the full-screen state live
 * above the full-screen frame, so they survive it.
 */
const EndpointFileLineageTab: React.FC<Props> = ({ name, overview, lineage }) => {
  const [query, setQuery] = useState('');
  const [fullscreen, setFullscreen] = useState(false);
  const toggle = useRef<HTMLButtonElement>(null);
  const exitFullscreen = useCallback(() => setFullscreen(false), []);

  const file = overview.data;
  const settled = file !== null || overview.error !== null;
  const subject = useMemo(() => ({
    name,
    owner: file?.owner ?? null,
    device: file?.device ?? null,
    fileCreatedAt: file?.fileCreatedAt ?? null,
  }), [name, file]);
  // A failed request keeps its last answer in `data`; the error decides what is drawn.
  const answer = lineage.error ? null : lineage.data;
  const loaded = answer !== null && !lineage.loading && settled;
  const lanes = useMemo(() => buildEndpointLineageLanes(subject, answer), [subject, answer]);
  const shown = useMemo(() => visibleLanes(lanes, query), [lanes, query]);
  const searching = query.trim() !== '';
  const noMatch = searching && shown.length > 0 && shown.every(lane => lane.events.length === 0);
  const unavailable = loaded && (lanes.length === 0 || (answer.asset === null && answer.copies.available === false));
  const device = answer?.asset?.device ?? null;

  let body: React.ReactNode;
  if (lineage.error) {
    body = <ErrorNote error={lineage.error} onRetry={lineage.reload} subject="file" />;
  } else if (!loaded) {
    body = <LoadingNote />;
  } else if (unavailable) {
    body = (
      <div className="rounded-xl border border-[rgba(0,0,0,0.08)]">
        <EmptyState title="No lineage recorded for this file." />
      </div>
    );
  } else {
    body = <LineageCanvas lanes={shown} zoomable fullscreen={fullscreen} variant="endpoint" />;
  }

  return (
    <FullscreenFrame open={fullscreen} title={`Lineage for ${name}`} onExit={exitFullscreen} toggleRef={toggle}>
      <div className="space-y-3">
        <div>
          <h4 className="flex items-center gap-1.5 text-sm font-bold text-[rgba(0,0,0,0.85)]">
            <span className="min-w-0 truncate" title={`Lineage for ${name}`}>Lineage for {name}</span>
            {loaded && !unavailable && onlyBuiltEvent(lanes) && <InfoTooltip>{BUILT_EVENT_NOTE}</InfoTooltip>}
          </h4>
          <p className="mt-0.5 text-xs text-[rgba(0,0,0,0.5)]">
            {device ? `Recorded activity on ${device}.` : 'Trace where this file came from.'}
          </p>
        </div>

        <div className="flex flex-wrap items-center gap-3">
          <SearchInput value={query} onChange={setQuery} placeholder="Search an event" maxLength={MAX_SEARCH} className="w-60" />
          <ConsoleOnlyButton>
            {RELATED_FILES_CONFIDENCE}
            <Icon icon="mdi:chevron-down" width={14} />
          </ConsoleOnlyButton>
          {!fullscreen && (
            <button
              ref={toggle}
              type="button"
              aria-label="Full screen"
              onClick={() => setFullscreen(true)}
              className="ml-auto inline-flex items-center rounded-md p-1 text-[rgba(0,0,0,0.55)] transition-colors hover:bg-[rgba(0,0,0,0.05)] hover:text-[rgba(0,0,0,0.85)]"
            >
              <Icon icon="mdi:fullscreen" width={16} />
            </button>
          )}
        </div>

        {body}

        {noMatch && <p className="text-xs text-[rgba(0,0,0,0.45)]">No events match your search.</p>}
        {loaded && showsNoRelatedFiles(lanes) && (
          <p className="text-xs text-[rgba(0,0,0,0.45)]">{NO_RELATED_FILES_TEXT}</p>
        )}
      </div>
    </FullscreenFrame>
  );
};

export default EndpointFileLineageTab;
