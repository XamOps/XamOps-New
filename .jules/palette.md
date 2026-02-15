## 2025-05-14 - Accessible Interactive Components Pattern
**Learning:** Many interactive components in this application (like the account selector and toggles) were implemented using `div` elements with click handlers. This breaks keyboard accessibility and screen reader support. Using semantic `button` elements and appropriate ARIA roles (like `role="switch"` or `aria-haspopup`) is essential for accessibility. Additionally, the `vite-plugin-html-include` pattern means that improvements to shared fragments like `_sidebar.html` have an outsized positive impact on the overall application's UX.
**Action:** Always check if a clickable `div` can be replaced with a `button` and ensure shared fragments are prioritized for accessibility improvements.

## 2026-02-25 - Resilient Input Focus Animations
**Learning:** Form input focus animations in `index.html` were sensitive to DOM structure, relying on `.parentElement` to find the target container. Wrapping inputs (e.g., for password visibility toggles) breaks this. Using `.closest()` to target the parent container ensures compatibility and resilience when the DOM hierarchy changes.
**Action:** Use `.closest()` instead of `.parentElement` for DOM traversal in scripts that manage form input states.
