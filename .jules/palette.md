## 2025-05-02 - [Line Limit vs. Accessibility Wrappers]
**Learning:** Adding accessibility wrappers (like `TooltipWrapper`) to multiple adjacent elements can cause significant re-indentation, easily exceeding the 50-line PR limit.
**Action:** Refactor repetitive elements into a local sub-component within the same file to keep the diff clean and stay within the line limit while maintaining high accessibility standards.

## 2025-05-02 - [Repository Hygiene in Frontend Tasks]
**Learning:** Running `pnpm install` in a directory where `pnpm-lock.yaml` is not tracked may generate a new lockfile.
**Action:** Always check `git status` before committing to ensure auxiliary files like `pnpm-lock.yaml` aren't accidentally included in a micro-UX PR.
