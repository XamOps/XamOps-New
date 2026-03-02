## 2025-05-14 - Accessible Interactive Components Pattern
**Learning:** Many interactive components in this application (like the account selector and toggles) were implemented using `div` elements with click handlers. This breaks keyboard accessibility and screen reader support. Using semantic `button` elements and appropriate ARIA roles (like `role="switch"` or `aria-haspopup`) is essential for accessibility. Additionally, the `vite-plugin-html-include` pattern means that improvements to shared fragments like `_sidebar.html` have an outsized positive impact on the overall application's UX.
**Action:** Always check if a clickable `div` can be replaced with a `button` and ensure shared fragments are prioritized for accessibility improvements.

## 2025-05-15 - Resilient DOM Traversal for Input Effects
**Learning:** When adding interactive elements like password visibility toggles that require nesting inputs in new wrapper elements (e.g., `relative` containers), existing DOM-traversal-based scripts (like those using `parentElement`) can break. Using `.closest()` in event listeners provides a more resilient way to target container elements for animations or state changes, regardless of the nesting depth.
**Action:** Use `.closest()` instead of `parentElement` when writing scripts that interact with the parent containers of form inputs to ensure compatibility with nested UI enhancements.

## 2025-05-16 - Production Build HTML Parsing Constraints
**Learning:** Using complex JavaScript expressions (especially template literals or nested quotes) directly within Alpine.js HTML attributes like `x-data` or `:class` can cause `missing-whitespace-between-attributes` errors in the Vite/Parse5 HTML parser during production builds. This is particularly prevalent in this project's architecture where fragments are included across many pages.
**Action:** Encapsulate complex Alpine.js logic into a named component using `Alpine.data` within a separate `<script>` tag. This ensures stable production builds and cleaner code separation.
