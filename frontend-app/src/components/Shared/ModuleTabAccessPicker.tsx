/**
 * The module → tab checkbox tree used to scope a user's sidebar.
 *
 * Extracted from the BillOps admin user manager so the XamOps catalog
 * (constants/xamopsTabs.ts) and the BillOps admin catalog (constants/billopsAdminTabs.ts) render
 * through the same component. They are two different key vocabularies with identical semantics,
 * and the shape of that UI — including the "no selection = full access" rule it has to keep
 * stating — is the part worth having in one place.
 *
 * Both catalogs satisfy {@link AccessModule} structurally; the BillOps one carries an extra
 * `route` per tab, which is ignored here.
 */
import React from 'react';
import { Icon } from '@iconify/react';

export interface AccessTab {
  key: string;
  label: string;
  icon: string;
}

export interface AccessModule {
  key: string;
  label: string;
  icon: string;
  tabs: readonly AccessTab[];
}

export interface ModuleTabAccessPickerProps {
  /** Section heading, e.g. "Sidebar Module & Tab Access". */
  label: string;
  icon?: string;
  /** The catalog to render. */
  modules: readonly AccessModule[];
  /** Currently granted keys. Empty means unrestricted. */
  selected: readonly string[];
  onChange: (next: string[]) => void;
  /**
   * Applied to every new list before it is handed back. The XamOps catalog uses it to drop a
   * nested permission whose parent tab is no longer granted; the BillOps catalog has no nesting
   * and leaves it out.
   */
  normalize?: (keys: readonly string[]) => string[];
  /**
   * What "Select all" produces. Defaults to every tab in the catalog. XamOps overrides it so the
   * action never hands out a write permission that is deliberately absent from the tree.
   */
  selectAll?: (keys: readonly string[]) => string[];
  /** Rendered under each tab checkbox — the XamOps per-tab permission toggles. */
  renderTabExtra?: (tabKey: string) => React.ReactNode;
  /** Replaces the default "leave unchecked for full access" explanation. */
  helpText?: string;
  /** Height of the scroll area. */
  maxHeightClass?: string;
}

const DEFAULT_HELP =
  'Leave everything unchecked to grant full sidebar access. Selecting specific tabs restricts the user to only those.';

export const ModuleTabAccessPicker: React.FC<ModuleTabAccessPickerProps> = ({
  label,
  icon = 'mdi:view-grid-outline',
  modules,
  selected,
  onChange,
  normalize,
  selectAll,
  renderTabExtra,
  helpText = DEFAULT_HELP,
  maxHeightClass = 'max-h-[320px]',
}) => {
  const allKeys = React.useMemo(
    () => modules.flatMap(m => m.tabs.map(t => t.key)),
    [modules],
  );

  const apply = (next: readonly string[]) => onChange(normalize ? normalize(next) : [...next]);

  const handleSelectAll = () =>
    onChange(selectAll ? selectAll(selected) : (normalize ? normalize(allKeys) : [...allKeys]));

  return (
    <div className="pt-1">
      <div className="flex items-center justify-between mb-2">
        <label className="flex items-center gap-1.5 text-[10px] font-semibold text-[rgba(0,0,0,0.5)] uppercase tracking-wider mb-1.5">
          <Icon icon={icon} width={11} />
          {label}
        </label>
        <div className="flex items-center gap-2 mb-1.5">
          <button
            type="button"
            onClick={handleSelectAll}
            className="text-[10px] font-semibold text-[rgba(0,0,0,0.6)] hover:text-black"
          >
            Select all
          </button>
          <span className="text-[rgba(0,0,0,0.15)]">|</span>
          <button
            type="button"
            onClick={() => onChange([])}
            className="text-[10px] font-semibold text-rose-500 hover:text-rose-700"
          >
            Clear
          </button>
        </div>
      </div>
      <p className="text-[11px] text-[rgba(0,0,0,0.4)] mb-3">{helpText}</p>
      <div className={`space-y-3 ${maxHeightClass} overflow-y-auto pr-1`}>
        {modules.map(mod => {
          const moduleTabKeys = mod.tabs.map(t => t.key);
          const allChecked = moduleTabKeys.every(k => selected.includes(k));
          // Every change goes through `normalize`, so unchecking a tab or its module also removes
          // anything nested under it.
          const toggleModule = () => {
            if (allChecked) {
              apply(selected.filter(k => !moduleTabKeys.includes(k)));
            } else {
              apply(Array.from(new Set([...selected, ...moduleTabKeys])));
            }
          };
          const toggleTab = (key: string) =>
            apply(selected.includes(key) ? selected.filter(k => k !== key) : [...selected, key]);

          return (
            <div key={mod.key} className="border border-[rgba(0,0,0,0.08)] rounded-xl overflow-hidden">
              <label className="flex items-center gap-2.5 px-3 py-2.5 bg-[rgba(0,0,0,0.025)] cursor-pointer">
                <input
                  type="checkbox"
                  checked={allChecked}
                  onChange={toggleModule}
                  className="w-4 h-4 rounded accent-[rgba(0,0,0,0.88)] cursor-pointer flex-shrink-0"
                />
                <Icon icon={mod.icon} width={14} color="rgba(0,0,0,0.6)" />
                <span className="text-xs font-bold text-[rgba(0,0,0,0.8)]">{mod.label}</span>
              </label>
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-1.5 p-2.5">
                {mod.tabs.map(tab => (
                  <div key={tab.key} className="flex flex-col gap-1">
                    <label className="flex items-center gap-2 px-2.5 py-2 border border-[rgba(0,0,0,0.06)] rounded-lg cursor-pointer hover:bg-[rgba(0,0,0,0.02)] transition-colors">
                      <input
                        type="checkbox"
                        checked={selected.includes(tab.key)}
                        onChange={() => toggleTab(tab.key)}
                        className="w-3.5 h-3.5 rounded accent-[rgba(0,0,0,0.88)] cursor-pointer flex-shrink-0"
                      />
                      <Icon icon={tab.icon} width={13} color="rgba(0,0,0,0.45)" />
                      <span className="text-[11px] font-medium text-[rgba(0,0,0,0.7)]">{tab.label}</span>
                    </label>
                    {renderTabExtra?.(tab.key)}
                  </div>
                ))}
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
};

export default ModuleTabAccessPicker;
