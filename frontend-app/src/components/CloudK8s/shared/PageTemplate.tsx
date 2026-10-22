/**
 * PageTemplate (FEAT-001; docs/cloudk8s/ui-revamp-design.md §8): the one layout the flagship tabs
 * stamp. The main column reads top-to-bottom: a SECTION title, an at-most-one-sentence intro, the
 * hero, the children, then any detail — on an 8px grid (gap-6 between sections). `width` is 'prose'
 * (max-w-[935px], the default for cards and copy) or 'table' (max-w-full — fills the available
 * container, i.e. the real viewport minus the app sidebar, so a wide table never pins a fixed width
 * that clips its rightmost column; the table card scrolls INSIDE itself when its fixed columns
 * exceed the available width — owner review #5). An optional right-rail `aside` sits two-column on
 * large screens (w-[280px]) and stacks below on mobile. `<main>` stamps `data-k8s-redesigned-main`
 * and the region is labelled by its title.
 */
import { useId, type ReactNode } from 'react';
import { SECTION, SECONDARY } from './typeScale';

export interface PageTemplateProps {
  title: string;
  /** At most one sentence under the title. */
  intro?: ReactNode;
  /** The hero (usually a MetricStrip), above the body. */
  hero?: ReactNode;
  /** The main body. */
  children?: ReactNode;
  /** Detail content below the body (e.g. an opened drawer's peer content). */
  detail?: ReactNode;
  /** Right rail; two-column on lg, stacks below on mobile. */
  aside?: ReactNode;
  /**
   * 'prose' (935px, default) for cards and copy; 'table' (max-w-full) for wide tables — fills the
   * available container (viewport − sidebar) and lets the table card scroll internally rather than
   * pinning a fixed width that clips.
   */
  width?: 'prose' | 'table';
  className?: string;
}

export function PageTemplate({
  title, intro, hero, children, detail, aside, width = 'prose', className = '',
}: PageTemplateProps) {
  const titleId = useId();
  // 'table' fills the available container (never a fixed 1200px that ignores the sidebar and clips
  // the last column); the table card's own overflow scrolls its fixed columns. 'prose' keeps its cap.
  const maxWidth = width === 'table' ? 'max-w-full' : 'max-w-[935px]';

  const hasAside = aside !== undefined && aside !== null;

  /*
    The page's single visible title is the section breadcrumb above this template (EksDetailsPage ›
    SectionHeading, "Automation › Overview" / "Workloads › Pods"). Repeating that word here as a
    visible <h1> was a duplicate title, so the title stays as the <main> region's accessible name
    only (sr-only) — one visible page title, no change to assistive-tech labelling.
  */
  const titleBlock =
    intro !== undefined && intro !== null ? (
      <div className="flex flex-col gap-1">
        <h1 id={titleId} className={`${SECTION} sr-only`}>{title}</h1>
        <p className={`max-w-[66ch] ${SECONDARY}`}>{intro}</p>
      </div>
    ) : (
      <h1 id={titleId} className={`${SECTION} sr-only`}>{title}</h1>
    );

  const heroBlock = hero !== undefined && hero !== null ? <div data-page-hero="">{hero}</div> : null;
  const childrenBlock = children !== undefined && children !== null ? <div className="flex flex-col gap-6">{children}</div> : null;
  const detailBlock = detail !== undefined && detail !== null ? <div data-page-detail="">{detail}</div> : null;

  return (
    // Left-aligned (not mx-auto): the content column shares the SAME left edge as the full-bleed
    // cluster strip + section breadcrumb above it, so nothing is indented away from them. The width
    // cap still bounds prose / tables; it simply grows rightward from the shared left edge.
    <main
      data-k8s-redesigned-main=""
      data-k8s-kit=""
      data-page-width={width}
      aria-labelledby={titleId}
      className={`flex w-full flex-col gap-6 ${maxWidth} ${className}`.trim()}
    >
      {/*
        The title + hero always span the FULL content width, even when there is a right rail — so the
        hero MetricStrip keeps the room to stay one horizontal row and isn't squeezed by the aside
        (owner review #3c). Only the body + detail share the two-column split with the aside.
      */}
      {titleBlock}
      {heroBlock}
      {hasAside ? (
        <div className="flex flex-col gap-6 lg:flex-row lg:items-start">
          <div className="flex min-w-0 flex-1 flex-col gap-6">
            {childrenBlock}
            {detailBlock}
          </div>
          <aside className="w-full shrink-0 lg:w-[280px]" data-page-aside="">{aside}</aside>
        </div>
      ) : (
        <>
          {childrenBlock}
          {detailBlock}
        </>
      )}
    </main>
  );
}

export default PageTemplate;
