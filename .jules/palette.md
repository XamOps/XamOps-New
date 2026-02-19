## 2025-05-14 - Accessible Interactive Components Pattern
**Learning:** Many interactive components in this application (like the account selector and toggles) were implemented using `div` elements with click handlers. This breaks keyboard accessibility and screen reader support. Using semantic `button` elements and appropriate ARIA roles (like `role="switch"` or `aria-haspopup`) is essential for accessibility. Additionally, the `vite-plugin-html-include` pattern means that improvements to shared fragments like `_sidebar.html` have an outsized positive impact on the overall application's UX.
**Action:** Always check if a clickable `div` can be replaced with a `button` and ensure shared fragments are prioritized for accessibility improvements.

## 2026-02-19 - [Password Visibility & Form Accessibility]
**Learning:** Adding password visibility toggles significantly improves usability, especially for complex passwords. However, standardizing the input wrapper structure (e.g., using relative containers and consistent padding) is crucial to prevent the toggle button from overlapping text or breaking existing focus animations that depend on parent selectors.
**Action:** Use a relative container for password inputs, add right padding (e.g., pr-10) to the input, and ensure the toggle button has clear ARIA labels and focus-visible styles.
