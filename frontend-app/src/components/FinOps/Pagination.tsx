import React from "react";
import { Icon } from "@iconify/react";
import { TooltipWrapper } from "../ui";

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
  const btnCls = (disabled: boolean) =>
    `w-7 h-7 flex items-center justify-center rounded-md border text-[rgba(0,0,0,0.45)] transition-colors ${
      disabled
        ? "border-[rgba(0,0,0,0.06)] opacity-40 cursor-not-allowed"
        : "border-[rgba(0,0,0,0.12)] hover:bg-[rgba(0,0,0,0.04)] cursor-pointer"
    }`;
  return (
    <div className="flex items-center justify-between mt-3 px-1">
      <p className="text-[11px] text-[rgba(0,0,0,0.4)]">
        Showing{" "}
        <span className="font-semibold text-[rgba(0,0,0,0.7)]">{start}</span>–
        <span className="font-semibold text-[rgba(0,0,0,0.7)]">{end}</span> of{" "}
        <span className="font-semibold text-[rgba(0,0,0,0.7)]">
          {totalItems}
        </span>
      </p>
      <div className="flex items-center gap-1">
        <TooltipWrapper tip="First Page">
          <button
            type="button"
            onClick={() => onPageChange(1)}
            disabled={currentPage === 1}
            className={btnCls(currentPage === 1)}
            aria-label="First Page"
          >
            <Icon icon="mdi:chevron-double-left" width={14} />
          </button>
        </TooltipWrapper>

        <TooltipWrapper tip="Previous Page">
          <button
            type="button"
            onClick={() => onPageChange(currentPage - 1)}
            disabled={currentPage === 1}
            className={btnCls(currentPage === 1)}
            aria-label="Previous Page"
          >
            <Icon icon="mdi:chevron-left" width={14} />
          </button>
        </TooltipWrapper>

        <span className="px-3 text-[11px] font-medium text-[rgba(0,0,0,0.6)]">
          Page {currentPage} of {totalPages}
        </span>

        <TooltipWrapper tip="Next Page">
          <button
            type="button"
            onClick={() => onPageChange(currentPage + 1)}
            disabled={currentPage >= totalPages}
            className={btnCls(currentPage >= totalPages)}
            aria-label="Next Page"
          >
            <Icon icon="mdi:chevron-right" width={14} />
          </button>
        </TooltipWrapper>

        <TooltipWrapper tip="Last Page">
          <button
            type="button"
            onClick={() => onPageChange(totalPages)}
            disabled={currentPage >= totalPages}
            className={btnCls(currentPage >= totalPages)}
            aria-label="Last Page"
          >
            <Icon icon="mdi:chevron-double-right" width={14} />
          </button>
        </TooltipWrapper>
      </div>
    </div>
  );
};

export default Pagination;
