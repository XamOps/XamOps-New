## 2025-05-23 - Robust Input Focus Handling with Wrapped Elements
**Learning:** In this application, form input focus animations (e.g., in `index.html`) are sensitive to DOM structure. When wrapping inputs for UI enhancements like a password visibility toggle, static sibling selectors (like `input ~ label`) or simple `parentElement` logic can break. Using `.closest()` or more resilient selectors ensures these animations continue to work after layout changes.
**Action:** When adding wrappers to existing inputs, always verify that associated JavaScript/CSS selectors for focus states are updated to use robust traversal methods like `.closest()`.

## 2025-05-23 - Mixed Scripting Patterns
**Learning:** The frontend uses a mix of vanilla JavaScript (in `index.html`) and Alpine.js (in `dashboard.html`).
**Action:** Always check the existing scripting pattern of a page before implementing new interactivity to maintain consistency.
