## 2025-05-14 - Accessible Interactive Components Pattern
**Learning:** Many interactive components in this application (like the account selector and toggles) were implemented using `div` elements with click handlers. This breaks keyboard accessibility and screen reader support. Using semantic `button` elements and appropriate ARIA roles (like `role="switch"` or `aria-haspopup`) is essential for accessibility. Additionally, the `vite-plugin-html-include` pattern means that improvements to shared fragments like `_sidebar.html` have an outsized positive impact on the overall application's UX.
**Action:** Always check if a clickable `div` can be replaced with a `button` and ensure shared fragments are prioritized for accessibility improvements.

## 2025-05-15 - Account Search Usability Pattern
**Learning:** Adding a "Clear Search" button to the account selection sidebar significantly improves usability for users managing many cloud accounts. When implemented with Alpine.js, it provides immediate visual feedback and reduces interaction friction. Combining this with ARIA labels and semantic buttons ensures that power-user features remain accessible.
**Action:** Always include a quick-clear mechanism for search inputs in multi-item selectors, especially in high-traffic shared fragments like sidebars.
