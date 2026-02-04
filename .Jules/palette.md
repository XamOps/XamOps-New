## 2025-05-15 - Mixed Scripting Environments
**Learning:** This repository utilizes different scripting approaches across pages: `index.html` uses vanilla JavaScript for form logic and animations, while `dashboard.html` leverages Alpine.js for state management and interactivity.
**Action:** Always inspect the page's `<head>` and element attributes (e.g., `x-data`) before adding interactive features. Use vanilla JS on pages without a framework to avoid adding unnecessary dependencies, but use the existing framework on pages where it's already active to maintain consistency.
