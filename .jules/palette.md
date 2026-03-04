## 2025-05-14 - Accessible Interactive Components Pattern
**Learning:** Many interactive components in this application (like the account selector and toggles) were implemented using `div` elements with click handlers. This breaks keyboard accessibility and screen reader support. Using semantic `button` elements and appropriate ARIA roles (like `role="switch"` or `aria-haspopup`) is essential for accessibility. Additionally, the `vite-plugin-html-include` pattern means that improvements to shared fragments like `_sidebar.html` have an outsized positive impact on the overall application's UX.
**Action:** Always check if a clickable `div` can be replaced with a `button` and ensure shared fragments are prioritized for accessibility improvements.

## 2025-05-15 - Resilient DOM Traversal for Input Effects
**Learning:** When adding interactive elements like password visibility toggles that require nesting inputs in new wrapper elements (e.g., `relative` containers), existing DOM-traversal-based scripts (like those using `parentElement`) can break. Using `.closest()` in event listeners provides a more resilient way to target container elements for animations or state changes, regardless of the nesting depth.
**Action:** Use `.closest()` instead of `parentElement` when writing scripts that interact with the parent containers of form inputs to ensure compatibility with nested UI enhancements.

## 2025-05-16 - Accessible Search & Selection Patterns
**Learning:** For components like the account selector, a standard search-and-filter UI requires more than just click handlers. A truly accessible pattern includes `role="listbox"` for the container, `role="option"` for items with keyboard handlers (Enter/Space), and an `aria-live="polite"` region for empty states to inform screen reader users when results are filtered out. Additionally, explicitly adding `type="button"` to interactive elements in Alpine.js templates prevents unexpected form submissions and ensures consistent behavior.
**Action:** Implement `role="listbox"`/`option"` patterns for all selection lists and use `aria-live` for dynamic feedback regions.
