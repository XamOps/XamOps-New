## 2025-05-14 - Accessible Interactive Components Pattern
**Learning:** Many interactive components in this application (like the account selector and toggles) were implemented using `div` elements with click handlers. This breaks keyboard accessibility and screen reader support. Using semantic `button` elements and appropriate ARIA roles (like `role="switch"` or `aria-haspopup`) is essential for accessibility. Additionally, the `vite-plugin-html-include` pattern means that improvements to shared fragments like `_sidebar.html` have an outsized positive impact on the overall application's UX.
**Action:** Always check if a clickable `div` can be replaced with a `button` and ensure shared fragments are prioritized for accessibility improvements.

## 2026-02-25 - Form Accessibility and Focus Logic
**Learning:** Authentication forms in 'index.html' must use semantic <label> elements; relying solely on placeholders is an accessibility anti-pattern. Furthermore, when adding interactive elements like password toggles within input containers, DOM-traversal-based focus animations (e.g., using .parentElement) can break. Using .closest() ensures focus logic compatibility across varying input wrapper structures.
**Action:** Always associate inputs with <label> tags and use .closest() for DOM traversal in event listeners to maintain robust UI interactions.
