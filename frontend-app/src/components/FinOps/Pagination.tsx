/**
 * Pagination Component
 *
 * Reusable pagination control for tables
 */

import React from 'react';
import { TooltipWrapper } from '../ui';

interface PaginationProps {
  currentPage: number;
  totalPages: number;
  totalItems: number;
  itemsPerPage: number;
  onPageChange: (page: number) => void;
}

const PaginationButton: React.FC<{
  onClick: () => void;
  disabled: boolean;
  ariaLabel: string;
  tip: string;
  icon: string;
}> = ({ onClick, disabled, ariaLabel, tip, icon }) => (
  <TooltipWrapper tip={tip}>
    <button
      onClick={onClick}
      disabled={disabled}
      aria-label={ariaLabel}
      className={`px-3 py-1.5 text-sm font-medium rounded-lg border transition-all focus-visible:ring-2 focus-visible:ring-indigo-500 focus-visible:ring-offset-1 outline-none ${
        disabled
          ? 'bg-gray-100 text-gray-400 border-gray-200 cursor-not-allowed'
          : 'bg-white text-gray-700 border-gray-300 hover:bg-gray-50'
      }`}
    >
      <i className={icon}></i>
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
      <p className="text-sm text-gray-600">
        Showing <span className="font-semibold">{start}</span> to <span className="font-semibold">{end}</span> of{' '}
        <span className="font-semibold">{totalItems}</span> items
      </p>
      <div className="flex items-center space-x-2">
        <PaginationButton onClick={() => onPageChange(1)} disabled={currentPage === 1} ariaLabel="First page" tip="First Page" icon="fas fa-angle-double-left" />
        <PaginationButton onClick={() => onPageChange(currentPage - 1)} disabled={currentPage === 1} ariaLabel="Previous page" tip="Previous Page" icon="fas fa-angle-left" />
        <span className="px-4 py-1.5 text-sm font-medium text-gray-700">Page {currentPage} of {totalPages}</span>
        <PaginationButton onClick={() => onPageChange(currentPage + 1)} disabled={currentPage >= totalPages} ariaLabel="Next page" tip="Next Page" icon="fas fa-angle-right" />
        <PaginationButton onClick={() => onPageChange(totalPages)} disabled={currentPage >= totalPages} ariaLabel="Last page" tip="Last Page" icon="fas fa-angle-double-right" />
      </div>
    </div>
  );
};

export default Pagination;
