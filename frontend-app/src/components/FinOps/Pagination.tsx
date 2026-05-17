/**
 * Pagination Component
 *
 * Reusable pagination control for tables
 */

import React from 'react';
import { TooltipWrapper } from '../ui';
import clsx from 'clsx';

interface PaginationProps {
  currentPage: number;
  totalPages: number;
  totalItems: number;
  itemsPerPage: number;
  onPageChange: (page: number) => void;
}

const NavButton: React.FC<{
  onClick: () => void;
  disabled: boolean;
  label: string;
  icon: string;
}> = ({ onClick, disabled, label, icon }) => (
  <TooltipWrapper tip={label}>
    <button
      onClick={onClick}
      disabled={disabled}
      aria-label={label}
      className={clsx(
        'px-3 py-1.5 text-sm font-medium rounded-lg border transition-all',
        'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500 focus-visible:ring-offset-1 dark:focus-visible:ring-offset-slate-900',
        disabled
          ? 'bg-gray-100 text-gray-400 border-gray-200 cursor-not-allowed opacity-50'
          : 'bg-white text-gray-700 border-gray-300 hover:bg-gray-50 hover:border-gray-400 active:bg-gray-100 dark:bg-slate-800 dark:border-slate-700 dark:text-slate-300 dark:hover:bg-slate-700'
      )}
    >
      <i className={clsx('fas', icon)}></i>
    </button>
  </TooltipWrapper>
);

const Pagination: React.FC<PaginationProps> = ({
  currentPage,
  totalPages,
  totalItems,
  itemsPerPage,
  onPageChange,
}) => {
  if (totalPages <= 1) return null;

  const start = (currentPage - 1) * itemsPerPage + 1;
  const end = Math.min(currentPage * itemsPerPage, totalItems);

  return (
    <div className="flex items-center justify-between mt-4 px-2">
      <p className="text-sm text-gray-600 dark:text-slate-400">
        Showing <span className="font-semibold text-gray-900 dark:text-slate-200">{start}</span> to{' '}
        <span className="font-semibold text-gray-900 dark:text-slate-200">{end}</span> of{' '}
        <span className="font-semibold text-gray-900 dark:text-slate-200">{totalItems}</span> items
      </p>
      <div className="flex items-center space-x-2">
        <NavButton
          onClick={() => onPageChange(1)}
          disabled={currentPage === 1}
          label="First Page"
          icon="fa-angle-double-left"
        />
        <NavButton
          onClick={() => onPageChange(currentPage - 1)}
          disabled={currentPage === 1}
          label="Previous Page"
          icon="fa-angle-left"
        />
        <span className="px-4 py-1.5 text-sm font-medium text-gray-700 dark:text-slate-300">
          Page {currentPage} of {totalPages}
        </span>
        <NavButton
          onClick={() => onPageChange(currentPage + 1)}
          disabled={currentPage >= totalPages}
          label="Next Page"
          icon="fa-angle-right"
        />
        <NavButton
          onClick={() => onPageChange(totalPages)}
          disabled={currentPage >= totalPages}
          label="Last Page"
          icon="fa-angle-double-right"
        />
      </div>
    </div>
  );
};

export default Pagination;
