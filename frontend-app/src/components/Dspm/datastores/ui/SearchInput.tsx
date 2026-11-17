import React from 'react';
import { Icon } from '@iconify/react';

interface SearchInputProps {
  value: string;
  onChange: (value: string) => void;
  /** Also the input's accessible name. */
  placeholder: string;
  /** Inputs that feed a `q` parameter pass `MAX_SEARCH`. */
  maxLength?: number;
  autoFocus?: boolean;
  className?: string;
}

/** A search box with the magnifier icon. */
export const SearchInput: React.FC<SearchInputProps> = ({ value, onChange, placeholder, maxLength, autoFocus, className }) => (
  <div className={`relative ${className ?? ''}`}>
    <Icon
      icon="mdi:magnify"
      width={14}
      className="pointer-events-none absolute left-2.5 top-1/2 -translate-y-1/2 text-[rgba(0,0,0,0.35)]"
    />
    <input
      type="search"
      value={value}
      onChange={e => onChange(e.target.value)}
      placeholder={placeholder}
      aria-label={placeholder}
      maxLength={maxLength}
      autoFocus={autoFocus}
      className="w-full rounded-lg border border-[rgba(0,0,0,0.1)] bg-white py-1.5 pl-8 pr-2.5 text-xs text-[rgba(0,0,0,0.8)] placeholder:text-[rgba(0,0,0,0.35)] focus:outline-none focus:ring-1 focus:ring-[rgba(0,0,0,0.2)]"
    />
  </div>
);
