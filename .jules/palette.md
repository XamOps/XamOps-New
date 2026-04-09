## 2025-05-15 - [Aria-label Shadowing & Dependency Boundaries]
**Learning:** Adding an `aria-label` to an element with visible text (like a profile button with a name) can shadow the text for screen readers, causing information loss. Also, even if a dependency seems missing for a specific page, adding it might violate the "no new dependencies" constraint of a micro-UX task.
**Action:** Avoid `aria-label` when visible text is present and sufficient. Investigate existing dependencies or alternative solutions before considering adding new ones.
