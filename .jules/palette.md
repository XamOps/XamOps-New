## 2025-05-14 - Accessible Interactive Components Pattern
**Learning:** Many interactive components in this application (like the account selector and toggles) were implemented using `div` elements with click handlers. This breaks keyboard accessibility and screen reader support. Using semantic `button` elements and appropriate ARIA roles (like `role="switch"` or `aria-haspopup`) is essential for accessibility. Additionally, the `vite-plugin-html-include` pattern means that improvements to shared fragments like `_sidebar.html` have an outsized positive impact on the overall application's UX.
**Action:** Always check if a clickable `div` can be replaced with a `button` and ensure shared fragments are prioritized for accessibility improvements.

## 2026-02-18 - [Password Visibility Toggles]
**Learning:** Form input focus animations in 'index.html' depend on targeting parent containers; using '.closest()' in event listeners and standardizing input wrappers across forms (e.g., Login vs Reset Password) ensures focus logic compatibility when adding nested interactive elements like password visibility toggles.
**Action:** Always wrap inputs in relative containers when adding absolute-positioned toggles, and use 'pr-10' (or equivalent) to prevent text overlap.
