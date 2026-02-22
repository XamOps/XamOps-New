## 2025-05-14 - Accessible Interactive Components Pattern
**Learning:** Many interactive components in this application (like the account selector and toggles) were implemented using `div` elements with click handlers. This breaks keyboard accessibility and screen reader support. Using semantic `button` elements and appropriate ARIA roles (like `role="switch"` or `aria-haspopup`) is essential for accessibility. Additionally, the `vite-plugin-html-include` pattern means that improvements to shared fragments like `_sidebar.html` have an outsized positive impact on the overall application's UX.
**Action:** Always check if a clickable `div` can be replaced with a `button` and ensure shared fragments are prioritized for accessibility improvements.

## 2025-05-15 - [Password Visibility & Input Focus]
**Learning:** Adding interactive elements (like visibility toggles) inside input containers requires careful parent selection for focus animations. Using `.parentElement` is brittle if the DOM structure changes (e.g., adding a wrapper for positioning).
**Action:** Use `.closest('.form-group')` to find the target container for focus/blur animations to ensure resilience when nesting new interactive elements.

## 2025-05-15 - [Form Accessibility Micro-fixes]
**Learning:** Explicit association between labels and inputs (e.g., "Remember me" checkbox) significantly improves the clickable hit area and usability for screen readers. Icon-only buttons (like toggles and back arrows) MUST have `aria-label` to be meaningful.
**Action:** Always verify that every `<input>` has an associated `<label>` with a matching `id`/`for`, and every icon-only `<button>` has an `aria-label`.
