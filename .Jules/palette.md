## 2025-05-14 - Accessibility Gaps in Reusable Components
**Learning:** Reusable HTML fragments (like `_sidebar.html` included via `vite-plugin-html-include`) often harbor accessibility issues that propagate across the entire application. Specifically, search inputs and navigation controls in these fragments were missing ARIA labels.
**Action:** Always audit included HTML fragments and partials during accessibility reviews, as they provide high-leverage points for UX improvements.
