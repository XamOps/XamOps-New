import { SoonMarker } from './SoonMarker';

export interface SegmentedOption<T extends string> {
  value: T;
  label: string;
  /** Rendered `aria-disabled`, still focusable; clicking it does nothing. */
  disabled?: boolean;
  /** Adds the "Soon" marker after the label. */
  soon?: boolean;
  title?: string;
}

interface SegmentedToggleProps<T extends string> {
  options: ReadonlyArray<SegmentedOption<T>>;
  value: T;
  onChange: (value: T) => void;
  /** Accessible name of the group, e.g. "View". */
  label: string;
}

/** A two-or-more-way switch (Entity View / File View, Table View / Graph View). One option is pressed. */
export function SegmentedToggle<T extends string>({ options, value, onChange, label }: SegmentedToggleProps<T>) {
  return (
    <div role="group" aria-label={label} className="inline-flex rounded-lg border border-[rgba(0,0,0,0.1)] bg-[rgba(0,0,0,0.03)] p-0.5">
      {options.map(option => {
        const pressed = option.value === value;
        return (
          <button
            key={option.value}
            type="button"
            aria-pressed={pressed}
            aria-disabled={option.disabled ? true : undefined}
            title={option.title}
            onClick={() => { if (!option.disabled && !pressed) onChange(option.value); }}
            className={`inline-flex items-center gap-1.5 rounded-md px-2.5 py-1 text-xs font-semibold transition-colors ${
              pressed
                ? 'bg-white text-[rgba(0,0,0,0.88)] shadow-sm'
                : option.disabled
                  ? 'cursor-not-allowed text-[rgba(0,0,0,0.35)]'
                  : 'text-[rgba(0,0,0,0.55)] hover:text-[rgba(0,0,0,0.8)]'
            }`}
          >
            {option.label}
            {option.soon && <SoonMarker />}
          </button>
        );
      })}
    </div>
  );
}
