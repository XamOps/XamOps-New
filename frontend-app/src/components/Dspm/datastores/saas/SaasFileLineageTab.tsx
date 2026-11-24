import React, { useCallback, useMemo, useRef, useState } from 'react';
import { Icon } from '@iconify/react';
import type { SaasFileLineage, SaasFileOverview } from '../../../../types/dspmDrawers';
import type { LazyResource } from '../../../../hooks/useDspmDatastoreDrawer';
import { InfoTooltip } from '../../../ui/Tooltip';
import { MAX_SEARCH } from '../datastoreFilters';
import { ConsoleOnlyButton } from '../ui/ConsoleOnly';
import { EmptyState } from '../ui/EmptyState';
import { FullscreenFrame } from '../ui/FullscreenFrame';
import { SearchInput } from '../ui/SearchInput';
import { ErrorNote, LoadingNote } from '../drawer/TabParts';
import LineageCanvas from './LineageCanvas';
import {
  GMAIL_LINEAGE_NOTE, NO_RELATED_FILES_TEXT, RELATED_FILES_CONFIDENCE, buildLineageLanes, isGmail, showsNoRelatedFiles,
  visibleLanes,
} from './lineageModel';

interface Props {
  overview: SaasFileOverview;
  /** The drawer's title: "Lineage for <name>". */
  name: string;
  lineage: LazyResource<SaasFileLineage>;
}

/**
 * The file's Lineage tab (FR-LIN, design 6.6). A Gmail file replicates the console in full (D8): the
 * subtitle about the attachment, the console's note, its own Gmail lane with "Attached to Email" (built
 * from the overview, GAP-12), no zoom. Any other file draws its copy lanes with a zoom and the coverage
 * note's ⓘ.
 *
 * States of the lineage request: while it loads, a Gmail file already shows its lane and any other file
 * "Loading…"; after a failure the error with Retry replaces the copy lanes, while a Gmail file keeps its
 * lane and note (review fix R6). The footer waits for real data in both cases, since whether other lanes
 * exist is unknown. The search and full-screen state live above the full-screen frame, so they survive it.
 */
const SaasFileLineageTab: React.FC<Props> = ({ overview, name, lineage }) => {
  const [query, setQuery] = useState('');
  const [fullscreen, setFullscreen] = useState(false);
  const toggle = useRef<HTMLButtonElement>(null);
  const exitFullscreen = useCallback(() => setFullscreen(false), []);

  const gmail = isGmail(overview);
  // A failed request keeps its last answer in `data`; the error decides what is drawn.
  const answer = lineage.error ? null : lineage.data;
  const loaded = answer !== null && !lineage.loading;
  const lanes = useMemo(() => buildLineageLanes(overview, answer), [overview, answer]);
  const shown = useMemo(() => visibleLanes(lanes, query), [lanes, query]);
  const searching = query.trim() !== '';
  const noMatch = searching && shown.length > 0 && shown.every(lane => lane.events.length === 0);
  const unavailable = loaded && !gmail && (answer.available === false || lanes.length === 0);
  const coverageNote = !gmail ? answer?.coverageNote ?? null : null;

  let body: React.ReactNode;
  if (lineage.error && !gmail) {
    body = <ErrorNote error={lineage.error} onRetry={lineage.reload} subject="file" />;
  } else if (!gmail && !loaded) {
    body = <LoadingNote />;
  } else if (unavailable) {
    body = (
      <div className="rounded-xl border border-[rgba(0,0,0,0.08)]">
        <EmptyState title="No lineage recorded for this file." />
      </div>
    );
  } else {
    body = (
      <>
        <LineageCanvas lanes={shown} zoomable={!gmail} fullscreen={fullscreen} />
        {lineage.error && <ErrorNote error={lineage.error} onRetry={lineage.reload} subject="file" />}
      </>
    );
  }

  return (
    <FullscreenFrame open={fullscreen} title={`Lineage for ${name}`} onExit={exitFullscreen} toggleRef={toggle}>
      <div className="space-y-3">
        <div>
          <h4 className="flex items-center gap-1.5 text-sm font-bold text-[rgba(0,0,0,0.85)]">
            <span className="min-w-0 truncate" title={`Lineage for ${name}`}>Lineage for {name}</span>
            {coverageNote && <InfoTooltip>{coverageNote}</InfoTooltip>}
          </h4>
          <p className="mt-0.5 text-xs text-[rgba(0,0,0,0.5)]">
            {gmail ? 'Trace where this attachment came from.' : 'Trace where this file came from.'}
          </p>
        </div>

        {gmail && (
          <div role="note" className="flex items-start gap-2 rounded-lg border border-sky-100 bg-sky-50 p-3 text-xs text-sky-800">
            <Icon icon="mdi:information-outline" width={14} className="mt-0.5 flex-shrink-0" />
            <span>{GMAIL_LINEAGE_NOTE}</span>
          </div>
        )}

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

export default SaasFileLineageTab;
