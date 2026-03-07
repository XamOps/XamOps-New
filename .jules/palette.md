## 2025-05-14 - Accessible Interactive Components Pattern
**Learning:** Many interactive components in this application (like the account selector and toggles) were implemented using `div` elements with click handlers. This breaks keyboard accessibility and screen reader support. Using semantic `button` elements and appropriate ARIA roles (like `role="switch"` or `aria-haspopup`) is essential for accessibility. Additionally, the `vite-plugin-html-include` pattern means that improvements to shared fragments like `_sidebar.html` have an outsized positive impact on the overall application's UX.
**Action:** Always check if a clickable `div` can be replaced with a `button` and ensure shared fragments are prioritized for accessibility improvements.

## 2025-05-15 - Resilient DOM Traversal for Input Effects
**Learning:** When adding interactive elements like password visibility toggles that require nesting inputs in new wrapper elements (e.g., `relative` containers), existing DOM-traversal-based scripts (like those using `parentElement`) can break. Using `.closest()` in event listeners provides a more resilient way to target container elements for animations or state changes, regardless of the nesting depth.
**Action:** Use `.closest()` instead of `parentElement` when writing scripts that interact with the parent containers of form inputs to ensure compatibility with nested UI enhancements.

## 2026-03-07 - ARIA Semantics for Interactive Lists
**Learning:** Adding `role="listbox"` to a scroll container that includes non-option elements (like headers or "No results" buttons) violates ARIA semantics and can break screen reader navigation. For dynamic search results with headers, it's often better to rely on standard semantic elements or `aria-live="polite"` for empty state announcements rather than forcing a strict `listbox` role.
**Action:** Avoid `role="listbox"` on containers that are not strictly collections of `option` elements.

## 2026-03-07 - Unique Accessible Names for Redundant Controls
**Learning:** Multiple "Clear" buttons in the same view (e.g., one in an input field and one in an empty state link) should have unique and descriptive `aria-label` values to avoid confusion for screen reader users and to satisfy strict locator requirements in testing tools like Playwright.
**Action:** Always provide specific context in ARIA labels for buttons that perform similar actions in different contexts (e.g., "Clear account search" vs "Clear search and show all accounts").
