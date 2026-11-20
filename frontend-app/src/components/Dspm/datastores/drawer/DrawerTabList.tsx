export interface DrawerTabDef<T extends string> {
  id: T;
  label: string;
}

export interface DrawerTabListProps<T extends string> {
  tabs: ReadonlyArray<DrawerTabDef<T>>;
  active: T;
  onSelect: (id: T) => void;
  /** The tablist's accessible name: "Datastore details", "File details", "Table details". */
  label: string;
  /** From `useId()`. Tab ids are `${idPrefix}-tab-${id}` and the panel's id is `${idPrefix}-panel`. */
  idPrefix: string;
  /**
   * `horizontal` (the default): the underline strip. `vertical`: a column of tabs with a left border
   * marker on the active one, as the Misconfiguration drawer's Details / Affected Datastores nav.
   */
  orientation?: 'horizontal' | 'vertical';
}

/**
 * A drawer's underline tab strip (design 6.2), with the active tab underlined. The caller renders the
 * panel: `div role="tabpanel" id={`${idPrefix}-panel`} aria-labelledby={`${idPrefix}-tab-${active}`}`.
 * Vertical, the tabs stack and the active one is marked on its left edge instead.
 */
export function DrawerTabList<T extends string>({
  tabs, active, onSelect, label, idPrefix, orientation = 'horizontal',
}: DrawerTabListProps<T>) {
  if (orientation === 'vertical') {
    return (
      <div
        role="tablist"
        aria-label={label}
        aria-orientation="vertical"
        className="flex flex-col gap-0.5 border-l border-[rgba(0,0,0,0.08)]"
      >
        {tabs.map(t => (
          <button
            key={t.id}
            id={`${idPrefix}-tab-${t.id}`}
            type="button"
            role="tab"
            aria-selected={t.id === active}
            aria-controls={`${idPrefix}-panel`}
            onClick={() => onSelect(t.id)}
            className={`-ml-px border-l-2 px-3 py-2 text-left text-xs font-semibold transition-colors ${
              t.id === active
                ? 'border-[rgba(0,0,0,0.88)] bg-[rgba(0,0,0,0.03)] text-[rgba(0,0,0,0.88)]'
                : 'border-transparent text-[rgba(0,0,0,0.45)] hover:text-[rgba(0,0,0,0.7)]'
            }`}
          >
            {t.label}
          </button>
        ))}
      </div>
    );
  }
  return (
    <div role="tablist" aria-label={label} className="flex gap-1 border-b border-[rgba(0,0,0,0.08)]">
      {tabs.map(t => (
        <button
          key={t.id}
          id={`${idPrefix}-tab-${t.id}`}
          type="button"
          role="tab"
          aria-selected={t.id === active}
          aria-controls={`${idPrefix}-panel`}
          onClick={() => onSelect(t.id)}
          className={`-mb-px border-b-2 px-3 py-2.5 text-xs font-semibold transition-colors ${
            t.id === active
              ? 'border-[rgba(0,0,0,0.88)] text-[rgba(0,0,0,0.88)]'
              : 'border-transparent text-[rgba(0,0,0,0.45)] hover:text-[rgba(0,0,0,0.7)]'
          }`}
        >
          {t.label}
        </button>
      ))}
    </div>
  );
}
