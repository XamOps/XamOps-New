/**
 * XamOps Dashboard - Usability Utility Functions
 * This file contains reusable JavaScript functions for common usability features
 * across all pages of the dashboard.
 */

/**
 * Click-to-copy functionality for text elements
 * @param {string} text - The text to copy to clipboard
 * @param {HTMLElement} element - The element that triggered the copy (optional)
 */
export function copyToClipboard(text, element = null) {
  navigator.clipboard.writeText(text).then(() => {
    if (element) {
      // Add visual feedback
      element.classList.add('copied');

      // Show success message
      showToast('Copied to clipboard!', 'success');

      // Remove class after animation
      setTimeout(() => {
        element.classList.remove('copied');
      }, 2000);
    }
  }).catch(err => {
    console.error('Failed to copy:', err);
    showToast('Failed to copy to clipboard', 'error');
  });
}

/**
 * Format timestamp to relative time (e.g., "2 minutes ago")
 * @param {string|Date} timestamp - The timestamp to format
 * @returns {string} Formatted relative time string
 */
export function formatRelativeTime(timestamp) {
  const date = new Date(timestamp);
  const now = new Date();
  const seconds = Math.floor((now - date) / 1000);

  const intervals = {
    year: 31536000,
    month: 2592000,
    week: 604800,
    day: 86400,
    hour: 3600,
    minute: 60,
    second: 1
  };

  for (const [unit, secondsInUnit] of Object.entries(intervals)) {
    const interval = Math.floor(seconds / secondsInUnit);
    if (interval >= 1) {
      return `${interval} ${unit}${interval > 1 ? 's' : ''} ago`;
    }
  }

  return 'Just now';
}

/**
 * Format timestamp to standard format
 * @param {string|Date} timestamp - The timestamp to format
 * @param {boolean} includeTime - Whether to include time (default: true)
 * @returns {string} Formatted timestamp
 */
export function formatTimestamp(timestamp, includeTime = true) {
  const date = new Date(timestamp);

  const options = {
    year: 'numeric',
    month: 'short',
    day: 'numeric'
  };

  if (includeTime) {
    options.hour = '2-digit';
    options.minute = '2-digit';
  }

  return date.toLocaleDateString('en-US', options);
}

/**
 * Show toast notification
 * @param {string} message - The message to display
 * @param {string} type - Type of toast: 'success', 'error', 'warning', 'info'
 * @param {number} duration - Duration in milliseconds (default: 3000)
 */
export function showToast(message, type = 'info', duration = 3000) {
  // Create toast element
  const toast = document.createElement('div');
  toast.className = `toast-notification toast-${type}`;

  const iconMap = {
    success: 'fa-check-circle',
    error: 'fa-times-circle',
    warning: 'fa-exclamation-triangle',
    info: 'fa-info-circle'
  };

  toast.innerHTML = `
    <i class="fas ${iconMap[type]} toast-icon"></i>
    <span class="toast-message">${message}</span>
    <button class="toast-close" onclick="this.parentElement.remove()">
      <i class="fas fa-times"></i>
    </button>
  `;

  // Add to page
  let container = document.querySelector('.toast-container');
  if (!container) {
    container = document.createElement('div');
    container.className = 'toast-container';
    document.body.appendChild(container);
  }

  container.appendChild(toast);

  // Trigger animation
  setTimeout(() => toast.classList.add('show'), 10);

  // Auto remove
  setTimeout(() => {
    toast.classList.remove('show');
    setTimeout(() => toast.remove(), 300);
  }, duration);
}

/**
 * Initialize tooltips for elements with data-tooltip attribute
 */
export function initializeTooltips() {
  document.querySelectorAll('[data-tooltip]').forEach(element => {
    const tooltipText = element.getAttribute('data-tooltip');
    const tooltipPosition = element.getAttribute('data-tooltip-position') || 'top';

    // Create tooltip element
    const tooltip = document.createElement('span');
    tooltip.className = `tooltip-text tooltip-${tooltipPosition}`;
    tooltip.textContent = tooltipText;

    // Wrap element if not already wrapped
    if (!element.parentElement.classList.contains('tooltip-container')) {
      const wrapper = document.createElement('span');
      wrapper.className = 'tooltip-container';
      element.parentNode.insertBefore(wrapper, element);
      wrapper.appendChild(element);
      wrapper.appendChild(tooltip);
    }
  });
}

/**
 * Initialize sortable table headers
 * @param {HTMLTableElement} table - The table element
 * @param {Function} sortCallback - Callback function for sorting: (columnIndex, direction) => void
 */
export function initializeSortableTable(table, sortCallback) {
  const headers = table.querySelectorAll('thead th[data-sortable]');

  headers.forEach((header, index) => {
    header.classList.add('sortable-header');
    let currentSort = null; // null, 'asc', or 'desc'

    header.addEventListener('click', () => {
      // Reset other headers
      headers.forEach(h => {
        if (h !== header) {
          h.classList.remove('sort-asc', 'sort-desc');
        }
      });

      // Toggle sort direction
      if (currentSort === null || currentSort === 'desc') {
        currentSort = 'asc';
        header.classList.remove('sort-desc');
        header.classList.add('sort-asc');
      } else {
        currentSort = 'desc';
        header.classList.remove('sort-asc');
        header.classList.add('sort-desc');
      }

      // Call callback
      sortCallback(index, currentSort);
    });

    // Make keyboard accessible
    header.setAttribute('tabindex', '0');
    header.setAttribute('role', 'button');
    header.setAttribute('aria-label', `Sort by ${header.textContent}`);

    header.addEventListener('keydown', (e) => {
      if (e.key === 'Enter' || e.key === ' ') {
        e.preventDefault();
        header.click();
      }
    });
  });
}

/**
 * Initialize pagination for a list
 * @param {Array} items - Array of items to paginate
 * @param {number} itemsPerPage - Number of items per page
 * @param {Function} renderCallback - Callback to render items: (paginatedItems, currentPage, totalPages) => void
 * @returns {Object} Pagination controls
 */
export function createPagination(items, itemsPerPage = 10, renderCallback) {
  let currentPage = 1;
  const totalPages = Math.ceil(items.length / itemsPerPage);

  const render = () => {
    const start = (currentPage - 1) * itemsPerPage;
    const end = start + itemsPerPage;
    const paginatedItems = items.slice(start, end);
    renderCallback(paginatedItems, currentPage, totalPages);
  };

  return {
    goToPage(page) {
      if (page >= 1 && page <= totalPages) {
        currentPage = page;
        render();
      }
    },
    nextPage() {
      this.goToPage(currentPage + 1);
    },
    prevPage() {
      this.goToPage(currentPage - 1);
    },
    getCurrentPage() {
      return currentPage;
    },
    getTotalPages() {
      return totalPages;
    },
    render
  };
}

/**
 * Create pagination UI component
 * @param {HTMLElement} container - Container element for pagination
 * @param {Object} pagination - Pagination object from createPagination
 */
export function renderPaginationUI(container, pagination) {
  const currentPage = pagination.getCurrentPage();
  const totalPages = pagination.getTotalPages();

  container.innerHTML = `
    <div class="pagination-container">
      <div class="pagination-info">
        Page ${currentPage} of ${totalPages}
      </div>
      <div class="pagination-buttons">
        <button class="pagination-btn"
          ${currentPage === 1 ? 'disabled' : ''}
          data-action="first">
          <i class="fas fa-angle-double-left"></i>
        </button>
        <button class="pagination-btn"
          ${currentPage === 1 ? 'disabled' : ''}
          data-action="prev">
          <i class="fas fa-angle-left"></i> Prev
        </button>
        ${renderPageNumbers(currentPage, totalPages)}
        <button class="pagination-btn"
          ${currentPage === totalPages ? 'disabled' : ''}
          data-action="next">
          Next <i class="fas fa-angle-right"></i>
        </button>
        <button class="pagination-btn"
          ${currentPage === totalPages ? 'disabled' : ''}
          data-action="last">
          <i class="fas fa-angle-double-right"></i>
        </button>
      </div>
    </div>
  `;

  // Add event listeners
  container.querySelectorAll('[data-action]').forEach(btn => {
    btn.addEventListener('click', () => {
      const action = btn.getAttribute('data-action');
      switch (action) {
        case 'first':
          pagination.goToPage(1);
          break;
        case 'prev':
          pagination.prevPage();
          break;
        case 'next':
          pagination.nextPage();
          break;
        case 'last':
          pagination.goToPage(totalPages);
          break;
        default:
          pagination.goToPage(parseInt(action));
      }
      renderPaginationUI(container, pagination);
    });
  });
}

/**
 * Helper function to render page number buttons
 */
function renderPageNumbers(currentPage, totalPages) {
  let pages = [];
  const maxVisible = 5;

  if (totalPages <= maxVisible) {
    for (let i = 1; i <= totalPages; i++) {
      pages.push(i);
    }
  } else {
    if (currentPage <= 3) {
      pages = [1, 2, 3, 4, '...', totalPages];
    } else if (currentPage >= totalPages - 2) {
      pages = [1, '...', totalPages - 3, totalPages - 2, totalPages - 1, totalPages];
    } else {
      pages = [1, '...', currentPage - 1, currentPage, currentPage + 1, '...', totalPages];
    }
  }

  return pages.map(page => {
    if (page === '...') {
      return '<span class="pagination-ellipsis">...</span>';
    }
    return `
      <button class="pagination-btn ${page === currentPage ? 'active' : ''}"
        data-action="${page}">
        ${page}
      </button>
    `;
  }).join('');
}

/**
 * Initialize click-to-copy for elements with data-copy attribute
 */
export function initializeCopyElements() {
  document.querySelectorAll('[data-copy]').forEach(element => {
    element.classList.add('copy-text');
    element.setAttribute('role', 'button');
    element.setAttribute('tabindex', '0');
    element.setAttribute('aria-label', 'Click to copy');

    const copyText = element.getAttribute('data-copy') || element.textContent;

    const handleCopy = () => {
      copyToClipboard(copyText, element);
    };

    element.addEventListener('click', handleCopy);
    element.addEventListener('keydown', (e) => {
      if (e.key === 'Enter' || e.key === ' ') {
        e.preventDefault();
        handleCopy();
      }
    });
  });
}

/**
 * Detect if table is scrollable and add indicators
 * @param {HTMLElement} tableContainer - The table container element
 */
export function initializeTableScrollIndicators(tableContainer) {
  const table = tableContainer.querySelector('table');
  if (!table) return;

  const updateScrollIndicators = () => {
    const scrollLeft = tableContainer.scrollLeft;
    const maxScroll = tableContainer.scrollWidth - tableContainer.clientWidth;

    // Add/remove scroll indicator classes
    if (scrollLeft > 10) {
      tableContainer.classList.add('scroll-left');
    } else {
      tableContainer.classList.remove('scroll-left');
    }

    if (scrollLeft < maxScroll - 10) {
      tableContainer.classList.add('scroll-right');
    } else {
      tableContainer.classList.remove('scroll-right');
    }
  };

  // Initialize
  setTimeout(updateScrollIndicators, 100);

  // Update on scroll
  tableContainer.addEventListener('scroll', updateScrollIndicators);

  // Update on window resize
  window.addEventListener('resize', updateScrollIndicators);
}

/**
 * Enhanced service name mapping with tooltips
 */
export const serviceNameMap = {
  'VPC': 'Virtual Private Cloud',
  'ECS': 'Elastic Container Service',
  'EKS': 'Elastic Kubernetes Service',
  'RDS': 'Relational Database Service',
  'EC2': 'Elastic Compute Cloud',
  'S3': 'Simple Storage Service',
  'IAM': 'Identity and Access Management',
  'EBS': 'Elastic Block Store',
  'ALB': 'Application Load Balancer',
  'NLB': 'Network Load Balancer',
  'CloudFront': 'Content Delivery Network',
  'Route53': 'Domain Name System',
  'Lambda': 'Serverless Compute',
  'DynamoDB': 'NoSQL Database',
  'SQS': 'Simple Queue Service',
  'SNS': 'Simple Notification Service',
  'CloudWatch': 'Monitoring and Observability',
  'CloudTrail': 'AWS Audit and Compliance',
  'KMS': 'Key Management Service',
  'Secrets Manager': 'Secrets Management',
  'ElastiCache': 'In-Memory Caching',
  'CloudFormation': 'Infrastructure as Code',
  'CodePipeline': 'Continuous Delivery',
  'CodeBuild': 'Build Service',
  'CodeDeploy': 'Deployment Service'
};

/**
 * Get full service name from abbreviation
 * @param {string} abbreviation - Service abbreviation
 * @returns {string} Full service name
 */
export function getServiceFullName(abbreviation) {
  return serviceNameMap[abbreviation] || abbreviation;
}

/**
 * Initialize service name tooltips
 */
export function initializeServiceTooltips() {
  document.querySelectorAll('[data-service]').forEach(element => {
    const service = element.getAttribute('data-service');
    const fullName = getServiceFullName(service);

    if (fullName !== service) {
      element.setAttribute('data-tooltip', fullName);
      element.setAttribute('data-tooltip-position', 'top');
      element.classList.add('service-abbreviation');
    }
  });

  initializeTooltips();
}

/**
 * Add keyboard navigation to tabs
 * @param {string} tabContainerSelector - Selector for tab container
 */
export function initializeKeyboardTabNavigation(tabContainerSelector) {
  const tabButtons = document.querySelectorAll(`${tabContainerSelector} button[role="tab"], ${tabContainerSelector} .tab-button`);

  tabButtons.forEach((tab, index) => {
    tab.setAttribute('tabindex', index === 0 ? '0' : '-1');

    tab.addEventListener('keydown', (e) => {
      let targetTab = null;

      if (e.key === 'ArrowRight') {
        e.preventDefault();
        targetTab = tabButtons[index + 1] || tabButtons[0];
      } else if (e.key === 'ArrowLeft') {
        e.preventDefault();
        targetTab = tabButtons[index - 1] || tabButtons[tabButtons.length - 1];
      } else if (e.key === 'Home') {
        e.preventDefault();
        targetTab = tabButtons[0];
      } else if (e.key === 'End') {
        e.preventDefault();
        targetTab = tabButtons[tabButtons.length - 1];
      }

      if (targetTab) {
        tabButtons.forEach(t => t.setAttribute('tabindex', '-1'));
        targetTab.setAttribute('tabindex', '0');
        targetTab.focus();
        targetTab.click();
      }
    });
  });
}

/**
 * Initialize all usability enhancements
 */
export function initializeUsabilityEnhancements() {
  // Initialize tooltips
  initializeTooltips();

  // Initialize copy elements
  initializeCopyElements();

  // Initialize service tooltips
  initializeServiceTooltips();

  // Initialize table scroll indicators
  document.querySelectorAll('.table-enhanced, .table-container').forEach(container => {
    initializeTableScrollIndicators(container);
  });

  console.log('✅ Usability enhancements initialized');
}

// Auto-initialize when DOM is ready
if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', initializeUsabilityEnhancements);
} else {
  initializeUsabilityEnhancements();
}
