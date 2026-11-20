import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Icon } from '@iconify/react';
import { useLocation, useSearchParams } from 'react-router-dom';
import { inPageState } from '../../../utils/inPageNavigation';
import type { LineageEventView } from '../datastores/saas/lineageModel';
import { FullscreenFrame } from '../datastores/ui/FullscreenFrame';
import LineageEventDrawer from './LineageEventDrawer';
import LineageEscapeHost from './LineageEscapeHost';
import PublicLineagePanel from './PublicLineagePanel';
import { NEW_INVESTIGATION, PAGE_SUBTITLE, PAGE_TITLE, PANEL_COPY } from './lineageText';
import {
  EMPTY_PUBLIC_QUERY, clickPublicNode, parsePublicQuery, publicOpenLevel, setPublicPage, setPublicSearch,
  toPublicSearchParams, type PublicLineageQuery, type PublicOpenLevel, type PublicRowLevel,
} from './publicLineageQuery';
import { usePublicLineageData } from './usePublicLineageData';

interface Props {
  /** The XamOps project's name for the root node (`DspmStatus.projectName`); null shows "Project". */
  projectName: string | null;
}

/**
 * The public path (build 7 design 6.5), shown while the exposure routes are switched off: the header with
 * "New Investigation", which goes back to the datastore step, and the panel with the tree under the
 * project root, which full screen shows in a centred modal. The URL holds the choices, pages and searches
 * (`publicLineageQuery.ts`) and is rewritten with `replace` whenever it is not canonical. "View full
 * details →" in the event popover opens the event drawer, above full screen while it is open.
 */
const PublicLineageInvestigation: React.FC<Props> = ({ projectName }) => {
  const [params, setParams] = useSearchParams();
  const query = parsePublicQuery(params);

  // In-page navigations keep the layout's page key (utils/inPageNavigation), as build 4's page does.
  const location = useLocation();
  const go = useCallback(
    (next: PublicLineageQuery) => setParams(toPublicSearchParams(next), { replace: true, state: inPageState(location) }),
    [setParams, location],
  );

  // A passive effect, as DataLineagePage's: a navigation from a mount-time layout effect would be lost.
  useEffect(() => {
    const next = parsePublicQuery(params);
    if (toPublicSearchParams(next).toString() !== params.toString()) go(next);
  }, [params, go]);

  const [fullscreen, setFullscreen] = useState(false);
  /** The event whose drawer is open, and the card that opened it. */
  const [openEvent, setOpenEvent] = useState<{ event: LineageEventView; anchor: HTMLElement } | null>(null);
  const toggleRef = useRef<HTMLButtonElement>(null);
  const data = usePublicLineageData(query, go);

  const onNode = (level: 'root' | PublicRowLevel, id: string) => go(clickPublicNode(query, level, id));
  const onPage = (level: PublicRowLevel, direction: 1 | -1) => go(setPublicPage(query, level, query.page[level] + direction));
  const onSearch = (level: PublicOpenLevel, text: string) => go(setPublicSearch(query, level, text));

  const exitFullscreen = useCallback(() => setFullscreen(false), []);
  const closeEvent = useCallback(() => setOpenEvent(null), []);
  const level = publicOpenLevel(query);

  return (
    <LineageEscapeHost>
      <header className="flex items-start justify-between gap-4">
        <div>
          <h1 className="text-lg font-bold text-[rgba(0,0,0,0.88)]">{PAGE_TITLE}</h1>
          <p className="mt-0.5 text-sm text-[rgba(0,0,0,0.5)]">{PAGE_SUBTITLE}</p>
        </div>
        <button
          type="button"
          onClick={() => go(EMPTY_PUBLIC_QUERY)}
          className="inline-flex items-center gap-1.5 rounded-lg border border-[rgba(0,0,0,0.12)] bg-white px-3 py-1.5 text-xs font-semibold text-[rgba(0,0,0,0.75)] transition-colors hover:bg-[rgba(0,0,0,0.03)]"
        >
          <Icon icon="mdi:plus-circle-outline" width={14} aria-hidden="true" />
          {NEW_INVESTIGATION}
        </button>
      </header>
      <div className="mt-4">
        <FullscreenFrame
          variant="modal"
          open={fullscreen}
          title={PANEL_COPY[level].heading}
          onExit={exitFullscreen}
          toggleRef={toggleRef}
        >
          <PublicLineagePanel
            query={query}
            projectName={projectName}
            data={data}
            fullscreen={fullscreen}
            toggleRef={toggleRef}
            onToggleFullscreen={() => setFullscreen(true)}
            onNode={onNode}
            onPage={onPage}
            onSearch={onSearch}
            onViewDetails={(event, anchor) => setOpenEvent({ event, anchor })}
          />
        </FullscreenFrame>
      </div>
      {openEvent && (
        <LineageEventDrawer
          event={openEvent.event}
          returnFocus={openEvent.anchor}
          elevated={fullscreen}
          onClose={closeEvent}
        />
      )}
    </LineageEscapeHost>
  );
};

export default PublicLineageInvestigation;
