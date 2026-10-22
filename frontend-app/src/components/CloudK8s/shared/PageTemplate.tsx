/**
 * PageTemplate (FEAT-001; docs/cloudk8s/ui-revamp-design.md §8): the one layout the flagship tabs
 * stamp. The main column reads top-to-bottom: a SECTION title, an at-most-one-sentence intro, the
 * hero, the children, then any detail — on an 8px grid (gap-6 between sections). `width` is 'prose'
 * (max-w-[935px], the default for cards and copy) or 'table' (max-w-[1200px], so a wide table isn't
 * forced to truncate). An optional right-rail `aside` sits two-column on large screens (w-[280px])
 * and stacks below on mobile. `<main>` stamps `data-k8s-redesigned-main` and the region is labelled
 * by its title.
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
  /** 'prose' (935px, default) for cards and copy; 'table' (1200px) for wide tables. */
  width?: 'prose' | 'table';
  className?: string;
}

export function PageTemplate({
  title, intro, hero, children, detail, aside, width = 'prose', className = '',
}: PageTemplateProps) {
  const titleId = useId();
  const maxWidth = width === 'table' ? 'max-w-[1200px]' : 'max-w-[935px]';

  const mainColumn = (
    <div className="flex min-w-0 flex-1 flex-col gap-6">
      <div className="flex flex-col gap-1">
        <h1 id={titleId} className={SECTION}>{title}</h1>
        {intro !== undefined && intro !== null && <p className={`max-w-[66ch] ${SECONDARY}`}>{intro}</p>}
      </div>
      {hero !== undefined && hero !== null && <div data-page-hero="">{hero}</div>}
      {children !== undefined && children !== null && <div className="flex flex-col gap-6">{children}</div>}
      {detail !== undefined && detail !== null && <div data-page-detail="">{detail}</div>}
    </div>
  );

  return (
    <main
      data-k8s-redesigned-main=""
      data-k8s-kit=""
      data-page-width={width}
      aria-labelledby={titleId}
      className={`mx-auto w-full ${maxWidth} ${className}`.trim()}
    >
      {aside !== undefined && aside !== null ? (
        <div className="flex flex-col gap-6 lg:flex-row lg:items-start">
          {mainColumn}
          <aside className="w-full shrink-0 lg:w-[280px]" data-page-aside="">{aside}</aside>
        </div>
      ) : (
        mainColumn
      )}
    </main>
  );
}

export default PageTemplate;
