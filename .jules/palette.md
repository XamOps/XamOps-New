## 2025-05-14 - Accessible Interactive Components Pattern
**Learning:** Many interactive components in this application (like the account selector and toggles) were implemented using `div` elements with click handlers. This breaks keyboard accessibility and screen reader support. Using semantic `button` elements and appropriate ARIA roles (like `role="switch"` or `aria-haspopup`) is essential for accessibility. Additionally, the `vite-plugin-html-include` pattern means that improvements to shared fragments like `_sidebar.html` have an outsized positive impact on the overall application's UX.
**Action:** Always check if a clickable `div` can be replaced with a `button` and ensure shared fragments are prioritized for accessibility improvements.

## 2026-02-26 - Enhancing Search and Empty States for Better Discovery
**Learning:** Providing immediate feedback and easy ways to recover from a search that yields no results significantly improves the user experience. Adding a "Clear" button within the search input and an explicit empty state message with a "Clear search" call-to-action reduces friction and helps users navigate large lists of items like cloud accounts.
**Action:** Always implement empty state feedback and clear input mechanisms for searchable list components.
