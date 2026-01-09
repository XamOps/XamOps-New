# XamOps Usability Enhancements - Quick Reference Card

> **Quick copy-paste examples for common usability improvements**

## 🎯 Setup (Add to every HTML file)

```html
<head>
  <!-- Add these two lines -->
  <link rel="stylesheet" href="/src/usability-improvements.css">
  <script type="module" src="/src/usability-utils.js"></script>
</head>
```

## 🔘 Buttons

```html
<!-- Primary button -->
<button class="btn-primary-standard">
  <i class="fas fa-save"></i> Save Changes
</button>

<!-- Secondary button -->
<button class="btn-secondary-standard">
  Cancel
</button>

<!-- Small button -->
<button class="btn-primary-standard btn-sm-standard">
  Apply
</button>

<!-- Icon-only button -->
<button class="btn-icon-standard" data-tooltip="Refresh" data-tooltip-position="bottom">
  <i class="fas fa-sync-alt"></i>
</button>
```

## 📋 Copy to Clipboard

```html
<!-- Method 1: Auto-init with data attribute -->
<span data-copy="i-08a75236668011d47">i-08a75236668011d47</span>

<!-- Method 2: Alpine.js -->
<span class="copy-text" @click="copyText('value-here')">
  value-here
</span>

<!-- Alpine method -->
<script>
function myComponent() {
  return {
    async copyText(text) {
      const { copyToClipboard } = await import('./usability-utils.js');
      copyToClipboard(text);
    }
  }
}
</script>
```

## 💬 Tooltips

```html
<!-- Simple tooltip -->
<span data-tooltip="This is a helpful tip" data-tooltip-position="top">
  Hover me
</span>

<!-- Service abbreviation (auto-expands) -->
<span data-service="VPC">VPC</span>
<span data-service="ECS">ECS</span>
<span data-service="RDS">RDS</span>

<!-- Manual tooltip container -->
<div class="tooltip-container">
  <button>Multi-Account Mode</button>
  <span class="tooltip-text tooltip-right">
    View data across all accounts
  </span>
</div>
```

**Positions:** `tooltip-top`, `tooltip-bottom`, `tooltip-left`, `tooltip-right`

## 📊 Sortable Tables

```html
<!-- Add data-sortable to headers -->
<table id="myTable">
  <thead>
    <tr>
      <th data-sortable>Name</th>
      <th data-sortable>Cost</th>
      <th data-sortable>Date</th>
      <th>Action</th> <!-- not sortable -->
    </tr>
  </thead>
  <tbody>
    <!-- rows -->
  </tbody>
</table>

<script>
// Initialize sorting
import { initializeSortableTable } from './usability-utils.js';

initializeSortableTable(
  document.getElementById('myTable'),
  (columnIndex, direction) => {
    // Sort your data array
    this.items.sort((a, b) => {
      // your sort logic
    });
  }
);
</script>
```

## 📄 Pagination

```html
<!-- Add pagination container -->
<div id="pagination"></div>

<script>
import { createPagination, renderPaginationUI } from './usability-utils.js';

// Create pagination
const pagination = createPagination(
  allItems,           // your data array
  10,                 // items per page
  (items, page, totalPages) => {
    // Update displayed items
    this.displayedItems = items;
  }
);

// Render UI
renderPaginationUI(
  document.getElementById('pagination'),
  pagination
);

// Initial render
pagination.render();
</script>
```

## 🔔 Toast Notifications

```html
<script>
import { showToast } from './usability-utils.js';

// Success
showToast('Saved successfully!', 'success');

// Error
showToast('Something went wrong', 'error');

// Warning
showToast('Please review changes', 'warning');

// Info
showToast('Loading data...', 'info');

// Custom duration (default 3000ms)
showToast('Message here', 'success', 5000);
</script>
```

## ⏰ Timestamp Formatting

```html
<script>
import { formatRelativeTime, formatTimestamp } from './usability-utils.js';

// Relative time
formatRelativeTime('2026-01-09T10:00:00Z')  // "2 hours ago"

// Standard format with time
formatTimestamp('2026-01-09T10:00:00Z')     // "Jan 9, 2026, 10:00 AM"

// Date only
formatTimestamp('2026-01-09T10:00:00Z', false)  // "Jan 9, 2026"
</script>

<!-- In Alpine template -->
<span x-text="formatRelativeTime(lastUpdated)"></span>
```

## ⏳ Loading States

```html
<!-- Skeleton loader -->
<div x-show="isLoading" class="skeleton-loader h-96"></div>

<!-- Spinner -->
<div x-show="isLoading" class="spinner-loader"></div>

<!-- Full-screen overlay -->
<div x-show="isProcessing" class="loading-overlay">
  <div class="loading-overlay-content">
    <div class="spinner-loader"></div>
    <p class="mt-4 text-gray-700 font-semibold">Processing...</p>
  </div>
</div>
```

## ⚠️ Error & Success Messages

```html
<!-- Error -->
<div x-show="hasError" class="error-message">
  <i class="fas fa-exclamation-circle"></i>
  <span>Failed to load data. Please try again.</span>
</div>

<!-- Success -->
<div x-show="saved" class="success-message">
  <i class="fas fa-check-circle"></i>
  <span>Changes saved successfully!</span>
</div>

<!-- Warning -->
<div x-show="warning" class="warning-message">
  <i class="fas fa-exclamation-triangle"></i>
  <span>Some services may have delays.</span>
</div>
```

## 📭 Empty States

```html
<div x-show="items.length === 0" class="empty-state-improved">
  <div class="w-16 h-16 bg-gradient-to-br from-indigo-500 to-purple-600
              rounded-full flex items-center justify-center mx-auto mb-4">
    <i class="fas fa-inbox text-white text-2xl"></i>
  </div>
  <h3 class="text-2xl font-bold text-gray-800 mb-2">No Data Available</h3>
  <p class="text-gray-600 mb-4">No items match your current filters.</p>
  <button class="btn-secondary-standard" @click="clearFilters()">
    Clear Filters
  </button>
</div>
```

## 📱 Table Scroll Indicators

```html
<!-- Wrap your table -->
<div class="table-container" x-data="{ showHint: true }">
  <!-- Optional hint message -->
  <div x-show="showHint" @click="showHint = false" class="scroll-hint cursor-pointer">
    <i class="fas fa-arrows-alt-h"></i>
    <span>Scroll horizontally to view all columns</span>
    <i class="fas fa-times-circle text-xs ml-2"></i>
  </div>

  <!-- Your table with scroll detection -->
  <div class="table-enhanced"
       x-init="/* scroll indicator logic here */">
    <table>
      <!-- table content -->
    </table>
  </div>
</div>
```

## ⌨️ Keyboard Accessibility

```html
<!-- Make elements keyboard accessible -->
<div tabindex="0" role="button" aria-label="Click to expand"
     @keydown.enter="expand()" @keydown.space.prevent="expand()">
  Clickable content
</div>

<!-- Tab navigation -->
<button role="tab" aria-selected="true" aria-controls="panel-1">
  Tab 1
</button>

<!-- Skip to main content -->
<a href="#main-content" class="skip-to-main">
  Skip to main content
</a>
<main id="main-content">
  <!-- page content -->
</main>
```

## 🎨 CSS Classes Reference

### Interactive Elements
- `.copy-text` - Clickable text with copy icon
- `.tooltip-container` - Wrapper for tooltips
- `.sortable-header` - Sortable table header

### States
- `.skeleton-loader` - Loading skeleton
- `.spinner-loader` - Loading spinner
- `.loading-overlay` - Full-screen loading

### Messages
- `.error-message` - Error notification
- `.success-message` - Success notification
- `.warning-message` - Warning notification
- `.empty-state-improved` - No data state

### Buttons
- `.btn-primary-standard` - Primary button
- `.btn-secondary-standard` - Secondary button
- `.btn-sm-standard` - Small size modifier
- `.btn-icon-standard` - Icon-only button

### Tables
- `.table-enhanced` - Enhanced table wrapper
- `.table-container` - Table with scroll indicators
- `.scroll-hint` - Horizontal scroll hint

### Pagination
- `.pagination-container` - Pagination wrapper
- `.pagination-btn` - Pagination button
- `.pagination-info` - Page count display

## 🔧 Alpine.js Integration

```javascript
function myComponent() {
  return {
    async init() {
      // Import utilities
      const utils = await import('./usability-utils.js');

      // Initialize features
      utils.initializeTooltips();
      utils.initializeCopyElements();
      utils.initializeServiceTooltips();
    },

    // Helper methods
    async copy(text) {
      const { copyToClipboard } = await import('./usability-utils.js');
      copyToClipboard(text);
    },

    async notify(message, type = 'info') {
      const { showToast } = await import('./usability-utils.js');
      showToast(message, type);
    },

    formatTime(timestamp) {
      import('./usability-utils.js').then(({ formatRelativeTime }) => {
        return formatRelativeTime(timestamp);
      });
    }
  }
}
```

## 🎯 Common Patterns

### Pattern 1: Filterable Table with Pagination

```html
<div x-data="tableComponent()">
  <!-- Filter -->
  <select x-model="filter" @change="applyFilter()">
    <option value="all">All</option>
  </select>

  <!-- Table -->
  <table id="dataTable">
    <thead>
      <tr>
        <th data-sortable>Name</th>
        <th data-sortable>Value</th>
      </tr>
    </thead>
    <tbody>
      <template x-for="item in displayedItems" :key="item.id">
        <tr>
          <td x-text="item.name"></td>
          <td x-text="item.value"></td>
        </tr>
      </template>
    </tbody>
  </table>

  <!-- Pagination -->
  <div id="pagination"></div>
</div>
```

### Pattern 2: Form with Validation

```html
<form x-data="formComponent()" @submit.prevent="submit()">
  <input x-model="data.name" @blur="validate('name')">

  <!-- Error message -->
  <div x-show="errors.name" class="error-message mt-2">
    <i class="fas fa-exclamation-circle"></i>
    <span x-text="errors.name"></span>
  </div>

  <button type="submit" class="btn-primary-standard">
    Submit
  </button>
</form>
```

### Pattern 3: Card Grid with Tooltips

```html
<div class="grid grid-cols-1 md:grid-cols-3 gap-6">
  <template x-for="card in cards" :key="card.id">
    <div class="quota-card rounded-xl p-6"
         :data-tooltip="card.description"
         data-tooltip-position="top">
      <h3 class="text-lg font-bold" x-text="card.title"></h3>
      <p class="text-3xl font-bold mt-2" x-text="card.value"></p>
    </div>
  </template>
</div>
```

## 📚 Full Documentation

For complete details, see:
- [`USABILITY_IMPROVEMENTS_GUIDE.md`](USABILITY_IMPROVEMENTS_GUIDE.md) - Full implementation guide
- [`USABILITY_FIXES_SUMMARY.md`](USABILITY_FIXES_SUMMARY.md) - Issues resolved
- [`src/usability-improvements.css`](src/usability-improvements.css) - All CSS classes
- [`src/usability-utils.js`](src/usability-utils.js) - All JavaScript functions

---

**Version:** 1.0.0 | **Last Updated:** January 9, 2026
