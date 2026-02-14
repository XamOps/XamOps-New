## 2025-05-14 - Accessible Interactive Components Pattern
**Learning:** Many interactive components in this application (like the account selector and toggles) were implemented using `div` elements with click handlers. This breaks keyboard accessibility and screen reader support. Using semantic `button` elements and appropriate ARIA roles (like `role="switch"` or `aria-haspopup`) is essential for accessibility. Additionally, the `vite-plugin-html-include` pattern means that improvements to shared fragments like `_sidebar.html` have an outsized positive impact on the overall application's UX.
**Action:** Always check if a clickable `div` can be replaced with a `button` and ensure shared fragments are prioritized for accessibility improvements.

## 2025-02-14 - DOM structure sensitivity in form animations
**Learning:** Form input focus animations that rely on parent-traversal (like `this.parentElement`) can break when inputs are wrapped in utility containers (e.g., for relative-positioned icons).
**Action:** Use `.closest()` with appropriate selectors to target the intended parent container regardless of intermediate wrapping.
