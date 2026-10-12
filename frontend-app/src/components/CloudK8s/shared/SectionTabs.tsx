/**
 * WAI-ARIA tabs for the cluster page (docs/cloudk8s/ui-revamp-design.md §11.1, §14): react-aria
 * `Tabs`, `TabList`, `Tab` and `TabPanel`, so the tablist has a label, tabs have roving focus with
 * arrow keys, Home and End, `aria-selected` and `aria-controls`, and every panel has
 * `aria-labelledby` back to its tab. (`components/ui/Tabs.tsx`'s `AriaTabGroup` renders no
 * `TabPanel`, so its tabs control panels that don't exist; it stays as is for other pages.)
 *
 * `primary` is the underlined greyscale look for "Cluster sections"; `sub` the segmented look for
 * "<Section> views". Only the selected panel is mounted.
 */
import type { Key, ReactNode } from 'react';
import { Icon } from '@iconify/react';
import { Tab, TabList, TabPanel, Tabs } from 'react-aria-components';

export interface SectionTabItem<K extends string> {
  id: K;
  label: string;
  /** Bundled Iconify id, a string literal. */
  icon?: string;
}

export interface SectionTabsProps<K extends string> {
  variant: 'primary' | 'sub';
  /** Names the tablist, e.g. "Cluster sections" or "Workloads views". */
  label: string;
  items: ReadonlyArray<SectionTabItem<K>>;
  selectedKey: K;
  onSelectionChange: (key: K) => void;
  /** Renders the selected panel's content. */
  renderPanel: (key: K) => ReactNode;
  /** Classes for the panel container. */
  panelClassName?: string;
  className?: string;
}

const focusRing = 'outline-none data-[focus-visible]:ring-2 data-[focus-visible]:ring-[rgba(0,0,0,0.88)]';

const LIST_CLASS = {
  primary: 'flex gap-1 overflow-x-auto border-b border-[rgba(0,0,0,0.12)]',
  sub: 'inline-flex flex-wrap items-center gap-0.5 rounded-[10px] border border-[rgba(0,0,0,0.08)] bg-[rgba(0,0,0,0.045)] p-[3px]',
};

function tabClass(variant: 'primary' | 'sub', isSelected: boolean): string {
  if (variant === 'primary') {
    return [
      '-mb-px flex cursor-pointer items-center gap-2 whitespace-nowrap border-b-2 px-3.5 py-2.5 text-[13px] font-semibold',
      `transition-colors ${focusRing} data-[focus-visible]:ring-inset`,
      isSelected
        ? 'border-[rgba(0,0,0,0.88)] text-[rgba(0,0,0,0.88)]'
        : 'border-transparent text-[rgba(0,0,0,0.6)] hover:border-[rgba(0,0,0,0.25)] hover:text-[rgba(0,0,0,0.88)]',
    ].join(' ');
  }
  return [
    'flex cursor-pointer items-center gap-1.5 whitespace-nowrap rounded-[7px] px-3 py-1.5 text-[13px] font-semibold',
    `transition-colors ${focusRing} data-[focus-visible]:ring-offset-1`,
    isSelected
      ? 'bg-white text-[rgba(0,0,0,0.88)] shadow-[0_1px_2px_rgba(0,0,0,0.08)]'
      : 'text-[rgba(0,0,0,0.6)] hover:text-[rgba(0,0,0,0.88)]',
  ].join(' ');
}

export function SectionTabs<K extends string>({
  variant, label, items, selectedKey, onSelectionChange, renderPanel, panelClassName = '', className = '',
}: SectionTabsProps<K>) {
  return (
    <Tabs
      selectedKey={selectedKey}
      onSelectionChange={(key: Key) => onSelectionChange(String(key) as K)}
      className={className}
    >
      <TabList aria-label={label} className={LIST_CLASS[variant]}>
        {items.map((item) => (
          <Tab key={item.id} id={item.id} className={({ isSelected }) => tabClass(variant, isSelected)}>
            {item.icon && <Icon icon={item.icon} width={15} aria-hidden="true" />}
            {item.label}
          </Tab>
        ))}
      </TabList>
      {items.map((item) => (
        <TabPanel key={item.id} id={item.id} className={`outline-none ${variant === 'primary' ? 'pt-4' : 'pt-3'} ${panelClassName}`}>
          {renderPanel(item.id)}
        </TabPanel>
      ))}
    </Tabs>
  );
}

export default SectionTabs;
