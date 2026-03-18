## 2025-03-18 - [Clear Search in Account Selector]
**Learning:** Restoring "Clear search" functionality from legacy templates into React components maintains user expectations and improves usability, especially in long lists. Using unique ARIA labels for similar-acting buttons (input clear vs. empty state clear) avoids screen reader confusion.
**Action:** Always check for missing search-reset affordances when migrating or auditing search inputs. Ensure icon-only buttons have descriptive, unique `aria-label` attributes.
