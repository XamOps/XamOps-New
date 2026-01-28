/**
 * Pagination Component
 *
 * Reusable pagination control for tables
 */

import React from 'react';

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

  return (
    <div className="flex items-center justify-between mt-4 px-2">
      <p className="text-sm text-gray-600">
        Showing <span className="font-semibold">{start}</span> to <span className="font-semibold">{end}</span> of{' '}
        <span className="font-semibold">{totalItems}</span> items
      </p>
      <div className="flex items-center space-x-2">
        <button
          onClick={() => onPageChange(1)}
          disabled={currentPage === 1}
          className={`px-3 py-1.5 text-sm font-medium rounded-lg border transition-all ${
            currentPage === 1
              ? 'bg-gray-100 text-gray-400 border-gray-200 cursor-not-allowed'
              : 'bg-white text-gray-700 border-gray-300 hover:bg-gray-50'
          }`}
        >
          <i className="fas fa-angle-double-left"></i>
        </button>
        <button
          onClick={() => onPageChange(currentPage - 1)}
          disabled={currentPage === 1}
          className={`px-3 py-1.5 text-sm font-medium rounded-lg border transition-all ${
            currentPage === 1
              ? 'bg-gray-100 text-gray-400 border-gray-200 cursor-not-allowed'
              : 'bg-white text-gray-700 border-gray-300 hover:bg-gray-50'
          }`}
        >
          <i className="fas fa-angle-left"></i>
        </button>
        <span className="px-4 py-1.5 text-sm font-medium text-gray-700">
          Page {currentPage} of {totalPages}
        </span>
        <button
          onClick={() => onPageChange(currentPage + 1)}
          disabled={currentPage >= totalPages}
          className={`px-3 py-1.5 text-sm font-medium rounded-lg border transition-all ${
            currentPage >= totalPages
              ? 'bg-gray-100 text-gray-400 border-gray-200 cursor-not-allowed'
              : 'bg-white text-gray-700 border-gray-300 hover:bg-gray-50'
          }`}
        >
          <i className="fas fa-angle-right"></i>
        </button>
        <button
          onClick={() => onPageChange(totalPages)}
          disabled={currentPage >= totalPages}
          className={`px-3 py-1.5 text-sm font-medium rounded-lg border transition-all ${
            currentPage >= totalPages
              ? 'bg-gray-100 text-gray-400 border-gray-200 cursor-not-allowed'
              : 'bg-white text-gray-700 border-gray-300 hover:bg-gray-50'
          }`}
        >
          <i className="fas fa-angle-double-right"></i>
        </button>
      </div>
    </div>
  );
};

export default Pagination;
