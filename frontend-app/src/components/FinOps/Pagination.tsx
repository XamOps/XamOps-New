/**
 * Pagination Component
 *
 * Reusable pagination control for tables
 */

import React from 'react';
import { TooltipWrapper } from '../ui/Tooltip';

interface PaginationProps {
  currentPage: number;
  totalPages: number;
  totalItems: number;
  itemsPerPage: number;
  onPageChange: (page: number) => void;
}

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

  const getBtnClass = (disabled: boolean) => `px-3 py-1.5 text-sm font-medium rounded-lg border transition-all focus-visible:ring-2 focus-visible:ring-indigo-500 focus-visible:ring-offset-1 ${
    disabled ? 'bg-gray-100 text-gray-400 border-gray-200 cursor-not-allowed' : 'bg-white text-gray-700 border-gray-300 hover:bg-gray-50'
  }`;

  return (
    <div className="flex items-center justify-between mt-4 px-2">
      <p className="text-sm text-gray-600">
        Showing <span className="font-semibold">{start}</span> to <span className="font-semibold">{end}</span> of{' '}
        <span className="font-semibold">{totalItems}</span> items
      </p>
      <div className="flex items-center space-x-2">
        <TooltipWrapper tip="First page">
          <button onClick={() => onPageChange(1)} disabled={currentPage === 1} aria-label="First page" className={getBtnClass(currentPage === 1)}>
            <i className="fas fa-angle-double-left"></i>
          </button>
        </TooltipWrapper>
        <TooltipWrapper tip="Previous page">
          <button onClick={() => onPageChange(currentPage - 1)} disabled={currentPage === 1} aria-label="Previous page" className={getBtnClass(currentPage === 1)}>
            <i className="fas fa-angle-left"></i>
          </button>
        </TooltipWrapper>
        <span className="px-4 py-1.5 text-sm font-medium text-gray-700">Page {currentPage} of {totalPages}</span>
        <TooltipWrapper tip="Next page">
          <button onClick={() => onPageChange(currentPage + 1)} disabled={currentPage >= totalPages} aria-label="Next page" className={getBtnClass(currentPage >= totalPages)}>
            <i className="fas fa-angle-right"></i>
          </button>
        </TooltipWrapper>
        <TooltipWrapper tip="Last page">
          <button onClick={() => onPageChange(totalPages)} disabled={currentPage >= totalPages} aria-label="Last page" className={getBtnClass(currentPage >= totalPages)}>
            <i className="fas fa-angle-double-right"></i>
          </button>
        </TooltipWrapper>
      </div>
    </div>
  );
};

export default Pagination;
