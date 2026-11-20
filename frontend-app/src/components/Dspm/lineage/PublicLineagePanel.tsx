import React, { useMemo, type RefObject } from 'react';
import { Icon } from '@iconify/react';
import { visibleLanes, type LineageEventView } from '../datastores/saas/lineageModel';
import { ConsoleOnlyButton } from '../datastores/ui/ConsoleOnly';
import LineageTreeCanvas, { type TreeBandView, type TreeRowView } from './LineageTreeCanvas';
import { LevelSearch, rowView } from './LineagePanel';
import type { RowLevel } from './lineageQuery';
import {
  LINEAGE_LOADING_TEXT, NO_EVENT_MATCH_TEXT, NO_LINEAGE_TEXT, PANEL_COPY, ROOT_FALLBACK_NAME,
} from './lineageText';
import type { TreeLevel } from './lineageTree';
import { publicLineageLanes, type PublicLineageSubject } from './publicLineageModel';
import {
  datastoreKind, publicOpenLevel, type PublicLineageQuery, type PublicOpenLevel, type PublicRowLevel,
} from './publicLineageQuery';
import type { PublicLineageData } from './usePublicLineageData';

interface Props {
  query: PublicLineageQuery;
  /** The root node's name; null shows "Project". */
  projectName: string | null;
  data: PublicLineageData;
  /** Drawn inside the full-screen modal: no full-screen button, a taller canvas. */
  fullscreen: boolean;
  toggleRef: RefObject<HTMLButtonElement | null>;
  onToggleFullscreen: () => void;
  onNode: (level: 'root' | PublicRowLevel, id: string) => void;
  onPage: (level: PublicRowLevel, direction: 1 | -1) => void;
  onSearch: (level: PublicOpenLevel, text: string) => void;
  onViewDetails: (event: LineageEventView, anchor: HTMLElement) => void;
}

/**
 * The public path's panel (build 7 design 6.5): the open level's heading, help and search, at the
 * datastore level the filter icon and a greyed "+ More filters" (no public data, D7), the full-screen
 * button, and build 4's tree canvas with the project root, the datastores, the chosen datastore's files
 * and the chosen file's lineage band.
 */
const PublicLineagePanel: React.FC<Props> = ({
  query, projectName, data, fullscreen, toggleRef, onToggleFullscreen, onNode, onPage, onSearch, onViewDetails,
}) => {
  const level = publicOpenLevel(query);
  const copy = PANEL_COPY[level];

  const rows: TreeRowView[] = [{
    level: 'root',
    nodes: [{ key: 'root', id: 'root', view: { kind: 'root', name: projectName ?? ROOT_FALLBACK_NAME } }],
    chosen: 'root',
    prev: false,
    next: false,
    message: null,
  }];
  rows.push(rowView({
    level: 'datastore', resource: data.datastores, view: datastore => ({ kind: 'datastore', datastore }),
    chosenId: query.datastore, page: query.page.datastore, q: query.q.datastore, filtered: false,
  }));
  if (query.datastore !== null) {
    const actorLine = datastoreKind(query.datastore) === 'ENDPOINT';
    rows.push(rowView({
      level: 'file',
      resource: data.files,
      rows: data.fileRows,
      view: file => ({
        kind: 'file',
        file: { id: file.id, name: file.name, extension: file.extension, actor: file.actor, modifiedAt: file.dateAt },
        actorLine,
      }),
      chosenId: query.file, page: query.page.file, q: query.q.file, filtered: false, subject: 'file',
    }));
  }

  // The band: the chosen file's lanes, with what the chosen rows tell when they are on the loaded page.
  const fileRow = data.files.data?.rows.find(row => row.id !== null && row.id === query.file) ?? null;
  const datastoreRow = data.datastores.data?.rows.find(row => row.id !== null && row.id === query.datastore) ?? null;
  const subject = useMemo<PublicLineageSubject>(() => ({
    name: fileRow?.name ?? '-',
    datastoreName: datastoreRow?.name ?? null,
    service: datastoreRow?.service ?? null,
    actor: fileRow?.actor ?? null,
    createdAt: fileRow?.createdAt ?? null,
  }), [fileRow, datastoreRow]);
  const lineage = data.lineage;
  const kind = query.datastore === null ? null : datastoreKind(query.datastore);
  const lanes = useMemo(
    () => (kind !== null && lineage.data ? publicLineageLanes(kind, subject, lineage.data) : null),
    [kind, subject, lineage.data],
  );
  const eventQ = query.q.event;
  const band = useMemo<TreeBandView | null>(() => {
    if (query.file === null) return null;
    if (lineage.error) {
      return { kind: 'message', message: { kind: 'error', error: lineage.error, onRetry: lineage.reload, subject: 'file' } };
    }
    if (!lineage.data || !lanes) return { kind: 'message', message: { kind: 'loading', text: LINEAGE_LOADING_TEXT } };
    if (lanes.length === 0) return { kind: 'message', message: { kind: 'empty', text: NO_LINEAGE_TEXT } };
    const shown = visibleLanes(lanes, eventQ);
    if (eventQ !== '' && shown.every(lane => lane.events.length === 0)) {
      return { kind: 'message', message: { kind: 'empty', text: NO_EVENT_MATCH_TEXT } };
    }
    return { kind: 'lanes', lanes: shown };
  }, [query.file, lineage.error, lineage.reload, lineage.data, lanes, eventQ]);

  // The canvas only draws the root, datastore and file rows (plan adaptation A-2).
  const nodeClick = (treeLevel: TreeLevel, id: string) => {
    if (treeLevel === 'root' || treeLevel === 'datastore' || treeLevel === 'file') onNode(treeLevel, id);
  };
  const pageClick = (rowLevel: RowLevel, direction: 1 | -1) => {
    if (rowLevel !== 'policy') onPage(rowLevel, direction);
  };

  return (
    <section className="rounded-xl border border-[rgba(0,0,0,0.08)] bg-white p-4">
      <div className="flex items-start justify-between gap-4">
        <div>
          <h2 className="text-sm font-bold text-[rgba(0,0,0,0.88)]">{copy.heading}</h2>
          <p className="mt-0.5 text-xs text-[rgba(0,0,0,0.5)]">{copy.help}</p>
        </div>
        {!fullscreen && (
          <button
            ref={toggleRef}
            type="button"
            aria-label="Full screen"
            title="Full screen"
            onClick={onToggleFullscreen}
            className="flex h-7 w-7 flex-shrink-0 items-center justify-center rounded-md border border-[rgba(0,0,0,0.12)] text-[rgba(0,0,0,0.55)] transition-colors hover:bg-[rgba(0,0,0,0.04)]"
          >
            <Icon icon="mdi:fullscreen" width={16} aria-hidden="true" />
          </button>
        )}
      </div>
      <div className="mt-3 flex items-center gap-2">
        <LevelSearch
          key={level}
          initial={query.q[level]}
          placeholder={copy.placeholder}
          onSearch={text => onSearch(level, text)}
        />
        {level === 'datastore' && (
          <>
            <Icon icon="mdi:filter-variant" width={16} className="text-[rgba(0,0,0,0.45)]" aria-hidden="true" />
            <ConsoleOnlyButton variant="secondary" icon="mdi:plus">More filters</ConsoleOnlyButton>
          </>
        )}
      </div>
      <div className="mt-3">
        <LineageTreeCanvas
          rows={rows}
          band={band}
          fullscreen={fullscreen}
          fitKey={level}
          onNode={nodeClick}
          onPage={pageClick}
          onViewDetails={onViewDetails}
        />
      </div>
    </section>
  );
};

export default PublicLineagePanel;
